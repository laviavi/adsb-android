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
) : Parcelable
