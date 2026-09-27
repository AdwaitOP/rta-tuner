package com.rtatuner.app.core.dsp

import com.rtatuner.app.core.audio.AudioMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Continuous 4th-order IIR bandpass filter bank (two cascaded 2nd-order biquad sections per band)
 * for real-time fractional-octave spectrum analysis (IEC 61260 Class 0/1 compliance architecture).
 *
 * Each displayed band is filtered by two cascaded 2nd-order BPF sections (constant 0 dB peak gain each,
 * producing 0 dB overall peak gain at f0 and 24 dB/octave asymptotic skirts: 12 dB/octave per side).
 *
 * Key DSP characteristics:
 * - 64-bit Double state (Transposed Direct Form II) across both cascaded biquad stages to prevent
 *   numerical instability, limit cycles, and quantization noise at low frequencies (< 100 Hz).
 * - State persistence across hops: audio stream is processed continuously without per-frame reset.
 * - Computes true RMS power per band over each hop and converts to dBFS using AudioMath.linearToDbFs.
 * - Zero heap allocations in processHop().
 * - Coefficients recomputed only upon sample rate or band configuration changes (preset switches).
 */
class BiquadFilterBank(
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
    centerFrequencies: FloatArray = Iso31Bands.CENTER_FREQUENCIES,
    q: Double = DEFAULT_Q_ONE_THIRD_OCTAVE
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 48000

        /**
         * Standard quality factor for 1/3-octave filters (IEC 61260 / ISO 266):
         * Q = 1 / (2^(1/6) - 2^(-1/6)) ≈ 4.318464
         */
        val DEFAULT_Q_ONE_THIRD_OCTAVE: Double =
            1.0 / (2.0.pow(1.0 / 6.0) - 2.0.pow(-1.0 / 6.0))

        /**
         * Calculates Q from an arbitrary octave fraction (e.g. 1.0/3.0 for 1/3-octave, 1.0 for 1-octave).
         */
        fun qFromOctaveFraction(octaveFraction: Double): Double {
            val halfBandRatio = 2.0.pow(octaveFraction / 2.0)
            return 1.0 / (halfBandRatio - 1.0 / halfBandRatio)
        }

        private const val INV_SHORT_MAX = 1.0 / 32768.0
        private const val ANTI_DENORMAL_THRESHOLD = 1e-25
    }

    var sampleRate: Int = sampleRate
        private set
    var centerFrequencies: FloatArray = centerFrequencies.clone()
        private set
    var q: Double = q
        private set

    val numBands: Int
        get() = centerFrequencies.size

    // Normalized biquad filter coefficients:
    // H(z) = (b0 + b1*z^-1 + b2*z^-2) / (1 + a1*z^-1 + a2*z^-2)
    // For 0 dB peak gain BPF: b1 = 0, b2 = -b0
    // Identical for both cascaded stages (total gain at f0 = 1.0 * 1.0 = 1.0 = 0 dB)
    private var b0Coeffs = DoubleArray(centerFrequencies.size)
    private var a1Coeffs = DoubleArray(centerFrequencies.size)
    private var a2Coeffs = DoubleArray(centerFrequencies.size)

    // 64-bit filter state (Transposed Direct Form II)
    // Stage 1 state: s1_1, s1_2 per band
    private var state1_1 = DoubleArray(centerFrequencies.size)
    private var state1_2 = DoubleArray(centerFrequencies.size)

    // Stage 2 state: s2_1, s2_2 per band (cascaded 4th-order)
    private var state2_1 = DoubleArray(centerFrequencies.size)
    private var state2_2 = DoubleArray(centerFrequencies.size)

    // Reusable buffers (zero runtime heap allocation)
    private var normBuffer = DoubleArray(2048)
    private var outDbBuffer = FloatArray(centerFrequencies.size)

    /** Execution duration of the last processHop call in microseconds */
    var lastExecutionTimeUs: Long = 0L
        private set

    data class BandCoefficients(
        val bandIndex: Int,
        val f0: Float,
        val q: Double,
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    )

    fun getBandCoefficients(bandIndex: Int): BandCoefficients {
        require(bandIndex in 0 until numBands) { "Band index out of bounds: $bandIndex (total bands: $numBands)" }
        val b0 = b0Coeffs[bandIndex]
        return BandCoefficients(
            bandIndex = bandIndex,
            f0 = centerFrequencies[bandIndex],
            q = q,
            b0 = b0,
            b1 = 0.0,
            b2 = -b0,
            a1 = a1Coeffs[bandIndex],
            a2 = a2Coeffs[bandIndex]
        )
    }

    fun getAllBandCoefficients(): List<BandCoefficients> {
        return (0 until numBands).map { getBandCoefficients(it) }
    }

    init {
        recomputeCoefficients()
    }

    /**
     * Updates sample rate, center frequencies, or Q factor.
     * Recomputes filter coefficients and resets filter states.
     */
    @Synchronized
    fun updateConfiguration(
        newSampleRate: Int = this.sampleRate,
        newCenterFrequencies: FloatArray = this.centerFrequencies,
        newQ: Double = this.q
    ) {
        val sizeChanged = newCenterFrequencies.size != this.centerFrequencies.size
        this.sampleRate = newSampleRate
        this.centerFrequencies = newCenterFrequencies.clone()
        this.q = newQ

        if (sizeChanged) {
            b0Coeffs = DoubleArray(centerFrequencies.size)
            a1Coeffs = DoubleArray(centerFrequencies.size)
            a2Coeffs = DoubleArray(centerFrequencies.size)
            state1_1 = DoubleArray(centerFrequencies.size)
            state1_2 = DoubleArray(centerFrequencies.size)
            state2_1 = DoubleArray(centerFrequencies.size)
            state2_2 = DoubleArray(centerFrequencies.size)
            outDbBuffer = FloatArray(centerFrequencies.size)
        }

        recomputeCoefficients()
        reset()
    }

    /**
     * Clears all filter states to zero (silence initial condition).
     */
    @Synchronized
    fun reset() {
        state1_1.fill(0.0)
        state1_2.fill(0.0)
        state2_1.fill(0.0)
        state2_2.fill(0.0)
    }

    /**
     * Recomputes 2nd-order BPF coefficients for each center frequency.
     * Standard RBJ Audio EQ Cookbook bandpass filter with constant 0 dB peak gain:
     *   w0 = 2 * PI * f0 / Fs
     *   alpha = sin(w0) / (2 * Q)
     *   b0 = alpha / (1 + alpha)
     *   b1 = 0
     *   b2 = -b0
     *   a1 = -2 * cos(w0) / (1 + alpha)
     *   a2 = (1 - alpha) / (1 + alpha)
     */
    private fun recomputeCoefficients() {
        val nyquist = sampleRate * 0.5
        for (b in centerFrequencies.indices) {
            val f0 = centerFrequencies[b]
            if (f0 <= 0.0f || f0 >= nyquist) {
                // Out of valid band range: disable filter
                b0Coeffs[b] = 0.0
                a1Coeffs[b] = 0.0
                a2Coeffs[b] = 0.0
            } else {
                val omega0 = 2.0 * Math.PI * f0 / sampleRate
                val sinW0 = sin(omega0)
                val cosW0 = cos(omega0)
                val alpha = sinW0 / (2.0 * q)
                val a0 = 1.0 + alpha

                b0Coeffs[b] = alpha / a0
                a1Coeffs[b] = (-2.0 * cosW0) / a0
                a2Coeffs[b] = (1.0 - alpha) / a0
            }
        }
    }

    /**
     * Processes a continuous hop of 16-bit PCM samples through all 4th-order bandpass filters.
     * Filter state persists across successive calls to maintain phase and continuous energy integration.
     *
     * @param pcmSamples 16-bit PCM samples
     * @param length Number of valid samples in the hop
     * @return FloatArray of size [numBands] containing RMS magnitudes in dBFS
     */
    fun processHop(pcmSamples: ShortArray, length: Int = pcmSamples.size): FloatArray {
        val startTimeNs = System.nanoTime()

        if (length <= 0) {
            outDbBuffer.fill(AudioMath.MIN_DB_FS)
            lastExecutionTimeUs = (System.nanoTime() - startTimeNs) / 1000L
            return outDbBuffer.copyOf()
        }

        // Ensure normBuffer is large enough
        if (normBuffer.size < length) {
            normBuffer = DoubleArray(length)
        }

        // Normalize PCM to [-1.0, 1.0] once for all bands
        for (i in 0 until length) {
            normBuffer[i] = pcmSamples[i] * INV_SHORT_MAX
        }

        filterNormalizedHop(length)

        lastExecutionTimeUs = (System.nanoTime() - startTimeNs) / 1000L
        return outDbBuffer.copyOf()
    }

    /**
     * Overload for normalized Float samples in [-1.0, 1.0].
     */
    fun processHop(samples: FloatArray, length: Int = samples.size): FloatArray {
        val startTimeNs = System.nanoTime()

        if (length <= 0) {
            outDbBuffer.fill(AudioMath.MIN_DB_FS)
            lastExecutionTimeUs = (System.nanoTime() - startTimeNs) / 1000L
            return outDbBuffer.copyOf()
        }

        if (normBuffer.size < length) {
            normBuffer = DoubleArray(length)
        }

        for (i in 0 until length) {
            normBuffer[i] = samples[i].toDouble()
        }

        filterNormalizedHop(length)

        lastExecutionTimeUs = (System.nanoTime() - startTimeNs) / 1000L
        return outDbBuffer.copyOf()
    }

    /**
     * Core DSP loop: executes two cascaded Transposed Direct Form II biquad stages across all bands
     * and accumulates true RMS energy in-place.
     */
    private fun filterNormalizedHop(length: Int) {
        val invLength = 1.0 / length

        for (b in 0 until numBands) {
            val b0 = b0Coeffs[b]
            val a1 = a1Coeffs[b]
            val a2 = a2Coeffs[b]
            var s1_1 = state1_1[b]
            var s1_2 = state1_2[b]
            var s2_1 = state2_1[b]
            var s2_2 = state2_2[b]
            var sumSquares = 0.0

            for (i in 0 until length) {
                val x = normBuffer[i]

                // Stage 1 biquad (Transposed Direct Form II)
                val y1 = b0 * x + s1_1
                s1_1 = s1_2 - a1 * y1
                s1_2 = -b0 * x - a2 * y1

                // Stage 2 biquad (cascaded in series with Stage 1)
                val y2 = b0 * y1 + s2_1
                s2_1 = s2_2 - a1 * y2
                s2_2 = -b0 * y1 - a2 * y2

                sumSquares += y2 * y2
            }

            // Flush subnormal floating-point numbers to prevent performance degradation
            if (abs(s1_1) < ANTI_DENORMAL_THRESHOLD) s1_1 = 0.0
            if (abs(s1_2) < ANTI_DENORMAL_THRESHOLD) s1_2 = 0.0
            if (abs(s2_1) < ANTI_DENORMAL_THRESHOLD) s2_1 = 0.0
            if (abs(s2_2) < ANTI_DENORMAL_THRESHOLD) s2_2 = 0.0

            state1_1[b] = s1_1
            state1_2[b] = s1_2
            state2_1[b] = s2_1
            state2_2[b] = s2_2

            val meanSquare = sumSquares * invLength
            val rms = sqrt(meanSquare).toFloat()
            outDbBuffer[b] = AudioMath.linearToDbFs(rms)
        }
    }
}
