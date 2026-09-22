package com.rtatuner.app.ui

import com.rtatuner.app.core.audio.FftDisplayMode

/**
 * Temporal smoothing presets for Exponential Moving Average (EMA).
 */
enum class EmaPreset(
    val label: String,
    val timeConstantMs: Long,
    val alpha: Float
) {
    OFF("OFF", 0L, 1.0f),
    FAST("FAST (50ms)", 50L, 0.35f),
    MEDIUM("MED (150ms)", 150L, 0.13f),
    SLOW("SLOW (450ms)", 450L, 0.046f)
}

/**
 * Configuration options for the SurfaceView spectrum visualizer.
 */
data class RtaDisplayConfig(
    val displayMode: FftDisplayMode = FftDisplayMode.OCTAVE_1_3,
    val emaPreset: EmaPreset = EmaPreset.MEDIUM,
    val minDb: Float = -80.0f,
    val maxDb: Float = 0.0f,
    val peakHoldDurationMs: Long = 1200L,
    val peakDecayRateDbPerSec: Float = 25.0f,
    val isSplMode: Boolean = false,
    val splOffsetDb: Float = 0.0f,
    val isTargetCurveActive: Boolean = false,
    val targetCurve: com.rtatuner.app.core.calibration.TargetCurve? = null,
    val targetBaselineDb: Float = -20.0f
)

