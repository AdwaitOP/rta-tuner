package com.rtatuner.app

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import com.rtatuner.app.core.database.SessionSnapshotEntity
import com.rtatuner.app.core.database.SessionWithSnapshots
import com.rtatuner.app.core.database.TuningSessionEntity
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.core.entitlement.ProFeature
import com.rtatuner.app.core.export.CsvExportResult
import com.rtatuner.app.core.session.RtaSnapshot
import com.rtatuner.app.ui.AudioCaptureViewModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Regression tests exercise the actual application entry points used by Compose. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileProviderPathStrategyShadow::class], instrumentedPackages = ["androidx.core.content"])
class AudioCaptureEntitlementIntegrationTest {
    private lateinit var application: Application
    private lateinit var entitlements: FakeEntitlementRepository
    private lateinit var model: AudioCaptureViewModel
    private lateinit var store: ViewModelStore
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun createModel() {
        Dispatchers.setMain(dispatcher)
        application = RuntimeEnvironment.getApplication()
        installCsvFileProvider(application)
        entitlements = FakeEntitlementRepository()
        model = AudioCaptureViewModel(application, entitlements)
        store = ViewModelStore().apply { put("entitlement-test", model) }
    }

    @After
    fun clearModel() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun liveSnapshotAndSessionCsvApplicationActionsRejectFreeBeforeContextAccess() {
        var contextAccesses = 0
        val forbiddenContext = object : ContextWrapper(null) {
            override fun getCacheDir(): File {
                contextAccesses++
                throw AssertionError("Unauthorized application export reached cache")
            }
        }
        val initialUi = model.uiState.value
        val outcomes = listOf(
            model.exportLiveCsv(forbiddenContext),
            model.exportSnapshotCsv(forbiddenContext, RtaSnapshot(name = "Frozen", bands31Db = FloatArray(31))),
            model.exportSessionCsv(forbiddenContext, savedSession())
        )

        outcomes.forEach { result ->
            assertTrue(result is CsvExportResult.AccessDenied)
            assertEquals(ProFeature.CSV_EXPORT, (result as CsvExportResult.AccessDenied).denial.feature)
        }
        assertEquals(0, contextAccesses)
        assertEquals(initialUi, model.uiState.value)
        assertTrue(model.frozenSnapshots.value.isEmpty())
        assertEquals(ProFeature.CSV_EXPORT, model.accessDenial.value!!.feature)
    }

