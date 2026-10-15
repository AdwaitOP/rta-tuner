package com.rtatuner.app

import android.content.Context
import android.content.SharedPreferences
import com.rtatuner.app.core.audio.CaptureEffectsInfo
import com.rtatuner.app.core.audio.CaptureFingerprint
import com.rtatuner.app.core.audio.NoiseFloorCompatibility
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
class StrictPersistenceReauditTest {
    private fun fp() = CaptureFingerprint(actualSampleRate = 48000, routedMicrophoneId = "17:strict persistence actual microphone", routedDeviceType = "Built-in", selectedAudioSource = 6, actualAudioSource = 6, effects = CaptureEffectsInfo(false, false, false, true))
    private fun json() = fp().toJson()
    private fun parse(j: JSONObject) = CaptureFingerprint.fromJsonString(j.toString())
    private fun reject(j: JSONObject) = assertNull("Invalid persisted input accepted: $j", parse(j))
    private fun dsp(key: String, value: Any) = json().apply { getJSONObject("dspRevision").put(key, value) }
    private fun effect(key: String, value: Any) = json().apply { getJSONObject("effects").put(key, value) }
    private fun snapshot(value: CaptureFingerprint) = NoiseFloorSnapshot(FloatArray(31) { -50f }, 777, true, value, false)

