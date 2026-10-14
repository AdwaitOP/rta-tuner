package com.rtatuner.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import androidx.lifecycle.ViewModelStore
import com.rtatuner.app.core.audio.AudioCaptureManager
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.audio.AudioRecordSource
import com.rtatuner.app.core.audio.CaptureFingerprint
import com.rtatuner.app.core.audio.CapturedFrameContext
import com.rtatuner.app.core.audio.CaptureStatus
import com.rtatuner.app.core.calibration.CalibrationFileConvention
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.calibration.TargetCurve
import com.rtatuner.app.core.dsp.BiquadFilterBank
import com.rtatuner.app.core.dsp.DspResponseRevision
import com.rtatuner.app.core.dsp.Iso31Bands
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.ui.AudioCaptureViewModel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
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
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager

/** Independent audit only. All production manager, worker, filters, VM and calibration code executes.
 * Hardware construction/probing are the only shadows. Numerical oracle uses direct-form-I recurrence
 * rather than the production transposed-direct-form-II recurrence, and independent power arithmetic. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [NumericsAuditFactoryShadow::class, NumericsAuditMinBufferShadow::class,
    NumericsAuditAudioManagerShadow::class, NumericsAuditDeviceShadow::class], instrumentedPackages = ["com.rtatuner.app.core.audio"])
class IndependentAuditIntegrationNumericsTest {
    private val dispatcher = StandardTestDispatcher()
    private var model: AudioCaptureViewModel? = null
    private var store: ViewModelStore? = null

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        NumericsAuditFactoryShadow.pending.clear()
        NumericsAuditAudioManagerShadow.rate = 48000
        clearPreferences()
    }

    @After fun teardown() {
        disposeModel()
        NumericsAuditFactoryShadow.pending.clear()
        Dispatchers.resetMain()
    }

    private fun clearPreferences() {
        RuntimeEnvironment.getApplication().getSharedPreferences("rta_calibration_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun disposeModel() {
        store?.clear()
        dispatcher.scheduler.runCurrent()
        model = null
        store = null
    }

    private fun newModel(tier: EntitlementTier = EntitlementTier.PRO_LIFETIME): Pair<AudioCaptureViewModel, FakeEntitlementRepository> {
        val entitlements = FakeEntitlementRepository(tier)
        val vm = AudioCaptureViewModel(RuntimeEnvironment.getApplication(), entitlements)
        model = vm
        store = ViewModelStore().apply { put("independent-integration-numerics", vm) }
        dispatcher.scheduler.runCurrent()
        return vm to entitlements
    }

    private fun manager(vm: AudioCaptureViewModel): AudioCaptureManager {
        val field = AudioCaptureViewModel::class.java.getDeclaredField("captureManager")
        field.isAccessible = true
        return field.get(vm) as AudioCaptureManager
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            dispatcher.scheduler.runCurrent()
            Thread.sleep(2)
        }
        val state = model?.let { manager(it).uiState.value }
        assertTrue("Production worker did not reach expected condition; status=${state?.status} error=${state?.errorMessage} sequence=${state?.frameContext?.frameSequence}", condition())
        dispatcher.scheduler.runCurrent()
    }

    private fun publish(vm: AudioCaptureViewModel, record: NumericsAuditRecord, pcm: ShortArray, sequence: Long) {
        record.blocks.add(pcm)
        await { manager(vm).uiState.value.frameContext?.frameSequence == sequence }
        await { vm.uiState.value.frameContext?.frameSequence == sequence }
    }

    private fun fingerprint(rate: Int) = CaptureFingerprint(actualSampleRate = rate,
        routedMicrophoneId = "independent-mic", routedDeviceType = "Built-in",
        selectedAudioSource = 9, actualAudioSource = 9)

    private fun saveFloor(rate: Int, bands: FloatArray): android.content.SharedPreferences {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("numerics-audit-$rate", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("noise_floor_bands", bands.joinToString(","))
            .putLong("noise_floor_timestamp", 12345L).putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .putString("noise_floor_fingerprint", fingerprint(rate).toJsonString()).commit()
        return prefs
    }

    @Test fun all31CorrectedCoefficientsAtBothRatesMatchIndependentAnalogPrototypeAndDifferFromLegacy() {
        for (rate in intArrayOf(44100, 48000)) {
            val bank = BiquadFilterBank(rate)
            assertEquals(DspResponseRevision.CURRENT, bank.responseRevision)
            assertEquals("CABINRTA_DSP_WAVE1_BIQUAD_V1", bank.responseRevision.revisionId)
            assertEquals(31, bank.numBands)
            for (i in 0 until 31) {
                val expected = coefficients(Iso31Bands.CENTER_FREQUENCIES[i].toDouble(), rate)
                val actual = bank.getBandCoefficients(i)
                assertEquals("b0 rate=$rate band=$i", expected[0], actual.b0, 1e-13)
                assertEquals("a1 rate=$rate band=$i", expected[1], actual.a1, 1e-13)
                assertEquals("a2 rate=$rate band=$i", expected[2], actual.a2, 1e-13)
                assertEquals(0.0, responseDb(expected, Iso31Bands.CENTER_FREQUENCIES[i].toDouble(), rate), 1e-7)
                val omega = 2.0 * Math.PI * Iso31Bands.CENTER_FREQUENCIES[i] / rate
                val legacyAlpha = sin(omega) / (2.0 * BiquadFilterBank.DEFAULT_Q_ONE_THIRD_OCTAVE)
                val legacyB0 = legacyAlpha / (1.0 + legacyAlpha)
                assertTrue("Corrected coefficient cannot be legacy rate=$rate band=$i", abs(actual.b0 - legacyB0) > 1e-7)
            }
            val high = coefficients(20000.0, rate)
            assertEquals(-3.0102999566, responseDb(high, 20000.0 / 2.0.pow(1.0 / 6.0), rate), 1e-7)
            assertTrue("Near Nyquist response must strongly roll off", responseDb(high, rate * 0.49999, rate) < -80)
        }
    }

    @Test fun pcmHopOutputMatchesIndependentContinuousDirectFormReferenceAll31BandsAtBothRates() {
        for (rate in intArrayOf(44100, 48000)) {
            val bank = BiquadFilterBank(rate)
            val oracle = DirectFormOracle(rate)
            for (hop in 0 until 8) {
                val pcm = pcm(rate, hop * 1024, 1024, 0.4)
                assertArrayEquals("PCM hop rate=$rate hop=$hop", oracle.process(pcm), bank.processHop(pcm), 0.0002f)
            }
        }
    }

    @Test fun pcmThenNoiseMicSplAndTargetMathAligns31BandsAtBothRatesAndRawFftAvoidsSubtraction() {
        for (rate in intArrayOf(44100, 48000)) {
            val oracle = DirectFormOracle(rate)
            val production = BiquadFilterBank(rate)
            var raw = FloatArray(31)
            repeat(5) { hop ->
                val samples = pcm(rate, hop * 1024, 1024, 0.4)
                raw = production.processHop(samples)
                assertArrayEquals(oracle.process(samples), raw, 0.0002f)
            }
            val floor = FloatArray(31) { raw[it] - 10f - (it % 4) }
            val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
            val calibration = CalibrationManager(saveFloor(rate, floor), FeatureAccessPolicy(entitlements))
            try {
                assertTrue(calibration.loadMicCurve("Measured plus 3", "20 3\n20000 3", CalibrationFileConvention.MEASURED_RESPONSE).isSuccess)
                calibration.setSplOffset(104f)
                calibration.setSplMode(true)
                calibration.setTargetCurve(TargetCurve.FLAT)
                calibration.setTargetCurveActive(true)
                val fft = FloatArray(1025) { -70f + it % 40 }
                val context = CapturedFrameContext("numerics-owner", 1L, 1L, fingerprint(rate))
                val result = calibration.processFrame(raw, fft, rate / 2048f, context)
                assertEquals(31, result.calibrated31Db.size)
                for (i in 0 until 31) {
                    val clean = powerSubtract(raw[i], floor[i])
                    val expected = clean + (if (clean > -100f) -3f else 0f) + 104f
                    assertEquals("Band alignment rate=$rate band=$i", expected, result.calibrated31Db[i], 0.0002f)
                    assertEquals(expected - 84f, result.targetDeltas31Db!![i], 0.0002f)
                    assertFalse(result.unreliableBands[i])
                }
                assertArrayEquals(FloatArray(fft.size) { fft[it] - 3f + 104f }, result.calibratedRawFftDb, 0.0002f)
                calibration.setNoiseSubtractionActive(false)
                val withoutNoise = calibration.processFrame(raw, fft, rate / 2048f, context.copy(frameSequence = 2L))
                assertArrayEquals("FFT is invariant to noise toggle", result.calibratedRawFftDb, withoutNoise.calibratedRawFftDb, 0f)
                assertTrue(calibration.uiState.value.noiseFloor.isCaptured)
                assertArrayEquals(floor, calibration.uiState.value.noiseFloor.bands31Db, 0f)
            } finally { calibration.close() }
        }
    }

    @Test fun negativeNearZeroAndNonfiniteInputsClampPowerResidualsAndDoNotAffectRawFftAtBothRates() {
        for (rate in intArrayOf(44100, 48000)) {
            val floor = FloatArray(31) { -50f }
            floor[5] = Float.NaN
            floor[6] = Float.POSITIVE_INFINITY
            val calibration = CalibrationManager(saveFloor(rate, floor),
                FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
            try {
                val raw = FloatArray(31) { -40f }
                raw[0] = -55f; raw[1] = -50f; raw[2] = -49.999996f
                raw[3] = Float.NaN; raw[4] = Float.NEGATIVE_INFINITY
                val fft = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -42f)
                val result = calibration.processFrame(raw, fft, 20f,
                    CapturedFrameContext("numeric-owner", 1L, 1L, fingerprint(rate)))
                for (i in intArrayOf(0, 1, 2, 3, 4)) {
                    assertEquals("Residual band=$i", -100f, result.calibrated31Db[i], 0f)
                    assertTrue(result.unreliableBands[i])
                }
                for (i in 5 until 31) {
                    val safeNoise = if (floor[i].isFinite()) floor[i] else -100f
                    assertEquals(powerSubtract(raw[i], safeNoise), result.calibrated31Db[i], 0.0002f)
                }
                assertArrayEquals(floatArrayOf(-100f, -100f, -100f, -42f), result.calibratedRawFftDb, 0f)
                assertTrue(result.calibrated31Db.all { it.isFinite() })
            } finally { calibration.close() }
        }
    }

    @Test fun realPcmManagerToViewModelCalibrationBothRatesAndProFreeProMathUsesActualFrames() {
        for (rate in intArrayOf(44100, 48000)) {
            NumericsAuditAudioManagerShadow.rate = rate
            clearPreferences()
            val (vm, entitlements) = newModel()
            val recorder = NumericsAuditRecord()
            NumericsAuditFactoryShadow.pending.add(recorder)
            val oracle = DirectFormOracle(rate)
            vm.startCapture()
            val warmup = pcm(rate, 0, 1024, 0.1)
            publish(vm, recorder, warmup, 1L)
            assertArrayEquals(oracle.process(warmup), manager(vm).uiState.value.octave31Db, 0.0002f)
            assertEquals(rate, manager(vm).uiState.value.diagnostics.actualSampleRate)
            assertTrue(manager(vm).uiState.value.frameContext!!.fingerprint.isMicrophoneKnown)
            assertTrue(vm.loadMicCurve("Independent measured plus3", "20 3\n20000 3", CalibrationFileConvention.MEASURED_RESPONSE).isSuccess)
            vm.calibrationManager.setSplOffset(104f)
            vm.setSplMode(true)
            vm.setTargetCurve(TargetCurve.FLAT)
            vm.setTargetCurveActive(true)
            vm.calibrationManager.startNoiseFloorCapture(1L)
            Thread.sleep(3)
            val noise = pcm(rate, 1024, 1024, 0.1)
            val expectedFloor = oracle.process(noise)
            publish(vm, recorder, noise, 2L)
            assertTrue(vm.calibrationManager.uiState.value.noiseFloor.isCaptured)
            assertArrayEquals(expectedFloor, vm.calibrationManager.uiState.value.noiseFloor.bands31Db, 0.0002f)
            assertEquals(manager(vm).uiState.value.frameContext!!.fingerprint,
                vm.calibrationManager.uiState.value.noiseFloor.fingerprint)

            val signal = pcm(rate, 2048, 1024, 0.5)
            val expectedRaw = oracle.process(signal)
            publish(vm, recorder, signal, 3L)
            assertArrayEquals(expectedRaw, manager(vm).uiState.value.octave31Db, 0.0002f)
            val proOutput = vm.uiState.value
            for (i in 0 until 31) {
                val clean = powerSubtract(expectedRaw[i], expectedFloor[i])
                val expected = clean + (if (clean > -100f) -3f else 0f) + 104f
                assertEquals("Real calibrated band rate=$rate band=$i", expected, proOutput.octave31Db[i], 0.0003f)
                assertEquals(expected - 84f, proOutput.targetDeltas31Db!![i], 0.0003f)
            }
            val actualRawFft = manager(vm).uiState.value.rawFftDb
            assertArrayEquals(FloatArray(actualRawFft.size) { actualRawFft[it] - 3f + 104f }, proOutput.rawFftDb, 0.0002f)
            assertTrue(vm.calibrationManager.uiState.value.isNoiseSubtractionEffective)

            entitlements.setTier(EntitlementTier.FREE)
            dispatcher.scheduler.runCurrent()
            val freePcm = pcm(rate, 3072, 1024, 0.5)
            val freeRaw = oracle.process(freePcm)
            publish(vm, recorder, freePcm, 4L)
            assertArrayEquals("Free removes paid numerical processing", freeRaw, vm.uiState.value.octave31Db, 0.0002f)
            assertArrayEquals(manager(vm).uiState.value.rawFftDb, vm.uiState.value.rawFftDb, 0f)
            assertFalse(vm.calibrationManager.uiState.value.isNoiseSubtractionEffective)
            assertArrayEquals(expectedFloor, vm.calibrationManager.uiState.value.noiseFloor.bands31Db, 0.0002f)

            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            dispatcher.scheduler.runCurrent()
            val restoredPcm = pcm(rate, 4096, 1024, 0.5)
            val restoredRaw = oracle.process(restoredPcm)
            publish(vm, recorder, restoredPcm, 5L)
            for (i in 0 until 31) {
                val clean = powerSubtract(restoredRaw[i], expectedFloor[i])
                assertEquals(clean + (if (clean > -100f) -3f else 0f) + 104f, vm.uiState.value.octave31Db[i], 0.0003f)
            }
            vm.stopCapture()
            dispatcher.scheduler.runCurrent()
            assertEquals(1, recorder.releases.get())
            disposeModel()
        }
    }

    @Test fun independentRealRetryAndLivePro4096Free2048PreserveFrozenMetadataAndFreshPcmOwnership() {
        val (vm, entitlements) = newModel()
        val failed = NumericsAuditRecord().apply { failStart = true }
        val healthy = NumericsAuditRecord()
        val replacement = NumericsAuditRecord()
        NumericsAuditFactoryShadow.pending.add(failed)
        NumericsAuditFactoryShadow.pending.add(healthy)
        NumericsAuditFactoryShadow.pending.add(replacement)
        assertEquals(FeatureAccessResult.Allowed, vm.setWindowSize(4096))
        vm.startCapture()
        await { manager(vm).uiState.value.status == CaptureStatus.ERROR && failed.releases.get() == 1 }
        vm.retryCapture()
        publish(vm, healthy, pcm(48000, 0, 2048, 0.4), 1L)
        val old = manager(vm)
        val oldContext = old.uiState.value.frameContext!!
        assertEquals(2049, vm.uiState.value.rawFftDb.size)
        assertEquals(FeatureAccessResult.Allowed, vm.freezeCurrentSnapshot("Independent retained snapshot"))
        val frozen = vm.frozenSnapshots.value.single()
        entitlements.setTier(EntitlementTier.FREE)
        dispatcher.scheduler.runCurrent()
        val new = manager(vm)
        assertNotSame(old, new)
        publish(vm, replacement, pcm(48000, 0, 1024, 0.4), 1L)
        assertEquals(2048, vm.fftWindowSize.value)
        assertEquals(1025, vm.uiState.value.rawFftDb.size)
        assertEquals(1024, new.uiState.value.diagnostics.hopSize)
        assertNotEquals(oldContext.managerId, new.uiState.value.frameContext!!.managerId)
        assertEquals(1, failed.releases.get())
        assertEquals(1, healthy.releases.get())
        assertEquals(0, replacement.releases.get())
        assertEquals(listOf(frozen), vm.frozenSnapshots.value)
        assertEquals(4096, frozen.windowSize)
        vm.stopCapture()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, replacement.releases.get())
    }

    @Test fun fftOnlyManagerReplacementCancelsAcquisition() {
        val (vm, _) = newModel()
        val originalRecord = NumericsAuditRecord()
        val replacementRecord = NumericsAuditRecord()
        NumericsAuditFactoryShadow.pending.add(originalRecord)
        NumericsAuditFactoryShadow.pending.add(replacementRecord)
        vm.startCapture()
        publish(vm, originalRecord, pcm(48000, 0, 1024, 0.1), 1L)
        val auth = vm.uiState.value.captureAuthority
        vm.calibrationManager.startNoiseFloorCapture(100000L, authority = auth)
        publish(vm, originalRecord, pcm(48000, 1024, 1024, 0.1), 2L)
        val old = manager(vm)
        val oldContext = old.uiState.value.frameContext!!
        val countField = CalibrationManager::class.java.getDeclaredField("noiseAccumFrames").apply { isAccessible = true }
        val ownerField = CalibrationManager::class.java.getDeclaredField("activeNoiseCaptureManagerId").apply { isAccessible = true }
        val before = countField.getInt(vm.calibrationManager)
        assertEquals(1, before)
        assertEquals(FeatureAccessResult.Allowed, vm.setWindowSize(4096))
        val replacement = manager(vm)
        assertNotSame(old, replacement)
        await { replacementRecord.starts.get() == 1 }
        assertFalse("FFT replacement must immediately cancel acquisition", vm.calibrationManager.uiState.value.isCapturingNoiseFloor)
        assertEquals("Accumulator must be reset upon cancellation", 0, countField.getInt(vm.calibrationManager))
        assertNull("Active manager owner must be null after cancellation", ownerField.get(vm.calibrationManager))
        publish(vm, replacementRecord, pcm(48000, 0, 2048, 0.4), 1L)
        assertNotEquals(oldContext.managerId, replacement.uiState.value.frameContext!!.managerId)
        assertFalse(vm.calibrationManager.uiState.value.isCapturingNoiseFloor)
        vm.stopCapture()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, originalRecord.releases.get())
        assertEquals(1, replacementRecord.releases.get())
    }

    private fun pcm(rate: Int, offset: Int, size: Int, amplitude: Double): ShortArray = ShortArray(size) { i ->
        val t = (offset + i).toDouble() / rate
        val sum = 0.38 * sin(2 * Math.PI * 31.5 * t) + 0.3 * sin(2 * Math.PI * 1000 * t) +
            0.2 * sin(2 * Math.PI * 16000 * t) + 0.12 * cos(2 * Math.PI * 20000 * t)
        (sum * amplitude * 32767).toInt().toShort()
    }

    private fun powerSubtract(signal: Float, floor: Float): Float {
        if (!signal.isFinite() || !floor.isFinite() || signal <= floor) return -100f
        val remaining = 10.0.pow(signal / 10.0) - 10.0.pow(floor / 10.0)
        return if (remaining <= 1e-12 || !remaining.isFinite()) -100f else (10.0 * log10(remaining)).toFloat().coerceAtLeast(-100f)
    }

    private fun coefficients(center: Double, rate: Int): DoubleArray {
        val ratio = 2.0.pow(1.0 / 6.0)
        val centerWarp = tan(Math.PI * center / rate)
        val lowerWarp = tan(Math.PI * center / ratio / rate)
        val contraction = sqrt(sqrt(2.0) - 1.0)
        val lowerWidth = (centerWarp * centerWarp - lowerWarp * lowerWarp) / (lowerWarp * contraction)
        val width = if (center * ratio < 0.45 * rate) {
            val upperWarp = tan(Math.PI * center * ratio / rate)
            val upperWidth = (upperWarp * upperWarp - centerWarp * centerWarp) / (upperWarp * contraction)
            sqrt(lowerWidth * upperWidth)
        } else lowerWidth
        val denominator = 1.0 + width + centerWarp * centerWarp
        return doubleArrayOf(width / denominator, 2.0 * (centerWarp * centerWarp - 1.0) / denominator,
            (1.0 - width + centerWarp * centerWarp) / denominator)
    }

    private fun responseDb(coeff: DoubleArray, frequency: Double, rate: Int): Double {
        val w = 2.0 * Math.PI * frequency / rate
        val numReal = coeff[0] * (1.0 - cos(2 * w))
        val numImag = coeff[0] * sin(2 * w)
        val denReal = 1.0 + coeff[1] * cos(w) + coeff[2] * cos(2 * w)
        val denImag = -coeff[1] * sin(w) - coeff[2] * sin(2 * w)
        val magnitudeSquared = (numReal * numReal + numImag * numImag) / (denReal * denReal + denImag * denImag)
        return 20.0 * log10(magnitudeSquared)
    }

    private inner class DirectFormOracle(rate: Int) {
        private val coefficient = Array(31) { coefficients(Iso31Bands.CENTER_FREQUENCIES[it].toDouble(), rate) }
        private val memory = Array(31) { Array(2) { DoubleArray(4) } }
        fun process(pcm: ShortArray): FloatArray = FloatArray(31) { band ->
            val c = coefficient[band]
            var sum = 0.0
            for (sample in pcm) {
                var value = sample / 32768.0
                for (stage in 0..1) {
                    val m = memory[band][stage]
                    val out = c[0] * (value - m[1]) - c[1] * m[2] - c[2] * m[3]
                    m[1] = m[0]; m[0] = value; m[3] = m[2]; m[2] = out
                    value = out
                }
                sum += value * value
            }
            val rms = sqrt(sum / pcm.size).toFloat()
            if (!rms.isFinite() || rms <= 1e-7f) -100f else (20f * log10(rms)).coerceAtLeast(-100f)
        }
    }
}

@Implements(className = "com.rtatuner.app.core.audio.DefaultAudioRecordSourceFactory", isInAndroidSdk = false)
class NumericsAuditFactoryShadow {
    companion object { val pending = ConcurrentLinkedQueue<NumericsAuditRecord>() }
    @Implementation fun create(audioSource: Int, sampleRate: Int, channelConfig: Int, audioEncoding: Int, bufferSizeInBytes: Int): AudioRecordSource =
        pending.poll() ?: throw AssertionError("Unexpected recorder construction")
}

@Implements(AudioRecord::class)
class NumericsAuditMinBufferShadow {
    companion object {
        @JvmStatic @Implementation fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 4096
    }
}

@Implements(AudioManager::class)
class NumericsAuditAudioManagerShadow : ShadowAudioManager() {
    companion object { var rate = 48000 }
    @Implementation fun getProperty(key: String): String = rate.toString()
    @Implementation override fun getDevices(flags: Int): Array<AudioDeviceInfo> = emptyArray()
}

/** Supply all actual-route getters at the Android hardware boundary rather than relying on
 * a builder's partially populated AudioPort internals. */
