package com.laviavi.adsbandroid.capture

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    private var requestToken: Long = 0L

    /**
     * PipelineService gives up waiting on us if the driver app takes too long
     * (RtlSdrDefaults.DRIVER_RESULT_TIMEOUT_MS) — this is how it tells us to stop
     * waiting too, so this trampoline doesn't linger as an orphaned background
     * task. Cannot reach the driver app's own stuck Activity this way — no
     * cross-app API exists for that — only removes our own task.
     */
    private val giveUpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(EXTRA_TOKEN, -1L) != requestToken) return
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.registerReceiver(
            this, giveUpReceiver, IntentFilter(ACTION_GIVE_UP), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val config = intent.getParcelableExtra<SdrLaunchConfig>(EXTRA_CONFIG)
            ?: run { sendResult(false, "Missing launch config"); finish(); return }
        requestToken = config.requestToken

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

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(giveUpReceiver) }
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
            putExtra(EXTRA_TOKEN, requestToken)
            if (errorMessage != null) putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
            setPackage(packageName)
        }
        sendBroadcast(broadcast)
    }

    companion object {
        const val ACTION_DRIVER_RESULT = "com.laviavi.adsbandroid.SDR_DRIVER_RESULT"
        /** PipelineService → this Activity: give up waiting, remove yourself. See [giveUpReceiver]. */
        const val ACTION_GIVE_UP       = "com.laviavi.adsbandroid.SDR_GIVE_UP"
        const val EXTRA_SUCCESS        = "success"
        const val EXTRA_ERROR_MESSAGE  = "error_message"
        const val EXTRA_CONFIG         = "config"
        /** Identifies one open attempt — see [SdrLaunchConfig.requestToken]. */
        const val EXTRA_TOKEN          = "request_token"

        fun createIntent(context: Context, config: SdrLaunchConfig) =
            Intent(context, SdrSourceActivity::class.java).apply {
                putExtra(EXTRA_CONFIG, config)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
