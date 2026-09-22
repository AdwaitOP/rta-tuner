package com.rtatuner.app.core.dsp

import com.rtatuner.app.core.audio.AudioMath
import edu.emory.mathcs.jtransforms.fft.FloatFFT_1D
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Result of real-time spectral analysis for a single audio frame.
 */
data class FftResult(
    /** Raw linear FFT magnitude in dBFS for bins 0 .. N/2 (size N/2 + 1) */
    val rawFftDb: FloatArray,
    /** Standard 31-band 1/3-octave smoothed magnitudes in dBFS */
    val octave31Db: FloatArray,
    /** Bin spacing in Hz (sampleRate / windowSize) */
    val binResolutionHz: Float,
    /** Execution duration in microseconds */
    val executionTimeUs: Long
)

/**
 * High-performance DSP processor executing:
 * - Precomputed Hann windowing
 * - Radix-2 Real FFT via JTransforms
 * - Amplitude-calibrated dBFS magnitude conversion (0 dBFS for 1.0 pk sine)
 * - ISO 31-band 1/3-octave energy aggregation
 * - Zero allocations in processFrame()
 */
class FftProcessor(
    val windowSize: Int = 2048,
    val sampleRate: Int = 48000
) {
    init {
        require(windowSize > 0 && (windowSize and (windowSize - 1)) == 0) {
            "windowSize must be a positive power of 2 (e.g. 1024, 2048, 4096), was: $windowSize"
        }
    }

    private val fft = FloatFFT_1D(windowSize)
    val numBins = windowSize / 2 + 1
    val binResolutionHz = sampleRate.toFloat() / windowSize

    // Precomputed Hann window coefficients
    private val hannWindow = FloatArray(windowSize)
    private val sumHannWeights: Float
    /** Equivalent Noise Bandwidth of the Hann window (1.5 bins) for power aggregation */
    private val enbwFactor: Float

    // Precomputed reusable buffers (zero runtime allocation)
    private val fftBuffer = FloatArray(windowSize)
    private val linearMagnitudes = FloatArray(numBins)
    private val rawDbBuffer = FloatArray(numBins)
    private val octaveDbBuffer = FloatArray(Iso31Bands.NUM_BANDS)

    // Precomputed bin mappings for each of the 31 ISO bands
    private val bandMappings: Array<BandMapping>

    init {
        // Compute Hann window: w[n] = 0.5 * (1 - cos(2*pi*n / N))
        var sumW = 0.0
        var sumW2 = 0.0
        val factor = (2.0 * Math.PI) / windowSize
        for (n in 0 until windowSize) {
            val w = 0.5f * (1.0f - cos(factor * n).toFloat())
            hannWindow[n] = w
            sumW += w
            sumW2 += (w * w)
        }
        sumHannWeights = sumW.toFloat()
        enbwFactor = (windowSize * sumW2 / (sumW * sumW)).toFloat()

        // Precompute 1/3-octave band bin ranges
        bandMappings = Array(Iso31Bands.NUM_BANDS) { bandIdx ->
            val lowerHz = Iso31Bands.LOWER_EDGES[bandIdx]
            val upperHz = Iso31Bands.UPPER_EDGES[bandIdx]
            val centerHz = Iso31Bands.CENTER_FREQUENCIES[bandIdx]

            val startBin = max(1, (lowerHz / binResolutionHz).roundToInt())
            val endBin = min(numBins - 1, (upperHz / binResolutionHz).roundToInt())

            val matchingBins = mutableListOf<Int>()
            for (k in 1 until numBins) {
                val binFreq = k * binResolutionHz
                if (binFreq >= lowerHz && binFreq <= upperHz) {
                    matchingBins.add(k)
                }
            }

            val nearestBin = (centerHz / binResolutionHz).roundToInt().coerceIn(1, numBins - 1)
            BandMapping(
                bins = matchingBins.toIntArray(),
                nearestBin = nearestBin
            )
        }
    }

    /**
     * Executes windowing, FFT, magnitude conversion, and 1/3-octave smoothing.
     * Operates completely in-place on pre-allocated buffers.
     */
    fun processFrame(pcmSamples: ShortArray): FftResult {
        val startTimeNs = System.nanoTime()

        val length = min(pcmSamples.size, windowSize)

        // 1. Apply precomputed Hann window & normalize 16-bit PCM to [-1.0, 1.0]
        for (i in 0 until length) {
            fftBuffer[i] = (pcmSamples[i].toFloat() / 32768.0f) * hannWindow[i]
        }
        for (i in length until windowSize) {
            fftBuffer[i] = 0.0f
        }

        // 2. Compute Real Forward FFT using JTransforms
        fft.realForward(fftBuffer)

        // 3. Compute calibrated linear magnitudes:
        // DC bin (k = 0):
        val dcMag = abs(fftBuffer[0]) / sumHannWeights
        linearMagnitudes[0] = dcMag
        rawDbBuffer[0] = AudioMath.linearToDbFs(dcMag)

        // Nyquist bin (k = windowSize / 2):
        val nyquistMag = abs(fftBuffer[1]) / sumHannWeights
        val nyquistIndex = windowSize / 2
        linearMagnitudes[nyquistIndex] = nyquistMag
        rawDbBuffer[nyquistIndex] = AudioMath.linearToDbFs(nyquistMag)

        // Positive frequency bins (k = 1 until N/2):
        // Scale by 2.0 / sumHannWeights for single-sided amplitude calibration
        val scale = 2.0f / sumHannWeights
        for (k in 1 until nyquistIndex) {
            val re = fftBuffer[2 * k]
            val im = fftBuffer[2 * k + 1]
            val mag = sqrt(re * re + im * im) * scale
            linearMagnitudes[k] = mag
            rawDbBuffer[k] = AudioMath.linearToDbFs(mag)
        }

        // 4. Compute 1/3-Octave ISO 31-Band Energy Aggregation
        for (b in 0 until Iso31Bands.NUM_BANDS) {
            val mapping = bandMappings[b]
            val bins = mapping.bins

            val bandMag = when {
                bins.size > 1 -> {
                    // Sum the power (mag^2) across all bins in the band, normalized by ENBW to prevent window leakage inflation
                    var powerSum = 0.0
                    for (k in bins) {
                        val m = linearMagnitudes[k]
                        powerSum += (m * m)
                    }
                    sqrt(powerSum / enbwFactor).toFloat()
                }
                bins.size == 1 -> linearMagnitudes[bins[0]]
                else -> linearMagnitudes[mapping.nearestBin]
            }

            octaveDbBuffer[b] = AudioMath.linearToDbFs(bandMag)
        }

        val elapsedUs = (System.nanoTime() - startTimeNs) / 1000L

        return FftResult(
            rawFftDb = rawDbBuffer.copyOf(),
            octave31Db = octaveDbBuffer.copyOf(),
            binResolutionHz = binResolutionHz,
            executionTimeUs = elapsedUs
        )
    }

    private class BandMapping(
        val bins: IntArray,
        val nearestBin: Int
    )
}