@Implements(AudioDeviceInfo::class)
class NumericsAuditDeviceShadow {
    @Implementation fun getId(): Int = 91
    @Implementation fun getProductName(): CharSequence = "Independent numerics microphone"
    @Implementation fun getType(): Int = AudioDeviceInfo.TYPE_BUILTIN_MIC
    @Implementation fun getSampleRates(): IntArray = intArrayOf(NumericsAuditAudioManagerShadow.rate)
    @Implementation fun getChannelCounts(): IntArray = intArrayOf(1)
    @Implementation fun isSource(): Boolean = true
}

@Suppress("DEPRECATION")
class NumericsAuditRecord : AudioRecordSource {
    override val state: Int = AudioRecord.STATE_INITIALIZED
    @Volatile private var recording = AudioRecord.RECORDSTATE_STOPPED
    override val recordingState: Int get() = recording
    override val routedDevice: AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
    val blocks = LinkedBlockingQueue<ShortArray>()
    val releases = AtomicInteger()
    val starts = AtomicInteger()
    @Volatile var released = false
    var failStart = false
    override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean = true
    override fun addOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener, handler: Handler?) = Unit
    override fun removeOnRoutingChangedListener(listener: AudioRecord.OnRoutingChangedListener) = Unit
    override fun startRecording() {
        if (failStart) throw IllegalStateException("Independent numerics native start failure")
        check(!released)
        starts.incrementAndGet()
        recording = AudioRecord.RECORDSTATE_RECORDING
    }
    override fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, readMode: Int): Int {
        while (!released) {
            val next = blocks.poll(20, TimeUnit.MILLISECONDS) ?: continue
            check(next.size == sizeInShorts) { "Controlled PCM block must fill exactly requested hop" }
            next.copyInto(audioData, offsetInShorts)
            return next.size
        }
        return AudioRecord.ERROR_INVALID_OPERATION
    }
    override fun stop() { recording = AudioRecord.RECORDSTATE_STOPPED }
    override fun release() { releases.incrementAndGet(); released = true; recording = AudioRecord.RECORDSTATE_STOPPED }
}
