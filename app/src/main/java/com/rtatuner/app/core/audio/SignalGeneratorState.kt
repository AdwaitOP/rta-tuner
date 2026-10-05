package com.rtatuner.app.core.audio

/**
 * Operating mode of the audio test signal generator.
 */
enum class GeneratorMode {
    OFF,
    PINK_NOISE,
    SINE_SWEEP
}

/**
 * Frequency sweep range presets for logarithmic sweep.
 */
enum class SweepRangePreset(
    val label: String,
    val startFreqHz: Float,
    val stopFreqHz: Float
) {
    FULL_SPECTRUM("20Hz - 20kHz", 20.0f, 20000.0f),
    SUB_BASS("20Hz - 200Hz", 20.0f, 200.0f),
    MID_HIGH("200Hz - 20kHz", 200.0f, 20000.0f)
}

/**
 * Immutable state model for the test signal generator.
 */
data class SignalGeneratorState(
    val mode: GeneratorMode = GeneratorMode.OFF,
    val isPlaying: Boolean = false,
    val outputLevelDb: Float = -12.0f, // Safe default level: -12 dBFS
    val sweepDurationSec: Float = 5.0f, // 2.5s, 5.0s, 10.0s
    val sweepRange: SweepRangePreset = SweepRangePreset.FULL_SPECTRUM,
    val isSweepLooping: Boolean = true,
    val currentSweepProgress: Float = 0.0f, // 0.0 to 1.0
    val currentSweepFreqHz: Float = 20.0f,
    val errorMessage: String? = null
)
