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
 * Represents a single lifecycle generation of audio capture.
 * Encapsulates the hardware record source, listeners, and resources.
 * Ensures that stale background threads cannot release or corrupt newer sessions.
 */
@Suppress("DEPRECATION")
internal class CaptureSession(
    val generation: Long,
    val record: AudioRecordSource,
    var routingListener: AudioRecord.OnRoutingChangedListener? = null,
    val targetDevice: AudioDeviceInfo?,
    val requestedRate: Int,
    val actualRate: Int,
    val windowSize: Int,
    val hopSize: Int
) {
    private val isCleanedUp = AtomicBoolean(false)

    fun isCleanedUp(): Boolean = isCleanedUp.get()

    fun cleanup() {
        if (!isCleanedUp.compareAndSet(false, true)) {
            return
        }
        val listener = routingListener
        routingListener = null
        if (listener != null) {
            try {
                record.removeOnRoutingChangedListener(listener)
            } catch (e: Exception) {
                Log.w("AudioCaptureManager", "Error removing routing listener in session $generation", e)
            }
        }

        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (e: Exception) {
            Log.w("AudioCaptureManager", "Exception stopping record in session $generation", e)
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
 * - Accumulation of partial AudioRecord reads into complete, contiguous processing hops
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
    @Volatile
    private var activeSession: CaptureSession? = null
    private var captureThread: Thread? = null

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
                _uiState.value = _uiState.value.copy(deviceAttachedPrompt = inputInfo)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.i(TAG, "Audio devices removed: ${removedDevices?.size ?: 0}")
            refreshInputDevices()
            if (isRunning.get()) {
                val currentRoutedDeviceId = _uiState.value.routedDeviceId
                val targetDeviceId = activeConfig.preferredDeviceId
                val removedActive = removedDevices?.firstOrNull { dev ->
                    dev.id == targetDeviceId || dev.id == currentRoutedDeviceId
                }
                if (removedActive != null) {
                    val name = removedActive.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Active microphone"
                    val msg = "$name was disconnected during active capture."
                    Log.w(TAG, msg)
                    activeSession?.let { notifyCaptureError(it, msg) }
                }
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
            val currentRoutedDeviceId = _uiState.value.routedDeviceId

            val deviceList = devices.map { device ->
                AudioInputDeviceInfo.fromAudioDeviceInfo(
                    device = device,
                    isSelected = (selectedId != null && device.id == selectedId) || (selectedId == null && device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC),
                    isCurrentlyRouted = currentRoutedDeviceId != null && device.id == currentRoutedDeviceId
                )
            }

            _uiState.value = _uiState.value.copy(
                availableInputDevices = deviceList,
                selectedDeviceId = selectedId
            )
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
        val detectedInputRate = detectInputDeviceSampleRate(activeConfig.preferredDeviceId)
        val detectedOutputRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()

        // Log discrepancy if input and output rates do not match
        if (detectedInputRate != null && detectedOutputRate != null && detectedInputRate != detectedOutputRate) {
            Log.w(
                TAG,
                "Sample rate discrepancy detected! Native Input Device Rate: $detectedInputRate Hz vs " +
                        "Output Rate: $detectedOutputRate Hz. Locking capture to input rate to avoid software resampling."
            )
        }

        val chosenRate = activeConfig.targetSampleRate
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
     * Initiates audio capture. Safe to call from any thread.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startCapture(): Boolean {
        if (isRunning.get() && activeSession?.let { !it.isCleanedUp() } == true) {
            Log.d(TAG, "Capture already in progress")
            return true
        }

        // Clean up any stale or lingering session before starting anew
        activeSession?.cleanup()
        activeSession = null
        isRunning.set(false)

        _uiState.value = _uiState.value.copy(
            status = CaptureStatus.INITIALIZING,
            errorMessage = null
        )

        val rateResolution = resolveSampleRate()
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
            _uiState.value = _uiState.value.copy(
                status = CaptureStatus.ERROR,
                errorMessage = errorMsg
            )
            return false
        }

        // 2. Compute safe hardware buffer size (must be at least 2x our FFT window to prevent overrun)
        val windowSize = activeConfig.windowSize
        val hopSize = activeConfig.hopSize
        val windowBytes = windowSize * 2 // 16-bit PCM = 2 bytes per sample
        val hardwareBufferSize = max(minBufferSize, windowBytes * 2)

        // 3. Negotiate AudioSource (Try UNPROCESSED -> Fallback to MIC)
        val sourceNegotiation = createAudioRecordWithFallback(
            preferredSource = activeConfig.preferredAudioSource,
            sampleRate = sampleRate,
            bufferSizeInBytes = hardwareBufferSize
        )

        if (sourceNegotiation == null) {
            val errorMsg = "Failed to initialize AudioRecord with UNPROCESSED or MIC source"
            Log.e(TAG, errorMsg)
            _uiState.value = _uiState.value.copy(
                status = CaptureStatus.ERROR,
                errorMessage = errorMsg
            )
            return false
        }

        val record = sourceNegotiation.record
        val activeSourceName = sourceNegotiation.sourceName

        // Look up target device
        val targetDevice = activeConfig.preferredDeviceId?.let { prefId ->
            try {
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == prefId }
            } catch (e: Exception) {
                null
            }
        }

        val generation = sessionGeneration.incrementAndGet()
        val session = CaptureSession(
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
                if (isCurrentSession(session)) {
                    updateActualRoutedDevice(audRecord.routedDevice, targetDevice)
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
            thread.start()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting capture session $generation", e)
            session.cleanup()
            if (activeSession === session) {
                activeSession = null
                isRunning.set(false)
            }
            _uiState.value = _uiState.value.copy(
                status = CaptureStatus.ERROR,
                errorMessage = "Failed to launch capture thread: ${e.message}"
            )
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

        val record = session.record
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                notifyCaptureError(
                    session,
                    "AudioRecord failed to enter RECORDING state (${record.recordingState})"
                )
                return
            }
            if (isCurrentSession(session)) {
                updateActualRoutedDevice(record.routedDevice, session.targetDevice)
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

                        // Update UI state diagnostics if this worker still owns the active session
                        if (isCurrentSession(session)) {
                            _uiState.value = _uiState.value.let { current ->
                                current.copy(
                                    rmsDb = rmsDb,
                                    peakDb = peakDb,
                                    isClipping = isClipping,
                                    octave31Db = octave31FilterDb,
                                    rawFftDb = fftResult.rawFftDb,
                                    diagnostics = current.diagnostics.copy(
                                        totalFramesCaptured = totalFrames,
                                        fps = currentFps,
                                        consecutiveZeroReads = 0,
                                        fftExecutionTimeUs = fftResult.executionTimeUs,
                                        filterBankExecutionTimeUs = biquadFilterBank.lastExecutionTimeUs
                                    )
                                )
                            }
                        }

                        hopSamplesAccumulated = 0
                    }
                } else if (readResult == 0) {
                    consecutiveZeroReads++
                    if (isCurrentSession(session)) {
                        _uiState.value = _uiState.value.let { current ->
                            current.copy(
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
            synchronized(this) {
                if (activeSession === session) {
                    activeSession = null
                    isRunning.set(false)
                }
            }
            Log.i(TAG, "Audio capture thread for session ${session.generation} exited cleanly")
        }
    }

    private fun notifyCaptureError(session: CaptureSession, message: String) {
        synchronized(this) {
            if (activeSession === session) {
                isRunning.set(false)
                activeSession = null
                _uiState.value = _uiState.value.copy(
                    status = CaptureStatus.ERROR,
                    errorMessage = message
                )
            } else {
                Log.w(TAG, "Suppressed stale capture error from session ${session.generation}: $message")
            }
        }
        session.cleanup()
    }

    /**
     * Gracefully terminates audio capture and frees hardware resources.
     */
    @Synchronized
    fun stopCapture() {
        val session = activeSession
        activeSession = null
        isRunning.set(false)

        session?.cleanup()

        try {
            captureThread?.interrupt()
            captureThread = null
        } catch (e: Exception) {
            Log.w(TAG, "Exception interrupting capture thread", e)
        }

        _uiState.value = _uiState.value.copy(
            status = CaptureStatus.STOPPED,
            errorMessage = null,
            isClipping = false
        )
    }

    fun setDisplayMode(mode: FftDisplayMode) {
        _uiState.value = _uiState.value.copy(displayMode = mode)
    }

    private fun updateActualRoutedDevice(routedDevice: AudioDeviceInfo?, targetDevice: AudioDeviceInfo?) {
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

        _uiState.value = _uiState.value.let { current ->
            current.copy(
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
        }
        Log.i(TAG, "Active routed input device: $actualName (type: $actualType, id: $routedId), mismatch: $isMismatch, BT limited: $isBtLimited")
    }

    fun setPreferredInputDevice(deviceId: Int?) {
        activeConfig = activeConfig.copy(preferredDeviceId = deviceId)
        val wasRunning = isRunning.get()
        if (wasRunning) {
            stopCapture()
        }
        refreshInputDevices()
        if (wasRunning) {
            startCapture()
        }
    }

    fun dismissDevicePrompt() {
        _uiState.value = _uiState.value.copy(deviceAttachedPrompt = null)
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
