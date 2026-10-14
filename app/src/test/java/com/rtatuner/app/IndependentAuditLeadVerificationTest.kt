package com.rtatuner.app

import android.content.Context
import com.rtatuner.app.core.audio.CaptureAuthoritySnapshot
import com.rtatuner.app.core.audio.CaptureFingerprint
import com.rtatuner.app.core.audio.CapturedFrameContext
import com.rtatuner.app.core.audio.NoiseFloorCompatibility
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Lead-written acceptance verification tests asserting corrected required behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IndependentAuditLeadVerificationTest {
    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("lead-audit-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun fp(mic: String = "17:Internal A") = CaptureFingerprint(
        actualSampleRate = 48000, routedMicrophoneId = mic,
        routedDeviceType = "Built-in Mic", selectedAudioSource = 9, actualAudioSource = 9
    )
    private fun frame(seq: Long, fingerprint: CaptureFingerprint = fp(), running: Boolean = true,
                      manager: String = "lead-manager", generation: Long = 1, routeEpoch: Long = 1L) =
        CapturedFrameContext(manager, generation, seq, fingerprint, running, routeEpoch)
    private fun raw(db: Float) = FloatArray(31) { db }

    @Test fun invariant_missingDspRevisionOrEmptyObjectIsRejected() {
        val complete = fp()
        val json = JSONObject(complete.toJsonString()).apply { remove("dspRevision") }
        assertNull(CaptureFingerprint.fromJsonString(json.toString()))
        val nested = JSONObject(complete.toJsonString()).apply { put("dspRevision", JSONObject()) }
        assertNull(CaptureFingerprint.fromJsonString(nested.toString()))
    }

    @Test fun invariant_contextlessAcquisitionIsDeniedAndDoesNotFabricateMicrophone() = runTest {
        var now = 0L
        val storage = prefs()
        val manager = CalibrationManager(storage,
            FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)), this, { now })
        try {
            assertTrue(manager.startNoiseFloorCapture(10) is FeatureAccessResult.Denied)
            manager.processFrame(raw(-40f), floatArrayOf(-30f), 48000f / 2048)
            now = 11
            manager.processFrame(raw(-40f), floatArrayOf(-30f), 48000f / 2048)
            val state = manager.uiState.value
            assertFalse(state.noiseFloor.isCaptured)
            assertFalse(state.isNoiseSubtractionEffective)
            assertFalse(storage.contains("noise_floor_fingerprint"))
        } finally { manager.close() }
    }

    @Test fun invariant_nullContextCancelsPreviouslyOwnedAcquisition() = runTest {
        var now = 0L
        val manager = CalibrationManager(prefs(),
            FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)), this, { now })
        val auth = CaptureAuthoritySnapshot("lead-manager", 1L, 1L, fp(), true, 0L)
        manager.updateCaptureAuthority(auth)
        try {
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(10, authority = auth))
            manager.processFrame(raw(-40f), floatArrayOf(), 1f, frame(1))
            now = 11
            manager.processFrame(raw(-20f), floatArrayOf(), 1f) // null context
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertFalse(manager.uiState.value.noiseFloor.isCaptured)
        } finally { manager.close() }
    }

    @Test fun invariant_restoredFloorRequiresLiveCaptureBeforeSubtractionIsEffective() = runTest {
        val storage = prefs()
        storage.edit().putString("noise_floor_bands", raw(-40f).joinToString(","))
            .putBoolean("noise_floor_captured", true).putLong("noise_floor_timestamp", 123)
            .putBoolean("noise_subtraction_active", true)
            .putString("noise_floor_fingerprint", fp().toJsonString()).commit()
        val manager = CalibrationManager(storage,
            FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)), this)
        try {
            assertFalse("Noise subtraction must not be effective without running live capture", manager.uiState.value.isNoiseSubtractionEffective)
            val result = manager.processFrame(raw(-30f), floatArrayOf(-30f), 1f)
            assertEquals(-30f, result.calibrated31Db[0], 0f) // No subtraction applied
            assertEquals(-30f, result.calibratedRawFftDb[0], 0f)
            assertTrue(manager.uiState.value.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        } finally { manager.close() }
    }

    @Test fun invariant_stopAfterOneValidFrameCancelsRatherThanFinalizes() = runTest {
        var now = 0L
        val manager = CalibrationManager(prefs(),
            FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)), this, { now })
        val auth = CaptureAuthoritySnapshot("lead-manager", 1L, 1L, fp(), true, 0L)
        manager.updateCaptureAuthority(auth)
        try {
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(10, authority = auth))
            manager.processFrame(raw(-40f), floatArrayOf(), 1f, frame(1))
            now = 11
            manager.processFrame(raw(-20f), floatArrayOf(), 1f, frame(2, running = false))
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertFalse(manager.uiState.value.noiseFloor.isCaptured)
        } finally { manager.close() }
    }

    @Test fun invariant_replacedOwnerCancelsOldPartialAcquisition() = runTest {
        var now = 0L
        val manager = CalibrationManager(prefs(),
            FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)), this, { now })
        val auth = CaptureAuthoritySnapshot("lead-manager", 1L, 1L, fp(), true, 0L)
        manager.updateCaptureAuthority(auth)
        try {
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(10, authority = auth))
            manager.processFrame(raw(-40f), floatArrayOf(), 1f, frame(1))
            now = 11
            manager.processFrame(raw(-20f), floatArrayOf(), 1f, frame(2, manager = "replacement", generation = 2))
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertFalse(manager.uiState.value.noiseFloor.isCaptured)
        } finally { manager.close() }
    }

    @Test fun invariant_preferencePublicationWhileIncompatibleLeavesSubtractionIneffective() = runTest {
        val storage = prefs()
        storage.edit().putString("noise_floor_bands", raw(-40f).joinToString(","))
            .putBoolean("noise_floor_captured", true).putBoolean("noise_subtraction_active", true)
            .putString("noise_floor_fingerprint", fp().toJsonString()).commit()
        val manager = CalibrationManager(storage,
            FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)), this)
        val auth = CaptureAuthoritySnapshot("lead-manager", 1L, 1L, fp("23:USB B"), true, 0L)
        manager.updateCaptureAuthority(auth)
        try {
            manager.processFrame(raw(-30f), floatArrayOf(), 1f, frame(1, fp("23:USB B")))
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
            assertTrue(manager.uiState.value.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
            manager.setNoiseSubtractionActive(true)
            assertFalse("Toggling active while incompatible must keep subtraction ineffective", manager.uiState.value.isNoiseSubtractionEffective)
            val result = manager.processFrame(raw(-30f), floatArrayOf(), 1f, frame(2, fp("23:USB B")))
            assertEquals(-30f, result.calibrated31Db[0], 0f)
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
        } finally { manager.close() }
    }

    @Test fun invariant_preRequestFrameCannotBeReplayedIntoNewAcquisition() = runTest {
        var now = 0L
        val manager = CalibrationManager(prefs(),
            FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)), this, { now })
        val auth = CaptureAuthoritySnapshot("lead-manager", 1L, 1L, fp(), true, 1L)
        manager.updateCaptureAuthority(auth)
        try {
            val alreadyPublished = frame(1)
            manager.processFrame(raw(-40f), floatArrayOf(), 1f, alreadyPublished)
            manager.startNoiseFloorCapture(10, authority = auth)
            now = 11
            manager.processFrame(raw(-40f), floatArrayOf(), 1f, alreadyPublished)
            assertFalse("Replayed pre-request frame must not finalize acquisition", manager.uiState.value.noiseFloor.isCaptured)
        } finally { manager.close() }
    }
}
