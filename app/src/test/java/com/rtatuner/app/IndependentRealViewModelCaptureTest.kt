package com.rtatuner.app

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.os.Handler
import androidx.lifecycle.ViewModelStore
import com.rtatuner.app.core.audio.AudioCaptureManager
import com.rtatuner.app.core.audio.AudioRecordSource
import com.rtatuner.app.core.audio.CaptureStatus
import com.rtatuner.app.core.audio.FftDisplayMode
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.ui.AudioCaptureViewModel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Real ViewModel, policy, manager, CaptureSession, worker, PCM accumulation and DSP.
 * Only recorder construction and platform min-buffer probing are replaced at hardware boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [IndependentRecorderFactoryShadow::class, IndependentMinBufferShadow::class],
    instrumentedPackages = ["com.rtatuner.app.core.audio"])
class IndependentRealViewModelCaptureTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var model: AudioCaptureViewModel
    private lateinit var entitlements: FakeEntitlementRepository
    private lateinit var store: ViewModelStore
    private val manager: AudioCaptureManager get() {
        val field = AudioCaptureViewModel::class.java.getDeclaredField("captureManager")
        field.isAccessible = true
        return field.get(model) as AudioCaptureManager
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        IndependentRecorderFactoryShadow.pending.clear()
        IndependentRecorderFactoryShadow.created.clear()
        entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        model = AudioCaptureViewModel(RuntimeEnvironment.getApplication(), entitlements)
        store = ViewModelStore().apply { put("independent-real-capture", model) }
        dispatcher.scheduler.runCurrent()
    }

    @After fun teardown() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        IndependentRecorderFactoryShadow.pending.clear()
        IndependentRecorderFactoryShadow.created.clear()
    }

    @Test fun realViewModelRetryAfterNativeStartFailureReleasesOldRecorderAndPublishesFreshFrames() {
        val failed = EngineeringRecord().apply { onStart = { throw IllegalStateException("Independent native start failure") } }
        val healthy = EngineeringRecord()
        IndependentRecorderFactoryShadow.pending.add(failed)
        IndependentRecorderFactoryShadow.pending.add(healthy)
        model.startCapture()
        await { manager.uiState.value.status == CaptureStatus.ERROR && failed.releases.get() == 1 }
        assertEquals(1, failed.releases.get())
        assertTrue(model.uiState.value.errorMessage!!.contains("Independent native start failure"))
        model.retryCapture()
        await { manager.uiState.value.diagnostics.totalFramesCaptured >= 2 }
        assertEquals(CaptureStatus.RUNNING, model.uiState.value.status)
        assertNull(model.uiState.value.errorMessage)
        assertEquals(1, failed.starts.get())
        assertEquals(1, failed.releases.get())
        assertEquals(1, healthy.starts.get())
        assertEquals(0, healthy.releases.get())
        assertTrue(model.uiState.value.rawFftDb.any { it > -100f })
        model.stopCapture()
        dispatcher.scheduler.runCurrent()
        assertEquals(CaptureStatus.STOPPED, model.uiState.value.status)
        assertEquals(1, healthy.releases.get())
    }

    @Test fun realLivePro4096ToFree2048RecreatesManagerAndKeepsDeviceDisplayAndSnapshotMetadata() {
        model.setPreferredInputDevice(77)
        model.setFftDisplayMode(FftDisplayMode.RAW_FFT)
        assertEquals(FeatureAccessResult.Allowed, model.setWindowSize(4096))
        val oldManager = manager
        val proRecord = EngineeringRecord()
        val freeRecord = EngineeringRecord()
        IndependentRecorderFactoryShadow.pending.add(proRecord)
        IndependentRecorderFactoryShadow.pending.add(freeRecord)
        model.startCapture()
        await { oldManager.uiState.value.diagnostics.totalFramesCaptured >= 2 }
        assertEquals(4096, oldManager.uiState.value.diagnostics.windowSize)
        assertEquals(2049, oldManager.uiState.value.rawFftDb.size)
        assertEquals(FeatureAccessResult.Allowed, model.freezeCurrentSnapshot("Retained Pro4096"))
        val frozen = model.frozenSnapshots.value.single()
        assertEquals(4096, frozen.windowSize)
        entitlements.setTier(EntitlementTier.FREE)
        dispatcher.scheduler.runCurrent()
        val replacement = manager
        assertNotSame(oldManager, replacement)
        await { replacement.uiState.value.diagnostics.totalFramesCaptured >= 2 }
        assertEquals(2048, model.fftWindowSize.value)
        assertEquals(2048, replacement.uiState.value.diagnostics.windowSize)
        assertEquals(1024, replacement.uiState.value.diagnostics.hopSize)
        assertEquals(1025, replacement.uiState.value.rawFftDb.size)
        assertEquals(CaptureStatus.RUNNING, model.uiState.value.status)
        assertEquals(1025, model.uiState.value.rawFftDb.size)
        assertEquals(77, model.uiState.value.selectedDeviceId)
        assertEquals(FftDisplayMode.RAW_FFT, model.uiState.value.displayMode)
        assertEquals(listOf(frozen), model.frozenSnapshots.value)
        assertEquals(1, proRecord.releases.get())
        assertEquals(1, freeRecord.starts.get())
        assertEquals(0, freeRecord.releases.get())
        assertEquals(2, IndependentRecorderFactoryShadow.created.size)
        model.stopCapture()
        dispatcher.scheduler.runCurrent()
        assertEquals(CaptureStatus.STOPPED, model.uiState.value.status)
        assertEquals(1, freeRecord.releases.get())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            dispatcher.scheduler.runCurrent()
            Thread.sleep(2)
        }
        assertTrue("Real worker condition should complete within bounded wait", condition())
        dispatcher.scheduler.runCurrent()
    }
}

