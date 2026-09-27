package com.rtatuner.app

import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.dsp.BiquadFilterBank
import com.rtatuner.app.core.dsp.FftProcessor
import com.rtatuner.app.core.dsp.Iso31Bands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.abs

class BiquadFilterBankTest {

    private val sampleRate = 48000
    private val hopSize = 1024

    @Test
    fun testLowFrequency20HzAnd25HzIndependentResolution() {
        val filterBank = BiquadFilterBank(sampleRate = sampleRate)
        val fftProcessor = FftProcessor(windowSize = 2048, sampleRate = sampleRate)

        // 50 hops = ~1.07 seconds of continuous audio (sufficient for 4th-order 20 Hz filter to reach steady state)
        val numHops = 50
        val totalSamples = numHops * hopSize

        // --- 1. Test 20 Hz synthetic tone ---
        val tone20Full = AudioMath.generateSineWave(
            frequency = 20.0,
            sampleRate = sampleRate,
            numSamples = totalSamples,
            amplitude = 1.0
        )

        // Before: FFT-binning (window size 2048)
        val fftWindow20 = tone20Full.copyOfRange(0, 2048)
        val fftResult20 = fftProcessor.processFrame(fftWindow20)
        val fft20Band0 = fftResult20.octave31Db[0] // 20 Hz band
        val fft20Band1 = fftResult20.octave31Db[1] // 25 Hz band

        // Verify the original FFT binning bug: both 20 Hz and 25 Hz bands share the EXACT same value
        assertEquals(
            "Before fix: FFT binning caused 20 Hz and 25 Hz bands to have shared/identical values",
            fft20Band0,
            fft20Band1,
            0.001f
        )

        // After: Continuous 4th-Order Biquad Filter Bank
        filterBank.reset()
        var filterOutput20 = FloatArray(Iso31Bands.NUM_BANDS)
        for (h in 0 until numHops) {
            val hopPcm = tone20Full.copyOfRange(h * hopSize, (h + 1) * hopSize)
            filterOutput20 = filterBank.processHop(hopPcm, hopSize)
        }

        val biquad20Band0 = filterOutput20[0] // 20 Hz band
        val biquad20Band1 = filterOutput20[1] // 25 Hz band
        val peakBand20 = filterOutput20.indices.maxByOrNull { filterOutput20[it] } ?: -1

        // Verify independent resolution for 20 Hz tone
        assertEquals("20 Hz tone must peak at ISO band 0 (20 Hz)", 0, peakBand20)
        assertEquals("20 Hz band steady-state response must be near -3.01 dBFS (full scale sine RMS)", -3.01f, biquad20Band0, 0.8f)
        val attenuation20To25 = biquad20Band0 - biquad20Band1
        assertTrue(
            "4th-order adjacent 25 Hz band must be steeply attenuated (target meaningfully > 6 dB, was $attenuation20To25 dB)",
            attenuation20To25 >= 11.0f
        )

        // --- 2. Test 25 Hz synthetic tone ---
        val tone25Full = AudioMath.generateSineWave(
            frequency = 25.0,
            sampleRate = sampleRate,
            numSamples = totalSamples,
            amplitude = 1.0
        )

        // Before: FFT-binning
        val fftWindow25 = tone25Full.copyOfRange(0, 2048)
        val fftResult25 = fftProcessor.processFrame(fftWindow25)
        val fft25Band0 = fftResult25.octave31Db[0]
        val fft25Band1 = fftResult25.octave31Db[1]

        assertEquals(
            "Before fix: FFT binning caused 20 Hz and 25 Hz bands to have shared/identical values for 25 Hz tone",
            fft25Band0,
            fft25Band1,
            0.001f
        )

        // After: Continuous 4th-Order Biquad Filter Bank
        filterBank.reset()
        var filterOutput25 = FloatArray(Iso31Bands.NUM_BANDS)
        for (h in 0 until numHops) {
            val hopPcm = tone25Full.copyOfRange(h * hopSize, (h + 1) * hopSize)
            filterOutput25 = filterBank.processHop(hopPcm, hopSize)
        }

        val biquad25Band0 = filterOutput25[0] // 20 Hz band
        val biquad25Band1 = filterOutput25[1] // 25 Hz band
        val peakBand25 = filterOutput25.indices.maxByOrNull { filterOutput25[it] } ?: -1

        // Verify independent resolution for 25 Hz tone
        assertEquals("25 Hz tone must peak at ISO band 1 (25 Hz)", 1, peakBand25)
        assertEquals("25 Hz band steady-state response must be near -3.01 dBFS (full scale sine RMS)", -3.01f, biquad25Band1, 0.8f)
        val attenuation25To20 = biquad25Band1 - biquad25Band0
        assertTrue(
            "4th-order adjacent 20 Hz band must be steeply attenuated (target meaningfully > 6 dB, was $attenuation25To20 dB)",
            attenuation25To20 >= 11.0f
        )

        println(
            """
            === 4th-Order 20 Hz / 25 Hz Resolution Comparison ===
            20 Hz Tone:
              Before (FFT):    Band 0 (20 Hz) = $fft20Band0 dBFS, Band 1 (25 Hz) = $fft20Band1 dBFS (Delta: ${fft20Band0 - fft20Band1} dB -> Identical!)
              After  (4th-Ord):Band 0 (20 Hz) = $biquad20Band0 dBFS, Band 1 (25 Hz) = $biquad20Band1 dBFS (Delta: $attenuation20To25 dB -> Steeply Resolved!)
            25 Hz Tone:
              Before (FFT):    Band 0 (20 Hz) = $fft25Band0 dBFS, Band 1 (25 Hz) = $fft25Band1 dBFS (Delta: ${fft25Band1 - fft25Band0} dB -> Identical!)
              After  (4th-Ord):Band 0 (20 Hz) = $biquad25Band0 dBFS, Band 1 (25 Hz) = $biquad25Band1 dBFS (Delta: $attenuation25To20 dB -> Steeply Resolved!)
            """.trimIndent()
        )
    }

