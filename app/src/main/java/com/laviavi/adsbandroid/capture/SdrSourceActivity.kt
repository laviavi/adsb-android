package com.laviavi.adsbandroid.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.laviavi.adsbandroid.pipeline.PipelineService

/**
 * Transparent trampoline Activity that bridges the iqsrc:// Intent result
 * back into the pipeline coroutine.
 *
 * Why a separate Activity:
 *   - startActivityForResult() requires an Activity context
 *   - PipelineService is a Service, not an Activity
 *   - This Activity is theme NoDisplay — zero UI, invisible to the user
 *
 * Flow:
 *   PipelineService.startUsbSource()
 *     → starts SdrSourceActivity via Intent
 *     → SdrSourceActivity fires iqsrc:// to driver app
 *     → driver returns RESULT_OK or RESULT_CANCELED
 *     → SdrSourceActivity sends result back to PipelineService via LocalBroadcast
 *     → PipelineService resumes its coroutine
 *
 * Declare in AndroidManifest with android:theme="@android:style/Theme.NoDisplay"
 */
class SdrSourceActivity : ComponentActivity() {

    private val driverLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        handleDriverResult(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = intent.getParcelableExtra<SdrLaunchConfig>(EXTRA_CONFIG)
            ?: run { sendResult(false, "Missing launch config"); finish(); return }

        if (!UsbHotplugReceiver.isDriverInstalled(this)) {
            sendResult(false, "RTL-SDR driver not installed")
            finish()
            return
        }

        val uri = UsbRtlSdrSource.buildIqSrcUri(
            port    = config.port,
            freqHz  = config.frequencyHz,
            rateHz  = config.sampleRateHz,
            gainTenths = config.gainTenths,
            ppm     = config.ppm,
        )

        try {
            driverLauncher.launch(Intent(Intent.ACTION_VIEW).setData(uri))
        } catch (e: android.content.ActivityNotFoundException) {
            sendResult(false, "RTL-SDR driver not installed")
            finish()
        }
    }

    private fun handleDriverResult(result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK) {
            sendResult(success = true, errorMessage = null)
        } else {
            val msg = result.data?.getStringExtra("detailed_exception_message")
                ?: "Driver returned RESULT_CANCELED"
            val code = result.data?.getIntExtra("detailed_exception_code", -1)
            sendResult(success = false, errorMessage = "[$code] $msg")
        }
        finish()
    }

    private fun sendResult(success: Boolean, errorMessage: String?) {
        val broadcast = Intent(ACTION_DRIVER_RESULT).apply {
            putExtra(EXTRA_SUCCESS, success)
            if (errorMessage != null) putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
            setPackage(packageName)
        }
        sendBroadcast(broadcast)
    }

    companion object {
        const val ACTION_DRIVER_RESULT = "com.laviavi.adsbandroid.SDR_DRIVER_RESULT"
        const val EXTRA_SUCCESS        = "success"
        const val EXTRA_ERROR_MESSAGE  = "error_message"
        const val EXTRA_CONFIG         = "config"

        fun createIntent(context: Context, config: SdrLaunchConfig) =
            Intent(context, SdrSourceActivity::class.java).apply {
                putExtra(EXTRA_CONFIG, config)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
