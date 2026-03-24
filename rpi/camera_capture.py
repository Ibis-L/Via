# camera_capture.py — picamera2-based camera capture with JPEG compression

import time
import threading
import logging
import cv2
import numpy as np
from picamera2 import Picamera2
from config import CAPTURE_RESOLUTION, CAPTURE_FPS, JPEG_QUALITY

logger = logging.getLogger(__name__)

_FRAME_INTERVAL = 1.0 / CAPTURE_FPS  # seconds between frames


class CameraCapture:
    """
    Captures frames from the Pi Camera v2 via picamera2 (libcamera backend).
    Frames are encoded as JPEG and stored in a thread-safe buffer.
    """

    def __init__(
        self,
        resolution: tuple[int, int] = CAPTURE_RESOLUTION,
        fps: int = CAPTURE_FPS,
    ):
        self._resolution = resolution
        self._fps = fps
        self._lock = threading.Lock()
        self._jpeg_frame: bytes | None = None
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._camera: Picamera2 | None = None

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def get_latest_jpeg(self) -> bytes | None:
        """Return the most recently captured JPEG bytes, or None if not ready."""
        with self._lock:
            return self._jpeg_frame

    def start(self) -> None:
        """Start the background capture thread."""
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._capture_loop,
            name="camera-thread",
            daemon=True
        )
        self._thread.start()
        logger.info(
            "CameraCapture started (%dx%d @ %dfps, JPEG quality %d)",
            self._resolution[0], self._resolution[1], self._fps, JPEG_QUALITY
        )

    def stop(self) -> None:
        """Stop the capture thread and release the camera."""
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=5.0)
        self._release_camera()
        logger.info("CameraCapture stopped")

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _init_camera(self) -> bool:
        """Attempt to initialise picamera2. Returns True on success."""
        try:
            cam = Picamera2()
            config = cam.create_still_configuration(
                main={
                    "size": self._resolution,
                    "format": "RGB888",
                }
            )
            cam.configure(config)
            cam.start()
            time.sleep(0.5)  # warm-up
            self._camera = cam
            logger.info("Camera initialised OK")
            return True
        except Exception as exc:
            logger.error("Camera init failed: %s", exc)
            return False

    def _release_camera(self) -> None:
        if self._camera is not None:
            try:
                self._camera.stop()
                self._camera.close()
            except Exception:
                pass
            self._camera = None

    def _capture_loop(self) -> None:
        interval = 1.0 / self._fps
        while not self._stop_event.is_set():
            # (Re-)initialise camera if needed
            if self._camera is None:
                if not self._init_camera():
                    logger.error("Camera not available, retrying in 3s …")
                    self._stop_event.wait(3.0)
                    continue

            try:
                # Capture array (RGB888 → numpy array shape HxWx3)
                frame: np.ndarray = self._camera.capture_array()

                # Convert RGB → BGR for OpenCV
                bgr = cv2.cvtColor(frame, cv2.COLOR_RGB2BGR)

                # Encode to JPEG
                encode_params = [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY]
                ok, buf = cv2.imencode(".jpg", bgr, encode_params)
                if not ok:
                    logger.warning("JPEG encode failed, skipping frame")
                    self._stop_event.wait(interval)
                    continue

                jpeg_bytes = buf.tobytes()

                with self._lock:
                    self._jpeg_frame = jpeg_bytes

            except Exception as exc:
                logger.error("Camera capture error: %s — reinitialising …", exc)
                self._release_camera()
                self._stop_event.wait(3.0)
                continue

            self._stop_event.wait(interval)
