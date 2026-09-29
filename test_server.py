import asyncio
import unittest
from unittest.mock import AsyncMock, MagicMock, patch
import server
import camera_worker


class ServerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        server.capture_lock = asyncio.Lock()

    async def test_busy_rejects_second_capture(self):
        async with server.capture_lock:
            with patch.object(server.asyncio, 'create_subprocess_exec', new_callable=AsyncMock) as spawn:
                with self.assertRaisesRegex(ValueError, 'busy'):
                    await server.capture_photo()
                spawn.assert_not_called()

    async def test_timeout_kills_worker_and_unlocks(self):
        process = MagicMock(returncode=None)
        process.communicate = AsyncMock(side_effect=asyncio.TimeoutError)
        process.wait = AsyncMock()
        with patch.object(server.asyncio, 'create_subprocess_exec', new_callable=AsyncMock, return_value=process):
            with self.assertRaisesRegex(ValueError, 'timed out'):
                await server.capture_photo()
        process.kill.assert_called_once()
        process.wait.assert_awaited_once()
        self.assertFalse(server.capture_lock.locked())

    async def test_cancellation_releases_camera(self):
        process = MagicMock(returncode=None)
        process.communicate = AsyncMock(side_effect=asyncio.CancelledError)
        process.wait = AsyncMock()
        with patch.object(server.asyncio, 'create_subprocess_exec', new_callable=AsyncMock, return_value=process):
            with self.assertRaises(asyncio.CancelledError):
                await server.capture_photo()
        process.kill.assert_called_once()
        self.assertFalse(server.capture_lock.locked())

    async def test_invalid_jpeg_rejected(self):
        process = MagicMock(returncode=0)
        process.communicate = AsyncMock(return_value=(b'invalid', b''))
        with patch.object(server.asyncio, 'create_subprocess_exec', new_callable=AsyncMock, return_value=process):
            with self.assertRaisesRegex(ValueError, 'invalid JPEG'):
                await server.capture_photo()

    async def test_capture_returns_image_without_key_in_worker(self):
        process = MagicMock(returncode=0)
        process.communicate = AsyncMock(return_value=(b'\xff\xd8test\xff\xd9', b''))
        with patch.dict(server.os.environ, {'CONTROL_PLANE_API_KEY': 'unit-test-placeholder'}):
            with patch.object(server.asyncio, 'create_subprocess_exec', new_callable=AsyncMock, return_value=process) as spawn:
                result = await server.capture_photo()
        self.assertEqual(result[1].mimeType, 'image/jpeg')
        self.assertNotIn('CONTROL_PLANE_API_KEY', spawn.call_args.kwargs['env'])


class CameraTests(unittest.TestCase):
    def test_usb_releases_on_read_failure(self):
        cv2 = MagicMock()
        camera = cv2.VideoCapture.return_value
        camera.isOpened.return_value = True
        camera.read.return_value = (False, None)
        with patch.dict('sys.modules', {'cv2': cv2}):
            with self.assertRaisesRegex(RuntimeError, 'no frame'):
                camera_worker.capture_usb(0, 1280, 720)
        camera.release.assert_called_once()
        cv2.VideoCapture.assert_called_once_with('/dev/video0', cv2.CAP_V4L2)

    def test_csi_uses_memory_output_no_preview(self):
        with patch.object(camera_worker.shutil, 'which', return_value='/usr/bin/rpicam-still'):
            command = camera_worker.csi_command(1, 1280, 720)
        self.assertIn('--nopreview', command)
        self.assertEqual(command[-2:], ['--output', '-'])
        self.assertEqual(command[command.index('--camera')+1], '1')

    def test_csi_missing_binary_clear_error(self):
        with patch.object(camera_worker.shutil, 'which', return_value=None):
            with self.assertRaisesRegex(RuntimeError, 'rpicam-still missing'):
                camera_worker.csi_command(0, 1280, 720)


if __name__ == '__main__':
    unittest.main()
