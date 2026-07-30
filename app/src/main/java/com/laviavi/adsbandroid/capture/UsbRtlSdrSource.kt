package com.laviavi.adsbandroid.capture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * USB OTG RTL-SDR source using the signalware/marto rtl_tcp_andro driver app.
 *
 * Flow:
 *  1. Fire iqsrc:// Intent → driver app opens USB device, starts rtl_tcp server on loopback
 *  2. Receive RESULT_OK → connect NetworkSource on 127.0.0.1:[port]
 *  3. Delegate all readSamples() calls to NetworkSource
 *  4. On close → close NetworkSource (driver app manages its own lifecycle)
 *
 * Intent protocol (from signalware README):
 *   Uri: iqsrc://-a 127.0.0.1 -p <port> -s <samplerate> -f <freq> -g <gain> -P <ppm>
 *   RESULT_OK   → connected; read IQ from TCP loopback
 *   RESULT_CANCELED → error; extras: detailed_exception_message, detailed_exception_code
 *
 * Hotplug: PipelineService registers UsbHotplugReceiver and calls open() again on re-attach.
 *
 * Threading: open() suspends on IO dispatcher waiting for Activity result callback.
 * readSamples() delegates to NetworkSource (IO dispatcher internally).
 */
class UsbRtlSdrSource : IqSource {

    private var networkSource: NetworkSource? = null

    /** Tuner reported by the dongle on connect; null until connected or if the greeting was malformed. */
    var dongleInfo: DongleInfo? = null
        private set

    companion object {
        // Single source of truth lives in :core:receiver/RtlSdrDefaults so it can be
        // unit-tested without Android. Re-exported here so existing call sites and
        // the Android-typed Uri builder keep working.
        const val LOOPBACK_HOST  = RtlSdrDefaults.LOOPBACK_HOST
        const val LOOPBACK_PORT  = RtlSdrDefaults.LOOPBACK_PORT
        const val CENTER_FREQ_HZ = RtlSdrDefaults.CENTER_FREQ_HZ
        const val SAMPLE_RATE_HZ = RtlSdrDefaults.SAMPLE_RATE_HZ
        const val REQUEST_CODE   = RtlSdrDefaults.REQUEST_CODE

        /** rtl_tcp sends this 4-byte magic header immediately on connect. */
        private val RTL_TCP_MAGIC = RtlTcpGain.MAGIC

        fun buildIqSrcUri(
            host: String  = LOOPBACK_HOST,
            port: Int     = LOOPBACK_PORT,
            freqHz: Long  = CENTER_FREQ_HZ,
            rateHz: Long  = SAMPLE_RATE_HZ,
            gainTenths: Int = 0,   // 0 = auto
            ppm: Int      = 0,
        ): Uri = Uri.parse(
            RtlSdrDefaults.buildIqSrcUri(host, port, freqHz, rateHz, gainTenths, ppm)
        )
    }

    /**
     * Open the USB source.
     *
     * Fires the iqsrc:// Intent and suspends until the Activity result arrives
     * via [onActivityResult]. The caller (MainActivity or PipelineService trampoline
     * Activity) must forward onActivityResult to this method.
     *
     * @throws SdrDriverNotInstalledException if driver app not present
     * @throws SdrDriverFailedException if driver returned RESULT_CANCELED
     */
    override suspend fun open(config: CaptureConfig) {
        // Driver app connection is handled via Activity result — delegated through
        // SdrSourceActivity. PipelineService calls openViaActivity() instead.
        // This overload is retained for interface compatibility with FileSource/NetworkSource.
        openNetworkSource(config)
    }

    /**
     * Connect NetworkSource to loopback after driver confirms RESULT_OK.
     * Called by SdrSourceActivity after receiving a successful activity result.
     */
    suspend fun openNetworkSource(config: CaptureConfig) {
        val net = NetworkSource(RtlSdrDefaults.IQ_READ_TIMEOUT_MS)
        val loopbackConfig = config.copy(
            networkHost = LOOPBACK_HOST,
            networkPort = config.networkPort.takeIf { it > 0 } ?: LOOPBACK_PORT,
        )
        net.open(loopbackConfig)
        readDongleInfo(net)
        networkSource = net
    }

