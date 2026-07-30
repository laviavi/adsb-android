package com.laviavi.adsbandroid.capture

/**
 * Thrown when the user tries to use USB source but the RTL-SDR driver app
 * (marto.rtl_tcp_andro) is not installed on the device.
 */
class SdrDriverNotInstalledException : Exception(
    "RTL-SDR driver app is not installed. " +
    "Please install it from the Play Store: " +
    "https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro"
)

/** Thrown when the driver app returned RESULT_CANCELED. */
class SdrDriverFailedException(
    val errorCode: Int?,
    message: String?,
) : Exception(message ?: "RTL-SDR driver failed to open device (code=$errorCode)")
