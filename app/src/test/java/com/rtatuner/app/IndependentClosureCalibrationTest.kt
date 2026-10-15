package com.rtatuner.app

import android.content.Context
import android.content.SharedPreferences
import com.rtatuner.app.core.audio.*
import com.rtatuner.app.core.calibration.*
import com.rtatuner.app.core.entitlement.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.pow

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class IndependentClosureCalibrationTest {
    private fun fp(mic: String="A") = CaptureFingerprint(actualSampleRate=48000,
        routedMicrophoneId=mic,routedDeviceType="Built-in",selectedAudioSource=6,actualAudioSource=6)
    private fun auth(running: Boolean=true, mic: String="A",epoch: Long=3) =
        CaptureAuthoritySnapshot("owner",2,epoch,fp(mic),running,20)
    private fun ctx(mic: String="A",epoch: Long=3) = CapturedFrameContext("owner",2,21,fp(mic),true,epoch)
    private fun prefs(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("closure-calibration-${UUID.randomUUID()}",Context.MODE_PRIVATE).also {
            check(it.edit().putString("noise_floor_bands",FloatArray(31){-50f}.joinToString(","))
                .putLong("noise_floor_timestamp",777).putBoolean("noise_floor_captured",true)
                .putBoolean("noise_subtraction_active",true).putString("noise_floor_fingerprint",fp().toJsonString()).commit())
        }
    private fun output(m:CalibrationManager,c:CapturedFrameContext?=ctx()) =
        m.processFrame(FloatArray(31){-40f},FloatArray(8){-40f},100f,c)
    private fun raw(m:CalibrationManager,c:CapturedFrameContext?=ctx()) =
        assertArrayEquals(FloatArray(31){-40f},output(m,c).calibrated31Db,0f)
    private fun cleaned(m:CalibrationManager,c:CapturedFrameContext?=ctx()) =
        assertArrayEquals(FloatArray(31){cleanValue()},output(m,c).calibrated31Db,0.0001f)
    private fun cleanValue()=(10.0*log10(10.0.pow(-4.0)-10.0.pow(-5.0))).toFloat()

    @Test fun matchingAuthorityPositiveAndOldRouteFramesRejectedIncludingABA()=runTest {
        val m=CalibrationManager(prefs(),FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),backgroundScope)
        try {
            m.updateCaptureAuthority(auth()); cleaned(m)
            m.updateCaptureAuthority(auth(mic="B",epoch=4)); raw(m); raw(m,ctx("B",4))
            assertFalse(m.uiState.value.isNoiseSubtractionEffective)
            m.updateCaptureAuthority(auth(epoch=5)); raw(m); cleaned(m,ctx(epoch=5))
            assertTrue(m.uiState.value.isNoiseSubtractionEffective)
        } finally {m.close()}
    }

    @Test fun entitlementProjectionEventuallyConvergesWithoutRewritingRequestedPreference()=runTest {
        val p=prefs(); val before=p.all.toMap(); val ent=FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val m=CalibrationManager(p,FeatureAccessPolicy(ent),backgroundScope)
        try {
            m.updateCaptureAuthority(auth()); cleaned(m); runCurrent()
            ent.setTier(EntitlementTier.FREE); raw(m)
            println("BEFORE_FREE_OBSERVER entitlement=${ent.state.value.tier} active=${m.uiState.value.isNoiseSubtractionActive} effective=${m.uiState.value.isNoiseSubtractionEffective}")
            runCurrent(); assertFalse(m.uiState.value.isNoiseSubtractionActive);assertFalse(m.uiState.value.isNoiseSubtractionEffective)
            assertTrue(p.getBoolean("noise_subtraction_active",false));assertEquals(before,p.all)
            ent.setTier(EntitlementTier.PRO_LIFETIME);raw(m)
            runCurrent();cleaned(m);assertTrue(m.uiState.value.isNoiseSubtractionEffective);assertEquals(before,p.all)
        } finally {m.close()}
    }

    @Test fun delayedNoAuthorityProcessingCannotOverrideNewerRouteUi()=runTest {
        val m=CalibrationManager(prefs(),FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),backgroundScope)
        val entered=CountDownLatch(1);val resume=CountDownLatch(1)
        val field=m.javaClass.getDeclaredField("_uiState").also {it.isAccessible=true}
        @Suppress("UNCHECKED_CAST") val backing=field.get(m) as MutableStateFlow<CalibrationUiState>
        var workerThread:Thread?=null
        val wrapper=object:MutableStateFlow<CalibrationUiState> by backing {
            override var value:CalibrationUiState
                get(){if(Thread.currentThread()===workerThread && entered.count>0){entered.countDown();check(resume.await(8,TimeUnit.SECONDS))};return backing.value}
                set(v){backing.value=v}
        }
        field.set(m,wrapper)
        var result:CalibratedSpectralFrame?=null;var failure:Throwable?=null
        val worker=Thread({try{result=output(m)}catch(t:Throwable){failure=t}},"delayed-no-authority-route");workerThread=worker
        try {
            worker.start();assertTrue(entered.await(8,TimeUnit.SECONDS))
            m.updateCaptureAuthority(auth(mic="B",epoch=4));val current=m.uiState.value
            assertFalse(current.isNoiseSubtractionEffective);assertTrue(current.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
            resume.countDown();worker.join(8000);assertFalse(worker.isAlive);failure?.let{throw it}
            assertArrayEquals(FloatArray(31){-40f},result!!.calibrated31Db,0f)
            assertEquals(current,m.uiState.value)
        } finally{resume.countDown();worker.join(8000);m.close()}
    }

    // Getter delegates identical entitlement data. It only places a deterministic barrier at
    // the one per-frame policy read, after effectiveFrame/currentAuthority were sampled.
    private class GateRepository:EntitlementRepository {
        val backing=MutableStateFlow(EntitlementState(EntitlementTier.PRO_LIFETIME))
        val entered=CountDownLatch(1);val resume=CountDownLatch(1)
        @Volatile var worker:Thread?=null
        override val state:StateFlow<EntitlementState> = object:StateFlow<EntitlementState> by backing {
            override val value:EntitlementState get(){
                if(Thread.currentThread()===worker && entered.count>0){entered.countDown();check(resume.await(8,TimeUnit.SECONDS))}
                return backing.value
            }
        }
    }
    private fun delayed(kind:String)=runTest {
        val ent=GateRepository();val p=prefs();val before=p.all.toMap()
        val m=CalibrationManager(p,FeatureAccessPolicy(ent),backgroundScope)
        m.updateCaptureAuthority(auth());runCurrent()
        var result:CalibratedSpectralFrame?=null;var failure:Throwable?=null
        val worker=Thread({try{result=output(m)}catch(t:Throwable){failure=t}},"delayed-live-$kind");ent.worker=worker
        try {
            worker.start();assertTrue(ent.entered.await(8,TimeUnit.SECONDS))
            when(kind){"stop"->m.updateCaptureAuthority(auth(running=false));"route"->m.updateCaptureAuthority(auth(mic="B",epoch=4));"revoke"->{ent.backing.value=EntitlementState(EntitlementTier.FREE);runCurrent()}}
            val newer=m.uiState.value;assertFalse(newer.isNoiseSubtractionEffective)
            ent.resume.countDown();worker.join(8000);assertFalse(worker.isAlive);failure?.let{throw it}
            assertEquals("In-flight processing cannot write over newer UI",newer,m.uiState.value)
            println("IN_FLIGHT_$kind output=${result!!.calibrated31Db[0]} newerEffective=${newer.isNoiseSubtractionEffective}")
            if(kind=="revoke")assertArrayEquals(FloatArray(31){-40f},result!!.calibrated31Db,0f)
            raw(m);assertEquals(before,p.all)
        } finally {ent.resume.countDown();worker.join(8000);m.close()}
    }
    @Test fun delayedLiveProcessingVersusStopPreservesNewerUi()=delayed("stop")
    @Test fun delayedLiveProcessingVersusRoutePreservesNewerUi()=delayed("route")
    @Test fun delayedLiveProcessingVersusRevocationUsesCurrentEntitlement()=delayed("revoke")

    @Test fun revocationBeforeObserverMustDisableMicSplAndAdvancedTargetAtProcessingBoundary()=runTest {
        val p=prefs();check(p.edit().putString("mic_curve_name","constant+3")
            .putString("mic_curve_points","[{\"f\":20,\"o\":3},{\"f\":20000,\"o\":3}]")
            .putBoolean("mic_curve_active",true).putBoolean("spl_mode",true).putFloat("spl_offset_db",100f)
            .putString("target_curve_id",TargetCurve.FLAT.id).putBoolean("target_curve_active",true).commit())
        val ent=FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME);val before=p.all.toMap()
        val m=CalibrationManager(p,FeatureAccessPolicy(ent),backgroundScope)
        try {
            m.updateCaptureAuthority(auth());runCurrent();ent.setTier(EntitlementTier.FREE)
            val frame=output(m);println("PRE_OBSERVER_PREMIUM output=${frame.calibrated31Db[0]} spl=${frame.isSplMode} target=${frame.targetDeltas31Db!=null}")
            assertArrayEquals("Free processing must bypass stored premium numerical transforms immediately",FloatArray(31){-40f},frame.calibrated31Db,0f)
            assertArrayEquals(FloatArray(8){-40f},frame.calibratedRawFftDb,0f)
            assertFalse(frame.isSplMode);assertNull(frame.targetDeltas31Db);assertEquals(before,p.all)
        }finally{m.close()}
    }
}
