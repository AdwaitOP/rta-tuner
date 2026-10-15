package com.rtatuner.app

import android.content.Context
import com.rtatuner.app.core.audio.*
import com.rtatuner.app.core.calibration.CalibrationManager
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class IndependentCoherentAuthoritySnapshotTest {
    private fun fingerprint(mic:String)=CaptureFingerprint(actualSampleRate=48000,
        routedMicrophoneId=mic,routedDeviceType="Built-in",selectedAudioSource=6,actualAudioSource=6)
    private fun authority(mic:String,epoch:Long)=CaptureAuthoritySnapshot("owner",2,epoch,fingerprint(mic),true,20)
    private fun frame(mic:String,epoch:Long)=CapturedFrameContext("owner",2,21,fingerprint(mic),true,epoch)

    private class UpdaterGateRepository:EntitlementRepository {
        private val backing=MutableStateFlow(EntitlementState(EntitlementTier.PRO_LIFETIME))
        val entered=CountDownLatch(1);val resume=CountDownLatch(1)
        @Volatile var updater:Thread?=null
        override val state:StateFlow<EntitlementState> = object:StateFlow<EntitlementState> by backing {
            override val value:EntitlementState get(){
                // Delegate identical current Pro data, pausing only authority UPDATE.
                // The processing thread's fresh policy check is never paused.
                if(Thread.currentThread()===updater && entered.count>0){
                    entered.countDown();check(resume.await(8,TimeUnit.SECONDS))
                }
                return backing.value
            }
        }
    }

    @Test fun newBFrameCannotUseAFloorWhileBCurrentAuthorityAndEffectiveStatePublicationOverlap()=runTest {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("coherent-authority-${UUID.randomUUID()}",Context.MODE_PRIVATE)
        check(prefs.edit().putString("noise_floor_bands",FloatArray(31){-50f}.joinToString(","))
            .putLong("noise_floor_timestamp",777).putBoolean("noise_floor_captured",true)
            .putBoolean("noise_subtraction_active",true).putString("noise_floor_fingerprint",fingerprint("A").toJsonString()).commit())
        val before=prefs.all.toMap();val ent=UpdaterGateRepository()
        val m=CalibrationManager(prefs,FeatureAccessPolicy(ent),backgroundScope)
        fun process(mic:String,epoch:Long)=m.processFrame(FloatArray(31){-40f},FloatArray(8){-40f},100f,frame(mic,epoch)).calibrated31Db
        var updaterError:Throwable?=null
        val updater=Thread({try{m.updateCaptureAuthority(authority("B",4))}catch(t:Throwable){updaterError=t}},"authority-B-updater")
        try {
            m.updateCaptureAuthority(authority("A",3));runCurrent()
            assertTrue("A setup must positively authorize subtraction",m.uiState.value.isNoiseSubtractionEffective)
            assertTrue(process("A",3)[0] < -40f)
            ent.updater=updater;updater.start();assertTrue("Updater reached effective-state policy recomputation",ent.entered.await(8,TimeUnit.SECONDS))
            // Read-only assertion of the public updater's already-written volatile field.
            val authorityField=m.javaClass.getDeclaredField("currentAuthority").also{it.isAccessible=true}
            assertEquals(authority("B",4),authorityField.get(m))
            // This B frame enters only NOW, with correct B fingerprint and route epoch.
            val during=process("B",4)
            println("COHERENT_SNAPSHOT during=${during[0]} currentMic=B savedFloorMic=A oldUiEffective=${m.uiState.value.isNoiseSubtractionEffective}")
            ent.resume.countDown();updater.join(8000);assertFalse(updater.isAlive);updaterError?.let{throw it}
            assertFalse("Completed B publication must be incompatible with A floor",m.uiState.value.isNoiseSubtractionEffective)
            assertTrue(m.uiState.value.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
            assertArrayEquals(FloatArray(31){-40f},process("B",4),0f)
            assertEquals(before,prefs.all)
            assertArrayEquals("A compatible snapshot may not authorize a newly entered B frame",FloatArray(31){-40f},during,0f)
        } finally {ent.resume.countDown();updater.join(8000);m.close()}
    }
}
