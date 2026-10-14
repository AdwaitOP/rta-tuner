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
    val requestedDeviceName: String = "Auto (System Default)",
    val actualRoutedDeviceName: String = "Default Mic",
    val actualRoutedDeviceType: String = "Built-in",
    val isUsbDevice: Boolean = false,
    val isRoutingMismatch: Boolean = false,
    val isBluetoothBandwidthLimited: Boolean = false,
    val bluetoothWarningText: String? = null,
    val windowSize: Int = 2048,
    val hopSize: Int = 1024,
    val bufferSizeInBytes: Int = 0,
    val minBufferSizeInBytes: Int = 0,
    val totalFramesCaptured: Long = 0L,
    val fps: Float = 0f,
    val consecutiveZeroReads: Int = 0,
    val fftExecutionTimeUs: Long = 0L,
    val filterBankExecutionTimeUs: Long = 0L,
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
    val diagnostics: AudioCaptureDiagnostics = AudioCaptureDiagnostics(),
    val generator: SignalGeneratorState = SignalGeneratorState(),
    val calibration: com.rtatuner.app.core.calibration.CalibrationUiState = com.rtatuner.app.core.calibration.CalibrationUiState(),
    val unreliableBands: BooleanArray = BooleanArray(Iso31Bands.NUM_BANDS) { false },
    val targetDeltas31Db: FloatArray? = null,
    val availableInputDevices: List<AudioInputDeviceInfo> = emptyList(),
    val selectedDeviceId: Int? = null,
    val routedDeviceId: Int? = null,
    val deviceAttachedPrompt: AudioInputDeviceInfo? = null,
    val isRoutingMismatch: Boolean = false,
    val routingMismatchMessage: String? = null,
    val isBluetoothBandwidthLimited: Boolean = false,
    val bluetoothWarningText: String? = null,
    val shouldResetSmoothing: Boolean = false,
    val frameContext: CapturedFrameContext? = null
) {
    val captureManagerId: String get() = frameContext?.managerId ?: ""
    val captureGeneration: Long get() = frameContext?.captureGeneration ?: 0L
    val frameSequence: Long get() = frameContext?.frameSequence ?: 0L
    val captureFingerprint: CaptureFingerprint? get() = frameContext?.fingerprint

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
        if (generator != other.generator) return false
        if (calibration != other.calibration) return false
        if (!unreliableBands.contentEquals(other.unreliableBands)) return false
        if (targetDeltas31Db != null) {
            if (other.targetDeltas31Db == null || !targetDeltas31Db.contentEquals(other.targetDeltas31Db)) return false
        } else if (other.targetDeltas31Db != null) return false
        if (availableInputDevices != other.availableInputDevices) return false
        if (selectedDeviceId != other.selectedDeviceId) return false
        if (routedDeviceId != other.routedDeviceId) return false
        if (deviceAttachedPrompt != other.deviceAttachedPrompt) return false
        if (isRoutingMismatch != other.isRoutingMismatch) return false
        if (routingMismatchMessage != other.routingMismatchMessage) return false
        if (isBluetoothBandwidthLimited != other.isBluetoothBandwidthLimited) return false
        if (bluetoothWarningText != other.bluetoothWarningText) return false
        if (shouldResetSmoothing != other.shouldResetSmoothing) return false
        if (frameContext != other.frameContext) return false

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
        result = 31 * result + generator.hashCode()
        result = 31 * result + calibration.hashCode()
        result = 31 * result + unreliableBands.contentHashCode()
        result = 31 * result + (targetDeltas31Db?.contentHashCode() ?: 0)
        result = 31 * result + availableInputDevices.hashCode()
        result = 31 * result + (selectedDeviceId ?: 0)
        result = 31 * result + (routedDeviceId ?: 0)
        result = 31 * result + (deviceAttachedPrompt?.hashCode() ?: 0)
        result = 31 * result + isRoutingMismatch.hashCode()
        result = 31 * result + (routingMismatchMessage?.hashCode() ?: 0)
        result = 31 * result + isBluetoothBandwidthLimited.hashCode()
        result = 31 * result + (bluetoothWarningText?.hashCode() ?: 0)
        result = 31 * result + shouldResetSmoothing.hashCode()
        result = 31 * result + (frameContext?.hashCode() ?: 0)
        return result
    }
}
