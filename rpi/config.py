# config.py — Central configuration for Smart Blind Stick RPi system

# GPIO Pin Numbers (BCM mode)
TRIG_PIN = 23
ECHO_PIN = 24
VIBRATION_PIN = 18
LED_PIN = 25

# Network
SERVER_PORT = 5000

# Camera
CAPTURE_RESOLUTION = (640, 480)
CAPTURE_FPS = 10
JPEG_QUALITY = 60

# Distance thresholds (meters)
MAX_DISTANCE_THRESHOLD = 1.5   # beyond this → no vibration
DANGER_DISTANCE = 0.5          # below this → continuous buzz
NEAR_DISTANCE = 1.0            # below this → fast pulses

# Ultrasonic sensor
ULTRASONIC_TIMEOUT = 0.03      # 30ms echo timeout
ULTRASONIC_INTERVAL = 0.1      # 100ms polling interval
MAX_VALID_DISTANCE = 10.0      # readings above this treated as invalid (metres)
ECHO_FALLBACK = 999.0          # returned when no echo within timeout

# Vibration PWM
PWM_FREQUENCY = 100            # Hz
DUTY_CONTINUOUS = 100          # full buzz
DUTY_OFF = 0

# Logging
LOG_SENSOR_INTERVAL = 5        # log sensor reading every N seconds
