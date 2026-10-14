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
        put("aecEnabled", aecSessionEnabled)
        put("agcEnabled", agcSessionEnabled)
        put("nsEnabled", nsSessionEnabled)
    }

    companion object {
        fun fromJson(json: JSONObject?): CaptureEffectsInfo? {
            if (json == null) return null
            if (!json.has("aec") || !json.has("agc") || !json.has("ns") || !json.has("known")) {
                return null
            }
            if (json.isNull("aec") || json.isNull("agc") || json.isNull("ns") || json.isNull("known")) {
                return null
            }
            val aec = try { json.getBoolean("aec") } catch (_: Exception) { return null }
            val agc = try { json.getBoolean("agc") } catch (_: Exception) { return null }
            val ns = try { json.getBoolean("ns") } catch (_: Exception) { return null }
            val known = try { json.getBoolean("known") } catch (_: Exception) { return null }
            val aecEn = json.optBoolean("aecEnabled", false)
            val agcEn = json.optBoolean("agcEnabled", false)
            val nsEn = json.optBoolean("nsEnabled", false)

            return CaptureEffectsInfo(
                aecAvailable = aec,
                agcAvailable = agc,
                nsAvailable = ns,
                isEffectsKnown = known,
                aecSessionEnabled = aecEn,
                agcSessionEnabled = agcEn,
                nsSessionEnabled = nsEn
            )
        }
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
            return try {
                val json = JSONObject(jsonStr)

                // Required: dspRevision object
                if (!json.has("dspRevision") || json.isNull("dspRevision")) return null
                val dspJson = json.optJSONObject("dspRevision") ?: return null
                val dspRev = DspResponseRevision.fromJson(dspJson) ?: return null

                // Required: sampleRate integer
                if (!json.has("sampleRate") || json.isNull("sampleRate")) return null
                val sampleRate = json.getInt("sampleRate")
                if (sampleRate != 44100 && sampleRate != 48000) return null

                // Required: micId string
                if (!json.has("micId") || json.isNull("micId")) return null
                val micId = json.getString("micId")
                if (micId.isBlank() || micId == UNKNOWN_MICROPHONE_ID || micId == PENDING_MICROPHONE_ID) return null

                // Required: devType string
                if (!json.has("devType") || json.isNull("devType")) return null
                val devType = json.getString("devType")
                if (devType.isBlank() || devType == UNKNOWN_DEVICE_TYPE || devType == PENDING_DEVICE_TYPE || devType !in KNOWN_DEVICE_TYPES) return null

                // Required: selectedSource & actualSource
                if (!json.has("selectedSource") || json.isNull("selectedSource")) return null
                val selectedSource = json.getInt("selectedSource")
                if (selectedSource < 0 || selectedSource > 10) return null

                if (!json.has("actualSource") || json.isNull("actualSource")) return null
                val actualSource = json.getInt("actualSource")
                if (actualSource < 0 || actualSource > 10) return null

                // Required: fallback boolean, must match (selectedSource != actualSource)
                if (!json.has("fallback") || json.isNull("fallback")) return null
                val fallback = json.getBoolean("fallback")
                val expectedFallback = (selectedSource != actualSource)
                if (fallback != expectedFallback) return null

                // Required: effects object
                if (!json.has("effects") || json.isNull("effects")) return null
                val effectsJson = json.optJSONObject("effects") ?: return null
                val effects = CaptureEffectsInfo.fromJson(effectsJson) ?: return null

                CaptureFingerprint(
                    dspRevision = dspRev,
                    actualSampleRate = sampleRate,
                    routedMicrophoneId = micId,
                    routedDeviceType = devType,
                    selectedAudioSource = selectedSource,
                    actualAudioSource = actualSource,
                    isSourceFallback = fallback,
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