    @Test
    fun allApplicationCsvActionsObserveFreeProAndFreeTransitions() {
        val snapshot = RtaSnapshot(name = "Frozen", bands31Db = FloatArray(31) { -30f })
        val session = savedSession()
        val actions = listOf<() -> CsvExportResult>(
            { model.exportLiveCsv(application) },
            { model.exportSnapshotCsv(application, snapshot) },
            { model.exportSessionCsv(application, session) }
        )
        actions.forEach { assertTrue(it() is CsvExportResult.AccessDenied) }

        entitlements.setTier(EntitlementTier.PRO_LIFETIME)
        dispatcher.scheduler.runCurrent()
        actions.forEach { action ->
            assertEquals(CsvExportResult.ShareChooserLaunched, action())
            assertNotNull(shadowOf(application).nextStartedActivity)
        }
        val previousFiles = File(application.cacheDir, "exports").listFiles()!!.map { it.name }.toSet()
        assertEquals(3, previousFiles.size)

        entitlements.setTier(EntitlementTier.FREE)
        dispatcher.scheduler.runCurrent()
        actions.forEach { assertTrue(it() is CsvExportResult.AccessDenied) }
        assertEquals(previousFiles, File(application.cacheDir, "exports").listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun allCsvActionsPropagateCreationFailuresAndQueueFriendlyErrorsWithoutProDetails() = runBlocking {
        entitlements.setTier(EntitlementTier.PRO_LIFETIME)
        val cause = IOException("Simulated unavailable cache")
        val context = object : ContextWrapper(application) {
            override fun getCacheDir(): File = throw cause
        }
        val actions = csvActions(context)
        actions.forEach { action ->
            val outcome = action()
            assertTrue(outcome is CsvExportResult.Failed)
            assertSame(cause, (outcome as CsvExportResult.Failed).cause)
        }
        val messages = withTimeout(1000) { model.csvExportErrors.take(actions.size).toList() }
        assertEquals(List(3) { "Could not share CSV. Please try again." }, messages)
        assertNull(model.accessDenial.value)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun allCsvActionsPropagateUriFailuresWithoutShowingAnUpgradeSheet() = runBlocking {
        entitlements.setTier(EntitlementTier.PRO_LIFETIME)
        val context = object : ContextWrapper(application) {
            override fun getPackageName(): String = "missing.csv.provider"
        }
        csvActions(context).forEach { action ->
            val outcome = action()
            assertTrue(outcome is CsvExportResult.Failed)
            assertTrue((outcome as CsvExportResult.Failed).cause is IllegalArgumentException)
        }
        assertEquals(3, withTimeout(1000) { model.csvExportErrors.take(3).toList() }.size)
        assertNull(model.accessDenial.value)
        assertNull(shadowOf(application).nextStartedActivity)
        assertTrue(File(application.cacheDir, "exports").listFiles()!!.isEmpty())
    }

    @Test
    fun allCsvActionsReportChooserLaunchFailuresAndKeepPreviousExports() = runBlocking {
        entitlements.setTier(EntitlementTier.PRO_LIFETIME)
        val exports = File(application.cacheDir, "exports").apply { mkdirs() }
        val previous = File(exports, "previous.csv").apply { writeText("Keep prior user data") }
        val cause = android.content.ActivityNotFoundException("Simulated unavailable chooser")
        val context = object : ContextWrapper(application) {
            override fun startActivity(intent: Intent) { throw cause }
        }
        csvActions(context).forEach { action ->
            val outcome = action()
            assertTrue(outcome is CsvExportResult.Failed)
            assertSame(cause, (outcome as CsvExportResult.Failed).cause)
        }
        assertEquals(3, withTimeout(1000) { model.csvExportErrors.take(3).toList() }.size)
        assertNull(model.accessDenial.value)
        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals(listOf(previous.name), exports.listFiles()!!.map { it.name })
        assertEquals("Keep prior user data", previous.readText())
    }

    @Test
    fun deniedAndInitiatedCsvActionsDoNotPublishFailureMessages() = runBlocking {
        val error = async(start = CoroutineStart.UNDISPATCHED) { model.csvExportErrors.first() }
        try {
            csvActions(application).forEach { assertTrue(it() is CsvExportResult.AccessDenied) }
            assertFalse(error.isCompleted)
            model.dismissAccessDenial()
            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            csvActions(application).forEach { action ->
                assertEquals(CsvExportResult.ShareChooserLaunched, action())
                assertNotNull(shadowOf(application).nextStartedActivity)
            }
            assertFalse(error.isCompleted)
            assertNull(model.accessDenial.value)
        } finally { error.cancelAndJoin() }
    }

    private fun csvActions(context: android.content.Context): List<() -> CsvExportResult> = listOf(
        { model.exportLiveCsv(context) },
        { model.exportSnapshotCsv(context, RtaSnapshot(name = "Frozen", bands31Db = FloatArray(31))) },
        { model.exportSessionCsv(context, savedSession()) }
    )

    @Test
    fun liveFreezeAndSavedOverlayLoadingShareTheFreeCapacityAndRetainExistingMeasurements() {
        assertEquals(FeatureAccessResult.Allowed, model.freezeCurrentSnapshot("Keep live"))
        val existing = model.frozenSnapshots.value
        assertTrue(model.freezeCurrentSnapshot("Rejected live") is FeatureAccessResult.Denied)
        assertEquals(existing, model.frozenSnapshots.value)
        assertTrue(model.loadSessionSnapshotsToOverlay(savedSession()) is FeatureAccessResult.Denied)
        assertTrue(model.loadSessionSnapshotsToOverlay(savedSession(), listOf(11)) is FeatureAccessResult.Denied)
        assertEquals(existing, model.frozenSnapshots.value)
        assertEquals(ProFeature.UNLIMITED_OVERLAYS, model.accessDenial.value!!.feature)
    }

    @Test
    fun freeCanSelectOneSavedSnapshotWithoutTruncatingTheSession() {
        val session = savedSession()
        val originalSnapshots = session.snapshots.toList()
        assertTrue(model.loadSessionSnapshotsToOverlay(session) is FeatureAccessResult.Denied)
        assertTrue(model.frozenSnapshots.value.isEmpty())

        assertEquals(FeatureAccessResult.Allowed, model.loadSessionSnapshotsToOverlay(session, listOf(12)))
        val loaded = model.frozenSnapshots.value.single()
        assertEquals("Historical - After", loaded.name)
        assertEquals(4096, loaded.windowSize)
        assertTrue(loaded.bands31Db.contentEquals(session.snapshots[1].bands31Db))
        assertEquals(originalSnapshots, session.snapshots)
        assertTrue(model.freezeCurrentSnapshot() is FeatureAccessResult.Denied)
    }

    @Test
    fun overlayTransitionKeepsAllProMeasurementsAndBlocksNewFreeAdditions() {
        entitlements.setTier(EntitlementTier.PRO_LIFETIME)
        dispatcher.scheduler.runCurrent()
        assertEquals(FeatureAccessResult.Allowed, model.freezeCurrentSnapshot("Live"))
        assertEquals(FeatureAccessResult.Allowed, model.loadSessionSnapshotsToOverlay(savedSession()))
        assertEquals(3, model.frozenSnapshots.value.size)
        val retained = model.frozenSnapshots.value

        entitlements.setTier(EntitlementTier.FREE)
        dispatcher.scheduler.runCurrent()
        assertEquals(retained, model.frozenSnapshots.value)
        assertTrue(model.freezeCurrentSnapshot() is FeatureAccessResult.Denied)
        assertTrue(model.loadSessionSnapshotsToOverlay(savedSession(), listOf(11)) is FeatureAccessResult.Denied)
        assertEquals(retained, model.frozenSnapshots.value)
        model.unfreezeSnapshot(retained[0].id)
        model.unfreezeSnapshot(retained[1].id)
        assertTrue(model.freezeCurrentSnapshot() is FeatureAccessResult.Denied)
        model.unfreezeSnapshot(retained[2].id)
        assertEquals(FeatureAccessResult.Allowed, model.freezeCurrentSnapshot("Free again"))

        entitlements.setTier(EntitlementTier.PRO_LIFETIME)
        dispatcher.scheduler.runCurrent()
        assertEquals(FeatureAccessResult.Allowed, model.loadSessionSnapshotsToOverlay(savedSession()))
        assertEquals(3, model.frozenSnapshots.value.size)
    }

    private fun savedSession() = SessionWithSnapshots(
        TuningSessionEntity(id = 1, name = "Historical", windowSize = 4096),
        listOf(
            SessionSnapshotEntity(id = 11, sessionId = 1, name = "Before", bands31Db = FloatArray(31) { -40f }),
            SessionSnapshotEntity(id = 12, sessionId = 1, name = "After", bands31Db = FloatArray(31) { -30f })
        )
    )
}
