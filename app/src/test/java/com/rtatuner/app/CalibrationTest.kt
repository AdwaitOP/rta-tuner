package com.rtatuner.app

import com.rtatuner.app.core.audio.AudioCaptureUiState
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.calibration.CalibratedSpectralFrame
import com.rtatuner.app.core.calibration.CalibrationCurveParser
import com.rtatuner.app.core.calibration.MicCalibrationCurve
import com.rtatuner.app.core.calibration.MicCalibrationPoint
import com.rtatuner.app.core.calibration.TargetCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt


class CalibrationTest {

    @Test
    fun testCalibrationParserWithValidFormats() {
        val rewSample = """
            # REW Calibrated Microphone File
            * Format: Frequency Hz, Amplitude dB
            10.0   -2.50
            20.0   -1.00
            "100.0"	0.20
            1000.0, 0.00
            10000.0  1.50
            20000.0  3.20
        """.trimIndent()

        val result = CalibrationCurveParser.parse("TestMic", rewSample)
        assertTrue("Parser should succeed on valid multi-format data", result.isSuccess)
        val curve = result.getOrNull()
        assertNotNull("Curve should not be null", curve)
        assertEquals("Name should match", "TestMic", curve!!.name)
        assertEquals("Should parse 6 points", 6, curve.points.size)
        assertEquals(10.0f, curve.points[0].freqHz, 0.001f)
        assertEquals(-2.50f, curve.points[0].dbOffset, 0.001f)
        assertEquals(100.0f, curve.points[2].freqHz, 0.001f)
        assertEquals(0.20f, curve.points[2].dbOffset, 0.001f)
        assertEquals(20000.0f, curve.points.last().freqHz, 0.001f)
        assertEquals(3.20f, curve.points.last().dbOffset, 0.001f)
    }

    @Test
    fun testCalibrationParserRejectsInvalidData() {
        val invalidSample = """
            # Only headers and comments
            // Nothing valid here
            * Just words
        """.trimIndent()

        val result = CalibrationCurveParser.parse("BadMic", invalidSample)
        assertTrue("Parser should fail when fewer than 2 points are found", result.isFailure)
    }

    @Test
    fun testLogarithmicFrequencyInterpolationAtGeometricMean() {
        // Points: 100 Hz -> 0.0 dB, 1000 Hz -> +10.0 dB
        val curve = MicCalibrationCurve(
            name = "TestLog",
            points = listOf(
                MicCalibrationPoint(100.0f, 0.0f),
                MicCalibrationPoint(1000.0f, 10.0f)
            )
        )

        // At geometric mean f = sqrt(100 * 1000) ~= 316.2277 Hz:
        // log10(316.2277 / 100) = log10(sqrt(10)) = 0.5
        // log10(1000 / 100) = 1.0
        // Expected offset = 0.0 + 0.5 * (10.0 - 0.0) = +5.0 dB
        val geoMeanHz = sqrt(100.0f * 1000.0f)
        val offset = curve.getOffsetAt(geoMeanHz)
        assertEquals("Geometric mean in log space should interpolate to exact mid-offset (5.0 dB)", 5.0f, offset, 0.01f)

        // Test clamping outside range
        assertEquals("Below min freq should clamp to first point", 0.0f, curve.getOffsetAt(20.0f), 0.001f)
        assertEquals("Above max freq should clamp to last point", 10.0f, curve.getOffsetAt(15000.0f), 0.001f)
    }

