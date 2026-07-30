package com.laviavi.adsbandroid.capture

interface IqSource {
    suspend fun open(config: CaptureConfig)
    suspend fun readSamples(buffer: ByteArray): Int
    suspend fun close()
}

data class CaptureConfig(
    val centerFrequencyHz: Long = 1_090_000_000L,
    val sampleRateHz: Int = 2_400_000,
    val gainTenths: Int = 0,
    val ppmCorrection: Int = 0,
    val networkHost: String = "127.0.0.1",
    val networkPort: Int = 30_002,
    val filePath: String = "",
)
