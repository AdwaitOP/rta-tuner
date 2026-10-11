package com.rtatuner.app

import android.content.Context
import com.rtatuner.app.core.audio.AudioCaptureConfig
import com.rtatuner.app.core.audio.AudioCaptureDiagnostics
import com.rtatuner.app.core.audio.AudioCaptureUiState
import com.rtatuner.app.core.audio.CaptureStatus
import com.rtatuner.app.core.audio.FftDisplayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Deterministic hardware boundary; the tests still execute the real ViewModel and policy. */
@Implements(className = "com.rtatuner.app.core.audio.AudioCaptureManager", isInAndroidSdk = false)
class ShadowAudioCaptureManager {
    companion object {
        val instances = mutableListOf<ShadowAudioCaptureManager>()
    }

    lateinit var constructedConfig: AudioCaptureConfig
        private set
    lateinit var state: MutableStateFlow<AudioCaptureUiState>
        private set
    var preferredDeviceId: Int? = null
        private set
    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var releaseCalls = 0
        private set
    var onRelease: (() -> Unit)? = null

    @Implementation
    fun __constructor__(@Suppress("UNUSED_PARAMETER") context: Context, config: AudioCaptureConfig) {
        constructedConfig = config
        preferredDeviceId = config.preferredDeviceId
        state = MutableStateFlow(AudioCaptureUiState(
            diagnostics = AudioCaptureDiagnostics(windowSize = config.windowSize, hopSize = config.hopSize),
            rawFftDb = FloatArray(config.windowSize / 2 + 1) { -100f },
            selectedDeviceId = preferredDeviceId,
            routedDeviceId = preferredDeviceId
        ))
        instances.add(this)
    }

    @Implementation
    fun getUiState(): StateFlow<AudioCaptureUiState> = state

    @Implementation
    fun startCapture(): Boolean {
        startCalls++
        state.value = state.value.copy(status = CaptureStatus.RUNNING)
        return true
    }

    @Implementation
    fun stopCapture() {
        stopCalls++
        state.value = state.value.copy(status = CaptureStatus.STOPPED)
    }

    @Implementation
    fun release() {
        releaseCalls++
        state.value = state.value.copy(status = CaptureStatus.STOPPED)
        onRelease?.invoke()
    }

    @Implementation
    fun setPreferredInputDevice(deviceId: Int?) {
        preferredDeviceId = deviceId
        state.value = state.value.copy(selectedDeviceId = deviceId, routedDeviceId = deviceId)
    }

    @Implementation
    fun setDisplayMode(mode: FftDisplayMode) {
        state.value = state.value.copy(displayMode = mode)
    }

    @Implementation
    fun refreshInputDevices() = Unit

    @Implementation
    fun dismissDevicePrompt() = Unit
}
