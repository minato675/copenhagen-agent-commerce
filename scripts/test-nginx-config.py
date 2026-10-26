#!/usr/bin/env python3
"""Exercise real HTTP/TLS nginx routing with disposable certificates and backends."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
DOMAIN = 'j15a207.p.ssafy.io'
NGINX = 'docker.io/library/nginx:1.28-alpine'
CURL = 'docker.io/curlimages/curl:8.16.0'


def run(*args, **kwargs):
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed ({result.returncode}): {result.stderr[-3000:]}')
    return result.stdout.strip()


def main():
    prefix = 'copenhagen-nginx-' + uuid.uuid4().hex[:12]
    containers = []
    images = []
    network = prefix
    try:
        with tempfile.TemporaryDirectory(prefix=prefix) as directory:
            work = Path(directory)
            (work / 'empty.env').touch()
            compose = ('docker', 'compose', '--env-file', str(work / 'empty.env'),
                       '--file', str(ROOT / 'docker-compose.yml'))
            plain = json.loads(run(*compose, 'config', '--format', 'json'))['services']['nginx']
            tls = json.loads(run(*compose, '--file', str(ROOT / 'infra/docker/https.override.yml'),
                                 'config', '--format', 'json'))['services']['nginx']
            assert {p['target'] for p in plain['ports']} == {80}
            assert len(plain['volumes']) == 1 and plain['volumes'][0]['source'].endswith('/nginx.conf')
            assert {p['target'] for p in tls['ports']} == {80, 443}
            mounts = {v['target']: v for v in tls['volumes']}
            assert mounts['/etc/nginx/nginx.conf']['source'].endswith('/nginx.https.conf')
            assert mounts['/etc/letsencrypt']['read_only']
            assert mounts['/var/www/certbot']['read_only']
            print('PASS Compose HTTP default and HTTPS override', flush=True)

            certs = work / 'certs' / 'live' / DOMAIN
            certs.mkdir(parents=True)
            run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                '-subj', '/CN=' + DOMAIN, '-addext', 'subjectAltName=DNS:' + DOMAIN,
                '-keyout', str(certs / 'privkey.pem'), '-out', str(certs / 'fullchain.pem'))
            challenge = work / 'www/.well-known/acme-challenge'
            challenge.mkdir(parents=True)
            (challenge / 'probe').write_text('acme-ready')
            for name in ('nginx.conf', 'nginx.https.conf'):
                shutil.copyfile(ROOT / 'infra/nginx' / name, work / name)
            (work / 'backend.conf').write_text('''events {}
http {
  server {
    listen 8080;
    default_type application/json;
    location /mcp {
      return 401 '{"code":"AUTHENTICATION_REQUIRED","method":"$request_method","uri":"$request_uri","proto":"$http_x_forwarded_proto","backend":"$hostname"}';
    }
    # Proxying reads the body before the echo server inspects it. A direct return
    # does not read request bodies, so it cannot verify forwarding by the ingress.
    location = /api/pg/__body_probe {
      proxy_pass http://127.0.0.1:8081;
      proxy_set_header Host $http_host;
      proxy_set_header X-Probe-Body $request_body;
    }
    location / {
      return 200 '{"method":"$request_method","uri":"$request_uri","proto":"$http_x_forwarded_proto","backend":"$hostname"}';
    }
  }
  server {
    listen 8081;
    default_type application/json;
    location / {
      return 200 '{"method":"$request_method","uri":"$request_uri","proto":"$http_x_forwarded_proto","backend":"$hostname","host":"$http_host","forwarded_for":"$http_x_forwarded_for","request_id":"$http_x_request_id","idempotency_key":"$http_idempotency_key","content_type":"$http_content_type","body":$http_x_probe_body}';
    }
  }
}
''')
            (work / 'Dockerfile').write_text(f'''FROM {NGINX}
COPY nginx.conf nginx.https.conf backend.conf /fixtures/
COPY certs /etc/letsencrypt
COPY www /var/www/certbot
''')
            fixture = prefix + ':fixture'
            images.append(fixture)
            run('docker', 'build', '--tag', fixture, str(work))
            run('docker', 'pull', CURL)
            run('docker', 'network', 'create', network)

            def start(role, config, aliases):
                name = prefix + '-' + role
                containers.append(name)
                options = [part for alias in aliases for part in ('--network-alias', alias)]
                run('docker', 'run', '-d', '--name', name, '--hostname', role, '--network', network, *options,
                    fixture, 'nginx', '-c', '/fixtures/' + config, '-g', 'daemon off;')
                return name

            backends = [start(role, 'backend.conf', [role])
                        for role in ('core-service', 'mcp-server', 'pay-service', 'pg-mock')]
            http = start('http', 'nginx.conf', ['plain'])
            https = start('https', 'nginx.https.conf', [DOMAIN])
            for name, config in ((http, 'nginx.conf'), (https, 'nginx.https.conf')):
                run('docker', 'exec', name, 'nginx', '-t', '-c', '/fixtures/' + config)
            print('PASS HTTP/TLS nginx configuration syntax', flush=True)
            # The curl image cannot see the client's filesystem on a remote DinD daemon.
            # Copy only the disposable public certificate into a reusable probe container.
            probe = prefix + '-curl'
            containers.append(probe)
            run('docker', 'run', '-d', '--name', probe, '--network', network,
                '--entrypoint', 'sh', CURL, '-c', 'while :; do sleep 3600; done')
            run('docker', 'cp', str(certs / 'fullchain.pem'), probe + ':/tmp/ca.pem')

            def request(url, method='GET', data=None, headers=()):
                options = [part for header in headers for part in ('-H', header)]
                if data is not None:
                    options.extend(('--data-binary', data))
                output = run('docker', 'exec', probe, 'curl', '-sS', '--connect-timeout', '2',
                             '--max-time', '5', '--cacert', '/tmp/ca.pem', '-X', method,
                             '-w', '\n%{http_code}\n%{redirect_url}', *options, url)
                parts = output.split('\n')
                if parts[-1].isdigit():
                    return '\n'.join(parts[:-1]), int(parts[-1]), ''
                return '\n'.join(parts[:-2]), int(parts[-2]), parts[-1]

            for attempt in range(20):
                try:
                    if request('https://' + DOMAIN + '/mcp')[1] == 401:
                        break
                except RuntimeError:
                    pass
                time.sleep(1)
            else:
                raise AssertionError('TLS nginx did not become ready')

            for scheme, host in (('http', 'plain'), ('https', DOMAIN)):
                for path, upstream in (
                    ('/mcp?test=1', 'mcp-server'),
                    ('/.well-known/oauth-protected-resource', 'mcp-server'),
                    ('/.well-known/oauth-protected-resource/mcp', 'mcp-server'),
                    ('/.well-known/oauth-authorization-server', 'core-service'),
                    ('/.well-known/jwks.json', 'core-service'),
                    ('/oauth/authorize?client_id=chatgpt-plugin&state=probe', 'core-service'),
                    ('/oauth/authorize/test-request/decision', 'core-service'),
                    ('/oauth/token', 'core-service'),
                    ('/api/core/probe', 'core-service'), ('/api/pay/probe', 'pay-service'),
                    ('/api/pg/v1/payment-methods/tokenize', 'pg-mock'),
                    ('/api/pg/', 'pg-mock'),
                    ('/api/pg/v1/payment-methods/tokenize?probe=1&label=pg%2Fmock', 'pg-mock'),
                    ('/api/pg/v1/payment-methods/other', 'pg-mock'),
                    ('/api/pg/v2/probe?test=1', 'pg-mock'),
                    ('/api/pg/internal/v1/charges', 'pg-mock'),
                    ('/merchant/v1/probe', 'pay-service'), ('/.well-known/ucp', 'core-service')):
                    for method in ('GET', 'POST'):
                        body, status, _ = request(f'{scheme}://{host}{path}', method)
                        assert status == (401 if path.startswith('/mcp') else 200), (scheme, path, status)
                        data = json.loads(body)
                        assert data == dict(data, uri=path, method=method, proto=scheme, backend=upstream)
                for path in ('/internal', '/internal/v1/catalog/products', '/v1/payment-methods/tokenize',
                             '/internal/v1/charges', '/api/pgx/probe', '/api/pg-other/probe', '/unknown',
                             '/.well-known/unknown', '/.well-known/jwks.json/extra',
                             '/.well-known/oauth-protected-resource/extra', '/oauth/unknown',
                             '/oauth/token/extra'):
                    for method in ('GET', 'POST'):
                        assert request(f'{scheme}://{host}{path}', method)[1] == 404, (scheme, path, method)

                path = '/api/pg/__body_probe?test=1&label=pg%2Fmock'
                payload = {'probe': 'nginx-routing', 'nested': {'value': 414}}
                body, status, _ = request(f'{scheme}://{host}{path}', 'POST', json.dumps(payload),
                                          ('Content-Type: application/json',
                                           'Idempotency-Key: 550e8400-e29b-41d4-a716-446655440400',
                                           'X-Forwarded-For: 192.0.2.10'))
                assert status == 200, (scheme, path, status)
                data = json.loads(body)
                assert data == dict(data, uri=path, method='POST', proto=scheme, backend='pg-mock',
                                    host=host, content_type='application/json', body=payload,
                                    idempotency_key='550e8400-e29b-41d4-a716-446655440400'), (scheme, data)
                assert data['forwarded_for'].startswith('192.0.2.10, '), data
                assert len(data['request_id']) == 32 and all(c in '0123456789abcdef' for c in data['request_id']), data
            print('PASS HTTP/TLS routes, methods, query strings, forwarded protocol and internal blocking', flush=True)
            print('PASS PG prefix routing, URI/body preservation, idempotency and forwarding headers', flush=True)

            for path in ('/mcp?test=1', '/api/pg/v1/payment-methods/tokenize?test=1'):
                for method in ('GET', 'POST'):
                    _, status, redirect = request('http://' + DOMAIN + path, method)
                    assert status == 308 and redirect == 'https://' + DOMAIN + path
            assert request('http://' + DOMAIN + '/.well-known/acme-challenge/probe')[:2] == ('acme-ready', 200)
            assert request('http://' + DOMAIN + '/internal')[1] == 404
            print('PASS HTTPS redirect and direct HTTP ACME validation', flush=True)

            run('docker', 'stop', *backends)
            run('docker', 'exec', https, 'nginx', '-t', '-c', '/fixtures/nginx.https.conf')
            run('docker', 'exec', https, 'nginx', '-s', 'reload', '-c', '/fixtures/nginx.https.conf')
            assert request('https://' + DOMAIN + '/internal')[1] == 404
            print('PASS TLS reload with unavailable backend DNS', flush=True)
    finally:
        for name in reversed(containers):
            subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['docker', 'network', 'rm', network], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for name in images:
            subprocess.run(['docker', 'image', 'rm', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
