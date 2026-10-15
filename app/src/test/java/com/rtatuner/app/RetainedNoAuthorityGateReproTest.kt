package com.rtatuner.app
import android.content.Context
import com.rtatuner.app.core.audio.CaptureFingerprint
import com.rtatuner.app.core.audio.CapturedFrameContext
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.entitlement.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RetainedNoAuthorityGateReproTest {
    @Test fun frameMetadataWithoutLiveAuthorityMustNotSubtract() = runTest {
        val fp=CaptureFingerprint(actualSampleRate=48000,routedMicrophoneId="A",routedDeviceType="Built-in",selectedAudioSource=6,actualAudioSource=6)
        val p=RuntimeEnvironment.getApplication().getSharedPreferences("retained-gate-${UUID.randomUUID()}",Context.MODE_PRIVATE)
        p.edit().putString("noise_floor_bands",FloatArray(31){-50f}.joinToString(",")).putBoolean("noise_floor_captured",true)
            .putLong("noise_floor_timestamp",777).putBoolean("noise_subtraction_active",true).putString("noise_floor_fingerprint",fp.toJsonString()).commit()
        val m=CalibrationManager(p,FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)),backgroundScope)
        try {
            val context=CapturedFrameContext("A",1,1,fp)
            val out=m.processFrame(FloatArray(31){-40f},FloatArray(8){-40f},100f,context)
            assertArrayEquals("Absent live capture authority must bypass subtraction",FloatArray(31){-40f},out.calibrated31Db,0f)
        } finally { m.close() }
    }
}