    @Test
    fun testTargetCurvePresets() {
        val flat = TargetCurve.FLAT
        assertEquals(0.0f, flat.getTargetDbAt(20.0f), 0.001f)
        assertEquals(0.0f, flat.getTargetDbAt(1000.0f), 0.001f)
        assertEquals(0.0f, flat.getTargetDbAt(20000.0f), 0.001f)

        val harman = TargetCurve.HARMAN_CAR
        assertEquals("+9 dB bass boost at 20 Hz", 9.0f, harman.getTargetDbAt(20.0f), 0.01f)
        assertEquals("+9 dB bass boost at 31.5 Hz", 9.0f, harman.getTargetDbAt(31.5f), 0.01f)
        assertEquals("Midrange target at 1000 Hz is 0 dB", 0.0f, harman.getTargetDbAt(1000.0f), 0.01f)
        assertEquals("Treble roll-off at 20000 Hz is -4 dB", -4.0f, harman.getTargetDbAt(20000.0f), 0.01f)

        val audioControl = TargetCurve.AUDIO_CONTROL_CAR
        assertEquals("+6 dB bass boost at 20 Hz", 6.0f, audioControl.getTargetDbAt(20.0f), 0.01f)
        assertEquals("Midrange target at 1000 Hz is 0 dB", 0.0f, audioControl.getTargetDbAt(1000.0f), 0.01f)
    }

    @Test
    fun testPowerDomainNoiseSubtractionHighSnr() {
        // High SNR (Signal - Noise >= 40 dB): Subtraction should have virtually zero effect on signal
        val signalDb = -20.0f
        val noiseDb = -60.0f
        val result = AudioMath.subtractNoiseFloor(signalDb, noiseDb, AudioMath.MIN_DB_FS)

        // Expected: 10 * log10(10^(-2) - 10^(-6)) = -20.00043 dB
        assertEquals("High SNR signal should be virtually unchanged by noise subtraction", -20.0f, result.correctedDb, 0.01f)
        assertFalse("High SNR band must not be flagged unreliable", result.isUnreliable)
        assertFalse("High SNR band must not be flagged below floor", result.isBelowFloor)
    }

    @Test
    fun testPowerDomainNoiseSubtraction6DbSnrBoundary() {
        // Exactly 6 dB SNR: Signal = -40 dB, Noise = -46 dB
        // Linear: 10^(-4) - 10^(-4.6) = 1e-4 - 0.25119e-4 = 0.7488e-4
        // Corrected dB = 10 * log10(0.7488e-4) = -41.256 dB
        val signalDb = -40.0f
        val noiseDb = -46.0f
        val result = AudioMath.subtractNoiseFloor(signalDb, noiseDb, AudioMath.MIN_DB_FS)

        assertEquals("6 dB SNR power subtraction should reduce measured level by ~1.25 dB", -41.26f, result.correctedDb, 0.05f)
        assertFalse(">= 6 dB SNR band should not be flagged unreliable", result.isUnreliable)
        assertFalse(">= 6 dB SNR band is not below floor", result.isBelowFloor)
    }

    @Test
    fun testPowerDomainNoiseSubtractionLowSnrFlagsUnreliable() {
        // Low SNR (< 6 dB): Signal = -40 dB, Noise = -43 dB (3 dB SNR)
        // Noise represents 50% of the power
        val signalDb = -40.0f
        val noiseDb = -43.0f
        val result = AudioMath.subtractNoiseFloor(signalDb, noiseDb, AudioMath.MIN_DB_FS)

        assertTrue("< 6 dB SNR band must be flagged unreliable", result.isUnreliable)
        assertFalse("Band is still above noise floor", result.isBelowFloor)
        assertTrue("Corrected dB must be lower than raw signal", result.correctedDb < signalDb)
    }

