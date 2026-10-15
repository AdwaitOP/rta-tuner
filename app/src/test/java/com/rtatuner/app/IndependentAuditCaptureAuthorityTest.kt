package com.rtatuner.app

import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import androidx.lifecycle.ViewModelStore
import com.rtatuner.app.core.audio.AudioCaptureManager
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.audio.AudioRecordSource
import com.rtatuner.app.core.audio.AudioRecordSourceFactory
import com.rtatuner.app.core.audio.CaptureStatus
import com.rtatuner.app.core.audio.FftDisplayMode
import com.rtatuner.app.core.audio.NoiseFloorCompatibility
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.ui.AudioCaptureViewModel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.AudioDeviceInfoBuilder
import kotlin.math.sin

/**
 * Independent audit acceptance tests asserting corrected required capture authority,
 * genuine PCM frame provenance, and acquisition cancellation semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [IndependentCaptureAuthorityDeviceShadow::class])
class IndependentAuditCaptureAuthorityTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = AtomicLong(1000L)
    private lateinit var app: Application
    private lateinit var model: AudioCaptureViewModel
    private lateinit var store: ViewModelStore
    private lateinit var micA: AudioDeviceInfo
    private lateinit var micB: AudioDeviceInfo
    private val records = ConcurrentLinkedQueue<SteppedRecord>()
    private val created = CopyOnWriteArrayList<SteppedRecord>()
    private val manager: AudioCaptureManager get() {
        val field = AudioCaptureViewModel::class.java.getDeclaredField("captureManager")
        field.isAccessible = true
        return field.get(model) as AudioCaptureManager
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("rta_calibration_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        micA = device(311, "Independent A", AudioDeviceInfo.TYPE_BUILTIN_MIC)
        micB = device(322, "Independent B", AudioDeviceInfo.TYPE_USB_DEVICE)
        Shadows.shadowOf(app.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setInputDevices(listOf(micA, micB))
        model = AudioCaptureViewModel(app, FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME))
        store = ViewModelStore().apply { put("independent-capture-authority", model) }
        val clockField = model.calibrationManager.javaClass.getDeclaredField("nowMs")
        clockField.isAccessible = true
        clockField.set(model.calibrationManager, { now.get() })
        injectHardware(manager)
        dispatcher.scheduler.runCurrent()
    }

    @After fun cleanup() {
        store.clear()
        created.forEach { it.closeRead() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun injectHardware(target: AudioCaptureManager) {
        target.minBufferSizeProvider = { _, _, _ -> 4096 }
        target.audioRecordFactory = AudioRecordSourceFactory { _, _, _, _, _ ->
            val next = records.poll() ?: throw AssertionError("Unplanned recorder construction")
            created.add(next)
            next
        }
    }

    private fun device(id: Int, name: String, type: Int): AudioDeviceInfo {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val shadow = Shadow.extract<IndependentCaptureAuthorityDeviceShadow>(device)
        shadow.auditId = id
        shadow.auditName = name
        shadow.auditType = type
        return device
    }

    private fun startRecord(route: AudioDeviceInfo? = micA): SteppedRecord {
        val record = SteppedRecord(route)
        records.add(record)
        model.startCapture()
        await { record.starts.get() == 1 && manager.uiState.value.routedDeviceId == route?.id }
        return record
    }

    private fun emit(record: SteppedRecord, amplitude: Int = 5000): Long {
        val prior = manager.uiState.value.frameContext
        record.steps.put(amplitude)
        var offeredReplacement = false
        val initialReads = record.enteredReads.get()
        await {
            val latest = manager.uiState.value.frameContext
            if (latest != null && (prior == null || latest.captureGeneration != prior.captureGeneration ||
                latest.managerId != prior.managerId || latest.frameSequence > prior.frameSequence)) {
                true
            } else {
                if (!offeredReplacement && record.steps.isEmpty() && record.enteredReads.get() > initialReads) {
                    offeredReplacement = true
                    record.steps.offer(amplitude)
                }
                false
            }
        }
        return manager.uiState.value.frameSequence
    }

    private fun saveRealFloor(record: SteppedRecord) {
        val auth = model.uiState.value.captureAuthority
        model.calibrationManager.startNoiseFloorCapture(100L, authority = auth)
        dispatcher.scheduler.runCurrent()
        now.addAndGet(150L)
        emit(record, 1000)
        assertTrue(model.calibrationManager.uiState.value.noiseFloor.isCaptured)
        assertEquals("311:Independent A", model.calibrationManager.uiState.value.noiseFloor.fingerprint!!.routedMicrophoneId)
        assertTrue(model.calibrationManager.uiState.value.isNoiseSubtractionEffective)
    }

    private fun accumulated(): Int {
        val field = model.calibrationManager.javaClass.getDeclaredField("noiseAccumFrames")
        field.isAccessible = true
        return field.getInt(model.calibrationManager)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) {
            dispatcher.scheduler.runCurrent()
            Thread.sleep(2)
        }
        assertTrue("Real worker must reach deterministic condition", condition())
        dispatcher.scheduler.runCurrent()
    }

    @Test fun genuinePcmFramesHaveUniqueOwnerGenerationSequenceAndDuplicatesAreNotAccumulated() {
        val record = startRecord()
        val auth = model.uiState.value.captureAuthority
        model.calibrationManager.startNoiseFloorCapture(10000L, authority = auth)
        emit(record)
        val first = manager.uiState.value.frameContext!!
        assertEquals(manager.managerId, first.managerId)
        assertTrue(first.captureGeneration > 0L)
        assertEquals(1L, first.frameSequence)
        assertEquals(1, accumulated())
        model.setFftDisplayMode(FftDisplayMode.RAW_FFT)
        dispatcher.scheduler.runCurrent()
        assertEquals(first, manager.uiState.value.frameContext)
        assertEquals(1, accumulated())
        emit(record)
        val second = manager.uiState.value.frameContext!!
        assertEquals(first.managerId, second.managerId)
        assertEquals(first.captureGeneration, second.captureGeneration)
        assertEquals(first.frameSequence + 1, second.frameSequence)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(2, accumulated())
    }

    @Test fun routeMetadataChangeImmediatelyInvalidatesCompatibilityAndDisablesSubtractionBeforeBPublishesPcm() {
        val record = startRecord()
        saveRealFloor(record)
        emit(record, 5000) // Mandatory last genuine A PCM before route transition.
        val rawA = manager.uiState.value.octave31Db.copyOf()
        val calibratedA = model.uiState.value.octave31Db.copyOf()
        assertTrue(calibratedA.indices.any { kotlin.math.abs(calibratedA[it] - rawA[it]) > 0.01f })
        record.actualRoute = micB
        record.routingListeners.single().onRoutingChanged(null)
        dispatcher.scheduler.runCurrent()
        assertEquals(322, manager.uiState.value.routedDeviceId)
        assertEquals("Independent B", model.uiState.value.diagnostics.actualRoutedDeviceName)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
        assertArrayEquals(rawA, model.uiState.value.octave31Db, 0.0001f)
        emit(record, 5000)
        assertEquals("322:Independent B", manager.uiState.value.frameContext!!.fingerprint.routedMicrophoneId)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
        assertArrayEquals(manager.uiState.value.octave31Db, model.uiState.value.octave31Db, 0.001f)
    }

    @Test fun nullRouteMetadataImmediatelyInvalidatesCompatibilityWithoutAnotherPcm() {
        val record = startRecord()
        saveRealFloor(record)
        emit(record)
        record.actualRoute = null
        record.routingListeners.single().onRoutingChanged(null)
        dispatcher.scheduler.runCurrent()
        assertNull(manager.uiState.value.routedDeviceId)
        assertEquals("Pending", model.uiState.value.diagnostics.actualRoutedDeviceName)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
        emit(record)
        assertFalse(manager.uiState.value.frameContext!!.fingerprint.isMicrophoneKnown)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
    }

    @Test fun routeOnlyMaterialChangeCancelsAcquisitionImmediatelyAndPreservesSavedFloor() {
        val record = startRecord()
        saveRealFloor(record)
        val saved = model.calibrationManager.uiState.value.noiseFloor
        val auth = model.uiState.value.captureAuthority
        model.calibrationManager.startNoiseFloorCapture(10000L, authority = auth)
        emit(record)
        assertEquals(1, accumulated())
        record.actualRoute = micB
        record.routingListeners.single().onRoutingChanged(null)
        dispatcher.scheduler.runCurrent()
        assertFalse("Route change to B must immediately cancel acquisition", model.calibrationManager.uiState.value.isCapturingNoiseFloor)
        assertEquals(0, accumulated())
        assertEquals(saved, model.calibrationManager.uiState.value.noiseFloor)
        emit(record)
        assertFalse(model.calibrationManager.uiState.value.isCapturingNoiseFloor)
        assertEquals(saved, model.calibrationManager.uiState.value.noiseFloor)
    }

    @Test fun oldPcmBeforeAcquisitionIsNotReusedByMetadataOnlyEmissionAfterRequest() {
        val record = startRecord()
        emit(record)
        val auth = model.uiState.value.captureAuthority
        model.calibrationManager.startNoiseFloorCapture(10000L, authority = auth)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, accumulated())
        model.setFftDisplayMode(FftDisplayMode.RAW_FFT)
        dispatcher.scheduler.runCurrent()
        assertEquals("Pre-request PCM must not be acquired from display-only emission", 0, accumulated())
    }

    @Test fun stoppedCaptureCannotAcquireFloor() {
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertNull(manager.uiState.value.frameContext)
        assertTrue(model.calibrationManager.startNoiseFloorCapture(100L) is FeatureAccessResult.Denied)
        now.addAndGet(150L)
        model.setFftDisplayMode(FftDisplayMode.RAW_FFT)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, created.size)
        assertEquals(0L, manager.uiState.value.diagnostics.totalFramesCaptured)
        assertNull(manager.uiState.value.frameContext)
        val saved = model.calibrationManager.uiState.value.noiseFloor
        assertFalse("Floor must not be saved when stopped", saved.isCaptured)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
    }

    @Test fun stopDisablesCompatibilityAndEffectiveSubtraction() {
        val record = startRecord()
        saveRealFloor(record)
        emit(record)
        model.stopCapture()
        dispatcher.scheduler.runCurrent()
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertNull(manager.uiState.value.frameContext)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        assertFalse("Subtraction must be ineffective after STOP", model.uiState.value.calibration.isNoiseSubtractionEffective)
    }

    @Test fun activeDeviceUnplugReportsErrorAndDisablesSubtraction() {
        val record = startRecord()
        saveRealFloor(record)
        emit(record)
        val saved = model.calibrationManager.uiState.value.noiseFloor
        val callbackField = AudioCaptureManager::class.java.getDeclaredField("deviceCallback")
        callbackField.isAccessible = true
        val callback = callbackField.get(manager) as AudioDeviceCallback
        callback.onAudioDevicesRemoved(arrayOf(micA)) // Deliver real production platform boundary callback.
        dispatcher.scheduler.runCurrent()
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(manager.uiState.value.errorMessage!!.contains("disconnected"))
        assertNull(manager.uiState.value.frameContext)
        assertNull(manager.uiState.value.routedDeviceId)
        assertEquals(saved, model.calibrationManager.uiState.value.noiseFloor)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        assertFalse("Unplug error must disable subtraction", model.uiState.value.calibration.isNoiseSubtractionEffective)
    }

    @Test fun stopDuringAcquisitionCancelsAcquisitionImmediately() {
        val record = startRecord()
        val auth = model.uiState.value.captureAuthority
        model.calibrationManager.startNoiseFloorCapture(100L, authority = auth)
        emit(record)
        assertEquals(1, accumulated())
        now.addAndGet(150L)
        model.stopCapture()
        dispatcher.scheduler.runCurrent()
        assertNull(manager.uiState.value.frameContext)
        assertFalse("STOP must cancel acquisition immediately", model.calibrationManager.uiState.value.isCapturingNoiseFloor)
        assertFalse("STOP must not finalize acquisition", model.calibrationManager.uiState.value.noiseFloor.isCaptured)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
    }

    @Test fun nativeErrorCancelsAcquisitionAndDisablesCompatibility() {
        val record = startRecord()
        val auth = model.uiState.value.captureAuthority
        model.calibrationManager.startNoiseFloorCapture(100L, authority = auth)
        emit(record)
        assertEquals(1, accumulated())
        now.addAndGet(150L)
        record.steps.put(AudioRecord.ERROR_DEAD_OBJECT)
        await { manager.uiState.value.status == CaptureStatus.ERROR }
        assertNull(manager.uiState.value.frameContext)
        assertFalse(model.calibrationManager.uiState.value.isCapturingNoiseFloor)
        assertFalse(model.calibrationManager.uiState.value.noiseFloor.isCaptured)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
    }

    @Test fun replacementSessionBeforeNativeStartRevokesCompatibilityImmediately() {
        val old = startRecord()
        saveRealFloor(old)
        emit(old)
        val oldContext = manager.uiState.value.frameContext
        val oldCallback = old.routingListeners.single()
        val next = SteppedRecord(micB)
        records.add(next)
        var pendingThread: Thread? = null
        manager.threadLauncher = { pendingThread = it } // Deliberately leave replacement before native start.
        model.setPreferredInputDevice(322)
        dispatcher.scheduler.runCurrent()
        assertNotNull(pendingThread)
        assertEquals(0, next.starts.get())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertNull(manager.uiState.value.routedDeviceId)
        assertEquals("Pending", manager.uiState.value.diagnostics.actualRoutedDeviceName)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
        val beforeStale = manager.uiState.value
        old.actualRoute = micA
        oldCallback.onRoutingChanged(null)
        dispatcher.scheduler.runCurrent()
        assertEquals(beforeStale, manager.uiState.value) // Positive control: obsolete callback cannot mutate replacement.
        pendingThread!!.start()
        await { next.starts.get() == 1 && manager.uiState.value.routedDeviceId == 322 }
        emit(next)
        assertTrue(manager.uiState.value.frameContext!!.captureGeneration > oldContext!!.captureGeneration)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
    }

    @Test fun changedActualFallbackSourceDisablesCompatibilityImmediately() {
        val old = startRecord()
        saveRealFloor(old)
        emit(old)
        val failedPrimary = SteppedRecord(micA, AudioRecord.STATE_UNINITIALIZED)
        val fallback = SteppedRecord(micA)
        records.add(failedPrimary)
        records.add(fallback)
        var pendingThread: Thread? = null
        manager.threadLauncher = { pendingThread = it }
        model.setPreferredInputDevice(311)
        dispatcher.scheduler.runCurrent()
        assertEquals("MIC (Fallback Source)", manager.uiState.value.diagnostics.activeAudioSource)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
        pendingThread!!.start()
        await { fallback.starts.get() == 1 }
        emit(fallback)
        val context = manager.uiState.value.frameContext!!
        assertEquals(MediaRecorder.AudioSource.MIC, context.fingerprint.actualAudioSource)
        assertTrue(context.fingerprint.isSourceFallback)
        assertTrue(model.uiState.value.calibration.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
        assertFalse(model.uiState.value.calibration.isNoiseSubtractionEffective)
    }

    @Test fun routeChangeAfterPcmReadBeforePublicationDiscardsHopAndNeverPublishesAAsB() {
        val record = startRecord()
        val beforePublication = CountDownLatch(1)
        val resumePublication = CountDownLatch(1)
        manager.onPrePublishFrame = {
            beforePublication.countDown()
            resumePublication.await(8, TimeUnit.SECONDS)
        }
        try {
            record.steps.put(7000) // Synchronous fake read returns this hop while actual route is A.
            assertTrue(beforePublication.await(8, TimeUnit.SECONDS))
            assertEquals(311, record.lastReadRouteId)
            assertNull(manager.uiState.value.frameContext)
            record.actualRoute = micB
            record.routingListeners.single().onRoutingChanged(null)
            dispatcher.scheduler.runCurrent()
            resumePublication.countDown()
            await { record.enteredReads.get() >= 2 }
            dispatcher.scheduler.runCurrent()
            // Hop must have been discarded because route epoch changed before publication:
            assertEquals("Hop acquired under A must be discarded when route changes before publish",
                0L, manager.uiState.value.frameSequence)
            assertNull(manager.uiState.value.frameContext)
            // Next read actually occurs under B:
            manager.onPrePublishFrame = null
            emit(record, 7000)
            assertEquals(1L, manager.uiState.value.frameSequence)
            assertEquals("Next genuine hop belongs to B",
                "322:Independent B", manager.uiState.value.frameContext!!.fingerprint.routedMicrophoneId)
        } finally {
            resumePublication.countDown()
            manager.onPrePublishFrame = null
        }
    }

    @Test fun newGenerationWithSameFingerprintCancelsAcquisition() {
        val first = startRecord()
        val auth = model.uiState.value.captureAuthority
        model.calibrationManager.startNoiseFloorCapture(10000L, authority = auth)
        emit(first)
        val initial = manager.uiState.value.frameContext!!
        val second = SteppedRecord(micA)
        records.add(second)
        model.retryCapture()
        await { second.starts.get() == 1 }
        emit(second)
        val replacement = manager.uiState.value.frameContext!!
        assertTrue(replacement.captureGeneration > initial.captureGeneration)
        assertEquals(initial.fingerprint, replacement.fingerprint)
        assertFalse("New capture generation must cancel active acquisition", model.calibrationManager.uiState.value.isCapturingNoiseFloor)
        assertEquals(0, accumulated())
    }

    private class SteppedRecord(
        @Volatile var actualRoute: AudioDeviceInfo?,
        override val state: Int = AudioRecord.STATE_INITIALIZED
    ) : AudioRecordSource {
        @Volatile override var recordingState = AudioRecord.RECORDSTATE_STOPPED
        override val routedDevice: AudioDeviceInfo? get() = actualRoute
        val routingListeners = CopyOnWriteArrayList<AudioRecord.OnRoutingChangedListener>()
        val steps = LinkedBlockingQueue<Int>()
        val starts = AtomicInteger()
        val enteredReads = AtomicInteger()
        @Volatile var lastReadRouteId: Int? = null
        private var sampleIndex = 0
        override fun setPreferredDevice(device: AudioDeviceInfo?) = true
        override fun addOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener, handler: Handler?) { routingListeners.add(listener) }
        override fun removeOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener) { routingListeners.remove(listener) }
        override fun startRecording() { starts.incrementAndGet(); recordingState = AudioRecord.RECORDSTATE_RECORDING }
        override fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, readMode: Int): Int {
            enteredReads.incrementAndGet()
            val next = try { steps.take() } catch (_: InterruptedException) { return AudioRecord.ERROR_INVALID_OPERATION }
            if (next < 0) return next
            lastReadRouteId = actualRoute?.id
            for (i in 0 until sizeInShorts) {
                audioData[offsetInShorts + i] = (next * sin(2.0 * Math.PI * 1000.0 * sampleIndex++ / 48000.0)).toInt().toShort()
            }
            return sizeInShorts
        }
        override fun stop() { recordingState = AudioRecord.RECORDSTATE_STOPPED; closeRead() }
        override fun release() { closeRead() }
        fun closeRead() { steps.offer(AudioRecord.ERROR_INVALID_OPERATION) }
    }
}

@Implements(AudioDeviceInfo::class)
class IndependentCaptureAuthorityDeviceShadow {
    var auditId = 0
    var auditName = ""
    var auditType = AudioDeviceInfo.TYPE_BUILTIN_MIC
    @Implementation fun getId() = auditId
    @Implementation fun getProductName(): CharSequence = auditName
    @Implementation fun getType() = auditType
    @Implementation fun getSampleRates(): IntArray = intArrayOf(48000)
    @Implementation fun getChannelCounts(): IntArray = intArrayOf(1)
    @Implementation fun isSource() = true
}
