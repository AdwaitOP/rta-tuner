package com.rtatuner.app

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import com.rtatuner.app.core.audio.AudioCaptureConfig
import com.rtatuner.app.core.audio.AudioCaptureManager
import com.rtatuner.app.core.audio.AudioRecordSource
import com.rtatuner.app.core.audio.AudioRecordSourceFactory
import com.rtatuner.app.core.audio.CaptureStatus
import com.rtatuner.app.core.dsp.BiquadFilterBank
import com.rtatuner.app.core.dsp.FftProcessor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Independently written audit-only tests. Never copied into the audited Git checkout.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [IndependentAuditDeviceShadow::class])
class IndependentRestartConfigurationAuditTest {
    private lateinit var app: Application
    private lateinit var builtIn: AudioDeviceInfo
    private lateinit var usbA: AudioDeviceInfo
    private lateinit var usbB: AudioDeviceInfo

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        builtIn = device(1, "Actual built-in", AudioDeviceInfo.TYPE_BUILTIN_MIC, 48000)
        usbA = device(101, "Requested USB A", AudioDeviceInfo.TYPE_USB_DEVICE, 48000)
        usbB = device(102, "Requested USB B", AudioDeviceInfo.TYPE_USB_DEVICE, 44100)
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Shadows.shadowOf(audio).setInputDevices(listOf(builtIn, usbA, usbB))
    }

    private fun device(id: Int, name: String, type: Int, rate: Int): AudioDeviceInfo {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val shadow = Shadow.extract<IndependentAuditDeviceShadow>(device)
        shadow.auditId = id
        shadow.auditName = name
        shadow.auditType = type
        shadow.auditRates = intArrayOf(rate)
        return device
    }

    private fun await(latch: CountDownLatch, label: String) =
        assertTrue("Timed out waiting for $label", latch.await(10, TimeUnit.SECONDS))

    private fun launch(label: String, action: () -> Unit): Pair<Thread, AtomicReference<Throwable?>> {
        val thrown = AtomicReference<Throwable?>()
        val thread = Thread({ try { action() } catch (t: Throwable) { thrown.set(t) } }, label)
        thread.start()
        return thread to thrown
    }

    private fun finish(worker: Pair<Thread, AtomicReference<Throwable?>>) {
        worker.first.join(10000)
        assertFalse("Worker ${worker.first.name} must finish", worker.first.isAlive)
        worker.second.get()?.let { throw AssertionError("Worker ${worker.first.name} failed", it) }
    }

    private class Fixture(
        val manager: AudioCaptureManager,
        val created: AtomicInteger
    )

    private fun fixture(
        vararg records: AuditRecord,
        config: AudioCaptureConfig = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
        nativeWorkers: Boolean = false,
        minBuffer: (Int, Int, Int) -> Int = { _, _, _ -> 4096 }
    ): Fixture {
        val created = AtomicInteger()
        val queue = ConcurrentLinkedQueue(records.toList())
        val manager = AudioCaptureManager(app, config, AudioRecordSourceFactory { source, _, _, _, _ ->
            created.incrementAndGet()
            (queue.poll() ?: throw AssertionError("Unexpected extra recorder construction")).also { it.requestedSource = source }
        }, minBuffer)
        if (!nativeWorkers) manager.threadLauncher = { /* Lifecycle schedules need no background DSP. */ }
        return Fixture(manager, created)
    }

    @Test fun cleanupPausedSwitchThenStopCannotRestart() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val a = AuditRecord(onRelease = { entered.countDown(); await(resume, "cleanup resume") })
        val b = AuditRecord()
        val f = fixture(a, b)
        assertTrue(f.manager.startCapture())
        val switch = launch("switch-cleanup") { f.manager.setPreferredInputDevice(101) }
        try {
            await(entered, "blocked release")
            f.manager.stopCapture()
            assertEquals(CaptureStatus.STOPPED, f.manager.uiState.value.status)
        } finally { resume.countDown(); finish(switch) }
        assertEquals(CaptureStatus.STOPPED, f.manager.uiState.value.status)
        assertEquals("Canceled switch must not construct a replacement", 1, f.created.get())
        assertEquals(1, a.releases.get())
        assertEquals(0, b.releases.get())
        f.manager.release()
    }

    @Test fun refreshCompletePausedSwitchThenStopCannotRestart() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val f = fixture(AuditRecord(), AuditRecord())
        f.manager.onPreReconfigurationRestart = { entered.countDown(); await(resume, "post-refresh resume") }
        assertTrue(f.manager.startCapture())
        val switch = launch("switch-post-refresh") { f.manager.setPreferredInputDevice(101) }
        try {
            await(entered, "post-refresh boundary")
            f.manager.stopCapture()
        } finally { resume.countDown(); finish(switch) }
        assertEquals(CaptureStatus.STOPPED, f.manager.uiState.value.status)
        assertEquals(1, f.created.get())
        f.manager.release()
    }

    @Test fun overlappingSwitchCompletingDuringFirstCleanupMustRestartLatestPreference() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val f = fixture(AuditRecord(onRelease = { entered.countDown(); await(resume, "first cleanup resume") }), AuditRecord())
        assertTrue(f.manager.startCapture())
        val first = launch("first-switch-cleanup") { f.manager.setPreferredInputDevice(101) }
        try {
            await(entered, "first switch cleanup")
            // The second call is fully complete before A is released. No scheduler race in this assertion.
            f.manager.setPreferredInputDevice(102)
            assertEquals(102, f.manager.uiState.value.selectedDeviceId)
        } finally { resume.countDown(); finish(first) }
        try {
            println("AUDIT overlap-cleanup: status=${f.manager.uiState.value.status}, created=${f.created.get()}, selected=${f.manager.uiState.value.selectedDeviceId}")
            assertEquals("Latest device switch must preserve the intent to capture", CaptureStatus.RUNNING, f.manager.uiState.value.status)
            assertEquals(2, f.created.get())
        } finally { f.manager.release() }
    }

    @Test fun overlappingSwitchCompletingAfterFirstRefreshMustRestartLatestPreference() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val calls = AtomicInteger()
        val f = fixture(AuditRecord(), AuditRecord())
        f.manager.onPreReconfigurationRestart = {
            if (calls.incrementAndGet() == 1) { entered.countDown(); await(resume, "first post-refresh resume") }
        }
        assertTrue(f.manager.startCapture())
        val first = launch("first-switch-post-refresh") { f.manager.setPreferredInputDevice(101) }
        try {
            await(entered, "first post-refresh boundary")
            f.manager.setPreferredInputDevice(102)
            assertEquals(102, f.manager.uiState.value.selectedDeviceId)
        } finally { resume.countDown(); finish(first) }
        try {
            println("AUDIT overlap-post-refresh: status=${f.manager.uiState.value.status}, created=${f.created.get()}, selected=${f.manager.uiState.value.selectedDeviceId}")
            assertEquals(CaptureStatus.RUNNING, f.manager.uiState.value.status)
            assertEquals(2, f.created.get())
        } finally { f.manager.release() }
    }

    @Test fun explicitStartSupersedesSwitchWhileCleanupPaused() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val b = AuditRecord()
        val f = fixture(AuditRecord(onRelease = { entered.countDown(); await(resume, "cleanup resume") }), b)
        assertTrue(f.manager.startCapture())
        val first = launch("switch-before-explicit-start") { f.manager.setPreferredInputDevice(101) }
        try {
            await(entered, "first switch cleanup")
            assertTrue(f.manager.startCapture())
            assertEquals(CaptureStatus.RUNNING, f.manager.uiState.value.status)
            assertEquals(101, f.manager.uiState.value.selectedDeviceId)
        } finally { resume.countDown(); finish(first) }
        assertEquals(2, f.created.get())
        assertEquals(CaptureStatus.RUNNING, f.manager.uiState.value.status)
        assertEquals(0, b.releases.get())
        f.manager.release()
    }

    @Test fun releaseSupersedesSwitchWhileCleanupPaused() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val f = fixture(AuditRecord(onRelease = { entered.countDown(); await(resume, "cleanup resume") }), AuditRecord())
        assertTrue(f.manager.startCapture())
        val first = launch("switch-before-release") { f.manager.setPreferredInputDevice(101) }
        try { await(entered, "first switch cleanup"); f.manager.release() }
        finally { resume.countDown(); finish(first) }
        assertEquals(CaptureStatus.STOPPED, f.manager.uiState.value.status)
        assertEquals(1, f.created.get())
    }

    @Test fun normalSwitchCreatesLatestRecorderAndStartsNativeCapture() {
        val a = AuditRecord(actualRoute = builtIn)
        val b = AuditRecord(actualRoute = usbB)
        val f = fixture(a, b, nativeWorkers = true)
        try {
            assertTrue(f.manager.startCapture())
            await(a.inRead, "A native capture")
            f.manager.setPreferredInputDevice(102)
            await(b.inRead, "B native capture")
            val state = f.manager.uiState.value
            assertEquals(CaptureStatus.RUNNING, state.status)
            assertEquals(102, state.selectedDeviceId)
            assertEquals(102, state.routedDeviceId)
            assertEquals("Requested USB B", state.diagnostics.actualRoutedDeviceName)
            assertEquals(44100, state.diagnostics.actualSampleRate)
            assertEquals(1, a.releases.get())
            assertEquals(0, b.releases.get())
        } finally { f.manager.release() }
    }

    @Test fun pendingSelectionMustNotBecomeRoutedAndOldDiagnosticsKeepTheirOwner() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val a = AuditRecord(actualRoute = builtIn, onRelease = { entered.countDown(); await(resume, "cleanup resume") })
        val f = fixture(a, AuditRecord(actualRoute = usbB), config = AudioCaptureConfig(1024, 512, preferredDeviceId = 101), nativeWorkers = true)
        assertTrue(f.manager.startCapture())
        await(a.inRead, "initial routing publication")
        val oldDiagnostics = f.manager.uiState.value.diagnostics
        val switch = launch("pending-preference-switch") { f.manager.setPreferredInputDevice(102) }
        try {
            await(entered, "pending cleanup")
            val pending = f.manager.uiState.value
            assertEquals(CaptureStatus.INITIALIZING, pending.status)
            assertEquals(102, pending.selectedDeviceId)
            assertNull(pending.routedDeviceId)
            assertEquals(oldDiagnostics, pending.diagnostics)
            assertEquals("Requested USB A", pending.diagnostics.requestedDeviceName)
            assertEquals("Actual built-in", pending.diagnostics.actualRoutedDeviceName)
        } finally { resume.countDown(); finish(switch); f.manager.release() }
    }

    @Test fun staleRoutingCallbackCannotOverwriteNewDeviceOwner() {
        val a = AuditRecord(actualRoute = usbA)
        val b = AuditRecord(actualRoute = builtIn)
        val f = fixture(a, b, config = AudioCaptureConfig(1024, 512, preferredDeviceId = 101), nativeWorkers = true)
        try {
            assertTrue(f.manager.startCapture()); await(a.inRead, "A routing")
            val staleListener = a.listeners.single()
            f.manager.setPreferredInputDevice(102); await(b.inRead, "B routing")
            val before = f.manager.uiState.value
            assertEquals("Requested USB B", before.diagnostics.requestedDeviceName)
            assertEquals("Actual built-in", before.diagnostics.actualRoutedDeviceName)
            assertTrue(before.isRoutingMismatch)
            staleListener.onRoutingChanged(null)
            assertEquals(before, f.manager.uiState.value)
        } finally { f.manager.release() }
    }

    @Test fun configurationChangeDuringInitializationUsesNewSnapshotAndRetiresOldAttempt() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val a = AuditRecord()
        val b = AuditRecord()
        val f = fixture(a, b, config = AudioCaptureConfig(1024, 512, preferredDeviceId = 101))
        f.manager.onPostHardwareInit = { if (it == 1L) { entered.countDown(); await(resume, "old initialization resume") } }
        var oldResult = true
        val old = launch("initialization-A") { oldResult = f.manager.startCapture() }
        try {
            await(entered, "A created hardware")
            f.manager.setPreferredInputDevice(102)
            val current = f.manager.uiState.value
            assertEquals(CaptureStatus.RUNNING, current.status)
            assertEquals(102, current.selectedDeviceId)
            assertEquals("Requested USB B", current.diagnostics.requestedDeviceName)
            assertEquals(44100, current.diagnostics.requestedSampleRate)
            assertEquals(44100, current.diagnostics.actualSampleRate)
            assertEquals(1024, current.diagnostics.windowSize)
            assertEquals(512, current.diagnostics.hopSize)
        } finally { resume.countDown(); finish(old) }
        assertFalse("Superseded old initializer must fail", oldResult)
        assertEquals(1, a.releases.get())
        assertEquals(0, b.releases.get())
        assertEquals("Requested USB B", f.manager.uiState.value.diagnostics.requestedDeviceName)
        f.manager.release()
    }

    @Test fun canceledInitializationCanRetryWithLatestPreference() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val a = AuditRecord()
        val b = AuditRecord()
        val f = fixture(a, b, config = AudioCaptureConfig(1024, 512, preferredDeviceId = 101))
        f.manager.onPostHardwareInit = { if (it == 1L) { entered.countDown(); await(resume, "canceled init resume") } }
        var oldResult = true
        val old = launch("canceled-initializer") { oldResult = f.manager.startCapture() }
        try {
            await(entered, "old initialized hardware")
            f.manager.stopCapture()
            f.manager.setPreferredInputDevice(102)
            assertEquals(CaptureStatus.STOPPED, f.manager.uiState.value.status)
            assertTrue(f.manager.startCapture())
        } finally { resume.countDown(); finish(old) }
        assertFalse(oldResult)
        assertEquals(CaptureStatus.RUNNING, f.manager.uiState.value.status)
        assertEquals(102, f.manager.uiState.value.selectedDeviceId)
        assertEquals("Requested USB B", f.manager.uiState.value.diagnostics.requestedDeviceName)
        assertEquals(1, a.releases.get())
        assertEquals(0, b.releases.get())
        f.manager.release()
    }

    @Test fun newSessionMustNotClaimRequestedMicAsActualBeforeNativeStart() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val a = AuditRecord(actualRoute = builtIn)
        val f = fixture(a, config = AudioCaptureConfig(1024, 512, preferredDeviceId = 101), nativeWorkers = true)
        f.manager.onPreStartRecording = { entered.countDown(); await(resume, "native start resume") }
        try {
            assertTrue(f.manager.startCapture())
            await(entered, "worker paused before native start")
            val interim = f.manager.uiState.value
            println("AUDIT interim: status=${interim.status}, selected=${interim.selectedDeviceId}, routed=${interim.routedDeviceId}, requested=${interim.diagnostics.requestedDeviceName}, actual=${interim.diagnostics.actualRoutedDeviceName}, nativeStarts=${a.starts.get()}")
            assertEquals(0, a.starts.get())
            assertEquals(101, interim.selectedDeviceId)
            assertNull(interim.routedDeviceId)
            assertEquals("Requested USB A", interim.diagnostics.requestedDeviceName)
            assertNotEquals("Requested mic must not be presented as actual before hardware routing is observed", "Requested USB A", interim.diagnostics.actualRoutedDeviceName)
        } finally { resume.countDown(); f.manager.release() }
    }

    @Test fun negotiatedSampleRateAndFftDimensionsUseSessionSnapshot() {
        val frameReady = CountDownLatch(1)
        val resumeFrame = CountDownLatch(1)
        val framePublished = CountDownLatch(1)
        val hop = ShortArray(128) { index -> ((index % 17 - 8) * 2000).toShort() }
        val a = AuditRecord(actualRoute = builtIn, firstHop = hop, onSubsequentRead = { framePublished.countDown() })
        val f = fixture(a, config = AudioCaptureConfig(256, 128, targetSampleRate = 96000, preferredDeviceId = 101), nativeWorkers = true,
            minBuffer = { rate, _, _ -> if (rate == 96000) AudioRecord.ERROR_BAD_VALUE else 2048 })
        f.manager.onPrePublishFrame = { frameReady.countDown(); await(resumeFrame, "frame publication resume") }
        try {
            assertTrue(f.manager.startCapture())
            await(frameReady, "first complete DSP frame")
            val initial = f.manager.uiState.value.diagnostics
            assertEquals(96000, initial.requestedSampleRate)
            assertEquals(48000, initial.actualSampleRate)
            assertEquals(256, initial.windowSize)
            assertEquals(128, initial.hopSize)
            assertEquals(2048, initial.bufferSizeInBytes)
            val window = ShortArray(256)
            System.arraycopy(hop, 0, window, 128, 128)
            val expectedFft = FftProcessor(256, 48000).processFrame(window, computeOctaveBands = false).rawFftDb
            val expectedBands = BiquadFilterBank(48000).processHop(hop, 128)
            resumeFrame.countDown()
            await(framePublished, "read after first frame publication")
            val published = f.manager.uiState.value
            assertEquals(129, published.rawFftDb.size)
            assertEquals(1L, published.diagnostics.totalFramesCaptured)
            assertArrayEquals(expectedFft, published.rawFftDb, 0.0001f)
            assertArrayEquals(expectedBands, published.octave31Db, 0.0001f)
            assertEquals(96000, published.diagnostics.requestedSampleRate)
            assertEquals(48000, published.diagnostics.actualSampleRate)
            assertEquals(MediaRecorder.AudioSource.UNPROCESSED, a.requestedSource)
        } finally { resumeFrame.countDown(); f.manager.release() }
    }

    @Suppress("DEPRECATION")
    private class AuditRecord(
        var actualRoute: AudioDeviceInfo? = null,
        val onRelease: (() -> Unit)? = null,
        val firstHop: ShortArray? = null,
        val onSubsequentRead: (() -> Unit)? = null
    ) : AudioRecordSource {
        override val state = AudioRecord.STATE_INITIALIZED
        @Volatile override var recordingState = AudioRecord.RECORDSTATE_STOPPED
        override val routedDevice: AudioDeviceInfo? get() = actualRoute
        val starts = AtomicInteger()
        val releases = AtomicInteger()
        val inRead = CountDownLatch(1)
        private val unblockRead = CountDownLatch(1)
        private val reads = AtomicInteger()
        val listeners = mutableListOf<AudioRecord.OnRoutingChangedListener>()
        var requestedSource: Int = MediaRecorder.AudioSource.UNPROCESSED
        override fun setPreferredDevice(device: AudioDeviceInfo?) = true
        override fun addOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener, handler: Handler?) { listeners.add(listener) }
        override fun removeOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener) { listeners.remove(listener) }
        override fun startRecording() { starts.incrementAndGet(); recordingState = AudioRecord.RECORDSTATE_RECORDING }
        override fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, readMode: Int): Int {
            inRead.countDown()
            if (reads.incrementAndGet() == 1 && firstHop != null) {
                require(firstHop.size == sizeInShorts)
                System.arraycopy(firstHop, 0, audioData, offsetInShorts, sizeInShorts)
                return sizeInShorts
            }
            onSubsequentRead?.invoke()
            while (unblockRead.count != 0L) {
                try { unblockRead.await(10, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            }
            return AudioRecord.ERROR_INVALID_OPERATION
        }
        override fun stop() { recordingState = AudioRecord.RECORDSTATE_STOPPED; unblockRead.countDown() }
        override fun release() { onRelease?.invoke(); releases.incrementAndGet(); unblockRead.countDown() }
    }
}

@Implements(AudioDeviceInfo::class)
class IndependentAuditDeviceShadow {
    var auditId = 0
    var auditName = ""
    var auditType = AudioDeviceInfo.TYPE_BUILTIN_MIC
    var auditRates = intArrayOf(48000)
    @Implementation fun getId(): Int = auditId
    @Implementation fun getProductName(): CharSequence = auditName
    @Implementation fun getType(): Int = auditType
    @Implementation fun getSampleRates(): IntArray = auditRates
    @Implementation fun getChannelCounts(): IntArray = intArrayOf(1)
    @Implementation fun isSource(): Boolean = true
}
