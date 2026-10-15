package com.rtatuner.app

import com.rtatuner.app.core.audio.CaptureEffectsInfo
import com.rtatuner.app.core.audio.CaptureFingerprint
import com.rtatuner.app.core.dsp.DspResponseRevision
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** Actual org.json:json:20240303 parsing on the repository's plain JVM test runtime. */
class JvmOrgJsonNumericRepresentationClosureTest {
    private fun fp() = CaptureFingerprint(actualSampleRate = 48000, routedMicrophoneId = "17:strict persistence actual microphone", routedDeviceType = "Built-in", selectedAudioSource = 6, actualAudioSource = 6, effects = CaptureEffectsInfo(false, false, false, true))
    private fun token(key: String, value: String) = fp().toJsonString().replace(Regex("\\\"" + key + "\\\":-?[0-9]+"), "\"$key\":$value")
    private fun reject(key: String, value: String) {
        val raw = token(key, value)
        val parsed = JSONObject(raw)
        val number = if (key == "coefficientRevision") parsed.getJSONObject("dspRevision").opt(key) else parsed.opt(key)
        val restored = CaptureFingerprint.fromJsonString(raw)
        println("JVM_NUMERIC origin=${JSONObject::class.java.protectionDomain.codeSource?.location} key=$key token=$value representation=${number?.javaClass?.name} restored=$restored equalCurrent=${restored == fp()}")
        assertNull("Invalid $key=$value accepted through actual org.json ${number?.javaClass?.name}", restored)
    }
    @Test fun actualOrgJsonRepresentsFractionalTokenAsBigDecimal() {
        val raw = JSONObject(token("sampleRate", "48000.9")).opt("sampleRate")
        assertTrue("Runtime parser=${JSONObject::class.java.protectionDomain.codeSource?.location} representation=${raw?.javaClass?.name}", raw is BigDecimal)
    }
    @Test fun bigDecimalRateFractionRejectedBeforeNarrowing() = reject("sampleRate", "48000.9")
    @Test fun bigDecimalSelectedFractionRejectedBeforeNarrowing() = reject("selectedSource", "6.9")
    @Test fun bigDecimalActualFractionRejectedBeforeNarrowing() = reject("actualSource", "6.9")
    @Test fun bigDecimalCoefficientFractionRejectedBeforeNarrowing() = reject("coefficientRevision", "1.9")
    @Test fun bigDecimalRateOverflowRejectedBeforeNarrowing() = reject("sampleRate", "4295015296.0")
    @Test fun bigDecimalCoefficientOverflowRejectedBeforeNarrowing() = reject("coefficientRevision", "4294967297.0")
    @Test fun bigIntegerRateOverflowRejectedBeforeNarrowing() = reject("sampleRate", "18446744073709600000")
    @Test fun directDspBigDecimalFractionRejectedBeforeNarrowing() {
        val actualParsed = JSONObject("{\"coefficientRevision\":1.9}").opt("coefficientRevision")
        val j = DspResponseRevision.CURRENT.toJson().put("coefficientRevision", actualParsed)
        assertNull(DspResponseRevision.fromJson(j))
    }
}
