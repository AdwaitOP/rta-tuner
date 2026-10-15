package com.rtatuner.app

import com.rtatuner.app.core.audio.CaptureEffectsInfo
import com.rtatuner.app.core.audio.CaptureFingerprint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class JvmExactBigIntegerClosureTest {
    private fun fp() = CaptureFingerprint(actualSampleRate = 48000, routedMicrophoneId = "17:strict persistence actual microphone", routedDeviceType = "Built-in", selectedAudioSource = 6, actualAudioSource = 6, effects = CaptureEffectsInfo(false, false, false, true))
    @Test fun parsedBigIntegerRateAliasRejectedBeforeNarrowing() {
        val invalid = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(48000)).toString()
        assertEquals("18446744073709599616", invalid)
        val raw = fp().toJsonString().replace(Regex("\\\"sampleRate\\\":-?[0-9]+"), "\"sampleRate\":$invalid")
        val actual = JSONObject(raw).opt("sampleRate")
        assertTrue("Actual org.json parser representation=${actual?.javaClass?.name}", actual is BigInteger)
        assertEquals(BigInteger(invalid), actual)
        val restored = CaptureFingerprint.fromJsonString(raw)
        println("JVM_EXACT_BIGINTEGER token=$invalid representation=${actual?.javaClass?.name} original=$actual restored=$restored equalCurrent=${restored == fp()}")
        assertNull("Out-of-range actual JSON BigInteger must not become current fingerprint", restored)
    }
}