    /**
     * rtl_tcp sends a 12-byte dongle-info header (magic + tuner type + gain
     * count) on every new connection, before any IQ sample bytes. It must be
     * consumed here or the demodulator's first read misaligns — and it is the
     * only place the attached tuner identifies itself, so it is retained in
     * [dongleInfo] to drive the manual gain list.
     */
    private suspend fun readDongleInfo(net: NetworkSource) {
        val header = ByteArray(RtlTcpGain.HEADER_SIZE)
        var off = 0
        while (off < header.size) {
            val chunk = ByteArray(header.size - off)
            val n = net.readSamples(chunk)
            if (n <= 0) break
            System.arraycopy(chunk, 0, header, off, n)
            off += n
        }
        dongleInfo = if (off == header.size) RtlTcpGain.parseDongleInfo(header) else null
    }

    /**
     * Applies gain over the live rtl_tcp control channel — no reconnect needed.
     * Auto mode hands control to the tuner's own AGC; manual mode pins a specific
     * step from the device's gain table.
     */
    suspend fun applyGain(autoGain: Boolean, gainTenths: Int): Boolean {
        val net = networkSource ?: return false
        return if (autoGain) {
            net.writeBytes(RtlTcpGain.command(RtlTcpGain.CMD_SET_GAIN_MODE, 0))
        } else {
            net.writeBytes(RtlTcpGain.command(RtlTcpGain.CMD_SET_GAIN_MODE, 1)) &&
                net.writeBytes(RtlTcpGain.command(RtlTcpGain.CMD_SET_GAIN, gainTenths))
        }
    }

    /** Enables or disables the dongle's bias tee over the live rtl_tcp control channel. */
    suspend fun applyBiasTee(enabled: Boolean): Boolean {
        val net = networkSource ?: return false
        return net.writeBytes(RtlTcpGain.command(RtlTcpGain.CMD_SET_BIAS_TEE, if (enabled) 1 else 0))
    }

    /**
     * Probes the loopback port for a driver session that's already live from a
     * previous run - the driver's foreground service can outlive this app being
     * killed, so a fresh iqsrc:// intent would otherwise fail (port already bound).
     *
     * Requires the session to actually **deliver IQ samples**, not merely to
     * answer. Accepting the `RTL0` greeting as proof of life is what let a
     * disconnected dongle look healthy: rtl_tcp replays that greeting from cached
     * device info on every new connection, so it survives the USB device being
     * yanked. Taking it at face value reused a session that would never produce a
     * sample, and — because reuse skips the `iqsrc://` intent — the driver was
     * never asked to reopen the device, so reconnecting could not recover.
     */
    suspend fun tryConnectExisting(config: CaptureConfig): Boolean = withContext(Dispatchers.IO) {
        val probe = Socket()
        try {
            probe.connect(InetSocketAddress(config.networkHost, config.networkPort), 500)
            probe.soTimeout = RtlSdrDefaults.SESSION_PROOF_TIMEOUT_MS
            val stream = probe.getInputStream()

            val header = ByteArray(RtlTcpGain.HEADER_SIZE)
            var off = 0
            while (off < header.size) {
                val n = stream.read(header, off, header.size - off)
                if (n <= 0) return@withContext false
                off += n
            }
            if (!header.copyOf(RTL_TCP_MAGIC.size).contentEquals(RTL_TCP_MAGIC)) return@withContext false

            // The greeting is followed immediately by the sample stream on a live
            // device; on a stale session this read is what times out.
            if (stream.read(ByteArray(1024)) <= 0) return@withContext false

            probe.close()
            openNetworkSource(config)
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { probe.close() }
        }
    }

    override suspend fun readSamples(buffer: ByteArray): Int {
        return networkSource?.readSamples(buffer)
            ?: throw IllegalStateException("UsbRtlSdrSource not open — call openNetworkSource() first")
    }

    override suspend fun close() {
        networkSource?.close()
        networkSource = null
    }

    val isOpen: Boolean get() = networkSource != null
}