@Implements(className = "com.rtatuner.app.core.audio.DefaultAudioRecordSourceFactory", isInAndroidSdk = false)
class IndependentRecorderFactoryShadow {
    companion object {
        val pending = ConcurrentLinkedQueue<EngineeringRecord>()
        val created = CopyOnWriteArrayList<EngineeringRecord>()
    }
    @Implementation fun create(audioSource: Int, sampleRate: Int, channelConfig: Int, audioEncoding: Int, bufferSizeInBytes: Int): AudioRecordSource {
        val record = pending.poll() ?: throw AssertionError("Unplanned hardware recorder creation")
        created.add(record)
        return record
    }
}

@Implements(AudioRecord::class)
class IndependentMinBufferShadow {
    companion object {
        @JvmStatic @Implementation fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 4096
    }
}

/** Models lifecycle separately: release does not call stop or silently manufacture extra stop counts. */
@Suppress("DEPRECATION")
open class EngineeringRecord : AudioRecordSource {
    override val state: Int get() = AudioRecord.STATE_INITIALIZED
    @Volatile private var nativeRecordingState = AudioRecord.RECORDSTATE_STOPPED
    override val recordingState: Int get() = nativeRecordingState
    override val routedDevice: AudioDeviceInfo? get() = routeLookup?.invoke()
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    val releases = AtomicInteger()
    val reads = AtomicInteger()
    @Volatile var released = false
    var onStart: (() -> Unit)? = null
    var routeLookup: (() -> AudioDeviceInfo?)? = null
    val listeners = java.util.concurrent.CopyOnWriteArrayList<AudioRecord.OnRoutingChangedListener>()
    override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean = true
    override fun addOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener, handler: Handler?) { listeners.add(listener) }
    override fun removeOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener) { listeners.remove(listener) }
    override fun startRecording() {
        check(!released) { "Start after release" }
        starts.incrementAndGet()
        nativeRecordingState = AudioRecord.RECORDSTATE_RECORDING
        onStart?.invoke()
    }
    override fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, readMode: Int): Int {
        reads.incrementAndGet()
        if (released) return AudioRecord.ERROR_INVALID_OPERATION
        Thread.sleep(3)
        for (i in 0 until sizeInShorts) audioData[offsetInShorts + i] = ((i % 32 - 16) * 900).toShort()
        return sizeInShorts
    }
    override fun stop() { stops.incrementAndGet(); nativeRecordingState = AudioRecord.RECORDSTATE_STOPPED }
    override fun release() { releases.incrementAndGet(); released = true; nativeRecordingState = AudioRecord.RECORDSTATE_STOPPED }
}

