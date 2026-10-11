package com.rtatuner.app

import android.content.ContextWrapper
import android.content.Intent
import android.view.SurfaceView
import com.rtatuner.app.core.database.SessionSnapshotEntity
import com.rtatuner.app.core.database.SessionWithSnapshots
import com.rtatuner.app.core.database.TuningSessionEntity
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessDeniedException
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.ProFeature
import com.rtatuner.app.core.export.RtaExportManager
import com.rtatuner.app.core.session.RtaSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileProviderPathStrategyShadow::class], instrumentedPackages = ["androidx.core.content"])
class RtaExportEntitlementTest {
    @Before
    fun installProductionProvider() {
        installCsvFileProvider(RuntimeEnvironment.getApplication())
    }

    @Test
    fun allCsvSourcesAreRejectedBeforeTouchingContextOrWritingFilesByDefault() {
        var contextAccesses = 0
        val context = object : ContextWrapper(null) {
            override fun getCacheDir(): File {
                contextAccesses++
                throw AssertionError("Denied exports must not touch the Android cache")
            }
        }

        csvSources().forEach { (name, csv) ->
            val failure = runCatching { RtaExportManager.shareCsv(context, csv, name) }.exceptionOrNull()
            assertTrue("$name CSV must reject Free", failure is FeatureAccessDeniedException)
            assertEquals(ProFeature.CSV_EXPORT, (failure as FeatureAccessDeniedException).denial.feature)
        }
        assertEquals(0, contextAccesses)
    }

    @Test
    fun proCanShareLiveSnapshotAndSavedSessionCsvThroughTheSharedActionBoundary() {
        val application = RuntimeEnvironment.getApplication()
        val policy = FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME))

        csvSources().forEach { (name, csv) ->
            RtaExportManager.shareCsv(application, csv, name, policy)
            val chooser = shadowOf(application).nextStartedActivity
            assertNotNull("$name opens a share chooser", chooser)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val share = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals("text/csv", share.type)
            val exported = File(application.cacheDir, "exports").listFiles()!!
                .single { it.name.startsWith("${name}_") && it.extension == "csv" }
            assertEquals(csv, exported.readText())
        }
    }

    @Test
    fun entitlementTransitionsChangeCsvPermissionsWithoutRemovingExistingExports() {
        val application = RuntimeEnvironment.getApplication()
        val entitlements = FakeEntitlementRepository()
        val policy = FeatureAccessPolicy(entitlements)
        val csv = csvSources().first().second
        assertTrue(runCatching { RtaExportManager.shareCsv(application, csv, "first", policy) }
            .exceptionOrNull() is FeatureAccessDeniedException)
        assertNull(shadowOf(application).nextStartedActivity)

        entitlements.setTier(EntitlementTier.PRO_LIFETIME)
        RtaExportManager.shareCsv(application, csv, "permitted", policy)
        assertNotNull(shadowOf(application).nextStartedActivity)
        val exportDir = File(application.cacheDir, "exports")
        val previousFiles = exportDir.listFiles()!!.map { it.name }.toSet()

        entitlements.setTier(EntitlementTier.FREE)
        assertTrue(runCatching { RtaExportManager.shareCsv(application, csv, "revoked", policy) }
            .exceptionOrNull() is FeatureAccessDeniedException)
        assertEquals(previousFiles, exportDir.listFiles()!!.map { it.name }.toSet())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun pngSharingEntryPointRemainsAccessibleWithoutPro() {
        val application = RuntimeEnvironment.getApplication()
        var completed = false
        var succeeded = true
        // No rendered pixels yet: Free reaches the existing size validation and callback.
        RtaExportManager.captureAndShareSurfacePng(SurfaceView(application), application, "Free PNG") {
            completed = true
            succeeded = it
        }
        assertTrue(completed)
        assertFalse(succeeded)
    }

    private fun csvSources(): List<Pair<String, String>> {
        val bands = FloatArray(31) { -40f }
        return listOf(
            "live" to RtaExportManager.formatLiveCurveCsv(
                bands, null, "Harman", false, 0f, 2048, false
            ),
            "snapshot" to RtaExportManager.formatSnapshotCsv(RtaSnapshot(name = "Frozen", bands31Db = bands)),
            "session" to RtaExportManager.formatSessionWithSnapshotsCsv(
                SessionWithSnapshots(
                    TuningSessionEntity(id = 1, name = "Saved"),
                    listOf(SessionSnapshotEntity(sessionId = 1, name = "Stored", bands31Db = bands))
                )
            )
        )
    }
}