    @Test
    fun testPowerDomainNoiseSubtractionSignalBelowOrEqualNoiseDoesNotNaN() {
        // Signal equal to noise floor: 10^(S/10) - 10^(N/10) == 0
        // Must clamp to minFloorDb without producing NaN or -Infinity
        val resultEqual = AudioMath.subtractNoiseFloor(-50.0f, -50.0f, AudioMath.MIN_DB_FS)
        assertFalse("Result must not be NaN", resultEqual.correctedDb.isNaN())
        assertFalse("Result must not be Infinite", resultEqual.correctedDb.isInfinite())
        assertEquals("Equal signal/noise clamps to minFloorDb", AudioMath.MIN_DB_FS, resultEqual.correctedDb, 0.01f)
        assertTrue("Equal signal/noise must be flagged unreliable", resultEqual.isUnreliable)
        assertTrue("Equal signal/noise must be flagged below floor", resultEqual.isBelowFloor)

        // Signal below noise floor (e.g. fluctuation): linear difference is negative
        val resultBelow = AudioMath.subtractNoiseFloor(-55.0f, -50.0f, AudioMath.MIN_DB_FS)
        assertFalse("Result must not be NaN", resultBelow.correctedDb.isNaN())
        assertFalse("Result must not be Infinite", resultBelow.correctedDb.isInfinite())
        assertEquals("Sub-noise signal clamps to minFloorDb", AudioMath.MIN_DB_FS, resultBelow.correctedDb, 0.01f)
        assertTrue("Sub-noise signal must be flagged unreliable", resultBelow.isUnreliable)
        assertTrue("Sub-noise signal must be flagged below floor", resultBelow.isBelowFloor)
    }

    @Test
    fun testSplOffsetCalculationUsesRmsNotPeak() {
        // Generate a 1 kHz sine wave at full scale amplitude (48000 Hz, 2048 samples)
        val sineWave = AudioMath.generateSineWave(
            frequency = 1000.0,
            sampleRate = 48000,
            numSamples = 2048,
            amplitude = 1.0
        )
        val (rmsDb, peakDb) = AudioMath.computeRmsAndPeakDb(sineWave, sineWave.size)

        // For a full scale sine wave:
        // Peak = 0.0 dBFS, RMS ~= -3.01 dBFS (crest factor sqrt(2) = 3.01 dB)
        assertEquals("Full scale sine peak should be ~0 dBFS", 0.0f, peakDb, 0.15f)
        assertEquals("Full scale sine RMS should be ~ -3.01 dBFS", -3.01f, rmsDb, 0.2f)

        // External reference SPL meter reads 94.0 dBSPL.
        // Real SPL meters integrate acoustic energy (RMS), so the true calibration offset is:
        // offset_RMS = 94.0 - rmsDb = 94.0 - (-3.01) = 97.01 dB
        val targetSplDb = 94.0f
        val correctRmsOffset = targetSplDb - rmsDb
        val wrongPeakOffset = targetSplDb - peakDb

        // Verify the 3.01 dB difference between RMS and Peak offsets
        val crestFactorError = correctRmsOffset - wrongPeakOffset
        assertEquals("Crest factor discrepancy between RMS and Peak should be ~3.01 dB", 3.01f, crestFactorError, 0.25f)

        // Verify that calibrating with RMS ensures an identical RMS tone produces the exact reference SPL:
        val calibratedRmsSpl = rmsDb + correctRmsOffset
        assertEquals("Calibrated level using RMS offset must equal the 94 dBSPL reference", targetSplDb, calibratedRmsSpl, 0.01f)

        // If Peak was used, the resulting SPL reading would be 3 dB too low!
        val wrongCalibratedSpl = rmsDb + wrongPeakOffset
        assertTrue("Using Peak introduces systematic under-reading equal to crest factor", wrongCalibratedSpl < targetSplDb - 2.8f)
    }

    @Test
    fun testSplOffsetAddition() {
        // Measured RMS: -24.0 dBFS, target reference SPL: 85.0 dBSPL
        // Offset = 85.0 - (-24.0) = +109.0 dB
        val rawRmsDbFs = -24.0f
        val targetSplDb = 85.0f
        val offset = targetSplDb - rawRmsDbFs
        assertEquals(109.0f, offset, 0.001f)

        // Calibrated reading: raw RMS + offset
        val quietSignalRmsDbFs = -60.0f
        val quietSignalSpl = quietSignalRmsDbFs + offset
        assertEquals(49.0f, quietSignalSpl, 0.001f)

        val fullScaleSpl = 0.0f + offset
        assertEquals(109.0f, fullScaleSpl, 0.001f)
    }

