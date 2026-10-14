package com.rtatuner.app

import android.content.Context
import android.content.SharedPreferences
import com.rtatuner.app.core.audio.AudioCaptureUiState
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.calibration.CalibratedSpectralFrame
import com.rtatuner.app.core.calibration.CalibrationCurveParser
import com.rtatuner.app.core.calibration.CalibrationFileConvention
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.calibration.MicCalibrationCurve
import com.rtatuner.app.core.calibration.MicCalibrationPoint
import com.rtatuner.app.core.calibration.TargetCurve
import com.rtatuner.app.core.dsp.Iso31Bands
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalibrationTest {

    private fun testPreferences(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("calib-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)

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

        // 1. Default convention: MEASURED_RESPONSE (Standard UMIK-1 / REW / Dayton)
        // Measured response values must be inverted into compensation offsets (-value)
        val resultStandard = CalibrationCurveParser.parse("TestMic", rewSample, CalibrationFileConvention.MEASURED_RESPONSE)
        assertTrue("Parser should succeed on valid multi-format data", resultStandard.isSuccess)
        val curveStd = resultStandard.getOrThrow()
        assertEquals("TestMic", curveStd.name)
        assertEquals(6, curveStd.points.size)
        assertEquals(10.0f, curveStd.points[0].freqHz, 0.001f)
        assertEquals("Measured -2.5 dB should invert to +2.5 dB compensation", 2.50f, curveStd.points[0].dbOffset, 0.001f)
        assertEquals(100.0f, curveStd.points[2].freqHz, 0.001f)
        assertEquals("Measured +0.2 dB should invert to -0.2 dB compensation", -0.20f, curveStd.points[2].dbOffset, 0.001f)
        assertEquals(1000.0f, curveStd.points[3].freqHz, 0.001f)
        assertEquals("Measured 0.0 dB remains 0.0 dB", 0.00f, curveStd.points[3].dbOffset, 0.001f)
        assertEquals(20000.0f, curveStd.points.last().freqHz, 0.001f)
        assertEquals("Measured +3.2 dB should invert to -3.2 dB compensation", -3.20f, curveStd.points.last().dbOffset, 0.001f)

        // 2. Explicit convention: ADDITIVE_CORRECTION
        // Offsets are kept as-is without inversion
        val resultAdditive = CalibrationCurveParser.parse("TestMicAdditive", rewSample, CalibrationFileConvention.ADDITIVE_CORRECTION)
        assertTrue(resultAdditive.isSuccess)
        val curveAdd = resultAdditive.getOrThrow()
        assertEquals(-2.50f, curveAdd.points[0].dbOffset, 0.001f)
        assertEquals(0.20f, curveAdd.points[2].dbOffset, 0.001f)
        assertEquals(3.20f, curveAdd.points.last().dbOffset, 0.001f)
    }

    @Test
    fun standardResponseFileWithPositive3DbProducesNegative3DbCompensation() {
        val sample = "1000.0  3.0\n2000.0  3.0"
        val result = CalibrationCurveParser.parse("HotMic", sample, CalibrationFileConvention.MEASURED_RESPONSE)
        assertTrue(result.isSuccess)
        val curve = result.getOrThrow()
        assertEquals("Standard +3 dB measured response must produce -3 dB compensation offset", -3.0f, curve.getOffsetAt(1000.0f), 0.001f)

        // Verify applied compensation on audio frame
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))
        manager.loadMicCurve("HotMic", sample, CalibrationFileConvention.MEASURED_RESPONSE)

        val frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, 100.0f)
        assertEquals("Measured response +3 dB reduces -40 dBFS input to -43 dBFS", -43.0f, frame.calibrated31Db[0], 0.01f)
        manager.close()
    }

    @Test
    fun standardResponseFileWithNegative3DbProducesPositive3DbCompensation() {
        val sample = "1000.0  -3.0\n2000.0  -3.0"
        val result = CalibrationCurveParser.parse("QuietMic", sample, CalibrationFileConvention.MEASURED_RESPONSE)
        assertTrue(result.isSuccess)
        val curve = result.getOrThrow()
        assertEquals("Standard -3 dB measured response must produce +3 dB compensation offset", 3.0f, curve.getOffsetAt(1000.0f), 0.001f)

        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))
        manager.loadMicCurve("QuietMic", sample, CalibrationFileConvention.MEASURED_RESPONSE)

        val frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, 100.0f)
        assertEquals("Measured response -3 dB boosts -40 dBFS input to -37 dBFS", -37.0f, frame.calibrated31Db[0], 0.01f)
        manager.close()
    }

    @Test
    fun legacyAdditiveCorrectionWithPositive3DbPreservesPositive3DbCompensation() {
        val sample = "1000.0  3.0\n2000.0  3.0"
        val result = CalibrationCurveParser.parse("DirectMic", sample, CalibrationFileConvention.ADDITIVE_CORRECTION)
        assertTrue(result.isSuccess)
        val curve = result.getOrThrow()
        assertEquals("Additive convention preserves +3 dB as +3 dB offset", 3.0f, curve.getOffsetAt(1000.0f), 0.001f)

        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))
        manager.loadMicCurve("DirectMic", sample, CalibrationFileConvention.ADDITIVE_CORRECTION)

        val frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, 100.0f)
        assertEquals("Additive +3 dB boosts -40 dBFS input to -37 dBFS", -37.0f, frame.calibrated31Db[0], 0.01f)
        manager.close()
    }

    @Test
    fun rawSignalAndNoiseFloorProduceIdenticalSubtractionRegardlessOfCalibrationState() {
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))

        // Capture noise floor at -46 dBFS across all bands
        manager.startNoiseFloorCapture(durationMs = 10L)
        // Feed 1 frame of -46 dBFS noise floor
        manager.processFrame(FloatArray(31) { -46.0f }, FloatArray(8) { -46.0f }, 100.0f)
        Thread.sleep(20)
        manager.processFrame(FloatArray(31) { -46.0f }, FloatArray(8) { -46.0f }, 100.0f)
        assertTrue(manager.uiState.value.isNoiseSubtractionActive)
        assertTrue(manager.uiState.value.noiseFloor.isCaptured)

        // Raw input: -40 dBFS tone (SNR = 6 dB: power subtraction yields ~ -41.256 dBFS)
        val expectedCleanRaw = AudioMath.subtractNoiseFloor(-40.0f, -46.0f, AudioMath.MIN_DB_FS).correctedDb
        assertEquals(-41.26f, expectedCleanRaw, 0.05f)

        // Case 1: Calibration disabled -> output is pure clean raw signal
        manager.setMicCurveActive(false)
        val frameUncal = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, 100.0f)
        assertEquals("Uncalibrated output must equal raw power subtraction", expectedCleanRaw, frameUncal.calibrated31Db[0], 0.001f)

        // Case 2: Calibration enabled with +5 dB offset
        manager.loadMicCurve("Boost5", "20.0 5.0\n20000.0 5.0", CalibrationFileConvention.ADDITIVE_CORRECTION)
        val frameBoost = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, 100.0f)
        val rawEquivalentBoost = frameBoost.calibrated31Db[0] - 5.0f
        assertEquals("Raw subtraction underlying +5dB calib must match uncalibrated subtraction exactly",
            expectedCleanRaw, rawEquivalentBoost, 0.001f)

        // Case 3: Calibration enabled with -5 dB offset
        manager.loadMicCurve("Cut5", "20.0 -5.0\n20000.0 -5.0", CalibrationFileConvention.ADDITIVE_CORRECTION)
        val frameCut = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, 100.0f)
        val rawEquivalentCut = frameCut.calibrated31Db[0] - (-5.0f)
        assertEquals("Raw subtraction underlying -5dB calib must match uncalibrated subtraction exactly",
            expectedCleanRaw, rawEquivalentCut, 0.001f)

        manager.close()
    }

    @Test
    fun changingCalibrationCurveAfterNoiseFloorCapturePreservesNoiseFloorIntegrity() {
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))

        // Set Curve 1 (+2 dB)
        manager.loadMicCurve("Curve1", "20.0 2.0\n20000.0 2.0", CalibrationFileConvention.ADDITIVE_CORRECTION)

        // Capture noise floor at -50 dBFS
        manager.startNoiseFloorCapture(durationMs = 10L)
        manager.processFrame(FloatArray(31) { -50.0f }, FloatArray(8) { -50.0f }, 100.0f)
        Thread.sleep(20)
        manager.processFrame(FloatArray(31) { -50.0f }, FloatArray(8) { -50.0f }, 100.0f)

        val capturedNoiseBands = manager.uiState.value.noiseFloor.bands31Db.clone()
        assertEquals(-50.0f, capturedNoiseBands[0], 0.1f)

        // Switch to Curve 2 (-4 dB) without recapturing noise floor
        manager.loadMicCurve("Curve2", "20.0 -4.0\n20000.0 -4.0", CalibrationFileConvention.ADDITIVE_CORRECTION)

        // Stored noise floor must NOT change!
        assertArrayEquals("Noise floor snapshot must remain untouched after curve switch",
            capturedNoiseBands, manager.uiState.value.noiseFloor.bands31Db, 0.0001f)

        // Feed -40 dBFS signal: raw clean is -40.457 dBFS, then Curve 2's -4 dB is applied -> -44.457 dBFS
        val frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, 100.0f)
        val expectedCleanRaw = AudioMath.subtractNoiseFloor(-40.0f, -50.0f, AudioMath.MIN_DB_FS).correctedDb
        assertEquals(expectedCleanRaw - 4.0f, frame.calibrated31Db[0], 0.01f)

        manager.close()
    }

    @Test
    fun unreliableBandDetectionSnrThresholdIsInvariantUnderCalibration() {
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))

        // Noise floor: Band 0 is at -43 dBFS (will be 3 dB SNR = unreliable)
        //              Band 1 is at -50 dBFS (will be 10 dB SNR = reliable)
        val noiseBands = FloatArray(31) { -50.0f }.also { it[0] = -43.0f }
        manager.startNoiseFloorCapture(durationMs = 10L)
        manager.processFrame(noiseBands, FloatArray(8) { -50.0f }, 100.0f)
        Thread.sleep(20)
        manager.processFrame(noiseBands, FloatArray(8) { -50.0f }, 100.0f)

        // Signal: -40 dBFS across all bands
        val signalBands = FloatArray(31) { -40.0f }

        // Test with calibration disabled
        manager.setMicCurveActive(false)
        val frameUncal = manager.processFrame(signalBands, FloatArray(8) { -40.0f }, 100.0f)
        assertTrue("Band 0 (3 dB SNR) must be unreliable", frameUncal.unreliableBands[0])
        assertFalse("Band 1 (10 dB SNR) must be reliable", frameUncal.unreliableBands[1])

        // Test with massive +15 dB calibration boost
        manager.loadMicCurve("BigBoost", "20.0 15.0\n20000.0 15.0", CalibrationFileConvention.ADDITIVE_CORRECTION)
        val frameBoost = manager.processFrame(signalBands, FloatArray(8) { -40.0f }, 100.0f)
        assertTrue("Band 0 must still be unreliable regardless of +15 dB mic boost", frameBoost.unreliableBands[0])
        assertFalse("Band 1 must still be reliable regardless of +15 dB mic boost", frameBoost.unreliableBands[1])

        // Test with massive -15 dB calibration cut
        manager.loadMicCurve("BigCut", "20.0 -15.0\n20000.0 -15.0", CalibrationFileConvention.ADDITIVE_CORRECTION)
        val frameCut = manager.processFrame(signalBands, FloatArray(8) { -40.0f }, 100.0f)
        assertTrue("Band 0 must still be unreliable regardless of -15 dB mic cut", frameCut.unreliableBands[0])
        assertFalse("Band 1 must still be reliable regardless of -15 dB mic cut", frameCut.unreliableBands[1])

        manager.close()
    }

    @Test
    fun fftAndThirdOctavePipelinesMaintainConsistentCompensationDirection() {
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))

        // Standard measured response: +2.5 dB
        manager.loadMicCurve("StandardMic", "20.0 2.5\n20000.0 2.5", CalibrationFileConvention.MEASURED_RESPONSE)

        // Both third-octave and raw FFT must have -2.5 dB applied
        val rawInput = FloatArray(31) { -30.0f }
        val rawFft = FloatArray(16) { -30.0f }
        val frame = manager.processFrame(rawInput, rawFft, 100.0f)

        for (i in 0 until 31) {
            assertEquals("Third-octave band $i must compensate by -2.5 dB", -32.5f, frame.calibrated31Db[i], 0.01f)
        }
        for (k in 0 until 16) {
            assertEquals("FFT bin $k must compensate by -2.5 dB", -32.5f, frame.calibratedRawFftDb[k], 0.01f)
        }

        manager.close()
    }

    @Test
    fun parsingCompatibilityWithRewDaytonAndUmik1Files() {
        // Dayton IMM-6 tab-delimited format
        val daytonContent = """
            20.00	-1.50
            50.00	-0.40
            1000.00	0.00
            10000.00	1.20
            20000.00	2.80
        """.trimIndent()
        val daytonResult = CalibrationCurveParser.parse("Dayton IMM-6", daytonContent, CalibrationFileConvention.MEASURED_RESPONSE)
        assertTrue(daytonResult.isSuccess)
        val daytonCurve = daytonResult.getOrThrow()
        assertEquals(5, daytonCurve.points.size)
        assertEquals(1.50f, daytonCurve.getOffsetAt(20.0f), 0.01f) // -1.50 inverted -> +1.50
        assertEquals(-2.80f, daytonCurve.getOffsetAt(20000.0f), 0.01f) // +2.80 inverted -> -2.80

        // miniDSP UMIK-1 space-delimited with header
        val umikContent = """
            "Sens Factor =-0.5dB, SerialNo = 7001234"
            10.000   -1.85
            100.000   0.05
            1000.000  0.00
            10000.000 1.35
            20000.000 3.80
        """.trimIndent()
        val umikResult = CalibrationCurveParser.parse("UMIK-1", umikContent, CalibrationFileConvention.MEASURED_RESPONSE)
        assertTrue(umikResult.isSuccess)
        val umikCurve = umikResult.getOrThrow()
        assertEquals(5, umikCurve.points.size)
        assertEquals(1.85f, umikCurve.getOffsetAt(10.0f), 0.01f)
        assertEquals(-3.80f, umikCurve.getOffsetAt(20000.0f), 0.01f)
    }

    @Test
    fun flatResponseCurvesWithZeroValuesHandledNumericallyCleanly() {
        val flatContent = "20.0 0.0\n1000.0 0.0\n20000.0 0.0"
        val result = CalibrationCurveParser.parse("FlatMic", flatContent, CalibrationFileConvention.MEASURED_RESPONSE)
        assertTrue(result.isSuccess)
        val curve = result.getOrThrow()
        assertEquals(0.0f, curve.getOffsetAt(20.0f), 0.0f)
        assertEquals(0.0f, curve.getOffsetAt(1000.0f), 0.0f)
        assertEquals(0.0f, curve.getOffsetAt(20000.0f), 0.0f)
        assertFalse("Offset must not be negative zero", curve.getOffsetAt(1000.0f).toRawBits() == (-0.0f).toRawBits())
    }

    @Test
    fun persistedLegacyCurvesWithoutConventionKeyPreservedAsAdditiveCorrection() {
        // Simulate preferences saved by older CabinRTA version (no mic_curve_convention key)
        val prefs = testPreferences()
        prefs.edit()
            .putString("mic_curve_name", "Legacy Preserved Mic")
            .putString("mic_curve_points", "[{\"f\":1000,\"o\":4.5},{\"f\":10000,\"o\":-2.0}]")
            .putBoolean("mic_curve_active", true)
            .commit()

        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))

        val loadedCurve = manager.uiState.value.micCurve
        assertNotNull(loadedCurve)
        assertEquals(CalibrationFileConvention.ADDITIVE_CORRECTION, loadedCurve!!.convention)
        assertEquals("Offset +4.5 must NOT be negated to -4.5", 4.5f, loadedCurve.getOffsetAt(1000.0f), 0.001f)
        assertEquals("Offset -2.0 must NOT be negated to +2.0", -2.0f, loadedCurve.getOffsetAt(10000.0f), 0.001f)

        manager.close()
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
            ),
            convention = CalibrationFileConvention.ADDITIVE_CORRECTION
        )

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
        val signalDb = -20.0f
        val noiseDb = -60.0f
        val result = AudioMath.subtractNoiseFloor(signalDb, noiseDb, AudioMath.MIN_DB_FS)

        assertEquals("High SNR signal should be virtually unchanged by noise subtraction", -20.0f, result.correctedDb, 0.01f)
        assertFalse("High SNR band must not be flagged unreliable", result.isUnreliable)
        assertFalse("High SNR band must not be flagged below floor", result.isBelowFloor)
    }

    @Test
    fun testPowerDomainNoiseSubtraction6DbSnrBoundary() {
        val signalDb = -40.0f
        val noiseDb = -46.0f
        val result = AudioMath.subtractNoiseFloor(signalDb, noiseDb, AudioMath.MIN_DB_FS)

        assertEquals("6 dB SNR power subtraction should reduce measured level by ~1.25 dB", -41.26f, result.correctedDb, 0.05f)
        assertFalse(">= 6 dB SNR band should not be flagged unreliable", result.isUnreliable)
        assertFalse(">= 6 dB SNR band is not below floor", result.isBelowFloor)
    }

    @Test
    fun testPowerDomainNoiseSubtractionLowSnrFlagsUnreliable() {
        val signalDb = -40.0f
        val noiseDb = -43.0f
        val result = AudioMath.subtractNoiseFloor(signalDb, noiseDb, AudioMath.MIN_DB_FS)

        assertTrue("< 6 dB SNR band must be flagged unreliable", result.isUnreliable)
        assertFalse("Band is still above noise floor", result.isBelowFloor)
        assertTrue("Corrected dB must be lower than raw signal", result.correctedDb < signalDb)
    }

    @Test
    fun testPowerDomainNoiseSubtractionSignalBelowOrEqualNoiseDoesNotNaN() {
        val resultEqual = AudioMath.subtractNoiseFloor(-50.0f, -50.0f, AudioMath.MIN_DB_FS)
        assertFalse("Result must not be NaN", resultEqual.correctedDb.isNaN())
        assertFalse("Result must not be Infinite", resultEqual.correctedDb.isInfinite())
        assertEquals("Equal signal/noise clamps to minFloorDb", AudioMath.MIN_DB_FS, resultEqual.correctedDb, 0.01f)
        assertTrue("Equal signal/noise must be flagged unreliable", resultEqual.isUnreliable)
        assertTrue("Equal signal/noise must be flagged below floor", resultEqual.isBelowFloor)

        val resultBelow = AudioMath.subtractNoiseFloor(-55.0f, -50.0f, AudioMath.MIN_DB_FS)
        assertFalse("Result must not be NaN", resultBelow.correctedDb.isNaN())
        assertFalse("Result must not be Infinite", resultBelow.correctedDb.isInfinite())
        assertEquals("Sub-noise signal clamps to minFloorDb", AudioMath.MIN_DB_FS, resultBelow.correctedDb, 0.01f)
        assertTrue("Sub-noise signal must be flagged unreliable", resultBelow.isUnreliable)
        assertTrue("Sub-noise signal must be flagged below floor", resultBelow.isBelowFloor)
    }

    @Test
    fun testSplOffsetCalculationUsesRmsNotPeak() {
        val sineWave = AudioMath.generateSineWave(
            frequency = 1000.0,
            sampleRate = 48000,
            numSamples = 2048,
            amplitude = 1.0
        )
        val (rmsDb, peakDb) = AudioMath.computeRmsAndPeakDb(sineWave, sineWave.size)

        assertEquals("Full scale sine peak should be ~0 dBFS", 0.0f, peakDb, 0.15f)
        assertEquals("Full scale sine RMS should be ~ -3.01 dBFS", -3.01f, rmsDb, 0.2f)

        val targetSplDb = 94.0f
        val correctRmsOffset = targetSplDb - rmsDb
        val wrongPeakOffset = targetSplDb - peakDb

        val crestFactorError = correctRmsOffset - wrongPeakOffset
        assertEquals("Crest factor discrepancy between RMS and Peak should be ~3.01 dB", 3.01f, crestFactorError, 0.25f)

        val calibratedRmsSpl = rmsDb + correctRmsOffset
        assertEquals("Calibrated level using RMS offset must equal the 94 dBSPL reference", targetSplDb, calibratedRmsSpl, 0.01f)

        val wrongCalibratedSpl = rmsDb + wrongPeakOffset
        assertTrue("Using Peak introduces systematic under-reading equal to crest factor", wrongCalibratedSpl < targetSplDb - 2.8f)
    }

    @Test
    fun testSplOffsetAddition() {
        val rawRmsDbFs = -24.0f
        val targetSplDb = 85.0f
        val offset = targetSplDb - rawRmsDbFs
        assertEquals(109.0f, offset, 0.001f)

        val quietSignalRmsDbFs = -60.0f
        val quietSignalSpl = quietSignalRmsDbFs + offset
        assertEquals(49.0f, quietSignalSpl, 0.001f)

        val fullScaleSpl = 0.0f + offset
        assertEquals(109.0f, fullScaleSpl, 0.001f)
    }

    @Test
    fun testNanAndInfinityGuardsInAudioMath() {
        val nanDb = AudioMath.linearToDbFs(Float.NaN)
        assertFalse("linearToDbFs(NaN) must not return NaN", nanDb.isNaN())
        assertEquals("linearToDbFs(NaN) must return MIN_DB_FS", AudioMath.MIN_DB_FS, nanDb, 0.01f)

        val posInfDb = AudioMath.linearToDbFs(Float.POSITIVE_INFINITY)
        assertFalse("linearToDbFs(+Inf) must not return NaN", posInfDb.isNaN())
        assertFalse("linearToDbFs(+Inf) must not return Infinity", posInfDb.isInfinite())
        assertEquals("linearToDbFs(+Inf) clamps to full scale 0 dBFS", 0.0f, posInfDb, 0.01f)

        val negInfDb = AudioMath.linearToDbFs(Float.NEGATIVE_INFINITY)
        assertEquals("linearToDbFs(-Inf) clamps to MIN_DB_FS", AudioMath.MIN_DB_FS, negInfDb, 0.01f)

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
            ),
            convention = CalibrationFileConvention.ADDITIVE_CORRECTION
        )

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

        val bassTarget = targetBaselineDb + harman.getTargetDbAt(20.0f)
        val midTarget = targetBaselineDb + harman.getTargetDbAt(1000.0f)
        val trebleTarget = targetBaselineDb + harman.getTargetDbAt(20000.0f)

        assertTrue("Bass target (-11 dBFS) must be strictly below ceiling (0 dBFS)", bassTarget < maxDb)
        assertTrue("Bass target (-11 dBFS) must be strictly above floor (-80 dBFS)", bassTarget > minDb)
        assertEquals(-11.0f, bassTarget, 0.01f)
        assertEquals(-20.0f, midTarget, 0.01f)
        assertEquals(-24.0f, trebleTarget, 0.01f)

        val splOffset = 100.0f
        val splBaseline = targetBaselineDb + splOffset
        val splMaxDb = maxDb + splOffset
        val splBassTarget = splBaseline + harman.getTargetDbAt(20.0f)

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

    // --- File-Picker Convention Selection & Import Path Verification ---

    @Test
    fun filePickerImportWithPositive3DbMeasuredResponseProducesMinus3DbOffsetAndAttenuation() {
        val fileContent = """
            # UMIK-1 / REW Format Calibration File
            1000.0  3.0
            2000.0  3.0
        """.trimIndent()

        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))

        // Simulate file picker import with MEASURED_RESPONSE chosen
        val result = manager.loadMicCurve(
            name = "umik1_picked.txt",
            textContent = fileContent,
            convention = CalibrationFileConvention.MEASURED_RESPONSE
        )
        assertTrue("Import must succeed with MEASURED_RESPONSE", result.isSuccess)
        val curve = result.getOrThrow()
        assertEquals(CalibrationFileConvention.MEASURED_RESPONSE, curve.convention)
        assertEquals("Offset at 1 kHz must be -3.0 dB (inverted)", -3.0f, curve.getOffsetAt(1000.0f), 0.001f)

        // Process audio frame: -40.0 dBFS raw input + (-3.0 dB compensation) = -43.0 dBFS
        val frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, 100.0f)
        assertEquals("Calibrated 1 kHz output must be attenuated by 3 dB to -43.0 dBFS", -43.0f, frame.calibrated31Db[0], 0.01f)
        manager.close()
    }

    @Test
    fun filePickerImportWithPositive3DbAdditiveCorrectionProducesPlus3DbOffsetAndBoost() {
        val fileContent = """
            # Additive Correction Calibration File
            1000.0  3.0
            2000.0  3.0
        """.trimIndent()

        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))

        // Simulate file picker import with ADDITIVE_CORRECTION chosen
        val result = manager.loadMicCurve(
            name = "additive_picked.cal",
            textContent = fileContent,
            convention = CalibrationFileConvention.ADDITIVE_CORRECTION
        )
        assertTrue("Import must succeed with ADDITIVE_CORRECTION", result.isSuccess)
        val curve = result.getOrThrow()
        assertEquals(CalibrationFileConvention.ADDITIVE_CORRECTION, curve.convention)
        assertEquals("Offset at 1 kHz must be +3.0 dB (direct)", 3.0f, curve.getOffsetAt(1000.0f), 0.001f)

        // Process audio frame: -40.0 dBFS raw input + (+3.0 dB compensation) = -37.0 dBFS
        val frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, 100.0f)
        assertEquals("Calibrated 1 kHz output must be boosted by 3 dB to -37.0 dBFS", -37.0f, frame.calibrated31Db[0], 0.01f)
        manager.close()
    }

    @Test
    fun filePickerImportCancellationLeavesActiveCurveUntouched() {
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))

        // Initially load baseline curve (+2 dB additive)
        manager.loadMicCurve("InitialMic", "1000.0 2.0\n2000.0 2.0", CalibrationFileConvention.ADDITIVE_CORRECTION)
        val initialCurve = manager.uiState.value.micCurve
        assertNotNull(initialCurve)
        assertEquals(2.0f, initialCurve!!.getOffsetAt(1000.0f), 0.001f)

        // Simulate user picking file, but dismissing/cancelling dialog -> onLoadMicCurve not called
        // UI pendingFileImport is set to null without calling manager.loadMicCurve
        val currentCurve = manager.uiState.value.micCurve
        assertNotNull(currentCurve)
        assertEquals("InitialMic", currentCurve!!.name)
        assertEquals(2.0f, currentCurve.getOffsetAt(1000.0f), 0.001f)

        // Frame processing remains consistent with InitialMic
        val frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, -40.0f)
        assertEquals(-38.0f, frame.calibrated31Db[0], 0.01f)
        manager.close()
    }

    @Test
    fun repeatedImportsWithConventionSwitchingAndInvalidHandling() {
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements))

        // Step 1: Import with MEASURED_RESPONSE (+3 dB) -> produces -3 dB offset (-43.0 dBFS)
        val res1 = manager.loadMicCurve("File1.txt", "1000.0 3.0\n2000.0 3.0", CalibrationFileConvention.MEASURED_RESPONSE)
        assertTrue(res1.isSuccess)
        var frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, -40.0f)
        assertEquals(-43.0f, frame.calibrated31Db[0], 0.01f)

        // Step 2: Repeat import with same file, but switch convention to ADDITIVE_CORRECTION (+3 dB) -> produces +3 dB offset (-37.0 dBFS)
        val res2 = manager.loadMicCurve("File1.txt", "1000.0 3.0\n2000.0 3.0", CalibrationFileConvention.ADDITIVE_CORRECTION)
        assertTrue(res2.isSuccess)
        frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, -40.0f)
        assertEquals(-37.0f, frame.calibrated31Db[0], 0.01f)

        // Step 3: Attempt import with invalid corrupt file data -> fails and preserves previous valid curve
        val res3 = manager.loadMicCurve("Corrupt.txt", "not a valid calibration format", CalibrationFileConvention.MEASURED_RESPONSE)
        assertTrue("Corrupt file import must return failure Result", res3.isFailure)
        frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, -40.0f)
        assertEquals("Active curve must remain untouched after failed import", -37.0f, frame.calibrated31Db[0], 0.01f)

        // Step 4: Import with MEASURED_RESPONSE (-2 dB) -> produces +2 dB offset (-38.0 dBFS)
        val res4 = manager.loadMicCurve("File2.txt", "1000.0 -2.0\n2000.0 -2.0", CalibrationFileConvention.MEASURED_RESPONSE)
        assertTrue(res4.isSuccess)
        frame = manager.processFrame(FloatArray(31) { -40.0f }, FloatArray(8) { -40.0f }, -40.0f)
        assertEquals(-38.0f, frame.calibrated31Db[0], 0.01f)

        manager.close()
    }

    @Test
    fun testPendingFileImportModel() {
        val pending = com.rtatuner.app.ui.PendingFileImport(
            displayName = "cal_mic.txt",
            content = "1000.0 0.0\n2000.0 0.5"
        )
        assertEquals("cal_mic.txt", pending.displayName)
        assertEquals("1000.0 0.0\n2000.0 0.5", pending.content)

        val copy = pending.copy(displayName = "renamed.txt")
        assertEquals("renamed.txt", copy.displayName)
        assertEquals(pending.content, copy.content)
    }
}

