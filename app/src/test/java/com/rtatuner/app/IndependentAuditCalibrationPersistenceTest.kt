package com.rtatuner.app

import android.content.Context
import android.content.SharedPreferences
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.audio.CaptureAuthoritySnapshot
import com.rtatuner.app.core.audio.CaptureEffectsInfo
import com.rtatuner.app.core.audio.CaptureFingerprint
import com.rtatuner.app.core.audio.CapturedFrameContext
import com.rtatuner.app.core.audio.NoiseFloorCompatibility
import com.rtatuner.app.core.calibration.CalibrationFileConvention
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.calibration.NoiseFloorSnapshot
import com.rtatuner.app.core.calibration.TargetCurve
import com.rtatuner.app.core.calibration.evaluateNoiseFloorCompatibility
import com.rtatuner.app.core.dsp.DspResponseRevision
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.math.log10
import kotlin.math.pow

/**
 * Independent, isolated audit acceptance tests asserting corrected required behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IndependentAuditCalibrationPersistenceTest {
    private fun prefs(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("independent-calibration-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun fp(): CaptureFingerprint = CaptureFingerprint(
        actualSampleRate = 48000,
        routedMicrophoneId = "17:Independent actual microphone A",
        routedDeviceType = "Built-in",
        selectedAudioSource = 6,
        actualAudioSource = 6,
        effects = CaptureEffectsInfo(false, false, false, true)
    )

    private fun ctx(sequence: Long = 1, fingerprint: CaptureFingerprint = fp(), running: Boolean = true, routeEpoch: Long = 1L) =
        CapturedFrameContext("independent-manager-A", 2L, sequence, fingerprint, running, routeEpoch)

    private fun auth(fingerprint: CaptureFingerprint = fp(), running: Boolean = true, seq: Long = 0L) =
        CaptureAuthoritySnapshot("independent-manager-A", 2L, 1L, fingerprint, running, seq)

    private fun snapshot(fingerprint: CaptureFingerprint) = NoiseFloorSnapshot(
        FloatArray(31) { -50f }, 777L, true, fingerprint, false
    )

    private fun seed(p: SharedPreferences, fingerprint: String? = fp().toJsonString()) {
        val editor = p.edit()
            .putString("noise_floor_bands", FloatArray(31) { -50f }.joinToString(","))
            .putLong("noise_floor_timestamp", 777L)
            .putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
        if (fingerprint == null) editor.remove("noise_floor_fingerprint")
        else editor.putString("noise_floor_fingerprint", fingerprint)
        assertTrue(editor.commit())
    }

    private fun process(manager: CalibrationManager, context: CapturedFrameContext?, db: Float = -40f) =
        manager.processFrame(FloatArray(31) { db }, FloatArray(8) { db }, 100f, context)

    private fun expectedClean(input: Float = -40f, noise: Float = -50f): Float =
        (10.0 * log10(10.0.pow(input / 10.0) - 10.0.pow(noise / 10.0))).toFloat()

    @Test fun invariant_completeFingerprintRoundTrip() {
        val full = fp().copy(actualAudioSource = 1, isSourceFallback = true,
            effects = CaptureEffectsInfo(true, false, true, true))
        assertEquals(full, CaptureFingerprint.fromJsonString(full.toJsonString()))
    }

    @Test fun invariant_missingDspRevisionReturnsNull() {
        val json = fp().toJson().apply { remove("dspRevision") }
        assertNull(CaptureFingerprint.fromJsonString(json.toString()))
    }

    @Test fun invariant_partialDspRevisionObjectReturnsNull() {
        val json = fp().toJson().apply { put("dspRevision", JSONObject()) }
        assertNull(CaptureFingerprint.fromJsonString(json.toString()))
    }

    @Test fun invariant_persistedFloorWithoutDspRevisionFailsParsingAndDisablesSubtraction() = runTest {
        val p = prefs()
        seed(p, fp().toJson().apply { remove("dspRevision") }.toString())
        val before = p.all.toMap()
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),
            backgroundScope)
        manager.updateCaptureAuthority(auth())
        try {
            assertTrue(manager.uiState.value.noiseFloor.requiresRecapture)
            assertNull(manager.uiState.value.noiseFloor.fingerprint)
            assertArrayEquals(FloatArray(31) { -40f }, process(manager, ctx()).calibrated31Db, 0f)
            assertTrue(manager.uiState.value.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
            assertEquals(before, p.all)
        } finally { manager.close() }
    }

    @Test fun invariant_missingSelectedSourceReturnsNull() {
        val json = fp().toJson().apply { remove("selectedSource") }
        assertNull(CaptureFingerprint.fromJsonString(json.toString()))
    }

    @Test fun invariant_missingActualSourceReturnsNull() {
        val actualDefault = fp().copy(actualAudioSource = 0, isSourceFallback = true)
        val json = actualDefault.toJson().apply { remove("actualSource") }
        assertNull(CaptureFingerprint.fromJsonString(json.toString()))
    }

    @Test fun invariant_missingEffectsFieldsReturnsNull() {
        val json = fp().toJson().apply { put("effects", JSONObject()) }
        assertNull(CaptureFingerprint.fromJsonString(json.toString()))
    }

    @Test fun invariant_whollyAbsentEffectsReturnsNull() {
        val json = fp().toJson().apply { remove("effects") }
        assertNull(CaptureFingerprint.fromJsonString(json.toString()))
    }

    @Test fun invariant_invalidDeviceTypeReturnsNull() {
        assertNull(CaptureFingerprint.fromJsonString(
            fp().toJson().apply { put("devType", "NOT_AN_ANDROID_INPUT_DEVICE") }.toString()))
    }

    @Test fun invariant_explicitUnknownDeviceTypeFailsClosed() {
        val unknown = fp().copy(routedDeviceType = CaptureFingerprint.UNKNOWN_DEVICE_TYPE)
        assertFalse(unknown.isMicrophoneKnown)
        assertTrue(evaluateNoiseFloorCompatibility(snapshot(unknown), fp()) is NoiseFloorCompatibility.Incompatible)
    }

    @Test fun invariant_invalidRateReturnsNull() {
        for (badRate in listOf(0, -48000, 16000)) {
            assertNull(CaptureFingerprint.fromJsonString(
                fp().toJson().apply { put("sampleRate", badRate) }.toString()))
        }
    }

    @Test fun invariant_inconsistentFallbackFlagReturnsNull() {
        val actualFallback = fp().copy(actualAudioSource = 1, isSourceFallback = true)
        assertNull(CaptureFingerprint.fromJsonString(
            actualFallback.toJson().apply { put("fallback", false) }.toString()))
    }

    @Test fun invariant_futureRevisionDoesNotMatchCurrentResponse() {
        val future = fp().copy(dspRevision = fp().dspRevision.copy(revisionId = "UNKNOWN_FUTURE_999"))
        val restored = requireNotNull(CaptureFingerprint.fromJsonString(future.toJsonString()))
        assertTrue(evaluateNoiseFloorCompatibility(snapshot(restored), fp()) is NoiseFloorCompatibility.Incompatible)
    }

    @Test fun invariant_malformedJsonDoesNotCreateFingerprint() {
        for (bad in listOf("<<<NOT_JSON>>>", "{", "[]", "", " ")) {
            assertNull(bad, CaptureFingerprint.fromJsonString(bad))
        }
    }

    @Test fun invariant_partialJsonReturnsNull() {
        assertNull(CaptureFingerprint.fromJsonString(
            "{\"micId\":\"17:Independent actual microphone A\",\"devType\":\"Built-in\"}"))
    }

    @Test fun invariant_contextlessAcquisitionDeniedAndDoesNotPersistFloor() = runTest {
        var now = 1000L
        val p = prefs()
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),
            backgroundScope, nowMs = { now })
        try {
            assertTrue(manager.startNoiseFloorCapture(100) is FeatureAccessResult.Denied)
            process(manager, null, -50f)
            now += 101
            process(manager, null, -50f)
            val saved = manager.uiState.value.noiseFloor
            assertFalse(saved.isCaptured)
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
            assertFalse(p.contains("noise_floor_fingerprint"))
        } finally { manager.close() }
    }

    @Test fun invariant_contextlessAcquisitionDoesNotReusePriorLiveFingerprint() = runTest {
        var now = 1000L
        val p = prefs()
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),
            backgroundScope, nowMs = { now })
        val a = auth()
        manager.updateCaptureAuthority(a)
        try {
            process(manager, ctx(), -30f)
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(100, authority = a))
            process(manager, null, -70f)
            now += 101
            process(manager, null, -70f)
            assertFalse(manager.uiState.value.noiseFloor.isCaptured)
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
        } finally { manager.close() }
    }

    @Test fun invariant_nullContextAfterValidFrameCancelsAcquisitionAndPreservesSavedFloor() = runTest {
        var now = 1000L
        val p = prefs()
        seed(p)
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),
            backgroundScope, nowMs = { now })
        val a = auth()
        manager.updateCaptureAuthority(a)
        try {
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(100, authority = a))
            process(manager, ctx(), -60f)
            now += 101
            process(manager, null, -20f)
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertArrayEquals(FloatArray(31) { -50f }, manager.uiState.value.noiseFloor.bands31Db, 0f)
            assertEquals(fp(), manager.uiState.value.noiseFloor.fingerprint)
            assertEquals(777L, manager.uiState.value.noiseFloor.captureTimestampMs)
        } finally { manager.close() }
    }

    @Test fun invariant_acquisitionStartRequiresActiveCaptureAuthority() = runTest {
        val manager = CalibrationManager(prefs(), FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),
            backgroundScope)
        try {
            assertTrue(manager.startNoiseFloorCapture(100) is FeatureAccessResult.Denied)
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
        } finally { manager.close() }
    }

    @Test fun invariant_stoppedContextCancelsAcquisitionInsteadOfFinalization() = runTest {
        var now = 1000L
        val p = prefs()
        seed(p)
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),
            backgroundScope, nowMs = { now })
        val a = auth()
        manager.updateCaptureAuthority(a)
        try {
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(100, authority = a))
            process(manager, ctx(1), -70f)
            now += 101
            process(manager, ctx(2, running = false), -10f)
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertEquals(777L, manager.uiState.value.noiseFloor.captureTimestampMs)
            assertArrayEquals(FloatArray(31) { -50f }, manager.uiState.value.noiseFloor.bands31Db, 0f)
            assertEquals(777L, p.getLong("noise_floor_timestamp", 0L))
        } finally { manager.close() }
    }

    @Test fun invariant_materialFingerprintChangesCancelAndPreserveSavedFloor() {
        val changes = listOf(
            fp().copy(routedMicrophoneId = "99:Actual microphone B"),
            fp().copy(actualSampleRate = 44100),
            fp().copy(actualAudioSource = 1, isSourceFallback = true),
            fp().copy(effects = CaptureEffectsInfo(true, false, false, true))
        )
        for (changed in changes) {
            var now = 1000L
            val p = prefs()
            seed(p)
            val before = p.all.toMap()
            val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),
                nowMs = { now })
            val a = auth()
            manager.updateCaptureAuthority(a)
            try {
                assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(100, authority = a))
                process(manager, ctx(1), -60f)
                now += 50
                process(manager, ctx(2, changed), -10f)
                assertFalse(manager.uiState.value.isCapturingNoiseFloor)
                assertEquals(0f, manager.uiState.value.noiseCaptureProgress, 0f)
                assertEquals(777L, manager.uiState.value.noiseFloor.captureTimestampMs)
                assertEquals(fp(), manager.uiState.value.noiseFloor.fingerprint)
                assertArrayEquals(FloatArray(31) { -50f }, manager.uiState.value.noiseFloor.bands31Db, 0f)
                assertEquals(before, p.all)
            } finally { manager.close() }
        }
    }

    @Test fun invariant_duplicateAndOldSequenceDoNotContaminatePowerAverage() {
        var now = 1000L
        val manager = CalibrationManager(prefs(), FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),
            nowMs = { now })
        val a = auth(seq = 2L)
        manager.updateCaptureAuthority(a)
        try {
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(100, authority = a))
            process(manager, ctx(3), -60f)
            process(manager, ctx(3), -10f)
            process(manager, ctx(2), -10f)
            now += 101
            process(manager, ctx(4), -60f)
            assertArrayEquals(FloatArray(31) { -60f }, manager.uiState.value.noiseFloor.bands31Db, 0.0001f)
        } finally { manager.close() }
    }

    @Test fun invariant_legacyPrefsPreserveHistoryAndDisableLiveSubtraction() {
        val p = prefs()
        seed(p, null)
        val before = p.all.toMap()
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
        manager.updateCaptureAuthority(auth())
        try {
            val floor = manager.uiState.value.noiseFloor
            assertTrue(floor.isCaptured)
            assertTrue(floor.requiresRecapture)
            assertNull(floor.fingerprint)
            assertEquals(777L, floor.captureTimestampMs)
            assertArrayEquals(FloatArray(31) { -50f }, floor.bands31Db, 0f)
            assertTrue(manager.uiState.value.isNoiseSubtractionActive)
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
            assertArrayEquals(FloatArray(31) { -40f }, process(manager, ctx()).calibrated31Db, 0f)
            assertTrue(manager.uiState.value.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
            assertEquals(before, p.all)
        } finally { manager.close() }
    }

    @Test fun invariant_validFingerprintSurvivesRestartAndSubtractsOnlyAgainstMatchingRateSourceEffectsAndMic() {
        val p = prefs()
        seed(p)
        val before = p.all.toMap()
        repeat(2) {
            val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
            manager.updateCaptureAuthority(auth())
            try {
                assertEquals(fp(), manager.uiState.value.noiseFloor.fingerprint)
                assertArrayEquals(FloatArray(31) { expectedClean() }, process(manager, ctx()).calibrated31Db, 0.0001f)
                for (bad in listOf(fp().copy(actualSampleRate = 44100), fp().copy(actualAudioSource = 1),
                    fp().copy(routedMicrophoneId = "USB mic"),
                    fp().copy(effects = CaptureEffectsInfo(false, true, false, true)),
                    fp().copy(dspRevision = fp().dspRevision.copy(coefficientRevision = 2)))) {
                    val badAuth = auth(bad)
                    manager.updateCaptureAuthority(badAuth)
                    assertArrayEquals(FloatArray(31) { -40f }, process(manager, ctx(2, bad)).calibrated31Db, 0f)
                    assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
                }
                assertEquals(before, p.all)
            } finally { manager.close() }
        }
    }

    @Test fun invariant_savedFloorSubtractionRequiresLiveCaptureBeforeBecomingEffective() {
        val p = prefs()
        seed(p)
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
        try {
            assertFalse("Noise subtraction must not be effective without running live capture", manager.uiState.value.isNoiseSubtractionEffective)
            assertArrayEquals(FloatArray(31) { -40f }, process(manager, null).calibrated31Db, 0f)
            assertTrue(manager.uiState.value.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        } finally { manager.close() }
    }

    @Test fun invariant_nullFrameDoesNotReceiveSubtractionEvenAfterMatchingLiveFrame() {
        val p = prefs()
        seed(p)
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
        try {
            process(manager, ctx())
            assertArrayEquals(FloatArray(31) { -40f }, process(manager, null).calibrated31Db, 0f)
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
        } finally { manager.close() }
    }

    @Test fun invariant_stoppedFrameContextDoesNotAuthorizeSavedFloorSubtraction() {
        val p = prefs()
        seed(p)
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
        val a = auth(running = false)
        manager.updateCaptureAuthority(a)
        try {
            assertArrayEquals(FloatArray(31) { -40f }, process(manager, ctx(running = false)).calibrated31Db, 0f)
            assertTrue(manager.uiState.value.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
        } finally { manager.close() }
    }

    @Test fun invariant_persistedFloorDoesNotAdvertiseEffectiveOnStartup() {
        val p = prefs()
        seed(p, fp().copy(dspRevision = fp().dspRevision.copy(revisionId = "LEGACY_RESPONSE")).toJsonString())
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
        try {
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
            assertArrayEquals(FloatArray(31) { -40f }, process(manager, ctx()).calibrated31Db, 0f)
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
        } finally { manager.close() }
    }

    @Test fun invariant_preferenceMutationWhileIncompatibleKeepsSubtractionIneffective() {
        val p = prefs()
        seed(p)
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
        val wrong = fp().copy(routedMicrophoneId = "99:Actual microphone B")
        val wrongAuth = auth(wrong)
        manager.updateCaptureAuthority(wrongAuth)
        try {
            process(manager, ctx(1, wrong))
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
            assertSame(FeatureAccessResult.Allowed, manager.setNoiseSubtractionActive(true))
            assertFalse("Toggling active while incompatible must leave subtraction ineffective", manager.uiState.value.isNoiseSubtractionEffective)
            assertTrue(manager.uiState.value.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
            assertArrayEquals(FloatArray(31) { -40f }, process(manager, ctx(2, wrong)).calibrated31Db, 0f)
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
        } finally { manager.close() }
    }

    @Test fun invariant_freeCannotStartCaptureOrEnableSubtractionAndHistoryIsPreserved() {
        val p = prefs()
        seed(p)
        val before = p.all.toMap()
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.FREE)))
        try {
            assertTrue(manager.startNoiseFloorCapture(100) is FeatureAccessResult.Denied)
            assertTrue(manager.setNoiseSubtractionActive(true) is FeatureAccessResult.Denied)
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
            assertArrayEquals(FloatArray(31) { -40f }, process(manager, ctx()).calibrated31Db, 0f)
            assertEquals(before, p.all)
        } finally { manager.close() }
    }

    @Test fun invariant_entitlementRevocationCancelsAcquisitionAndPreservesOldFloor() = runTest {
        var now = 1000L
        val p = prefs()
        seed(p)
        val before = p.all.toMap()
        val repo = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(p, FeatureAccessPolicy(repo), backgroundScope, nowMs = { now })
        val a = auth()
        manager.updateCaptureAuthority(a)
        try {
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(100, authority = a))
            process(manager, ctx(1), -70f)
            repo.setTier(EntitlementTier.FREE)
            runCurrent()
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            now += 101
            process(manager, ctx(2), -10f)
            assertEquals(777L, manager.uiState.value.noiseFloor.captureTimestampMs)
            assertEquals(before, p.all)
        } finally { manager.close() }
    }

    @Test fun invariant_proFreeProTransitionPreservesRequestAndRestoresCompatibleSubtraction() = runTest {
        val p = prefs()
        seed(p)
        val before = p.all.toMap()
        val repo = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(p, FeatureAccessPolicy(repo), backgroundScope)
        val a = auth()
        manager.updateCaptureAuthority(a)
        try {
            assertArrayEquals(FloatArray(31) { expectedClean() }, process(manager, ctx()).calibrated31Db, 0.0001f)
            repo.setTier(EntitlementTier.FREE)
            runCurrent()
            assertFalse(manager.uiState.value.isNoiseSubtractionActive)
            assertTrue(p.getBoolean("noise_subtraction_active", false))
            assertArrayEquals(FloatArray(31) { -40f }, process(manager, ctx(2)).calibrated31Db, 0f)
            repo.setTier(EntitlementTier.PRO_LIFETIME)
            runCurrent()
            assertTrue(manager.uiState.value.isNoiseSubtractionActive)
            assertArrayEquals(FloatArray(31) { expectedClean() }, process(manager, ctx(3)).calibrated31Db, 0.0001f)
            assertEquals(before, p.all)
        } finally { manager.close() }
    }

    @Test fun invariant_fftDisplayMicAndSplChangesKeepRawFloorAndCorrectNumericalOrder() {
        val p = prefs()
        seed(p)
        val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
        val a = auth()
        manager.updateCaptureAuthority(a)
        try {
            manager.loadMicCurve("Independent correction", "20 3\n20000 3", CalibrationFileConvention.ADDITIVE_CORRECTION)
            manager.setSplOffset(100f)
            manager.setSplMode(true)
            manager.setTargetCurve(TargetCurve.HARMAN_CAR)
            manager.setTargetCurveActive(true)
            for (size in listOf(2048, 4096)) {
                val result = manager.processFrame(FloatArray(31) { -40f }, FloatArray(size / 2 + 1) { -40f },
                    48000f / size, ctx(size.toLong()))
                assertArrayEquals(FloatArray(31) { expectedClean() + 103f }, result.calibrated31Db, 0.0001f)
                assertArrayEquals(FloatArray(size / 2 + 1) { 63f }, result.calibratedRawFftDb, 0.0001f)
                assertEquals(fp(), manager.uiState.value.noiseFloor.fingerprint)
                assertFalse(manager.uiState.value.noiseFloor.requiresRecapture)
            }
        } finally { manager.close() }
    }
}
