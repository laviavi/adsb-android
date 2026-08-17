package com.laviavi.adsbandroid.capture

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Parcelable config passed from PipelineService → SdrSourceActivity via Intent extras.
 */
@Parcelize
data class SdrLaunchConfig(
    val port: Int        = UsbRtlSdrSource.LOOPBACK_PORT,
    val frequencyHz: Long = UsbRtlSdrSource.CENTER_FREQ_HZ,
    val sampleRateHz: Long = UsbRtlSdrSource.SAMPLE_RATE_HZ,
    val gainTenths: Int  = 0,
    val ppm: Int         = 0,
    /** Identifies this specific open attempt, echoed back on the result broadcast so a
     *  late reply from an attempt PipelineService already gave up waiting on (timed out)
     *  can't be mistaken for the answer to a newer attempt. See PipelineService.openUsbSource(). */
    val requestToken: Long = 0L,
) : Parcelable
