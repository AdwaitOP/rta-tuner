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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

import com.rtatuner.app.core.audio.SignalGenerator
import com.rtatuner.app.core.audio.SignalGeneratorState
import com.rtatuner.app.core.audio.SweepRangePreset
import com.rtatuner.app.core.audio.GeneratorMode

/**
 * ViewModel orchestrating audio capture lifecycle, display configuration, and UI state.
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

    private val _uiState = MutableStateFlow(AudioCaptureUiState())
    val uiState: StateFlow<AudioCaptureUiState> = _uiState.asStateFlow()

    private var captureCollectorJob: Job? = null
    private var generatorCollectorJob: Job? = null
    private var ballisticsJob: Job? = null
    private var peakHoldDb = AudioMath.MIN_DB_FS
    private var peakHoldTimestamp = 0L

    init {
        startCollectingCaptureState()
        startCollectingGeneratorState()
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
                val currentPeak = mgrState.peakDb

                // Broadband Peak hold ballistic update
                if (currentPeak >= peakHoldDb) {
                    peakHoldDb = currentPeak
                    peakHoldTimestamp = now
                }

                _uiState.value = mgrState.copy(
                    peakHoldDb = peakHoldDb,
                    displayMode = _uiState.value.displayMode,
                    displayConfig = _uiState.value.displayConfig,
                    diagnostics = mgrState.diagnostics.copy(
                        renderFps = _uiState.value.diagnostics.renderFps,
                        renderDrawTimeUs = _uiState.value.diagnostics.renderDrawTimeUs
                    ),
                    generator = _uiState.value.generator
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

                if (_uiState.value.status == CaptureStatus.RUNNING) {
                    val timeSincePeak = now - peakHoldTimestamp
                    if (timeSincePeak > HOLD_DURATION_MS) {
                        val decayed = peakHoldDb - (DECAY_RATE_DB_PER_SEC * dt)
                        peakHoldDb = max(AudioMath.MIN_DB_FS, max(decayed, _uiState.value.peakDb))
                        _uiState.value = _uiState.value.copy(peakHoldDb = peakHoldDb)
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
        peakHoldDb = AudioMath.MIN_DB_FS
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

    override fun onCleared() {
        super.onCleared()
        ballisticsJob?.cancel()
        captureCollectorJob?.cancel()
        generatorCollectorJob?.cancel()
        captureManager.stopCapture()
        signalGenerator.stop()
    }
}
