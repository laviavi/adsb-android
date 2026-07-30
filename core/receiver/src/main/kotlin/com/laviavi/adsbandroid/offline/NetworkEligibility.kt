package com.laviavi.adsbandroid.offline

/**
 * What the device is connected to, as far as offline downloads care.
 *
 * [UNKNOWN] is a real state, not a placeholder: when the platform cannot classify
 * the transport, the safe reading is "not Wi-Fi". Treating it as eligible is how a
 * metered connection gets billed for a two-hundred-megabyte download.
 */
enum class NetworkState {
    WIFI_UNMETERED,
    /** Wi-Fi the user or carrier has flagged as metered — a phone hotspot, typically. */
    WIFI_METERED,
    CELLULAR,
    /** Ethernet, USB tethering, or any other non-Wi-Fi transport. */
    OTHER,
    DISCONNECTED,
    UNKNOWN,
}

/** Why a download may not start. Carries the reason so the UI never has to guess the wording. */
sealed interface EligibilityResult {
    data object Eligible : EligibilityResult

    data class Ineligible(val state: NetworkState, val reason: String) : EligibilityResult

    val isEligible: Boolean get() = this is Eligible
}

/**
 * The single gate every download passes through.
 *
 * An interface rather than a direct `ConnectivityManager` call so the policy is
 * testable off-device and the platform detail stays in `:app`. Every acceptance
 * case for network states runs against a fake implementing this.
 */
interface NetworkEligibility {
    fun currentState(): NetworkState

    /**
     * Evaluated fresh on every call — never cached. Callers are expected to invoke
     * this immediately before each batch, because the answer at the moment the user
     * pressed the button says nothing about the network thirty seconds later.
     */
    fun check(): EligibilityResult = OfflineDownloadPolicy.evaluate(currentState())
}

/**
 * The Wi-Fi-only rule, in one place.
 *
 * Kept as an object rather than inlined into the manager so the rule has exactly one
 * definition and the tests assert the rule itself, not one caller's reading of it.
 */
object OfflineDownloadPolicy {

    /**
     * Only unmetered Wi-Fi qualifies. Metered Wi-Fi is excluded deliberately —
     * a tethered hotspot is a cellular plan wearing a Wi-Fi transport, and the
     * user's intent in asking for "Wi-Fi only" is to avoid the bill, not to
     * match on the radio technology.
     */
    fun evaluate(state: NetworkState): EligibilityResult = when (state) {
        NetworkState.WIFI_UNMETERED -> EligibilityResult.Eligible
        NetworkState.WIFI_METERED -> EligibilityResult.Ineligible(
            state, "This Wi-Fi network is metered. Offline maps only download over unmetered Wi-Fi.",
        )
        NetworkState.CELLULAR -> EligibilityResult.Ineligible(
            state, "Offline maps can only download or update over Wi-Fi. Connect to Wi-Fi and try again.",
        )
        NetworkState.OTHER -> EligibilityResult.Ineligible(
            state, "Offline maps can only download or update over Wi-Fi.",
        )
        NetworkState.DISCONNECTED -> EligibilityResult.Ineligible(
            state, "No network connection. Offline maps can only download over Wi-Fi.",
        )
        NetworkState.UNKNOWN -> EligibilityResult.Ineligible(
            state, "The network type could not be confirmed. Offline maps only download over Wi-Fi.",
        )
    }

    /** True only for the one state that permits network use. */
    fun isDownloadAllowed(state: NetworkState): Boolean = state == NetworkState.WIFI_UNMETERED
}
