package com.rtatuner.app.core.audio

import android.media.AudioRecord
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance audio math utilities designed for zero allocation inside the capture loop.
 */
object AudioMath {
    /** Clamped floor for dBFS displays representing digital silence */
    const val MIN_DB_FS = -100.0f

    /** 16-bit PCM clipping threshold (-0.05 dBFS from full scale) */
    const val CLIP_THRESHOLD_SHORT: Short = 32570

    private const val EPSILON = 1e-7f
    private const val SHORT_MAX_F = 32768.0f
    private const val TWO_PI = 2.0 * Math.PI

    /**
     * Computes Peak and RMS in dBFS from a 16-bit PCM short array in a single pass.
     * Operates with ZERO heap allocations.
     *
     * @param buffer Pre-allocated PCM samples
     * @param length Number of valid samples to evaluate
     * @return Pair of (rmsDb, peakDb)
     */
    fun computeRmsAndPeakDb(buffer: ShortArray, length: Int): Pair<Float, Float> {
        if (length <= 0) return Pair(MIN_DB_FS, MIN_DB_FS)

        var sumSquares = 0.0
        var maxPeak = 0

        for (i in 0 until length) {
            val sample = buffer[i].toInt()
            val absSample = abs(sample)
            if (absSample > maxPeak) {
                maxPeak = absSample
            }
            val normalized = sample / SHORT_MAX_F
            sumSquares += (normalized * normalized)
        }

        val peakLinear = maxPeak / SHORT_MAX_F
        val peakDb = linearToDbFs(peakLinear)

        val meanSquare = sumSquares / length
        val rmsLinear = sqrt(meanSquare).toFloat()
        val rmsDb = linearToDbFs(rmsLinear)

        return Pair(rmsDb, peakDb)
    }

    /**
     * Converts a normalized linear amplitude [0.0..1.0] to decibels relative to Full Scale (dBFS).
     * Strictly guards against NaN and Infinity, clamping safely to silence [MIN_DB_FS].
     */
    fun linearToDbFs(linear: Float): Float {
        if (linear.isNaN() || linear <= EPSILON) return MIN_DB_FS
        if (linear.isInfinite()) return if (linear > 0f) 0.0f else MIN_DB_FS
        val db = 20.0f * log10(linear)
        if (db.isNaN() || db.isInfinite()) return MIN_DB_FS
        return max(MIN_DB_FS, db)
    }

    /**
     * Checks if any sample within [length] hits or exceeds the clipping threshold.
     */
    fun isClipping(buffer: ShortArray, length: Int): Boolean {
        for (i in 0 until length) {
            if (abs(buffer[i].toInt()) >= CLIP_THRESHOLD_SHORT) {
                return true
            }
        }
        return false
    }

    /**
     * Maps negative AudioRecord return codes to clear, human-readable explanations.
     */
    fun mapAudioRecordError(errorCode: Int): String {
        return when (errorCode) {
            AudioRecord.ERROR_DEAD_OBJECT -> "Audio server died or mic disconnected (ERROR_DEAD_OBJECT)"
            AudioRecord.ERROR_INVALID_OPERATION -> "AudioRecord state invalid or mic stolen (ERROR_INVALID_OPERATION)"
            AudioRecord.ERROR_BAD_VALUE -> "Invalid capture buffer parameters (ERROR_BAD_VALUE)"
            AudioRecord.ERROR -> "Generic audio HAL capture error (ERROR)"
            else -> "Hardware capture error code ($errorCode)"
        }
    }

    /**
     * Generates a 16-bit PCM sinusoidal wave for deterministic pipeline verification and unit tests.
     */
    fun generateSineWave(
        frequency: Double,
        sampleRate: Int,
        numSamples: Int,
        amplitude: Double = 1.0
    ): ShortArray {
        val buffer = ShortArray(numSamples)
        val angularFreq = TWO_PI * frequency / sampleRate
        val scale = (32767.0 * amplitude).coerceIn(0.0, 32767.0)

        for (i in 0 until numSamples) {
            val sampleVal = (sin(i * angularFreq) * scale).toInt()
            buffer[i] = sampleVal.toShort()
        }
        return buffer
    }

    /**
     * Result of power-domain noise subtraction.
     */
    data class NoiseSubtractionResult(
        val correctedDb: Float,
        val isUnreliable: Boolean, // True if SNR (signalDb - noiseDb) < 6.0 dB
        val isBelowFloor: Boolean  // True if signalDb <= noiseDb
    )

    /**
     * Subtracts captured ambient noise floor from live signal in the linear power domain:
     * P_clean = max(epsilon, 10^(signal/10) - 10^(noise/10))
     *
     * Handles edge cases cleanly:
     * - If signal or noise is NaN or Infinite: returns minFloorDb safely.
     * - If signal <= noise: returns minFloorDb with isBelowFloor = true and isUnreliable = true.
     * - If signal - noise < 6.0 dB: returns corrected dB with isUnreliable = true (SNR is low).
     * - If signal - noise >= 6.0 dB: returns corrected dB with isUnreliable = false (reliable signal).
     */
    fun subtractNoiseFloor(
        signalDb: Float,
        noiseDb: Float,
        minFloorDb: Float = MIN_DB_FS
    ): NoiseSubtractionResult {
        if (signalDb.isNaN() || noiseDb.isNaN() || signalDb.isInfinite() || noiseDb.isInfinite() || signalDb <= noiseDb) {
            return NoiseSubtractionResult(
                correctedDb = minFloorDb,
                isUnreliable = true,
                isBelowFloor = true
            )
        }

        val snrDb = signalDb - noiseDb
        val isUnreliable = snrDb < 6.0f

        // Convert to linear power: P = 10^(dB / 10)
        val pSignal = Math.pow(10.0, signalDb / 10.0)
        val pNoise = Math.pow(10.0, noiseDb / 10.0)
        val pClean = pSignal - pNoise

        val corrected = if (pClean > 1e-12 && !pClean.isNaN() && !pClean.isInfinite()) {
            val db = (10.0 * log10(pClean)).toFloat()
            if (db.isNaN() || db.isInfinite()) minFloorDb else db.coerceAtLeast(minFloorDb)
        } else {
            minFloorDb
        }

        return NoiseSubtractionResult(
            correctedDb = corrected,
            isUnreliable = isUnreliable,
            isBelowFloor = false
        )
    }
}

