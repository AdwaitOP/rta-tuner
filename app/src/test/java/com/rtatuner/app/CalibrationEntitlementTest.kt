package com.rtatuner.app

import android.content.Context
import android.content.SharedPreferences
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.calibration.TargetCurve
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessDeniedException
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CalibrationEntitlementTest {
    private fun preferences(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("calibration-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun legacyPreferences(): SharedPreferences = preferences().apply {
        edit()
            .putString("mic_curve_name", "Legacy microphone")
            .putString("mic_curve_points", "[{\"f\":20,\"o\":3},{\"f\":20000,\"o\":3}]")
            .putBoolean("mic_curve_active", true)
            .putBoolean("spl_mode", true)
            .putFloat("spl_offset_db", 100f)
            .putString("target_curve_id", TargetCurve.FLAT.id)
            .putBoolean("target_curve_active", true)
            .putString("noise_floor_bands", FloatArray(31) { -50f }.joinToString(","))
            .putLong("noise_floor_timestamp", 1234L)
            .putString("noise_floor_fingerprint", com.rtatuner.app.core.audio.CaptureFingerprint(
                actualSampleRate = 48000,
                routedMicrophoneId = "builtin_mic",
                routedDeviceType = "TYPE_BUILTIN_MIC",
                selectedAudioSource = 6,
                actualAudioSource = 6
            ).toJsonString())
            .putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .commit()
    }

    @Test
    fun defaultFreeRejectsAllPremiumMutationsWithoutChangingStateOrPreferences() = runTest {
        val prefs = legacyPreferences()
        val manager = CalibrationManager(prefs, scope = backgroundScope)
        try {
            val beforePrefs = prefs.all.toMap()
            val beforeState = manager.uiState.value
            val import = manager.loadMicCurve("Rejected microphone", "20 6\n20000 6")
            assertTrue(import.exceptionOrNull() is FeatureAccessDeniedException)
            val denied = listOf(
                manager.setMicCurveActive(true), manager.setMicCurveActive(false), manager.clearMicCurve(),
                manager.calibrateSplReference(-30f, 94f), manager.setSplMode(true), manager.setSplMode(false),
                manager.setSplOffset(110f), manager.startNoiseFloorCapture(),
                manager.setNoiseSubtractionActive(true), manager.setNoiseSubtractionActive(false), manager.clearNoiseFloor(),
                manager.setTargetCurve(TargetCurve.FLAT), manager.setTargetCurve(TargetCurve.AUDIO_CONTROL_CAR),
                manager.setTargetCurveActive(true), manager.setTargetCurveActive(false)
            )
            assertTrue(denied.all { it is FeatureAccessResult.Denied })
            assertEquals(beforePrefs, prefs.all)
            assertEquals(beforeState, manager.uiState.value)
        } finally { manager.close() }
    }

    @Test
    fun persistedPremiumSettingsAreRetainedButDoNotModifyFreeAudio() = runTest {
        val prefs = legacyPreferences()
        val before = prefs.all.toMap()
        val manager = CalibrationManager(prefs, scope = backgroundScope)
        try {
            val state = manager.uiState.value
            assertEquals("Legacy microphone", state.micCurve?.name)
            assertFalse(state.isMicCurveActive)
            assertFalse(state.splCalibration.isSplMode)
            assertEquals(100f, state.splCalibration.splOffsetDb, 0f)
            assertTrue(state.noiseFloor.isCaptured)
            assertEquals(1234L, state.noiseFloor.captureTimestampMs)
            assertFalse(state.isNoiseSubtractionActive)
            assertEquals(TargetCurve.FLAT, state.targetCurve)
            assertFalse(state.isTargetCurveActive)
            val frame = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f)
            assertArrayEquals(FloatArray(31) { -40f }, frame.calibrated31Db, 0f)
            assertArrayEquals(FloatArray(8) { -40f }, frame.calibratedRawFftDb, 0f)
            assertFalse(frame.isSplMode)
            assertFalse(frame.unreliableBands.any { it })
            assertNull(frame.targetDeltas31Db)
            assertEquals(before, prefs.all)
        } finally { manager.close() }
    }

    @Test
    fun freeProFreeProTransitionsRestoreRequestedSettingsAndKeepPersistedData() = runTest {
        val prefs = legacyPreferences()
        val before = prefs.all.toMap()
        val entitlements = FakeEntitlementRepository()
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)
        try {
            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            runCurrent()
            val pro = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f)
            val expectedBand = AudioMath.subtractNoiseFloor(-40f, -50f, AudioMath.MIN_DB_FS).correctedDb + 3f + 100f
            assertArrayEquals(FloatArray(31) { expectedBand }, pro.calibrated31Db, 0.0001f)
            assertArrayEquals(FloatArray(8) { 63f }, pro.calibratedRawFftDb, 0.0001f)
            assertTrue(pro.isSplMode)
            assertNotNull(pro.targetDeltas31Db)
            assertTrue(manager.uiState.value.isMicCurveActive)

            entitlements.setTier(EntitlementTier.FREE)
            runCurrent()
            val free = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f)
            assertArrayEquals(FloatArray(31) { -40f }, free.calibrated31Db, 0f)
            assertArrayEquals(FloatArray(8) { -40f }, free.calibratedRawFftDb, 0f)
            assertFalse(free.isSplMode)
            assertNull(free.targetDeltas31Db)
            assertTrue(free.shouldResetSmoothing)
            assertEquals(before, prefs.all)

            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            runCurrent()
            assertTrue(manager.uiState.value.isMicCurveActive)
            assertTrue(manager.uiState.value.splCalibration.isSplMode)
            assertTrue(manager.uiState.value.isNoiseSubtractionActive)
            assertTrue(manager.uiState.value.isTargetCurveActive)
            assertEquals(before, prefs.all)
        } finally { manager.close() }
    }

    @Test
    fun freeHarmanTargetRemainsFullyAvailableWhileAdvancedTargetsAreLocked() = runTest {
        val manager = CalibrationManager(preferences(), scope = backgroundScope)
        try {
            assertEquals(FeatureAccessResult.Allowed, manager.setTargetCurve(TargetCurve.HARMAN_CAR))
            assertEquals(FeatureAccessResult.Allowed, manager.setTargetCurveActive(true))
            val frame = manager.processFrame(FloatArray(31) { -20f }, floatArrayOf(-20f), 100f)
            assertNotNull(frame.targetDeltas31Db)
            assertEquals(-9f, frame.targetDeltas31Db!![0], 0.0001f)
            assertTrue(manager.setTargetCurve(TargetCurve.FLAT) is FeatureAccessResult.Denied)
            assertTrue(manager.setTargetCurve(TargetCurve.AUDIO_CONTROL_CAR) is FeatureAccessResult.Denied)
            assertEquals(TargetCurve.HARMAN_CAR, manager.uiState.value.targetCurve)
            assertTrue(manager.uiState.value.isTargetCurveActive)
            assertEquals(FeatureAccessResult.Allowed, manager.setTargetCurve(null))
        } finally { manager.close() }
    }

    @Test
    fun proCanImportCalibrateAndSelectBothAdvancedPresets() = runTest {
        val manager = CalibrationManager(preferences(), FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)), backgroundScope)
        try {
            assertTrue(manager.loadMicCurve("Measurement microphone", "20 2\n20000 2").isSuccess)
            assertTrue(manager.uiState.value.isMicCurveActive)
            assertEquals(FeatureAccessResult.Allowed, manager.calibrateSplReference(-24f, 85f))
            assertEquals(109f, manager.uiState.value.splCalibration.splOffsetDb, 0f)
            assertEquals(FeatureAccessResult.Allowed, manager.setTargetCurve(TargetCurve.FLAT))
            assertEquals(FeatureAccessResult.Allowed, manager.setTargetCurve(TargetCurve.AUDIO_CONTROL_CAR))
            assertEquals(FeatureAccessResult.Allowed, manager.setTargetCurveActive(true))
            assertTrue(manager.uiState.value.isTargetCurveActive)
        } finally { manager.close() }
    }

    @Test
    fun revocationCancelsInFlightNoiseCaptureAndRetainsPreviousSnapshot() = runTest {
        val prefs = legacyPreferences()
        val before = prefs.all.toMap()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)
        try {
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(60_000L))
            manager.processFrame(FloatArray(31) { -80f }, floatArrayOf(-80f), 100f)
            assertTrue(manager.uiState.value.isCapturingNoiseFloor)
            entitlements.setTier(EntitlementTier.FREE)
            runCurrent()
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertFalse(manager.uiState.value.isNoiseSubtractionActive)
            assertEquals(0f, manager.uiState.value.noiseCaptureProgress, 0f)
            assertArrayEquals(FloatArray(31) { -50f }, manager.uiState.value.noiseFloor.bands31Db, 0f)
            assertEquals(before, prefs.all)
            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            runCurrent()
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertTrue(manager.uiState.value.isNoiseSubtractionActive)
        } finally { manager.close() }
    }

    @Test
    fun noiseCaptureFinalizationRechecksAuthorizationBeforePersisting() = runTest {
        var now = 1_000L
        val prefs = legacyPreferences()
        val before = prefs.all.toMap()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope, nowMs = { now })
        try {
            manager.startNoiseFloorCapture(100L)
            now += 200L
            entitlements.setTier(EntitlementTier.FREE)
            // Do not dispatch the collector: persistence must guard this race itself.
            val frame = manager.processFrame(FloatArray(31) { -80f }, floatArrayOf(-80f), 100f)
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertEquals(before, prefs.all)
            assertArrayEquals(FloatArray(31) { -80f }, frame.calibrated31Db, 0f)
        } finally { manager.close() }
    }

    @Test
    fun authorizedNoiseCaptureStillPersistsPowerAverageAndEnablesSubtraction() = runTest {
        var now = 1_000L
        val prefs = preferences()
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)), backgroundScope, nowMs = { now })
        try {
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(100L))
            manager.processFrame(FloatArray(31) { -40f }, floatArrayOf(-40f), 100f)
            now += 100L
            manager.processFrame(FloatArray(31) { -50f }, floatArrayOf(-50f), 100f)
            val expected = (10.0 * kotlin.math.log10((0.0001 + 0.00001) / 2.0)).toFloat()
            assertArrayEquals(FloatArray(31) { expected }, manager.uiState.value.noiseFloor.bands31Db, 0.0001f)
            assertTrue(manager.uiState.value.isNoiseSubtractionActive)
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertTrue(prefs.getBoolean("noise_floor_captured", false))
            assertEquals(now, prefs.getLong("noise_floor_timestamp", 0L))
        } finally { manager.close() }
    }
}
