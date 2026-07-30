package com.laviavi.adsbandroid.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Socket

/**
 * TCP socket source. Supports two independent read modes on the same connection:
 *  - [readFrame]: AVR text lines from dump1090 (port 30002), e.g. "*8D4840D6202CC371C32CE0576098;"
 *  - [readSamples]: raw IQ byte stream from rtl_tcp - either a direct network
 *    connection (port 1234) or the USB OTG loopback (via rtl_tcp_andro).
 *
 * Only one mode is used per connection; the raw [InputStream] is read directly for
 * [readSamples] so binary sample data is never passed through a charset decoder
 * (which would corrupt bytes outside the ASCII range).
 */
/**
 * @param readTimeoutMs socket read timeout. **A finite value is what makes a dead
 *   session detectable at all.** rtl_tcp has no ping, no keepalive and no status
 *   command — the greeting is sent once on connect and everything after it is
 *   one-way IQ. So the only evidence the dongle is alive is that samples keep
 *   arriving. With `0` (infinite) a driver that holds the socket open with no
 *   device behind it parks `read()` forever: no EOF, no exception, no error
 *   state — the receiver silently stops while still reporting Running. Because
 *   the stream is continuous, silence is diagnostic; that is the reason to time
 *   out, not a reason to avoid it. `0` is retained for the AVR text mode, where
 *   lines legitimately arrive minutes apart.
 */
class NetworkSource(private val readTimeoutMs: Int = 0) : IqSource {

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var reader: BufferedReader? = null
    private var running = false

    override suspend fun open(config: CaptureConfig) = withContext(Dispatchers.IO) {
        val s = Socket(config.networkHost, config.networkPort)
        s.soTimeout = readTimeoutMs
        socket = s
        inputStream = s.getInputStream()
        running = true
    }

    /**
     * Read one AVR-format line and decode it into raw frame bytes.
     * Returns the hex bytes as IntArray, or null on end-of-stream / malformed line.
     * Blocks until a line is available (IO dispatcher).
     */
    suspend fun readFrame(): IntArray? = withContext(Dispatchers.IO) {
        if (!running) return@withContext null
        val stream = inputStream ?: return@withContext null
        val r = reader ?: BufferedReader(InputStreamReader(stream, Charsets.US_ASCII)).also { reader = it }
        val line = r.readLine() ?: return@withContext null
        parseAvrLine(line)
    }

    /**
     * Read raw bytes directly from the socket (rtl_tcp IQ stream).
     * Returns the number of bytes read, or -1 on end-of-stream.
     */
    override suspend fun readSamples(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        if (!running) return@withContext -1
        val stream = inputStream ?: return@withContext -1
        stream.read(buffer)
    }

    /** Writes control bytes back up the socket (rtl_tcp accepts commands on the same connection). */
    suspend fun writeBytes(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        runCatching {
            s.getOutputStream().apply { write(data); flush() }
        }.isSuccess
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        running = false
        reader?.close()
        inputStream?.close()
        socket?.close()
        reader = null
        inputStream = null
        socket = null
    }

    companion object {
        /**
         * Parse one AVR line: "*<hexbytes>;" → IntArray of byte values.
         * Returns null for malformed input.
         */
        fun parseAvrLine(line: String): IntArray? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("*") || !trimmed.endsWith(";")) return null
            val hex = trimmed.substring(1, trimmed.length - 1)
            if (hex.length % 2 != 0 || hex.isEmpty()) return null
            if (hex.length != 14 && hex.length != 28) return null  // 7 or 14 bytes
            return try {
                IntArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16)
                }
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
