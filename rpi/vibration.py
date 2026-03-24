# vibration.py — PWM vibration motor controller

import time
import threading
import logging
import RPi.GPIO as GPIO
from config import (
    VIBRATION_PIN, PWM_FREQUENCY,
    DUTY_CONTINUOUS, DUTY_OFF,
    DANGER_DISTANCE, NEAR_DISTANCE, MAX_DISTANCE_THRESHOLD
)

logger = logging.getLogger(__name__)


class VibrationMotor:
    """
    Controls a vibration motor via PWM through an NPN transistor.
    Vibration pattern is driven by the latest distance reading:
      < 0.5m  → continuous buzz (100% duty cycle)
      0.5–1.0m → fast pulses (0.1s on / 0.1s off)
      1.0–1.5m → slow pulses (0.3s on / 0.3s off)
      > 1.5m  → off (0% duty cycle)
    """

    def __init__(self, pwm_pin: int = VIBRATION_PIN):
        self._pin = pwm_pin
        self._lock = threading.Lock()
        self._distance: float = 999.0
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None

        GPIO.setup(self._pin, GPIO.OUT)
        self._pwm = GPIO.PWM(self._pin, PWM_FREQUENCY)
        self._pwm.start(DUTY_OFF)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def set_distance(self, distance: float) -> None:
        """Update the distance used to choose vibration pattern (thread-safe)."""
        with self._lock:
            self._distance = distance

    def start(self) -> None:
        """Start the background vibration-control thread."""
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._control_loop,
            name="vibration-thread",
            daemon=True
        )
        self._thread.start()
        logger.info("VibrationMotor started on GPIO %d", self._pin)

    def stop(self) -> None:
        """Stop the vibration motor and background thread."""
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=2.0)
        self._pwm.ChangeDutyCycle(DUTY_OFF)
        self._pwm.stop()
        logger.info("VibrationMotor stopped")

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _control_loop(self) -> None:
        while not self._stop_event.is_set():
            with self._lock:
                dist = self._distance

            if dist < DANGER_DISTANCE:
                # Continuous buzz — no pulsing needed
                self._pwm.ChangeDutyCycle(DUTY_CONTINUOUS)
                self._stop_event.wait(0.1)

            elif dist < NEAR_DISTANCE:
                # Fast pulses: 0.1s on / 0.1s off
                self._pwm.ChangeDutyCycle(DUTY_CONTINUOUS)
                if self._stop_event.wait(0.1):
                    break
                self._pwm.ChangeDutyCycle(DUTY_OFF)
                if self._stop_event.wait(0.1):
                    break

            elif dist < MAX_DISTANCE_THRESHOLD:
                # Slow pulses: 0.3s on / 0.3s off
                self._pwm.ChangeDutyCycle(DUTY_CONTINUOUS)
                if self._stop_event.wait(0.3):
                    break
                self._pwm.ChangeDutyCycle(DUTY_OFF)
                if self._stop_event.wait(0.3):
                    break

            else:
                # Too far — no vibration
                self._pwm.ChangeDutyCycle(DUTY_OFF)
                self._stop_event.wait(0.1)

        # Ensure motor is off when loop exits
        self._pwm.ChangeDutyCycle(DUTY_OFF)
