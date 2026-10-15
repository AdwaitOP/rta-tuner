package com.rtatuner.app.core.audio

import com.rtatuner.app.core.dsp.DspResponseRevision
import org.json.JSONObject

/**
 * Capture processing and hardware acoustic effects availability.
 */
data class CaptureEffectsInfo(
    val aecAvailable: Boolean = false,
    val agcAvailable: Boolean = false,
    val nsAvailable: Boolean = false,
    val isEffectsKnown: Boolean = true,
    val isSessionEffectsKnown: Boolean = isEffectsKnown,
    val aecSessionEnabled: Boolean = false,
    val agcSessionEnabled: Boolean = false,
    val nsSessionEnabled: Boolean = false,
    val unobservableProcessingNotes: String = "OEM/HAL acoustic processing cannot be verified"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("aec", aecAvailable)
        put("agc", agcAvailable)
        put("ns", nsAvailable)
        put("known", isEffectsKnown)
        put("sessionKnown", isSessionEffectsKnown)
        put("aecEnabled", aecSessionEnabled)
        put("agcEnabled", agcSessionEnabled)
        put("nsEnabled", nsSessionEnabled)
    }

    companion object {
        fun fromJson(json: JSONObject?): CaptureEffectsInfo? {
            if (json == null) return null
            val requiredKeys = listOf("aec", "agc", "ns", "known", "sessionKnown", "aecEnabled", "agcEnabled", "nsEnabled")
            for (key in requiredKeys) {
                if (!json.has(key) || json.isNull(key)) return null
                val raw = json.opt(key)
                if (raw !is Boolean) return null
            }
            val aec = json.getBoolean("aec")
            val agc = json.getBoolean("agc")
            val ns = json.getBoolean("ns")
            val known = json.getBoolean("known")
            val sessionKnown = json.getBoolean("sessionKnown")
            val aecEn = json.getBoolean("aecEnabled")
            val agcEn = json.getBoolean("agcEnabled")
            val nsEn = json.getBoolean("nsEnabled")

            return CaptureEffectsInfo(
                aecAvailable = aec,
                agcAvailable = agc,
                nsAvailable = ns,
                isEffectsKnown = known,
                isSessionEffectsKnown = sessionKnown,
                aecSessionEnabled = aecEn,
                agcSessionEnabled = agcEn,
                nsSessionEnabled = nsEn
            )
        }
    }
}

/**
 * Strict integer decoding helper that validates mathematically exact integrality
 * and original-domain bounds before narrowing to Int, rejecting Long overflow,
 * BigInteger wrapping, and fractional BigDecimal/Double/Float truncation.
 */
