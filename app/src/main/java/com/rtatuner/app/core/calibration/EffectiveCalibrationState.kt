package com.rtatuner.app.core.calibration

import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.core.entitlement.ProFeature

/**
 * Derives authorized runtime flags without rewriting requested/persisted calibration data.
 * Called on preference/ownership changes, never inside the per-bin audio processing loop.
 */
internal fun effectiveCalibrationState(
    requested: CalibrationUiState,
    policy: FeatureAccessPolicy
): CalibrationUiState {
    val micAllowed = policy.check(ProFeature.MIC_CALIBRATION) is FeatureAccessResult.Allowed
    val splAllowed = policy.check(ProFeature.SPL_CALIBRATION) is FeatureAccessResult.Allowed
    val noiseAllowed = policy.check(ProFeature.NOISE_FLOOR) is FeatureAccessResult.Allowed
    val targetAllowed = requested.targetCurve == null || requested.targetCurve == TargetCurve.HARMAN_CAR ||
        policy.check(ProFeature.ADVANCED_TARGETS) is FeatureAccessResult.Allowed
    val micActive = requested.isMicCurveActive && requested.micCurve != null && micAllowed

    return requested.copy(
        isMicCurveActive = micActive,
        micCurveStatusText = when {
            micActive -> "Calib.: Active (${requested.micCurve!!.name})"
            requested.micCurve != null && !micAllowed -> "Calib.: Stored (Pro required)"
            else -> "Calib.: None"
        },
        splCalibration = requested.splCalibration.copy(isSplMode = requested.splCalibration.isSplMode && splAllowed),
        isTargetCurveActive = requested.isTargetCurveActive && targetAllowed,
        isNoiseSubtractionActive = requested.isNoiseSubtractionActive && noiseAllowed,
        isCapturingNoiseFloor = requested.isCapturingNoiseFloor && noiseAllowed,
        noiseCaptureProgress = if (noiseAllowed) requested.noiseCaptureProgress else 0.0f
    )
}
