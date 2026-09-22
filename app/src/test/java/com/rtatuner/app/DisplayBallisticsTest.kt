package com.rtatuner.app

import com.rtatuner.app.ui.EmaPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.max

class DisplayBallisticsTest {

    @Test
    fun testLogarithmicFrequencyMapping() {
        val minFreq = 20.0f
        val maxFreq = 20000.0f
        val logSpan = log10(maxFreq / minFreq) // log10(1000) = 3.0

        fun freqToNorm(f: Float): Float {
            return log10(f / minFreq) / logSpan
        }

        // 20 Hz -> 0.0
        assertEquals(0.0f, freqToNorm(20f), 0.001f)

        // 200 Hz -> 1/3 (1 decade above 20 Hz)
        assertEquals(1.0f / 3.0f, freqToNorm(200f), 0.001f)

        // 2000 Hz -> 2/3 (2 decades above 20 Hz)
        assertEquals(2.0f / 3.0f, freqToNorm(2000f), 0.001f)

        // 20000 Hz -> 1.0 (3 decades above 20 Hz)
        assertEquals(1.0f, freqToNorm(20000f), 0.001f)

        // 1000 Hz -> log10(50) / 3.0 = 1.69897 / 3.0 = ~0.5663
        assertEquals(0.5663f, freqToNorm(1000f), 0.001f)
    }

    @Test
    fun testEmaFilterStepResponse() {
        // Step input from -100 dBFS to 0 dBFS
        var smoothed = -100.0f
        val input = 0.0f
        val alpha = EmaPreset.MEDIUM.alpha // 0.13f

        // First step
        smoothed = (alpha * input) + ((1.0f - alpha) * smoothed)
        assertEquals("First step smoothed = 0.13 * 0 + 0.87 * (-100) = -87 dBFS", -87.0f, smoothed, 0.01f)

        // Run 50 iterations: should steadily approach 0.0 dBFS without overshoot
        for (i in 0 until 50) {
            smoothed = (alpha * input) + ((1.0f - alpha) * smoothed)
        }
        assertTrue("After 50 steps at alpha 0.13, should be within 0.1 dB of target", smoothed > -0.1f && smoothed <= 0.0f)

        // Off preset should be instantaneous
        var offSmoothed = -100.0f
        offSmoothed = (EmaPreset.OFF.alpha * input) + ((1.0f - EmaPreset.OFF.alpha) * offSmoothed)
        assertEquals("EMA OFF should transition instantly to input", 0.0f, offSmoothed, 0.001f)
    }

    @Test
    fun testPerBandPeakHoldAndDecay() {
        val holdDurationMs = 1200L
        val decayRateDbPerSec = 25.0f

        var peakHold = -100.0f
        var peakHoldTimeMs = 0L

        fun updatePeakHold(currentVal: Float, nowMs: Long, dtSec: Float) {
            if (currentVal >= peakHold) {
                peakHold = currentVal
                peakHoldTimeMs = nowMs
            } else {
                val elapsed = nowMs - peakHoldTimeMs
                if (elapsed > holdDurationMs) {
                    val decayed = peakHold - (decayRateDbPerSec * dtSec)
                    peakHold = max(-100.0f, max(currentVal, decayed))
                }
            }
        }

        // Instantaneous jump on new peak at t = 1000 ms
        updatePeakHold(currentVal = -6.0f, nowMs = 1000L, dtSec = 0.016f)
        assertEquals("Peak hold should jump instantly to -6 dBFS", -6.0f, peakHold, 0.01f)

        // During hold window (e.g. t = 1800 ms, elapsed = 800 ms < 1200 ms), peak should remain locked
        updatePeakHold(currentVal = -30.0f, nowMs = 1800L, dtSec = 0.016f)
        assertEquals("Peak hold should remain locked during hold duration", -6.0f, peakHold, 0.01f)

        // After hold window (e.g. t = 2400 ms, elapsed = 1400 ms > 1200 ms):
        // Decays at 25 dB/s. Over dt = 0.2s, decayed by 5.0 dB: -6 - 5 = -11 dBFS
        updatePeakHold(currentVal = -30.0f, nowMs = 2400L, dtSec = 0.200f)
        assertEquals("Peak hold should decay at 25 dB/s after hold duration", -11.0f, peakHold, 0.01f)
    }
}
