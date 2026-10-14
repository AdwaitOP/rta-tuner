package com.rtatuner.app

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import com.rtatuner.app.core.audio.AudioCaptureConfig
import com.rtatuner.app.core.audio.AudioCaptureManager
import com.rtatuner.app.core.audio.AudioRecordSource
import com.rtatuner.app.core.audio.AudioRecordSourceFactory
import com.rtatuner.app.core.audio.CaptureSession
import com.rtatuner.app.core.audio.CaptureStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Independent auditor harness. No use of the author's fake, helper, or assertions. */
@Implements(AudioManager::class)
class TransactionAuditAudioManagerShadow : ShadowAudioManager() {
    companion object {
        var propertyAction: ((String) -> String?)? = null
        var devicesAction: (() -> Array<AudioDeviceInfo>)? = null
    }

    @Implementation
    fun getProperty(key: String): String? = propertyAction?.invoke(key) ?: "48000"

    @Implementation
    override fun getDevices(flags: Int): Array<AudioDeviceInfo> =
        devicesAction?.invoke() ?: emptyArray()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [TransactionAuditAudioManagerShadow::class])
class IndependentTransactionResourceAuditTest {
    @Before
    fun resetShadow() {
        TransactionAuditAudioManagerShadow.propertyAction = null
        TransactionAuditAudioManagerShadow.devicesAction = null
    }