    @Test
    fun testHighFrequency10kHzFullBandwidthCapture() {
        val filterBank = BiquadFilterBank(sampleRate = sampleRate)
        val band10kIndex = 27 // ISO band 27 is 10 kHz
        assertEquals("Band 27 must be 10000 Hz", 10000f, Iso31Bands.CENTER_FREQUENCIES[band10kIndex])

        val lowerEdge = Iso31Bands.LOWER_EDGES[band10kIndex] // ~8909 Hz
        val upperEdge = Iso31Bands.UPPER_EDGES[band10kIndex] // ~11225 Hz
        val bandwidthHz = upperEdge - lowerEdge               // ~2316 Hz

        assertTrue("10 kHz band bandwidth should be ~2.3 kHz", bandwidthHz in 2200f..2400f)

        // Helper to run tone through filter bank until settled (at 10 kHz, settling takes < 5 ms / 1 hop)
        fun measureBandResponse(freqHz: Double): FloatArray {
            filterBank.reset()
            var result = FloatArray(Iso31Bands.NUM_BANDS)
            val numHops = 10
            val tone = AudioMath.generateSineWave(freqHz, sampleRate, numHops * hopSize, 1.0)
            for (h in 0 until numHops) {
                val hop = tone.copyOfRange(h * hopSize, (h + 1) * hopSize)
                result = filterBank.processHop(hop, hopSize)
            }
            return result
        }

        // 1. Center frequency: 10,000 Hz
        val resCenter = measureBandResponse(10000.0)
        val peakCenter = resCenter.indices.maxByOrNull { resCenter[it] } ?: -1
        assertEquals("10 kHz tone must peak in band 27", band10kIndex, peakCenter)
        assertEquals("10 kHz center response should be ~ -3.01 dBFS", -3.01f, resCenter[band10kIndex], 0.2f)

        // 2. In-band frequency: 9500 Hz (500 Hz below center, inside the 2.3 kHz band)
        val res9500 = measureBandResponse(9500.0)
        val peak9500 = res9500.indices.maxByOrNull { res9500[it] } ?: -1
        assertEquals("9500 Hz tone must still peak in band 27", band10kIndex, peak9500)
        val drop9500 = resCenter[band10kIndex] - res9500[band10kIndex]
        assertTrue("9500 Hz response must be within 3.0 dB of center (was drop of $drop9500 dB)", drop9500 < 3.0f)

        // 3. In-band frequency: 10500 Hz (500 Hz above center, inside the 2.3 kHz band)
        val res10500 = measureBandResponse(10500.0)
        val peak10500 = res10500.indices.maxByOrNull { res10500[it] } ?: -1
        assertEquals("10500 Hz tone must still peak in band 27", band10kIndex, peak10500)
        val drop10500 = resCenter[band10kIndex] - res10500[band10kIndex]
        assertTrue("10500 Hz response must be within 3.0 dB of center (was drop of $drop10500 dB)", drop10500 < 3.0f)

        // 4. In-band wider frequency: 9300 Hz (700 Hz below center)
        val res9300 = measureBandResponse(9300.0)
        val peak9300 = res9300.indices.maxByOrNull { res9300[it] } ?: -1
        assertEquals("9300 Hz tone must still peak in band 27", band10kIndex, peak9300)
        val drop9300 = resCenter[band10kIndex] - res9300[band10kIndex]
        assertTrue("9300 Hz response must still peak in band 27 and be within 5.0 dB (was drop of $drop9300 dB)", drop9300 < 5.0f)

        // 5. In-band wider frequency: 10700 Hz (700 Hz above center)
        val res10700 = measureBandResponse(10700.0)
        val peak10700 = res10700.indices.maxByOrNull { res10700[it] } ?: -1
        assertEquals("10700 Hz tone must still peak in band 27", band10kIndex, peak10700)
        val drop10700 = resCenter[band10kIndex] - res10700[band10kIndex]
        assertTrue("10700 Hz response must still peak in band 27 and be within 5.0 dB (was drop of $drop10700 dB)", drop10700 < 5.0f)

        // 6. Out-of-band adjacent centers: 8000 Hz (band 26) and 12500 Hz (band 28)
        val res8k = measureBandResponse(8000.0)
        val peak8k = res8k.indices.maxByOrNull { res8k[it] } ?: -1
        assertEquals("8000 Hz tone must peak in band 26", 26, peak8k)
        val atten8k = resCenter[band10kIndex] - res8k[band10kIndex]
        assertTrue(
            "8000 Hz tone should attenuate band 27 by > 15 dB in 4th-order (was $atten8k dB)",
            atten8k > 15.0f
        )

        val res12k5 = measureBandResponse(12500.0)
        val peak12k5 = res12k5.indices.maxByOrNull { res12k5[it] } ?: -1
        assertEquals("12500 Hz tone must peak in band 28", 28, peak12k5)
        val atten12k5 = resCenter[band10kIndex] - res12k5[band10kIndex]
        assertTrue(
            "12500 Hz tone should attenuate band 27 by > 15 dB in 4th-order (was $atten12k5 dB)",
            atten12k5 > 15.0f
        )

        println(
            """
            === 4th-Order 10 kHz Band (~2.3 kHz Bandwidth) Capture Test ===
              Center (10,000 Hz): Band 27 = ${resCenter[band10kIndex]} dBFS (Peak Band: $peakCenter)
              Core In(9,500 Hz):  Band 27 = ${res9500[band10kIndex]} dBFS (Peak Band: $peak9500, Drop: $drop9500 dB)
              Core In(10,500 Hz): Band 27 = ${res10500[band10kIndex]} dBFS (Peak Band: $peak10500, Drop: $drop10500 dB)
              Wide In(9,300 Hz):  Band 27 = ${res9300[band10kIndex]} dBFS (Peak Band: $peak9300, Drop: $drop9300 dB)
              Wide In(10,700 Hz): Band 27 = ${res10700[band10kIndex]} dBFS (Peak Band: $peak10700, Drop: $drop10700 dB)
              Adj Low(8,000 Hz):  Band 27 = ${res8k[band10kIndex]} dBFS (Attenuated: $atten8k dB)
              Adj High(12,500Hz): Band 27 = ${res12k5[band10kIndex]} dBFS (Attenuated: $atten12k5 dB)
            """.trimIndent()
        )
    }

