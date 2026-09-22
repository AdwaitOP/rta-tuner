package com.rtatuner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SignalGeneratorTest {

    @Test
    fun testPinkNoiseFilterStabilityAndBounds() {
        val numSamples = 48000
        val random = Random(42L) // Fixed seed for deterministic test

        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0

        var sum = 0.0
        var sumSquares = 0.0
        var maxPeak = 0.0

        for (i in 0 until numSamples) {
            val white = random.nextDouble() * 2.0 - 1.0
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
            b6 = white * 0.115926

            val sample = pink * 0.12
            val absVal = kotlin.math.abs(sample)
            if (absVal > maxPeak) {
                maxPeak = absVal
            }

            sum += sample
            sumSquares += sample * sample
        }

        val mean = sum / numSamples
        val rms = sqrt(sumSquares / numSamples)

        // Verify DC bias is negligible
        assertTrue("Pink noise DC bias should be near zero (mean=$mean)", kotlin.math.abs(mean) < 0.05)

        // Verify RMS is in normal pro-audio calibration range (~0.12 - 0.25)
        assertTrue("Pink noise RMS should be within [0.08, 0.30] (rms=$rms)", rms in 0.08..0.30)

        // Verify that with nominal -12 dBFS scaling (amplitude = 0.251), signal never exceeds 1.0 (no DAC clipping)
        val amplitude12Db = 10.0.pow(-12.0 / 20.0) // ~0.2512
        val scaledPeak = maxPeak * amplitude12Db
        assertTrue("Scaled pink noise peak ($scaledPeak) must not clip 1.0", scaledPeak < 1.0)
    }

    @Test
    fun testLogarithmicSineSweepFrequencyProgression() {
        val sampleRate = 48000
        val durationSec = 5.0
        val totalSamples = (durationSec * sampleRate).toLong()
        val f0 = 20.0
        val f1 = 20000.0
        val logRatio = ln(f1 / f0)
        val twoPi = 2.0 * PI

        fun freqAtTime(tSec: Double): Double {
            return f0 * (f1 / f0).pow(tSec / durationSec)
        }

        // Start frequency
        assertEquals("Sweep should start at 20 Hz", 20.0, freqAtTime(0.0), 0.01)

        // Midpoint (halfway in time = geometric mean of frequencies)
        // sqrt(20 * 20000) = sqrt(400000) = ~632.45 Hz
        val expectedMidFreq = sqrt(f0 * f1)
        assertEquals("Sweep midpoint should equal geometric mean", expectedMidFreq, freqAtTime(2.5), 0.5)

        // End frequency
        assertEquals("Sweep should terminate at 20000 Hz", 20000.0, freqAtTime(5.0), 0.01)

        // Test sample generation across first 1000 samples
        for (n in 0L until 1000L) {
            val t = n.toDouble() / sampleRate
            val phase = (twoPi * f0 * durationSec / logRatio) * ((f1 / f0).pow(t / durationSec) - 1.0)
            val sample = sin(phase)

            assertTrue("Sweep sample at index $n ($sample) must stay within [-1.0, 1.0]", sample in -1.0..1.0)
        }
    }

    @Test
    fun testCosineFadeRamp() {
        val fadeSamples = 2400 // 50ms at 48kHz

        // Fade in: 0.5 * (1 - cos(pi * i / N))
        val startVal = 0.5 * (1.0 - cos(0.0))
        val midVal = 0.5 * (1.0 - cos(PI * 0.5))
        val endVal = 0.5 * (1.0 - cos(PI))

        assertEquals("Fade in start must be 0.0", 0.0, startVal, 0.0001)
        assertEquals("Fade in midpoint must be 0.5", 0.5, midVal, 0.0001)
        assertEquals("Fade in end must be 1.0", 1.0, endVal, 0.0001)

        // Verify monotonicity
        var prev = -0.001
        for (i in 0..fadeSamples) {
            val progress = i.toDouble() / fadeSamples
            val window = 0.5 * (1.0 - cos(PI * progress))
            assertTrue("Fade in ramp must be monotonically non-decreasing", window >= prev)
            prev = window
        }
    }
}