    @Suppress("DEPRECATION")
    private class Record : AudioRecordSource {
        var stateAction: () -> Int = { AudioRecord.STATE_INITIALIZED }
        var recordingAction: () -> Int = { AudioRecord.RECORDSTATE_RECORDING }
        var startAction: () -> Unit = {}
        var stopAction: () -> Unit = {}
        var releaseAction: () -> Unit = {}
        var addAction: () -> Unit = {}
        var removeAction: () -> Unit = {}
        var preferredAction: () -> Unit = {}
        var readAction: () -> Int = { AudioRecord.ERROR_INVALID_OPERATION }
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val releases = AtomicInteger()
        val additions = AtomicInteger()
        val removals = AtomicInteger()
        override val state get() = stateAction()
        override val recordingState get() = recordingAction()
        override val routedDevice: AudioDeviceInfo? get() = null
        override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean { preferredAction(); return true }
        override fun addOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener, handler: Handler?) {
            additions.incrementAndGet(); addAction()
        }
        override fun removeOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener) {
            removals.incrementAndGet(); removeAction()
        }
        override fun startRecording() { starts.incrementAndGet(); startAction() }
        override fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, readMode: Int): Int = readAction()
        override fun stop() { stops.incrementAndGet(); stopAction() }
        override fun release() { releases.incrementAndGet(); releaseAction() }
    }

    private fun manager(
        factory: AudioRecordSourceFactory,
        config: AudioCaptureConfig = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
        min: (Int, Int, Int) -> Int = { _, _, _ -> 4096 }
    ): AudioCaptureManager = AudioCaptureManager(
        RuntimeEnvironment.getApplication(), config, factory, min
    ).also { it.threadLauncher = {} }

    private fun manager(record: Record): AudioCaptureManager = manager(AudioRecordSourceFactory { _, _, _, _, _ -> record })

    private fun field(manager: AudioCaptureManager, name: String): Any? {
        val field = AudioCaptureManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(manager)
    }

    private fun retired(manager: AudioCaptureManager) {
        assertEquals("owning attempt retired", 0L, field(manager, "activeAttemptToken"))
        assertNull("no installed session", field(manager, "activeSession"))
        assertFalse("running flag false", (field(manager, "isRunning") as AtomicBoolean).get())
    }

    private fun thrown(action: () -> Unit): Throwable? = try { action(); null } catch (t: Throwable) { t }
    private fun await(latch: CountDownLatch) = assertTrue("controlled event reached", latch.await(8, TimeUnit.SECONDS))
    private fun joined(thread: Thread) { thread.join(8000); assertFalse("thread finished", thread.isAlive) }

    @Test
    fun sampleRateOutputPropertyFailureRetiresOwnerAndRetryWorks() {
        val factoryCalls = AtomicInteger()
        val record = Record()
        val manager = manager(AudioRecordSourceFactory { _, _, _, _, _ -> factoryCalls.incrementAndGet(); record })
        TransactionAuditAudioManagerShadow.propertyAction = { key ->
            if (key == AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) throw IllegalArgumentException("sample-rate-property")
            "48000"
        }
        assertFalse(manager.startCapture())
        retired(manager)
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertEquals(0, factoryCalls.get())
        TransactionAuditAudioManagerShadow.propertyAction = null
        assertTrue(manager.startCapture())
        manager.stopCapture()
        assertEquals(1, record.releases.get())
    }

    @Test
    fun primaryAndFallbackBufferExceptionsEachRetireWithoutFactoryAllocation() {
        for (failSecond in listOf(false, true)) {
            val minCalls = AtomicInteger()
            val factoryCalls = AtomicInteger()
            val manager = manager(
                AudioRecordSourceFactory { _, _, _, _, _ -> factoryCalls.incrementAndGet(); Record() },
                min = { _, _, _ ->
                    if (failSecond && minCalls.incrementAndGet() == 1) AudioRecord.ERROR_BAD_VALUE
                    else throw IllegalStateException("buffer-provider")
                }
            )
            assertFalse(manager.startCapture())
            retired(manager)
            assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
            assertEquals(0, factoryCalls.get())
        }
    }

    @Test
    fun primaryAndFallbackStateAndReleaseExceptionsAttemptEachReleaseOnce() {
        val primary = Record().also {
            it.stateAction = { throw IllegalStateException("primary-state") }
            it.releaseAction = { throw IllegalStateException("primary-release") }
        }
        val fallback = Record().also {
            it.stateAction = { throw IllegalStateException("fallback-state") }
            it.releaseAction = { throw IllegalStateException("fallback-release") }
        }
        val sources = mutableListOf<Int>()
        val manager = manager(AudioRecordSourceFactory { source, _, _, _, _ ->
            sources.add(source); if (source == MediaRecorder.AudioSource.UNPROCESSED) primary else fallback
        })
        assertFalse(manager.startCapture())
        retired(manager)
        assertEquals(listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC), sources)
        assertEquals(1, primary.releases.get())
        assertEquals(1, fallback.releases.get())
    }

    @Test
    fun preferredFactoryExceptionCanFallbackAndBothFactoryExceptionsRetire() {
        val fallback = Record()
        val calls = mutableListOf<Int>()
        val manager = manager(AudioRecordSourceFactory { source, _, _, _, _ ->
            calls.add(source)
            if (source != MediaRecorder.AudioSource.MIC) throw IllegalStateException("preferred-factory")
            fallback
        })
        assertTrue(manager.startCapture())
        assertEquals(listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC), calls)
        manager.stopCapture()
        assertEquals(1, fallback.releases.get())
        val both = manager(AudioRecordSourceFactory { _, _, _, _, _ -> throw IllegalArgumentException("both-factory") })
        assertFalse(both.startCapture())
        retired(both)
        assertEquals(CaptureStatus.ERROR, both.uiState.value.status)
    }

    @Test
    fun fatalPrimaryAndFallbackFactoryErrorsPropagateOriginalAndRetire() {
        for (inFallback in listOf(false, true)) {
            val original = LinkageError("factory-linkage")
            val primary = Record().also { it.stateAction = { AudioRecord.STATE_UNINITIALIZED } }
            val manager = manager(AudioRecordSourceFactory { source, _, _, _, _ ->
                if (inFallback && source != MediaRecorder.AudioSource.MIC) primary else throw original
            })
            assertSame(original, thrown { manager.startCapture() })
            retired(manager)
            assertEquals(if (inFallback) 1 else 0, primary.releases.get())
        }
    }

    @Test
    fun fatalPrimaryAndFallbackStateErrorsReleaseTheirLocalOwnerOnce() {
        for (inFallback in listOf(false, true)) {
            val original = OutOfMemoryError("state-oom")
            val primary = Record().also { it.stateAction = {
                if (inFallback) AudioRecord.STATE_UNINITIALIZED else throw original
            } }
            val fallback = Record().also { it.stateAction = { throw original } }
            val manager = manager(AudioRecordSourceFactory { source, _, _, _, _ ->
                if (source == MediaRecorder.AudioSource.MIC) fallback else primary
            })
            assertSame(original, thrown { manager.startCapture() })
            retired(manager)
            assertEquals(1, primary.releases.get())
            assertEquals(if (inFallback) 1 else 0, fallback.releases.get())
        }
    }

    @Test
    fun failureBeforeSessionTransferAttemptsRawReleaseExactlyOnceDespiteException() {
        val record = Record().also { it.releaseAction = { throw IllegalStateException("raw-release") } }
        val manager = manager(record)
        manager.onPostHardwareInit = { throw IllegalStateException("post-factory") }
        assertFalse(manager.startCapture())
        retired(manager)
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(manager.uiState.value.errorMessage!!.contains("post-factory"))
        assertEquals(1, record.releases.get())
        manager.stopCapture()
        assertEquals(1, record.releases.get())
    }

    @Test
    fun failureAfterTransferSurvivesAllOrdinaryCleanupExceptionsAndRetries() {
        val broken = Record().also {
            it.addAction = { throw IllegalStateException("listener-add") }
            it.removeAction = { throw IllegalArgumentException("listener-remove") }
            it.stopAction = { throw IllegalStateException("stop") }
            it.releaseAction = { throw IllegalStateException("release") }
        }
        val healthy = Record()
        val calls = AtomicInteger()
        val manager = manager(AudioRecordSourceFactory { _, _, _, _, _ -> if (calls.getAndIncrement() == 0) broken else healthy })
        assertFalse(manager.startCapture())
        retired(manager)
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertEquals(1, broken.removals.get())
        assertEquals(1, broken.stops.get())
        assertEquals(1, broken.releases.get())
        assertTrue(manager.startCapture())
        manager.stopCapture()
        assertEquals(1, broken.releases.get())
        assertEquals(1, healthy.releases.get())
    }

    @Test
    fun recordingStateProbeExceptionDoesNotBlockReleaseAfterTransferFailure() {
        val record = Record().also {
            it.addAction = { throw IllegalStateException("listener-add") }
            it.recordingAction = { throw IllegalStateException("recording-state") }
        }
        val manager = manager(record)
        assertFalse(manager.startCapture())
        retired(manager)
        assertEquals(1, record.removals.get())
        assertEquals(0, record.stops.get())
        assertEquals(1, record.releases.get())
    }

    @Test
    fun preferredDeviceSetupExceptionRetiresSessionBeforeListenerRegistration() {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build()
        TransactionAuditAudioManagerShadow.devicesAction = { arrayOf(device) }
        val record = Record().also { it.preferredAction = { throw IllegalStateException("preferred-device") } }
        val manager = manager(
            AudioRecordSourceFactory { _, _, _, _, _ -> record },
            AudioCaptureConfig(windowSize = 1024, hopSize = 512, preferredDeviceId = device.id)
        )
        assertFalse(manager.startCapture())
        retired(manager)
        assertEquals(0, record.additions.get())
        assertEquals(0, record.removals.get())
        assertEquals(1, record.releases.get())
    }

    @Test
    fun targetDeviceLookupExceptionUsesDefaultAndStillOwnsRecorder() {
        val record = Record()
        val manager = manager(
            AudioRecordSourceFactory { _, _, _, _, _ -> record },
            AudioCaptureConfig(windowSize = 1024, hopSize = 512, preferredDeviceId = 73)
        )
        manager.onPostHardwareInit = {
            TransactionAuditAudioManagerShadow.devicesAction = { throw IllegalStateException("device-lookup") }
        }
        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertEquals(0, record.releases.get())
        manager.stopCapture()
        assertEquals(1, record.releases.get())
    }

    @Test
    fun stopInInstallLaunchGapNeverStartsReleasedRecorder() {
        val record = Record()
        val manager = manager(record)
        var worker: Thread? = null
        manager.threadLauncher = { thread ->
            worker = thread
            manager.stopCapture()
            assertEquals(1, record.releases.get())
            thread.start()
        }
        assertTrue(manager.startCapture())
        joined(worker!!)
        retired(manager)
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertEquals(0, record.starts.get())
        assertEquals(1, record.releases.get())
    }

    @Test
    fun launcherFailureAfterLaunchRetiresOwnerWhileWorkerIsPausedBeforeStart() {
        val record = Record()
        val manager = manager(record)
        val preStart = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var worker: Thread? = null
        manager.onPreStartRecording = { preStart.countDown(); await(resume) }
        manager.threadLauncher = { thread ->
            worker = thread
            thread.start()
            await(preStart)
            throw IllegalStateException("launcher-after-start")
        }
        try {
            assertFalse(manager.startCapture())
            retired(manager)
            assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
            assertEquals(1, record.releases.get())
        } finally { resume.countDown(); worker?.let { joined(it) } }
        assertEquals(0, record.starts.get())
        assertEquals(1, record.releases.get())
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
    }

    @Test
    fun launcherFailureAfterHardwareStartsReleasesExactlyOnceAndWorkerCannotPublishLater() {
        val record = Record()
        val readEntered = CountDownLatch(1)
        val resumeRead = CountDownLatch(1)
        record.readAction = { readEntered.countDown(); await(resumeRead); AudioRecord.ERROR_DEAD_OBJECT }
        val manager = manager(record)
        var worker: Thread? = null
        manager.threadLauncher = { thread ->
            worker = thread; thread.start(); await(readEntered)
            throw IllegalStateException("launched-worker-failure")
        }
        try {
            assertFalse(manager.startCapture())
            retired(manager)
            assertEquals(1, record.starts.get())
            assertEquals(1, record.stops.get())
            assertEquals(1, record.releases.get())
        } finally { resumeRead.countDown(); worker?.let { joined(it) } }
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertEquals(1, record.releases.get())
    }

    @Test
    fun oldLauncherFailureCannotRetireReplacementOwner() {
        val first = Record()
        val second = Record()
        val calls = AtomicInteger()
        val manager = manager(AudioRecordSourceFactory { _, _, _, _, _ -> if (calls.getAndIncrement() == 0) first else second })
        val launcherEntered = CountDownLatch(1)
        val resumeLauncher = CountDownLatch(1)
        val firstResult = AtomicReference<Boolean>()
        manager.threadLauncher = { launcherEntered.countDown(); await(resumeLauncher); throw IllegalStateException("old-launcher") }
        val starter = Thread { firstResult.set(manager.startCapture()) }
        starter.start()
        try {
            await(launcherEntered)
            manager.stopCapture()
            manager.threadLauncher = {}
            assertTrue(manager.startCapture())
            val replacementToken = field(manager, "activeAttemptToken")
            val replacementSession = field(manager, "activeSession")
            resumeLauncher.countDown()
            joined(starter)
            assertEquals(false, firstResult.get())
            assertEquals(replacementToken, field(manager, "activeAttemptToken"))
            assertSame(replacementSession, field(manager, "activeSession"))
            assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
            assertEquals(1, first.releases.get())
            assertEquals(0, second.releases.get())
        } finally { resumeLauncher.countDown(); joined(starter); manager.stopCapture() }
        assertEquals(1, second.releases.get())
    }

    @Test
    fun workerStartExceptionAndCleanupExceptionsReleaseOnceAndRetire() {
        val record = Record().also {
            it.startAction = { throw IllegalStateException("start-recording") }
            it.recordingAction = { throw IllegalStateException("recording-state") }
            it.removeAction = { throw IllegalStateException("remove-listener") }
            it.releaseAction = { throw IllegalStateException("release") }
        }
        val manager = manager(record)
        var worker: Thread? = null
        manager.threadLauncher = { worker = it; it.start() }
        assertTrue(manager.startCapture())
        joined(worker!!)
        retired(manager)
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertEquals(1, record.starts.get())
        assertEquals(1, record.releases.get())
        manager.stopCapture()
        assertEquals(1, record.releases.get())
    }

    @Test
    fun twelveCompetingSessionCleanupsAttemptOneTerminalRelease() {
        val record = Record()
        val releaseEntered = CountDownLatch(1)
        val resumeRelease = CountDownLatch(1)
        record.releaseAction = { releaseEntered.countDown(); await(resumeRelease) }
        val session = CaptureSession(1, 1, record, targetDevice = null, requestedRate = 48000, actualRate = 48000, windowSize = 1024, hopSize = 512)
        session.routingListener = AudioRecord.OnRoutingChangedListener {}
        val barrier = CyclicBarrier(12)
        val failure = AtomicReference<Throwable>()
        val threads = (1..12).map { Thread { try { barrier.await(); session.cleanup() } catch (t: Throwable) { failure.set(t) } } }
        threads.forEach { it.start() }
        try { await(releaseEntered); assertEquals(1, record.releases.get()) }
        finally { resumeRelease.countDown(); threads.forEach { joined(it) } }
        assertNull(failure.get())
        assertEquals(1, record.removals.get())
        assertEquals(1, record.stops.get())
        assertEquals(1, record.releases.get())
        assertFalse(session.startRecording())
        assertEquals(0, record.starts.get())
    }

    @Test
    fun fatalLauncherAfterInstallWithOrdinaryCleanupPreservesFatalAndRetires() {
        val original = LinkageError("launch-linkage")
        val record = Record().also { it.releaseAction = { throw IllegalStateException("release") } }
        val manager = manager(record)
        manager.threadLauncher = { throw original }
        assertSame(original, thrown { manager.startCapture() })
        retired(manager)
        assertEquals(1, record.releases.get())
    }

    @Test
    fun fatalWorkerStartMustReleaseOwnedRecorderBeforeUncaughtPropagation() {
        val original = LinkageError("worker-start-linkage")
        val record = Record().also { it.startAction = { throw original } }
        val manager = manager(record)
        val uncaught = AtomicReference<Throwable>()
        var worker: Thread? = null
        manager.threadLauncher = { thread ->
            worker = thread
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) }
            thread.start()
        }
        assertTrue(manager.startCapture())
        joined(worker!!)
        println("AUDIT_FATAL_WORKER sameOriginal=${uncaught.get() === original}; releases=${record.releases.get()}; activeToken=${field(manager, "activeAttemptToken")}; sessionPresent=${field(manager, "activeSession") != null}; running=${(field(manager, "isRunning") as AtomicBoolean).get()}; status=${manager.uiState.value.status}")
        try {
            assertSame(original, uncaught.get())
            assertEquals("dead worker must retire its recorder", 1, record.releases.get())
            retired(manager)
        } finally { manager.stopCapture() }
    }

    // Adversarial fatal-cleanup probes. The assertions express the advertised B05/B06 guarantees.
    @Test
    fun fatalListenerRemovalDuringFatalInitStillReleasesRetiresAndPreservesOriginal() {
        val original = OutOfMemoryError("original-init-oom")
        val cleanupFatal = LinkageError("cleanup-listener-linkage")
        val record = Record().also {
            it.addAction = { throw original }
            it.removeAction = { throw cleanupFatal }
        }
        val manager = manager(record)
        val actual = thrown { manager.startCapture() }
        println("AUDIT_FATAL_LISTENER actual=${actual?.javaClass?.simpleName}:${actual?.message}; sameOriginal=${actual === original}; releases=${record.releases.get()}; activeToken=${field(manager, "activeAttemptToken")}; status=${manager.uiState.value.status}")
        assertEquals("release must still be attempted", 1, record.releases.get())
        retired(manager)
        assertSame("original fatal must survive cleanup failure", original, actual)
    }

    @Test
    fun fatalRecordingStateDuringSessionCleanupStillAttemptsRelease() {
        val fatal = LinkageError("cleanup-state-linkage")
        val record = Record().also { it.recordingAction = { throw fatal } }
        val session = CaptureSession(1, 1, record, targetDevice = null, requestedRate = 48000, actualRate = 48000, windowSize = 1024, hopSize = 512)
        val actual = thrown { session.cleanup() }
        session.cleanup()
        println("AUDIT_FATAL_STATE sameFatal=${actual === fatal}; cleaned=${session.isCleanedUp()}; releases=${record.releases.get()}")
        assertSame(fatal, actual)
        assertEquals("terminal cleanup must attempt release before propagating fatal", 1, record.releases.get())
    }

    @Test
    fun fatalStopDuringSessionCleanupStillAttemptsRelease() {
        val fatal = LinkageError("cleanup-stop-linkage")
        val record = Record().also { it.stopAction = { throw fatal } }
        val session = CaptureSession(1, 1, record, targetDevice = null, requestedRate = 48000, actualRate = 48000, windowSize = 1024, hopSize = 512)
        val actual = thrown { session.cleanup() }
        session.cleanup()
        println("AUDIT_FATAL_STOP sameFatal=${actual === fatal}; stops=${record.stops.get()}; releases=${record.releases.get()}; cleaned=${session.isCleanedUp()}")
        assertSame(fatal, actual)
        assertEquals("terminal stop failure must not skip release", 1, record.releases.get())
    }

    @Test
    fun fatalReleaseOnUninitializedPrimaryAndFallbackIsAttemptedOnce() {
        val releaseAttempts = mutableListOf<Int>()
        for (inFallback in listOf(false, true)) {
            val fatal = LinkageError("uninitialized-release-linkage")
            val primary = Record().also {
                it.stateAction = { AudioRecord.STATE_UNINITIALIZED }
                if (!inFallback) it.releaseAction = { throw fatal }
            }
            val fallback = Record().also {
                it.stateAction = { AudioRecord.STATE_UNINITIALIZED }
                it.releaseAction = { throw fatal }
            }
            val manager = manager(AudioRecordSourceFactory { source, _, _, _, _ -> if (source == MediaRecorder.AudioSource.MIC) fallback else primary })
            val actual = thrown { manager.startCapture() }
            val failingRecord = if (inFallback) fallback else primary
            println("AUDIT_FATAL_NEGOTIATION fallback=$inFallback; sameFatal=${actual === fatal}; releaseAttempts=${failingRecord.releases.get()}; activeToken=${field(manager, "activeAttemptToken")}")
            assertSame(fatal, actual)
            retired(manager)
            releaseAttempts.add(failingRecord.releases.get())
        }
        assertEquals("primary and fallback owners must each make exactly one release attempt", listOf(1, 1), releaseAttempts)
    }

    @Test
    fun fatalReleaseDuringOrdinaryInitFailureStillRetiresOwningAttempt() {
        val fatal = LinkageError("session-release-linkage")
        val record = Record().also {
            it.addAction = { throw IllegalStateException("ordinary-init-failure") }
            it.releaseAction = { throw fatal }
        }
        val manager = manager(record)
        val actual = thrown { manager.startCapture() }
        println("AUDIT_FATAL_RELEASE sameFatal=${actual === fatal}; releases=${record.releases.get()}; activeToken=${field(manager, "activeAttemptToken")}; status=${manager.uiState.value.status}")
        assertSame(fatal, actual)
        assertEquals(1, record.releases.get())
        retired(manager)
    }
}