    @Test
    fun testSilenceProducesMinimumDbFloor() {
        val filterBank = BiquadFilterBank(sampleRate = sampleRate)
        val silence = ShortArray(hopSize) { 0 }
        val result = filterBank.processHop(silence, hopSize)

        for (b in 0 until Iso31Bands.NUM_BANDS) {
            assertEquals("Silence in band $b must be -100 dBFS", AudioMath.MIN_DB_FS, result[b], 0.01f)
        }
    }

    @Test
    fun testStatePersistenceAcrossHops() {
        val filterBankWithPersistence = BiquadFilterBank(sampleRate = sampleRate)
        val filterBankWithoutPersistence = BiquadFilterBank(sampleRate = sampleRate)

        val numHops = 45
        val tone20 = AudioMath.generateSineWave(20.0, sampleRate, numHops * hopSize, 1.0)

        var finalWithPersistence = -100f
        var finalWithoutPersistence = -100f

        for (h in 0 until numHops) {
            val hop = tone20.copyOfRange(h * hopSize, (h + 1) * hopSize)
            finalWithPersistence = filterBankWithPersistence.processHop(hop, hopSize)[0]

            // Resetting state on every hop destroys the IIR integrator for low frequencies
            filterBankWithoutPersistence.reset()
            finalWithoutPersistence = filterBankWithoutPersistence.processHop(hop, hopSize)[0]
        }

        // With persistence: 20 Hz filter settles to ~ -3.01 dBFS (within 0.8 dB instantaneous sub-cycle hop ripple)
        assertEquals("With state persistence, 20 Hz settles to full scale RMS", -3.01f, finalWithPersistence, 0.8f)
        // Without persistence: single hop of 21.3 ms is less than half a cycle of 20 Hz, resulting in severe truncation
        assertTrue(
            "Without persistence, 20 Hz filter cannot settle (was $finalWithoutPersistence dBFS)",
            finalWithoutPersistence < -10.0f
        )
    }

