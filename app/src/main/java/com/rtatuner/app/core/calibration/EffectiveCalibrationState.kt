package com.rtatuner.app.core.calibration

import com.rtatuner.app.core.audio.CaptureAuthoritySnapshot
import com.rtatuner.app.core.audio.NoiseFloorCompatibility
import com.rtatuner.app.core.entitlement.EntitlementState

/**
 * Derives authorized runtime flags without rewriting requested/persisted calibration data.
 * Called on preference/ownership/authority changes, never inside the per-bin audio processing loop.
 */
internal fun effectiveCalibrationState(
    requested: CalibrationUiState,
    entitlement: EntitlementState,
    liveAuthority: CaptureAuthoritySnapshot? = null
): CalibrationUiState {
    // All effective flags use one current ownership sample.
    val micAllowed = entitlement.isPro
    val splAllowed = entitlement.isPro
    val noiseAllowed = entitlement.isPro
    val targetAllowed = requested.targetCurve == null || requested.targetCurve == TargetCurve.HARMAN_CAR ||
        entitlement.isPro
    val micActive = requested.isMicCurveActive && requested.micCurve != null && micAllowed

    // One current live compatibility decision:
    val compatibility: NoiseFloorCompatibility = when {
        liveAuthority != null && !liveAuthority.isValidRunningAuthority -> {
            NoiseFloorCompatibility.Incompatible(
                reason = "Live audio capture context is stopped or inactive.",
                requiresRecapture = false
            )
        }
        liveAuthority == null && requested.noiseFloor.isCaptured -> {
            NoiseFloorCompatibility.Incompatible(
                reason = "Live audio capture context is stopped or inactive.",
                requiresRecapture = false
            )
        }
        !requested.noiseFloor.isCaptured -> NoiseFloorCompatibility.Uncaptured
        liveAuthority != null -> evaluateNoiseFloorCompatibility(requested.noiseFloor, liveAuthority.fingerprint)
        else -> NoiseFloorCompatibility.Uncaptured
    }

    val isCompatible = compatibility is NoiseFloorCompatibility.Compatible
    val noiseSubRequested = requested.isNoiseSubtractionActive && noiseAllowed
    val noiseEffective = noiseSubRequested &&
        requested.noiseFloor.isCaptured &&
        !requested.noiseFloor.requiresRecapture &&
        isCompatible

    return requested.copy(
        isMicCurveActive = micActive,
        micCurveStatusText = when {
            micActive -> "Calib.: Active (${requested.micCurve!!.name})"
            requested.micCurve != null && !micAllowed -> "Calib.: Stored (Pro required)"
            else -> "Calib.: None"
        },
        splCalibration = requested.splCalibration.copy(isSplMode = requested.splCalibration.isSplMode && splAllowed),
        isTargetCurveActive = requested.isTargetCurveActive && targetAllowed,
        isNoiseSubtractionActive = noiseSubRequested,
        isNoiseSubtractionEffective = noiseEffective,
        noiseFloorCompatibility = compatibility,
        isCapturingNoiseFloor = requested.isCapturingNoiseFloor && noiseAllowed,
        noiseCaptureProgress = if (noiseAllowed) requested.noiseCaptureProgress else 0.0f
    )
}
