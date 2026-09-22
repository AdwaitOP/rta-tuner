package com.rtatuner.app.core.audio

import com.rtatuner.app.core.dsp.Iso31Bands
import com.rtatuner.app.ui.RtaDisplayConfig

/**
 * Display modes for the RTA spectrum visualizer.
 */
enum class FftDisplayMode {
    /** Standard 31-band 1/3-octave smoothed bar graph (ISO 266 center frequencies) */
    OCTAVE_1_3,
    /** Full-resolution raw FFT linear spectrum (up to 2048 bins) */
    RAW_FFT
}

/**
 * Lifecycle states of the audio capture engine.
 */
enum class CaptureStatus {
    /** Capture is idle and unallocated */
    STOPPED,
    /** Pipeline is probing hardware, sample rates, and initializing AudioRecord */
    INITIALIZING,
    /** Capture thread is actively reading raw PCM from hardware */
    RUNNING,
    /** An unrecoverable hardware or OS error interrupted capture */
    ERROR
}

/**
 * Diagnostics information exposed for live hardware and pipeline verification.
 */
data class AudioCaptureDiagnostics(
    val activeAudioSource: String = "None",
    val requestedSampleRate: Int = 0,
    val actualSampleRate: Int = 0,
    val detectedInputRate: Int? = null,
    val detectedOutputRate: Int? = null,
    val inputDeviceName: String = "Default Mic",
    val windowSize: Int = 2048,
    val hopSize: Int = 1024,
    val bufferSizeInBytes: Int = 0,
    val minBufferSizeInBytes: Int = 0,
    val totalFramesCaptured: Long = 0L,
    val fps: Float = 0f,
    val consecutiveZeroReads: Int = 0,
    val fftExecutionTimeUs: Long = 0L,
    val renderFps: Float = 60.0f,
    val renderDrawTimeUs: Long = 0L
)

/**
 * State exposed to the Jetpack Compose UI layer.
 */
data class AudioCaptureUiState(
    val status: CaptureStatus = CaptureStatus.STOPPED,
    val errorMessage: String? = null,
    val rmsDb: Float = -100f,
    val peakDb: Float = -100f,
    val peakHoldDb: Float = -100f,
    val isClipping: Boolean = false,
    val displayMode: FftDisplayMode = FftDisplayMode.OCTAVE_1_3,
    val displayConfig: RtaDisplayConfig = RtaDisplayConfig(),
    val octave31Db: FloatArray = FloatArray(Iso31Bands.NUM_BANDS) { -100f },
    val rawFftDb: FloatArray = FloatArray(1025) { -100f },
    val diagnostics: AudioCaptureDiagnostics = AudioCaptureDiagnostics()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioCaptureUiState

        if (status != other.status) return false
        if (errorMessage != other.errorMessage) return false
        if (rmsDb != other.rmsDb) return false
        if (peakDb != other.peakDb) return false
        if (peakHoldDb != other.peakHoldDb) return false
        if (isClipping != other.isClipping) return false
        if (displayMode != other.displayMode) return false
        if (displayConfig != other.displayConfig) return false
        if (!octave31Db.contentEquals(other.octave31Db)) return false
        if (!rawFftDb.contentEquals(other.rawFftDb)) return false
        if (diagnostics != other.diagnostics) return false

        return true
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + rmsDb.hashCode()
        result = 31 * result + peakDb.hashCode()
        result = 31 * result + peakHoldDb.hashCode()
        result = 31 * result + isClipping.hashCode()
        result = 31 * result + displayMode.hashCode()
        result = 31 * result + displayConfig.hashCode()
        result = 31 * result + octave31Db.contentHashCode()
        result = 31 * result + rawFftDb.contentHashCode()
        result = 31 * result + diagnostics.hashCode()
        return result
    }
}
