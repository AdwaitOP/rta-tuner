package com.rtatuner.app

import android.content.Context
import com.rtatuner.app.core.audio.*
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.calibration.NoiseFloorSnapshot
import com.rtatuner.app.core.calibration.evaluateNoiseFloorCompatibility
import com.rtatuner.app.core.entitlement.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NumericRepresentationClosureTest {
    private fun fp() = CaptureFingerprint(actualSampleRate = 48000, routedMicrophoneId = "17:strict persistence actual microphone", routedDeviceType = "Built-in", selectedAudioSource = 6, actualAudioSource = 6, effects = CaptureEffectsInfo(false, false, false, true))
    private fun token(key: String, value: String): String {
        val text = fp().toJsonString()
        val regex = Regex("\\\"" + key + "\\\":-?[0-9]+")
        assertTrue("Expected existing numeric field $key", regex.containsMatchIn(text))
        return text.replace(regex, "\"$key\":$value")
    }
    private fun rejectToken(key: String, value: String) {
        val raw = token(key, value)
        val j = JSONObject(raw)
        val parsedNumber = if (key == "coefficientRevision") j.getJSONObject("dspRevision").opt(key) else j.opt(key)
        val result = CaptureFingerprint.fromJsonString(raw)
        println("ANDROID_NUMERIC key=$key token=$value representation=${parsedNumber?.javaClass?.name} parsed=$parsedNumber restored=$result equalCurrent=${result == fp()}")
        assertNull("Invalid $key=$value (${parsedNumber?.javaClass?.name}) became $result", result)
    }
    private fun cannotSubtract(key: String, value: String) {
        val raw = token(key, value)
        val p = RuntimeEnvironment.getApplication().getSharedPreferences("numeric-closure-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val bands = FloatArray(31) { -50f - it }
        assertTrue(p.edit().putString("noise_floor_bands", bands.joinToString(",")).putLong("noise_floor_timestamp", 777L).putBoolean("noise_floor_captured", true).putBoolean("noise_subtraction_active", true).putString("noise_floor_fingerprint", raw).commit())
        val before = p.all.toMap()
        val altered = mutableListOf<Float>()
        repeat(2) { restart ->
            val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
            try {
                manager.updateCaptureAuthority(CaptureAuthoritySnapshot("strict-live", 1L, 1L, fp(), true, 0L))
                val frame = CapturedFrameContext("strict-live", 1L, 1L, fp(), true, 1L)
                val out = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frame)
                val state = manager.uiState.value
                assertArrayEquals(bands, state.noiseFloor.bands31Db, 0f)
                assertEquals(777L, state.noiseFloor.captureTimestampMs)
                assertTrue(state.noiseFloor.isCaptured)
                assertTrue(state.isNoiseSubtractionActive)
                assertEquals(before, p.all)
                println("ANDROID_PERSISTED key=$key token=$value restart=$restart equalCurrent=${state.noiseFloor.fingerprint == fp()} requiresRecapture=${state.noiseFloor.requiresRecapture} effective=${state.isNoiseSubtractionEffective} firstBand=${out.calibrated31Db[0]}")
                if (out.calibrated31Db[0] != -40f) altered.add(out.calibrated31Db[0])
            } finally { manager.close() }
        }
        assertEquals("Invalid $key=$value authorized subtraction with explicit live authority", emptyList<Float>(), altered)
    }

    @Test fun androidOrgJsonActuallyParsesLargeIntegralAsLong() {
        val raw = JSONObject(token("sampleRate", "4295015296")).opt("sampleRate")
        assertTrue("Actual Android parser representation=${raw?.javaClass?.name}", raw is Long)
        assertEquals(4295015296L, raw)
    }
    @Test fun legitimateIntegralExponentNotationRemainsValid() {
        for ((key, value) in listOf("sampleRate" to "4.8e4", "selectedSource" to "6e0", "actualSource" to "6e0", "coefficientRevision" to "1e0")) {
            val restored = CaptureFingerprint.fromJsonString(token(key, value))
            assertEquals("Exact integral exponent $key=$value", fp(), restored)
        }
    }
    @Test fun fractionalExponentRateRejected() = rejectToken("sampleRate", "4.80009e4")
    @Test fun outOfRangeExponentRateRejected() = rejectToken("sampleRate", "4.295015296e9")
    @Test fun longRateOverflowAliasRejected() = rejectToken("sampleRate", "4295015296")
    @Test fun negativeLongRateOverflowAliasRejected() = rejectToken("sampleRate", "-4294919296")
    @Test fun longSelectedSourceOverflowAliasRejected() = rejectToken("selectedSource", "4294967302")
    @Test fun longActualSourceOverflowAliasRejected() = rejectToken("actualSource", "4294967302")
    @Test fun negativeLongSelectedSourceOverflowAliasRejected() = rejectToken("selectedSource", "-4294967290")
    @Test fun negativeLongActualSourceOverflowAliasRejected() = rejectToken("actualSource", "-4294967290")
    @Test fun longCoefficientOverflowAliasRejected() = rejectToken("coefficientRevision", "4294967297")
    @Test fun negativeLongCoefficientOverflowAliasRejected() = rejectToken("coefficientRevision", "-4294967295")
    @Test fun maxLongRateRejected() = rejectToken("sampleRate", "9223372036854775807")
    @Test fun beyondLongHugeRateRejected() = rejectToken("sampleRate", "18446744073709600000")
    @Test fun hugeCoefficientExponentRejected() = rejectToken("coefficientRevision", "1e100")
    @Test fun longRateCannotAuthorizePersistedFloor() = cannotSubtract("sampleRate", "4295015296")
    @Test fun longSelectedCannotAuthorizePersistedFloor() = cannotSubtract("selectedSource", "4294967302")
    @Test fun longActualCannotAuthorizePersistedFloor() = cannotSubtract("actualSource", "4294967302")
    @Test fun longCoefficientCannotAuthorizePersistedFloor() = cannotSubtract("coefficientRevision", "4294967297")
    @Test fun negativeLongRateCannotAuthorizePersistedFloor() = cannotSubtract("sampleRate", "-4294919296")
}
