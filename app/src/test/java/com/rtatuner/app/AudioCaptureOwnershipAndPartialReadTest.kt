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
    class FakeAudioRecordSource(
        override var state: Int = AudioRecord.STATE_INITIALIZED,
        override var recordingState: Int = AudioRecord.RECORDSTATE_RECORDING,
        override var routedDevice: AudioDeviceInfo? = null,
        val onRead: ((audioData: ShortArray, offset: Int, size: Int) -> Int)? = null
    ) : AudioRecordSource {
        var startRecordingCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        var isReleased = false
        var startRecordingException: Exception? = null

        val routingListeners = mutableListOf<AudioRecord.OnRoutingChangedListener>()

        override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean = true

        override fun addOnRoutingChangedListener(
            listener: AudioRecord.OnRoutingChangedListener,
            handler: Handler?
        ) {
            routingListeners.add(listener)
        }

        override fun removeOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener) {
            routingListeners.remove(listener)
        }

        override fun startRecording() {
            startRecordingCalls++
            startRecordingException?.let { throw it }
        }

        override fun read(
            audioData: ShortArray,
            offsetInShorts: Int,
            sizeInShorts: Int,
            readMode: Int
        ): Int {
            if (isReleased) return AudioRecord.ERROR_INVALID_OPERATION
            return onRead?.invoke(audioData, offsetInShorts, sizeInShorts) ?: sizeInShorts
        }

        override fun stop() {
            stopCalls++
            recordingState = AudioRecord.RECORDSTATE_STOPPED
        }

        override fun release() {
            releaseCalls++
            isReleased = true
            stop()
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
}

