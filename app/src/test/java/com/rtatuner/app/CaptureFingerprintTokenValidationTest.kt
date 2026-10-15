package com.rtatuner.app

import android.content.Context
import com.rtatuner.app.core.audio.CaptureAuthoritySnapshot
import com.rtatuner.app.core.audio.CaptureEffectsInfo
import com.rtatuner.app.core.audio.CaptureFingerprint
import com.rtatuner.app.core.audio.CapturedFrameContext
import com.rtatuner.app.core.audio.validateNumericTokens
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** Runs against Android's JSONObject, whose Double conversion can erase original fractions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureFingerprintTokenValidationTest {
    private fun fingerprint() = CaptureFingerprint(
        actualSampleRate = 48000,
        routedMicrophoneId = "17:token validation microphone",
        routedDeviceType = "Built-in",
        selectedAudioSource = 6,
        actualAudioSource = 6,
        effects = CaptureEffectsInfo(false, false, false, true)
    )

    private val escapedKeys = linkedMapOf(
        "sampleRate" to "sample\\u0052ate",
        "selectedSource" to "selected\\u0053ource",
        "actualSource" to "actual\\u0053ource",
        "coefficientRevision" to "coefficient\\u0052evision"
    )

    private fun numericToken(key: String, token: String, escaped: Boolean = false): String {
        val encodedKey = if (escaped) escapedKeys.getValue(key) else key
        val pattern = Regex("\"$key\":-?[0-9]+")
        return pattern.replace(fingerprint().toJsonString()) { "\"$encodedKey\":$token" }
    }

    private fun assertRejected(key: String, token: String) {
        val raw = numericToken(key, token, escaped = true)
        val json = JSONObject(raw)
        val decoded = if (key == "coefficientRevision") json.getJSONObject("dspRevision").opt(key) else json.opt(key)
        val lexicalValid = validateNumericTokens(raw)
        val restored = CaptureFingerprint.fromJsonString(raw)
        println("ESCAPED_NUMERIC key=$key token=$token decoded=$decoded representation=${decoded?.javaClass?.name} lexicalValid=$lexicalValid restored=$restored")
        assertFalse("Original token for decoded $key bypassed validation: $raw", lexicalValid)
        assertNull("Original token for decoded $key was accepted: $raw", restored)
    }

    @Test fun escapedSampleRateRejectsOriginalPrecisionLoss() = assertRejected("sampleRate", "48000.0000000000000000001")
    @Test fun escapedSelectedSourceRejectsOriginalPrecisionLoss() = assertRejected("selectedSource", "6.0000000000000000001")
    @Test fun escapedActualSourceRejectsOriginalPrecisionLoss() = assertRejected("actualSource", "6.0000000000000000001")
    @Test fun escapedCoefficientRevisionRejectsOriginalPrecisionLoss() = assertRejected("coefficientRevision", "1.0000000000000000001")

    @Test fun androidJSONObjectDecodesEscapedKeyAndRoundsFraction() {
        val fragment = "{\"sample\\u0052ate\":48000.0000000000000000001}"
        val raw = JSONObject(fragment).get("sampleRate")
        assertTrue(raw is Double)
        assertEquals(48000.0, (raw as Number).toDouble(), 0.0)
        assertFalse(validateNumericTokens(fragment))
    }

    @Test fun canonicalPersistedFingerprintRoundTrips() {
        val expected = fingerprint()
        assertTrue(validateNumericTokens(expected.toJsonString()))
        assertEquals(expected, CaptureFingerprint.fromJsonString(expected.toJsonString()))
    }

    @Test fun validUnicodeEscapedRequiredNamesAreAccepted() {
        var raw = fingerprint().toJsonString()
        for ((key, escapedKey) in escapedKeys) raw = raw.replace("\"$key\":", "\"$escapedKey\":")
        raw = raw.replace("\"dspRevision\":", "\"dsp\\u0052evision\":")
            .replace("\"effects\":", "\"eff\\u0065cts\":")
            .replace("\"sessionKnown\":", "\"session\\u004bnown\":")
        assertTrue(validateNumericTokens(raw))
        assertEquals(fingerprint(), CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun exactIntegerExponentFormsAreAccepted() {
        for ((key, token) in listOf("sampleRate" to "4.8000e4", "selectedSource" to "60e-1", "actualSource" to "0.6E+1", "coefficientRevision" to "100e-2")) {
            for (escaped in listOf(false, true)) {
                val raw = numericToken(key, token, escaped)
                assertTrue("$key=$token escaped=$escaped", validateNumericTokens(raw))
                assertEquals(fingerprint(), CaptureFingerprint.fromJsonString(raw))
            }
        }
        val rate44100 = numericToken("sampleRate", "4.41E4", escaped = true)
        assertEquals(44100, CaptureFingerprint.fromJsonString(rate44100)?.actualSampleRate)
    }

    @Test fun fractionalExponentFormsAreRejected() {
        for ((key, token) in listOf("sampleRate" to "480000000000000000001e-16", "selectedSource" to "60000000000000000001e-19", "actualSource" to "60000000000000000001e-19", "coefficientRevision" to "10000000000000000001e-19")) {
            assertRejected(key, token)
        }
    }

    @Test fun escapedNumericBoundsAreValidatedBeforeNarrowing() {
        for ((key, tokens) in listOf(
            "sampleRate" to listOf("48001", "4295015296", "-48000"),
            "selectedSource" to listOf("-1", "11", "4294967302"),
            "actualSource" to listOf("-1", "11", "4294967302"),
            "coefficientRevision" to listOf("0", "-1", "2147483648", "4294967297")
        )) for (token in tokens) assertRejected(key, token)
    }

    @Test fun escapedQuotationMarkInUnknownKeyDoesNotAliasRequiredKey() {
        val raw = fingerprint().toJsonString().dropLast(1) + ",\"sample\\\"Rate\":48000.0000000000000000001}"
        assertEquals(fingerprint(), CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun escapedBackslashInUnknownKeyDoesNotBecomeUnicodeEscape() {
        val raw = fingerprint().toJsonString().dropLast(1) + ",\"sample\\\\u0052ate\":48000.0000000000000000001}"
        assertEquals(fingerprint(), CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun escapedStringValuesDoNotCreateObjectMembers() {
        val expected = fingerprint().copy(routedMicrophoneId = "17:\"sampleRate\":48000.0000000000000000001 \\ sample\\u0052ate")
        assertEquals(expected, CaptureFingerprint.fromJsonString(expected.toJsonString()))
    }

    @Test fun requiredNamesInUnknownNestedObjectsDoNotChangeFingerprint() {
        val raw = fingerprint().toJsonString().dropLast(1) + ",\"extra\":{\"sampleRate\":1.5,\"selectedSource\":-50,\"actualSource\":999,\"coefficientRevision\":0}}"
        assertTrue(validateNumericTokens(raw))
        assertEquals(fingerprint(), CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun nestedObjectsCannotSupplyMissingRootRequiredFields() {
        val raw = fingerprint().toJson().apply {
            remove("sampleRate")
            put("extra", JSONObject().put("sampleRate", 48000))
        }.toString()
        assertNull(CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun coefficientRevisionMustBelongToDspRevisionObject() {
        val raw = fingerprint().toJson().apply {
            getJSONObject("dspRevision").remove("coefficientRevision")
            put("coefficientRevision", 1)
        }.toString()
        assertNull(CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun escapedDspRevisionNameStillValidatesOriginalCoefficientToken() {
        val raw = numericToken("coefficientRevision", "1.0000000000000000001", escaped = true)
            .replace("\"dspRevision\":", "\"dsp\\u0052evision\":")
        assertFalse(validateNumericTokens(raw))
        assertNull(CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun duplicateRequiredNumericKeysAreRejected() {
        for ((key, token) in listOf("sampleRate" to "48000", "selectedSource" to "6", "actualSource" to "6", "coefficientRevision" to "1")) {
            val raw = fingerprint().toJsonString().replace("\"$key\":$token", "\"$key\":$token,\"$key\":$token")
            assertFalse("Duplicate $key accepted", validateNumericTokens(raw))
            assertNull(CaptureFingerprint.fromJsonString(raw))
        }
    }

    @Test fun escapedAndUnescapedRequiredNumericAliasesAreRejected() {
        for ((key, escapedKey) in escapedKeys) {
            val value = when (key) { "sampleRate" -> "48000"; "coefficientRevision" -> "1"; else -> "6" }
            val raw = fingerprint().toJsonString().replace("\"$key\":$value", "\"$key\":$value,\"$escapedKey\":$value")
            assertFalse("Decoded duplicate $key accepted", validateNumericTokens(raw))
            assertNull(CaptureFingerprint.fromJsonString(raw))
        }
    }

    @Test fun duplicateRequiredStringBooleanAndObjectAliasesAreRejected() {
        val aliases = listOf("micId" to "mic\\u0049d", "devType" to "dev\\u0054ype", "fallback" to "fall\\u0062ack", "dspRevision" to "dsp\\u0052evision", "effects" to "eff\\u0065cts")
        for ((key, alias) in aliases) {
            val value = JSONObject.quote(key) + ":" + when (val v = fingerprint().toJson().get(key)) {
                is String -> JSONObject.quote(v)
                else -> v.toString()
            }
            val raw = fingerprint().toJsonString().dropLast(1) + "," + value.replaceFirst("\"$key\":", "\"$alias\":") + "}"
            assertFalse("Decoded duplicate $key accepted", validateNumericTokens(raw))
            assertNull(CaptureFingerprint.fromJsonString(raw))
        }
    }

    @Test fun duplicateAliasesInsideEffectsAndDspObjectsAreRejected() {
        val effectsDuplicate = fingerprint().toJsonString().replace("\"sessionKnown\":true", "\"sessionKnown\":true,\"session\\u004bnown\":true")
        val dspDuplicate = fingerprint().toJsonString().replace("\"filterType\":", "\"filter\\u0054ype\":\"duplicate\",\"filterType\":")
        for (raw in listOf(effectsDuplicate, dspDuplicate)) {
            assertFalse(validateNumericTokens(raw))
            assertNull(CaptureFingerprint.fromJsonString(raw))
        }
    }

    @Test fun arraysAndNestedMetadataPreserveObjectScopeAndStringEscapes() {
        val raw = fingerprint().toJsonString().dropLast(1) + ",\"extra\":[{\"sampleRate\":1.5},[true,null,\"quoted\\\"value\\\\\"]]}"
        assertTrue(validateNumericTokens(raw))
        assertEquals(fingerprint(), CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun malformedOriginalJsonFailsClosed() {
        val valid = fingerprint().toJsonString()
        val malformed = listOf(
            valid + " trailing",
            valid.dropLast(1) + ",}",
            valid.replace("\"sampleRate\":48000", "\"sampleRate\":+48000"),
            valid.replace("\"sampleRate\":48000", "\"sampleRate\":048000"),
            valid.replace("\"sampleRate\":48000", "\"sampleRate\":48000."),
            valid.replace("\"sampleRate\":48000", "\"sampleRate\":4.8e"),
            valid.replace("\"micId\":", "\"mic\\qId\":"),
            valid.replace("\"micId\":", "\"mic\\u00x1Id\":"),
            valid.replace("token validation", "token\nvalidation")
        )
        for (raw in malformed) {
            assertFalse("Malformed original JSON accepted: $raw", validateNumericTokens(raw))
            assertNull(CaptureFingerprint.fromJsonString(raw))
        }
    }

    @Test fun enormousExponentTokensFailClosedWithoutIntegerExpansion() {
        for (key in escapedKeys.keys) {
            for (token in listOf("1e2147483647", "1e-2147483647", "1e2147483648", "1e-2147483648")) {
                val raw = numericToken(key, token, escaped = true)
                assertFalse("$key=$token accepted", validateNumericTokens(raw))
                assertNull(CaptureFingerprint.fromJsonString(raw))
            }
        }
    }

    @Test fun excessiveNestingFailsClosedBeforeStackExhaustion() {
        val prefix = fingerprint().toJsonString().dropLast(1) + ",\"extra\":"
        val ordinary = prefix + "[".repeat(8) + "null" + "]".repeat(8) + "}"
        assertEquals(fingerprint(), CaptureFingerprint.fromJsonString(ordinary))
        val excessive = prefix + "[".repeat(1000) + "null" + "]".repeat(1000) + "}"
        assertFalse(validateNumericTokens(excessive))
        assertNull(CaptureFingerprint.fromJsonString(excessive))
    }

    @Test fun sameDecodedNameInDifferentObjectsIsNotADuplicate() {
        val raw = fingerprint().toJsonString().dropLast(1) +
            ",\"first\":{\"sample\\u0052ate\":1.5},\"second\":{\"sampleRate\":2.5}}"
        assertTrue(validateNumericTokens(raw))
        assertEquals(fingerprint(), CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun unicodeEscapedValuesPreserveDecodedIdentity() {
        val raw = fingerprint().toJsonString()
            .replace("17:token validation microphone", "17:mic\\uD83D\\uDE80")
            .replace("Built-in", "Buil\\u0074-in")
        assertTrue(validateNumericTokens(raw))
        assertEquals(fingerprint().copy(routedMicrophoneId = "17:mic\uD83D\uDE80"), CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun unicodeEscapesRequireAsciiHexDigits() {
        val raw = fingerprint().toJsonString().replace("\"micId\":", "\"mic\\u００４９d\":")
        assertFalse(validateNumericTokens(raw))
        assertNull(CaptureFingerprint.fromJsonString(raw))
    }

    @Test fun malformedEscapedMetadataPreservesHistoricalFloorAndRequestedSettings() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("escaped-fingerprint-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val bands = FloatArray(31) { -50f }
        prefs.edit().putString("noise_floor_bands", bands.joinToString(","))
            .putLong("noise_floor_timestamp", 777L).putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .putString("noise_floor_fingerprint", numericToken("sampleRate", "48000.0000000000000000001", escaped = true)).commit()
        val before = prefs.all.toMap()
        repeat(2) {
            val manager = CalibrationManager(prefs, FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)))
            try {
                manager.updateCaptureAuthority(CaptureAuthoritySnapshot("token-live", 1L, 1L, fingerprint(), true, 0L))
                val state = manager.uiState.value
                assertNull(state.noiseFloor.fingerprint)
                assertArrayEquals(bands, state.noiseFloor.bands31Db, 0f)
                assertEquals(777L, state.noiseFloor.captureTimestampMs)
                assertTrue(state.noiseFloor.isCaptured)
                val out = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f,
                    CapturedFrameContext("token-live", 1L, 1L, fingerprint(), true, 1L))
                assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
                assertEquals(-40f, out.calibrated31Db[0], 0f)
                assertEquals(before, prefs.all)
            } finally { manager.close() }
        }
    }
}