    @Test fun validCompleteFingerprintRoundTripsAndMatches() {
        val parsed = requireNotNull(parse(json()))
        assertEquals(fp(), parsed)
        assertTrue(evaluateNoiseFloorCompatibility(snapshot(parsed), fp()) is NoiseFloorCompatibility.Compatible)
    }
    @Test fun numericStringSampleRateRejected() = reject(json().put("sampleRate", "48000"))
    @Test fun fractionalSampleRateRejected() = reject(json().put("sampleRate", 48000.9))
    @Test fun numericStringSelectedSourceRejected() = reject(json().put("selectedSource", "6"))
    @Test fun fractionalSelectedSourceRejected() = reject(json().put("selectedSource", 6.9))
    @Test fun numericStringActualSourceRejected() = reject(json().put("actualSource", "6"))
    @Test fun fractionalActualSourceRejected() = reject(json().put("actualSource", 6.9))
    @Test fun stringFallbackBooleanRejected() = reject(json().put("fallback", "false"))
    @Test fun numericMicrophoneIdRejected() = reject(json().put("micId", 17))
    @Test fun booleanDeviceTypeRejected() = reject(json().put("devType", true))
    @Test fun numericStringDspCoefficientRejected() = reject(dsp("coefficientRevision", "1"))
    @Test fun fractionalDspCoefficientRejected() = reject(dsp("coefficientRevision", 1.9))
    @Test fun incorrectDspTextFieldTypesRejected() {
        val accepted = listOf("revisionId", "filterType", "centerFrequencyPreset", "qBandwidthDefinition", "cascadeAndNormalization", "edgeAnchoringDesign").filter { parse(dsp(it, true)) != null }
        assertEquals("Non-string DSP text fields accepted: $accepted", emptyList<String>(), accepted)
    }
    @Test fun stringAvailabilityBooleansRejected() {
        val accepted = listOf("aec", "agc", "ns", "known").filter { parse(effect(it, if (it == "known") "true" else "false")) != null }
        assertEquals("String booleans accepted: $accepted", emptyList<String>(), accepted)
    }
    @Test fun stringEnabledBooleansRejected() {
        val accepted = listOf("aecEnabled", "agcEnabled", "nsEnabled").filter { parse(effect(it, "false")) != null }
        assertEquals("String enabled booleans accepted: $accepted", emptyList<String>(), accepted)
    }
    @Test fun missingEnabledProcessingStateRejected() {
        val accepted = listOf("aecEnabled", "agcEnabled", "nsEnabled").filter { key -> parse(json().apply { getJSONObject("effects").remove(key) }) != null }
        assertEquals("Absent enabled state defaulted: $accepted", emptyList<String>(), accepted)
    }
    @Test fun nullEnabledProcessingStateRejected() {
        val accepted = listOf("aecEnabled", "agcEnabled", "nsEnabled").filter { parse(effect(it, JSONObject.NULL)) != null }
        assertEquals("Null enabled state defaulted: $accepted", emptyList<String>(), accepted)
    }
    @Test fun unrecognizedEnabledProcessingStateRejected() {
        val accepted = listOf("aecEnabled", "agcEnabled", "nsEnabled").flatMap { key -> listOf("unknown", 0, 1, JSONObject()).map { key to it } }.filter { parse(effect(it.first, it.second)) != null }
        assertEquals("Unknown enabled state defaulted: $accepted", emptyList<Pair<String, Any>>(), accepted)
    }
    @Test fun unknownEffectsDoNotMatch() {
        val restored = requireNotNull(parse(effect("known", false)))
        assertTrue(evaluateNoiseFloorCompatibility(snapshot(restored), fp()) is NoiseFloorCompatibility.Incompatible)
    }
    @Test fun unknownEffectsOnBothSidesNeverMatch() {
        val restored = requireNotNull(parse(effect("known", false)))
        assertTrue(evaluateNoiseFloorCompatibility(snapshot(restored), restored) is NoiseFloorCompatibility.Incompatible)
    }
    @Test fun enabledProcessingChangeDoesNotMatch() {
        val restored = requireNotNull(parse(effect("aecEnabled", true)))
        assertTrue(evaluateNoiseFloorCompatibility(snapshot(restored), fp()) is NoiseFloorCompatibility.Incompatible)
    }
    @Test fun missingRequiredMetadataFailsClosed() {
        val accepted = listOf("dspRevision", "sampleRate", "micId", "devType", "selectedSource", "actualSource", "fallback", "effects").filter { parse(json().apply { remove(it) }) != null }
        assertEquals("Absent required metadata accepted: $accepted", emptyList<String>(), accepted)
    }
    @Test fun partialDspRevisionFailsClosed() {
        val accepted = listOf("revisionId", "filterType", "coefficientRevision", "centerFrequencyPreset", "qBandwidthDefinition", "cascadeAndNormalization", "edgeAnchoringDesign").filter { key -> parse(json().apply { getJSONObject("dspRevision").remove(key) }) != null }
        assertEquals("Partial DSP metadata accepted: $accepted", emptyList<String>(), accepted)
    }
    @Test fun unknownDspRevisionDoesNotMatch() {
        val restored = requireNotNull(parse(dsp("revisionId", "future")))
        assertTrue(evaluateNoiseFloorCompatibility(snapshot(restored), fp()) is NoiseFloorCompatibility.Incompatible)
    }
    @Test fun invalidRatesAndInconsistentFallbackRejected() {
        for (rate in listOf(0, -48000, 16000)) reject(json().put("sampleRate", rate))
        reject(json().put("fallback", true))
    }
    @Test fun malformedAndTruncatedJsonRejected() {
        val valid = json().toString()
        for (bad in listOf("{", "[]", "not-json", "", " ", valid.dropLast(1), valid.take(valid.length / 2))) assertNull(bad, CaptureFingerprint.fromJsonString(bad))
    }
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("strict-reaudit-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun seed(p: SharedPreferences, fingerprint: String?) {
        val e = p.edit().putString("noise_floor_bands", FloatArray(31) { -50f - it }.joinToString(","))
            .putLong("noise_floor_timestamp", 777L).putBoolean("noise_floor_captured", true).putBoolean("noise_subtraction_active", true)
            .putString("mic_curve_name", "retained mic").putString("mic_curve_points", "[{\"f\":20,\"o\":3},{\"f\":20000,\"o\":3}]").putBoolean("mic_curve_active", true).putString("mic_curve_convention", "ADDITIVE_CORRECTION")
            .putBoolean("spl_mode", true).putFloat("spl_offset_db", 103f).putBoolean("target_curve_active", true).putString("target_curve_id", "FLAT")
        if (fingerprint == null) e.remove("noise_floor_fingerprint") else e.putString("noise_floor_fingerprint", fingerprint)
        assertTrue(e.commit())
    }
    @Test fun legacyMalformedAndTruncatedFloorsPreservePreferencesAndHistoryAcrossRestart() {
        for (raw in listOf(null, "{", json().toString().dropLast(1), json().apply { remove("dspRevision") }.toString())) {
            val p = prefs(); seed(p, raw); val before = p.all.toMap()
            repeat(2) {
                val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
                try {
                    val state = manager.uiState.value
                    assertTrue(state.noiseFloor.isCaptured)
                    assertTrue("Parse failure must retain recapture status: $raw", state.noiseFloor.requiresRecapture)
                    assertEquals(777L, state.noiseFloor.captureTimestampMs)
                    assertArrayEquals(FloatArray(31) { -50f - it }, state.noiseFloor.bands31Db, 0f)
                    assertTrue(state.isMicCurveActive)
                    assertEquals("retained mic", state.micCurve!!.name)
                    assertEquals(3f, state.micCurve!!.points.first().dbOffset, 0f)
                    assertTrue(state.splCalibration.isSplMode)
                    assertEquals(103f, state.splCalibration.splOffsetDb, 0f)
                    assertTrue(state.isTargetCurveActive)
                    assertEquals("FLAT", state.targetCurve!!.id)
                    assertTrue(state.isNoiseSubtractionActive)
                    assertFalse(state.isNoiseSubtractionEffective)
                    assertEquals(before, p.all)
                } finally { manager.close() }
            }
        }
    }
    @Test fun probeCoercionsCanEqualCurrentAndAuthorizeCompatibility() {
        val malformed = listOf(
            json().put("sampleRate", "48000"),
            json().put("sampleRate", 48000.9),
            json().put("selectedSource", "6"),
            json().put("actualSource", 6.9),
            json().put("fallback", "false"),
            dsp("coefficientRevision", "1"),
            effect("known", "true"),
            json().apply { getJSONObject("effects").remove("aecEnabled") },
            effect("agcEnabled", "unknown")
        )
        val accepted = malformed.mapNotNull { value -> parse(value)?.let { restored ->
            println("STRICT_PROBE accepted=$value equalCurrent=${restored == fp()} compatible=${evaluateNoiseFloorCompatibility(snapshot(restored), fp()) is NoiseFloorCompatibility.Compatible}")
            restored
        } }
        assertEquals("Malformed records must never become valid current fingerprints", 0, accepted.size)
    }
}



