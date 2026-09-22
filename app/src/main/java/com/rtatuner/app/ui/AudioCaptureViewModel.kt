package com.rtatuner.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rtatuner.app.core.audio.AudioCaptureConfig
import com.rtatuner.app.core.audio.AudioCaptureManager
import com.rtatuner.app.core.audio.AudioCaptureUiState
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.audio.CaptureStatus
import com.rtatuner.app.core.audio.FftDisplayMode
import com.rtatuner.app.core.audio.GeneratorMode
import com.rtatuner.app.core.audio.SignalGenerator
import com.rtatuner.app.core.audio.SignalGeneratorState
import com.rtatuner.app.core.audio.SweepRangePreset
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.calibration.CalibrationUiState
import com.rtatuner.app.core.calibration.MicCalibrationCurve
import com.rtatuner.app.core.calibration.TargetCurve
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * ViewModel orchestrating audio capture lifecycle, signal generation, calibration, and UI state.
 */
class AudioCaptureViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val HOLD_DURATION_MS = 1200L
        private const val DECAY_RATE_DB_PER_SEC = 25.0f
        private const val BALLISTICS_TICK_MS = 33L // ~30 fps meter update
    }

    private var currentWindowSize = 2048
    private var captureManager: AudioCaptureManager = createCaptureManager(currentWindowSize)
    val signalGenerator: SignalGenerator = SignalGenerator(sampleRate = 48000)
    val calibrationManager: CalibrationManager = CalibrationManager(application)

    private val _uiState = MutableStateFlow(AudioCaptureUiState())
    val uiState: StateFlow<AudioCaptureUiState> = _uiState.asStateFlow()

    private var captureCollectorJob: Job? = null
    private var generatorCollectorJob: Job? = null
    private var calibrationCollectorJob: Job? = null
    private var ballisticsJob: Job? = null
    private var peakHoldDb = AudioMath.MIN_DB_FS
    private var peakHoldTimestamp = 0L

    init {
        startCollectingCaptureState()
        startCollectingGeneratorState()
        startCollectingCalibrationState()
        startBallisticsTicker()
    }

    private fun startCollectingGeneratorState() {
        generatorCollectorJob?.cancel()
        generatorCollectorJob = viewModelScope.launch {
            signalGenerator.state.collect { genState ->
                _uiState.value = _uiState.value.copy(generator = genState)
            }
        }
    }

    private fun startCollectingCalibrationState() {
        calibrationCollectorJob?.cancel()
        calibrationCollectorJob = viewModelScope.launch {
            calibrationManager.uiState.collect { calState ->
                val updatedConfig = _uiState.value.displayConfig.copy(
                    isSplMode = calState.splCalibration.isSplMode,
                    splOffsetDb = calState.splCalibration.splOffsetDb,
                    isTargetCurveActive = calState.isTargetCurveActive,
                    targetCurve = calState.targetCurve
                )
                _uiState.value = _uiState.value.copy(
                    calibration = calState,
                    displayConfig = updatedConfig
                )
            }
        }
    }

    private fun createCaptureManager(windowSize: Int): AudioCaptureManager {
        return AudioCaptureManager(
            context = getApplication<Application>().applicationContext,
            config = AudioCaptureConfig(
                windowSize = windowSize,
                hopSize = windowSize / 2
            )
        )
    }

    private fun startCollectingCaptureState() {
        captureCollectorJob?.cancel()
        captureCollectorJob = viewModelScope.launch {
            captureManager.uiState.collect { mgrState ->
                val now = System.currentTimeMillis()

                // Pass raw audio spectral data through calibration engine (mic curve, noise subtraction, SPL, target deltas)
                val diag = mgrState.diagnostics
                val binRes = if (diag.windowSize > 0 && diag.actualSampleRate > 0) {
                    diag.actualSampleRate.toFloat() / diag.windowSize
                } else {
                    23.4375f
                }

                val calFrame = calibrationManager.processFrame(
                    raw31Bands = mgrState.octave31Db,
                    rawFftDb = mgrState.rawFftDb,
                    binResolutionHz = binRes
                )

                val rawSpl = if (calFrame.isSplMode) calFrame.splOffsetDb else 0.0f
                val splOffset = if (rawSpl.isNaN() || rawSpl.isInfinite()) 0.0f else rawSpl

                val rawPeak = if (mgrState.peakDb.isNaN() || mgrState.peakDb.isInfinite()) AudioMath.MIN_DB_FS else mgrState.peakDb
                val rawRms = if (mgrState.rmsDb.isNaN() || mgrState.rmsDb.isInfinite()) AudioMath.MIN_DB_FS else mgrState.rmsDb

                val effectivePeak = rawPeak + splOffset
                val effectiveRms = rawRms + splOffset

                // Broadband Peak hold ballistic update (clamped safely)
                if (peakHoldDb.isNaN() || peakHoldDb.isInfinite() || effectivePeak >= peakHoldDb) {
                    peakHoldDb = effectivePeak
                    peakHoldTimestamp = now
                }

                _uiState.value = mgrState.copy(
                    rmsDb = effectiveRms,
                    peakDb = effectivePeak,
                    peakHoldDb = peakHoldDb,
                    displayMode = _uiState.value.displayMode,
                    displayConfig = _uiState.value.displayConfig,
                    octave31Db = calFrame.calibrated31Db,
                    rawFftDb = calFrame.calibratedRawFftDb,
                    unreliableBands = calFrame.unreliableBands,
                    targetDeltas31Db = calFrame.targetDeltas31Db,
                    diagnostics = mgrState.diagnostics.copy(
                        renderFps = _uiState.value.diagnostics.renderFps,
                        renderDrawTimeUs = _uiState.value.diagnostics.renderDrawTimeUs
                    ),
                    generator = _uiState.value.generator,
                    calibration = calibrationManager.uiState.value
                )
            }
        }
    }

    private fun startBallisticsTicker() {
        ballisticsJob?.cancel()
        ballisticsJob = viewModelScope.launch {
            var lastTick = System.currentTimeMillis()
            while (isActive) {
                delay(BALLISTICS_TICK_MS)
                val now = System.currentTimeMillis()
                val dt = (now - lastTick) / 1000f
                lastTick = now

                val rawSpl = if (_uiState.value.calibration.splCalibration.isSplMode) {
                    _uiState.value.calibration.splCalibration.splOffsetDb
                } else 0.0f
                val splOffset = if (rawSpl.isNaN() || rawSpl.isInfinite()) 0.0f else rawSpl
                val floorDb = AudioMath.MIN_DB_FS + splOffset

                if (_uiState.value.status == CaptureStatus.RUNNING) {
                    val currentPeak = _uiState.value.peakDb
                    val safePeak = if (currentPeak.isNaN() || currentPeak.isInfinite()) floorDb else currentPeak

                    if (peakHoldDb.isNaN() || peakHoldDb.isInfinite()) {
                        peakHoldDb = safePeak
                        peakHoldTimestamp = now
                    } else {
                        val timeSincePeak = now - peakHoldTimestamp
                        if (timeSincePeak > HOLD_DURATION_MS) {
                            val decayed = peakHoldDb - (DECAY_RATE_DB_PER_SEC * dt)
                            peakHoldDb = max(floorDb, max(decayed, safePeak))
                            if (peakHoldDb.isNaN() || peakHoldDb.isInfinite()) {
                                peakHoldDb = floorDb
                            }
                            _uiState.value = _uiState.value.copy(peakHoldDb = peakHoldDb)
                        }
                    }
                }
            }
        }
    }

    fun startCapture() {
        resetPeakHold()
        captureManager.startCapture()
    }

    fun stopCapture() {
        captureManager.stopCapture()
    }

    fun retryCapture() {
        captureManager.stopCapture()
        startCapture()
    }

    fun resetPeakHold() {
        val rawSpl = if (_uiState.value.calibration.splCalibration.isSplMode) {
            _uiState.value.calibration.splCalibration.splOffsetDb
        } else 0.0f
        val splOffset = if (rawSpl.isNaN() || rawSpl.isInfinite()) 0.0f else rawSpl
        peakHoldDb = AudioMath.MIN_DB_FS + splOffset
        peakHoldTimestamp = System.currentTimeMillis()
        _uiState.value = _uiState.value.copy(peakHoldDb = peakHoldDb)
    }

    fun setFftDisplayMode(mode: FftDisplayMode) {
        val updatedConfig = _uiState.value.displayConfig.copy(displayMode = mode)
        captureManager.setDisplayMode(mode)
        _uiState.value = _uiState.value.copy(
            displayMode = mode,
            displayConfig = updatedConfig
        )
    }

    fun setEmaPreset(preset: EmaPreset) {
        val updatedConfig = _uiState.value.displayConfig.copy(emaPreset = preset)
        _uiState.value = _uiState.value.copy(displayConfig = updatedConfig)
    }

    fun setDbRange(minDb: Float, maxDb: Float = 0.0f) {
        val updatedConfig = _uiState.value.displayConfig.copy(minDb = minDb, maxDb = maxDb)
        _uiState.value = _uiState.value.copy(displayConfig = updatedConfig)
    }

    fun setPeakHoldSettings(durationMs: Long, decayRateDbPerSec: Float) {
        val updatedConfig = _uiState.value.displayConfig.copy(
            peakHoldDurationMs = durationMs,
            peakDecayRateDbPerSec = decayRateDbPerSec
        )
        _uiState.value = _uiState.value.copy(displayConfig = updatedConfig)
    }

    fun updateRenderTelemetry(fps: Float, drawTimeUs: Long) {
        _uiState.value = _uiState.value.let { current ->
            current.copy(
                diagnostics = current.diagnostics.copy(
                    renderFps = fps,
                    renderDrawTimeUs = drawTimeUs
                )
            )
        }
    }

    fun setWindowSize(newSize: Int) {
        if (newSize == currentWindowSize) return
        require(newSize == 1024 || newSize == 2048 || newSize == 4096) {
            "Unsupported window size: $newSize"
        }

        val wasRunning = _uiState.value.status == CaptureStatus.RUNNING
        captureManager.stopCapture()

        currentWindowSize = newSize
        captureManager = createCaptureManager(newSize)
        startCollectingCaptureState()

        if (wasRunning) {
            captureManager.startCapture()
        }
    }

    // --- Stage 4 Test Signal Generator Controls ---

    fun startPinkNoise() {
        signalGenerator.startPinkNoise(_uiState.value.generator.outputLevelDb)
    }

    fun startSineSweep() {
        val gen = _uiState.value.generator
        signalGenerator.startSineSweep(
            range = gen.sweepRange,
            durationSec = gen.sweepDurationSec,
            isLooping = gen.isSweepLooping,
            levelDb = gen.outputLevelDb
        )
    }

    fun stopGenerator() {
        signalGenerator.stop()
    }

    fun setGeneratorVolume(levelDb: Float) {
        signalGenerator.setOutputLevel(levelDb)
    }

    fun setSweepDuration(durationSec: Float) {
        signalGenerator.setSweepDuration(durationSec)
    }

    fun setSweepRange(range: SweepRangePreset) {
        signalGenerator.setSweepRange(range)
    }

    fun setSweepLooping(looping: Boolean) {
        signalGenerator.setSweepLooping(looping)
    }

    // --- Stage 5 Calibration & Target Curve Actions ---

    fun loadMicCurve(name: String, textContent: String): Result<MicCalibrationCurve> {
        return calibrationManager.loadMicCurve(name, textContent)
    }

    fun setMicCurveActive(active: Boolean) {
        calibrationManager.setMicCurveActive(active)
    }

    fun clearMicCurve() {
        calibrationManager.clearMicCurve()
    }

    fun calibrateSplReference(targetSplDb: Float) {
        val rawRmsDbFs = if (_uiState.value.status == CaptureStatus.RUNNING) {
            if (_uiState.value.calibration.splCalibration.isSplMode) {
                _uiState.value.rmsDb - _uiState.value.calibration.splCalibration.splOffsetDb
            } else {
                _uiState.value.rmsDb
            }
        } else {
            -23.01f
        }
        calibrationManager.calibrateSplReference(rawRmsDbFs, targetSplDb)
        resetPeakHold()
    }

    fun setSplMode(enabled: Boolean) {
        calibrationManager.setSplMode(enabled)
        resetPeakHold()
    }

    fun setTargetCurve(curve: TargetCurve?) {
        calibrationManager.setTargetCurve(curve)
    }

    fun setTargetCurveActive(active: Boolean) {
        calibrationManager.setTargetCurveActive(active)
    }

    fun startNoiseFloorCapture() {
        calibrationManager.startNoiseFloorCapture(2000L)
    }

    fun setNoiseSubtractionActive(active: Boolean) {
        calibrationManager.setNoiseSubtractionActive(active)
    }

    fun clearNoiseFloor() {
        calibrationManager.clearNoiseFloor()
    }

    override fun onCleared() {
        super.onCleared()
        ballisticsJob?.cancel()
        captureCollectorJob?.cancel()
        generatorCollectorJob?.cancel()
        calibrationCollectorJob?.cancel()
        captureManager.stopCapture()
        signalGenerator.stop()
    }
}
