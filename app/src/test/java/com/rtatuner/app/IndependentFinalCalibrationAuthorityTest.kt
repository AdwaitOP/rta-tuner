package com.rtatuner.app

import android.content.Context
import android.content.SharedPreferences
import com.rtatuner.app.core.audio.*
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.entitlement.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.math.log10
import kotlin.math.pow

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IndependentFinalCalibrationAuthorityTest {
    private fun fp(mic: String = "live-A") = CaptureFingerprint(actualSampleRate=48000,
        routedMicrophoneId=mic, routedDeviceType="Built-in", selectedAudioSource=6, actualAudioSource=6)
    private fun auth(running: Boolean = true, fingerprint: CaptureFingerprint? = fp(), manager: String="owner-A",
        generation: Long=2, epoch: Long=3, sequence: Long=20) =
        CaptureAuthoritySnapshot(manager, generation, epoch, fingerprint, running, sequence)
    private fun ctx(sequence: Long=21, running: Boolean=true, fingerprint: CaptureFingerprint=fp(),
        manager: String="owner-A", generation: Long=2, epoch: Long=3) =
        CapturedFrameContext(manager,generation,sequence,fingerprint,running,epoch)
    private fun preferences(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("final-authority-${UUID.randomUUID()}", Context.MODE_PRIVATE).also {
            check(it.edit().putString("noise_floor_bands", FloatArray(31){-50f}.joinToString(","))
                .putLong("noise_floor_timestamp", 777L).putBoolean("noise_floor_captured", true)
                .putBoolean("noise_subtraction_active", true).putString("noise_floor_fingerprint", fp().toJsonString()).commit())
        }
    private fun output(m: CalibrationManager, c: CapturedFrameContext?, db: Float=-40f) =
        m.processFrame(FloatArray(31){db},FloatArray(8){db},100f,c).calibrated31Db
    private fun bypass(m: CalibrationManager, c: CapturedFrameContext?) =
        assertArrayEquals("No current verified running capture means raw bands",FloatArray(31){-40f},output(m,c),0f)
    private fun clean(m: CalibrationManager, c: CapturedFrameContext?) {
        val expected = (10.0*log10(10.0.pow(-4.0)-10.0.pow(-5.0))).toFloat()
        assertArrayEquals(FloatArray(31){expected}, output(m,c),0.0001f)
    }
    private fun policy() = FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME))

    @Test fun noAuthorityPlusValidLookingFrameCannotAuthorizeNumericalSubtraction() = runTest {
        val m=CalibrationManager(preferences(),policy(),backgroundScope)
        try { assertFalse(m.uiState.value.isNoiseSubtractionEffective); bypass(m,ctx()) }
        finally { m.close() }
    }
    @Test fun noAuthorityPlusValidLookingFrameCannotPromoteUiToEffective() = runTest {
        val m=CalibrationManager(preferences(),policy(),backgroundScope)
        try { output(m,ctx()); assertFalse("Historical metadata cannot promote effective UI", m.uiState.value.isNoiseSubtractionEffective) }
        finally { m.close() }
    }
    @Test fun stoppedAuthorityRejectsMatchingHistoricalFrame() = runTest {
        val m=CalibrationManager(preferences(),policy(),backgroundScope)
        try { m.updateCaptureAuthority(auth()); clean(m,ctx()); m.updateCaptureAuthority(auth(running=false)); bypass(m,ctx()); assertFalse(m.uiState.value.isNoiseSubtractionEffective) }
        finally { m.close() }
    }
    @Test fun replacementAuthorityRejectsOldManagerFrame() = runTest {
        val m=CalibrationManager(preferences(),policy(),backgroundScope)
        try { m.updateCaptureAuthority(auth()); clean(m,ctx()); m.updateCaptureAuthority(auth(manager="owner-B")); bypass(m,ctx()) }
        finally { m.close() }
    }
    @Test fun explicitNonRunningFrameCannotSubtract() = runTest {
        val m=CalibrationManager(preferences(),policy(),backgroundScope)
        try { m.updateCaptureAuthority(auth()); bypass(m,ctx(running=false)) }
        finally { m.close() }
    }
    @Test fun nullFrameContextCannotSubtract() = runTest {
        val m=CalibrationManager(preferences(),policy(),backgroundScope)
        try { m.updateCaptureAuthority(auth()); clean(m,ctx()); bypass(m,null) }
        finally { m.close() }
    }
    @Test fun clearingAuthorityAfterValidSessionCannotAuthorizeHistoricalFrame() = runTest {
        val m=CalibrationManager(preferences(),policy(),backgroundScope)
        try { m.updateCaptureAuthority(auth()); clean(m,ctx())
            // Explicitly exercise the nullable authority state requested by the audit.
            m.javaClass.getDeclaredField("currentAuthority").also { it.isAccessible=true }.set(m,null)
            bypass(m,ctx())
        } finally { m.close() }
    }
    @Test fun revocationBeforeEntitlementObserverRunsCannotSubtract() = runTest {
        val ent=FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val m=CalibrationManager(preferences(),FeatureAccessPolicy(ent),backgroundScope)
        try { m.updateCaptureAuthority(auth()); runCurrent(); clean(m,ctx()); ent.setTier(EntitlementTier.FREE)
            // Deliberately do not dispatch the observer; operation must fail closed now.
            bypass(m,ctx())
        } finally { m.close() }
    }
    @Test fun matchingOwnerGenerationEpochWithWrongFingerprintCannotSubtract() = runTest {
        val m=CalibrationManager(preferences(),policy(),backgroundScope)
        try { m.updateCaptureAuthority(auth()); bypass(m,ctx(fingerprint=fp("other-B"))) }
        finally { m.close() }
    }
    @Test fun stoppedInitializingAndUnknownAuthoritiesDenyAcquisitionPreservingFloor() = runTest {
        val p=preferences(); val before=p.all.toMap(); val m=CalibrationManager(p,policy(),backgroundScope)
        try { for(a in listOf(auth(running=false),auth(fingerprint=null),auth(generation=0),auth(epoch=0),
            auth(fingerprint=fp(CaptureFingerprint.UNKNOWN_MICROPHONE_ID)))) {
                m.updateCaptureAuthority(a)
                assertTrue(m.startNoiseFloorCapture(1) is FeatureAccessResult.Denied)
                assertFalse(m.uiState.value.isCapturingNoiseFloor); assertEquals(before,p.all)
            }
        } finally { m.close() }
    }
    @Test fun requestBoundSequenceRejectsPreRequestReplayAndDuplicateThenCapturesNewPowerAverage() = runTest {
        var now=1000L; val m=CalibrationManager(preferences(),policy(),backgroundScope,nowMs={now})
        try { m.updateCaptureAuthority(auth()); assertEquals(FeatureAccessResult.Allowed,m.startNoiseFloorCapture(100))
            output(m,ctx(sequence=20),-5f); output(m,ctx(sequence=21),-40f)
            output(m,ctx(sequence=21),-5f); now+=101
            output(m,ctx(sequence=22),-50f)
            val expected=(10.0*log10((10.0.pow(-4.0)+10.0.pow(-5.0))/2)).toFloat()
            assertArrayEquals(FloatArray(31){expected},m.uiState.value.noiseFloor.bands31Db,0.0001f)
        } finally { m.close() }
    }
    @Test fun authorityLossAndEpochOwnerGenerationChangesCancelImmediatelyPreservingFloor() = runTest {
        val p=preferences(); val before=p.all.toMap(); val m=CalibrationManager(p,policy(),backgroundScope)
        try { for(a in listOf(auth(running=false),auth(fingerprint=null),auth(manager="replacement"),
            auth(generation=3),auth(epoch=4),auth(fingerprint=fp("other-B")))) {
                m.updateCaptureAuthority(auth()); assertEquals(FeatureAccessResult.Allowed,m.startNoiseFloorCapture(100))
                output(m,ctx()); m.updateCaptureAuthority(a)
                assertFalse(m.uiState.value.isCapturingNoiseFloor); assertEquals(before,p.all)
            }
        } finally { m.close() }
    }
    @Test fun invalidFrameContextsCancelAcquisitionPreservingExistingFloor() = runTest {
        val p=preferences(); val before=p.all.toMap(); val m=CalibrationManager(p,policy(),backgroundScope)
        try { for(c in listOf(null,ctx(running=false),ctx(manager="replacement"),ctx(generation=3),ctx(epoch=4))) {
                m.updateCaptureAuthority(auth()); m.startNoiseFloorCapture(100); output(m,c)
                assertFalse(m.uiState.value.isCapturingNoiseFloor); assertEquals(before,p.all)
            }
        } finally { m.close() }
    }
    @Test fun delayedFallbackFrameCannotOverrideNewerStoppedAuthorityUi() = runTest {
        val m=CalibrationManager(preferences(),policy(),backgroundScope)
        val entered=java.util.concurrent.CountDownLatch(1); val resume=java.util.concurrent.CountDownLatch(1)
        val field=m.javaClass.getDeclaredField("_uiState").also { it.isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val original=field.get(m) as kotlinx.coroutines.flow.MutableStateFlow<com.rtatuner.app.core.calibration.CalibrationUiState>
        var processingThread: Thread?=null
        val wrapper=object : kotlinx.coroutines.flow.MutableStateFlow<com.rtatuner.app.core.calibration.CalibrationUiState> by original {
            override var value: com.rtatuner.app.core.calibration.CalibrationUiState
                get() {
                    if(Thread.currentThread() === processingThread && entered.count > 0) {
                        entered.countDown(); check(resume.await(8,java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return original.value
                }
                set(v) { original.value=v }
        }
        // Test-only scheduling seam: same StateFlow/backing data, pause precisely at fallback UI read.
        field.set(m,wrapper)
        var error: Throwable?=null
        val worker=Thread({try { output(m,ctx()) } catch(t:Throwable){error=t}},"delayed-fallback")
        processingThread=worker
        try {
            worker.start(); assertTrue(entered.await(8,java.util.concurrent.TimeUnit.SECONDS))
            m.updateCaptureAuthority(auth(running=false,manager="new-owner"))
            assertFalse(m.uiState.value.isNoiseSubtractionEffective)
            resume.countDown(); worker.join(8000); assertFalse(worker.isAlive); if(error!=null) throw error!!
            assertFalse("Older fallback must not overwrite newer stopped authority UI",m.uiState.value.isNoiseSubtractionEffective)
        } finally { resume.countDown(); worker.join(8000); m.close() }
    }
    @Test fun entitlementRevokedDuringFallbackCannotReauthorizeSubtractionUi() = runTest {
        val ent=FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val m=CalibrationManager(preferences(),FeatureAccessPolicy(ent),backgroundScope)
        val entered=java.util.concurrent.CountDownLatch(1); val resume=java.util.concurrent.CountDownLatch(1)
        val field=m.javaClass.getDeclaredField("_uiState").also { it.isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val original=field.get(m) as kotlinx.coroutines.flow.MutableStateFlow<com.rtatuner.app.core.calibration.CalibrationUiState>
        var processingThread: Thread?=null
        val wrapper=object : kotlinx.coroutines.flow.MutableStateFlow<com.rtatuner.app.core.calibration.CalibrationUiState> by original {
            override var value: com.rtatuner.app.core.calibration.CalibrationUiState
                get() {
                    if(Thread.currentThread() === processingThread && entered.count > 0) {
                        entered.countDown(); check(resume.await(8,java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return original.value
                }
                set(v) { original.value=v }
        }
        field.set(m,wrapper)
        var result: FloatArray?=null; var error: Throwable?=null
        val worker=Thread({try { result=output(m,ctx()) } catch(t:Throwable){error=t}},"revoked-fallback")
        processingThread=worker
        try {
            worker.start(); assertTrue(entered.await(8,java.util.concurrent.TimeUnit.SECONDS))
            ent.setTier(EntitlementTier.FREE); runCurrent()
            assertFalse(m.uiState.value.isNoiseSubtractionActive); assertFalse(m.uiState.value.isNoiseSubtractionEffective)
            resume.countDown(); worker.join(8000); assertFalse(worker.isAlive); if(error!=null) throw error!!
            assertFalse("Processing frame must not reauthorize effective UI after completed Pro revocation",m.uiState.value.isNoiseSubtractionEffective)
            assertArrayEquals(FloatArray(31){-40f},result,0f)
        } finally { resume.countDown(); worker.join(8000); m.close() }
    }
    @Test fun entitlementRevokedInsideFinalizingFrameCannotOverwriteExistingFloor() = runTest {
        val ent=FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME); val p=preferences(); val before=p.all.toMap()
        var now=1000L; var revokeInsideClock=false
        val m=CalibrationManager(p,FeatureAccessPolicy(ent),backgroundScope,nowMs={
            if(revokeInsideClock) ent.setTier(EntitlementTier.FREE)
            now
        })
        try { m.updateCaptureAuthority(auth()); m.startNoiseFloorCapture(100); now+=101; revokeInsideClock=true
            output(m,ctx(),-80f)
            assertEquals(before,p.all); assertFalse(m.uiState.value.isCapturingNoiseFloor)
        } finally { m.close() }
    }
    @Test fun freeProTransitionPreservesRequestedToggleAndStoredFloor() = runTest {
        val ent=FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME); val p=preferences(); val before=p.all.toMap()
        val m=CalibrationManager(p,FeatureAccessPolicy(ent),backgroundScope)
        try { m.updateCaptureAuthority(auth()); clean(m,ctx()); ent.setTier(EntitlementTier.FREE); runCurrent()
            bypass(m,ctx()); assertFalse(m.uiState.value.isNoiseSubtractionEffective)
            ent.setTier(EntitlementTier.PRO_LIFETIME); runCurrent(); clean(m,ctx())
            assertTrue(m.uiState.value.isNoiseSubtractionEffective); assertEquals(before,p.all)
        } finally { m.close() }
    }
}



