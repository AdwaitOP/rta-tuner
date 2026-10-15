package com.rtatuner.app.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import com.rtatuner.app.core.dsp.BiquadFilterBank
import com.rtatuner.app.core.dsp.DspResponseRevision
import com.rtatuner.app.core.dsp.FftProcessor
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Abstraction over AudioRecord hardware operations to ensure testability,
 * deterministic mocking, and strict generation ownership.
 */
@Suppress("DEPRECATION")
interface AudioRecordSource {
    val state: Int
    val recordingState: Int
    val routedDevice: AudioDeviceInfo?
    fun setPreferredDevice(device: AudioDeviceInfo?): Boolean
    fun addOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener, handler: Handler?)
    fun removeOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener)
    fun startRecording()
    fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, readMode: Int): Int
    fun stop()
    fun release()
}

/**
 * Factory for creating [AudioRecordSource] instances.
 */
fun interface AudioRecordSourceFactory {
    fun create(
        audioSource: Int,
        sampleRate: Int,
        channelConfig: Int,
        audioEncoding: Int,
        bufferSizeInBytes: Int
    ): AudioRecordSource?
}

/**
 * Default production implementation wrapping Android framework AudioRecord.
 */
@Suppress("DEPRECATION")
class AndroidAudioRecordSource(val record: AudioRecord) : AudioRecordSource {
    override val state: Int get() = record.state
    override val recordingState: Int get() = record.recordingState
    override val routedDevice: AudioDeviceInfo? get() = record.routedDevice
    override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean = record.setPreferredDevice(device)
    override fun addOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener, handler: Handler?) {
        record.addOnRoutingChangedListener(listener, handler)
    }
    override fun removeOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener) {
        record.removeOnRoutingChangedListener(listener)
    }
    override fun startRecording() = record.startRecording()
    override fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, readMode: Int): Int =
        record.read(audioData, offsetInShorts, sizeInShorts, readMode)
    override fun stop() = record.stop()
    override fun release() = record.release()
}

/**
 * Default production factory creating real AudioRecord instances.
 */
class DefaultAudioRecordSourceFactory : AudioRecordSourceFactory {
    @SuppressLint("MissingPermission")
    override fun create(
        audioSource: Int,
        sampleRate: Int,
        channelConfig: Int,
        audioEncoding: Int,
        bufferSizeInBytes: Int
    ): AudioRecordSource? {
        val record = AudioRecord(audioSource, sampleRate, channelConfig, audioEncoding, bufferSizeInBytes)
        return AndroidAudioRecordSource(record)
    }
}

/**
 * Immutable configuration snapshot for a specific capture initialization attempt.
 * Preserves FFT window/hop, audio source, requested device, and rate settings (B01).
 */
internal data class CaptureAttemptConfig(
    val token: Long,
    val windowSize: Int,
    val hopSize: Int,
    val preferredAudioSource: Int,
    val preferredDeviceId: Int?,
    val targetSampleRate: Int?
)

/**
 * Represents a single lifecycle generation of audio capture.
 * Encapsulates the hardware record source, listeners, and resources.
 * Ensures that stale background threads cannot release or corrupt newer sessions (B01).
 * Synchronizes hardware start and terminal cleanup on a dedicated sessionLock (B03)
 * so that an invalidated worker never calls startRecording() on a released recorder.
 */
@Suppress("DEPRECATION")
internal class CaptureSession(
    val attemptToken: Long,
    val generation: Long,
    val record: AudioRecordSource,
    var routingListener: AudioRecord.OnRoutingChangedListener? = null,
    val targetDevice: AudioDeviceInfo?,
    val requestedRate: Int,
    val actualRate: Int,
    val windowSize: Int,
    val hopSize: Int,
    val selectedSource: Int = MediaRecorder.AudioSource.UNPROCESSED,
    val actualSource: Int = MediaRecorder.AudioSource.UNPROCESSED,
    val effectsInfo: CaptureEffectsInfo = CaptureEffectsInfo()
) {
    @Volatile
    var currentFingerprint: CaptureFingerprint = CaptureFingerprint(
        dspRevision = DspResponseRevision.CURRENT,
        actualSampleRate = actualRate,
        routedMicrophoneId = CaptureFingerprint.UNKNOWN_MICROPHONE_ID,
        routedDeviceType = CaptureFingerprint.UNKNOWN_DEVICE_TYPE,
        selectedAudioSource = selectedSource,
        actualAudioSource = actualSource,
        isSourceFallback = (selectedSource != actualSource),
        effects = effectsInfo
    )

    @Volatile
    var routeEpoch: Long = 0L

    private val sessionLock = Any()
    @Volatile
    private var isCleanedUp = false
    @Volatile
    private var isRecordingStarted = false

    fun isCleanedUp(): Boolean = isCleanedUp

    /**
     * Attempts to start recording under sessionLock.
     * Prevents race condition B03: if cleanup() has already started or completed,
     * startRecording() returns false immediately without invoking record.startRecording().
     * Handles synthetic reentrancy where cleanup is invoked synchronously during startRecording.
     */
    fun startRecording(): Boolean {
        synchronized(sessionLock) {
            if (isCleanedUp) {
                return false
            }
            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.e("AudioCaptureManager", "Exception in record.startRecording for session $generation", e)
                throw e
            }
            if (isCleanedUp) {
                return false
            }
            val recState = try {
                record.recordingState
            } catch (e: Exception) {
                Log.w("AudioCaptureManager", "Exception querying recordingState after start in session $generation", e)
                AudioRecord.RECORDSTATE_STOPPED
            }
            if (recState == AudioRecord.RECORDSTATE_RECORDING) {
                isRecordingStarted = true
                return true
            }
            return false
        }
    }

    /**
     * Cleans up the session and releases the hardware recorder.
     * Synchronized on sessionLock to prevent race condition B03 (overlapping with startRecording).
     * Guaranteed idempotent (runs at most once).
     * Hardware stop, release, and listener removal operations execute outside any manager-wide lock (B02).
     * Fully exception-resilient (B06): failures querying state or releasing never prevent release attempt.
     */
    fun cleanup() {
        var listenerToRemove: AudioRecord.OnRoutingChangedListener? = null
        var shouldStop = false

        synchronized(sessionLock) {
            if (isCleanedUp) {
                return
            }
            isCleanedUp = true
            listenerToRemove = routingListener
            routingListener = null
            shouldStop = isRecordingStarted
        }

        var fatalError: Throwable? = null

        fun recordFatal(t: Throwable) {
            if (fatalError == null) {
                fatalError = t
            } else if (fatalError !== t) {
                try {
                    fatalError?.addSuppressed(t)
                } catch (_: Throwable) { }
            }
        }

        if (!shouldStop) {
            try {
                shouldStop = (record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            } catch (e: Exception) {
                Log.w("AudioCaptureManager", "Exception querying recordingState during cleanup in session $generation", e)
            } catch (t: Throwable) {
                recordFatal(t)
            }
        }

        if (listenerToRemove != null) {
            try {
                record.removeOnRoutingChangedListener(listenerToRemove)
            } catch (e: Exception) {
                Log.w("AudioCaptureManager", "Error removing routing listener in session $generation", e)
            } catch (t: Throwable) {
                recordFatal(t)
            }
        }

        if (shouldStop) {
            try {
                record.stop()
            } catch (e: Exception) {
                Log.w("AudioCaptureManager", "Exception stopping record in session $generation", e)
            } catch (t: Throwable) {
                recordFatal(t)
            }
        }

        try {
            record.release()
        } catch (e: Exception) {
            Log.w("AudioCaptureManager", "Exception releasing record in session $generation", e)
        } catch (t: Throwable) {
            recordFatal(t)
        }

        fatalError?.let { throw it }
    }
}

