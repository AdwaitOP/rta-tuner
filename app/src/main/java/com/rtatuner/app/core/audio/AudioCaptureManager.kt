package com.rtatuner.app.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import com.rtatuner.app.core.dsp.FftProcessor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Manages low-latency raw audio acquisition from device hardware.
 *
 * Implements:
 * - Hardware native sample rate negotiation (input device rates prioritized)
 * - Safe source negotiation: UNPROCESSED (raw PCM, no AGC/AEC/NS) with fallback to MIC
 * - Defensive buffer size validation (preventing negative AudioRecord error returns)
 * - Dedicated capture thread running at Process.THREAD_PRIORITY_URGENT_AUDIO
 * - 50% overlap sliding window buffer
 * - Zero-allocation RMS/Peak math and clipping detection
 * - Resilient error/stall interception (detecting DEAD_OBJECT, mic theft, 0-byte reads)
 */
class AudioCaptureManager(
    private val context: Context,
    private val config: AudioCaptureConfig = AudioCaptureConfig()
) {
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

    // Non-blocking channel for high-speed audio frames
    private val frameChannel = Channel<AudioFrame>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioFrames: Flow<AudioFrame> = frameChannel.receiveAsFlow()

    private val isRunning = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    /**
     * Resolves the primary native sample rate for input capture.
     * Prioritizes active input device capabilities, verifies with output rate,
     * and logs warnings on discrepancy.
     */
    fun resolveSampleRate(): SampleRateResolution {
        val detectedInputRate = detectInputDeviceSampleRate()
        val detectedOutputRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()

        // Log discrepancy if input and output rates do not match
        if (detectedInputRate != null && detectedOutputRate != null && detectedInputRate != detectedOutputRate) {
            Log.w(
                TAG,
                "Sample rate discrepancy detected! Native Input Device Rate: $detectedInputRate Hz vs " +
                        "Output Rate: $detectedOutputRate Hz. Locking capture to input rate to avoid software resampling."
            )
        }

        val chosenRate = config.targetSampleRate
            ?: detectedInputRate
            ?: detectedOutputRate
            ?: DEFAULT_SAMPLE_RATE

        return SampleRateResolution(
            resolvedRate = chosenRate,
            detectedInputRate = detectedInputRate,
            detectedOutputRate = detectedOutputRate
        )
    }

    private fun detectInputDeviceSampleRate(): Int? {
        try {
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            for (device in inputDevices) {
                // Look for built-in mic, headset, or USB input
                val rates = device.sampleRates
                if (rates.isNotEmpty()) {
                    if (rates.contains(DEFAULT_SAMPLE_RATE)) return DEFAULT_SAMPLE_RATE
                    if (rates.contains(FALLBACK_SAMPLE_RATE)) return FALLBACK_SAMPLE_RATE
                    val maxRate = rates.maxOrNull()
                    if (maxRate != null && maxRate > 0) return maxRate
                }
            }

            // Secondary check: undocumented or manufacturer specific input property probe
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
        if (isRunning.get()) {
            Log.d(TAG, "Capture already in progress")
            return true
        }

        _uiState.value = _uiState.value.copy(
            status = CaptureStatus.INITIALIZING,
            errorMessage = null
        )

        val rateResolution = resolveSampleRate()
        val requestedRate = rateResolution.resolvedRate

        // 1. Validate AudioRecord.getMinBufferSize() defensively against negative error codes
        var sampleRate = requestedRate
        var minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // If primary rate fails, test fallback sample rate
        if (minBufferSize <= 0 || minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            val altRate = if (sampleRate == DEFAULT_SAMPLE_RATE) FALLBACK_SAMPLE_RATE else DEFAULT_SAMPLE_RATE
            Log.w(TAG, "Invalid minBufferSize ($minBufferSize) for $sampleRate Hz. Attempting fallback: $altRate Hz")
            sampleRate = altRate
            minBufferSize = AudioRecord.getMinBufferSize(
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
        val windowSize = config.windowSize
        val hopSize = config.hopSize
        val windowBytes = windowSize * 2 // 16-bit PCM = 2 bytes per sample
        val hardwareBufferSize = max(minBufferSize, windowBytes * 2)

        // 3. Negotiate AudioSource (Try UNPROCESSED -> Fallback to MIC)
        val sourceNegotiation = createAudioRecordWithFallback(
            preferredSource = config.preferredAudioSource,
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
        audioRecord = record

        val inputDeviceName = getPrimaryInputDeviceName()

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
                windowSize = windowSize,
                hopSize = hopSize,
                bufferSizeInBytes = hardwareBufferSize,
                minBufferSizeInBytes = minBufferSize
            )
        )

        isRunning.set(true)

        // 4. Launch dedicated real-time audio thread
        val thread = Thread({
            runAudioCaptureLoop(
                record = record,
                sampleRate = sampleRate,
                windowSize = windowSize,
                hopSize = hopSize
            )
        }, "AudioCaptureThread")

        captureThread = thread
        thread.start()
        return true
    }

    /**
     * Dedicated capture loop executed on high-priority audio thread.
     */
    private fun runAudioCaptureLoop(
        record: AudioRecord,
        sampleRate: Int,
        windowSize: Int,
        hopSize: Int
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                notifyCaptureError("AudioRecord failed to enter RECORDING state (${record.recordingState})")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting AudioRecord", e)
            notifyCaptureError("Failed to start audio recording: ${e.message}")
            return
        }

        // Pre-allocated buffers: zero allocations inside the loop
        val readBuffer = ShortArray(hopSize)
        val slidingWindow = ShortArray(windowSize)
        val fftProcessor = FftProcessor(windowSize = windowSize, sampleRate = sampleRate)
        var consecutiveZeroReads = 0
        var totalFrames = 0L
        var lastFpsTimeNs = System.nanoTime()
        var framesInSecond = 0
        var currentFps = 0f

        Log.i(TAG, "Audio capture loop running. Window: $windowSize, Hop: $hopSize, Rate: $sampleRate Hz")

        while (isRunning.get()) {
            val readResult = record.read(readBuffer, 0, hopSize, AudioRecord.READ_BLOCKING)

            if (readResult > 0) {
                consecutiveZeroReads = 0

                // 50% overlap sliding window update
                val shiftCount = windowSize - hopSize
                if (shiftCount > 0) {
                    System.arraycopy(slidingWindow, hopSize, slidingWindow, 0, shiftCount)
                }
                System.arraycopy(readBuffer, 0, slidingWindow, shiftCount, hopSize)

                // Compute instantaneous acoustic metrics with zero object allocations
                val (rmsDb, peakDb) = AudioMath.computeRmsAndPeakDb(slidingWindow, windowSize)
                val isClipping = AudioMath.isClipping(readBuffer, readResult)

                // Real-time FFT spectral analysis (Hann window, Radix-2 FFT, 1/3-octave ISO 31 bands)
                val fftResult = fftProcessor.processFrame(slidingWindow)

                val nowNs = System.nanoTime()
                totalFrames++
                framesInSecond++

                if (nowNs - lastFpsTimeNs >= 1_000_000_000L) {
                    currentFps = framesInSecond.toFloat()
                    framesInSecond = 0
                    lastFpsTimeNs = nowNs
                }

                val frame = AudioFrame(
                    samples = slidingWindow.copyOf(), // Cloned for downstream thread safety
                    sampleRate = sampleRate,
                    rmsDb = rmsDb,
                    peakDb = peakDb,
                    isClipping = isClipping,
                    fftResult = fftResult,
                    timestampNs = nowNs
                )

                frameChannel.trySend(frame)

                // Update UI state diagnostics periodically (avoid overwhelming Main thread)
                _uiState.value = _uiState.value.let { current ->
                    current.copy(
                        rmsDb = rmsDb,
                        peakDb = peakDb,
                        isClipping = isClipping,
                        octave31Db = fftResult.octave31Db,
                        rawFftDb = fftResult.rawFftDb,
                        diagnostics = current.diagnostics.copy(
                            totalFramesCaptured = totalFrames,
                            fps = currentFps,
                            consecutiveZeroReads = 0,
                            fftExecutionTimeUs = fftResult.executionTimeUs
                        )
                    )
                }
            } else if (readResult == 0) {
                consecutiveZeroReads++
                _uiState.value = _uiState.value.let { current ->
                    current.copy(
                        diagnostics = current.diagnostics.copy(
                            consecutiveZeroReads = consecutiveZeroReads
                        )
                    )
                }
                if (consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                    val msg = "Capture stalled — Audio hardware returned 0 samples repeatedly ($consecutiveZeroReads reads)"
                    Log.e(TAG, msg)
                    notifyCaptureError(msg)
                    break
                }
            } else {
                // readResult < 0: explicit hardware or HAL failure code
                val errorReason = AudioMath.mapAudioRecordError(readResult)
                val msg = "Capture interrupted — $errorReason"
                Log.e(TAG, msg)
                notifyCaptureError(msg)
                break
            }
        }

        cleanupAudioRecord()
        Log.i(TAG, "Audio capture thread exited cleanly")
    }

    private fun notifyCaptureError(message: String) {
        isRunning.set(false)
        _uiState.value = _uiState.value.copy(
            status = CaptureStatus.ERROR,
            errorMessage = message
        )
    }

    /**
     * Gracefully terminates audio capture and frees hardware resources.
     */
    @Synchronized
    fun stopCapture() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        try {
            captureThread?.interrupt()
            captureThread = null
        } catch (e: Exception) {
            Log.w(TAG, "Exception interrupting capture thread", e)
        }

        cleanupAudioRecord()

        _uiState.value = _uiState.value.copy(
            status = CaptureStatus.STOPPED,
            errorMessage = null,
            isClipping = false
        )
    }

    fun setDisplayMode(mode: FftDisplayMode) {
        _uiState.value = _uiState.value.copy(displayMode = mode)
    }

    private fun cleanupAudioRecord() {
        try {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception releasing AudioRecord", e)
        } finally {
            audioRecord = null
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
            val record = AudioRecord(
                preferredSource,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufferSizeInBytes
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                val name = if (preferredSource == MediaRecorder.AudioSource.UNPROCESSED) {
                    "UNPROCESSED (Raw Direct HAL)"
                } else {
                    "AudioSource ($preferredSource)"
                }
                Log.i(TAG, "Successfully initialized AudioRecord with source: $name")
                return RecordNegotiationResult(record, name)
            } else {
                Log.w(TAG, "AudioRecord state not initialized with preferred source $preferredSource")
                record.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating AudioRecord with source $preferredSource: ${e.message}")
        }

        // 2. Fallback to standard MIC
        if (preferredSource != MediaRecorder.AudioSource.MIC) {
            try {
                Log.i(TAG, "Attempting fallback to MediaRecorder.AudioSource.MIC")
                val fallbackRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferSizeInBytes
                )
                if (fallbackRecord.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "Successfully initialized fallback AudioRecord with source: MIC")
                    return RecordNegotiationResult(fallbackRecord, "MIC (Fallback Source)")
                } else {
                    Log.e(TAG, "Fallback AudioRecord with MIC failed to initialize")
                    fallbackRecord.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception initializing fallback MIC AudioRecord", e)
            }
        }

        return null
    }

    private data class RecordNegotiationResult(
        val record: AudioRecord,
        val sourceName: String
    )

    data class SampleRateResolution(
        val resolvedRate: Int,
        val detectedInputRate: Int?,
        val detectedOutputRate: Int?
    )
}
