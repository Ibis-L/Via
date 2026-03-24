package com.blindstick.network

import android.util.Log
import com.blindstick.model.ParsedFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TCP client that connects to the Raspberry Pi on port 5000.
 * Parses binary packets:
 *   [4B big-endian uint  = JPEG size]
 *   [N bytes             = JPEG data ]
 *   [4B big-endian float = distance  ]
 *
 * Automatically retries on disconnect.
 */
object SocketClient {

    private const val TAG = "SocketClient"
    const val RPI_IP = "192.168.43.100"   // ← Change to your RPi's hotspot IP
    private const val RPI_PORT = 5000
    private const val RETRY_DELAY_MS = 2_000L
    private const val CONNECT_TIMEOUT_MS = 5_000

    // --- Public state ---
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _latestFrame = MutableStateFlow<ParsedFrame?>(null)
    val latestFrame: StateFlow<ParsedFrame?> = _latestFrame

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -------------------------------------------------------------------

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { connectLoop() }
        Log.i(TAG, "SocketClient started — target: $RPI_IP:$RPI_PORT")
    }

    fun stop() {
        job?.cancel()
        _isConnected.value = false
        Log.i(TAG, "SocketClient stopped")
    }

    // -------------------------------------------------------------------

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                Log.i(TAG, "Connecting to $RPI_IP:$RPI_PORT …")
                val socket = Socket()
                socket.connect(InetSocketAddress(RPI_IP, RPI_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = 10_000   // 10-second read timeout

                _isConnected.value = true
                Log.i(TAG, "Connected to RPi")

                socket.use { s ->
                    val dis = DataInputStream(s.getInputStream())
                    while (currentCoroutineContext().isActive) {
                        val frame = readPacket(dis)
                        _latestFrame.value = frame
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message} — retrying in ${RETRY_DELAY_MS}ms")
                _isConnected.value = false
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /**
     * Reads one complete packet from the stream.
     * Throws IOException on any read failure (triggers reconnect).
     */
    private fun readPacket(dis: DataInputStream): ParsedFrame {
        // 1. Read 4-byte big-endian image size
        val sizeBuf = ByteArray(4)
        dis.readFully(sizeBuf)
        val imageSize = ByteBuffer.wrap(sizeBuf).order(ByteOrder.BIG_ENDIAN).int.and(0xFFFFFFFFL.toInt())

        // 2. Read JPEG bytes
        val jpeg = ByteArray(imageSize)
        dis.readFully(jpeg)

        // 3. Read 4-byte big-endian float distance
        val distBuf = ByteArray(4)
        dis.readFully(distBuf)
        val distance = ByteBuffer.wrap(distBuf).order(ByteOrder.BIG_ENDIAN).float

        return ParsedFrame(jpeg, distance)
    }
}
