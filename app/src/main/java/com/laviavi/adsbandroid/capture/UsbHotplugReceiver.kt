package com.laviavi.adsbandroid.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * Listens for RTL-SDR dongle attach/detach events broadcast by the driver app.
 *
 * The signalware driver emits:
 *   com.sdrtouch.rtlsdr.SDR_DEVICE_ATTACHED   — dongle plugged in
 *   com.sdrtouch.rtlsdr.SDR_DEVICE_DETACHED   — dongle unplugged  (not always fired)
 *
 * Register in PipelineService.onCreate(), unregister in onDestroy().
 *
 * @param onAttached Called on the main thread when a compatible USB dongle is detected.
 * @param onDetached Called when the dongle is removed (best-effort).
 */
class UsbHotplugReceiver(
    private val onAttached: () -> Unit,
    private val onDetached: () -> Unit = {},
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ATTACHED -> onAttached()
            ACTION_DETACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached()
        }
    }

    companion object {
        const val ACTION_ATTACHED = "com.sdrtouch.rtlsdr.SDR_DEVICE_ATTACHED"
        const val ACTION_DETACHED = "com.sdrtouch.rtlsdr.SDR_DEVICE_DETACHED"
        const val DRIVER_PACKAGE  = "marto.rtl_tcp_andro"
        const val DRIVER_PLAY_URL = "https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro"

        /**
         * The driver's own actions plus the system detach broadcast. The system
         * one is the dependable half: it comes from the platform, so a
         * non-exported receiver still receives it, whereas the `com.sdrtouch.*`
         * pair are another package's implicit broadcasts. There is no system
         * counterpart worth adding for attach — `ACTION_USB_DEVICE_ATTACHED` is
         * delivered through the manifest device-filter, not to runtime
         * receivers, which is why reconnect polls [UsbPresence] instead.
         */
        fun intentFilter() = IntentFilter().apply {
            addAction(ACTION_ATTACHED)
            addAction(ACTION_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        /** Returns true if the RTL-SDR driver app is installed. */
        fun isDriverInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(DRIVER_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }

        /** Opens the driver's Play Store listing, falling back to a browser if Play Store itself is absent. */
        fun openDriverInstallPage(context: Context) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(DRIVER_PLAY_URL)
                setPackage("com.android.vending")
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DRIVER_PLAY_URL)))
            }
        }

        fun register(context: Context, receiver: UsbHotplugReceiver) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                intentFilter(),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }
}
