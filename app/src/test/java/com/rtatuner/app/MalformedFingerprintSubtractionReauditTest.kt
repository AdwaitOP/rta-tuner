package com.rtatuner.app
import android.content.Context
import com.rtatuner.app.core.audio.*
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.entitlement.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MalformedFingerprintSubtractionReauditTest {
    private fun fp() = CaptureFingerprint(actualSampleRate = 48000, routedMicrophoneId = "17:strict persistence actual microphone", routedDeviceType = "Built-in", selectedAudioSource = 6, actualAudioSource = 6, effects = CaptureEffectsInfo(false, false, false, true))
    private fun assertCannotSubtract(raw: String) {
        val p = RuntimeEnvironment.getApplication().getSharedPreferences("malformed-reaudit-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        p.edit().putString("noise_floor_bands", FloatArray(31) { -50f }.joinToString(",")).putLong("noise_floor_timestamp", 777L).putBoolean("noise_floor_captured", true).putBoolean("noise_subtraction_active", true).putString("noise_floor_fingerprint", raw).commit()
        val before = p.all.toMap()
        val failures = mutableListOf<Float>()
        repeat(2) {
            val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
            try {
                manager.updateCaptureAuthority(CaptureAuthoritySnapshot("strict-live", 1L, 1L, fp(), true, 0L))
                val out = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, CapturedFrameContext("strict-live", 1L, 1L, fp(), true, 1L))
                println("MALFORMED_SUBTRACTION input=$raw restored=${manager.uiState.value.noiseFloor.fingerprint} effective=${manager.uiState.value.isNoiseSubtractionEffective} firstBand=${out.calibrated31Db[0]} restart=$it")
                assertEquals(before, p.all)
                if (out.calibrated31Db[0] != -40f) failures.add(out.calibrated31Db[0])
            } finally { manager.close() }
        }
        assertEquals("Invalid persisted metadata subtracted raw -40 dB after restart: $failures", emptyList<Float>(), failures)
    }
    @Test fun numericStringSampleRateMustNotAuthorizePersistedFloor() = assertCannotSubtract(fp().toJson().put("sampleRate", "48000").toString())
    @Test fun missingEnabledProcessingMustNotAuthorizePersistedFloor() = assertCannotSubtract(fp().toJson().apply { getJSONObject("effects").remove("aecEnabled") }.toString())
}
