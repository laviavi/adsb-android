package com.laviavi.adsbandroid.pipeline

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.laviavi.adsbandroid.location.ThrottleParams
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thin wrapper around [FusedLocationProviderClient] — the only Android/Play-Services-coupled
 * GPS code. Throttling/gating decisions live in the pure [com.laviavi.adsbandroid.location]
 * policy classes and are applied by the caller (PipelineService), not here.
 */
class GpsLocationProvider(private val context: Context) {

    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Forces a fresh fix — never a cached/last-known location. Returns null on failure or no permission. */
    @SuppressLint("MissingPermission")
    suspend fun requestFreshFix(): Location? {
        if (!hasPermission()) return null
        return suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc -> if (cont.isActive) cont.resumeWith(Result.success(loc)) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.success(null)) }
            cont.invokeOnCancellation { cts.cancel() }
        }
    }

    /** (Re)starts continuous balanced-power updates with the given throttle tier. */
    @SuppressLint("MissingPermission")
    fun startUpdates(params: ThrottleParams, onLocation: (Location) -> Unit) {
        if (!hasPermission()) return
        stopUpdates()
        val request = LocationRequest.Builder(params.intervalMs)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMinUpdateIntervalMillis(params.intervalMs / 2)
            .setMinUpdateDistanceMeters(params.minDistanceMeters)
            .setMaxUpdateDelayMillis(params.intervalMs * 3)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(onLocation)
            }
        }
        callback = cb
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
    }

    fun stopUpdates() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }
}