    @Test
    fun testCpuPerformanceAndTimingBudget() {
        val filterBank = BiquadFilterBank(sampleRate = sampleRate)
        val randomTone = AudioMath.generateSineWave(1000.0, sampleRate, hopSize, 0.8)

        // Warm up JIT
        for (i in 0 until 100) {
            filterBank.processHop(randomTone, hopSize)
        }

        // Measure 1,000 hops (representing 1024 * 1000 / 48000 = ~21.33 seconds of continuous audio)
        val testHops = 1000
        val startNs = System.nanoTime()
        for (i in 0 until testHops) {
            filterBank.processHop(randomTone, hopSize)
        }
        val totalElapsedNs = System.nanoTime() - startNs

        val avgUsPerHop = (totalElapsedNs / testHops) / 1000.0
        val avgMsPerHop = avgUsPerHop / 1000.0
        val budgetMs = (hopSize.toDouble() / sampleRate) * 1000.0 // 21.33 ms

        println(
            """
            === CPU Timing Profile for 4th-Order 31-Band Continuous Biquad Filter Bank ===
              Evaluated:        $testHops continuous hops (1024 samples each, 48 kHz)
              Average time/hop: ${String.format("%.3f", avgMsPerHop)} ms ($avgUsPerHop µs)
              Hop audio budget: ${String.format("%.2f", budgetMs)} ms
              CPU utilization:  ${String.format("%.2f", (avgMsPerHop / budgetMs) * 100.0)}% of audio frame budget
            """.trimIndent()
        )

        // Confirm comfortably within 1-2 ms budget (far below the 21.33 ms audio frame budget)
        assertTrue(
            "Average 4th-order filter bank execution time per hop (${avgMsPerHop} ms) must be well below 2.0 ms",
            avgMsPerHop < 2.0
        )
    }

