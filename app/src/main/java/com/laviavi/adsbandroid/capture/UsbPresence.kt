package com.laviavi.adsbandroid.capture

import android.content.Context
import android.hardware.usb.UsbManager

/**
 * Asks the OS directly whether an RTL-SDR dongle is attached.
 *
 * This is ground truth, and it is what the reconnect loop gates on. The
 * alternative signals are all worse: the driver app's broadcasts are another
 * package's implicit intents that a non-exported receiver may never see,
 * `ACTION_USB_DEVICE_ATTACHED` is delivered via the manifest device-filter
 * rather than to runtime receivers, and the rtl_tcp protocol itself offers no
 * status query. Enumeration needs no USB permission — permission governs
 * opening a device, which the driver app does, not us.
 */
object UsbPresence {

    /** Realtek. Every RTL2832U-family dongle enumerates under this vendor. */
    private const val VENDOR_REALTEK = 0x0BDA

    /**
     * Matched on vendor alone, deliberately looser than `usb_device_filter.xml`'s
     * four product IDs. This gate can only *withhold* a reconnect attempt, so a
     * false negative strands the receiver on unlisted hardware while a false
     * positive costs one open that fails harmlessly.
     */
    fun isDongleAttached(context: Context): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return runCatching {
            manager.deviceList.values.any { it.vendorId == VENDOR_REALTEK }
        }.getOrDefault(false)
    }
}
