package com.rtatuner.app

import android.content.Context
import com.rtatuner.app.core.audio.*
import com.rtatuner.app.core.calibration.CalibrationManager
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
class PersistenceCompletenessClosureTest {
    private fun fp() = CaptureFingerprint(actualSampleRate = 48000, routedMicrophoneId = "17:strict persistence actual microphone", routedDeviceType = "Built-in", selectedAudioSource = 6, actualAudioSource = 6, effects = CaptureEffectsInfo(false, false, false, true))
    private val topKeys = listOf("dspRevision", "sampleRate", "micId", "devType", "selectedSource", "actualSource", "fallback", "effects")
    private val dspTextKeys = listOf("revisionId", "filterType", "centerFrequencyPreset", "qBandwidthDefinition", "cascadeAndNormalization", "edgeAnchoringDesign")
    private val effectKeys = listOf("aec", "agc", "ns", "known", "aecEnabled", "agcEnabled", "nsEnabled")
    private fun parse(j: JSONObject) = CaptureFingerprint.fromJsonString(j.toString())

    @Test fun nullEveryTopLevelRequiredFieldRejected() {
        val accepted = topKeys.filter { parse(fp().toJson().put(it, JSONObject.NULL)) != null }
        assertEquals("Null top-level required fields accepted: $accepted", emptyList<String>(), accepted)
    }
    @Test fun nullEveryDspRequiredFieldRejected() {
        val accepted = (dspTextKeys + "coefficientRevision").filter { key -> parse(fp().toJson().apply { getJSONObject("dspRevision").put(key, JSONObject.NULL) }) != null }
        assertEquals("Null DSP required fields accepted: $accepted", emptyList<String>(), accepted)
    }
    @Test fun nullEveryEffectsRequiredFieldRejected() {
        val accepted = effectKeys.filter { key -> parse(fp().toJson().apply { getJSONObject("effects").put(key, JSONObject.NULL) }) != null }
        assertEquals("Null effects required fields accepted: $accepted", emptyList<String>(), accepted)
    }
    @Test fun invalidMicrophoneAndDeviceValuesRejected() {
        val invalid = listOf(17, true, "", " ", "UNKNOWN", "Pending")
        val accepted = listOf("micId", "devType").flatMap { key -> invalid.map { key to it } }.filter { (key, value) -> parse(fp().toJson().put(key, value)) != null }
        assertEquals("Invalid microphone/device metadata accepted: $accepted", emptyList<Pair<String, Any>>(), accepted)
        assertNull(parse(fp().toJson().put("devType", "NOT_AN_ANDROID_MICROPHONE_TYPE")))
    }
    @Test fun outOfDomainAudioSourcesAndInconsistentFallbackRejected() {
        val accepted = listOf("selectedSource", "actualSource").flatMap { key -> listOf(-1, 11, Int.MAX_VALUE).map { key to it } }.filter { (key, value) -> parse(fp().toJson().put(key, value)) != null }
        assertEquals("Out-of-domain sources accepted: $accepted", emptyList<Pair<String, Int>>(), accepted)
        assertNull(parse(fp().toJson().put("fallback", true)))
        assertNull(parse(fp().toJson().put("actualSource", 1).put("fallback", false)))
    }

    private fun assertRestoredNumerics(raw: String, shouldSubtract: Boolean) {
        val p = RuntimeEnvironment.getApplication().getSharedPreferences("completeness-closure-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val floor = FloatArray(31) { -50f - it }
        assertTrue(p.edit().putString("noise_floor_fingerprint", raw).putString("noise_floor_bands", floor.joinToString(",")).putLong("noise_floor_timestamp", 777L).putBoolean("noise_floor_captured", true).putBoolean("noise_subtraction_active", true).commit())
        val before = p.all.toMap()
        repeat(2) { restart ->
            val manager = CalibrationManager(p, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
            try {
                manager.updateCaptureAuthority(CaptureAuthoritySnapshot("complete-live", 1L, 1L, fp(), true, 0L))
                val result = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, CapturedFrameContext("complete-live", 1L, 1L, fp(), true, 1L))
                val state = manager.uiState.value
                assertArrayEquals(floor, state.noiseFloor.bands31Db, 0f)
                assertEquals(777L, state.noiseFloor.captureTimestampMs)
                assertTrue(state.noiseFloor.isCaptured)
                assertTrue(state.isNoiseSubtractionActive)
                assertEquals(before, p.all)
                assertEquals(shouldSubtract, state.isNoiseSubtractionEffective)
                assertEquals(if (shouldSubtract) -40.457577f else -40f, result.calibrated31Db[0], 0.00001f)
                if (shouldSubtract) {
                    assertEquals(fp(), state.noiseFloor.fingerprint)
                    assertFalse(state.noiseFloor.requiresRecapture)
                    assertTrue(state.noiseFloorCompatibility is NoiseFloorCompatibility.Compatible)
                } else {
                    assertTrue(state.noiseFloorCompatibility is NoiseFloorCompatibility.Incompatible)
                }
                println("COMPLETENESS_RESTORE restart=$restart shouldSubtract=$shouldSubtract effective=${state.isNoiseSubtractionEffective} firstBand=${result.calibrated31Db[0]} raw=$raw")
            } finally { manager.close() }
        }
    }

    @Test fun unsupportedRequiredDspMetadataNeverAuthorizesAfterRestoration() {
        for (key in dspTextKeys) {
            assertRestoredNumerics(fp().toJson().apply { getJSONObject("dspRevision").put(key, "UNSUPPORTED_REQUIRED_METADATA") }.toString(), false)
        }
        assertRestoredNumerics(fp().toJson().apply { getJSONObject("dspRevision").put("coefficientRevision", 2) }.toString(), false)
        assertRestoredNumerics(fp().toJson().apply { getJSONObject("effects").put("known", false) }.toString(), false)
    }
    @Test fun exactIntegralExponentNotationRetainsCompatibilityAndNumericalSubtraction() {
        var raw = fp().toJsonString()
        for ((key, value) in listOf("sampleRate" to "4.8e4", "selectedSource" to "6e0", "actualSource" to "6e0", "coefficientRevision" to "1e0")) {
            raw = raw.replace(Regex("\\\"" + key + "\\\":-?[0-9]+"), "\"$key\":$value")
        }
        val actual = JSONObject(raw)
        assertTrue(actual.opt("sampleRate") is Double)
        assertTrue(actual.opt("selectedSource") is Double)
        assertTrue(actual.opt("actualSource") is Double)
        assertTrue(actual.getJSONObject("dspRevision").opt("coefficientRevision") is Double)
        assertEquals(fp(), CaptureFingerprint.fromJsonString(raw))
        assertRestoredNumerics(raw, true)
    }
}
