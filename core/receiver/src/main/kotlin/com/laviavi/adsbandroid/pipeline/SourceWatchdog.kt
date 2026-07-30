package com.laviavi.adsbandroid.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Generic "no active source for too long" watchdog. A single timer starts the moment
 * [onStateChanged] reports inactive; it is cancelled the moment activity resumes. Repeated
 * inactive reports while a timer is already running do not restart it, so state churn
 * during an outage (e.g. Error/Connecting cycling from an auto-reconnect loop) doesn't
 * extend the deadline, and separate outages don't accumulate time toward one shutdown.
 */
class SourceWatchdog(
    private val scope: CoroutineScope,
    private val onExpire: suspend () -> Unit,
) {
    private var job: Job? = null

    /** Call on every state change. [timeoutMs] <= 0 disables the watchdog entirely. */
    fun onStateChanged(active: Boolean, timeoutMs: Long) {
        if (active || timeoutMs <= 0) {
            job?.cancel()
            job = null
        } else if (job == null) {
            job = scope.launch {
                delay(timeoutMs)
                onExpire()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
