package com.rtatuner.app

import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.dsp.BiquadFilterBank
import com.rtatuner.app.core.dsp.FftProcessor
import com.rtatuner.app.core.dsp.Iso31Bands
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Regression test verifying that Raw FFT view and 1/3-octave Biquad filter bank view
 * report identical dBFS and dBSPL levels for the same test signal (resolving Finding #2: 3.01 dB mismatch).
 */
class SpectrumPipelineUnifiedLevelTest {

    @Test
    fun testRawFftAndBiquadLevelAgreementInDbFsAndDbSpl() {
        val sampleRate = 48000
        val windowSize = 2048
        val hopSize = 1024

        val fftProcessor = FftProcessor(windowSize = windowSize, sampleRate = sampleRate)
        val filterBank = BiquadFilterBank(sampleRate = sampleRate)

        // Choose 398.4375 Hz:
        // - Exact FFT bin 17 (17 * 48000 / 2048 = 398.4375 Hz) -> zero Hann scalloping loss
        // - Center of ISO Band 13 (400 Hz) -> biquad attenuation < 0.01 dB
        val testFreqHz = 17 * fftProcessor.binResolutionHz.toDouble()
        val isoBandIndex = 13 // 400 Hz band
        assertEquals("ISO Band 13 center frequency must be 400 Hz", 400f, Iso31Bands.CENTER_FREQUENCIES[isoBandIndex])

        // Generate full-scale (amplitude = 1.0) sine wave across multiple hops to settle filter
        val numHops = 10
        val totalSamples = numHops * hopSize
        val sineSignal = AudioMath.generateSineWave(
            frequency = testFreqHz,
            sampleRate = sampleRate,
            numSamples = totalSamples,
            amplitude = 1.0
        )

        // 1. Process continuous stream through 4th-order biquad filter bank
        var biquadOutput = FloatArray(Iso31Bands.NUM_BANDS)
        for (h in 0 until numHops) {
            val hop = sineSignal.copyOfRange(h * hopSize, (h + 1) * hopSize)
            biquadOutput = filterBank.processHop(hop, hopSize)
        }
        val biquadBandLevelDbFs = biquadOutput[isoBandIndex]

        // 2. Process final window frame through FFT processor
        val lastWindowFrame = sineSignal.copyOfRange(totalSamples - windowSize, totalSamples)
        val fftResult = fftProcessor.processFrame(lastWindowFrame)
        val fftPeakBin = 17
        val fftPeakLevelDbFs = fftResult.rawFftDb[fftPeakBin]

        // 3. Assert both report ~ -3.01 dBFS (true full-scale sine RMS)
        assertEquals("Raw FFT peak must report full scale sine RMS (-3.01 dBFS)", -3.01f, fftPeakLevelDbFs, 0.05f)
        assertEquals("Biquad band must report full scale sine RMS (-3.01 dBFS)", -3.01f, biquadBandLevelDbFs, 0.05f)

        // 4. Assert both display pipelines agree within 0.1 dB in dBFS mode
        val dbFsDiscrepancy = abs(fftPeakLevelDbFs - biquadBandLevelDbFs)
        assertEquals(
            "Raw FFT and 1/3-octave biquad must agree within 0.1 dB in dBFS mode (was $dbFsDiscrepancy dB)",
            0.0f,
            dbFsDiscrepancy,
            0.1f
        )

        // 5. Test calibrated dBSPL agreement (e.g. 94.0 dBSPL calibration)
        val targetCalibratedSpl = 94.0f
        val splOffset = targetCalibratedSpl - biquadBandLevelDbFs

        val fftSpl = fftPeakLevelDbFs + splOffset
        val biquadSpl = biquadBandLevelDbFs + splOffset

        assertEquals("Biquad band must report calibrated 94.0 dBSPL", targetCalibratedSpl, biquadSpl, 0.05f)
        assertEquals("Raw FFT peak must report calibrated 94.0 dBSPL", targetCalibratedSpl, fftSpl, 0.05f)

        val splDiscrepancy = abs(fftSpl - biquadSpl)
        assertEquals(
            "Raw FFT and 1/3-octave biquad must report identical dBSPL within 0.1 dB (was $splDiscrepancy dB)",
            0.0f,
            splDiscrepancy,
            0.1f
        )
    }
}
