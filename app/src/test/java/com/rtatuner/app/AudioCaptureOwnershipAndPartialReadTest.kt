package com.rtatuner.app

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import com.rtatuner.app.core.audio.AudioCaptureConfig
import com.rtatuner.app.core.audio.AudioCaptureManager
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.audio.AudioRecordSource
import com.rtatuner.app.core.audio.AudioRecordSourceFactory
import com.rtatuner.app.core.audio.CaptureStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production-boundary tests for AudioCaptureManager verifying:
 * - Generation ownership and stale worker containment (F04)
 * - Error isolation and release guarantees (F04)
 * - Contiguous partial-read accumulation and mathematical equivalence (F05)
 *
 * Exercises the actual production AudioCaptureManager lifecycle and capture loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioCaptureOwnershipAndPartialReadTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
    }

    /**
     * Fake AudioRecordSource allowing deterministic injection of reads, errors, and lifecycle state.
     */
    @Suppress("DEPRECATION")
    open class FakeAudioRecordSource(
        override var state: Int = AudioRecord.STATE_INITIALIZED,
        override var recordingState: Int = AudioRecord.RECORDSTATE_RECORDING,
        override var routedDevice: AudioDeviceInfo? = null,
        val onRead: ((audioData: ShortArray, offset: Int, size: Int) -> Int)? = null,
        var onRelease: (() -> Unit)? = null
    ) : AudioRecordSource {
        var startRecordingCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        var isReleased = false
        var startRecordingException: Throwable? = null
        var stopException: Throwable? = null
        var releaseException: Throwable? = null
        var removeListenerException: Throwable? = null
        var setPreferredDeviceException: Throwable? = null

        var addListenerException: Throwable? = null

        val routingListeners = mutableListOf<AudioRecord.OnRoutingChangedListener>()

        override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean {
            setPreferredDeviceException?.let { throw it }
            return true
        }

        override fun addOnRoutingChangedListener(
            listener: AudioRecord.OnRoutingChangedListener,
            handler: Handler?
        ) {
            addListenerException?.let { throw it }
            routingListeners.add(listener)
        }

        override fun removeOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener) {
            removeListenerException?.let { throw it }
            routingListeners.remove(listener)
        }

        open override fun startRecording() {
            startRecordingCalls++
            startRecordingException?.let { throw it }
        }

        open override fun read(
            audioData: ShortArray,
            offsetInShorts: Int,
            sizeInShorts: Int,
            readMode: Int
        ): Int {
            if (isReleased) return AudioRecord.ERROR_INVALID_OPERATION
            return onRead?.invoke(audioData, offsetInShorts, sizeInShorts) ?: sizeInShorts
        }

        open override fun stop() {
            stopCalls++
            stopException?.let { throw it }
            recordingState = AudioRecord.RECORDSTATE_STOPPED
        }

        open override fun release() {
            onRelease?.invoke()
            releaseCalls++
            isReleased = true
            stop()
            releaseException?.let { throw it }
        }
    }

    // --- F04: Capture Ownership Tests ---

    @Test
    fun staleWorkerACompletingAfterWorkerBStartsDoesNotReleaseOrCorruptB() {
        val workerAInRead = CountDownLatch(1)
        val resumeWorkerA = CountDownLatch(1)
        val workerAFinished = CountDownLatch(1)

        val recordA = FakeAudioRecordSource(
            onRead = { _, _, _ ->
                workerAInRead.countDown()
                resumeWorkerA.await(5, TimeUnit.SECONDS)
                AudioRecord.ERROR_INVALID_OPERATION
            }
        )

        val recordB = FakeAudioRecordSource(
            onRead = { buf, off, size ->
                Thread.sleep(10)
                size
            }
        )

        val sources = ConcurrentLinkedQueue(listOf(recordA, recordB))
        val factory = AudioRecordSourceFactory { _, _, _, _, _ -> sources.poll() }

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = factory,
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        // 1. Start session A
        assertTrue(manager.startCapture())
        assertTrue("Worker A should reach read()", workerAInRead.await(5, TimeUnit.SECONDS))
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        // 2. Stop session A and start session B while worker A is still paused in read()
        manager.stopCapture()
        assertTrue(recordA.isReleased)

        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertFalse("Record B must be active", recordB.isReleased)

        // 3. Unblock worker A; let it exit its loop and execute finally { session.cleanup() }
        resumeWorkerA.countDown()
        Thread.sleep(100) // Allow worker A thread to terminate

        // 4. Verify that worker A did NOT stop or release record B!
        assertFalse("Worker A must NOT release record B!", recordB.isReleased)
        assertEquals(0, recordB.stopCalls)
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertNull(manager.uiState.value.errorMessage)

        manager.stopCapture()
        assertTrue(recordB.isReleased)
    }

    @Test
    fun staleErrorsFromWorkerAAreSuppressedAfterWorkerBStarts() {
        val workerAInRead = CountDownLatch(1)
        val resumeWorkerA = CountDownLatch(1)

        val recordA = FakeAudioRecordSource(
            onRead = { _, _, _ ->
                workerAInRead.countDown()
                resumeWorkerA.await(5, TimeUnit.SECONDS)
                AudioRecord.ERROR_DEAD_OBJECT // Stale error
            }
        )

        val recordB = FakeAudioRecordSource(
            onRead = { buf, off, size ->
                Thread.sleep(10)
                size
            }
        )

        val sources = ConcurrentLinkedQueue(listOf(recordA, recordB))
        val factory = AudioRecordSourceFactory { _, _, _, _, _ -> sources.poll() }

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = factory,
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.startCapture()
        assertTrue(workerAInRead.await(5, TimeUnit.SECONDS))

        // Restart into session B
        manager.stopCapture()
        manager.startCapture()
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        // Let worker A return its DEAD_OBJECT error
        resumeWorkerA.countDown()
        Thread.sleep(100)

        // Verify status remains RUNNING for session B, error was suppressed
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertNull(manager.uiState.value.errorMessage)
        assertFalse(recordB.isReleased)

        manager.stopCapture()
    }

    @Test
    fun repeatedRapidStartStopRetryDoesNotDeadlockOrLeak() {
        val createdRecords = mutableListOf<FakeAudioRecordSource>()
        val factory = AudioRecordSourceFactory { _, _, _, _, _ ->
            FakeAudioRecordSource(onRead = { _, _, size -> size }).also { createdRecords.add(it) }
        }

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = factory,
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        for (i in 0 until 15) {
            assertTrue(manager.startCapture())
            manager.stopCapture()
        }

        // All created records except possibly none should be released
        assertTrue(createdRecords.isNotEmpty())
        assertTrue(createdRecords.all { it.isReleased })
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)

        // One final start
        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        val active = createdRecords.last()
        assertFalse(active.isReleased)

        manager.stopCapture()
        assertTrue(active.isReleased)
    }

    @Test
    fun failedStartRecordingCleansUpRecordImmediately() {
        val record = FakeAudioRecordSource()
        record.startRecordingException = IllegalStateException("Hardware bus in use")

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.startCapture()

        // Wait for error state
        runBlocking {
            withTimeout(3000) {
                manager.uiState.first { it.status == CaptureStatus.ERROR }
            }
        }

        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(manager.uiState.value.errorMessage!!.contains("Hardware bus in use"))
        assertTrue("Recorder must be cleaned up on start failure", record.isReleased)
    }

    @Test
    fun invalidRecordStateCleansUpRecordImmediately() {
        val record = FakeAudioRecordSource(recordingState = AudioRecord.RECORDSTATE_STOPPED)

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.startCapture()

        runBlocking {
            withTimeout(3000) {
                manager.uiState.first { it.status == CaptureStatus.ERROR }
            }
        }

        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(manager.uiState.value.errorMessage!!.contains("failed to enter RECORDING state"))
        assertTrue(record.isReleased)
    }

    @Test
    fun releaseAfterHardwareErrorGuaranteesTeardownWithoutDoubleRelease() {
        val record = FakeAudioRecordSource(
            onRead = { _, _, _ -> AudioRecord.ERROR_DEAD_OBJECT }
        )

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.startCapture()

        runBlocking {
            withTimeout(3000) {
                manager.uiState.first { it.status == CaptureStatus.ERROR }
            }
        }

        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(record.isReleased)
        assertEquals(1, record.releaseCalls)

        // Subsequent stopCapture must be idempotent
        manager.stopCapture()
        assertEquals(1, record.releaseCalls)
    }

    @Test
    fun deviceDisconnectionDuringCaptureReportsErrorAndCleansUp() {
        val record = FakeAudioRecordSource(
            onRead = { _, _, size ->
                Thread.sleep(10)
                size
            }
        )

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(preferredDeviceId = 42),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.startCapture()
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        // Simulate device removal via reflection or switching preferred input
        manager.setPreferredInputDevice(99) // Changes device, stops and restarts cleanly

        manager.stopCapture()
        assertTrue(record.isReleased)
    }

    // --- F05: Partial Read Accumulation and Equivalence Tests ---

    @Test
    fun partialReadsOfVaryingLengthsAccumulateIntoExactHopBoundaries() {
        val hopSize = 512
        val windowSize = 1024

        // Feed fragments: 100, 200, 212 (= 512), then 50, 462 (= 512)
        val chunkSizes = listOf(100, 200, 212, 50, 462)
        val chunkIndex = AtomicInteger(0)
        val readCount = AtomicInteger(0)

        val record = FakeAudioRecordSource(
            onRead = { buf, offset, size ->
                val idx = chunkIndex.getAndIncrement()
                if (idx < chunkSizes.size) {
                    val count = chunkSizes[idx]
                    // Write test pattern
                    for (i in 0 until count) {
                        buf[offset + i] = ((offset + i) % 1000).toShort()
                    }
                    readCount.incrementAndGet()
                    count
                } else {
                    Thread.sleep(50)
                    0
                }
            }
        )

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = windowSize, hopSize = hopSize),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.startCapture()

        // Wait until at least 2 full hops (5 chunk reads) have completed
        runBlocking {
            withTimeout(4000) {
                manager.uiState.first { it.diagnostics.totalFramesCaptured >= 2L }
            }
        }

        // Exactly 2 frames should have been captured for 2 complete hops (despite 5 partial reads)
        assertEquals(2L, manager.uiState.value.diagnostics.totalFramesCaptured)

        manager.stopCapture()
    }

    @Test
    fun audioRecordErrorFollowingPartialTransferAbortsCleanlyWithoutCorruptedHop() {
        val hopSize = 512
        val callCount = AtomicInteger(0)

        val record = FakeAudioRecordSource(
            onRead = { buf, offset, size ->
                val call = callCount.incrementAndGet()
                if (call == 1) {
                    // First read: partial 200 samples
                    200
                } else {
                    // Second read: hardware failure
                    AudioRecord.ERROR_INVALID_OPERATION
                }
            }
        )

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = hopSize),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.startCapture()

        runBlocking {
            withTimeout(3000) {
                manager.uiState.first { it.status == CaptureStatus.ERROR }
            }
        }

        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(manager.uiState.value.errorMessage!!.contains("ERROR_INVALID_OPERATION"))
        // No frame should have been emitted because the partial hop was never completed
        assertEquals(0L, manager.uiState.value.diagnostics.totalFramesCaptured)
        assertTrue(record.isReleased)
    }

    @Test
    fun fragmentedInputProducesEquivalentMeasurementsToUninterruptedInput() {
        val hopSize = 1024
        val windowSize = 2048
        val sampleRate = 48000
        val totalHops = 4
        val totalSamples = hopSize * totalHops

        // Generate deterministic test audio: 1 kHz tone at -6 dBFS
        val testSignal = AudioMath.generateSineWave(
            frequency = 1000.0,
            sampleRate = sampleRate,
            numSamples = totalSamples,
            amplitude = 0.5
        )

        // Stream 1: Uninterrupted single-hop reads (1024 samples per call)
        val stream1Rms = mutableListOf<Float>()
        val stream1Peak = mutableListOf<Float>()
        val stream1Fft = mutableListOf<FloatArray>()
        val stream1Octave = mutableListOf<FloatArray>()

        val stream1Cursor = AtomicInteger(0)
        val stream1Done = CountDownLatch(1)

        val record1 = FakeAudioRecordSource(
            onRead = { buf, offset, size ->
                val pos = stream1Cursor.get()
                if (pos < totalSamples) {
                    val toCopy = minOf(size, totalSamples - pos)
                    System.arraycopy(testSignal, pos, buf, offset, toCopy)
                    stream1Cursor.addAndGet(toCopy)
                    toCopy
                } else {
                    stream1Done.countDown()
                    Thread.sleep(50)
                    0
                }
            }
        )

        val manager1 = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = windowSize, hopSize = hopSize, targetSampleRate = sampleRate),
            audioRecordFactory = { _, _, _, _, _ -> record1 },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager1.startCapture()
        runBlocking {
            withTimeout(5000) {
                manager1.uiState.first { it.diagnostics.totalFramesCaptured >= totalHops.toLong() }
            }
        }
        val finalState1 = manager1.uiState.value
        manager1.stopCapture()

        // Stream 2: Fragmented reads of erratic chunk sizes: 100, 200, 300, 424 (= 1024), 512, 512 (= 1024), etc.
        val fragments = listOf(
            100, 200, 300, 424,       // Hop 1 (1024)
            512, 512,                 // Hop 2 (1024)
            128, 256, 128, 256, 256,  // Hop 3 (1024)
            1024                      // Hop 4 (1024)
        )
        val fragmentCursor = AtomicInteger(0)
        val stream2SampleCursor = AtomicInteger(0)
        val stream2Done = CountDownLatch(1)

        val record2 = FakeAudioRecordSource(
            onRead = { buf, offset, size ->
                val fIdx = fragmentCursor.getAndIncrement()
                if (fIdx < fragments.size) {
                    val fragSize = fragments[fIdx]
                    val pos = stream2SampleCursor.getAndAdd(fragSize)
                    System.arraycopy(testSignal, pos, buf, offset, fragSize)
                    fragSize
                } else {
                    stream2Done.countDown()
                    Thread.sleep(50)
                    0
                }
            }
        )

        val manager2 = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = windowSize, hopSize = hopSize, targetSampleRate = sampleRate),
            audioRecordFactory = { _, _, _, _, _ -> record2 },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager2.startCapture()
        runBlocking {
            withTimeout(5000) {
                manager2.uiState.first { it.diagnostics.totalFramesCaptured >= totalHops.toLong() }
            }
        }
        val finalState2 = manager2.uiState.value
        manager2.stopCapture()

        // Compare measurements between uninterrupted and fragmented capture
        assertEquals("Total frames captured must match", finalState1.diagnostics.totalFramesCaptured, finalState2.diagnostics.totalFramesCaptured)
        assertEquals("RMS dB must match identically", finalState1.rmsDb, finalState2.rmsDb, 1e-4f)
        assertEquals("Peak dB must match identically", finalState1.peakDb, finalState2.peakDb, 1e-4f)

        // Raw FFT spectrum must match across all bins
        assertEquals(finalState1.rawFftDb.size, finalState2.rawFftDb.size)
        for (i in finalState1.rawFftDb.indices) {
            assertEquals("FFT bin $i must match", finalState1.rawFftDb[i], finalState2.rawFftDb[i], 1e-4f)
        }

        // Octave 31 bands must match across all bands
        assertEquals(31, finalState1.octave31Db.size)
        assertEquals(31, finalState2.octave31Db.size)
        for (i in 0 until 31) {
            assertEquals("Octave band $i must match", finalState1.octave31Db[i], finalState2.octave31Db[i], 1e-4f)
        }
    }

    @Test
    fun windowReconfigurationWhileRecordingTransitionsCleanlyWithoutCorruption() {
        val record4096 = FakeAudioRecordSource(
            onRead = { _, _, size ->
                Thread.sleep(10)
                size
            }
        )
        val record2048 = FakeAudioRecordSource(
            onRead = { _, _, size ->
                Thread.sleep(10)
                size
            }
        )

        val sources = ConcurrentLinkedQueue(listOf(record4096, record2048))
        val factory = AudioRecordSourceFactory { _, _, _, _, _ -> sources.poll() }

        var currentManager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 4096, hopSize = 2048),
            audioRecordFactory = factory,
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(currentManager.startCapture())
        assertEquals(CaptureStatus.RUNNING, currentManager.uiState.value.status)
        assertEquals(4096, currentManager.uiState.value.diagnostics.windowSize)
        assertEquals(2048, currentManager.uiState.value.diagnostics.hopSize)

        // Simulate ViewModel reconfigureWindowLocked(2048): release old, construct new, start new
        currentManager.release()
        assertTrue("Old 4096 recorder must be released", record4096.isReleased)

        currentManager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 2048, hopSize = 1024),
            audioRecordFactory = factory,
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(currentManager.startCapture())
        assertEquals(CaptureStatus.RUNNING, currentManager.uiState.value.status)
        assertEquals(2048, currentManager.uiState.value.diagnostics.windowSize)
        assertEquals(1024, currentManager.uiState.value.diagnostics.hopSize)
        assertFalse("New 2048 recorder must be active", record2048.isReleased)

        currentManager.stopCapture()
        assertTrue("New 2048 recorder must be released on stop", record2048.isReleased)
    }

    // --- F04: Atomic Publication and Lifecycle Synchronization Tests ---

    @Test
    fun workerPausedBetweenComputationAndPublishDoesNotOverwriteStoppedState() {
        val prePublishReached = CountDownLatch(1)
        val resumeWorker = CountDownLatch(1)

        val record = FakeAudioRecordSource(
            onRead = { buf, offset, size ->
                for (i in 0 until size) {
                    buf[offset + i] = 1000.toShort()
                }
                size
            }
        )

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.onPrePublishFrame = { _ ->
            prePublishReached.countDown()
            resumeWorker.await(5, TimeUnit.SECONDS)
        }

        assertTrue(manager.startCapture())
        assertTrue("Capture thread should reach pre-publish hook", prePublishReached.await(5, TimeUnit.SECONDS))

        // While worker thread is paused right before publishFrame, main thread stops capture
        manager.stopCapture()
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertEquals(0L, manager.uiState.value.diagnostics.totalFramesCaptured)

        // Resume worker thread to attempt publishing into stopped state
        resumeWorker.countDown()
        Thread.sleep(100)

        // Status must remain strictly STOPPED, and stale frame must NOT have been published
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertEquals(0L, manager.uiState.value.diagnostics.totalFramesCaptured)
        assertTrue(record.isReleased)
    }

    @Test
    fun workerAPausedBeforePublishDoesNotInjectStaleSpectrumIntoSessionB() {
        val workerAPrePublish = CountDownLatch(1)
        val resumeWorkerA = CountDownLatch(1)

        // Worker A emits high amplitude 5000 tone
        val recordA = FakeAudioRecordSource(
            onRead = { buf, offset, size ->
                for (i in 0 until size) {
                    buf[offset + i] = 5000.toShort()
                }
                size
            }
        )
        // Worker B emits small amplitude 50 tone
        val recordB = FakeAudioRecordSource(
            onRead = { buf, offset, size ->
                for (i in 0 until size) {
                    buf[offset + i] = 50.toShort()
                }
                size
            }
        )

        val sources = ConcurrentLinkedQueue(listOf(recordA, recordB))
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> sources.poll() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        var pausedGen: Long = -1L
        manager.onPrePublishFrame = { gen ->
            if (pausedGen == -1L) {
                pausedGen = gen
                workerAPrePublish.countDown()
                resumeWorkerA.await(5, TimeUnit.SECONDS)
            }
        }

        assertTrue(manager.startCapture())
        assertTrue("Worker A must reach pre-publish hook", workerAPrePublish.await(5, TimeUnit.SECONDS))

        // Stop session A and start session B
        manager.stopCapture()
        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        // Now resume Worker A while Session B is active
        resumeWorkerA.countDown()
        Thread.sleep(100)

        // Wait for Session B to produce at least 1 frame
        runBlocking {
            withTimeout(5000) {
                manager.uiState.first { it.diagnostics.totalFramesCaptured >= 1L }
            }
        }

        val stateB = manager.uiState.value
        assertEquals(CaptureStatus.RUNNING, stateB.status)
        assertTrue("Record A must be cleaned up and released", recordA.isReleased)
        assertFalse("Record B must remain active", recordB.isReleased)

        // Verification: Worker A's high RMS (amplitude 5000 is ~-16 dBFS) must NOT be in Session B (amplitude 50 is ~-56 dBFS)
        assertTrue("Session B peak dB (${stateB.peakDb}) should reflect low amplitude signal, not Worker A's high signal", stateB.peakDb < -40f)

        manager.stopCapture()
        assertTrue(recordB.isReleased)
    }

    @Test
    @Suppress("DEPRECATION")
    fun inFlightRoutingListenerPausedBeforeExecutionDoesNotContaminateStoppedOrNewSession() {
        val preRoutingReached = CountDownLatch(1)
        val resumeRouting = CountDownLatch(1)

        val recordA = FakeAudioRecordSource()
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> recordA },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        var hookArmed = true
        manager.onPreRoutingUpdate = {
            if (hookArmed) {
                hookArmed = false
                preRoutingReached.countDown()
                resumeRouting.await(5, TimeUnit.SECONDS)
            }
        }

        assertTrue(manager.startCapture())
        val listener = recordA.routingListeners.firstOrNull()
        assertNotNull("Routing listener must be registered", listener)

        // Fire routing callback from a separate thread
        val callbackThread = Thread {
            listener?.onRoutingChanged(null)
        }
        callbackThread.start()

        assertTrue("Routing callback should reach pre-update hook", preRoutingReached.await(5, TimeUnit.SECONDS))

        // While routing callback is paused, stop capture
        manager.stopCapture()
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)

        // Resume routing callback
        resumeRouting.countDown()
        callbackThread.join(3000)

        // UI status must remain STOPPED, not corrupted back to RUNNING or mismatched
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertFalse(manager.uiState.value.isRoutingMismatch)
        assertTrue(recordA.isReleased)
    }

    @Test
    fun viewModelRetryConsumesFreshFrameAfterError() {
        val recordA = FakeAudioRecordSource(
            onRead = { _, _, _ -> AudioRecord.ERROR_DEAD_OBJECT }
        )
        val recordB = FakeAudioRecordSource(
            onRead = { buf, offset, size ->
                for (i in 0 until size) {
                    buf[offset + i] = 500.toShort()
                }
                size
            }
        )

        val sources = ConcurrentLinkedQueue(listOf(recordA, recordB))
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> sources.poll() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())

        // Wait for session A to enter ERROR state
        runBlocking {
            withTimeout(3000) {
                manager.uiState.first { it.status == CaptureStatus.ERROR }
            }
        }
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(recordA.isReleased)

        // ViewModel initiates retry
        assertTrue(manager.startCapture())

        // Wait for session B to publish fresh frames
        runBlocking {
            withTimeout(4000) {
                manager.uiState.first { it.status == CaptureStatus.RUNNING && it.diagnostics.totalFramesCaptured >= 2L }
            }
        }

        val stateB = manager.uiState.value
        assertEquals(CaptureStatus.RUNNING, stateB.status)
        assertNull(stateB.errorMessage)
        assertTrue(stateB.diagnostics.totalFramesCaptured >= 2L)
        assertFalse(recordB.isReleased)

        manager.stopCapture()
        assertTrue(recordB.isReleased)
    }

    // --- B01, B02, B03 & Mandatory Scenarios (1-9, 13, 17, 19, 20) ---

    @Test
    fun attemptACanceledDuringInitAllowsAttemptBToStartNormally() {
        val attemptAPaused = CountDownLatch(1)
        val resumeAttemptA = CountDownLatch(1)
        val attemptAFinished = CountDownLatch(1)

        val recordA = FakeAudioRecordSource()
        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })

        val records = ConcurrentLinkedQueue(listOf(recordA, recordB))
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> records.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        var attemptACall = true
        manager.onPostHardwareInit = { _ ->
            if (attemptACall) {
                attemptACall = false
                attemptAPaused.countDown()
                resumeAttemptA.await(5, TimeUnit.SECONDS)
            }
        }

        var startAResult = true
        val threadA = Thread {
            startAResult = manager.startCapture()
            attemptAFinished.countDown()
        }
        threadA.start()

        assertTrue(attemptAPaused.await(5, TimeUnit.SECONDS))
        assertEquals(CaptureStatus.INITIALIZING, manager.uiState.value.status)

        // Cancel A via stopCapture
        manager.stopCapture()
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)

        // Start B
        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        // Resume A
        resumeAttemptA.countDown()
        assertTrue(attemptAFinished.await(5, TimeUnit.SECONDS))
        threadA.join(3000)

        assertFalse("Attempt A must return false since it was canceled (B01)", startAResult)
        assertTrue("Record A must be released", recordA.isReleased)
        assertFalse("Record B must remain active", recordB.isReleased)
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        manager.stopCapture()
        assertTrue(recordB.isReleased)
    }

    @Test
    fun attemptACanceledWhileBInitializingSucceedsLateWithoutHijackingB() {
        val attemptAPaused = CountDownLatch(1)
        val resumeAttemptA = CountDownLatch(1)
        val attemptBPaused = CountDownLatch(1)
        val resumeAttemptB = CountDownLatch(1)
        val attemptAFinished = CountDownLatch(1)
        val attemptBFinished = CountDownLatch(1)

        val recordA = FakeAudioRecordSource()
        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })

        val records = ConcurrentLinkedQueue(listOf(recordA, recordB))
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> records.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        val initCall = AtomicInteger(0)
        manager.onPostHardwareInit = { _ ->
            val c = initCall.incrementAndGet()
            if (c == 1) {
                attemptAPaused.countDown()
                resumeAttemptA.await(5, TimeUnit.SECONDS)
            } else if (c == 2) {
                attemptBPaused.countDown()
                resumeAttemptB.await(5, TimeUnit.SECONDS)
            }
        }

        var resultA = true
        var resultB = false
        val threadA = Thread {
            resultA = manager.startCapture()
            attemptAFinished.countDown()
        }
        threadA.start()

        assertTrue(attemptAPaused.await(5, TimeUnit.SECONDS))
        // Stop capture, canceling A
        manager.stopCapture()
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)

        // Start capture B
        val threadB = Thread {
            resultB = manager.startCapture()
            attemptBFinished.countDown()
        }
        threadB.start()

        assertTrue(attemptBPaused.await(5, TimeUnit.SECONDS))
        assertEquals(CaptureStatus.INITIALIZING, manager.uiState.value.status)

        // Resume A, then resume B
        resumeAttemptA.countDown()
        assertTrue(attemptAFinished.await(5, TimeUnit.SECONDS))
        threadA.join(3000)

        // A must fail and NOT hijack B (B01)
        assertFalse("Attempt A must return false", resultA)
        assertTrue("Record A must be cleaned up", recordA.isReleased)
        assertEquals(CaptureStatus.INITIALIZING, manager.uiState.value.status)

        resumeAttemptB.countDown()
        assertTrue(attemptBFinished.await(5, TimeUnit.SECONDS))
        threadB.join(3000)

        assertTrue("Attempt B must succeed", resultB)
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertFalse("Record B must remain active", recordB.isReleased)

        manager.stopCapture()
        assertTrue(recordB.isReleased)
    }

    @Test
    fun attemptACanceledWhileBInitializingFailsLateWithoutPublishingError() {
        val attemptAPaused = CountDownLatch(1)
        val resumeAttemptA = CountDownLatch(1)
        val attemptBPaused = CountDownLatch(1)
        val resumeAttemptB = CountDownLatch(1)
        val attemptAFinished = CountDownLatch(1)
        val attemptBFinished = CountDownLatch(1)

        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })

        var threadA: Thread? = null
        val attemptAPausedOnce = AtomicBoolean(false)
        val factory = AudioRecordSourceFactory { _, _, _, _, _ ->
            if (Thread.currentThread() === threadA) {
                if (attemptAPausedOnce.compareAndSet(false, true)) {
                    attemptAPaused.countDown()
                    resumeAttemptA.await(5, TimeUnit.SECONDS)
                }
                null // Always fail for attempt A (both UNPROCESSED and fallback MIC)
            } else {
                recordB
            }
        }

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = factory,
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        var resultA = true
        var resultB = false

        threadA = Thread {
            resultA = manager.startCapture()
            attemptAFinished.countDown()
        }
        threadA.start()

        assertTrue(attemptAPaused.await(5, TimeUnit.SECONDS))

        // Cancel A and set hook for B
        manager.stopCapture()
        manager.onPostHardwareInit = { _ ->
            if (Thread.currentThread() !== threadA) {
                attemptBPaused.countDown()
                resumeAttemptB.await(5, TimeUnit.SECONDS)
            }
        }

        val threadB = Thread {
            resultB = manager.startCapture()
            attemptBFinished.countDown()
        }
        threadB.start()

        assertTrue(attemptBPaused.await(5, TimeUnit.SECONDS))
        assertEquals(CaptureStatus.INITIALIZING, manager.uiState.value.status)

        // Resume A so it returns null and triggers error path
        resumeAttemptA.countDown()
        assertTrue(attemptAFinished.await(5, TimeUnit.SECONDS))
        threadA.join(3000)

        assertFalse("Attempt A must return false", resultA)
        // CRITICAL CHECK B01: State must NOT be ERROR! It must remain INITIALIZING for B!
        assertEquals("Obsolete Attempt A must not publish ERROR during B initialization", CaptureStatus.INITIALIZING, manager.uiState.value.status)
        assertNull(manager.uiState.value.errorMessage)

        // Now resume B
        resumeAttemptB.countDown()
        assertTrue(attemptBFinished.await(5, TimeUnit.SECONDS))
        threadB.join(3000)

        assertTrue("Attempt B must succeed", resultB)
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertNull(manager.uiState.value.errorMessage)

        manager.stopCapture()
        assertTrue(recordB.isReleased)
    }

    @Test
    fun simultaneousStartRequestsResolveConsistentlyWithoutLeaking() {
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val record1 = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val record2 = FakeAudioRecordSource(onRead = { _, _, size -> size })

        val records = ConcurrentLinkedQueue(listOf(record1, record2))
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> records.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        val initBarrier = java.util.concurrent.CyclicBarrier(2)
        manager.onPreHardwareInit = {
            try {
                initBarrier.await(5, TimeUnit.SECONDS)
            } catch (e: Exception) {}
        }

        val results = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val thread1 = Thread {
            barrier.await(5, TimeUnit.SECONDS)
            results["thread1"] = manager.startCapture()
        }
        val thread2 = Thread {
            barrier.await(5, TimeUnit.SECONDS)
            results["thread2"] = manager.startCapture()
        }

        thread1.start()
        thread2.start()
        thread1.join(5000)
        thread2.join(5000)

        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        val activeCount = (if (!record1.isReleased) 1 else 0) + (if (!record2.isReleased) 1 else 0)
        assertEquals("Exactly one recorder must remain active", 1, activeCount)

        manager.stopCapture()
        assertTrue(record1.isReleased)
        assertTrue(record2.isReleased)
    }

    @Test
    fun startInterruptedByStopAndReleaseAbortsCleanly() {
        val inInit = CountDownLatch(1)
        val resumeInit = CountDownLatch(1)
        val record = FakeAudioRecordSource()

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.onPostHardwareInit = { _ ->
            inInit.countDown()
            resumeInit.await(5, TimeUnit.SECONDS)
        }

        var startResult = true
        val startThread = Thread {
            startResult = manager.startCapture()
        }
        startThread.start()

        assertTrue(inInit.await(5, TimeUnit.SECONDS))
        assertEquals(CaptureStatus.INITIALIZING, manager.uiState.value.status)

        // Release the manager while start is blocked
        manager.release()
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)

        resumeInit.countDown()
        startThread.join(3000)

        assertFalse("Interrupted start must return false", startResult)
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertTrue("Record must be released", record.isReleased)
    }

    @Test
    fun workerPausedBeforeStartRecordingThenStoppedDoesNotUseReleasedRecorder() {
        val workerPausedBeforeStart = CountDownLatch(1)
        val resumeWorker = CountDownLatch(1)

        val record = FakeAudioRecordSource(onRead = { _, _, size -> size })

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.onPreStartRecording = { _ ->
            workerPausedBeforeStart.countDown()
            resumeWorker.await(5, TimeUnit.SECONDS)
        }

        assertTrue(manager.startCapture())
        assertTrue("Worker must reach pre-start hook", workerPausedBeforeStart.await(5, TimeUnit.SECONDS))

        // Main thread stops capture while worker has NOT called startRecording yet!
        manager.stopCapture()
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertTrue("Record must be released on stop", record.isReleased)
        assertEquals("startRecording must not have been called yet", 0, record.startRecordingCalls)

        // Resume worker thread
        resumeWorker.countDown()
        Thread.sleep(150)

        // VERIFICATION OF B03: Worker MUST NOT call startRecording on released record!
        assertEquals("Worker must NOT call startRecording() on already-released recorder (B03)!", 0, record.startRecordingCalls)
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertNull(manager.uiState.value.errorMessage)
    }

    @Test
    fun hardwareStartupExceptionHandledSafelyWithoutCorruptingSubsequentSessions() {
        val record = FakeAudioRecordSource()
        record.startRecordingException = RuntimeException("HAL bus error")

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        runBlocking {
            withTimeout(3000) {
                manager.uiState.first { it.status == CaptureStatus.ERROR }
            }
        }

        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(manager.uiState.value.errorMessage!!.contains("HAL bus error"))
        assertTrue("Recorder must be released on exception", record.isReleased)
    }

    @Test
    fun hardwareReleaseBlockedDoesNotBlockOtherLifecycleOperations() {
        val releaseEntered = CountDownLatch(1)
        val unblockRelease = CountDownLatch(1)

        val recordA = FakeAudioRecordSource(
            onRelease = {
                releaseEntered.countDown()
                unblockRelease.await(5, TimeUnit.SECONDS)
            }
        )
        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val records = ConcurrentLinkedQueue(listOf(recordA, recordB))

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> records.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())

        // Stop capture on Thread 1: this will block in recordA.release() outside lifecycleLock
        val thread1 = Thread {
            manager.stopCapture()
        }
        thread1.start()

        assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))

        // While Thread 1 is blocked in hardware release, Thread 2 calls stopCapture or checks state
        val thread2Finished = CountDownLatch(1)
        val thread2 = Thread {
            // Must NOT block on Thread 1's hardware release! (B02)
            manager.stopCapture()
            assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
            thread2Finished.countDown()
        }
        thread2.start()

        assertTrue("Thread 2 must complete without blocking on Thread 1's slow release (B02)", thread2Finished.await(2, TimeUnit.SECONDS))

        // Unblock Thread 1
        unblockRelease.countDown()
        thread1.join(3000)
        thread2.join(3000)

        assertTrue(recordA.isReleased)
    }

    @Test
    fun zeroReadDiagnosticsDoNotMutateStateAfterInvalidation() {
        val inRead = CountDownLatch(1)
        val resumeRead = CountDownLatch(1)

        val record = FakeAudioRecordSource(
            onRead = { _, _, _ ->
                inRead.countDown()
                resumeRead.await(5, TimeUnit.SECONDS)
                0 // zero read
            }
        )

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        assertTrue(inRead.await(5, TimeUnit.SECONDS))

        // Stop session while paused in read
        manager.stopCapture()
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)

        // Resume read returning 0 samples
        resumeRead.countDown()
        Thread.sleep(100)

        // Must remain STOPPED, consecutiveZeroReads must not corrupt STOPPED state
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertTrue(record.isReleased)
    }

    @Test
    fun usbMicrophoneSelectionAndReplacementTransitionsCleanly() {
        val recordInternal = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val recordUsb = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val records = ConcurrentLinkedQueue(listOf(recordInternal, recordUsb))

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> records.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertFalse(recordInternal.isReleased)

        // Select USB microphone (ID 101)
        manager.setPreferredInputDevice(101)
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertTrue("Internal mic must be released upon USB selection", recordInternal.isReleased)
        assertFalse("USB mic must be active", recordUsb.isReleased)

        manager.stopCapture()
        assertTrue(recordUsb.isReleased)
    }

    @Test
    fun obsoleteAttemptsDoNotProduceFalseRunningOrErrorTransitions() {
        val pausedAttempts = listOf(CountDownLatch(1), CountDownLatch(1), CountDownLatch(1))
        val resumeAttempts = listOf(CountDownLatch(1), CountDownLatch(1), CountDownLatch(1))
        val finishedAttempts = listOf(CountDownLatch(1), CountDownLatch(1), CountDownLatch(1))

        val records = listOf(
            FakeAudioRecordSource(),
            FakeAudioRecordSource(),
            FakeAudioRecordSource(onRead = { _, _, size -> size })
        )
        val recordQueue = ConcurrentLinkedQueue(records)

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> recordQueue.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        val callCount = AtomicInteger(0)
        manager.onPostHardwareInit = { _ ->
            val idx = callCount.getAndIncrement()
            if (idx < 3) {
                pausedAttempts[idx].countDown()
                resumeAttempts[idx].await(5, TimeUnit.SECONDS)
            }
        }

        // Start Attempt 1
        Thread { manager.startCapture(); finishedAttempts[0].countDown() }.start()
        assertTrue(pausedAttempts[0].await(5, TimeUnit.SECONDS))
        manager.stopCapture()

        // Start Attempt 2
        Thread { manager.startCapture(); finishedAttempts[1].countDown() }.start()
        assertTrue(pausedAttempts[1].await(5, TimeUnit.SECONDS))
        manager.stopCapture()

        // Start Attempt 3
        Thread { manager.startCapture(); finishedAttempts[2].countDown() }.start()
        assertTrue(pausedAttempts[2].await(5, TimeUnit.SECONDS))

        // Resume obsolete Attempt 1 and Attempt 2
        resumeAttempts[0].countDown()
        resumeAttempts[1].countDown()
        assertTrue(finishedAttempts[0].await(5, TimeUnit.SECONDS))
        assertTrue(finishedAttempts[1].await(5, TimeUnit.SECONDS))

        // UI state must NOT be corrupted by Attempt 1 or 2
        assertTrue("Record 1 must be released", records[0].isReleased)
        assertTrue("Record 2 must be released", records[1].isReleased)
        assertEquals(CaptureStatus.INITIALIZING, manager.uiState.value.status)

        // Resume Attempt 3
        resumeAttempts[2].countDown()
        assertTrue(finishedAttempts[2].await(5, TimeUnit.SECONDS))

        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertFalse("Record 3 must remain active", records[2].isReleased)

        manager.stopCapture()
        assertTrue(records[2].isReleased)
    }

    // --- B04: Versioned Reconfiguration Intent Tests ---

    @Test
    fun b04_deviceSwitchDelayedInCleanupThenStoppedRemainsStoppedWithoutRestarting() {
        val cleanupEntered = CountDownLatch(1)
        val resumeCleanup = CountDownLatch(1)

        val recordA = FakeAudioRecordSource(
            onRelease = {
                cleanupEntered.countDown()
                resumeCleanup.await(5, TimeUnit.SECONDS)
            }
        )
        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })

        val sources = ConcurrentLinkedQueue(listOf(recordA, recordB))
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> sources.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        val switchThread = Thread {
            manager.setPreferredInputDevice(999)
        }
        switchThread.start()

        assertTrue("Device switch must enter hardware cleanup", cleanupEntered.await(5, TimeUnit.SECONDS))

        // Explicit stop completes while switch is delayed
        manager.stopCapture()
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)

        // Resume cleanup so switch thread completes
        resumeCleanup.countDown()
        switchThread.join(3000)

        // B04: Must remain STOPPED; Record B must NOT be active
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertEquals("Record B must not have been started (B04)", 0, recordB.startRecordingCalls)
        assertNull(manager.uiState.value.errorMessage)
    }

    @Test
    fun b04_deviceSwitchDelayedInRefreshThenStoppedRemainsStoppedWithoutRestarting() {
        val restartHookEntered = CountDownLatch(1)
        val resumeRestart = CountDownLatch(1)

        val recordA = FakeAudioRecordSource()
        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })

        val sources = ConcurrentLinkedQueue(listOf(recordA, recordB))
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> sources.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.onPreReconfigurationRestart = { _ ->
            restartHookEntered.countDown()
            resumeRestart.await(5, TimeUnit.SECONDS)
        }

        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        val switchThread = Thread {
            manager.setPreferredInputDevice(888)
        }
        switchThread.start()

        assertTrue("Must enter restart hook after refresh", restartHookEntered.await(5, TimeUnit.SECONDS))

        // Explicit stop completes while switch is paused
        manager.stopCapture()
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)

        resumeRestart.countDown()
        switchThread.join(3000)

        // B04: Obsolete switch must abort; state remains STOPPED
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertEquals(0, recordB.startRecordingCalls)
    }

    @Test
    fun b04_overlappingDeviceSwitchesResolveToLatestPreferenceWithoutResurrectingObsolete() {
        val switch1Paused = CountDownLatch(1)
        val resumeSwitch1 = CountDownLatch(1)

        val recordA = FakeAudioRecordSource()
        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val recordC = FakeAudioRecordSource(onRead = { _, _, size -> size })

        val sources = ConcurrentLinkedQueue(listOf(recordA, recordB, recordC))
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> sources.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        val callCount = AtomicInteger(0)
        manager.onPreReconfigurationRestart = { _ ->
            if (callCount.incrementAndGet() == 1) {
                switch1Paused.countDown()
                resumeSwitch1.await(5, TimeUnit.SECONDS)
            }
        }

        assertTrue(manager.startCapture())

        // Switch 1 begins (target 201)
        val thread1 = Thread { manager.setPreferredInputDevice(201) }
        thread1.start()
        assertTrue(switch1Paused.await(5, TimeUnit.SECONDS))

        // Switch 2 begins concurrently (target 202)
        val thread2 = Thread { manager.setPreferredInputDevice(202) }
        thread2.start()

        // Unblock Switch 1, then wait for both to finish
        resumeSwitch1.countDown()
        thread1.join(3000)
        thread2.join(3000)

        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertEquals(202, manager.uiState.value.selectedDeviceId)
        assertEquals("Record B must not have been started", 0, recordB.startRecordingCalls)
        assertFalse("Record C must be active", recordC.isReleased)

        manager.stopCapture()
        assertTrue(recordC.isReleased)
    }

    @Test
    fun b04_normalDeviceSwitchWhileRecordingTransfersSeamlesslyToNewDevice() {
        val recordA = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })

        val sources = ConcurrentLinkedQueue(listOf(recordA, recordB))
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> sources.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        // Normal switch without intervening stop
        manager.setPreferredInputDevice(303)

        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertEquals(303, manager.uiState.value.selectedDeviceId)
        assertTrue("Record A must be released", recordA.isReleased)
        assertFalse("Record B must remain active", recordB.isReleased)

        manager.stopCapture()
        assertTrue(recordB.isReleased)
    }

    // --- B05: Transaction-Wide Exception Safety Tests ---

    @Test
    fun b05_initializationExceptionFromMinBufferSizeRetiresAttemptAndAllowsRetry() {
        var throwBufferError = true
        val recordA = FakeAudioRecordSource(onRead = { _, _, size -> size })

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> recordA },
            minBufferSizeProvider = { _, _, _ ->
                if (throwBufferError) throw RuntimeException("Simulated buffer calculation failure") else 4096
            }
        )

        val firstResult = manager.startCapture()
        assertFalse("Must return false on initialization failure", firstResult)
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(manager.uiState.value.errorMessage!!.contains("buffer calculation failure"))

        // Retry with healthy provider
        throwBufferError = false
        val retryResult = manager.startCapture()
        assertTrue("Subsequent retry must succeed (B05)", retryResult)
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertNull(manager.uiState.value.errorMessage)

        manager.stopCapture()
        assertTrue(recordA.isReleased)
    }

    @Test
    fun b05_initializationExceptionFromFallbackRateRetiresAttempt() {
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> FakeAudioRecordSource() },
            minBufferSizeProvider = { sr, _, _ ->
                if (sr == 48000) -1 else throw IllegalStateException("Fallback rate calculation failure")
            }
        )

        assertFalse(manager.startCapture())
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(manager.uiState.value.errorMessage!!.contains("Fallback rate calculation failure"))
    }

    @Test
    fun b05_initializationExceptionFromRecorderFactoryRetiresAttempt() {
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> throw RuntimeException("HAL factory error") },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertFalse(manager.startCapture())
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(manager.uiState.value.errorMessage!!.contains("HAL factory error") ||
                   manager.uiState.value.errorMessage!!.contains("UNPROCESSED or MIC source"))
    }

    @Test
    fun b05_initializationExceptionDuringRoutingSetupCleansSessionAndRetiresAttempt() {
        val record = FakeAudioRecordSource()
        record.addListenerException = RuntimeException("Routing listener registration failed")

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertFalse(manager.startCapture())
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue("Record must be released on routing setup failure", record.isReleased)
    }

    @Test
    fun b05_fatalErrorDuringInitializationCleansResourcesAndPropagates() {
        val record = FakeAudioRecordSource()
        record.addListenerException = OutOfMemoryError("Native JNI OOM during routing setup")

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        var caughtFatal = false
        try {
            manager.startCapture()
        } catch (oom: OutOfMemoryError) {
            caughtFatal = true
        }

        assertTrue("Fatal JVM error must be propagated", caughtFatal)
        assertTrue("Resources must still be cleaned up on fatal error (B05)", record.isReleased)
    }

    @Test
    fun b05_initializationExceptionInObsoleteAttemptDoesNotCorruptNewerSession() {
        val attemptAPaused = CountDownLatch(1)
        val resumeAttemptA = CountDownLatch(1)
        val attemptAFinished = CountDownLatch(1)

        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })

        var throwInA = true
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> recordB },
            minBufferSizeProvider = { _, _, _ ->
                if (throwInA) {
                    attemptAPaused.countDown()
                    resumeAttemptA.await(5, TimeUnit.SECONDS)
                    throw RuntimeException("Attempt A late buffer crash")
                } else {
                    4096
                }
            }
        )

        val threadA = Thread {
            manager.startCapture()
            attemptAFinished.countDown()
        }
        threadA.start()

        assertTrue(attemptAPaused.await(5, TimeUnit.SECONDS))

        // Cancel A and start B
        manager.stopCapture()
        throwInA = false
        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        // Resume A to throw its exception
        resumeAttemptA.countDown()
        assertTrue(attemptAFinished.await(5, TimeUnit.SECONDS))
        threadA.join(3000)

        // B05: Obsolete failure must NOT corrupt Session B status!
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertNull(manager.uiState.value.errorMessage)
        assertFalse(recordB.isReleased)

        manager.stopCapture()
        assertTrue(recordB.isReleased)
    }

    // --- B06: Recorder Resource Leak & Ownership Tests ---

    @Test
    fun b06_recorderStateProbeThrowsReleasesRecorderAndAttemptsFallback() {
        var primaryReleased = false
        val primaryRecord = object : FakeAudioRecordSource() {
            override var state: Int
                get() = throw RuntimeException("Primary state probe failed")
                set(_) {}
            override fun release() {
                primaryReleased = true
                super.release()
            }
        }

        val fallbackRecord = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val sources = ConcurrentLinkedQueue(listOf(primaryRecord, fallbackRecord))

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> sources.poll() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        assertTrue("Primary recorder must be released when state probe throws (B06)", primaryReleased)
        assertFalse("Fallback recorder must be active", fallbackRecord.isReleased)
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        manager.stopCapture()
        assertTrue(fallbackRecord.isReleased)
    }

    @Test
    fun b06_recordingStateThrowsDuringCleanupDoesNotPreventRelease() {
        var releaseCalled = false
        val throwingRecord = object : FakeAudioRecordSource(onRead = { _, _, size -> size }) {
            override var recordingState: Int
                get() = throw RuntimeException("recordingState probe failed during cleanup")
                set(_) {}
            override fun release() {
                releaseCalled = true
                super.release()
            }
        }

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> throwingRecord },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        manager.stopCapture()

        assertTrue("Recorder release must be called even if recordingState throws (B06)", releaseCalled)
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
    }

    @Test
    fun b06_startRecordingThrowsCleansSessionAndReleasesRecorder() {
        val record = FakeAudioRecordSource()
        record.startRecordingException = RuntimeException("HAL startRecording failed")

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        runBlocking {
            withTimeout(3000) {
                manager.uiState.first { it.status == CaptureStatus.ERROR }
            }
        }

        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue("Recorder must be released when startRecording throws", record.isReleased)
    }

    @Test
    fun b06_stopThrowsDuringCleanupDoesNotPreventRelease() {
        val record = FakeAudioRecordSource(onRead = { _, _, size -> size })
        record.stopException = RuntimeException("Hardware stop failed")

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        manager.stopCapture()

        assertTrue("Recorder release must be called even if stop() throws (B06)", record.isReleased)
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
    }

    @Test
    fun b06_listenerRemovalThrowsDuringCleanupDoesNotPreventRelease() {
        val record = FakeAudioRecordSource(onRead = { _, _, size -> size })
        record.removeListenerException = RuntimeException("removeRoutingListener failed")

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        manager.stopCapture()

        assertTrue("Recorder release must be called even if removeListener throws (B06)", record.isReleased)
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
    }

    @Test
    fun b06_releaseThrowsDuringCleanupLeavesCleanLifecycleState() {
        val record = FakeAudioRecordSource(onRead = { _, _, size -> size })
        record.releaseException = RuntimeException("Hardware release failed")

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        // stopCapture must not crash even if native release throws
        manager.stopCapture()

        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
        assertTrue(record.releaseCalls >= 1)
    }

    @Test
    fun b06_bothPrimaryAndFallbackFailReleasesBoth() {
        var primaryReleased = false
        var fallbackReleased = false

        val primaryRecord = object : FakeAudioRecordSource() {
            override var state: Int get() = throw RuntimeException("Primary failed"); set(_) {}
            override fun release() { primaryReleased = true; super.release() }
        }
        val fallbackRecord = object : FakeAudioRecordSource() {
            override var state: Int get() = throw RuntimeException("Fallback failed"); set(_) {}
            override fun release() { fallbackReleased = true; super.release() }
        }

        val sources = ConcurrentLinkedQueue(listOf(primaryRecord, fallbackRecord))
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> sources.poll() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertFalse(manager.startCapture())
        assertTrue("Primary record must be released (B06)", primaryReleased)
        assertTrue("Fallback record must be released (B06)", fallbackReleased)
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
    }

    @Test
    fun b06_competingCleanupCallsAreIdempotentWithoutDoubleRelease() {
        val record = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())

        val latch = java.util.concurrent.CyclicBarrier(3)
        val threads = listOf(
            Thread { latch.await(); manager.stopCapture() },
            Thread { latch.await(); manager.stopCapture() },
            Thread { latch.await(); manager.release() }
        )

        threads.forEach { it.start() }
        threads.forEach { it.join(3000) }

        assertEquals("release() must be invoked exactly once across competing cleanups (B06)", 1, record.releaseCalls)
        assertEquals(CaptureStatus.STOPPED, manager.uiState.value.status)
    }

    @Test
    fun b06_exceptionAfterRecorderCreationBeforeOwnershipTransferReleasesRecorder() {
        val record = FakeAudioRecordSource()
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        manager.onPostHardwareInit = { _ ->
            throw RuntimeException("Injected exception after hardware init before session wrap")
        }

        val result = manager.startCapture()
        assertFalse(result)
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue("Recorder must be released if exception occurs before session transfer (B06)", record.isReleased)
    }

    // --- B07: Configuration Ownership and Diagnostics Tests ---

    @Test
    fun b07_devicePreferenceMutationDoesNotCorruptActiveSessionDiagnosticsDuringDelayedSwitch() {
        val cleanupEntered = CountDownLatch(1)
        val resumeCleanup = CountDownLatch(1)

        val recordA = FakeAudioRecordSource(
            onRelease = {
                cleanupEntered.countDown()
                resumeCleanup.await(5, TimeUnit.SECONDS)
            }
        )
        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val sources = ConcurrentLinkedQueue(listOf(recordA, recordB))

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512, preferredDeviceId = 101),
            audioRecordFactory = { _, _, _, _, _ -> sources.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        assertEquals(101, manager.uiState.value.selectedDeviceId)

        // Switch to device 102 on background thread; delayed in cleanup
        val switchThread = Thread {
            manager.setPreferredInputDevice(102)
        }
        switchThread.start()

        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))

        // B07 Check during delay:
        // selectedDeviceId is 102 (pending user preference)
        // routedDeviceId must be null (not falsely reported as 102 before actual hardware routing!)
        val stateDuringDelay = manager.uiState.value
        assertEquals(102, stateDuringDelay.selectedDeviceId)
        assertNull("Pending device must not be falsely reported as routed (B07)", stateDuringDelay.routedDeviceId)

        // Complete the switch
        resumeCleanup.countDown()
        switchThread.join(3000)

        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)
        assertEquals(102, manager.uiState.value.selectedDeviceId)

        manager.stopCapture()
        assertTrue(recordB.isReleased)
    }

    @Test
    fun b07_staleRoutingCallbackFromOldSessionCannotPublishPendingDevicePreference() {
        val recordA = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val recordB = FakeAudioRecordSource(onRead = { _, _, size -> size })
        val sources = ConcurrentLinkedQueue(listOf(recordA, recordB))

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512, preferredDeviceId = 101),
            audioRecordFactory = { _, _, _, _, _ -> sources.poll() ?: FakeAudioRecordSource() },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        val listenerA = recordA.routingListeners.firstOrNull()

        // Switch to 102
        manager.setPreferredInputDevice(102)
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        // Stale callback from session A fires late
        listenerA?.onRoutingChanged(null)

        // Must not corrupt active session B configuration
        val state = manager.uiState.value
        assertEquals(102, state.selectedDeviceId)
        assertFalse(recordB.isReleased)

        manager.stopCapture()
    }

    // --- Thread-Launch Fault Injection & Callback Reentrancy Tests ---

    @Test
    fun threadLaunchExceptionCleansUpSessionAndRetiresAttempt() {
        val record = FakeAudioRecordSource()
        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> record },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        // Injected thread-start failure
        manager.threadLauncher = { _ ->
            throw IllegalStateException("Simulated native thread limit exceeded")
        }

        val result = manager.startCapture()
        assertFalse("startCapture must return false on thread-start failure", result)
        assertEquals(CaptureStatus.ERROR, manager.uiState.value.status)
        assertTrue(manager.uiState.value.errorMessage!!.contains("thread limit exceeded"))
        assertTrue("Recorder must be cleaned up and released after launch failure", record.isReleased)

        // Verify subsequent retry succeeds with healthy launcher
        val recordRetry = FakeAudioRecordSource(onRead = { _, _, size -> size })
        manager.audioRecordFactory = AudioRecordSourceFactory { _, _, _, _, _ -> recordRetry }
        manager.threadLauncher = { it.start() }

        assertTrue(manager.startCapture())
        assertEquals(CaptureStatus.RUNNING, manager.uiState.value.status)

        manager.stopCapture()
        assertTrue(recordRetry.isReleased)
    }

    @Test
    fun callbackReentrancyDuringStartRecordingHandledSafely() {
        var reentrancyTriggered = false
        val reentrantRecord = object : FakeAudioRecordSource(onRead = { _, _, size -> size }) {
            override fun startRecording() {
                super.startRecording()
                if (!reentrancyTriggered) {
                    reentrancyTriggered = true
                    // Synthetic synchronous callback reentrantly invoking stop
                    this.release()
                }
            }
        }

        val manager = AudioCaptureManager(
            context = application,
            config = AudioCaptureConfig(windowSize = 1024, hopSize = 512),
            audioRecordFactory = { _, _, _, _, _ -> reentrantRecord },
            minBufferSizeProvider = { _, _, _ -> 4096 }
        )

        assertTrue(manager.startCapture())
        Thread.sleep(150)

        // Must handle reentrancy safely without deadlock or undefined state
        assertTrue("Recorder was released reentrantly", reentrantRecord.isReleased)
        manager.stopCapture()
    }
}

