package com.rtatuner.app

import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.dsp.FftProcessor
import com.rtatuner.app.core.dsp.Iso31Bands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FftProcessorTest {

    @Test
    fun testPureSineWavePeakBinAccuracy() {
        val sampleRate = 48000
        val windowSize = 2048
        val testFreq = 1000.0 // 1 kHz pure sine wave

        val processor = FftProcessor(windowSize = windowSize, sampleRate = sampleRate)

        // Generate full-scale (1.0 amplitude) 1 kHz sine wave
        val sineWave = AudioMath.generateSineWave(
            frequency = testFreq,
            sampleRate = sampleRate,
            numSamples = windowSize,
            amplitude = 1.0
        )

        val result = processor.processFrame(sineWave)

        // Expected bin index for 1 kHz: 1000 / 23.4375 = ~42.67 -> Bin 43
        var maxBin = 0
        var maxBinDb = AudioMath.MIN_DB_FS
        for (k in 1 until result.rawFftDb.size) {
            if (result.rawFftDb[k] > maxBinDb) {
                maxBinDb = result.rawFftDb[k]
                maxBin = k
            }
        }

        val detectedFreq = maxBin * result.binResolutionHz

        // 1. Verify detected peak frequency matches 1000 Hz within 1 FFT bin (~23.4 Hz)
        assertEquals("Peak should be located at bin 43 (~1007.8 Hz)", 43, maxBin)
        assertEquals("Detected frequency should match 1000 Hz within bin resolution", testFreq, detectedFreq.toDouble(), 25.0)

        // 2. Off-bin tone (1000 Hz at 23.44 Hz resolution is at bin 42.67, causing ~0.63 dB Hann scalloping loss)
        // Verify peak magnitude reflects expected scalloping loss within ~0.7 dB of full scale RMS (-3.01 - 0.63 = -3.64 dBFS)
        assertEquals("Off-bin 1000 Hz sine peak magnitude with scalloping loss should be ~ -3.64 dBFS", -3.64f, maxBinDb, 0.15f)

        // 3. Verify exact bin-centered tone produces -3.01 dBFS (zero scalloping loss, full scale sine RMS)
        val exactBinFreq = 43 * result.binResolutionHz.toDouble() // 1007.8125 Hz
        val exactSine = AudioMath.generateSineWave(exactBinFreq, sampleRate, windowSize, 1.0)
        val exactResult = processor.processFrame(exactSine)
        val exactMaxDb = exactResult.rawFftDb.maxOrNull() ?: AudioMath.MIN_DB_FS
        assertEquals("Exact bin-centered sine tone peak magnitude must be -3.01 dBFS (RMS)", -3.01f, exactMaxDb, 0.1f)

        // 4. Verify 1/3-octave band smoothing:
        // 1000 Hz is ISO band 17
        val iso1000HzBandIndex = 17
        assertEquals("ISO band 17 center frequency must be 1000 Hz", 1000f, Iso31Bands.CENTER_FREQUENCIES[iso1000HzBandIndex])

        var maxBand = 0
        var maxBandDb = AudioMath.MIN_DB_FS
        for (b in 0 until result.octave31Db.size) {
            if (result.octave31Db[b] > maxBandDb) {
                maxBandDb = result.octave31Db[b]
                maxBand = b
            }
        }

        assertEquals("1/3-octave peak band must be the 1000 Hz band", iso1000HzBandIndex, maxBand)
        assertEquals("1000 Hz band integrated energy should be ~ -3.01 dBFS", -3.01f, maxBandDb, 0.5f)
    }

    @Test
    fun testLowAndHighFrequenciesInOctaveBands() {
        val sampleRate = 48000
        val windowSize = 2048
        val processor = FftProcessor(windowSize = windowSize, sampleRate = sampleRate)

        // Test 250 Hz (ISO band 11)
        val sine250 = AudioMath.generateSineWave(250.0, sampleRate, windowSize, 1.0)
        val result250 = processor.processFrame(sine250)
        val peakBand250 = result250.octave31Db.indices.maxByOrNull { result250.octave31Db[it] } ?: -1
        assertEquals("250 Hz should peak at ISO band 11", 11, peakBand250)

        // Test 4000 Hz (ISO band 23)
        val sine4000 = AudioMath.generateSineWave(4000.0, sampleRate, windowSize, 1.0)
        val result4000 = processor.processFrame(sine4000)
        val peakBand4000 = result4000.octave31Db.indices.maxByOrNull { result4000.octave31Db[it] } ?: -1
        assertEquals("4000 Hz should peak at ISO band 23", 23, peakBand4000)
    }

    @Test
    fun testSilenceProducesMinimumDbFloor() {
        val processor = FftProcessor(windowSize = 2048, sampleRate = 48000)
        val silence = ShortArray(2048) { 0 }
        val result = processor.processFrame(silence)

        // Every raw bin and every 31-octave band should clamp to MIN_DB_FS (-100 dBFS)
        for (b in result.octave31Db) {
            assertEquals("Silence in 1/3-octave band must be -100 dBFS", AudioMath.MIN_DB_FS, b, 0.01f)
        }
        for (k in result.rawFftDb) {
            assertEquals("Silence in raw FFT bin must be -100 dBFS", AudioMath.MIN_DB_FS, k, 0.01f)
        }
    }

    @Test
    fun testSelectableWindowSizes() {
        for (size in listOf(1024, 2048, 4096)) {
            val processor = FftProcessor(windowSize = size, sampleRate = 48000)
            val sine = AudioMath.generateSineWave(1000.0, 48000, size, 1.0)
            val result = processor.processFrame(sine)

            assertEquals("Raw bins count should be N/2 + 1", size / 2 + 1, result.rawFftDb.size)
            assertEquals("31 ISO bands must always be 31", Iso31Bands.NUM_BANDS, result.octave31Db.size)
            assertTrue("Execution time must be measured", result.executionTimeUs >= 0)
        }
    }

    @Test
    fun testOptionalOctaveBinningSkipsComputation() {
        val processor = FftProcessor(windowSize = 2048, sampleRate = 48000)
        val sine = AudioMath.generateSineWave(1000.0, 48000, 2048, 1.0)
        val result = processor.processFrame(sine, computeOctaveBands = false)

        assertEquals("Raw bins count must still be computed", 1025, result.rawFftDb.size)
        assertEquals("Octave bands must be empty when computeOctaveBands is false", 0, result.octave31Db.size)
    }
}
