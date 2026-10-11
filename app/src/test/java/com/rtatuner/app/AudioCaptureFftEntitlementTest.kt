package com.rtatuner.app

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.rtatuner.app.core.audio.CaptureStatus
import com.rtatuner.app.core.audio.FftDisplayMode
import com.rtatuner.app.core.database.SessionSnapshotEntity
import com.rtatuner.app.core.database.TuningSessionEntity
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.core.entitlement.ProFeature
import com.rtatuner.app.ui.AudioCaptureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowAudioCaptureManager::class],
    instrumentedPackages = ["com.rtatuner.app.core.audio"])
class AudioCaptureFftEntitlementTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var entitlements: FakeEntitlementRepository
    private lateinit var model: AudioCaptureViewModel
    private lateinit var store: ViewModelStore
    private val managers get() = ShadowAudioCaptureManager.instances
    private val currentManager get() = managers.last()

    @Before
    fun createModel() {
        Dispatchers.setMain(dispatcher)
        managers.clear()
        application = RuntimeEnvironment.getApplication()
        entitlements = FakeEntitlementRepository()
        replaceModel(AudioCaptureViewModel(application, entitlements))
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun clearModel() {
        store.clear()
        Dispatchers.resetMain()
        managers.clear()
    }

    private fun replaceModel(next: AudioCaptureViewModel) {
        model = next
        store = ViewModelStore().apply { put("fft-entitlement", model) }
    }

    private fun grantPro() {
        entitlements.setTier(EntitlementTier.PRO_LIFETIME)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun freeDefaultsTo2048AndCanSelect1024And2048WithoutDuplicateRecreation() {
        val initial = currentManager
        assertEquals(2048, model.fftWindowSize.value)
        assertEquals(2048, initial.constructedConfig.windowSize)
        assertEquals(FeatureAccessResult.Allowed, model.setWindowSize(2048))
        assertSame(initial, currentManager)
        assertEquals(0, initial.releaseCalls)

        assertEquals(FeatureAccessResult.Allowed, model.setWindowSize(1024))
        assertEquals(1024, currentManager.constructedConfig.windowSize)
        assertEquals(512, currentManager.constructedConfig.hopSize)
        assertEquals(1, initial.releaseCalls)
        assertEquals(FeatureAccessResult.Allowed, model.setWindowSize(2048))
        assertEquals(2048, currentManager.constructedConfig.windowSize)
        assertEquals(3, managers.size)
        assertNull(model.accessDenial.value)
        assertTrue(managers.all { it.startCalls == 0 })
    }

    @Test
    fun free4096IsDeniedBeforeAnyCaptureOrMeasurementMutation() {
        model.setWindowSize(1024)
        model.setPreferredInputDevice(73)
        model.setFftDisplayMode(FftDisplayMode.RAW_FFT)
        model.startCapture()
        dispatcher.scheduler.runCurrent()
        assertEquals(FeatureAccessResult.Allowed, model.freezeCurrentSnapshot("Retained"))
        val manager = currentManager
        val ui = model.uiState.value
        val snapshots = model.frozenSnapshots.value
        val managerCount = managers.size
        val result = model.setWindowSize(4096)

        assertTrue(result is FeatureAccessResult.Denied)
        assertEquals(ProFeature.FFT_4096, (result as FeatureAccessResult.Denied).feature)
        assertEquals(result, model.accessDenial.value)
        assertEquals(managerCount, managers.size)
        assertSame(manager, currentManager)
        assertEquals(0, manager.releaseCalls)
        assertEquals(0, manager.stopCalls)
        assertEquals(1, manager.startCalls)
        assertEquals(73, manager.preferredDeviceId)
        assertEquals(CaptureStatus.RUNNING, manager.state.value.status)
        assertEquals(1024, model.fftWindowSize.value)
        assertEquals(ui, model.uiState.value)
        assertEquals(snapshots, model.frozenSnapshots.value)
    }

    @Test
    fun pro4096IsAllowedAndSameWindowDoesNotRestartTheManager() {
        grantPro()
        model.startCapture()
        // The manager reports RUNNING before the ViewModel's flow collector has resumed.
        assertEquals(FeatureAccessResult.Allowed, model.setWindowSize(4096))
        assertEquals(4096, model.fftWindowSize.value)
        val manager = currentManager
        assertEquals(4096, manager.constructedConfig.windowSize)
        assertEquals(2048, manager.constructedConfig.hopSize)
        assertEquals(CaptureStatus.RUNNING, manager.state.value.status)
        assertEquals(1, manager.startCalls)
        assertEquals(FeatureAccessResult.Allowed, model.setWindowSize(4096))
        assertSame(manager, currentManager)
        assertEquals(0, manager.releaseCalls)
        assertEquals(1, manager.startCalls)
        assertNull(model.accessDenial.value)
    }

    @Test
    fun runningProDowngradeReconfiguresOnceAndPreservesRoutingDisplayAnd4096Snapshots() {
        grantPro()
        model.setPreferredInputDevice(73)
        model.setFftDisplayMode(FftDisplayMode.RAW_FFT)
        model.setWindowSize(4096)
        model.startCapture()
        dispatcher.scheduler.runCurrent()
        model.freezeCurrentSnapshot("Historical 4096")
        val retained = model.frozenSnapshots.value
        val oldManager = currentManager
        val before = managers.size

        entitlements.setTier(EntitlementTier.FREE)
        dispatcher.scheduler.runCurrent()

        assertEquals(2048, model.fftWindowSize.value)
        assertEquals(before + 1, managers.size)
        assertEquals(1, oldManager.releaseCalls)
        assertEquals(2048, currentManager.constructedConfig.windowSize)
        assertEquals(73, currentManager.constructedConfig.preferredDeviceId)
        assertEquals(73, model.uiState.value.selectedDeviceId)
        assertEquals(73, model.uiState.value.routedDeviceId)
        assertEquals(CaptureStatus.RUNNING, model.uiState.value.status)
        assertEquals(FftDisplayMode.RAW_FFT, model.uiState.value.displayMode)
        assertEquals(FftDisplayMode.RAW_FFT, currentManager.state.value.displayMode)
        assertEquals(1, currentManager.startCalls)
        assertEquals(retained, model.frozenSnapshots.value)
        assertEquals(4096, retained.single().windowSize)
        assertNull(model.accessDenial.value)
        dispatcher.scheduler.runCurrent()
        assertEquals(before + 1, managers.size)
    }

    @Test
    fun historical4096RoomDataRemainsViewableAndDoesNotRestoreRestrictedProcessing() = runBlocking {
        grantPro()
        model.setWindowSize(4096)
        val id = model.sessionRepository.saveSession(
            TuningSessionEntity(name = "Historical 4096", notes = "Keep these notes", windowSize = 4096),
            listOf(SessionSnapshotEntity(sessionId = 0, name = "Measured at 4096", bands31Db = FloatArray(31) { -37f }))
        )
        val historical = model.sessionRepository.getSessionWithSnapshots(id)!!
        store.clear()
        entitlements.setTier(EntitlementTier.FREE)
        val restoredCount = managers.size
        replaceModel(AudioCaptureViewModel(application, entitlements))
        dispatcher.scheduler.runCurrent()

        assertEquals(2048, model.fftWindowSize.value)
        assertEquals(2048, currentManager.constructedConfig.windowSize)
        assertEquals(restoredCount + 1, managers.size)
        assertEquals(historical, model.sessionRepository.getSessionWithSnapshots(id))
        assertEquals(FeatureAccessResult.Allowed, model.loadSessionSnapshotsToOverlay(historical))
        val overlay = model.frozenSnapshots.value.single()
        assertEquals(4096, overlay.windowSize)
        assertTrue(overlay.bands31Db.contentEquals(historical.snapshots.single().bands31Db))
        assertEquals("Keep these notes", historical.session.notes)
        assertEquals(2048, model.fftWindowSize.value)
        assertEquals(restoredCount + 1, managers.size)
        assertTrue(model.setWindowSize(historical.session.windowSize) is FeatureAccessResult.Denied)
        assertEquals(historical, model.sessionRepository.getSessionWithSnapshots(id))
    }

    @Test
    fun rapidWindowAndEntitlementChangesUseLatestPolicyAndAvoidRedundantRestarts() {
        grantPro()
        model.startCapture()
        model.setWindowSize(4096)
        val manager = currentManager
        val before = managers.size
        entitlements.setTier(EntitlementTier.FREE)
        entitlements.setTier(EntitlementTier.PRO_LIFETIME)
        dispatcher.scheduler.runCurrent()
        assertSame(manager, currentManager)
        assertEquals(before, managers.size)
        assertEquals(4096, model.fftWindowSize.value)

        entitlements.setTier(EntitlementTier.FREE)
        // Check authorization before the same-size optimization, even with a delayed collector.
        assertTrue(model.setWindowSize(4096) is FeatureAccessResult.Denied)
        assertSame(manager, currentManager)
        assertEquals(0, manager.releaseCalls)
        assertEquals(FeatureAccessResult.Allowed, model.setWindowSize(1024))
        assertEquals(FeatureAccessResult.Allowed, model.setWindowSize(1024))
        dispatcher.scheduler.runCurrent()
        assertEquals(1024, model.fftWindowSize.value)
        assertEquals(before + 1, managers.size)
        assertEquals(1, manager.releaseCalls)

        grantPro()
        model.setWindowSize(4096)
        model.setWindowSize(2048)
        entitlements.setTier(EntitlementTier.FREE)
        dispatcher.scheduler.runCurrent()
        assertEquals(before + 3, managers.size)
        assertEquals(2048, model.fftWindowSize.value)
        assertEquals(CaptureStatus.RUNNING, currentManager.state.value.status)
        assertTrue(managers.dropLast(1).all { it.releaseCalls == 1 })
    }

    @Test
    fun startingBeforePendingDowngradeCollectorCannotRestart4096() {
        grantPro()
        model.setWindowSize(4096)
        val oldManager = currentManager
        entitlements.setTier(EntitlementTier.FREE)

        model.startCapture()

        assertEquals(0, oldManager.startCalls)
        assertEquals(1, oldManager.releaseCalls)
        assertEquals(2048, currentManager.constructedConfig.windowSize)
        assertEquals(1, currentManager.startCalls)
        val beforeCollector = managers.size
        dispatcher.scheduler.runCurrent()
        assertEquals(beforeCollector, managers.size)
        assertEquals(2048, model.fftWindowSize.value)
    }

    @Test
    fun revocationDuringReleaseCannotConstructOrStartA4096CaptureManager() {
        grantPro()
        model.setWindowSize(1024)
        model.setPreferredInputDevice(73)
        model.startCapture()
        val oldManager = currentManager
        val before = managers.size
        oldManager.onRelease = { entitlements.setTier(EntitlementTier.FREE) }

        assertTrue(model.setWindowSize(4096) is FeatureAccessResult.Denied)

        assertEquals(1, oldManager.releaseCalls)
        assertEquals(before + 1, managers.size)
        assertEquals(2048, model.fftWindowSize.value)
        assertEquals(2048, currentManager.constructedConfig.windowSize)
        assertEquals(73, currentManager.constructedConfig.preferredDeviceId)
        assertEquals(CaptureStatus.RUNNING, currentManager.state.value.status)
        assertEquals(1, currentManager.startCalls)
        assertTrue(managers.none { it.constructedConfig.windowSize == 4096 })
        dispatcher.scheduler.runCurrent()
        assertEquals(before + 1, managers.size)
    }

    @Test
    fun retryBeforePendingDowngradeCollectorUsesOnlyTheSafeWindow() {
        grantPro()
        model.setWindowSize(4096)
        model.startCapture()
        val oldManager = currentManager
        entitlements.setTier(EntitlementTier.FREE)

        model.retryCapture()

        assertEquals(1, oldManager.startCalls)
        assertEquals(1, oldManager.releaseCalls)
        assertEquals(2048, currentManager.constructedConfig.windowSize)
        assertEquals(CaptureStatus.RUNNING, currentManager.state.value.status)
        assertEquals(2048, model.fftWindowSize.value)
        val afterRetry = managers.size
        dispatcher.scheduler.runCurrent()
        assertEquals(afterRetry, managers.size)
    }

    @Test
    fun preferredRoutingBeforePendingDowngradeCollectorPreservesNewMicrophoneOnTheSafeWindow() {
        grantPro()
        model.setPreferredInputDevice(73)
        model.setWindowSize(4096)
        model.startCapture()
        val oldManager = currentManager
        entitlements.setTier(EntitlementTier.FREE)

        model.setPreferredInputDevice(81)

        assertEquals(1, oldManager.releaseCalls)
        assertEquals(2048, currentManager.constructedConfig.windowSize)
        assertEquals(81, currentManager.preferredDeviceId)
        assertEquals(CaptureStatus.RUNNING, currentManager.state.value.status)
        dispatcher.scheduler.runCurrent()
        assertEquals(81, model.uiState.value.selectedDeviceId)
        assertEquals(81, model.uiState.value.routedDeviceId)
        assertEquals(2048, model.fftWindowSize.value)
    }

    @Test
    fun defaultAppFactoryRestricts4096InBothDebugAndReleaseVariants() {
        store.clear()
        replaceModel(AudioCaptureViewModel(application))
        dispatcher.scheduler.runCurrent()
        val manager = currentManager
        val before = managers.size
        assertFalse(model.entitlementState.value.isPro)
        assertTrue(model.setWindowSize(4096) is FeatureAccessResult.Denied)
        assertEquals(ProFeature.FFT_4096, model.accessDenial.value!!.feature)
        assertSame(manager, currentManager)
        assertEquals(before, managers.size)
        assertEquals(0, manager.releaseCalls)
        assertEquals(2048, model.fftWindowSize.value)
    }
}
