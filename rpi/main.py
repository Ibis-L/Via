# main.py — Entry point for Smart Blind Stick RPi system

import logging
import signal
import sys
import threading
import time

import RPi.GPIO as GPIO

from config import (
    TRIG_PIN, ECHO_PIN, VIBRATION_PIN, LED_PIN,
    SERVER_PORT, CAPTURE_RESOLUTION, CAPTURE_FPS
)
from ultrasonic import UltrasonicSensor
from vibration import VibrationMotor
from camera_capture import CameraCapture
from socket_server import SocketServer

# ---------------------------------------------------------------------------
# Logging configuration
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Status LED helper
# ---------------------------------------------------------------------------

class StatusLED:
    """
    Controls the status LED on GPIO 25.
    - Blinks (1s on / 1s off) while waiting for client connection.
    - Solid ON once client is connected.
    """

    def __init__(self, pin: int = LED_PIN):
        self._pin = pin
        self._stop_event = threading.Event()
        self._connected = threading.Event()
        self._thread: threading.Thread | None = None
        GPIO.setup(self._pin, GPIO.OUT)
        GPIO.output(self._pin, False)

    def set_connected(self, state: bool) -> None:
        if state:
            self._connected.set()
        else:
            self._connected.clear()

    def start(self) -> None:
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._blink_loop,
            name="led-thread",
            daemon=True
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=3.0)
        GPIO.output(self._pin, False)

    def _blink_loop(self) -> None:
        while not self._stop_event.is_set():
            if self._connected.is_set():
                GPIO.output(self._pin, True)
                self._stop_event.wait(0.1)
            else:
                GPIO.output(self._pin, True)
                self._stop_event.wait(1.0)
                if self._stop_event.is_set():
                    break
                GPIO.output(self._pin, False)
                self._stop_event.wait(1.0)
        GPIO.output(self._pin, False)


# ---------------------------------------------------------------------------
# Vibration feeder thread — keeps VibrationMotor updated with latest distance
# ---------------------------------------------------------------------------

def _vibration_feeder(
    ultrasonic: UltrasonicSensor,
    motor: VibrationMotor,
    stop_event: threading.Event,
) -> None:
    """Reads distance every 100ms and pushes it to the motor controller."""
    while not stop_event.is_set():
        dist = ultrasonic.get_distance()
        motor.set_distance(dist)
        stop_event.wait(0.1)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    logger.info("=== Smart Blind Stick starting ===")

    # --- GPIO global init ---
    GPIO.setmode(GPIO.BCM)
    GPIO.setwarnings(False)

    # --- Instantiate hardware ---
    ultrasonic = UltrasonicSensor(TRIG_PIN, ECHO_PIN)
    motor = VibrationMotor(VIBRATION_PIN)
    camera = CameraCapture(CAPTURE_RESOLUTION, CAPTURE_FPS)
    server = SocketServer(SERVER_PORT)
    led = StatusLED(LED_PIN)

    # --- Shutdown event ---
    stop_event = threading.Event()

    # --- SIGINT / SIGTERM handler ---
    def _shutdown(signum, frame):  # noqa: ANN001
        logger.info("Shutdown signal received — cleaning up …")
        stop_event.set()

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    # --- Start hardware threads ---
    ultrasonic.start()   # Thread 1: sensor loop
    motor.start()        # Thread 2: vibration PWM loop
    camera.start()       # Thread 3: camera capture loop
    led.start()          # Thread: LED blink

    # Thread that feeds distance from sensor → motor (daemon, not critical)
    feeder = threading.Thread(
        target=_vibration_feeder,
        args=(ultrasonic, motor, stop_event),
        name="vibration-feeder",
        daemon=True,
    )
    feeder.start()

    logger.info("All subsystems online — starting TCP server on port %d", SERVER_PORT)

    # Wrap server.run so LED reflects connection state
    def _run_server() -> None:
        # Monkey-patch socket accept to toggle LED
        # We subclass with callbacks instead — simpler approach:
        while not stop_event.is_set():
            led.set_connected(False)
            logger.info("Waiting for Android connection …")
            try:
                server.run(
                    get_frame_fn=camera.get_latest_jpeg,
                    get_distance_fn=ultrasonic.get_distance,
                )
            except Exception as exc:
                logger.error("Server run() exited with error: %s", exc)
                stop_event.wait(2.0)

    server_thread = threading.Thread(
        target=_run_server,
        name="socket-server-thread",
        daemon=True,
    )
    server_thread.start()

    # --- Main thread: wait for stop ---
    try:
        while not stop_event.is_set():
            stop_event.wait(1.0)
    except KeyboardInterrupt:
        stop_event.set()

    # --- Cleanup ---
    logger.info("Stopping all subsystems …")
    ultrasonic.stop()
    motor.stop()
    camera.stop()
    led.stop()
    server.close()
    GPIO.cleanup()
    logger.info("=== Smart Blind Stick stopped cleanly ===")


if __name__ == "__main__":
    main()