    @Test
    fun testNanAndInfinityGuardsInAudioMath() {
        // linearToDbFs must safely clamp NaN / Infinity to floor / silence rather than propagating corrupt values
        val nanDb = AudioMath.linearToDbFs(Float.NaN)
        assertFalse("linearToDbFs(NaN) must not return NaN", nanDb.isNaN())
        assertEquals("linearToDbFs(NaN) must return MIN_DB_FS", AudioMath.MIN_DB_FS, nanDb, 0.01f)

        val posInfDb = AudioMath.linearToDbFs(Float.POSITIVE_INFINITY)
        assertFalse("linearToDbFs(+Inf) must not return NaN", posInfDb.isNaN())
        assertFalse("linearToDbFs(+Inf) must not return Infinity", posInfDb.isInfinite())
        assertEquals("linearToDbFs(+Inf) clamps to full scale 0 dBFS", 0.0f, posInfDb, 0.01f)

        val negInfDb = AudioMath.linearToDbFs(Float.NEGATIVE_INFINITY)
        assertEquals("linearToDbFs(-Inf) clamps to MIN_DB_FS", AudioMath.MIN_DB_FS, negInfDb, 0.01f)

        // subtractNoiseFloor must safely handle NaN / Infinity without producing NaN or Infinity
        val resNanSignal = AudioMath.subtractNoiseFloor(Float.NaN, -50.0f, AudioMath.MIN_DB_FS)
        assertFalse("subtractNoiseFloor with NaN signal must not produce NaN", resNanSignal.correctedDb.isNaN())
        assertEquals("subtractNoiseFloor with NaN signal must clamp to MIN_DB_FS", AudioMath.MIN_DB_FS, resNanSignal.correctedDb, 0.01f)
        assertTrue(resNanSignal.isUnreliable)

        val resNanNoise = AudioMath.subtractNoiseFloor(-20.0f, Float.NaN, AudioMath.MIN_DB_FS)
        assertFalse("subtractNoiseFloor with NaN noise must not produce NaN", resNanNoise.correctedDb.isNaN())
        assertEquals("subtractNoiseFloor with NaN noise must clamp to MIN_DB_FS", AudioMath.MIN_DB_FS, resNanNoise.correctedDb, 0.01f)

        val resPosInf = AudioMath.subtractNoiseFloor(Float.POSITIVE_INFINITY, -50.0f, AudioMath.MIN_DB_FS)
        assertFalse("subtractNoiseFloor with +Inf must not produce Infinity", resPosInf.correctedDb.isInfinite())
        assertEquals(AudioMath.MIN_DB_FS, resPosInf.correctedDb, 0.01f)
    }

    @Test
    fun testLogInterpolationGuardsAgainstZeroAndNegativeFrequency() {
        val curve = MicCalibrationCurve(
            name = "TestGuard",
            points = listOf(
                MicCalibrationPoint(20.0f, -1.0f),
                MicCalibrationPoint(1000.0f, 0.0f),
                MicCalibrationPoint(20000.0f, 2.0f)
            )
        )

        // DC bin (0 Hz) or negative frequencies must safely clamp to first point rather than evaluating log10(0) = -Inf / NaN
        val offsetZeroHz = curve.getOffsetAt(0.0f)
        assertFalse("0 Hz offset must not be NaN", offsetZeroHz.isNaN())
        assertFalse("0 Hz offset must not be Infinite", offsetZeroHz.isInfinite())
        assertEquals("0 Hz clamps to first point (-1.0 dB)", -1.0f, offsetZeroHz, 0.001f)

        val offsetNegHz = curve.getOffsetAt(-100.0f)
        assertFalse("-100 Hz offset must not be NaN", offsetNegHz.isNaN())
        assertEquals("Negative Hz clamps to first point (-1.0 dB)", -1.0f, offsetNegHz, 0.001f)

        val offsetNanHz = curve.getOffsetAt(Float.NaN)
        assertFalse("NaN Hz offset must not be NaN", offsetNanHz.isNaN())
        assertEquals("NaN Hz returns 0.0 dB safe default", 0.0f, offsetNanHz, 0.001f)
    }

