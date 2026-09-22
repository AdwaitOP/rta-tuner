package com.rtatuner.app

import android.media.AudioRecord
import com.rtatuner.app.core.audio.AudioMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioMathTest {

    @Test
    fun testSilenceClampsToFloor() {
        val silence = ShortArray(2048) { 0 }
        val (rmsDb, peakDb) = AudioMath.computeRmsAndPeakDb(silence, silence.size)

        assertEquals("Silence RMS should clamp to MIN_DB_FS", AudioMath.MIN_DB_FS, rmsDb, 0.01f)
        assertEquals("Silence Peak should clamp to MIN_DB_FS", AudioMath.MIN_DB_FS, peakDb, 0.01f)
        assertFalse("Silence should not clip", AudioMath.isClipping(silence, silence.size))
    }

    @Test
    fun testFullScaleSineWaveCalculations() {
        // 1 kHz sine wave at 48000 Hz, 2048 samples, 100% full scale amplitude
        val sineWave = AudioMath.generateSineWave(
            frequency = 1000.0,
            sampleRate = 48000,
            numSamples = 2048,
            amplitude = 1.0
        )

        val (rmsDb, peakDb) = AudioMath.computeRmsAndPeakDb(sineWave, sineWave.size)

        // For a full scale sine wave:
        // Peak = 0 dBFS
        // RMS = -3.01 dBFS (1/sqrt(2) of peak)
        assertEquals("Full scale sine peak should be ~0 dBFS", 0.0f, peakDb, 0.15f)
        assertEquals("Full scale sine RMS should be ~ -3.01 dBFS", -3.01f, rmsDb, 0.2f)
        assertTrue("Full scale sine hits clipping boundary", AudioMath.isClipping(sineWave, sineWave.size))
    }

    @Test
    fun testHalfScaleSineWaveCalculations() {
        // 1 kHz sine wave at 50% amplitude (-6 dBFS peak)
        val sineWave = AudioMath.generateSineWave(
            frequency = 1000.0,
            sampleRate = 48000,
            numSamples = 2048,
            amplitude = 0.5
        )

        val (rmsDb, peakDb) = AudioMath.computeRmsAndPeakDb(sineWave, sineWave.size)

        // 50% amplitude: Peak = 20*log10(0.5) = -6.02 dBFS, RMS = -6.02 - 3.01 = -9.03 dBFS
        assertEquals("Half scale sine peak should be ~ -6.02 dBFS", -6.02f, peakDb, 0.15f)
        assertEquals("Half scale sine RMS should be ~ -9.03 dBFS", -9.03f, rmsDb, 0.25f)
        assertFalse("Half scale sine must not clip", AudioMath.isClipping(sineWave, sineWave.size))
    }

    @Test
    fun testClippingDetectionThreshold() {
        val buffer = ShortArray(10) { 10000 }
        assertFalse(AudioMath.isClipping(buffer, buffer.size))

        // Set one sample at clipping limit
        buffer[5] = 32600
        assertTrue(AudioMath.isClipping(buffer, buffer.size))
    }

    @Test
    fun testAudioRecordErrorMapping() {
        val deadObjStr = AudioMath.mapAudioRecordError(AudioRecord.ERROR_DEAD_OBJECT)
        assertTrue(deadObjStr.contains("ERROR_DEAD_OBJECT"))

        val invalidOpStr = AudioMath.mapAudioRecordError(AudioRecord.ERROR_INVALID_OPERATION)
        assertTrue(invalidOpStr.contains("ERROR_INVALID_OPERATION"))

        val badValStr = AudioMath.mapAudioRecordError(AudioRecord.ERROR_BAD_VALUE)
        assertTrue(badValStr.contains("ERROR_BAD_VALUE"))

        val genericErrStr = AudioMath.mapAudioRecordError(AudioRecord.ERROR)
        assertTrue(genericErrStr.contains("ERROR"))
    }
}
