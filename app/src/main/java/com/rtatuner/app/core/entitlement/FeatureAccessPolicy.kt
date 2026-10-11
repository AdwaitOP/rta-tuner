package com.rtatuner.app.core.entitlement

enum class FreeFeature {
    LIVE_RTA, RAW_FFT, BASIC_FFT_WINDOWS, SMOOTHING_AND_GRAPH_CONTROLS, FULLSCREEN,
    PEAK_RMS_METERS, MICROPHONE_ROUTING, PINK_NOISE, HARMAN_TARGET,
    SESSION_VIEWING, SESSION_NOTES, SESSION_DELETION, PNG_SHARING
}

enum class ProFeature(val title: String, val benefit: String) {
    FFT_4096("4096-point FFT window", "Use a 4096-point FFT for finer frequency resolution. 1024 and 2048 remain available in Free."),
    MIC_CALIBRATION("Microphone calibration", "Import a microphone calibration file and correct its frequency response."),
    SPL_CALIBRATION("SPL calibration", "Set a reference level and measure in calibrated SPL mode."),
    NOISE_FLOOR("Noise floor subtraction", "Capture ambient noise and subtract it from your measurements."),
    SINE_SWEEP("Sine sweep", "Configure frequency ranges, duration and looping for a logarithmic sweep."),
    ADVANCED_TARGETS("More target curves", "Compare your tuning with Flat and AudioControl presets."),
    UNLIMITED_OVERLAYS("Unlimited frozen overlays", "Compare multiple live or saved measurements side by side."),
    UNLIMITED_SESSIONS("Unlimited saved sessions", "Keep a tuning history for all your vehicles and systems."),
    CSV_EXPORT("CSV exports", "Export live, frozen and saved-session measurements for further analysis.")
}

data class AccessLimits(val frozenOverlays: Int?, val savedSessions: Int?)

sealed interface FeatureAccessResult {
    data object Allowed : FeatureAccessResult
    data class Denied(val feature: ProFeature, val message: String) : FeatureAccessResult
}

class FeatureAccessDeniedException(val denial: FeatureAccessResult.Denied) :
    IllegalStateException(denial.message)

fun FeatureAccessResult.getOrThrow() {
    if (this is FeatureAccessResult.Denied) throw FeatureAccessDeniedException(this)
}

/** Single source of truth for capabilities and limits; checks happen at action boundaries. */
class FeatureAccessPolicy(val entitlements: EntitlementRepository = FreeEntitlementRepository) {
    val limits: AccessLimits
        get() = if (entitlements.state.value.isPro) AccessLimits(null, null) else AccessLimits(1, 1)

    fun check(@Suppress("UNUSED_PARAMETER") feature: FreeFeature): FeatureAccessResult =
        FeatureAccessResult.Allowed

    fun check(feature: ProFeature): FeatureAccessResult =
        if (entitlements.state.value.isPro) FeatureAccessResult.Allowed else
            FeatureAccessResult.Denied(feature, "${feature.title} requires CabinRTA Pro. ${feature.benefit}")

    fun requireAccess(feature: ProFeature) = check(feature).getOrThrow()

    fun checkFftWindow(windowSize: Int): FeatureAccessResult = when (windowSize) {
        1024, 2048 -> FeatureAccessResult.Allowed
        4096 -> check(ProFeature.FFT_4096)
        else -> throw IllegalArgumentException("Unsupported window size: $windowSize")
    }

    fun checkOverlayCapacity(existingCount: Int, additionalCount: Int = 1): FeatureAccessResult {
        require(existingCount >= 0 && additionalCount >= 0)
        val limit = limits.frozenOverlays
        return if (additionalCount == 0 || limit == null ||
            (existingCount <= limit && additionalCount <= limit - existingCount)) {
            FeatureAccessResult.Allowed
        } else FeatureAccessResult.Denied(
            ProFeature.UNLIMITED_OVERLAYS,
            "Free includes one frozen overlay. Remove an existing overlay to make room, or select one saved snapshot. Pro allows unlimited overlays. Your measurements have been kept."
        )
    }

    fun checkSessionCapacity(existingCount: Int): FeatureAccessResult {
        require(existingCount >= 0)
        val limit = limits.savedSessions
        return if (limit == null || existingCount < limit) FeatureAccessResult.Allowed else
            FeatureAccessResult.Denied(
                ProFeature.UNLIMITED_SESSIONS,
                "Free includes one saved tuning session. Your existing sessions remain available to view, edit notes and delete. Pro allows unlimited sessions."
            )
    }
}
