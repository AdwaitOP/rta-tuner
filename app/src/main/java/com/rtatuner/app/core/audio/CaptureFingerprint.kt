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
 * Checks original JSON number tokens before JSONObject can round them to Doubles.
 * Object names are decoded before comparison; each object has its own duplicate-name
 * set and only the fingerprint's actual numeric property paths have numeric bounds.
 */
internal fun validateNumericTokens(jsonStr: String): Boolean = try {
    FingerprintJsonTokenReader(jsonStr).readDocument()
    true
} catch (_: Exception) {
    false
}

/** Strict JSON token reader; never converts original number text through JSONObject. */
private class FingerprintJsonTokenReader(private val input: String) {
    private enum class Scope { ROOT, DSP_REVISION, OTHER }

    private var offset = 0

    fun readDocument() {
        readObject(Scope.ROOT, 0)
        skipWhitespace()
        require(offset == input.length)
    }

    private fun readObject(scope: Scope, depth: Int) {
        require(depth <= 64)
        consume('{')
        val names = HashSet<String>()
        if (take('}')) return
        do {
            skipWhitespace()
            val name = readString()
            require(names.add(name))
            consume(':')
            val isNumeric = (scope == Scope.ROOT && (name == "sampleRate" || name == "selectedSource" || name == "actualSource")) ||
                (scope == Scope.DSP_REVISION && name == "coefficientRevision")
            if (isNumeric) {
                skipWhitespace()
                requireValidNumber(name, readNumber())
            } else {
                val childScope = if (scope == Scope.ROOT && name == "dspRevision") Scope.DSP_REVISION else Scope.OTHER
                readValue(childScope, depth + 1)
            }
        } while (take(','))
        consume('}')
    }

    private fun readArray(depth: Int) {
        consume('[')
        if (take(']')) return
        do {
            readValue(Scope.OTHER, depth + 1)
        } while (take(','))
        consume(']')
    }

    private fun readValue(scope: Scope, depth: Int) {
        require(depth <= 64)
        skipWhitespace()
        when (current()) {
            '{' -> readObject(scope, depth)
            '[' -> readArray(depth)
            '"' -> readString()
            't' -> readLiteral("true")
            'f' -> readLiteral("false")
            'n' -> readLiteral("null")
            else -> readNumber()
        }
    }

    private fun readString(): String {
        require(current() == '"')
        offset++
        val decoded = StringBuilder()
        while (offset < input.length) {
            val ch = input[offset++]
            when {
                ch == '"' -> return decoded.toString()
                ch == '\\' -> {
                    require(offset < input.length)
                    when (val escaped = input[offset++]) {
                        '"', '\\', '/' -> decoded.append(escaped)
                        'b' -> decoded.append('\b')
                        'f' -> decoded.append('\u000c')
                        'n' -> decoded.append('\n')
                        'r' -> decoded.append('\r')
                        't' -> decoded.append('\t')
                        'u' -> {
                            require(offset + 4 <= input.length)
                            var codePoint = 0
                            repeat(4) {
                                val hex = input[offset++]
                                val digit = when (hex) {
                                    in '0'..'9' -> hex - '0'
                                    in 'a'..'f' -> hex - 'a' + 10
                                    in 'A'..'F' -> hex - 'A' + 10
                                    else -> null
                                }
                                require(digit != null)
                                codePoint = codePoint * 16 + digit
                            }
                            decoded.append(codePoint.toChar())
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape")
                    }
                }
                ch < ' ' -> throw IllegalArgumentException("Invalid JSON control character")
                else -> decoded.append(ch)
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun readNumber(): String {
        val start = offset
        if (current() == '-') offset++
        if (current() == '0') {
            offset++
        } else {
            require(current() in '1'..'9')
            while (current() in '0'..'9') offset++
        }
        if (current() == '.') {
            offset++
            require(current() in '0'..'9')
            while (current() in '0'..'9') offset++
        }
        if (current() == 'e' || current() == 'E') {
            offset++
            if (current() == '+' || current() == '-') offset++
            require(current() in '0'..'9')
            while (current() in '0'..'9') offset++
        }
        return input.substring(start, offset)
    }

    private fun requireValidNumber(name: String, token: String) {
        val number = java.math.BigDecimal(token)
        if (name == "sampleRate") {
            require(number.compareTo(java.math.BigDecimal.valueOf(44100)) == 0 ||
                number.compareTo(java.math.BigDecimal.valueOf(48000)) == 0)
        } else {
            val min = if (name == "coefficientRevision") 1L else 0L
            val max = if (name == "coefficientRevision") Int.MAX_VALUE.toLong() else 10L
            // Check bounds before integrality; huge exponents never allocate huge integers.
            require(number >= java.math.BigDecimal.valueOf(min) && number <= java.math.BigDecimal.valueOf(max))
            require(number.stripTrailingZeros().scale() <= 0)
        }
    }

    private fun readLiteral(literal: String) {
        require(input.startsWith(literal, offset))
        offset += literal.length
    }

    private fun current(): Char = input.getOrNull(offset) ?: '\u0000'

    private fun skipWhitespace() {
        while (current() == ' ' || current() == '\t' || current() == '\r' || current() == '\n') offset++
    }

    private fun take(expected: Char): Boolean {
        skipWhitespace()
        if (current() != expected) return false
        offset++
        return true
    }

    private fun consume(expected: Char) {
        require(take(expected))
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
