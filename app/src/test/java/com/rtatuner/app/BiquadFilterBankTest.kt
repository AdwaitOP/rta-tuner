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
            attenuation20To25 >= 8.0f
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
            attenuation25To20 >= 8.0f
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
            "8000 Hz tone should attenuate band 27 by >= 7.0 dB in 4th-order (was $atten8k dB)",
            atten8k >= 7.0f
        )

        val res12k5 = measureBandResponse(12500.0)
        val peak12k5 = res12k5.indices.maxByOrNull { res12k5[it] } ?: -1
        assertEquals("12500 Hz tone must peak in band 28", 28, peak12k5)
        val atten12k5 = resCenter[band10kIndex] - res12k5[band10kIndex]
        assertTrue(
            "12500 Hz tone should attenuate band 27 by >= 8.5 dB in 4th-order (was $atten12k5 dB)",
            atten12k5 >= 8.5f
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
              Average time/hop: ${String.format("%.3f", avgMsPerHop)} ms ($avgUsPerHop Âµs)
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
        println(" DIAGNOSTIC COEFFICIENTS: 31-BAND 4TH-ORDER BIQUAD FILTER BANK (Fs = $sampleRate Hz, Q â‰ˆ ${String.format(Locale.US, "%.4f", filterBank.q)})")
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

    @Test
    fun testPinkNoiseIntegratedResponseAt48kHzAnd44kHz() {
        fun computePinkNoiseRelativeLevels(sampleRate: Int): FloatArray {
            val filterBank = BiquadFilterBank(sampleRate = sampleRate)
            val coeffs = filterBank.getAllBandCoefficients()
            val fs = sampleRate.toDouble()
            val numPoints = 50000
            val df = (fs * 0.5) / numPoints
            val pinkPower = DoubleArray(Iso31Bands.NUM_BANDS)

            for (b in 0 until Iso31Bands.NUM_BANDS) {
                val c = coeffs[b]
                val b0 = c.b0
                val a1 = c.a1
                val a2 = c.a2
                if (b0 == 0.0) continue

                var sumPower = 0.0
                for (i in 1 until numPoints) {
                    val f = i * df
                    val omega = 2.0 * Math.PI * f / fs
                    val cosW = Math.cos(omega)
                    val cos2W = Math.cos(2.0 * omega)
                    val sinW = Math.sin(omega)
                    val sin2W = Math.sin(2.0 * omega)

                    val numRe = b0 * (1.0 - cos2W)
                    val numIm = b0 * sin2W
                    val numMagSq = numRe * numRe + numIm * numIm

                    val denRe = 1.0 + a1 * cosW + a2 * cos2W
                    val denIm = -(a1 * sinW + a2 * sin2W)
                    val denMagSq = denRe * denRe + denIm * denIm

                    val hCascadeMagSq = (numMagSq / denMagSq).let { it * it }
                    val psd = 1.0 / f
                    sumPower += psd * hCascadeMagSq * df
                }
                pinkPower[b] = sumPower
            }

            val ref1k = pinkPower[17] // Band 17 is 1 kHz
            return FloatArray(Iso31Bands.NUM_BANDS) { b ->
                if (pinkPower[b] > 0.0) (10.0 * kotlin.math.log10(pinkPower[b] / ref1k)).toFloat() else -100.0f
            }
        }

        // --- 48 kHz Verification ---
        val rel48 = computePinkNoiseRelativeLevels(48000)
        println("=== 48 kHz Pink Noise Response Relative to 1 kHz ===")
        for (b in 0 until Iso31Bands.NUM_BANDS) {
            println(String.format(Locale.US, "Band %2d (%6.0f Hz): %+.2f dB", b, Iso31Bands.CENTER_FREQUENCIES[b], rel48[b]))
        }
        // Bands 0..29 (up to 16 kHz) are safely below Nyquist and must be flat within 0.35 dB of 1 kHz
        for (b in 0 until Iso31Bands.NUM_BANDS - 1) {
            assertTrue(
                "Band $b (${Iso31Bands.CENTER_FREQUENCIES[b]} Hz) at 48 kHz must be within 0.35 dB of flat (was ${rel48[b]} dB)",
                kotlin.math.abs(rel48[b]) < 0.35f
            )
        }
        // Band 30 (20 kHz at 48 kHz Fs): Upper skirt is bounded by the 24 kHz Nyquist ceiling, yielding -0.942 dB.
        // The severe baseline FFT deficit (-7.15 dB) is significantly mitigated, but not completely eliminated.
        assertEquals(
            "20 kHz pink-noise deviation at 48 kHz must match verified reference ~ -0.942 dB",
            -0.942f,
            rel48[30],
            0.05f
        )
        assertTrue(
            "16 kHz pink-noise deficit is mitigated (legacy defect was -3.81 dB, now ${rel48[29]} dB)",
            rel48[29] in -0.15f..0.20f
        )

        // --- 44.1 kHz Verification ---
        val rel44 = computePinkNoiseRelativeLevels(44100)
        println("=== 44.1 kHz Pink Noise Response Relative to 1 kHz ===")
        for (b in 0 until Iso31Bands.NUM_BANDS) {
            println(String.format(Locale.US, "Band %2d (%6.0f Hz): %+.2f dB", b, Iso31Bands.CENTER_FREQUENCIES[b], rel44[b]))
        }
        for (b in 0 until Iso31Bands.NUM_BANDS - 1) { // Bands 0..29 (up to 16 kHz)
            assertTrue(
                "Band $b (${Iso31Bands.CENTER_FREQUENCIES[b]} Hz) at 44.1 kHz must be within 0.35 dB of flat (was ${rel44[b]} dB)",
                kotlin.math.abs(rel44[b]) < 0.35f
            )
        }
        // Band 30 (20 kHz at 44.1 kHz Fs): Nyquist wall is at 22.05 kHz, so nominal upper cutoff (22,449 Hz)
        // is physically above Nyquist. The filter naturally rolls off to zero at Nyquist, yielding -1.339 dB.
        assertEquals(
            "20 kHz pink-noise deviation at 44.1 kHz must match verified reference ~ -1.339 dB",
            -1.339f,
            rel44[30],
            0.05f
        )
    }

    @Test
    fun testAll31BandsCenterFrequencyGain() {
        val filterBank = BiquadFilterBank(sampleRate = sampleRate)

        for (b in 0 until Iso31Bands.NUM_BANDS) {
            val f0 = Iso31Bands.CENTER_FREQUENCIES[b].toDouble()
            if (f0 >= sampleRate * 0.5) continue

            filterBank.reset()
            val cycles = 30.0
            val durationSec = kotlin.math.max(0.25, cycles / f0)
            val numSamples = (durationSec * sampleRate).toInt().coerceAtLeast(hopSize * 4)
            val numHops = (numSamples + hopSize - 1) / hopSize
            val tone = AudioMath.generateSineWave(f0, sampleRate, numHops * hopSize, 1.0)

            var lastResult = FloatArray(Iso31Bands.NUM_BANDS)
            for (h in 0 until numHops) {
                val hop = tone.copyOfRange(h * hopSize, (h + 1) * hopSize)
                lastResult = filterBank.processHop(hop, hopSize)
            }

            val peakBand = lastResult.indices.maxByOrNull { lastResult[it] } ?: -1
            assertEquals("Tone at $f0 Hz must peak at band $b", b, peakBand)
            // Tolerances:
            // - Frequencies below 100 Hz have cycle periods (12.5 - 50 ms) comparable to the 21.3 ms hop window,
            //   producing sub-cycle instantaneous RMS ripple (tolerance 0.8 dB as in testLowFrequency20HzAnd25HzIndependentResolution).
            // - All other bands across the entire audio spectrum (100 Hz to 20,000 Hz): tight tolerance 0.35 dB,
            //   confirming that exact pre-warped center resonance Omega0 = tan(pi * f0 / Fs) eliminates high-frequency deficits.
            val tol = if (f0 < 100.0) 0.8f else 0.35f
            val responseAtCenter = lastResult[b]
            assertEquals(
                "Band $b ($f0 Hz) center frequency response must be -3.01 dBFS (±$tol dB tolerance, was $responseAtCenter dBFS)",
                -3.01f,
                responseAtCenter,
                tol
            )
        }
    }

    @Test
    fun testNominalBandEdgeResponseWithinTolerances() {
        val filterBank = BiquadFilterBank(sampleRate = sampleRate)
        val representativeBands = listOf(7, 17, 24, 27, 29, 30) // 100 Hz, 1 kHz, 5 kHz, 10 kHz, 16 kHz, 20 kHz

        for (b in representativeBands) {
            val f0 = Iso31Bands.CENTER_FREQUENCIES[b].toDouble()
            val lowerEdge = Iso31Bands.LOWER_EDGES[b].toDouble()
            val upperEdge = Iso31Bands.UPPER_EDGES[b].toDouble()

            fun measureResponseAt(freq: Double): Float {
                filterBank.reset()
                val durationSec = kotlin.math.max(0.15, 20.0 / freq)
                val numSamples = (durationSec * sampleRate).toInt().coerceAtLeast(hopSize * 3)
                val numHops = (numSamples + hopSize - 1) / hopSize
                val tone = AudioMath.generateSineWave(freq, sampleRate, numHops * hopSize, 1.0)
                var result = FloatArray(Iso31Bands.NUM_BANDS)
                for (h in 0 until numHops) {
                    val hop = tone.copyOfRange(h * hopSize, (h + 1) * hopSize)
                    result = filterBank.processHop(hop, hopSize)
                }
                return result[b]
            }

            val centerResp = measureResponseAt(f0)
            val lowerResp = measureResponseAt(lowerEdge)
            val upperResp = measureResponseAt(upperEdge)

            val lowerDrop = centerResp - lowerResp
            val upperDrop = centerResp - upperResp

            println("Band $b ($f0 Hz): Center=$centerResp dBFS, Lower($lowerEdge Hz) drop=$lowerDrop dB, Upper($upperEdge Hz) drop=$upperDrop dB")
            if (b == 30) {
                // At 48 kHz (Nyquist 24 kHz), Band 30 lower edge is anchored at -3.01 dB drop,
                // while upper nominal edge (22,449 Hz) rolls off to ~10.15 dB due to Nyquist compression.
                assertTrue("Band 30 lower edge drop ($lowerDrop dB) should be near 3.01 dB", lowerDrop in 2.8f..3.3f)
                assertTrue("Band 30 upper edge drop ($upperDrop dB) should be near 10.15 dB", upperDrop in 9.5f..11.0f)
            } else {
                // 4th-order cascade response at nominal band edge drops by ~3.01 dB (-3 dB half-power cutoff)
                // Near Nyquist (e.g. 16 kHz), bilinear warping causes slight asymmetric spreading (lower: 2.39 dB, upper: 3.76 dB).
                assertTrue("Band $b lower edge drop ($lowerDrop dB) should be near -3.01 dB (within 2.2 dB .. 3.5 dB)", lowerDrop in 2.2f..3.5f)
                assertTrue("Band $b upper edge drop ($upperDrop dB) should be near -3.01 dB (within 2.7 dB .. 4.5 dB)", upperDrop in 2.7f..4.5f)
            }
        }
    }

    @Test
    fun testNumericalStabilityLowFrequencyAndNearNyquist() {
        val filterBank = BiquadFilterBank(sampleRate = sampleRate)

        // 1. Extreme low-frequency tone: 5 Hz and DC offset (1000 hops = 21.3 seconds)
        val lowFreqTone = AudioMath.generateSineWave(5.0, sampleRate, hopSize, 1.0)
        filterBank.reset()
        for (h in 0 until 500) {
            val result = filterBank.processHop(lowFreqTone, hopSize)
            for (b in 0 until Iso31Bands.NUM_BANDS) {
                assertFalse("Band $b output must not be NaN at 5 Hz", result[b].isNaN())
                assertFalse("Band $b output must not be Infinite at 5 Hz", result[b].isInfinite())
            }
        }

        // 2. High-frequency near-Nyquist tone: 23,900 Hz (Fs = 48000 Hz)
        val nearNyquistTone = AudioMath.generateSineWave(23900.0, sampleRate, hopSize, 1.0)
        filterBank.reset()
        for (h in 0 until 500) {
            val result = filterBank.processHop(nearNyquistTone, hopSize)
            for (b in 0 until Iso31Bands.NUM_BANDS) {
                assertFalse("Band $b output must not be NaN near Nyquist", result[b].isNaN())
                assertFalse("Band $b output must not be Infinite near Nyquist", result[b].isInfinite())
            }
        }

        // 3. Unit impulse test: verify impulse response decays stably to silence without denormal stalls
        filterBank.reset()
        val impulseHop = ShortArray(hopSize) { if (it == 0) 32767 else 0 }
        val zeroHop = ShortArray(hopSize) { 0 }

        filterBank.processHop(impulseHop, hopSize)
        var finalResult = FloatArray(Iso31Bands.NUM_BANDS)
        for (h in 0 until 200) {
            finalResult = filterBank.processHop(zeroHop, hopSize)
        }
        for (b in 0 until Iso31Bands.NUM_BANDS) {
            assertTrue(
                "Filter state for band $b must stably decay to silence after impulse (was ${finalResult[b]} dBFS)",
                finalResult[b] <= -80.0f
            )
        }
    }

    @Test
    fun testMandatoryIndependentCodexValidation48kAnd44k() {
        val sampleRates = listOf(48000, 44100)

        for (fs in sampleRates) {
            val filterBank = BiquadFilterBank(sampleRate = fs)
            val allCoeffs = filterBank.getAllBandCoefficients()
            val nyquist = fs * 0.5
            val numPoints = 60000
            val df = nyquist / numPoints

            println("==========================================================================================")
            println(" MANDATORY CODEX VALIDATION: 31 BANDS AT Fs = $fs Hz (Nyquist = $nyquist Hz)")
            println("==========================================================================================")
            println(String.format(
                Locale.US,
                "%-4s | %-6s | %-8s | %-8s | %-8s | %-9s | %-9s | %-8s | %-8s",
                "Band", "f0", "Gain@f0", "Gain@f1", "Gain@f2", "fLow3dB", "fHigh3dB", "ENBW (Hz)", "AdjAtten"
            ))

            for (b in 0 until Iso31Bands.NUM_BANDS) {
                val f0 = Iso31Bands.CENTER_FREQUENCIES[b].toDouble()
                if (f0 >= nyquist) continue

                val f1 = f0 / Iso31Bands.OCTAVE_RATIO
                val f2 = f0 * Iso31Bands.OCTAVE_RATIO
                val coeff = allCoeffs[b]

                fun calcGain(freq: Double): Double {
                    val omega = 2.0 * Math.PI * freq / fs
                    val cosW = Math.cos(omega); val cos2W = Math.cos(2.0 * omega)
                    val sinW = Math.sin(omega); val sin2W = Math.sin(2.0 * omega)
                    val numMagSq = (coeff.b0 * (1.0 - cos2W)).let { it * it } + (coeff.b0 * sin2W).let { it * it }
                    val denMagSq = (1.0 + coeff.a1 * cosW + coeff.a2 * cos2W).let { it * it } + (-(coeff.a1 * sinW + coeff.a2 * sin2W)).let { it * it }
                    return 10.0 * Math.log10((numMagSq / denMagSq).let { it * it })
                }

                // 1. Center frequency gain must be identically 0.00 dB (0.000 ± 0.001 dB)
                val gainF0 = calcGain(f0)
                assertEquals("Band $b ($f0 Hz) center gain must be 0.000 dB at Fs=$fs", 0.0, gainF0, 0.001)

                // 2. Lower nominal band edge gain: nominal -3.01 dB (-3 dB half-power cutoff)
                val gainF1 = calcGain(f1)
                assertTrue(
                    "Band $b ($f0 Hz) lower edge ($f1 Hz) gain must be near -3.01 dB (was $gainF1 dB)",
                    gainF1 in -3.20..-2.10
                )

                // 3. Upper nominal band edge gain where valid below Nyquist
                val gainF2 = if (f2 < nyquist) calcGain(f2) else Double.NaN
                if (f2 < nyquist * 0.90) {
                    assertTrue(
                        "Band $b ($f0 Hz) upper edge ($f2 Hz) gain must be near -3.01 dB (was $gainF2 dB)",
                        gainF2 in -4.50..-2.80
                    )
                }

                // Explicit verification of independently validated reference values:
                if (fs == 48000) {
                    when (b) {
                        17 -> { // 1 kHz
                            assertEquals("1 kHz lower edge at 48k matches verified reference", -3.009, gainF1, 0.05)
                            assertEquals("1 kHz upper edge at 48k matches verified reference", -3.012, gainF2, 0.05)
                        }
                        29 -> { // 16 kHz
                            assertEquals("16 kHz lower edge at 48k matches verified reference", -2.389, gainF1, 0.05)
                            assertEquals("16 kHz upper edge at 48k matches verified reference", -3.762, gainF2, 0.05)
                        }
                        30 -> { // 20 kHz
                            assertEquals("20 kHz lower edge at 48k matches verified reference", -3.010, gainF1, 0.05)
                            assertEquals("20 kHz upper edge at 48k matches verified reference", -10.154, gainF2, 0.05)
                        }
                    }
                } else if (fs == 44100) {
                    when (b) {
                        29 -> { // 16 kHz
                            assertEquals("16 kHz lower edge at 44.1k matches verified reference", -2.186, gainF1, 0.05)
                            assertEquals("16 kHz upper edge at 44.1k matches verified reference", -4.081, gainF2, 0.05)
                        }
                        30 -> { // 20 kHz
                            assertEquals("20 kHz lower edge at 44.1k matches verified reference", -3.010, gainF1, 0.05)
                            assertTrue(
                                "20 kHz upper nominal edge at 44.1k (22,449 Hz) is above Nyquist (22,050 Hz) and must not be a valid digital frequency",
                                gainF2.isNaN()
                            )
                        }
                    }
                }

                // 4. Measure actual -3 dB cutoff boundaries
                var fLow3dB = Double.NaN
                for (step in 1..10000) {
                    val testF = f0 * (1.0 - step / 10000.0)
                    if (testF <= 0.1) break
                    if (calcGain(testF) <= -3.0103) { fLow3dB = testF; break }
                }
                var fHigh3dB = Double.NaN
                for (step in 1..20000) {
                    val testF = f0 * (1.0 + step / 20000.0)
                    if (testF >= nyquist) break
                    if (calcGain(testF) <= -3.0103) { fHigh3dB = testF; break }
                }

                // Lower -3 dB cutoff matches nominal f1 within 3.0% across the entire audio spectrum
                assertEquals("Band $b lower -3 dB cutoff must match nominal f1 within 3.0%", f1, fLow3dB, f1 * 0.030)

                // Upper -3 dB cutoff matches nominal f2 within 3.0% for bands below 0.90 * Nyquist
                if (f2 < nyquist * 0.90) {
                    assertEquals("Band $b upper -3 dB cutoff must match nominal f2 within 3.0%", f2, fHigh3dB, f2 * 0.030)
                }

                // 5. Compute white noise Equivalent Noise Bandwidth (ENBW)
                var wPower = 0.0
                for (i in 1 until numPoints) {
                    val f = i * df
                    val omega = 2.0 * Math.PI * f / fs
                    val cosW = Math.cos(omega); val cos2W = Math.cos(2.0 * omega)
                    val sinW = Math.sin(omega); val sin2W = Math.sin(2.0 * omega)
                    val numMagSq = (coeff.b0 * (1.0 - cos2W)).let { it * it } + (coeff.b0 * sin2W).let { it * it }
                    val denMagSq = (1.0 + coeff.a1 * cosW + coeff.a2 * cos2W).let { it * it } + (-(coeff.a1 * sinW + coeff.a2 * sin2W)).let { it * it }
                    wPower += (numMagSq / denMagSq).let { it * it } * df
                }
                assertTrue("ENBW must be positive and finite", wPower > 0.0 && !wPower.isInfinite())

                // Explicit ENBW bounds:
                // Mid-bands (f2 < nyquist * 0.85): normalized ENBW (wPower / f0) is ~0.282 for 4th-order cascade, bounded in [0.25, 0.31]
                if (f2 < nyquist * 0.85) {
                    val normalizedEnbw = wPower / f0
                    assertTrue(
                        "Band $b ($f0 Hz) normalized ENBW ($normalizedEnbw) must be within [0.25, 0.31]",
                        normalizedEnbw in 0.25..0.31
                    )
                } else {
                    // Near-Nyquist bands (16 kHz and 20 kHz): absolute ENBW bounded in [3000, 5000] Hz
                    assertTrue(
                        "Near-Nyquist band $b ($f0 Hz) ENBW ($wPower Hz) must be within [3000, 5000] Hz",
                        wPower in 3000.0..5000.0
                    )
                }

                // 6. Adjacent band rejection: at lower adjacent center (f0 * 2^(-1/3)), verifying >= 5.5 dB attenuation
                val adjLowerFreq = f0 / (Iso31Bands.OCTAVE_RATIO * Iso31Bands.OCTAVE_RATIO)
                val adjAtten = gainF0 - calcGain(adjLowerFreq)
                assertTrue(
                    "Band $b ($f0 Hz) adjacent band attenuation at $adjLowerFreq Hz must be >= 5.5 dB (was $adjAtten dB)",
                    adjAtten >= 5.5
                )

                println(String.format(
                    Locale.US,
                    "%-4d | %-6.0f | %-+8.3f | %-+8.3f | %-+8.3f | %-9.1f | %-9.1f | %-8.1f | %-8.2f dB",
                    b, f0, gainF0, gainF1, gainF2, fLow3dB, fHigh3dB, wPower, adjAtten
                ))
            }
        }
    }

    @Test
    fun testSampleRateReconfigurationDynamicSwitching() {
        val filterBank = BiquadFilterBank(sampleRate = 48000)

        // Run audio at 48 kHz
        val tone1k_48 = AudioMath.generateSineWave(1000.0, 48000, hopSize * 5, 1.0)
        var res48 = FloatArray(Iso31Bands.NUM_BANDS)
        for (h in 0 until 5) {
            val hop = tone1k_48.copyOfRange(h * hopSize, (h + 1) * hopSize)
            res48 = filterBank.processHop(hop, hopSize)
        }
        assertEquals("1 kHz at 48 kHz must peak in band 17", 17, res48.indices.maxByOrNull { res48[it] })
        assertEquals("1 kHz at 48 kHz center response must be -3.01 dBFS", -3.01f, res48[17], 0.35f)

        // Reconfigure to 44.1 kHz
        filterBank.updateConfiguration(newSampleRate = 44100)
        assertEquals("Sample rate must be updated to 44100", 44100, filterBank.sampleRate)

        val tone1k_44 = AudioMath.generateSineWave(1000.0, 44100, hopSize * 5, 1.0)
        var res44 = FloatArray(Iso31Bands.NUM_BANDS)
        for (h in 0 until 5) {
            val hop = tone1k_44.copyOfRange(h * hopSize, (h + 1) * hopSize)
            res44 = filterBank.processHop(hop, hopSize)
        }
        assertEquals("1 kHz at 44.1 kHz must peak in band 17", 17, res44.indices.maxByOrNull { res44[it] })
        assertEquals("1 kHz at 44.1 kHz center response must be -3.01 dBFS", -3.01f, res44[17], 0.35f)

        // Reconfigure back to 48 kHz
        filterBank.updateConfiguration(newSampleRate = 48000)
        assertEquals("Sample rate must be updated to 48000", 48000, filterBank.sampleRate)
        val coeff20k = filterBank.getBandCoefficients(30)
        assertTrue("b0 must be valid and positive at 48 kHz", coeff20k.b0 > 0.0)
    }
}
