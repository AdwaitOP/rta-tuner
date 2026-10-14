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
import com.rtatuner.app.core.dsp.FftProcessor
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
    val hopSize: Int
) {
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
     */
    fun startRecording(): Boolean {
        synchronized(sessionLock) {
            if (isCleanedUp) {
                return false
            }
            record.startRecording()
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
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
     * Hardware stop and release operations execute outside any manager-wide lock (B02).
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
            shouldStop = isRecordingStarted || (record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        }

        if (listenerToRemove != null) {
            try {
                record.removeOnRoutingChangedListener(listenerToRemove)
            } catch (e: Exception) {
                Log.w("AudioCaptureManager", "Error removing routing listener in session $generation", e)
            }
        }

        if (shouldStop) {
            try {
                record.stop()
            } catch (e: Exception) {
                Log.w("AudioCaptureManager", "Exception stopping record in session $generation", e)
            }
        }

        try {
            record.release()
        } catch (e: Exception) {
            Log.w("AudioCaptureManager", "Exception releasing record in session $generation", e)
        }
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

    // State flow for UI and diagnostics
    private val _uiState = MutableStateFlow(AudioCaptureUiState())
    val uiState: StateFlow<AudioCaptureUiState> = _uiState.asStateFlow()
    @Deprecated("Legacy unbuffered flow; observe uiState directly instead", ReplaceWith("uiState"))
    val audioFrames: Flow<AudioFrame> = emptyFlow()

    private val isRunning = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0L)
    private var nextAttemptToken = 1L
    private var activeAttemptToken = 0L
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

    private var activeConfig: AudioCaptureConfig = config

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
            if (externalInput != null && externalInput.id != activeConfig.preferredDeviceId) {
                val inputInfo = AudioInputDeviceInfo.fromAudioDeviceInfo(externalInput)
                synchronized(lifecycleLock) {
                    _uiState.value = _uiState.value.copy(deviceAttachedPrompt = inputInfo)
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
            val selectedId = activeConfig.preferredDeviceId
            synchronized(lifecycleLock) {
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
        return resolveSampleRateForConfig(
            CaptureAttemptConfig(
                token = 0L,
                windowSize = activeConfig.windowSize,
                hopSize = activeConfig.hopSize,
                preferredAudioSource = activeConfig.preferredAudioSource,
                preferredDeviceId = activeConfig.preferredDeviceId,
                targetSampleRate = activeConfig.targetSampleRate
            )
        )
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
        var sessionToClean: CaptureSession? = null
        var threadToInterrupt: Thread? = null
        val attemptToken: Long
        val attemptConfig: CaptureAttemptConfig

        synchronized(lifecycleLock) {
            if (isRunning.get() && activeSession?.let { !it.isCleanedUp() } == true) {
                Log.d(TAG, "Capture already in progress")
                return true
            }

            // Allocate a unique, monotonically increasing attempt token under lifecycleLock (B01)
            attemptToken = nextAttemptToken++
            activeAttemptToken = attemptToken

            // Capture an immutable configuration snapshot for this attempt (B01)
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
                errorMessage = null
            )
        }

        // Blocking hardware release executed strictly outside lifecycleLock (B02)
        sessionToClean?.cleanup()
        try {
            threadToInterrupt?.interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "Exception interrupting capture thread", e)
        }

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
                // Scoped strictly to owning attempt (B01)
                if (activeAttemptToken == attemptToken && activeSession == null) {
                    activeAttemptToken = 0L
                    _uiState.value = _uiState.value.copy(
                        status = CaptureStatus.ERROR,
                        errorMessage = errorMsg
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
                // Scoped strictly to owning attempt (B01)
                if (activeAttemptToken == attemptToken && activeSession == null) {
                    activeAttemptToken = 0L
                    _uiState.value = _uiState.value.copy(
                        status = CaptureStatus.ERROR,
                        errorMessage = errorMsg
                    )
                } else {
                    Log.w(TAG, "Suppressed source-negotiation error from obsolete attempt $attemptToken")
                }
            }
            return false
        }

        val record = sourceNegotiation.record
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
            record = record,
            targetDevice = targetDevice,
            requestedRate = requestedRate,
            actualRate = sampleRate,
            windowSize = windowSize,
            hopSize = hopSize
        )

        try {
            if (targetDevice != null) {
                val setSuccess = record.setPreferredDevice(targetDevice)
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

            try {
                record.addOnRoutingChangedListener(
                    listener,
                    Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register OnRoutingChangedListener for session $generation", e)
            }

            val inputDeviceName = targetDevice?.productName?.toString() ?: getPrimaryInputDeviceName()

            var threadToStart: Thread? = null
            var sessionToCleanOnAbort: CaptureSession? = null

            synchronized(lifecycleLock) {
                // Strict attempt ownership check: verify attempt has not been canceled or superseded (B01)
                if (activeAttemptToken != attemptToken || activeSession != null) {
                    Log.w(TAG, "Capture initialization aborted; attempt $attemptToken is obsolete (activeAttemptToken=$activeAttemptToken, activeSession=$activeSession)")
                    sessionToCleanOnAbort = session
                } else {
                    activeSession = session
                    isRunning.set(true)

                    _uiState.value = _uiState.value.copy(
                        status = CaptureStatus.RUNNING,
                        errorMessage = null,
                        diagnostics = AudioCaptureDiagnostics(
                            activeAudioSource = activeSourceName,
                            requestedSampleRate = requestedRate,
                            actualSampleRate = sampleRate,
                            detectedInputRate = rateResolution.detectedInputRate,
                            detectedOutputRate = rateResolution.detectedOutputRate,
                            inputDeviceName = inputDeviceName,
                            requestedDeviceName = targetDevice?.productName?.toString() ?: "Auto (System Default)",
                            actualRoutedDeviceName = inputDeviceName,
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

            threadToStart?.start()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting capture session $generation", e)
            session.cleanup()
            synchronized(lifecycleLock) {
                if (activeAttemptToken == attemptToken) {
                    activeAttemptToken = 0L
                    if (activeSession === session) {
                        activeSession = null
                        isRunning.set(false)
                    }
                    _uiState.value = _uiState.value.copy(
                        status = CaptureStatus.ERROR,
                        errorMessage = "Failed to launch capture thread: ${e.message}"
                    )
                }
            }
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
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set audio thread priority for session ${session.generation}", e)
        }

        onPreStartRecording?.invoke(session)

        val record = session.record
        try {
            val started = session.startRecording()
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
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting AudioRecord for session ${session.generation}", e)
            notifyCaptureError(session, "Failed to start audio recording: ${e.message}")
            return
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

        try {
            while (isRunning.get() && isCurrentSession(session)) {
                val toRead = hopSize - hopSamplesAccumulated
                val readResult = record.read(hopBuffer, hopSamplesAccumulated, toRead, AudioRecord.READ_BLOCKING)

                if (readResult > 0) {
                    consecutiveZeroReads = 0
                    hopSamplesAccumulated += readResult

                    // Only advance sliding window and process DSP when a complete contiguous hop has accumulated
                    if (hopSamplesAccumulated >= hopSize) {
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

                        val nowNs = System.nanoTime()
                        totalFrames++
                        framesInSecond++

                        if (nowNs - lastFpsTimeNs >= 1_000_000_000L) {
                            currentFps = framesInSecond.toFloat()
                            framesInSecond = 0
                            lastFpsTimeNs = nowNs
                        }

                        // Test hook to intercept execution right before publication
                        onPrePublishFrame?.invoke(session.generation)

                        val published = publishFrame(
                            session = session,
                            rmsDb = rmsDb,
                            peakDb = peakDb,
                            isClipping = isClipping,
                            octave31Db = octave31FilterDb,
                            rawFftDb = fftResult.rawFftDb,
                            totalFrames = totalFrames,
                            fps = currentFps,
                            fftExecutionTimeUs = fftResult.executionTimeUs,
                            filterBankExecutionTimeUs = biquadFilterBank.lastExecutionTimeUs
                        )

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
        } finally {
            session.cleanup()
            synchronized(lifecycleLock) {
                if (activeSession === session) {
                    activeSession = null
                    isRunning.set(false)
                }
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
            val current = _uiState.value
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
                )
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
                _uiState.value = _uiState.value.copy(
                    status = CaptureStatus.ERROR,
                    errorMessage = message
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
            // Invalidate any active or in-flight initialization attempt (B01)
            activeAttemptToken = 0L

            sessionToClean = activeSession
            activeSession = null
            isRunning.set(false)
            threadToInterrupt = captureThread
            captureThread = null

            _uiState.value = _uiState.value.copy(
                status = CaptureStatus.STOPPED,
                errorMessage = null,
                isClipping = false
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

        val actualName = routedDevice?.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: if (routedDevice != null) {
                AudioInputDeviceInfo.classifyDevice(routedDevice.type).second
            } else {
                "Default Mic (Auto)"
            }
        val actualType = routedDevice?.let { AudioInputDeviceInfo.classifyDevice(it.type).second } ?: "Built-in"
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
        val selectedId = activeConfig.preferredDeviceId

        val current = _uiState.value
        _uiState.value = current.copy(
            routedDeviceId = routedId,
            selectedDeviceId = selectedId,
            isRoutingMismatch = isMismatch,
            routingMismatchMessage = mismatchMessage,
            isBluetoothBandwidthLimited = isBtLimited,
            bluetoothWarningText = btWarning,
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
        activeConfig = activeConfig.copy(preferredDeviceId = deviceId)
        val wasActive: Boolean
        synchronized(lifecycleLock) {
            wasActive = isRunning.get() || activeAttemptToken != 0L
        }
        if (wasActive) {
            stopCapture()
        }
        refreshInputDevices()
        if (wasActive) {
            startCapture()
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
        try {
            val record = audioRecordFactory.create(
                preferredSource,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufferSizeInBytes
            )
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                val name = if (preferredSource == MediaRecorder.AudioSource.UNPROCESSED) {
                    "UNPROCESSED (Raw Direct HAL)"
                } else {
                    "AudioSource ($preferredSource)"
                }
                Log.i(TAG, "Successfully initialized AudioRecord with source: $name")
                return RecordNegotiationResult(record, name)
            } else {
                Log.w(TAG, "AudioRecord state not initialized with preferred source $preferredSource")
                record?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating AudioRecord with source $preferredSource: ${e.message}")
        }

        // 2. Fallback to standard MIC
        if (preferredSource != MediaRecorder.AudioSource.MIC) {
            try {
                Log.i(TAG, "Attempting fallback to MediaRecorder.AudioSource.MIC")
                val fallbackRecord = audioRecordFactory.create(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferSizeInBytes
                )
                if (fallbackRecord != null && fallbackRecord.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "Successfully initialized fallback AudioRecord with source: MIC")
                    return RecordNegotiationResult(fallbackRecord, "MIC (Fallback Source)")
                } else {
                    Log.e(TAG, "Fallback AudioRecord with MIC failed to initialize")
                    fallbackRecord?.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception initializing fallback MIC AudioRecord", e)
            }
        }

        return null
    }

    private data class RecordNegotiationResult(
        val record: AudioRecordSource,
        val sourceName: String
    )

    data class SampleRateResolution(
        val resolvedRate: Int,
        val detectedInputRate: Int?,
        val detectedOutputRate: Int?
    )
}