internal fun parseStrictInt(value: Any?, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int? {
    if (value == null) return null
    return when (value) {
        is Int -> if (value in min..max) value else null
        is Long -> if (value >= min.toLong() && value <= max.toLong()) value.toInt() else null
        is Double -> {
            if (!value.isFinite()) return null
            if (value < min.toDouble() || value > max.toDouble()) return null
            val l = value.toLong()
            if (l.toDouble() == value && l in min.toLong()..max.toLong()) l.toInt() else null
        }
        is Float -> {
            if (!value.isFinite()) return null
            if (value < min.toFloat() || value > max.toFloat()) return null
            val l = value.toLong()
            if (l.toFloat() == value && l in min.toLong()..max.toLong()) l.toInt() else null
        }
        is java.math.BigInteger -> {
            val minBig = java.math.BigInteger.valueOf(min.toLong())
            val maxBig = java.math.BigInteger.valueOf(max.toLong())
            if (value in minBig..maxBig) value.toInt() else null
        }
        is java.math.BigDecimal -> {
            try {
                val bigInt = value.toBigIntegerExact()
                val minBig = java.math.BigInteger.valueOf(min.toLong())
                val maxBig = java.math.BigInteger.valueOf(max.toLong())
                if (bigInt in minBig..maxBig) bigInt.toInt() else null
            } catch (_: ArithmeticException) {
                null
            }
        }
        else -> null
    }
}

/**
 * Lexical validator that inspects raw numeric tokens in [jsonStr] before [JSONObject]
 * parsing can widen or round non-integral decimals (e.g. 48000.0000000000000000001) into Doubles.
 *
 * Ensures mathematically exact integrality and original-domain bounds for all numeric fields:
 * - sampleRate: exactly 44100 or 48000
 * - selectedSource: 0..10
 * - actualSource: 0..10
 * - coefficientRevision: 1..Int.MAX_VALUE
 */
internal fun validateNumericTokens(jsonStr: String): Boolean {
    val numericKeys = setOf("sampleRate", "selectedSource", "actualSource", "coefficientRevision")
    var i = 0
    val len = jsonStr.length
    try {
        while (i < len) {
            val ch = jsonStr[i]
            if (ch == '"') {
                val start = i + 1
                i++
                while (i < len && jsonStr[i] != '"') {
                    if (jsonStr[i] == '\\') i++
                    i++
                }
                if (i >= len) return false
                val key = jsonStr.substring(start, i)
                i++ // skip closing quote

                while (i < len && jsonStr[i].isWhitespace()) i++
                if (i < len && jsonStr[i] == ':') {
                    i++ // skip ':'
                    while (i < len && jsonStr[i].isWhitespace()) i++
                    if (key in numericKeys && i < len) {
                        val firstValChar = jsonStr[i]
                        if (firstValChar == '-' || firstValChar == '+' || (firstValChar in '0'..'9')) {
                            val valStart = i
                            while (i < len && jsonStr[i] != ',' && jsonStr[i] != '}' && jsonStr[i] != ']' && !jsonStr[i].isWhitespace()) {
                                i++
                            }
                            val token = jsonStr.substring(valStart, i)
                            val bd = try {
                                java.math.BigDecimal(token)
                            } catch (_: Exception) {
                                return false
                            }
                            if (bd.stripTrailingZeros().scale() > 0) {
                                return false
                            }
                            val bigInt = try {
                                bd.toBigIntegerExact()
                            } catch (_: Exception) {
                                return false
                            }
                            when (key) {
                                "sampleRate" -> {
                                    if (bigInt != java.math.BigInteger.valueOf(44100) &&
                                        bigInt != java.math.BigInteger.valueOf(48000)) {
                                        return false
                                    }
                                }
                                "selectedSource", "actualSource" -> {
                                    if (bigInt < java.math.BigInteger.ZERO || bigInt > java.math.BigInteger.valueOf(10)) {
                                        return false
                                    }
                                }
                                "coefficientRevision" -> {
                                    if (bigInt < java.math.BigInteger.ONE || bigInt > java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
                                        return false
                                    }
                                }
                            }
                            continue
                        }
                    }
                }
            } else {
                i++
            }
        }
        return true
    } catch (_: Exception) {
        return false
    }
}

/**
 * Small, immutable capture fingerprint describing the authoritative measurement context.
 *
 * Requirements:
 * - Versioned DSP response revision
 * - Actual PCM sample rate (44100 or 48000)
 * - Actual routed microphone identity (observed, not requested)
 * - Relevant route/device type (from KNOWN_DEVICE_TYPES)
 * - Selected versus actual audio-source fallback
 * - Available capture-processing/effects information
 *
 * Unknown or pending values are explicitly represented as UNKNOWN.
 */
data class CaptureFingerprint(
    val dspRevision: DspResponseRevision = DspResponseRevision.CURRENT,
    val actualSampleRate: Int,
    val routedMicrophoneId: String,
    val routedDeviceType: String,
    val selectedAudioSource: Int,
    val actualAudioSource: Int,
    val isSourceFallback: Boolean = (selectedAudioSource != actualAudioSource),
    val effects: CaptureEffectsInfo = CaptureEffectsInfo()
) {
    val isMicrophoneKnown: Boolean
        get() = routedMicrophoneId.isNotBlank() &&
                routedMicrophoneId != UNKNOWN_MICROPHONE_ID &&
                routedMicrophoneId != PENDING_MICROPHONE_ID &&
                routedDeviceType.isNotBlank() &&
                routedDeviceType != UNKNOWN_DEVICE_TYPE &&
                routedDeviceType != PENDING_DEVICE_TYPE &&
                routedDeviceType in KNOWN_DEVICE_TYPES

    fun toJsonString(): String = toJson().toString()

    fun toJson(): JSONObject = JSONObject().apply {
        put("dspRevision", dspRevision.toJson())
        put("sampleRate", actualSampleRate)
        put("micId", routedMicrophoneId)
        put("devType", routedDeviceType)
        put("selectedSource", selectedAudioSource)
        put("actualSource", actualAudioSource)
        put("fallback", isSourceFallback)
        put("effects", effects.toJson())
    }

    companion object {
        const val UNKNOWN_MICROPHONE_ID = "UNKNOWN"
        const val UNKNOWN_DEVICE_TYPE = "UNKNOWN"
        const val PENDING_MICROPHONE_ID = "Pending"
        const val PENDING_DEVICE_TYPE = "Pending"

        val KNOWN_DEVICE_TYPES = setOf(
            "Built-in", "Built-in Mic", "Built-in Microphone", "TYPE_BUILTIN_MIC",
            "USB Audio Class (UAC)", "USB Headset / Interface", "USB Audio Microphone", "USB", "USB Audio", "USB_DEVICE", "TYPE_USB_DEVICE", "TYPE_USB_HEADSET",
            "Wired Headset (3.5mm)", "Wired Input (3.5mm)", "Line In (Analog)", "Line In (Digital)", "Wired Headset / Mic (3.5mm)", "TYPE_WIRED_HEADSET", "TYPE_WIRED_HEADPHONES", "TYPE_LINE_ANALOG", "TYPE_LINE_DIGITAL",
            "Bluetooth Voice Headset (SCO)", "Bluetooth SCO (Voice)", "Bluetooth Audio", "Bluetooth A2DP Input", "BLE Headset Input", "Bluetooth", "TYPE_BLUETOOTH_SCO", "TYPE_BLUETOOTH_A2DP", "TYPE_BLE_HEADSET",
            "Telephony / Cellular RX", "Remote Submix", "Hearing Aid Input", "FM Tuner", "TV Tuner", "TYPE_TELEPHONY", "TYPE_REMOTE_SUBMIX", "TYPE_HEARING_AID"
        )

        fun fromJsonString(jsonStr: String?): CaptureFingerprint? {
            if (jsonStr.isNullOrBlank()) return null
            if (!validateNumericTokens(jsonStr)) return null
            return try {
                val json = JSONObject(jsonStr)

                // Required: dspRevision object
                if (!json.has("dspRevision") || json.isNull("dspRevision")) return null
                val dspJson = json.optJSONObject("dspRevision") ?: return null
                val dspRev = DspResponseRevision.fromJson(dspJson) ?: return null

                // Required: sampleRate integer (strict whole Number, no numeric string, no fraction, no overflow)
                if (!json.has("sampleRate") || json.isNull("sampleRate")) return null
                val rawRate = json.opt("sampleRate")
                val sampleRate = parseStrictInt(rawRate, 44100, 48000) ?: return null
                if (sampleRate != 44100 && sampleRate != 48000) return null

                // Required: micId string (strict String, not Number, not Blank, not UNKNOWN/PENDING)
                if (!json.has("micId") || json.isNull("micId")) return null
                val rawMicId = json.opt("micId")
                if (rawMicId !is String) return null
                if (rawMicId.isBlank() || rawMicId == UNKNOWN_MICROPHONE_ID || rawMicId == PENDING_MICROPHONE_ID) return null

                // Required: devType string (strict String, not Boolean, not Blank, in KNOWN_DEVICE_TYPES)
                if (!json.has("devType") || json.isNull("devType")) return null
                val rawDevType = json.opt("devType")
                if (rawDevType !is String) return null
                if (rawDevType.isBlank() || rawDevType == UNKNOWN_DEVICE_TYPE || rawDevType == PENDING_DEVICE_TYPE || rawDevType !in KNOWN_DEVICE_TYPES) return null

                // Required: selectedSource & actualSource (strict whole Number, 0..10, no overflow)
                if (!json.has("selectedSource") || json.isNull("selectedSource")) return null
                val rawSelectedSource = json.opt("selectedSource")
                val selectedSource = parseStrictInt(rawSelectedSource, 0, 10) ?: return null

                if (!json.has("actualSource") || json.isNull("actualSource")) return null
                val rawActualSource = json.opt("actualSource")
                val actualSource = parseStrictInt(rawActualSource, 0, 10) ?: return null

                // Required: fallback boolean (strict Boolean, must match selectedSource != actualSource)
                if (!json.has("fallback") || json.isNull("fallback")) return null
                val rawFallback = json.opt("fallback")
                if (rawFallback !is Boolean) return null
                val expectedFallback = (selectedSource != actualSource)
                if (rawFallback != expectedFallback) return null

                // Required: effects object
                if (!json.has("effects") || json.isNull("effects")) return null
                val effectsJson = json.optJSONObject("effects") ?: return null
                val effects = CaptureEffectsInfo.fromJson(effectsJson) ?: return null

                CaptureFingerprint(
                    dspRevision = dspRev,
                    actualSampleRate = sampleRate,
                    routedMicrophoneId = rawMicId,
                    routedDeviceType = rawDevType,
                    selectedAudioSource = selectedSource,
                    actualAudioSource = actualSource,
                    isSourceFallback = rawFallback,
                    effects = effects
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Immutable frame identity and context bound atomically to a discrete PCM audio frame hop.
 *
 * Prevents collecting noise samples from status-only transitions, metadata-only updates,
 * repeated emissions of the same frame, obsolete generations, or replaced managers.
 */
data class CapturedFrameContext(
    val managerId: String,
    val captureGeneration: Long,
    val frameSequence: Long,
    val fingerprint: CaptureFingerprint,
    val isRunning: Boolean = true,
    val routeEpoch: Long = 0L
)

/**
 * Immutable capture authority snapshot separating live running context from historical frame data.
 */
data class CaptureAuthoritySnapshot(
    val managerId: String = "",
    val captureGeneration: Long = 0L,
    val routeEpoch: Long = 0L,
    val fingerprint: CaptureFingerprint? = null,
    val isRunning: Boolean = false,
    val latestPublishedSequence: Long = 0L,
    val selectedAudioSource: Int = 9,
    val actualAudioSource: Int = 9,
    val isSourceFallback: Boolean = false,
    val effects: CaptureEffectsInfo = CaptureEffectsInfo()
) {
    val isValidLiveAuthority: Boolean
        get() = isRunning && managerId.isNotBlank() && captureGeneration > 0L && routeEpoch > 0L && fingerprint != null && fingerprint.isMicrophoneKnown

    val isValidRunningAuthority: Boolean
        get() = isValidLiveAuthority
}

/**
 * Compatibility status of a captured noise floor against live capture context.
 */
sealed class NoiseFloorCompatibility {
    object Uncaptured : NoiseFloorCompatibility() {
        override fun toString(): String = "No noise floor captured"
    }

    object Compatible : NoiseFloorCompatibility() {
        override fun toString(): String = "Compatible"
    }

    data class Incompatible(
        val reason: String,
        val requiresRecapture: Boolean = true
    ) : NoiseFloorCompatibility() {
        override fun toString(): String = "Incompatible: $reason"
    }
}
