# socket_server.py — TCP server that streams camera + distance to Android phone

import socket
import struct
import time
import logging
from typing import Callable

logger = logging.getLogger(__name__)

# Type aliases for clarity
GetFrameFn = Callable[[], bytes | None]
GetDistanceFn = Callable[[], float]


class SocketServer:
    """
    TCP server bound on 0.0.0.0:<port>.
    Accepts one client at a time (Android app).
    Each iteration sends:
      [4B big-endian uint  = JPEG byte length]
      [N bytes             = JPEG data       ]
      [4B big-endian float = distance in m   ]
    On disconnect, waits 2 s then loops back to accept().
    """

    def __init__(self, port: int = 5000):
        self._port = port
        self._server_sock: socket.socket | None = None

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def run(
        self,
        get_frame_fn: GetFrameFn,
        get_distance_fn: GetDistanceFn,
    ) -> None:
        """
        Blocking loop: listen → accept → stream → reconnect.
        Call from the main thread.
        """
        self._server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server_sock.bind(("0.0.0.0", self._port))
        self._server_sock.listen(1)
        logger.info("TCP server listening on 0.0.0.0:%d", self._port)

        while True:
            logger.info("Waiting for Android client to connect …")
            try:
                conn, addr = self._server_sock.accept()
            except OSError as exc:
                logger.error("Server socket error during accept: %s", exc)
                break

            logger.info("Client connected from %s:%d", addr[0], addr[1])

            try:
                self._stream_loop(conn, get_frame_fn, get_distance_fn)
            except Exception as exc:
                logger.error("Unexpected error in stream loop: %s", exc)
            finally:
                try:
                    conn.close()
                except Exception:
                    pass
                logger.info("Client disconnected — waiting 2 s before accepting again …")
                time.sleep(2.0)

    def close(self) -> None:
        """Close the server socket (call during shutdown)."""
        if self._server_sock:
            try:
                self._server_sock.close()
            except Exception:
                pass
            self._server_sock = None
        logger.info("SocketServer closed")

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _stream_loop(
        self,
        conn: socket.socket,
        get_frame_fn: GetFrameFn,
        get_distance_fn: GetDistanceFn,
    ) -> None:
        """
        Continuously send packets to the connected client.
        Exits on any socket error.
        """
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        packets_sent = 0

        while True:
            jpeg: bytes | None = get_frame_fn()
            distance: float = get_distance_fn()

            if jpeg is None:
                # Camera not ready yet — short wait
                time.sleep(0.05)
                continue

            image_size = len(jpeg)

            # Build packet:  [4B size][N bytes image][4B float distance]
            header = struct.pack(">I", image_size)
            dist_bytes = struct.pack(">f", distance)
            packet = header + jpeg + dist_bytes

            try:
                conn.sendall(packet)
                packets_sent += 1
                if packets_sent % 100 == 0:
                    logger.debug(
                        "Sent %d packets; last: %d bytes, dist=%.3fm",
                        packets_sent, image_size, distance
                    )
            except (BrokenPipeError, ConnectionResetError, OSError) as exc:
                logger.warning("Send error (client likely disconnected): %s", exc)
                return
