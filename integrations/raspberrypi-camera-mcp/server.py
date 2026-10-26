"""Loopback-only Raspberry Pi camera MCP. No automatic capture or photo storage."""
import asyncio
import base64
from datetime import datetime, timezone
import os
from pathlib import Path
import signal
import sys
from typing import Annotated
from mcp.server.fastmcp import FastMCP
from mcp.types import ImageContent, TextContent, ToolAnnotations
from pydantic import Field

ROOT = Path(__file__).resolve().parent
BACKEND = os.environ.get('CAMERA_BACKEND', 'usb')
if BACKEND not in ('usb', 'csi'):
    raise ValueError('CAMERA_BACKEND must be usb or csi')
PORT = int(os.environ.get('MCP_PORT', '8765'))
capture_lock = asyncio.Lock()
CAPTURE_TIMEOUT = 20
mcp = FastMCP('Raspberry Pi Camera', host='127.0.0.1', port=PORT,
              stateless_http=True, json_response=True,
              instructions='Capture only when the user explicitly requests a photo. '
              'The camera belongs to the Raspberry Pi running this MCP, not the phone. '
              'Return one photo; no background capture or automatic retry after success.')


async def terminate_worker(process):
    if process.returncode is None:
        try:
            process.kill()
        except ProcessLookupError:
            pass
    await process.wait()


@mcp.tool(annotations=ToolAnnotations(readOnlyHint=True, openWorldHint=False))
def camera_status() -> dict:
    """Check server readiness without activating or testing the camera."""
    return {'server': 'Raspberry Pi Camera', 'ready': True,
            'backend': BACKEND, 'camera_busy': capture_lock.locked(),
            'camera_tested': False, 'storage': 'Image returned in memory; no photo saved.'}


@mcp.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False,
                                    idempotentHint=False, openWorldHint=False))
async def capture_photo(
    camera_index: Annotated[int, Field(ge=0, le=15)] = 0,
    width: Annotated[int, Field(ge=320, le=1920)] = 1280,
    height: Annotated[int, Field(ge=240, le=1080)] = 720,
) -> list[TextContent | ImageContent]:
    """Take one requested Raspberry Pi camera photo and return JPEG to ChatGPT.

    Activates camera briefly and releases it. No audio, video recording or disk storage.
    USB index N selects /dev/videoN. CSI index selects an rpicam camera.
    Width and height are upper bounds; hardware can return smaller images.
    """
    if capture_lock.locked():
        raise ValueError('Camera is busy. Wait for the current capture.')
    async with capture_lock:
        child_env = os.environ.copy()
        child_env.pop('CONTROL_PLANE_API_KEY', None)
        process = await asyncio.create_subprocess_exec(
            sys.executable, str(ROOT / 'camera_worker.py'), '--backend', BACKEND,
            '--index', str(camera_index), '--width', str(width), '--height', str(height),
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            env=child_env)
        try:
            try:
                jpeg, stderr = await asyncio.wait_for(process.communicate(), CAPTURE_TIMEOUT)
            except asyncio.TimeoutError:
                raise ValueError('Capture timed out after 20 seconds. Check device and camera ownership.') from None
        finally:
            if process.returncode is None:
                await terminate_worker(process)
        if process.returncode != 0:
            raise ValueError('Capture failed: ' + stderr.decode('utf-8', errors='replace')[-1200:])
        if not jpeg.startswith(b'\xff\xd8') or not jpeg.endswith(b'\xff\xd9'):
            raise ValueError('Camera returned invalid JPEG.')
        return [TextContent(type='text', text=f'One photo captured on Raspberry Pi at {datetime.now(timezone.utc).isoformat()}. Camera released.'),
                ImageContent(type='image', mimeType='image/jpeg', data=base64.b64encode(jpeg).decode('ascii'))]


if __name__ == '__main__':
    mcp.run(transport='streamable-http')
