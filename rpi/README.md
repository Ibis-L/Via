# Smart Blind Stick — Raspberry Pi Setup Guide

## Overview
This Python project turns a Raspberry Pi 3 into the hardware brain of a smart blind-stick system.  
It streams compressed camera frames and ultrasonic distance data over WiFi (TCP) to an Android phone.

---

## Hardware Wiring

| Component          | RPi GPIO (BCM) | Notes                                      |
|--------------------|----------------|--------------------------------------------|
| HC-SR04 TRIG       | GPIO 23        | 3.3 V logic out                            |
| HC-SR04 ECHO       | GPIO 24        | Connect via 1kΩ/2kΩ voltage divider to 3.3V |
| Vibration motor    | GPIO 18 (PWM)  | Base of 2N2222 NPN via 1kΩ resistor        |
| Status LED (+)     | GPIO 25        | Via 330Ω resistor                          |
| Pi Camera v2       | CSI ribbon     | Connected via CSI camera port              |
| GND                | GND            | Common ground for all components           |

> **IMPORTANT**: The HC-SR04 ECHO pin outputs 5 V.  
> Use a voltage divider (1 kΩ + 2 kΩ) to bring it down to ~3.3 V to protect the RPi GPIO.

### Vibration Motor Circuit
```
GPIO 18 ──[1kΩ]── Base(2N2222)
                   Emitter ── GND
                   Collector ── Motor (–)
Motor (+) ── 5V supply
Flyback diode: 1N4001 across motor terminals (cathode to +)
```

---

## Software Installation

### 1. System packages
```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y python3-pip python3-picamera2 libcamera-apps
```

### 2. Enable Camera interface
```bash
sudo raspi-config
# → Interface Options → Camera → Enable
# Reboot
```

### 3. Python dependencies
```bash
pip3 install RPi.GPIO opencv-python-headless numpy
# picamera2 is installed via apt (see step 1)
```

### 4. Clone / copy project files
Place all Python files (`main.py`, `config.py`, `ultrasonic.py`, `vibration.py`,  
`camera_capture.py`, `socket_server.py`) in a single directory, e.g. `/home/pi/blindstick/`.

---

## Running Manually

```bash
cd /home/pi/blindstick
python3 main.py
```

Press **Ctrl+C** to stop.

---

## Auto-start on Boot (systemd)

Copy the service file to systemd:
```bash
sudo cp blindstick.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable blindstick.service
sudo systemctl start blindstick.service
```

Check status:
```bash
sudo systemctl status blindstick.service
journalctl -u blindstick.service -f
```

---

## Network Setup

1. The Android phone creates a **WiFi hotspot**.
2. The RPi connects to that hotspot and receives an IP via DHCP.
3. The RPi listens on **port 5000** (all interfaces).
4. Find the RPi's assigned IP:
   ```bash
   hostname -I
   ```
5. Update `RPI_IP` constant in the Android app to match.

---

## File Structure

```
blindstick/
├── config.py          # All constants and pin numbers
├── main.py            # Entry point
├── ultrasonic.py      # HC-SR04 sensor driver
├── vibration.py       # PWM vibration motor controller
├── camera_capture.py  # picamera2 frame capture
├── socket_server.py   # TCP server (sends frames to phone)
├── requirements.txt   # pip dependencies
├── blindstick.service # systemd auto-start unit
└── README.md          # This file
```
