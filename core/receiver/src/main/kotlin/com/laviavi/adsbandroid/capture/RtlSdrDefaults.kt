package com.laviavi.adsbandroid.capture

/**
 * The dongle's launch parameters and the `iqsrc://` URI built from them.
 *
 * These live here, in the pure-JVM module, rather than in the Android
 * `UsbRtlSdrSource` so there is exactly one definition to test and to change.
 * The previous arrangement — a `UsbRtlSdrSource` stub in this module mirroring
 * the real one in `:app` — let the two drift, and they did: the stub still read
 * 2 400 000 Hz long after the real source was corrected, and a passing test
 * asserted that stale value. The sample rate is the one constant in this
 * codebase that silently destroys reception when wrong, so it gets a single home.
 */
object RtlSdrDefaults {

    const val LOOPBACK_HOST = "127.0.0.1"
    const val LOOPBACK_PORT = 1234
    const val CENTER_FREQ_HZ = 1_090_000_000L

    /**
     * Must equal [com.laviavi.adsbandroid.demod.Demodulator.REQUIRED_SAMPLE_RATE_HZ].
     * The demodulator assumes exactly 2 samples per 1 µs Mode S bit; at 2.4 Msps the
     * sampling drifts ~45 samples across a 112-bit frame and nothing decodes at all.
     */
    const val SAMPLE_RATE_HZ = 2_000_000L

    const val REQUEST_CODE = 1090

    /**
     * Socket read timeout for the IQ stream, in ms.
     *
     * At 2 Msps a 256 KB block arrives every ~65 ms, so this is ~45x the healthy
     * interval — far too long to trip on a transient stall, short enough to
     * surface a dead dongle in seconds. See [NetworkSource] for why a finite
     * value is load-bearing rather than a tuning preference.
     */
    const val IQ_READ_TIMEOUT_MS = 3_000

    /**
     * How long a reused driver session has to produce real IQ bytes before it is
     * judged stale. rtl_tcp replays its cached `RTL0` greeting on every new
     * connection — including after the USB device has been yanked — so the
     * greeting proves the driver process is alive, never that a dongle is
     * attached. Only sample flow proves that.
     */
    const val SESSION_PROOF_TIMEOUT_MS = 2_000

    /**
     * Build the `iqsrc://` URI string for the driver app. Arguments match
     * SdrTcpArguments in the signalware SDK; `gainTenths` is passed through as
     * tenths of a dB, which is what the driver expects — not whole dB.
     * Returned as a String so this stays free of `android.net.Uri`.
     */
    fun buildIqSrcUri(
        host: String = LOOPBACK_HOST,
        port: Int = LOOPBACK_PORT,
        freqHz: Long = CENTER_FREQ_HZ,
        rateHz: Long = SAMPLE_RATE_HZ,
        gainTenths: Int = 0,   // 0 = auto
        ppm: Int = 0,
    ): String =
        "iqsrc://-a $host -p $port -f $freqHz -s $rateHz" +
            (if (gainTenths > 0) " -g $gainTenths" else "") +
            (if (ppm != 0) " -P $ppm" else "")
}
