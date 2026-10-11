package com.rtatuner.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.core.entitlement.ProFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Real-time test signal generator streaming continuous audio through an AudioTrack.
 *
 * Implements:
 * - Built-in Pink Noise Generator: Paul Kellet's 3-pole IIR filter algorithm delivering
 *   a true -3 dB/octave (1/f) power density roll-off across the full 20 Hz - 20 kHz spectrum.
 * - Built-in Logarithmic Sine Sweep Generator: Exact continuous phase-integrated chirp
 *   spanning user-selectable durations (2.5s, 5.0s, 10.0s) and frequency ranges (Full, Bass, Treble).
 * - Click/Pop Prevention: 50 ms cosine window fade-in on playback start and fade-out on stop.
 * - Master output volume attenuation (-30 dBFS to 0 dBFS).
 * - Real-time state broadcasting with instantaneous sweep frequency and progress telemetry.
 */
class SignalGenerator(
    private val sampleRate: Int = 48000,
    private val featurePolicy: FeatureAccessPolicy = FeatureAccessPolicy(),
    scope: CoroutineScope? = null
) {
    companion object {
        private const val TAG = "SignalGenerator"
        private const val CHUNK_SIZE = 1024
        private const val FADE_DURATION_MS = 50L
        private const val TWO_PI = 2.0 * PI
    }

    private val _state = MutableStateFlow(SignalGeneratorState())
    val state: StateFlow<SignalGeneratorState> = _state.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private val playbackGeneration = AtomicLong(0)
    private val operationLock = Any()
    private val ownedScope = if (scope == null) CoroutineScope(SupervisorJob() + Dispatchers.Default) else null
    private var playbackThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private val trackLock = Any()

    // Fade state variables (protected by thread synchronization)
    @Volatile
    private var isFadingOut = false
    private var fadeSamplesRemaining = 0
    private var fadeTotalSamples = (sampleRate * (FADE_DURATION_MS / 1000.0)).toInt()

    // Random generator for white noise
    private val random = Random()

    private val entitlementJob = (scope ?: requireNotNull(ownedScope)).launch(start = CoroutineStart.UNDISPATCHED) {
        featurePolicy.entitlements.state.collect {
            synchronized(operationLock) {
                if (featurePolicy.check(ProFeature.SINE_SWEEP) is FeatureAccessResult.Denied &&
                    _state.value.mode == GeneratorMode.SINE_SWEEP) {
                    stopSync()
                }
            }
        }
    }

    fun close() {
        entitlementJob.cancel()
        ownedScope?.cancel()
        synchronized(operationLock) { stopSync() }
    }

    fun startPinkNoise(levelDb: Float = _state.value.outputLevelDb) = synchronized(operationLock) {
        stopSync()
        _state.update { it.copy(
            mode = GeneratorMode.PINK_NOISE,
            isPlaying = true,
            outputLevelDb = levelDb.coerceIn(-30.0f, 0.0f),
            errorMessage = null
        ) }
        startPlaybackThread()
    }

    fun startSineSweep(
        range: SweepRangePreset = _state.value.sweepRange,
        durationSec: Float = _state.value.sweepDurationSec,
        isLooping: Boolean = _state.value.isSweepLooping,
        levelDb: Float = _state.value.outputLevelDb
    ): FeatureAccessResult = synchronized(operationLock) {
        val access = featurePolicy.check(ProFeature.SINE_SWEEP)
        if (access is FeatureAccessResult.Denied) return@synchronized access
        stopSync()
        _state.update { it.copy(
            mode = GeneratorMode.SINE_SWEEP,
            sweepRange = range,
            sweepDurationSec = durationSec,
            isSweepLooping = isLooping,
            isPlaying = true,
            outputLevelDb = levelDb.coerceIn(-30.0f, 0.0f),
            currentSweepProgress = 0.0f,
            currentSweepFreqHz = range.startFreqHz,
            errorMessage = null
        ) }
        startPlaybackThread()
        FeatureAccessResult.Allowed
    }

    fun stop() {
        if (!isRunning.get()) return
        // Initiate graceful 50ms fade-out on the playback thread
        isFadingOut = true
    }

    private fun stopSync() {
        // A delayed AudioTrack initialization must not resurrect a revoked/stopped sweep.
        synchronized(trackLock) {
            playbackGeneration.incrementAndGet()
            isRunning.set(false)
        }
        isFadingOut = false
        playbackThread?.interrupt()
        try {
            playbackThread?.join(300)
        } catch (ignored: InterruptedException) {}
        playbackThread = null

        synchronized(trackLock) {
            audioTrack?.let { track ->
                try {
                    if (track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                    track.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Exception cleaning up AudioTrack", e)
                }
            }
            audioTrack = null
        }
        _state.update { it.copy(isPlaying = false, mode = GeneratorMode.OFF) }
    }

    fun setOutputLevel(levelDb: Float) {
        _state.update { it.copy(outputLevelDb = levelDb.coerceIn(-30.0f, 0.0f)) }
    }

    fun setSweepDuration(durationSec: Float): FeatureAccessResult = mutateSweepConfiguration {
        _state.update { it.copy(sweepDurationSec = durationSec.coerceIn(1.0f, 30.0f)) }
    }

    fun setSweepRange(range: SweepRangePreset): FeatureAccessResult = mutateSweepConfiguration {
        _state.update { it.copy(sweepRange = range) }
    }

    fun setSweepLooping(looping: Boolean): FeatureAccessResult = mutateSweepConfiguration {
        _state.update { it.copy(isSweepLooping = looping) }
    }

    private fun mutateSweepConfiguration(mutate: () -> Unit): FeatureAccessResult = synchronized(operationLock) {
        val access = featurePolicy.check(ProFeature.SINE_SWEEP)
        if (access is FeatureAccessResult.Denied) return@synchronized access
        mutate()
        FeatureAccessResult.Allowed
    }

    private fun startPlaybackThread() {
        val generation = synchronized(trackLock) {
            isRunning.set(true)
            playbackGeneration.incrementAndGet()
        }
        isFadingOut = false
        fadeSamplesRemaining = 0
        fadeTotalSamples = (sampleRate * (FADE_DURATION_MS / 1000.0)).toInt()

        val thread = Thread({
            runPlaybackLoop(generation)
        }, "RtaSignalGeneratorThread")
        playbackThread = thread
        thread.start()
    }

    private fun runPlaybackLoop(generation: Long) {
        if (!isCurrentPlayback(generation)) return
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSizeBytes = maxOf(minBufferSize, CHUNK_SIZE * 4)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
            finishPlayback(generation)
            updatePlaybackState(generation) { it.copy(
                isPlaying = false,
                mode = GeneratorMode.OFF,
                errorMessage = "Failed to create AudioTrack: ${e.localizedMessage ?: e.message}"
            ) }
            return
        }

        synchronized(trackLock) {
            if (!isCurrentPlayback(generation)) {
                track.release()
                return
            }
            audioTrack = track
        }

        try {
            track.play()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack.play() failed", e)
            synchronized(trackLock) {
                try {
                    track.release()
                } catch (ignored: Exception) {}
                if (audioTrack === track) {
                    audioTrack = null
                }
            }
            finishPlayback(generation)
            updatePlaybackState(generation) { it.copy(
                isPlaying = false,
                mode = GeneratorMode.OFF,
                errorMessage = "Failed to start AudioTrack: ${e.localizedMessage ?: e.message}"
            ) }
            return
        }

        val pcmBuffer = ShortArray(CHUNK_SIZE)

        // Pink noise filter state (Paul Kellet's algorithm)
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0

        // Sine sweep parameters
        var sweepSampleIndex = 0L

        // Fade-in state
        var fadeInSampleCount = 0

        while (isCurrentPlayback(generation)) {
            val currentState = _state.value
            val mode = currentState.mode
            val amplitude = 10.0.pow(currentState.outputLevelDb / 20.0)

            val totalSweepSamples = (currentState.sweepDurationSec * sampleRate).toLong().coerceAtLeast(1L)
            val f0 = currentState.sweepRange.startFreqHz.toDouble()
            val f1 = currentState.sweepRange.stopFreqHz.toDouble()
            val sweepDurationSec = currentState.sweepDurationSec.toDouble()
            val logRatio = ln(f1 / f0)

            for (i in 0 until CHUNK_SIZE) {
                var sample = 0.0

                when (mode) {
                    GeneratorMode.PINK_NOISE -> {
                        // Generate uniform white noise [-1.0, 1.0]
                        val white = random.nextDouble() * 2.0 - 1.0
                        b0 = 0.99886 * b0 + white * 0.0555179
                        b1 = 0.99332 * b1 + white * 0.0750759
                        b2 = 0.96900 * b2 + white * 0.1538520
                        b3 = 0.86650 * b3 + white * 0.3104856
                        b4 = 0.55000 * b4 + white * 0.5329522
                        b5 = -0.7616 * b5 - white * 0.0168980
                        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
                        b6 = white * 0.115926
                        // Scale pink noise to nominal RMS (~0.22) with 12 dB peak headroom
                        sample = pink * 0.12
                    }

                    GeneratorMode.SINE_SWEEP -> {
                        val t = sweepSampleIndex.toDouble() / sampleRate
                        val normProgress = (sweepSampleIndex.toDouble() / totalSweepSamples).coerceIn(0.0, 1.0)

                        // Continuous logarithmic phase integration
                        // phi(t) = 2*pi*f0 * (T / ln(f1/f0)) * ((f1/f0)^(t/T) - 1)
                        val phase = (TWO_PI * f0 * sweepDurationSec / logRatio) * ((f1 / f0).pow(t / sweepDurationSec) - 1.0)
                        sample = sin(phase)

                        // Boundary taper: 10ms smooth cosine fade at the very start and end of sweep
                        val taperSamples = (0.010 * sampleRate).toInt()
                        if (sweepSampleIndex < taperSamples) {
                            val taperNorm = sweepSampleIndex.toDouble() / taperSamples
                            sample *= 0.5 * (1.0 - cos(PI * taperNorm))
                        } else if (sweepSampleIndex > totalSweepSamples - taperSamples) {
                            val taperNorm = (totalSweepSamples - sweepSampleIndex).toDouble() / taperSamples
                            sample *= 0.5 * (1.0 - cos(PI * taperNorm))
                        }

                        sweepSampleIndex++
                        if (sweepSampleIndex >= totalSweepSamples) {
                            if (currentState.isSweepLooping) {
                                sweepSampleIndex = 0L
                            } else {
                                isFadingOut = true
                            }
                        }
                    }

                    GeneratorMode.OFF -> {
                        sample = 0.0
                    }
                }

                // Apply master amplitude
                sample *= amplitude

                // Handle 50ms startup fade-in
                if (fadeInSampleCount < fadeTotalSamples) {
                    val progress = fadeInSampleCount.toDouble() / fadeTotalSamples
                    val window = 0.5 * (1.0 - cos(PI * progress))
                    sample *= window
                    fadeInSampleCount++
                }

                // Handle 50ms graceful shutdown fade-out
                if (isFadingOut) {
                    val progress = fadeSamplesRemaining.toDouble() / fadeTotalSamples
                    val window = 0.5 * (1.0 + cos(PI * progress))
                    sample *= window
                    fadeSamplesRemaining++
                    if (fadeSamplesRemaining >= fadeTotalSamples) {
                        finishPlayback(generation)
                        break
                    }
                }

                // Convert double sample [-1.0, 1.0] to 16-bit PCM integer
                val clamped = sample.coerceIn(-1.0, 1.0)
                pcmBuffer[i] = (clamped * 32767.0).toInt().toShort()
            }

            // Write chunk to AudioTrack safely wrapped to handle post-release race or write failures
            if (generation == playbackGeneration.get() && (isRunning.get() || fadeSamplesRemaining > 0)) {
                val shouldContinue = try {
                    val currentTrack = synchronized(trackLock) {
                        if (generation == playbackGeneration.get()) audioTrack else null
                    }
                    if (currentTrack != null &&
                        currentTrack.state == AudioTrack.STATE_INITIALIZED &&
                        currentTrack.playState == AudioTrack.PLAYSTATE_PLAYING
                    ) {
                        val written = currentTrack.write(pcmBuffer, 0, CHUNK_SIZE)
                        if (written < 0) {
                            Log.w(TAG, "AudioTrack.write returned error code: $written")
                            false
                        } else {
                            true
                        }
                    } else {
                        false
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "AudioTrack.write called after release or invalid state", e)
                    false
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTrack.write failed with exception", e)
                    false
                }

                if (!shouldContinue) {
                    break
                }
            }

            // Update UI progress telemetry for sweep mode
            if (mode == GeneratorMode.SINE_SWEEP) {
                val progress = (sweepSampleIndex.toFloat() / totalSweepSamples).coerceIn(0.0f, 1.0f)
                val currentFreq = f0 * (f1 / f0).pow(progress.toDouble())
                updatePlaybackState(generation) { it.copy(
                    currentSweepProgress = progress,
                    currentSweepFreqHz = currentFreq.toFloat()
                ) }
            }
        }

        // Clean up AudioTrack safely
        synchronized(trackLock) {
            try {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                    track.release()
                }
            } catch (ignored: Exception) {}
            if (audioTrack === track) {
                audioTrack = null
            }
        }

        finishPlayback(generation)
        updatePlaybackState(generation) { it.copy(
            isPlaying = false,
            mode = GeneratorMode.OFF,
            currentSweepProgress = 0.0f
        ) }
    }

    private fun isCurrentPlayback(generation: Long): Boolean =
        isRunning.get() && generation == playbackGeneration.get()

    private fun finishPlayback(generation: Long) = synchronized(trackLock) {
        if (generation == playbackGeneration.get()) isRunning.set(false)
    }

    private fun updatePlaybackState(generation: Long, transform: (SignalGeneratorState) -> SignalGeneratorState) {
        _state.update { current -> if (generation == playbackGeneration.get()) transform(current) else current }
    }
}
