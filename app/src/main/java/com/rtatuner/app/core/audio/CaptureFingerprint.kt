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
    val isEffectsKnown: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("aec", aecAvailable)
        put("agc", agcAvailable)
        put("ns", nsAvailable)
        put("known", isEffectsKnown)
    }

    companion object {
        fun fromJson(json: JSONObject?): CaptureEffectsInfo {
            if (json == null) return CaptureEffectsInfo(isEffectsKnown = false)
            return CaptureEffectsInfo(
                aecAvailable = json.optBoolean("aec", false),
                agcAvailable = json.optBoolean("agc", false),
                nsAvailable = json.optBoolean("ns", false),
                isEffectsKnown = json.optBoolean("known", true)
            )
        }
    }
}

/**
 * Small, immutable capture fingerprint describing the authoritative measurement context.
 *
 * Requirements:
 * - Versioned DSP response revision
 * - Actual PCM sample rate
 * - Actual routed microphone identity (observed, not requested)
 * - Relevant route/device type
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
                routedDeviceType != PENDING_DEVICE_TYPE

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

        fun fromJsonString(jsonStr: String?): CaptureFingerprint? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val json = JSONObject(jsonStr)
                val dspJson = json.optJSONObject("dspRevision")
                val dspRev = if (dspJson != null) DspResponseRevision.fromJson(dspJson) else DspResponseRevision.CURRENT
                val effectsJson = json.optJSONObject("effects")
                val effects = CaptureEffectsInfo.fromJson(effectsJson)

                CaptureFingerprint(
                    dspRevision = dspRev,
                    actualSampleRate = json.optInt("sampleRate", 0),
                    routedMicrophoneId = json.optString("micId", UNKNOWN_MICROPHONE_ID),
                    routedDeviceType = json.optString("devType", UNKNOWN_DEVICE_TYPE),
                    selectedAudioSource = json.optInt("selectedSource", 0),
                    actualAudioSource = json.optInt("actualSource", 0),
                    isSourceFallback = json.optBoolean("fallback", false),
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
    val isRunning: Boolean = true
)

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
