"""Short-lived camera worker; stdout is JPEG only. Linux required for hardware."""
import argparse
import os
import shutil
import sys
import time


def capture_usb(index, width, height):
    import cv2
    camera = cv2.VideoCapture(f'/dev/video{index}', cv2.CAP_V4L2)
    try:
        if not camera.isOpened():
            raise RuntimeError(f'Cannot open /dev/video{index}; check video group, device index and other apps.')
        camera.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        camera.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        deadline = time.monotonic() + 1.5
        frame = None
        while time.monotonic() < deadline or frame is None:
            ok, frame = camera.read()
            if not ok:
                raise RuntimeError('USB camera returned no frame.')
        h, w = frame.shape[:2]
        scale = min(1.0, width / w, height / h)
        if scale < 1:
            frame = cv2.resize(frame, (max(1, round(w*scale)), max(1, round(h*scale))))
        ok, encoded = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 85])
        if not ok:
            raise RuntimeError('JPEG encoding failed.')
        return encoded.tobytes()
    finally:
        camera.release()


def csi_command(index, width, height):
    binary = shutil.which('rpicam-still')
    if not binary:
        raise RuntimeError('rpicam-still missing. Install rpicam-apps on Raspberry Pi OS.')
    return [binary, '--nopreview', '--camera', str(index), '--timeout', '2000',
            '--width', str(width), '--height', str(height), '--encoding', 'jpg',
            '--quality', '85', '--output', '-']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--backend', choices=['usb', 'csi'], required=True)
    parser.add_argument('--index', type=int, default=0)
    parser.add_argument('--width', type=int, default=1280)
    parser.add_argument('--height', type=int, default=720)
    args = parser.parse_args()
    if sys.platform != 'linux':
        raise RuntimeError('Hardware capture requires Linux on Raspberry Pi.')
    if not (0 <= args.index <= 15 and 320 <= args.width <= 1920 and 240 <= args.height <= 1080):
        raise ValueError('Invalid camera index or resolution.')
    if args.backend == 'csi':
        command = csi_command(args.index, args.width, args.height)
        # Replace worker so the MCP timeout also terminates the actual camera process.
        os.execv(command[0], command)
    else:
        sys.stdout.buffer.write(capture_usb(args.index, args.width, args.height))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
