#!/usr/bin/env python3
"""Validate resolved Compose configuration without a daemon or production secrets."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def resolve(env_file, https=False):
    # Never inherit a developer's real OAuth settings or secret locations.
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(('MCP_', 'CORE_', 'AGENT_OAUTH_', 'COMPOSE_'))}
    command = ['docker', 'compose', '--env-file', str(env_file), '-f', str(ROOT / 'docker-compose.yml')]
    if https:
        command += ['-f', str(ROOT / 'infra/docker/https.override.yml')]
    result = subprocess.run(command + ['config', '--format', 'json'], env=env,
                            text=True, encoding='utf-8', capture_output=True, check=True)
    return json.loads(result.stdout)['services']


def main():
    with tempfile.TemporaryDirectory(prefix='mcp-compose-') as directory:
        work = Path(directory)
        env_file = work / 'test.env'
        env_file.write_text('', encoding='utf-8')
        plain = resolve(env_file)
        assert plain['mcp-server']['environment']['SPRING_PROFILES_ACTIVE'] == ''
        assert not plain['mcp-server'].get('ports'), 'MCP must remain behind nginx'
        print('PASS default profile remains opt-in and has no public container port')

        # Missing and empty settings must both select the right deployment defaults.
        for contents in ('', 'AGENT_OAUTH_ISSUER=\nAGENT_OAUTH_MCP_AUDIENCE=\n'):
            env_file.write_text(contents, encoding='utf-8')
            for https, issuer in ((False, 'http://localhost:8080'),
                                  (True, 'https://j15a207.p.ssafy.io')):
                core = resolve(env_file, https)['core-service']['environment']
                assert core['AGENT_OAUTH_ISSUER'] == issuer
                assert core['AGENT_OAUTH_MCP_AUDIENCE'] == issuer + '/mcp'
        print('PASS local OAuth defaults stay local and HTTPS deployment defaults use the public origin')

        secret_dir = (work / 'secrets').as_posix()
        values = {
            'MCP_SPRING_PROFILES_ACTIVE': 'core-integration',
            'MCP_AUTH_RESOURCE': 'https://shop.example/mcp',
            'MCP_AUTH_ISSUER': 'https://shop.example',
            'MCP_AUTH_JWKS_URI': 'https://shop.example/.well-known/jwks.json',
            'CORE_TOKEN_URI': 'https://shop.example/oauth/token',
            'CORE_MCP_CLIENT_ID': 'test-mcp-client',
            'MCP_ALLOWED_HOSTS': 'shop.example,mcp-server:8080',
            'MCP_ALLOWED_ORIGINS': 'https://chatgpt.com',
            'MCP_SECRETS_DIR': secret_dir,
            'AGENT_OAUTH_ISSUER': 'https://shop.example',
            'AGENT_OAUTH_MCP_AUDIENCE': 'https://shop.example/mcp',
            # Synthetic marker only; no real key is generated or read by this test.
            'AGENT_OAUTH_JWT_PRIVATE_KEY': 'test-only-key-marker',
            'AGENT_OAUTH_JWT_KEY_ID': 'test-key-id',
        }
        env_file.write_text('\n'.join(f'{k}={v}' for k, v in values.items()), encoding='utf-8')
        for https in (False, True):
            services = resolve(env_file, https)
            mcp = services['mcp-server']
            actual = mcp['environment']
            assert 'MCP_APP_LINK_BASE' not in actual
            assert 'CORE_INTERNAL_APP_LINK_BASE' not in services['core-service']['environment']
            for key, value in values.items():
                if key.startswith('AGENT_OAUTH_'):
                    assert services['core-service']['environment'][key] == value, key
                elif key == 'MCP_SPRING_PROFILES_ACTIVE':
                    assert actual['SPRING_PROFILES_ACTIVE'] == value
                elif key != 'MCP_SECRETS_DIR':
                    assert actual[key] == value, key
            mounts = {v['target']: v for v in mcp['volumes']}
            secret = mounts['/run/secrets/mcp']
            assert secret['type'] == 'bind' and secret['read_only']
            assert secret['source'].replace('\\', '/') == secret_dir
            assert actual['CORE_MCP_PRIVATE_JWK_FILE'] == '/run/secrets/mcp/client.jwk'
            assert actual['CORE_SERVICE_URL'] == 'http://core-service:8080'
            assert 'AGENT_OAUTH_JWT_PRIVATE_KEY' not in actual
            assert not mcp.get('ports')
        print('PASS HTTP/HTTPS Compose forwards OAuth settings and mounts only MCP keys read-only')


if __name__ == '__main__':
    main()
