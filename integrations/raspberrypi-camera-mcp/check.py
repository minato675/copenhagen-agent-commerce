"""No capture unless --capture is explicitly supplied. Never writes photo files."""
import argparse
import asyncio
import base64
import json
import os
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--capture', action='store_true')
    parser.add_argument('--index', type=int, default=0)
    args = parser.parse_args()
    port = int(os.environ.get('MCP_PORT', '8765'))
    async with streamable_http_client(f'http://127.0.0.1:{port}/mcp') as (reader, writer, _):
        async with ClientSession(reader, writer) as session:
            await session.initialize()
            listing = await session.list_tools()
            assert {x.name for x in listing.tools} == {'capture_photo', 'camera_status'}
            status = await session.call_tool('camera_status', {})
            assert not status.isError
            invalid = await session.call_tool('capture_photo', {'camera_index': -1})
            assert invalid.isError
            print('PASS: MCP discovery, status, invalid input rejection')
            if args.capture:
                result = await session.call_tool('capture_photo', {'camera_index': args.index})
                if result.isError:
                    raise RuntimeError(str(result.content))
                from io import BytesIO
                from PIL import Image
                photo = next(x for x in result.content if x.type == 'image')
                data = base64.b64decode(photo.data)
                with Image.open(BytesIO(data)) as decoded:
                    decoded.load()
                    print(json.dumps({'capture': 'passed', 'size': decoded.size,
                                      'bytes': len(data), 'saved': False}))


if __name__ == '__main__':
    asyncio.run(main())