    @Test
    fun testDiagnosticCoefficientsLoggedAndAssertUniqueness() {
        val filterBank = BiquadFilterBank(sampleRate = sampleRate)
        val allCoeffs = filterBank.getAllBandCoefficients()

        assertEquals("Must have 31 coefficient sets for 31 ISO bands", Iso31Bands.NUM_BANDS, allCoeffs.size)

        println("=========================================================================================================")
        println(" DIAGNOSTIC COEFFICIENTS: 31-BAND 4TH-ORDER BIQUAD FILTER BANK (Fs = $sampleRate Hz, Q ≈ ${String.format(Locale.US, "%.4f", filterBank.q)})")
        println("=========================================================================================================")
        println(String.format(Locale.US, "%-4s | %-6s | %-8s | %-8s | %-12s | %-12s | %-12s | %-12s | %-12s",
            "Band", "Label", "f0 (Hz)", "Q", "b0", "b1", "b2", "a1", "a2"))
        println("---------------------------------------------------------------------------------------------------------")

        for (c in allCoeffs) {
            println(String.format(Locale.US, "%-4d | %-6s | %-8.1f | %-8.4f | %-12.8f | %-12.8f | %-12.8f | %-12.8f | %-12.8f",
                c.bandIndex, Iso31Bands.LABELS[c.bandIndex], c.f0, c.q, c.b0, c.b1, c.b2, c.a1, c.a2))
        }
        println("=========================================================================================================\n")

        // Detail Bands 0 through 9 explicitly to confirm no integer rounding / frequency truncation
        println("--- Detailed Inspection for Low ISO Bands 0..9 (20 Hz - 160 Hz) ---")
        for (i in 0..9) {
            val c = allCoeffs[i]
            println("Band $i (${Iso31Bands.LABELS[i]} Hz): f0=${c.f0}, b0=${c.b0}, b2=${c.b2}, a1=${c.a1}, a2=${c.a2}")
            // Verify center frequency is not truncated to an integer
            assertEquals("Center frequency must match Iso31Bands exactly", Iso31Bands.CENTER_FREQUENCIES[i], c.f0, 0.0001f)
            assertTrue("b0 must be positive and non-zero", c.b0 > 0.0)
            assertEquals("b2 must equal -b0", -c.b0, c.b2, 1e-12)
            assertEquals("b1 must be 0 for 0 dB BPF", 0.0, c.b1, 1e-12)
        }

        // Pairwise comparison: Assert NO two bands among all 31 bands have identical coefficient sets
        for (i in 0 until allCoeffs.size) {
            for (j in i + 1 until allCoeffs.size) {
                val c1 = allCoeffs[i]
                val c2 = allCoeffs[j]

                val isIdentical = (c1.f0 == c2.f0) &&
                        (c1.b0 == c2.b0) &&
                        (c1.a1 == c2.a1) &&
                        (c1.a2 == c2.a2)

                assertFalse(
                    "Band $i (${c1.f0} Hz) and Band $j (${c2.f0} Hz) must NOT have identical coefficients!",
                    isIdentical
                )

                // Adjacent bands must have distinct b0 and a1
                if (j == i + 1) {
                    val deltaB0 = abs(c1.b0 - c2.b0)
                    val deltaA1 = abs(c1.a1 - c2.a1)
                    assertTrue("Adjacent bands $i and $j must have distinct b0 (delta=$deltaB0)", deltaB0 > 1e-7)
                    assertTrue("Adjacent bands $i and $j must have distinct a1 (delta=$deltaA1)", deltaA1 > 1e-7)
                }
            }
        }
    }

