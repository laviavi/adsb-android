package com.laviavi.adsbandroid.location

/** Pure gating/trigger decisions for GPS orchestration — no Android or coroutine dependency. */
object GpsPolicy {

    /** Continuous (throttled) GPS updates should only run while actively receiving data. */
    fun shouldRunContinuousGps(mode: ObserverMode, sourceRunning: Boolean): Boolean =
        mode == ObserverMode.FOLLOW_GPS && sourceRunning

    /**
     * A reconnect after a genuine error invalidates any pre-outage fix — but a benign
     * settings-triggered restart (Running -> Connecting -> Running, no error in between)
     * should not force a re-fix; nothing about the antenna/source actually changed.
     */
    fun shouldRefixOnReconnect(hadErrorSinceLastRunning: Boolean, isRunning: Boolean): Boolean =
        hadErrorSinceLastRunning && isRunning

    /** Whether the periodic high-accuracy re-fix is due. intervalMinutes <= 0 disables it. */
    fun isPeriodicRefixDue(lastRefixMs: Long, nowMs: Long, intervalMinutes: Int): Boolean =
        intervalMinutes > 0 && (nowMs - lastRefixMs) >= intervalMinutes * 60_000L
}
