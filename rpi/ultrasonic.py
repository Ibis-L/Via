# ultrasonic.py — HC-SR04 ultrasonic sensor driver

import time
import threading
import logging
import RPi.GPIO as GPIO
from config import (
    TRIG_PIN, ECHO_PIN,
    ULTRASONIC_INTERVAL, ULTRASONIC_TIMEOUT,
    ECHO_FALLBACK, MAX_VALID_DISTANCE
)

logger = logging.getLogger(__name__)


class UltrasonicSensor:
    """
    Manages the HC-SR04 ultrasonic distance sensor.
    Runs continuous measurements in a background daemon thread.
    Thread-safe distance retrieval via get_distance().
    """

    def __init__(self, trig_pin: int = TRIG_PIN, echo_pin: int = ECHO_PIN):
        self._trig = trig_pin
        self._echo = echo_pin
        self._lock = threading.Lock()
        self._distance: float = ECHO_FALLBACK
        self._last_valid: float = ECHO_FALLBACK
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._last_log_time: float = 0.0

        # GPIO setup (BCM mode assumed set by caller — main.py)
        GPIO.setup(self._trig, GPIO.OUT)
        GPIO.setup(self._echo, GPIO.IN)
        GPIO.output(self._trig, False)
        time.sleep(0.05)  # let sensor settle

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def get_distance(self) -> float:
        """Return latest distance in metres (thread-safe)."""
        with self._lock:
            return self._distance

    def start(self) -> None:
        """Start the background measurement thread."""
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._measure_loop,
            name="ultrasonic-thread",
            daemon=True
        )
        self._thread.start()
        logger.info("UltrasonicSensor started (TRIG=%d, ECHO=%d)", self._trig, self._echo)

    def stop(self) -> None:
        """Signal background thread to stop and wait for it."""
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=2.0)
        logger.info("UltrasonicSensor stopped")

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _measure_loop(self) -> None:
        while not self._stop_event.is_set():
            dist = self._ping()
            now = time.time()

            if dist > MAX_VALID_DISTANCE:
                # Invalid reading — keep last valid
                logger.debug(
                    "Ultrasonic: out-of-range reading %.2f m, keeping last valid %.2f m",
                    dist, self._last_valid
                )
                dist = self._last_valid
            else:
                self._last_valid = dist

            with self._lock:
                self._distance = dist

            # Periodic log every LOG_SENSOR_INTERVAL seconds
            if now - self._last_log_time >= 5.0:
                logger.info("Ultrasonic distance: %.3f m", dist)
                self._last_log_time = now

            self._stop_event.wait(ULTRASONIC_INTERVAL)

    def _ping(self) -> float:
        """
        Send one ultrasonic pulse and measure round-trip time.
        Returns distance in metres or ECHO_FALLBACK on timeout.
        """
        # Ensure TRIG is low
        GPIO.output(self._trig, False)
        time.sleep(0.000002)

        # Send 10 µs TRIG pulse
        GPIO.output(self._trig, True)
        time.sleep(0.00001)
        GPIO.output(self._trig, False)

        # Wait for ECHO to go HIGH (start of signal)
        pulse_start = time.time()
        deadline = pulse_start + ULTRASONIC_TIMEOUT
        while GPIO.input(self._echo) == 0:
            pulse_start = time.time()
            if pulse_start > deadline:
                return ECHO_FALLBACK

        # Wait for ECHO to go LOW (end of signal)
        pulse_end = time.time()
        deadline = pulse_end + ULTRASONIC_TIMEOUT
        while GPIO.input(self._echo) == 1:
            pulse_end = time.time()
            if pulse_end > deadline:
                return ECHO_FALLBACK

        duration = pulse_end - pulse_start
        distance_cm = (duration * 34300) / 2
        return distance_cm / 100.0  # convert to metres