    @Test
    fun testLiveSignalBroadbandNoiseDistinctOutputsAcrossAllAdjacentBands() {
        val filterBank = BiquadFilterBank(sampleRate = sampleRate)
        filterBank.reset()

        // Generate broadband excitation: pseudo-random noise sequence (simulating door thump or white/pink noise)
        val rng = Random(42L)
        val numHops = 30
        val noiseSamples = ShortArray(numHops * hopSize) {
            (rng.nextGaussian() * 8000.0).toInt().coerceIn(-32767, 32767).toShort()
        }

        println("=== Live-Signal Broadband Noise Test (30 hops x $hopSize samples) ===")

        for (h in 0 until numHops) {
            val hop = noiseSamples.copyOfRange(h * hopSize, (h + 1) * hopSize)
            val result = filterBank.processHop(hop, hopSize)

            assertEquals("Filter bank must return 31 band dBFS values", Iso31Bands.NUM_BANDS, result.size)

            // Check that every band has a valid dBFS value
            for (b in 0 until Iso31Bands.NUM_BANDS) {
                assertTrue("Band $b dBFS must be finite and >= -100", result[b] in -100.0f..0.0f)
            }

            // After settling (hop >= 5), check adjacent band distinctness
            if (h >= 5) {
                for (b in 0 until Iso31Bands.NUM_BANDS - 1) {
                    val val1 = result[b]
                    val val2 = result[b + 1]

                    // Assert NO adjacent bands produce bit-for-bit identical output
                    assertNotEquals(
                        "Hop $h: Adjacent bands $b (${Iso31Bands.LABELS[b]} Hz) and ${b + 1} (${Iso31Bands.LABELS[b + 1]} Hz) must NOT produce bit-for-bit identical outputs! (Both were $val1 dBFS)",
                        java.lang.Float.floatToIntBits(val1),
                        java.lang.Float.floatToIntBits(val2)
                    )
                }

                // Specifically assert that the bands reported by the user (Bands 0-1-2, 3-4, 5-6 in 0-index or 1-2-3, 4-5, 6-7 in 1-index)
                // are NOT identical:
                assertNotEquals("Hop $h: Band 0 and Band 1 must not be identical", result[0], result[1], 0.0001f)
                assertNotEquals("Hop $h: Band 1 and Band 2 must not be identical", result[1], result[2], 0.0001f)
                assertNotEquals("Hop $h: Band 3 and Band 4 must not be identical", result[3], result[4], 0.0001f)
                assertNotEquals("Hop $h: Band 5 and Band 6 must not be identical", result[5], result[6], 0.0001f)
            }

            if (h == numHops - 1) {
                println("Final Hop Outputs for Bands 0..9:")
                for (b in 0..9) {
                    println("  Band $b (${Iso31Bands.LABELS[b]} Hz): ${result[b]} dBFS")
                }
            }
        }
    }

    @Test
    fun testLegacyFftBinningCausesExactBandCollapseReportedByUser() {
        val fftProcessor = FftProcessor(windowSize = 2048, sampleRate = sampleRate)
        val rng = Random(12345L)
        val randomWindow = ShortArray(2048) {
            (rng.nextGaussian() * 8000.0).toInt().coerceIn(-32767, 32767).toShort()
        }

        val fftResult = fftProcessor.processFrame(randomWindow)

        // In legacy FFT binning (windowSize=2048, Fs=48000 -> bin width 23.4375 Hz):
        // Band 0 (20 Hz), Band 1 (25 Hz), Band 2 (31.5 Hz) all map to FFT bin 1:
        assertEquals("Legacy FFT: Band 0 and Band 1 are bit-for-bit identical",
            fftResult.octave31Db[0], fftResult.octave31Db[1], 0.0f)
        assertEquals("Legacy FFT: Band 1 and Band 2 are bit-for-bit identical",
            fftResult.octave31Db[1], fftResult.octave31Db[2], 0.0f)

        // Band 3 (40 Hz) and Band 4 (50 Hz) both map to FFT bin 2:
        assertEquals("Legacy FFT: Band 3 and Band 4 are bit-for-bit identical",
            fftResult.octave31Db[3], fftResult.octave31Db[4], 0.0f)

        // Band 5 (63 Hz) and Band 6 (80 Hz) both map to FFT bin 3:
        assertEquals("Legacy FFT: Band 5 and Band 6 are bit-for-bit identical",
            fftResult.octave31Db[5], fftResult.octave31Db[6], 0.0f)

        println(
            """
            === Legacy FFT-Binning Root Cause Demonstration ===
            Identical bands in legacy FFT output:
              Bin 1 (23.4 Hz): Band 0 (20 Hz)=${fftResult.octave31Db[0]} dB, Band 1 (25 Hz)=${fftResult.octave31Db[1]} dB, Band 2 (31.5 Hz)=${fftResult.octave31Db[2]} dB [BIT-FOR-BIT IDENTICAL]
              Bin 2 (46.9 Hz): Band 3 (40 Hz)=${fftResult.octave31Db[3]} dB, Band 4 (50 Hz)=${fftResult.octave31Db[4]} dB [BIT-FOR-BIT IDENTICAL]
              Bin 3 (70.3 Hz): Band 5 (63 Hz)=${fftResult.octave31Db[5]} dB, Band 6 (80 Hz)=${fftResult.octave31Db[6]} dB [BIT-FOR-BIT IDENTICAL]
            This perfectly replicates the user's reported symptom: bands 1-2-3, 4-5, and 6-7 collapsing!
            """.trimIndent()
        )
    }
}
