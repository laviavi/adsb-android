package com.laviavi.adsbandroid.offline

import android.content.Context
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Platform implementations backing offline maps: network-state reading and reverse
 * geocoding for a downloaded area's display name. The actual download/storage is
 * MapLibre's own native `OfflineManager` (see `MapLibreOfflineRepository`) — these
 * two are the only pieces still worth keeping platform-Android and swappable-for-tests.
 */

/**
 * Wi-Fi eligibility from `ConnectivityManager`.
 *
 * Reads live on every call rather than caching a callback's last value: the manager
 * re-checks before each batch precisely so a network that changed mid-download is
 * noticed, and a cached answer would defeat that.
 */
class AndroidNetworkEligibility(context: Context) : NetworkEligibility {

    private val appContext = context.applicationContext
    private val cm get() = appContext.getSystemService(ConnectivityManager::class.java)

    override fun currentState(): NetworkState {
        val manager = cm ?: return NetworkState.UNKNOWN
        val network = manager.activeNetwork ?: return NetworkState.DISCONNECTED
        val caps = manager.getNetworkCapabilities(network) ?: return NetworkState.UNKNOWN

        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkState.DISCONNECTED
        }

        // NOT_METERED is the capability the user's data plan actually cares about;
        // a Wi-Fi transport that is metered (a phone hotspot) is reported as such so
        // policy can refuse it, rather than being waved through on transport alone.
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                if (unmetered) NetworkState.WIFI_UNMETERED else NetworkState.WIFI_METERED
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.OTHER
            // A transport we cannot classify is never assumed safe.
            else -> NetworkState.UNKNOWN
        }
    }
}

/** Resolves a coordinate to a place name, for a downloaded offline area's display name. */
interface LocationNamer {
    suspend fun nameFor(lat: Double, lon: Double): String?
}

/** Reverse-geocodes a coordinate to a place name. */
class AndroidLocationNamer(context: Context) : LocationNamer {

    private val appContext = context.applicationContext

    override suspend fun nameFor(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            val results = Geocoder(appContext, Locale.getDefault()).getFromLocation(lat, lon, 1)
            results?.firstOrNull()?.let { a ->
                // Locality first, then the progressively coarser fields — a coordinate
                // in open country has no city but usually has a county or state, and a
                // coarse name still beats raw degrees for telling areas apart.
                a.locality
                    ?: a.subAdminArea
                    ?: a.adminArea
                    ?: a.countryName
            }
        }.getOrNull()
    }
}
