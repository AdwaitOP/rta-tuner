package com.rtatuner.app

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessDeniedException
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.export.CsvShareException
import com.rtatuner.app.core.export.RtaExportManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileProviderPathStrategyShadow::class], instrumentedPackages = ["androidx.core.content"])
class RtaExportFailureTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val proPolicy = FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME))

    @Before
    fun installProductionProvider() {
        installCsvFileProvider(application)
    }

    @Test
    fun successfulInitiationClosesStreamAndRetainsFileForChooser() {
        var closed = false
        var createdFile: File? = null
        RtaExportManager.shareCsv(application, CSV, "successful", proPolicy) { file ->
            createdFile = file
            object : FilterOutputStream(FileOutputStream(file)) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }

        assertTrue(closed)
        assertEquals(CSV, createdFile!!.readText())
        val chooser = shadowOf(application).nextStartedActivity
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val share = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("text/csv", share.type)
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // Returning confirms only that Android was asked to show its chooser.
        assertTrue(createdFile!!.exists())
    }

    @Test
    fun deniedAccessDoesNotTouchContextOrOpenAnOutputStream() {
        var cacheAccessed = false
        var streamOpened = false
        val context = object : ContextWrapper(application) {
            override fun getCacheDir(): File {
                cacheAccessed = true
                throw AssertionError("Free must be rejected before file I/O")
            }
        }

        val failure = runCatching {
            RtaExportManager.shareCsv(context, CSV, "denied", FeatureAccessPolicy()) {
                streamOpened = true
                throw AssertionError("Free must be rejected before file I/O")
            }
        }.exceptionOrNull()

        assertTrue(failure is FeatureAccessDeniedException)
        assertFalse(cacheAccessed)
        assertFalse(streamOpened)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun directoryCreationFailurePropagatesWithoutRemovingExistingData() {
        val blockedCache = File(application.cacheDir, "blocked-cache").apply { mkdirs() }
        val existing = File(blockedCache, "exports").apply { writeText("existing cache data") }
        val context = object : ContextWrapper(application) {
            override fun getCacheDir(): File = blockedCache
        }

        val failure = runCatching {
            RtaExportManager.shareCsv(context, CSV, "failed-directory", proPolicy)
        }.exceptionOrNull()

        assertTrue(failure is CsvShareException)
        assertTrue(failure!!.cause is IOException)
        assertEquals("existing cache data", existing.readText())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun collidingFileIsNeitherOverwrittenNorDeleted() {
        val exportDir = exportDirectory()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val now = System.currentTimeMillis()
        // Cover second rollover without altering the filename convention or clock.
        val previousFiles = (-5L..5L).map { seconds ->
            File(exportDir, "collision_${timestamp.format(Date(now + seconds * 1000))}.csv")
                .apply { writeText("previous export") }
        }
        val previousNames = exportDir.listFiles()!!.map { it.name }.toSet()

        val failure = runCatching {
            RtaExportManager.shareCsv(application, CSV, "collision", proPolicy)
        }.exceptionOrNull()

        assertTrue(failure is CsvShareException)
        assertTrue(failure!!.cause is IOException)
        previousFiles.forEach { assertEquals("previous export", it.readText()) }
        assertEquals(previousNames, exportDir.listFiles()!!.map { it.name }.toSet())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun streamOpenFailureRemovesOnlyNewFileAndPreservesTechnicalCause() {
        val previous = previousExport()
        val failureCause = IOException("simulated stream creation failure")
        var createdFile: File? = null

        val failure = runCatching {
            RtaExportManager.shareCsv(application, CSV, "failed-open", proPolicy) { file ->
                createdFile = file
                throw failureCause
            }
        }.exceptionOrNull()

        assertShareFailure(failure, failureCause, createdFile!!, previous)
    }

    @Test
    fun partialWriteFailureClosesStreamAndRemovesOnlyNewFile() {
        val previous = previousExport()
        val failureCause = IOException("simulated CSV write failure")
        var closed = false
        var createdFile: File? = null

        val failure = runCatching {
            RtaExportManager.shareCsv(application, CSV, "failed-write", proPolicy) { file ->
                createdFile = file
                object : FilterOutputStream(FileOutputStream(file)) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        out.write(bytes, offset, minOf(2, length))
                        throw failureCause
                    }

                    override fun close() {
                        closed = true
                        super.close()
                    }
                }
            }
        }.exceptionOrNull()

        assertTrue(closed)
        assertShareFailure(failure, failureCause, createdFile!!, previous)
    }

    @Test
    fun uriGenerationFailureRemovesOnlyNewFileAndDoesNotLaunchChooser() {
        val previous = previousExport()
        val previousNames = exportDirectory().listFiles()!!.map { it.name }.toSet()
        val context = object : ContextWrapper(application) {
            override fun getPackageName(): String = "com.rtatuner.missingprovider"
        }

        val failure = runCatching {
            RtaExportManager.shareCsv(context, CSV, "failed-uri", proPolicy)
        }.exceptionOrNull()

        assertTrue(failure is CsvShareException)
        assertTrue(failure!!.cause is IllegalArgumentException)
        assertEquals(previousNames, exportDirectory().listFiles()!!.map { it.name }.toSet())
        assertEquals("previous user export", previous.readText())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun chooserLaunchFailureRemovesOnlyNewFileAndPreservesTechnicalCause() {
        val previous = previousExport()
        val previousNames = exportDirectory().listFiles()!!.map { it.name }.toSet()
        val failureCause = ActivityNotFoundException("simulated chooser launch failure")
        var attemptedChooser: Intent? = null
        val context = object : ContextWrapper(application) {
            override fun startActivity(intent: Intent) {
                attemptedChooser = intent
                throw failureCause
            }
        }

        val failure = runCatching {
            RtaExportManager.shareCsv(context, CSV, "failed-chooser", proPolicy)
        }.exceptionOrNull()

        assertTrue(failure is CsvShareException)
        assertSame(failureCause, failure!!.cause)
        assertSame(failureCause, ShadowLog.getLogsForTag("RtaExportManager").last().throwable)
        assertEquals(Intent.ACTION_CHOOSER, attemptedChooser!!.action)
        assertEquals(previousNames, exportDirectory().listFiles()!!.map { it.name }.toSet())
        assertEquals("previous user export", previous.readText())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    private fun assertShareFailure(failure: Throwable?, cause: Exception, created: File, previous: File) {
        assertTrue(failure is CsvShareException)
        assertSame(cause, failure!!.cause)
        assertSame(cause, ShadowLog.getLogsForTag("RtaExportManager").last().throwable)
        assertFalse(created.exists())
        assertEquals("previous user export", previous.readText())
        assertNull(shadowOf(application).nextStartedActivity)
    }

    private fun exportDirectory() = File(application.cacheDir, "exports").apply { mkdirs() }

    private fun previousExport() = File(exportDirectory(), "previous.csv").apply {
        writeText("previous user export")
    }

    private companion object {
        const val CSV = "Frequency_Hz,Level_dB\n1000,-40.00\n"
    }
}
