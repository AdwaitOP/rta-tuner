package com.rtatuner.app

import android.content.Context
import android.content.SharedPreferences
import com.rtatuner.app.core.audio.CaptureAuthoritySnapshot
import com.rtatuner.app.core.audio.CaptureFingerprint
import com.rtatuner.app.core.audio.CapturedFrameContext
import com.rtatuner.app.core.audio.NoiseFloorCompatibility
import com.rtatuner.app.core.calibration.CalibratedSpectralFrame
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.calibration.CalibrationUiState
import com.rtatuner.app.core.calibration.TargetCurve
import com.rtatuner.app.core.entitlement.EntitlementRepository
import com.rtatuner.app.core.entitlement.EntitlementState
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Entitlement observer delivery is deliberately withheld at every immediate-state boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EntitlementUiAuthorityTest {
    private fun fingerprint(mic: String = "A") = CaptureFingerprint(
        actualSampleRate = 48000, routedMicrophoneId = mic, routedDeviceType = "Built-in",
        selectedAudioSource = 6, actualAudioSource = 6
    )

    private fun authority(mic: String = "A", epoch: Long = 3L, running: Boolean = true) =
        CaptureAuthoritySnapshot("owner", 2L, epoch, fingerprint(mic), running, 20L)

    private fun context(mic: String = "A", epoch: Long = 3L) =
        CapturedFrameContext("owner", 2L, 21L, fingerprint(mic), true, epoch)

    private fun preferences(target: TargetCurve = TargetCurve.FLAT): SharedPreferences =
        RuntimeEnvironment.getApplication().getSharedPreferences("entitlement-ui-${UUID.randomUUID()}", Context.MODE_PRIVATE).also {
            check(it.edit()
                .putString("mic_curve_name", "constant+3")
                .putString("mic_curve_points", "[{\"f\":20,\"o\":3},{\"f\":20000,\"o\":3}]")
                .putBoolean("mic_curve_active", true).putBoolean("spl_mode", true).putFloat("spl_offset_db", 100f)
                .putString("target_curve_id", target.id).putBoolean("target_curve_active", true)
                .putString("noise_floor_bands", FloatArray(31) { -50f }.joinToString(","))
                .putLong("noise_floor_timestamp", 777L).putBoolean("noise_floor_captured", true)
                .putBoolean("noise_subtraction_active", true)
                .putString("noise_floor_fingerprint", fingerprint().toJsonString()).commit())
        }

    private fun output(manager: CalibrationManager, mic: String = "A", epoch: Long = 3L) =
        manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, context(mic, epoch))

    private fun assertFree(frame: CalibratedSpectralFrame, harmanTarget: Boolean = false) {
        assertArrayEquals(FloatArray(31) { -40f }, frame.calibrated31Db, 0f)
        assertArrayEquals(FloatArray(8) { -40f }, frame.calibratedRawFftDb, 0f)
        assertFalse(frame.isSplMode)
        assertEquals(0f, frame.splOffsetDb, 0f)
        assertFalse(frame.unreliableBands.any { it })
        if (harmanTarget) assertNotNull(frame.targetDeltas31Db) else assertNull(frame.targetDeltas31Db)
    }

    private fun assertFreeUi(state: CalibrationUiState, harmanTarget: Boolean = false) {
        assertFalse("Mic UI must use current Free entitlement", state.isMicCurveActive)
        assertFalse("SPL UI must use current Free entitlement", state.splCalibration.isSplMode)
        assertEquals("Advanced target UI must use current Free entitlement", harmanTarget, state.isTargetCurveActive)
        assertFalse("Noise request projection must use current Free entitlement", state.isNoiseSubtractionActive)
        assertFalse("Noise effective UI must use current Free entitlement", state.isNoiseSubtractionEffective)
        assertEquals("Calib.: Stored (Pro required)", state.micCurveStatusText)
    }

    private fun assertProUi(state: CalibrationUiState, noiseEffective: Boolean = true) {
        assertTrue(state.isMicCurveActive)
        assertTrue(state.splCalibration.isSplMode)
        assertTrue(state.isTargetCurveActive)
        assertTrue(state.isNoiseSubtractionActive)
        assertEquals(noiseEffective, state.isNoiseSubtractionEffective)
    }

    @Test fun retainedFlowImmediatelyProjectsFreeBeforeProcessingOrObserverDelivery() = runTest {
        val prefs = preferences(); val before = prefs.all.toMap()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)
        try {
            manager.updateCaptureAuthority(authority()); runCurrent()
            val retainedFlow = manager.uiState
            assertProUi(retainedFlow.value)
            entitlements.setTier(EntitlementTier.FREE)
            // Neither a fresh frame nor the asynchronous collector has run yet.
            val immediate = retainedFlow.value
            println("IMMEDIATE_FREE_UI mic=${immediate.isMicCurveActive} spl=${immediate.splCalibration.isSplMode} target=${immediate.isTargetCurveActive} noiseActive=${immediate.isNoiseSubtractionActive} noiseEffective=${immediate.isNoiseSubtractionEffective}")
            assertFree(output(manager))
            assertEquals(before, prefs.all)
            assertFreeUi(immediate)
            assertFreeUi(retainedFlow.value)
        } finally { manager.close() }
    }

    @Test fun newCollectorBeforeObserverDoesNotReceiveQueuedProProjection() = runTest {
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(preferences(), FeatureAccessPolicy(entitlements), backgroundScope)
        try {
            manager.updateCaptureAuthority(authority()); runCurrent()
            val retainedFlow = manager.uiState
            entitlements.setTier(EntitlementTier.FREE)
            val observed = mutableListOf<CalibrationUiState>()
            // Subscribe immediately, before either a UI value read or entitlement observer delivery.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { retainedFlow.collect { observed += it } }
            assertEquals(1, observed.size)
            assertFreeUi(observed.single())
            runCurrent()
            assertEquals("Equivalent current projections must remain conflated", 1, observed.size)
            assertFreeUi(observed.last())
        } finally { manager.close() }
    }

    @Test fun retainedReplayCacheImmediatelyUsesCurrentEntitlement() = runTest {
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(preferences(), FeatureAccessPolicy(entitlements), backgroundScope)
        try {
            manager.updateCaptureAuthority(authority()); runCurrent()
            val retainedFlow = manager.uiState
            entitlements.setTier(EntitlementTier.FREE)
            assertFreeUi(retainedFlow.replayCache.single())
            assertFree(output(manager))
        } finally { manager.close() }
    }

    @Test fun observerCollectionConvergesAndImmediateRegrantRestoresStoredRequests() = runTest {
        val prefs = preferences(); val before = prefs.all.toMap()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)
        try {
            manager.updateCaptureAuthority(authority()); runCurrent()
            val retainedFlow = manager.uiState
            val observed = mutableListOf<CalibrationUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { retainedFlow.collect { observed += it } }
            entitlements.setTier(EntitlementTier.FREE)
            assertFreeUi(retainedFlow.value)
            runCurrent()
            assertFreeUi(observed.last())
            assertFree(output(manager))
            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            assertProUi(retainedFlow.value)
            val restored = output(manager)
            assertEquals(62.542427f, restored.calibrated31Db[0], 0.0001f)
            assertArrayEquals(FloatArray(8) { 63f }, restored.calibratedRawFftDb, 0f)
            assertTrue(restored.isSplMode); assertNotNull(restored.targetDeltas31Db)
            runCurrent(); assertProUi(observed.last())
            assertEquals(before, prefs.all)
        } finally { manager.close() }
    }

    @Test fun observerWithoutAnyUiReadStillPublishesCurrentFreeState() = runTest {
        val prefs = preferences(); val before = prefs.all.toMap()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)
        try {
            manager.updateCaptureAuthority(authority()); runCurrent()
            val observed = mutableListOf<CalibrationUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.uiState.collect { observed += it } }
            entitlements.setTier(EntitlementTier.FREE)
            runCurrent()
            assertFreeUi(observed.last()); assertFreeUi(manager.uiState.value)
            assertFree(output(manager)); assertEquals(before, prefs.all)
        } finally { manager.close() }
    }

    @Test fun immediateRegrantUsesCurrentRouteAndStoppedAuthority() = runTest {
        val prefs = preferences(); val before = prefs.all.toMap()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)
        try {
            manager.updateCaptureAuthority(authority()); runCurrent()
            entitlements.setTier(EntitlementTier.FREE)
            assertFreeUi(manager.uiState.value)
            manager.updateCaptureAuthority(authority("B", 4))
            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            assertProUi(manager.uiState.value, noiseEffective = false)
            assertTrue(manager.uiState.value.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
            assertArrayEquals(FloatArray(31) { 63f }, output(manager, "B", 4).calibrated31Db, 0f)
            manager.updateCaptureAuthority(authority(running = false))
            entitlements.setTier(EntitlementTier.FREE); assertFreeUi(manager.uiState.value)
            entitlements.setTier(EntitlementTier.PRO_LIFETIME); assertProUi(manager.uiState.value, noiseEffective = false)
            assertArrayEquals(FloatArray(31) { 63f }, output(manager).calibrated31Db, 0f)
            manager.updateCaptureAuthority(authority(epoch = 5))
            assertProUi(manager.uiState.value)
            assertEquals(62.542427f, output(manager, epoch = 5).calibrated31Db[0], 0.0001f)
            runCurrent(); assertProUi(manager.uiState.value); assertEquals(before, prefs.all)
        } finally { manager.close() }
    }

    @Test fun freeHarmanProjectionRemainsAvailableBeforeObserverDelivery() = runTest {
        val prefs = preferences(TargetCurve.HARMAN_CAR); val before = prefs.all.toMap()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)
        try {
            manager.updateCaptureAuthority(authority()); runCurrent()
            entitlements.setTier(EntitlementTier.FREE)
            assertFreeUi(manager.uiState.value, harmanTarget = true)
            val frame = output(manager); assertFree(frame, harmanTarget = true)
            assertEquals(-29f, frame.targetDeltas31Db!![0], 0.0001f)
            runCurrent(); assertFreeUi(manager.uiState.value, harmanTarget = true)
            assertEquals(before, prefs.all)
        } finally { manager.close() }
    }

    @Test fun immediateFreeUiCancelsCaptureAndRegrantDoesNotResumeOldAccumulator() = runTest {
        val prefs = preferences(); val before = prefs.all.toMap()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)
        try {
            manager.updateCaptureAuthority(authority()); runCurrent()
            assertEquals(FeatureAccessResult.Allowed, manager.startNoiseFloorCapture(60_000L, authority()))
            output(manager)
            assertTrue(manager.uiState.value.isCapturingNoiseFloor)
            entitlements.setTier(EntitlementTier.FREE)
            val free = manager.uiState.value
            assertFreeUi(free); assertFalse(free.isCapturingNoiseFloor)
            assertEquals(0f, free.noiseCaptureProgress, 0f)
            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            assertProUi(manager.uiState.value)
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            runCurrent(); assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertArrayEquals(FloatArray(31) { -50f }, manager.uiState.value.noiseFloor.bands31Db, 0f)
            assertEquals(before, prefs.all)
        } finally { manager.close() }
    }

    private class PausedEntitlementRead : EntitlementRepository {
        val backing = MutableStateFlow(EntitlementState(EntitlementTier.PRO_LIFETIME))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        @Volatile var pausedThread: Thread? = null
        override val state: StateFlow<EntitlementState> = object : StateFlow<EntitlementState> by backing {
            override val value: EntitlementState get() {
                val sampled = backing.value
                if (Thread.currentThread() === pausedThread && entered.count > 0L) {
                    entered.countDown()
                    check(resume.await(8, TimeUnit.SECONDS)) { "Entitlement read was not released" }
                }
                return sampled
            }
        }
    }

    @Test fun unchangedEntitlementUiReadDoesNotWaitForPausedAuthorityPublisher() = runTest {
        val prefs = preferences(); val before = prefs.all.toMap(); val entitlements = PausedEntitlementRead()
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)
        val failure = AtomicReference<Throwable>(); val readResult = AtomicReference<CalibrationUiState>()
        val readDone = CountDownLatch(1)
        val updater = Thread({ try { manager.updateCaptureAuthority(authority("B", 4)) } catch (t: Throwable) { failure.set(t) } }, "paused-authority-publisher")
        val reader = Thread({ try { readResult.set(manager.uiState.value) } catch (t: Throwable) { failure.set(t) } finally { readDone.countDown() } }, "unchanged-ui-reader")
        try {
            manager.updateCaptureAuthority(authority()); runCurrent()
            val oldUi = manager.uiState.value
            entitlements.pausedThread = updater; updater.start()
            assertTrue(entitlements.entered.await(8, TimeUnit.SECONDS))
            // The updater holds the manager state lock while current B and published A overlap.
            reader.start()
            assertTrue("An unchanged entitlement UI getter cannot acquire the held state lock", readDone.await(3, TimeUnit.SECONDS))
            assertEquals(oldUi, readResult.get())
            // Fresh B must still reject the saved A floor while publication is paused (F3).
            assertArrayEquals(FloatArray(31) { 63f }, output(manager, "B", 4).calibrated31Db, 0f)
            entitlements.resume.countDown(); updater.join(8000); reader.join(8000)
            assertFalse(updater.isAlive); assertFalse(reader.isAlive); failure.get()?.let { throw it }
            assertProUi(manager.uiState.value, noiseEffective = false)
            assertEquals(before, prefs.all)
        } finally { entitlements.resume.countDown(); updater.join(8000); reader.join(8000); manager.close() }
    }

    private fun delayedReadCannotOverwrite(kind: String) = runTest {
        val prefs = preferences(); val before = prefs.all.toMap(); val entitlements = PausedEntitlementRead()
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)
        val result = AtomicReference<CalibrationUiState>(); val failure = AtomicReference<Throwable>()
        val retainedFlow = manager.uiState
        val reader = Thread({ try { result.set(retainedFlow.value) } catch (t: Throwable) { failure.set(t) } }, "delayed-ui-$kind")
        try {
            manager.updateCaptureAuthority(authority()); runCurrent()
            entitlements.pausedThread = reader; reader.start()
            assertTrue("UI reads must consult current entitlement", entitlements.entered.await(8, TimeUnit.SECONDS))
            // Complete transitions while the old getter is paused: it must hold no manager lock.
            entitlements.backing.value = EntitlementState(EntitlementTier.FREE)
            if (kind == "stop") manager.updateCaptureAuthority(authority(running = false))
            if (kind == "route") manager.updateCaptureAuthority(authority("B", 4))
            val newer = retainedFlow.value
            assertFreeUi(newer)
            runCurrent(); assertFreeUi(retainedFlow.value)
            entitlements.resume.countDown(); reader.join(8000)
            assertFalse("A delayed entitlement read must complete without lock inversion", reader.isAlive)
            failure.get()?.let { throw it }
            assertEquals("An older sampled Pro value cannot overwrite newer Free/authority UI", newer, result.get())
            assertEquals(newer, retainedFlow.value)
            assertFree(output(manager, if (kind == "route") "B" else "A", if (kind == "route") 4 else 3))
            assertEquals(before, prefs.all)
        } finally { entitlements.resume.countDown(); reader.join(8000); manager.close() }
    }

    @Test fun delayedUiEntitlementReadCannotOverwriteFreeObserverPublication() = delayedReadCannotOverwrite("revoke")
    @Test fun delayedUiEntitlementReadCannotOverwriteStoppedPublication() = delayedReadCannotOverwrite("stop")
    @Test fun delayedUiEntitlementReadCannotOverwriteCurrentRoutePublication() = delayedReadCannotOverwrite("route")
}