    @Test
    fun testTargetCurveBaselineDoesNotClampToCeiling() {
        val harman = TargetCurve.HARMAN_CAR
        val targetBaselineDb = -20.0f
        val maxDb = 0.0f
        val minDb = -80.0f

        // In dBFS mode, target curve should be centered at -20 dBFS, NOT at 0 dBFS ceiling
        val bassTarget = targetBaselineDb + harman.getTargetDbAt(20.0f) // -20 + 9 = -11 dBFS
        val midTarget = targetBaselineDb + harman.getTargetDbAt(1000.0f) // -20 + 0 = -20 dBFS
        val trebleTarget = targetBaselineDb + harman.getTargetDbAt(20000.0f) // -20 - 4 = -24 dBFS

        assertTrue("Bass target (-11 dBFS) must be strictly below ceiling (0 dBFS)", bassTarget < maxDb)
        assertTrue("Bass target (-11 dBFS) must be strictly above floor (-80 dBFS)", bassTarget > minDb)
        assertEquals(-11.0f, bassTarget, 0.01f)
        assertEquals(-20.0f, midTarget, 0.01f)
        assertEquals(-24.0f, trebleTarget, 0.01f)

        // In SPL mode with +100 dB SPL offset (baseline = 80 dBSPL, range = 20..100 dBSPL)
        val splOffset = 100.0f
        val splBaseline = targetBaselineDb + splOffset // 80 dBSPL
        val splMaxDb = maxDb + splOffset // 100 dBSPL
        val splBassTarget = splBaseline + harman.getTargetDbAt(20.0f) // 80 + 9 = 89 dBSPL

        assertTrue("SPL bass target (89 dBSPL) must be strictly below SPL ceiling (100 dBSPL)", splBassTarget < splMaxDb)
        assertEquals(89.0f, splBassTarget, 0.01f)
    }

    @Test
    fun testCalibratedSpectralFrameResetSmoothingFlag() {
        val dummyBands = FloatArray(31) { -40.0f }
        val dummyRaw = FloatArray(1024) { -40.0f }
        val dummyUnreliable = BooleanArray(31) { false }

        val defaultFrame = CalibratedSpectralFrame(
            calibrated31Db = dummyBands,
            calibratedRawFftDb = dummyRaw,
            unreliableBands = dummyUnreliable,
            targetDeltas31Db = null,
            isSplMode = false,
            splOffsetDb = 0.0f
        )
        assertFalse("Default shouldResetSmoothing must be false", defaultFrame.shouldResetSmoothing)

        val resetFrame = defaultFrame.copy(shouldResetSmoothing = true)
        assertTrue("Copied frame should have shouldResetSmoothing = true", resetFrame.shouldResetSmoothing)
        assertNotEquals("Frames with different shouldResetSmoothing must not be equal", defaultFrame, resetFrame)
        assertNotEquals("Hash codes should differ when shouldResetSmoothing differs", defaultFrame.hashCode(), resetFrame.hashCode())

        val anotherResetFrame = defaultFrame.copy(shouldResetSmoothing = true)
        assertEquals("Identical frames with shouldResetSmoothing = true must be equal", resetFrame, anotherResetFrame)
        assertEquals(resetFrame.hashCode(), anotherResetFrame.hashCode())
    }

    @Test
    fun testAudioCaptureUiStateResetSmoothingFlag() {
        val defaultState = AudioCaptureUiState()
        assertFalse("Default shouldResetSmoothing must be false", defaultState.shouldResetSmoothing)

        val resetState = defaultState.copy(shouldResetSmoothing = true)
        assertTrue("Copied state should have shouldResetSmoothing = true", resetState.shouldResetSmoothing)
        assertNotEquals("States with different shouldResetSmoothing must not be equal", defaultState, resetState)
        assertNotEquals(defaultState.hashCode(), resetState.hashCode())

        val identicalResetState = defaultState.copy(shouldResetSmoothing = true)
        assertEquals(resetState, identicalResetState)
        assertEquals(resetState.hashCode(), identicalResetState.hashCode())
    }
}

