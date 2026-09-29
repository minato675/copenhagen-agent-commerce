"""Interactive launcher. Keeps credentials out of files and camera subprocesses."""
import getpass
import os
from pathlib import Path
import platform
import re
import signal
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent


def occupied(port):
    with socket.socket() as probe:
        probe.settimeout(0.3)
        return probe.connect_ex(('127.0.0.1', port)) == 0


def stop(process):
    if process is None or process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=6)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
    except ProcessLookupError:
        pass


def main():
    if platform.system() != 'Linux' or platform.machine() not in ('aarch64', 'arm64'):
        raise RuntimeError('Run on Raspberry Pi OS 64-bit.')
    backend = sys.argv[1] if len(sys.argv) > 1 else 'usb'
    if backend not in ('usb', 'csi'):
        raise ValueError('Select usb or csi.')
    if occupied(8765) or occupied(18765):
        raise RuntimeError('Port 8765 or 18765 already in use. Stop the existing MCP/tunnel first.')
    tunnel_id = input('Your OpenAI tunnel ID (tunnel_...): ').strip()
    if not re.fullmatch(r'tunnel_[A-Za-z0-9]+', tunnel_id):
        raise ValueError('Invalid tunnel ID')
    binaries = [p for p in (ROOT/'bin'/'tunnel-client').rglob('tunnel-client') if p.is_file()]
    if len(binaries) != 1:
        raise RuntimeError('Run setup.sh first; tunnel executable missing.')
    binary = str(binaries[0])
    os.umask(0o077)
    profile = ROOT/'profiles'/tunnel_id
    profile.mkdir(parents=True, exist_ok=True)
    public_env = os.environ.copy()
    public_env.pop('CONTROL_PLANE_API_KEY', None)
    public_env['CAMERA_BACKEND'] = backend
    public_env['MCP_PORT'] = '8765'
    server = tunnel = None
    runtime_env = None
    try:
        server = subprocess.Popen([sys.executable, str(ROOT/'server.py')], cwd=ROOT,
                                  env=public_env, start_new_session=True)
        for _ in range(60):
            if server.poll() is not None:
                raise RuntimeError('MCP server failed to start.')
            if occupied(8765):
                break
            time.sleep(0.25)
        else:
            raise RuntimeError('MCP startup timeout')
        subprocess.run([sys.executable, str(ROOT/'check.py')], env=public_env, check=True)
        if not (profile/'webcam.yaml').exists():
            subprocess.run([binary, 'init', '--sample', 'sample_mcp_remote_no_auth',
                '--profile', 'webcam', '--profile-dir', str(profile), '--tunnel-id', tunnel_id,
                '--mcp-server-url', 'http://127.0.0.1:8765/mcp',
                '--health-listen-addr', '127.0.0.1:18765'], env=public_env, check=True)
        if not sys.stdin.isatty():
            raise RuntimeError('Interactive terminal required for hidden API key input.')
        key = getpass.getpass('Runtime API key (hidden, not saved): ').strip()
        if not key:
            raise ValueError('API key is empty.')
        runtime_env = public_env.copy()
        runtime_env['CONTROL_PLANE_API_KEY'] = key
        del key
        args = ['--profile', 'webcam', '--profile-dir', str(profile),
                '--control-plane.tunnel-id', tunnel_id,
                '--control-plane.base-url', 'https://api.openai.com',
                '--control-plane.api-key', 'env:CONTROL_PLANE_API_KEY']
        subprocess.run([binary, 'doctor', *args], env=runtime_env, check=True)
        tunnel = subprocess.Popen([binary, 'run', *args], env=runtime_env, cwd=ROOT,
                                  start_new_session=True)
        runtime_env.pop('CONTROL_PLANE_API_KEY', None)
        print('Tunnel started. Check: curl http://127.0.0.1:18765/readyz', flush=True)
        print('Keep this terminal open. Ctrl+C stops the tunnel and MCP.', flush=True)
        while tunnel.poll() is None:
            if server.poll() is not None:
                raise RuntimeError('MCP exited; stopping tunnel.')
            time.sleep(0.5)
        if tunnel.returncode:
            raise RuntimeError('Tunnel exited with an error. Check diagnostics above.')
    finally:
        if runtime_env is not None:
            runtime_env.pop('CONTROL_PLANE_API_KEY', None)
        stop(tunnel)
        stop(server)


if __name__ == '__main__':
    def terminate(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, terminate)
    try:
        main()
    except KeyboardInterrupt:
        print('\nStopped.')
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
