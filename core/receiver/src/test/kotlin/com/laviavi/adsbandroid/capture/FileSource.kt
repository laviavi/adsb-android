package com.laviavi.adsbandroid.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

/**
 * Raw IQ binary file replay source.
 * Reads interleaved unsigned 8-bit I,Q pairs from a .bin file captured
 * by the Python project's RTL-SDR capture module.
 *
 * Replays in real-time (no throttle) — suitable for algorithm testing.
 * Returns -1 on end-of-file; caller should close and optionally loop.
 */
class FileSource : IqSource {

    private var stream: FileInputStream? = null
    private var running = false

    override suspend fun open(config: CaptureConfig) = withContext(Dispatchers.IO) {
        if (config.filePath.isEmpty()) throw IllegalArgumentException("filePath must not be empty")
        stream = FileInputStream(config.filePath)
        running = true
    }

    override suspend fun readSamples(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        if (!running) return@withContext -1
        val n = stream?.read(buffer) ?: -1
        if (n <= 0) { running = false; return@withContext -1 }
        n
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        running = false
        stream?.close()
        stream = null
    }
}