/**
 * Manages low-latency raw audio acquisition from device hardware.
 *
 * Implements:
 * - Hardware native sample rate negotiation (input device rates prioritized)
 * - Safe source negotiation: UNPROCESSED (raw PCM, no AGC/AEC/NS) with fallback to MIC
 * - Defensive buffer size validation (preventing negative AudioRecord error returns)
 * - Explicit capture generation / session ownership preventing stale worker interference
 * - Contiguous partial-read accumulation and mathematical equivalence (F05)
 * - Atomic lifecycle synchronization preventing publication races and status corruption (F04)
 * - Dedicated capture thread running at Process.THREAD_PRIORITY_URGENT_AUDIO
 * - 50% overlap sliding window buffer
 * - Zero-allocation RMS/Peak math and clipping detection
 * - Resilient error/stall interception (detecting DEAD_OBJECT, mic theft, 0-byte reads)
 */
class AudioCaptureManager(
    private val context: Context,
    private val config: AudioCaptureConfig = AudioCaptureConfig()
) {
    internal var audioRecordFactory: AudioRecordSourceFactory = DefaultAudioRecordSourceFactory()
    internal var minBufferSizeProvider: (sampleRate: Int, channelConfig: Int, audioFormat: Int) -> Int =
        { sr, ch, fmt -> AudioRecord.getMinBufferSize(sr, ch, fmt) }

    internal constructor(
        context: Context,
        config: AudioCaptureConfig,
        audioRecordFactory: AudioRecordSourceFactory,
        minBufferSizeProvider: (sampleRate: Int, channelConfig: Int, audioFormat: Int) -> Int =
            { sr, ch, fmt -> AudioRecord.getMinBufferSize(sr, ch, fmt) }
    ) : this(context, config) {
        this.audioRecordFactory = audioRecordFactory
        this.minBufferSizeProvider = minBufferSizeProvider
    }
    companion object {
        private const val TAG = "AudioCaptureManager"
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val FALLBACK_SAMPLE_RATE = 44100
        private const val MAX_CONSECUTIVE_ZERO_READS = 10
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Unique monotonic/UUID identifier for this AudioCaptureManager instance */
    val managerId: String = UUID.randomUUID().toString()

    private fun probeAcousticEffects(): CaptureEffectsInfo {
        return try {
            CaptureEffectsInfo(
                aecAvailable = android.media.audiofx.AcousticEchoCanceler.isAvailable(),
                agcAvailable = android.media.audiofx.AutomaticGainControl.isAvailable(),
                nsAvailable = android.media.audiofx.NoiseSuppressor.isAvailable(),
                isEffectsKnown = true
            )
        } catch (_: Throwable) {
            CaptureEffectsInfo(
                aecAvailable = false,
                agcAvailable = false,
                nsAvailable = false,
                isEffectsKnown = false
            )
        }
    }

    private val routeEpochCounter = AtomicLong(1L)

    // State flow for UI and diagnostics
    private val _uiState = MutableStateFlow(AudioCaptureUiState(
        captureAuthority = CaptureAuthoritySnapshot(managerId = managerId, isRunning = false)
    ))
    val uiState: StateFlow<AudioCaptureUiState> = _uiState.asStateFlow()
    @Deprecated("Legacy unbuffered flow; observe uiState directly instead", ReplaceWith("uiState"))
    val audioFrames: Flow<AudioFrame> = emptyFlow()

    private val isRunning = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0L)
    private var nextAttemptToken = 1L
    private var activeAttemptToken = 0L
    private var nextReconfigurationToken = 1L
    private var activeRestartToken = 0L
    @Volatile
    private var activeSession: CaptureSession? = null
    private var captureThread: Thread? = null

    // Lock coordinating all lifecycle transitions and UI state mutations
    private val lifecycleLock = Any()

    // Deterministic test hooks for intercepting thread execution prior to publication
    internal var onPreHardwareInit: ((attemptToken: Long) -> Unit)? = null
    internal var onPostHardwareInit: ((attemptToken: Long) -> Unit)? = null
    internal var onPreStartRecording: ((session: CaptureSession) -> Unit)? = null
    internal var onPrePublishFrame: ((sessionGeneration: Long) -> Unit)? = null
    internal var onPreRoutingUpdate: (() -> Unit)? = null
    internal var onPreReconfigurationRestart: ((token: Long) -> Unit)? = null
    internal var threadLauncher: (thread: Thread) -> Unit = { it.start() }

    private var activeConfig: AudioCaptureConfig = config

    private fun isFatalError(t: Throwable): Boolean {
        return t is VirtualMachineError || t is ThreadDeath || t is LinkageError
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.i(TAG, "Audio devices added: ${addedDevices?.size ?: 0}")
            refreshInputDevices()
            val externalInput = addedDevices?.firstOrNull { device ->
                device.isSource && (
                    device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_LINE_ANALOG
                )
            }
            if (externalInput != null) {
                synchronized(lifecycleLock) {
                    if (externalInput.id != activeConfig.preferredDeviceId) {
                        val inputInfo = AudioInputDeviceInfo.fromAudioDeviceInfo(externalInput)
                        _uiState.value = _uiState.value.copy(deviceAttachedPrompt = inputInfo)
                    }
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.i(TAG, "Audio devices removed: ${removedDevices?.size ?: 0}")
            refreshInputDevices()
            var sessionToNotify: CaptureSession? = null
            var deviceName = "Active microphone"
            synchronized(lifecycleLock) {
                if (isRunning.get() && activeSession != null) {
                    val currentRoutedDeviceId = _uiState.value.routedDeviceId
                    val targetDeviceId = activeConfig.preferredDeviceId
                    val removedActive = removedDevices?.firstOrNull { dev ->
                        dev.id == targetDeviceId || dev.id == currentRoutedDeviceId
                    }
                    if (removedActive != null) {
                        deviceName = removedActive.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Active microphone"
                        sessionToNotify = activeSession
                    }
                }
            }
            if (sessionToNotify != null) {
                val msg = "$deviceName was disconnected during active capture."
                Log.w(TAG, msg)
                notifyCaptureError(sessionToNotify!!, msg)
            }
        }
    }

    init {
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register AudioDeviceCallback", e)
        }
        refreshInputDevices()
    }

    fun refreshInputDevices() {
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            synchronized(lifecycleLock) {
                val selectedId = activeConfig.preferredDeviceId
                val current = _uiState.value
                val currentRoutedDeviceId = current.routedDeviceId

                val deviceList = devices.map { device ->
                    AudioInputDeviceInfo.fromAudioDeviceInfo(
                        device = device,
                        isSelected = (selectedId != null && device.id == selectedId) || (selectedId == null && device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC),
                        isCurrentlyRouted = currentRoutedDeviceId != null && device.id == currentRoutedDeviceId
                    )
                }

                _uiState.value = current.copy(
                    availableInputDevices = deviceList,
                    selectedDeviceId = selectedId
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query audio input devices", e)
        }
    }

    /**
     * Resolves the primary native sample rate for input capture.
     * Prioritizes active input device capabilities, verifies with output rate,
     * and logs warnings on discrepancy.
     */
    fun resolveSampleRate(): SampleRateResolution {
        val configSnapshot: CaptureAttemptConfig
        synchronized(lifecycleLock) {
            configSnapshot = CaptureAttemptConfig(
                token = 0L,
                windowSize = activeConfig.windowSize,
                hopSize = activeConfig.hopSize,
                preferredAudioSource = activeConfig.preferredAudioSource,
                preferredDeviceId = activeConfig.preferredDeviceId,
                targetSampleRate = activeConfig.targetSampleRate
            )
        }
        return resolveSampleRateForConfig(configSnapshot)
    }

    internal fun resolveSampleRateForConfig(attemptConfig: CaptureAttemptConfig): SampleRateResolution {
        val detectedInputRate = detectInputDeviceSampleRate(attemptConfig.preferredDeviceId)
        val detectedOutputRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()

        // Log discrepancy if input and output rates do not match
        if (detectedInputRate != null && detectedOutputRate != null && detectedInputRate != detectedOutputRate) {
            Log.w(
                TAG,
                "Sample rate discrepancy detected! Native Input Device Rate: $detectedInputRate Hz vs " +
                        "Output Rate: $detectedOutputRate Hz. Locking capture to input rate to avoid software resampling."
            )
        }

        val chosenRate = attemptConfig.targetSampleRate
            ?: detectedInputRate
            ?: detectedOutputRate
            ?: DEFAULT_SAMPLE_RATE

        return SampleRateResolution(
            resolvedRate = chosenRate,
            detectedInputRate = detectedInputRate,
            detectedOutputRate = detectedOutputRate
        )
    }

    private fun detectInputDeviceSampleRate(targetDeviceId: Int? = null): Int? {
        try {
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            if (targetDeviceId != null) {
                val target = inputDevices.firstOrNull { it.id == targetDeviceId }
                if (target != null) {
                    val rates = target.sampleRates
                    if (rates.isNotEmpty()) {
                        if (rates.contains(DEFAULT_SAMPLE_RATE)) return DEFAULT_SAMPLE_RATE
                        if (rates.contains(FALLBACK_SAMPLE_RATE)) return FALLBACK_SAMPLE_RATE
                        val maxRate = rates.filter { it > 0 }.maxOrNull()
                        if (maxRate != null) return maxRate
                    }
                }
            }

            for (device in inputDevices) {
                val rates = device.sampleRates
                if (rates.isNotEmpty()) {
                    if (rates.contains(DEFAULT_SAMPLE_RATE)) return DEFAULT_SAMPLE_RATE
                    if (rates.contains(FALLBACK_SAMPLE_RATE)) return FALLBACK_SAMPLE_RATE
                    val maxRate = rates.maxOrNull()
                    if (maxRate != null && maxRate > 0) return maxRate
                }
            }

            val inputPropRate = audioManager.getProperty("android.media.property.INPUT_SAMPLE_RATE")
                ?: audioManager.getProperty("PROPERTY_INPUT_SAMPLE_RATE")
            inputPropRate?.toIntOrNull()?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating input audio devices for sample rate", e)
        }
        return null
    }

    private fun getPrimaryInputDeviceName(): String {
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            for (device in devices) {
                if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                    device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                ) {
                    val name = device.productName?.toString()
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve input device name", e)
        }
        return "Default Device Mic"
    }

    /**
     * Initiates audio capture. Safe to call concurrently from any thread.
     * Implements explicit attempt ownership (B01) to ensure obsolete or canceled
     * attempts never hijack replacement sessions or publish false status updates.
     */
    @SuppressLint("MissingPermission")
    fun startCapture(): Boolean {
        return startCaptureInternal(authorizedRestartToken = null)
    }

    @SuppressLint("MissingPermission")
    internal fun startCaptureInternal(authorizedRestartToken: Long? = null): Boolean {
        var sessionToClean: CaptureSession? = null
        var threadToInterrupt: Thread? = null
        val attemptToken: Long
        val attemptConfig: CaptureAttemptConfig

        synchronized(lifecycleLock) {
            if (authorizedRestartToken != null) {
                // Validate restart token immediately under lifecycleLock (B04)
                if (activeRestartToken != authorizedRestartToken) {
                    Log.w(TAG, "Reconfiguration restart aborted; token $authorizedRestartToken superseded or canceled (active=$activeRestartToken)")
                    return false
                }
                activeRestartToken = 0L
            } else {
                // Public startCapture() supersedes any pending automatic restart
                activeRestartToken = 0L

                if (isRunning.get() && activeSession?.let { !it.isCleanedUp() } == true) {
                    Log.d(TAG, "Capture already in progress")
                    return true
                }
            }

            // Allocate a unique, monotonically increasing attempt token under lifecycleLock (B01)
            attemptToken = nextAttemptToken++
            activeAttemptToken = attemptToken

            // Capture an immutable configuration snapshot for this attempt (B01, B07)
            attemptConfig = CaptureAttemptConfig(
                token = attemptToken,
                windowSize = activeConfig.windowSize,
                hopSize = activeConfig.hopSize,
                preferredAudioSource = activeConfig.preferredAudioSource,
                preferredDeviceId = activeConfig.preferredDeviceId,
                targetSampleRate = activeConfig.targetSampleRate
            )

            // Clean up any stale or lingering session before starting anew
            sessionToClean = activeSession
            activeSession = null
            isRunning.set(false)
            threadToInterrupt = captureThread
            captureThread = null

            _uiState.value = _uiState.value.copy(
                status = CaptureStatus.INITIALIZING,
                errorMessage = null,
                routedDeviceId = null
            )
        }

        // Blocking hardware release executed strictly outside lifecycleLock (B02)
        sessionToClean?.cleanup()
        try {
            threadToInterrupt?.interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "Exception interrupting capture thread", e)
        }

        var newlyCreatedRecord: AudioRecordSource? = null
        var createdSession: CaptureSession? = null

        try {
            onPreHardwareInit?.invoke(attemptToken)

            val rateResolution = resolveSampleRateForConfig(attemptConfig)
            val requestedRate = rateResolution.resolvedRate

            // 1. Validate AudioRecord buffer size defensively against negative error codes
            var sampleRate = requestedRate
            var minBufferSize = minBufferSizeProvider(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            // If primary rate fails, test fallback sample rate
            if (minBufferSize <= 0 || minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                val altRate = if (sampleRate == DEFAULT_SAMPLE_RATE) FALLBACK_SAMPLE_RATE else DEFAULT_SAMPLE_RATE
                Log.w(TAG, "Invalid minBufferSize ($minBufferSize) for $sampleRate Hz. Attempting fallback: $altRate Hz")
                sampleRate = altRate
                minBufferSize = minBufferSizeProvider(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            }

            if (minBufferSize <= 0 || minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                val errorMsg = "Audio hardware rejected buffer parameters (AudioRecord.getMinBufferSize error: $minBufferSize)"
                Log.e(TAG, errorMsg)
                synchronized(lifecycleLock) {
                    if (activeAttemptToken == attemptToken && activeSession == null) {
                        activeAttemptToken = 0L
                        _uiState.value = _uiState.value.copy(
                            status = CaptureStatus.ERROR,
                            errorMessage = errorMsg,
                            routedDeviceId = null
                        )
                    } else {
                        Log.w(TAG, "Suppressed buffer-size error from obsolete attempt $attemptToken")
                    }
                }
                return false
            }

            // 2. Compute safe hardware buffer size (must be at least 2x our FFT window to prevent overrun)
            val windowSize = attemptConfig.windowSize
            val hopSize = attemptConfig.hopSize
            val windowBytes = windowSize * 2 // 16-bit PCM = 2 bytes per sample
            val hardwareBufferSize = max(minBufferSize, windowBytes * 2)

            // 3. Negotiate AudioSource (Try UNPROCESSED -> Fallback to MIC)
            val sourceNegotiation = createAudioRecordWithFallback(
                preferredSource = attemptConfig.preferredAudioSource,
                sampleRate = sampleRate,
                bufferSizeInBytes = hardwareBufferSize
            )

            if (sourceNegotiation == null) {
                val errorMsg = "Failed to initialize AudioRecord with UNPROCESSED or MIC source"
                Log.e(TAG, errorMsg)
                synchronized(lifecycleLock) {
                    if (activeAttemptToken == attemptToken && activeSession == null) {
                        activeAttemptToken = 0L
                        _uiState.value = _uiState.value.copy(
                            status = CaptureStatus.ERROR,
                            errorMessage = errorMsg,
                            routedDeviceId = null
                        )
                    } else {
                        Log.w(TAG, "Suppressed source-negotiation error from obsolete attempt $attemptToken")
                    }
                }
                return false
            }

            newlyCreatedRecord = sourceNegotiation.record
            val activeSourceName = sourceNegotiation.sourceName

            onPostHardwareInit?.invoke(attemptToken)

            // Look up target device from snapshot
            val targetDevice = attemptConfig.preferredDeviceId?.let { prefId ->
                try {
                    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == prefId }
                } catch (e: Exception) {
                    null
                }
            }

            val generation = sessionGeneration.incrementAndGet()
            val session = CaptureSession(
                attemptToken = attemptToken,
                generation = generation,
                record = newlyCreatedRecord,
                targetDevice = targetDevice,
                requestedRate = requestedRate,
                actualRate = sampleRate,
                windowSize = windowSize,
                hopSize = hopSize,
                selectedSource = attemptConfig.preferredAudioSource,
                actualSource = sourceNegotiation.actualAudioSource,
                effectsInfo = probeAcousticEffects()
            )
            // Transfer ownership to session
            createdSession = session
            newlyCreatedRecord = null

            if (targetDevice != null) {
                val setSuccess = session.record.setPreferredDevice(targetDevice)
                Log.i(TAG, "setPreferredDevice(${targetDevice.productName}, id=${targetDevice.id}) returned $setSuccess")
            }

            // Attach routing listener bound to this generation session
            val listener = AudioRecord.OnRoutingChangedListener { audRecord ->
                onPreRoutingUpdate?.invoke()
                synchronized(lifecycleLock) {
                    updateActualRoutedDeviceLocked(session, audRecord?.routedDevice ?: session.record.routedDevice, targetDevice)
                }
            }
            session.routingListener = listener

            session.record.addOnRoutingChangedListener(
                listener,
                Handler(Looper.getMainLooper())
            )

            val inputDeviceName = targetDevice?.productName?.toString() ?: getPrimaryInputDeviceName()

            var threadToStart: Thread? = null
            var sessionToCleanOnAbort: CaptureSession? = null

            synchronized(lifecycleLock) {
                // Strict attempt ownership check: verify attempt has not been canceled or superseded (B01)
                if (activeAttemptToken != attemptToken || activeSession != null) {
                    Log.w(TAG, "Capture initialization aborted; attempt $attemptToken is obsolete (activeAttemptToken=$activeAttemptToken, activeSession=$activeSession)")
                    sessionToCleanOnAbort = session
                } else {
                    val epoch = routeEpochCounter.incrementAndGet()
                    session.routeEpoch = epoch
                    activeSession = session
                    isRunning.set(true)

                    val authority = CaptureAuthoritySnapshot(
                        managerId = managerId,
                        captureGeneration = session.generation,
                        routeEpoch = epoch,
                        fingerprint = session.currentFingerprint,
                        isRunning = true,
                        latestPublishedSequence = 0L
                    )

                    _uiState.value = _uiState.value.copy(
                        status = CaptureStatus.RUNNING,
                        errorMessage = null,
                        routedDeviceId = null,
                        isRoutingMismatch = false,
                        routingMismatchMessage = null,
                        isBluetoothBandwidthLimited = false,
                        bluetoothWarningText = null,
                        captureAuthority = authority,
                        diagnostics = AudioCaptureDiagnostics(
                            activeAudioSource = activeSourceName,
                            requestedSampleRate = requestedRate,
                            actualSampleRate = sampleRate,
                            detectedInputRate = rateResolution.detectedInputRate,
                            detectedOutputRate = rateResolution.detectedOutputRate,
                            inputDeviceName = "Pending",
                            requestedDeviceName = targetDevice?.productName?.toString() ?: "Auto (System Default)",
                            actualRoutedDeviceName = "Pending",
                            actualRoutedDeviceType = "Pending",
                            windowSize = windowSize,
                            hopSize = hopSize,
                            bufferSizeInBytes = hardwareBufferSize,
                            minBufferSizeInBytes = minBufferSize
                        )
                    )

                    // 4. Launch dedicated real-time audio thread
                    val thread = Thread({
                        runAudioCaptureLoop(session)
                    }, "AudioCaptureThread-$generation")

                    captureThread = thread
                    threadToStart = thread
                }
            }

            // Perform potentially blocking cleanup outside lifecycleLock (B02)
            if (sessionToCleanOnAbort != null) {
                sessionToCleanOnAbort!!.cleanup()
                return false
            }

            threadToStart?.let { thread ->
                threadLauncher(thread)
            }
            return true
        } catch (t: Throwable) {
            // Clean up all resources created by this attempt (B05, B06)
            var cleanupFatal: Throwable? = null
            try {
                if (createdSession != null) {
                    createdSession.cleanup()
                } else {
                    val toRelease = newlyCreatedRecord
                    newlyCreatedRecord = null
                    toRelease?.release()
                }
            } catch (cleanEx: Exception) {
                Log.w(TAG, "Exception cleaning up resources after initialization failure in attempt $attemptToken", cleanEx)
            } catch (cleanFatal: Throwable) {
                cleanupFatal = cleanFatal
                Log.e(TAG, "Fatal error cleaning up resources after initialization failure in attempt $attemptToken", cleanFatal)
            }

            // Authoritative attempt retirement must run in a guaranteed exit path (B05)
            try {
                synchronized(lifecycleLock) {
                    if (activeAttemptToken == attemptToken) {
                        activeAttemptToken = 0L
                        if (activeSession === createdSession) {
                            activeSession = null
                            isRunning.set(false)
                        }
                        if (!isFatalError(t) && cleanupFatal == null) {
                            _uiState.value = _uiState.value.copy(
                                status = CaptureStatus.ERROR,
                                errorMessage = "Capture initialization failed: ${t.message ?: t.javaClass.simpleName}",
                                routedDeviceId = null
                            )
                        }
                    } else {
                        Log.w(TAG, "Suppressed initialization error from obsolete attempt $attemptToken: ${t.message}")
                    }
                }
            } finally {
                // If initialization failed with a fatal error, preserve that original error as primary (B05)
                if (isFatalError(t)) {
                    if (cleanupFatal != null && t !== cleanupFatal) {
                        try {
                            t.addSuppressed(cleanupFatal)
                        } catch (_: Throwable) { }
                    }
                    throw t
                }

                // If cleanup itself was the only fatal failure, propagate it after lifecycle retirement (B05)
                if (cleanupFatal != null) {
                    throw cleanupFatal
                }
            }

            Log.e(TAG, "Exception during capture initialization for attempt $attemptToken", t)
            return false
        }
    }

    private fun isCurrentSession(session: CaptureSession): Boolean {
        return activeSession === session && !session.isCleanedUp()
    }

    /**
     * Dedicated capture loop executed on high-priority audio thread.
     * Guaranteed to interact only with its owned [session] resources.
     */
    private fun runAudioCaptureLoop(session: CaptureSession) {
        var workerPrimary: Throwable? = null
        try {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set audio thread priority for session ${session.generation}", e)
            }

            onPreStartRecording?.invoke(session)

            val record = session.record
            val started = try {
                session.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting AudioRecord for session ${session.generation}", e)
                notifyCaptureError(session, "Failed to start audio recording: ${e.message}")
                return
            }

            if (!started) {
                // If session was already cleaned up (stopped before worker could run), exit cleanly without false error (B03)
                if (!session.isCleanedUp()) {
                    notifyCaptureError(
                        session,
                        "AudioRecord failed to enter RECORDING state (${record.recordingState})"
                    )
                }
                return
            }

            synchronized(lifecycleLock) {
                updateActualRoutedDeviceLocked(session, record.routedDevice, session.targetDevice)
            }

            val windowSize = session.windowSize
            val hopSize = session.hopSize
            val sampleRate = session.actualRate

            // Pre-allocated buffers: zero allocations inside the loop
            val hopBuffer = ShortArray(hopSize)
            var hopSamplesAccumulated = 0
            val slidingWindow = ShortArray(windowSize)
            val fftProcessor = FftProcessor(windowSize = windowSize, sampleRate = sampleRate)
            val biquadFilterBank = BiquadFilterBank(sampleRate = sampleRate)
            var consecutiveZeroReads = 0
            var totalFrames = 0L
            var lastFpsTimeNs = System.nanoTime()
            var framesInSecond = 0
            var currentFps = 0f

            Log.i(TAG, "Audio capture loop running for session ${session.generation}. Window: $windowSize, Hop: $hopSize, Rate: $sampleRate Hz")

            var hopRouteEpoch = 0L

            while (isRunning.get() && isCurrentSession(session)) {
                val epochBeforeRead = session.routeEpoch
                val generationBeforeRead = session.generation

                val toRead = hopSize - hopSamplesAccumulated
                val readResult = record.read(hopBuffer, hopSamplesAccumulated, toRead, AudioRecord.READ_BLOCKING)

                val epochAfterRead = session.routeEpoch
                val generationAfterRead = session.generation

                // Check ownership and route epoch stability across the blocking read
                val isBetweenHopRouteChange = totalFrames > 0 && hopSamplesAccumulated == 0
                val readValid = readResult > 0 &&
                    generationAfterRead == generationBeforeRead &&
                    epochBeforeRead > 0L &&
                    isRunning.get() &&
                    isCurrentSession(session) &&
                    (epochAfterRead == epochBeforeRead || isBetweenHopRouteChange)

                if (readResult > 0 && !readValid) {
                    // Route changed, session changed, or stopped during blocking read:
                    // Discard returned transfer, discard any accumulated partial hop, and reset DSP history
                    hopSamplesAccumulated = 0
                    hopRouteEpoch = 0L
                    slidingWindow.fill(0)
                    biquadFilterBank.reset()
                    if (!isRunning.get() || !isCurrentSession(session)) {
                        break
                    }
                    continue
                }

                if (readResult > 0) {
                    consecutiveZeroReads = 0
                    val effectiveReadEpoch = epochAfterRead
                    if (hopSamplesAccumulated == 0) {
                        // Starting a new hop
                        if (hopRouteEpoch != 0L && hopRouteEpoch != effectiveReadEpoch) {
                            // Route changed since last hop (e.g. accepted A then switched to B)
                            // Clear overlap and IIR state so old route history does not contaminate new route
                            slidingWindow.fill(0)
                            biquadFilterBank.reset()
                        }
                        hopRouteEpoch = effectiveReadEpoch
                    } else if (effectiveReadEpoch != hopRouteEpoch) {
                        // Route changed during partial-hop assembly: discard both contributions to preserve genuine hop provenance
                        hopSamplesAccumulated = 0
                        hopRouteEpoch = 0L
                        slidingWindow.fill(0)
                        biquadFilterBank.reset()
                        continue
                    }
                    hopSamplesAccumulated += readResult

                    // Only advance sliding window and process DSP when a complete contiguous hop has accumulated
                    if (hopSamplesAccumulated >= hopSize) {
                        if (session.routeEpoch != hopRouteEpoch || session.generation != generationBeforeRead || !isRunning.get() || !isCurrentSession(session)) {
                            hopSamplesAccumulated = 0
                            hopRouteEpoch = 0L
                            slidingWindow.fill(0)
                            biquadFilterBank.reset()
                            if (!isRunning.get() || !isCurrentSession(session)) {
                                break
                            }
                            continue
                        }
                        // 1. Process continuous incoming hop through 64-bit continuous IIR biquad filter bank
                        val octave31FilterDb = biquadFilterBank.processHop(hopBuffer, hopSize)

                        // 2. 50% overlap sliding window update
                        val shiftCount = windowSize - hopSize
                        if (shiftCount > 0) {
                            System.arraycopy(slidingWindow, hopSize, slidingWindow, 0, shiftCount)
                        }
                        System.arraycopy(hopBuffer, 0, slidingWindow, shiftCount, hopSize)

                        // Compute instantaneous acoustic metrics with zero object allocations
                        val (rmsDb, peakDb) = AudioMath.computeRmsAndPeakDb(slidingWindow, windowSize)
                        val isClipping = AudioMath.isClipping(hopBuffer, hopSize)

                        // Real-time FFT spectral analysis (Hann window, Radix-2 FFT)
                        val fftResult = fftProcessor.processFrame(slidingWindow, computeOctaveBands = false)

                        // Test hook to intercept execution right before publication
                        onPrePublishFrame?.invoke(session.generation)

                        val nextSequence = totalFrames + 1
                        val published = publishFrame(
                            session = session,
                            hopRouteEpoch = hopRouteEpoch,
                            rmsDb = rmsDb,
                            peakDb = peakDb,
                            isClipping = isClipping,
                            octave31Db = octave31FilterDb,
                            rawFftDb = fftResult.rawFftDb,
                            totalFrames = nextSequence,
                            fps = currentFps,
                            fftExecutionTimeUs = fftResult.executionTimeUs,
                            filterBankExecutionTimeUs = biquadFilterBank.lastExecutionTimeUs
                        )

                        if (published) {
                            totalFrames = nextSequence
                            framesInSecond++
                            val nowNs = System.nanoTime()
                            if (nowNs - lastFpsTimeNs >= 1_000_000_000L) {
                                currentFps = framesInSecond.toFloat()
                                framesInSecond = 0
                                lastFpsTimeNs = nowNs
                            }
                        } else {
                            // Publication was rejected (e.g. route changed right before publish)
                            // Reset DSP state so rejected hop data cannot contaminate subsequent frames
                            slidingWindow.fill(0)
                            biquadFilterBank.reset()
                            hopRouteEpoch = 0L
                        }

                        if (!published && (!isRunning.get() || !isCurrentSession(session))) {
                            break
                        }

                        hopSamplesAccumulated = 0
                    }
                } else if (readResult == 0) {
                    consecutiveZeroReads++
                    synchronized(lifecycleLock) {
                        if (activeSession === session && isRunning.get() && !session.isCleanedUp() && _uiState.value.status == CaptureStatus.RUNNING) {
                            val current = _uiState.value
                            _uiState.value = current.copy(
                                diagnostics = current.diagnostics.copy(
                                    consecutiveZeroReads = consecutiveZeroReads
                                )
                            )
                        }
                    }
                    if (consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                        val msg = "Capture stalled — Audio hardware returned 0 samples repeatedly ($consecutiveZeroReads reads)"
                        Log.e(TAG, msg)
                        notifyCaptureError(session, msg)
                        break
                    }
                } else {
                    // readResult < 0: explicit hardware or HAL failure code
                    val errorReason = AudioMath.mapAudioRecordError(readResult)
                    val msg = "Capture interrupted — $errorReason"
                    Log.e(TAG, msg)
                    notifyCaptureError(session, msg)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception in capture loop for session ${session.generation}", e)
            notifyCaptureError(session, "Capture loop error: ${e.message}")
        } catch (fatal: Throwable) {
            workerPrimary = fatal
        } finally {
            var cleanupFatal: Throwable? = null
            try {
                session.cleanup()
            } catch (cleanEx: Exception) {
                Log.w(TAG, "Exception cleaning up session ${session.generation} on worker exit", cleanEx)
            } catch (cleanFatal: Throwable) {
                cleanupFatal = cleanFatal
                Log.e(TAG, "Fatal error cleaning up session ${session.generation} on worker exit", cleanFatal)
            }

            try {
                synchronized(lifecycleLock) {
                    if (activeSession === session) {
                        activeSession = null
                        isRunning.set(false)
                        activeAttemptToken = 0L
                        if (_uiState.value.status == CaptureStatus.RUNNING) {
                            val newEpoch = routeEpochCounter.incrementAndGet()
                            _uiState.value = _uiState.value.copy(
                                status = CaptureStatus.STOPPED,
                                routedDeviceId = null,
                                isRoutingMismatch = false,
                                routingMismatchMessage = null,
                                isBluetoothBandwidthLimited = false,
                                bluetoothWarningText = null,
                                frameContext = null,
                                captureAuthority = CaptureAuthoritySnapshot(
                                    managerId = managerId,
                                    captureGeneration = 0L,
                                    routeEpoch = newEpoch,
                                    fingerprint = null,
                                    isRunning = false,
                                    latestPublishedSequence = 0L
                                )
                            )
                        }
                    }
                }
            } finally {
                if (workerPrimary != null) {
                    if (cleanupFatal != null && workerPrimary !== cleanupFatal) {
                        try {
                            workerPrimary.addSuppressed(cleanupFatal)
                        } catch (_: Throwable) { }
                    }
                    throw workerPrimary
                }
                cleanupFatal?.let { throw it }
            }
            Log.i(TAG, "Audio capture thread for session ${session.generation} exited cleanly")
        }
    }

    /**
     * Atomically publishes computed audio frame measurements to UI state.
     * Rejects updates from obsolete, interrupted, or stopped capture sessions.
     */
    private fun publishFrame(
        session: CaptureSession,
        hopRouteEpoch: Long,
        rmsDb: Float,
        peakDb: Float,
        isClipping: Boolean,
        octave31Db: FloatArray,
        rawFftDb: FloatArray,
        totalFrames: Long,
        fps: Float,
        fftExecutionTimeUs: Long,
        filterBankExecutionTimeUs: Long
    ): Boolean {
        synchronized(lifecycleLock) {
            if (activeSession !== session || !isRunning.get() || session.isCleanedUp() || _uiState.value.status != CaptureStatus.RUNNING) {
                return false
            }
            if (session.routeEpoch != hopRouteEpoch || session.routeEpoch <= 0L) {
                return false
            }
            val current = _uiState.value
            val frameContext = CapturedFrameContext(
                managerId = managerId,
                captureGeneration = session.generation,
                frameSequence = totalFrames,
                fingerprint = session.currentFingerprint,
                isRunning = true,
                routeEpoch = hopRouteEpoch
            )
            val updatedAuthority = current.captureAuthority.copy(
                latestPublishedSequence = totalFrames
            )
            _uiState.value = current.copy(
                rmsDb = rmsDb,
                peakDb = peakDb,
                isClipping = isClipping,
                octave31Db = octave31Db,
                rawFftDb = rawFftDb,
                diagnostics = current.diagnostics.copy(
                    totalFramesCaptured = totalFrames,
                    fps = fps,
                    consecutiveZeroReads = 0,
                    fftExecutionTimeUs = fftExecutionTimeUs,
                    filterBankExecutionTimeUs = filterBankExecutionTimeUs
                ),
                frameContext = frameContext,
                captureAuthority = updatedAuthority
            )
            return true
        }
    }

    private fun notifyCaptureError(session: CaptureSession, message: String) {
        var shouldClean = false
        synchronized(lifecycleLock) {
            if (activeSession === session) {
                isRunning.set(false)
                activeSession = null
                activeAttemptToken = 0L
                val newEpoch = routeEpochCounter.incrementAndGet()
                val errorAuthority = CaptureAuthoritySnapshot(
                    managerId = managerId,
                    captureGeneration = 0L,
                    routeEpoch = newEpoch,
                    fingerprint = null,
                    isRunning = false,
                    latestPublishedSequence = 0L
                )
                _uiState.value = _uiState.value.copy(
                    status = CaptureStatus.ERROR,
                    errorMessage = message,
                    routedDeviceId = null,
                    isRoutingMismatch = false,
                    routingMismatchMessage = null,
                    isBluetoothBandwidthLimited = false,
                    bluetoothWarningText = null,
                    frameContext = null,
                    captureAuthority = errorAuthority
                )
                shouldClean = true
            } else {
                Log.w(TAG, "Suppressed stale capture error from session ${session.generation}: $message")
            }
        }
        if (shouldClean) {
            session.cleanup()
        }
    }

    /**
     * Gracefully terminates audio capture and frees hardware resources.
     */
    fun stopCapture() {
        val sessionToClean: CaptureSession?
        val threadToInterrupt: Thread?

        synchronized(lifecycleLock) {
            // Explicit stop invalidates any pending restart intentions and active attempts (B01, B04)
            activeRestartToken = 0L
            activeAttemptToken = 0L

            sessionToClean = activeSession
            activeSession = null
            isRunning.set(false)
            threadToInterrupt = captureThread
            captureThread = null

            val newEpoch = routeEpochCounter.incrementAndGet()
            val stoppedAuthority = CaptureAuthoritySnapshot(
                managerId = managerId,
                captureGeneration = 0L,
                routeEpoch = newEpoch,
                fingerprint = null,
                isRunning = false,
                latestPublishedSequence = 0L
            )

            _uiState.value = _uiState.value.copy(
                status = CaptureStatus.STOPPED,
                errorMessage = null,
                isClipping = false,
                routedDeviceId = null,
                isRoutingMismatch = false,
                routingMismatchMessage = null,
                isBluetoothBandwidthLimited = false,
                bluetoothWarningText = null,
                frameContext = null,
                captureAuthority = stoppedAuthority
            )
        }

        sessionToClean?.cleanup()

        try {
            threadToInterrupt?.interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "Exception interrupting capture thread", e)
        }
    }

    fun setDisplayMode(mode: FftDisplayMode) {
        synchronized(lifecycleLock) {
            _uiState.value = _uiState.value.copy(displayMode = mode)
        }
    }

    private fun updateActualRoutedDeviceLocked(
        session: CaptureSession,
        routedDevice: AudioDeviceInfo?,
        targetDevice: AudioDeviceInfo?
    ) {
        if (activeSession !== session || !isRunning.get() || session.isCleanedUp() || _uiState.value.status != CaptureStatus.RUNNING) {
            return
        }

        val actualName = if (routedDevice != null) {
            routedDevice.productName?.toString()?.takeIf { it.isNotBlank() }
                ?: AudioInputDeviceInfo.classifyDevice(routedDevice.type).second
        } else {
            "Pending"
        }
        val actualType = routedDevice?.let { AudioInputDeviceInfo.classifyDevice(it.type).second } ?: "Pending"
        val isUsb = routedDevice?.let {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_LINE_DIGITAL
        } ?: false

        val isMismatch = if (targetDevice != null && routedDevice != null) {
            targetDevice.id != routedDevice.id
        } else {
            false
        }

        val mismatchMessage = if (isMismatch && targetDevice != null) {
            val targetName = targetDevice.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Device #${targetDevice.id}"
            "Requested '$targetName' but audio is routed to '$actualName'."
        } else null

        val isBtLimited = routedDevice?.let { AudioInputDeviceInfo.isBandwidthLimited(it) } ?: false
        val btWarning = if (isBtLimited) {
            "Bluetooth SCO input active: voice bandwidth is limited (~8-16kHz). High frequencies above ~4-8kHz will be truncated."
        } else null

        val routedId = routedDevice?.id
        val selectedId = _uiState.value.selectedDeviceId

        val actualMicId = if (routedDevice != null) {
            val name = routedDevice.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Device #${routedDevice.id}"
            "${routedDevice.id}:$name"
        } else {
            CaptureFingerprint.UNKNOWN_MICROPHONE_ID
        }
        val actualDeviceType = routedDevice?.let { AudioInputDeviceInfo.classifyDevice(it.type).second }
            ?: CaptureFingerprint.UNKNOWN_DEVICE_TYPE

        val newEpoch = routeEpochCounter.incrementAndGet()
        session.routeEpoch = newEpoch

        session.currentFingerprint = CaptureFingerprint(
            dspRevision = DspResponseRevision.CURRENT,
            actualSampleRate = session.actualRate,
            routedMicrophoneId = actualMicId,
            routedDeviceType = actualDeviceType,
            selectedAudioSource = session.selectedSource,
            actualAudioSource = session.actualSource,
            isSourceFallback = (session.selectedSource != session.actualSource),
            effects = session.effectsInfo
        )

        val current = _uiState.value
        val newAuthority = CaptureAuthoritySnapshot(
            managerId = managerId,
            captureGeneration = session.generation,
            routeEpoch = newEpoch,
            fingerprint = session.currentFingerprint,
            isRunning = true,
            latestPublishedSequence = current.captureAuthority.latestPublishedSequence
        )
        _uiState.value = current.copy(
            routedDeviceId = routedId,
            selectedDeviceId = selectedId,
            isRoutingMismatch = isMismatch,
            routingMismatchMessage = mismatchMessage,
            isBluetoothBandwidthLimited = isBtLimited,
            bluetoothWarningText = btWarning,
            captureAuthority = newAuthority,
            availableInputDevices = current.availableInputDevices.map { dev ->
                dev.copy(
                    isSelected = (selectedId != null && dev.id == selectedId) || (selectedId == null && dev.isBuiltIn),
                    isCurrentlyRouted = routedId != null && dev.id == routedId
                )
            },
            diagnostics = current.diagnostics.copy(
                inputDeviceName = actualName,
                requestedDeviceName = targetDevice?.productName?.toString() ?: "Auto (System Default)",
                actualRoutedDeviceName = actualName,
                actualRoutedDeviceType = actualType,
                isUsbDevice = isUsb,
                isRoutingMismatch = isMismatch,
                isBluetoothBandwidthLimited = isBtLimited,
                bluetoothWarningText = btWarning
            )
        )
        Log.i(TAG, "Active routed input device: $actualName (type: $actualType, id: $routedId), mismatch: $isMismatch, BT limited: $isBtLimited")
    }

    fun setPreferredInputDevice(deviceId: Int?) {
        var sessionToClean: CaptureSession? = null
        var threadToInterrupt: Thread? = null
        val restartToken: Long?

        synchronized(lifecycleLock) {
            // Mutate configuration strictly under lifecycleLock (B07)
            activeConfig = activeConfig.copy(preferredDeviceId = deviceId)
            val wasActive = isRunning.get() || activeAttemptToken != 0L || activeRestartToken != 0L

            if (wasActive) {
                // Reserve restart ownership under lifecycleLock (B04)
                val token = nextReconfigurationToken++
                activeRestartToken = token
                restartToken = token

                activeAttemptToken = 0L
                sessionToClean = activeSession
                activeSession = null
                isRunning.set(false)
                threadToInterrupt = captureThread
                captureThread = null

                val initEpoch = routeEpochCounter.incrementAndGet()
                val initAuthority = CaptureAuthoritySnapshot(
                    managerId = managerId,
                    captureGeneration = 0L,
                    routeEpoch = initEpoch,
                    fingerprint = null,
                    isRunning = false,
                    latestPublishedSequence = 0L
                )
                _uiState.value = _uiState.value.copy(
                    status = CaptureStatus.INITIALIZING,
                    errorMessage = null,
                    selectedDeviceId = deviceId,
                    routedDeviceId = null,
                    captureAuthority = initAuthority
                )
            } else {
                activeRestartToken = 0L
                restartToken = null
                _uiState.value = _uiState.value.copy(
                    selectedDeviceId = deviceId,
                    routedDeviceId = null
                )
            }
        }

        // Blocking hardware release executed strictly outside lifecycleLock (B02, B04)
        sessionToClean?.cleanup()
        try {
            threadToInterrupt?.interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "Exception interrupting capture thread during device switch", e)
        }

        refreshInputDevices()

        onPreReconfigurationRestart?.invoke(restartToken ?: 0L)

        // If restart was authorized, start capture only if restart token is still authoritative (B04)
        if (restartToken != null) {
            startCaptureInternal(authorizedRestartToken = restartToken)
        }
    }

    fun dismissDevicePrompt() {
        synchronized(lifecycleLock) {
            _uiState.value = _uiState.value.copy(deviceAttachedPrompt = null)
        }
    }

    fun release() {
        stopCapture()
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister AudioDeviceCallback", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecordWithFallback(
        preferredSource: Int,
        sampleRate: Int,
        bufferSizeInBytes: Int
    ): RecordNegotiationResult? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

        // 1. Try preferred source (UNPROCESSED)
        var primaryRecord: AudioRecordSource? = null
        var primaryFatal: Throwable? = null
        try {
            primaryRecord = audioRecordFactory.create(
                preferredSource,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufferSizeInBytes
            )
            if (primaryRecord != null) {
                val state = try {
                    primaryRecord.state
                } catch (e: Exception) {
                    Log.w(TAG, "Exception probing state on preferred source $preferredSource", e)
                    AudioRecord.STATE_UNINITIALIZED
                }
                if (state == AudioRecord.STATE_INITIALIZED) {
                    val name = if (preferredSource == MediaRecorder.AudioSource.UNPROCESSED) {
                        "UNPROCESSED (Raw Direct HAL)"
                    } else {
                        "AudioSource ($preferredSource)"
                    }
                    Log.i(TAG, "Successfully initialized AudioRecord with source: $name")
                    val result = RecordNegotiationResult(primaryRecord, name, preferredSource)
                    primaryRecord = null // ownership successfully transferred
                    return result
                } else {
                    Log.w(TAG, "AudioRecord state not initialized with preferred source $preferredSource")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating AudioRecord with source $preferredSource: ${e.message}")
        } catch (t: Throwable) {
            primaryFatal = t
            throw t
        } finally {
            val toRelease = primaryRecord
            primaryRecord = null
            try {
                toRelease?.release()
            } catch (cleanEx: Exception) {
                Log.w(TAG, "Exception releasing unowned primary record in finally", cleanEx)
            } catch (cleanFatal: Throwable) {
                if (primaryFatal != null && primaryFatal !== cleanFatal) {
                    try { primaryFatal?.addSuppressed(cleanFatal) } catch (_: Throwable) { }
                } else {
                    throw cleanFatal
                }
            }
        }

        // 2. Fallback to standard MIC
        if (preferredSource != MediaRecorder.AudioSource.MIC) {
            var fallbackRecord: AudioRecordSource? = null
            var fallbackFatal: Throwable? = null
            try {
                Log.i(TAG, "Attempting fallback to MediaRecorder.AudioSource.MIC")
                fallbackRecord = audioRecordFactory.create(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferSizeInBytes
                )
                if (fallbackRecord != null) {
                    val fallbackState = try {
                        fallbackRecord.state
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception probing state on fallback MIC source", e)
                        AudioRecord.STATE_UNINITIALIZED
                    }
                    if (fallbackState == AudioRecord.STATE_INITIALIZED) {
                        Log.i(TAG, "Successfully initialized fallback AudioRecord with source: MIC")
                        val result = RecordNegotiationResult(fallbackRecord, "MIC (Fallback Source)", MediaRecorder.AudioSource.MIC)
                        fallbackRecord = null // ownership successfully transferred
                        return result
                    } else {
                        Log.e(TAG, "Fallback AudioRecord with MIC failed to initialize")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception initializing fallback MIC AudioRecord", e)
            } catch (t: Throwable) {
                fallbackFatal = t
                throw t
            } finally {
                val toRelease = fallbackRecord
                fallbackRecord = null
                try {
                    toRelease?.release()
                } catch (cleanEx: Exception) {
                    Log.w(TAG, "Exception releasing unowned fallback record in finally", cleanEx)
                } catch (cleanFatal: Throwable) {
                    if (fallbackFatal != null && fallbackFatal !== cleanFatal) {
                        try { fallbackFatal?.addSuppressed(cleanFatal) } catch (_: Throwable) { }
                    } else {
                        throw cleanFatal
                    }
                }
            }
        }

        return null
    }

    private data class RecordNegotiationResult(
        val record: AudioRecordSource,
        val sourceName: String,
        val actualAudioSource: Int
    )

    data class SampleRateResolution(
        val resolvedRate: Int,
        val detectedInputRate: Int?,
        val detectedOutputRate: Int?
    )
}
