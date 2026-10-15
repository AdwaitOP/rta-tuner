package com.rtatuner.app.core.dsp

import com.rtatuner.app.core.audio.parseStrictInt
import org.json.JSONObject

/**
 * Explicit, stable measurement-response revision for 1/3-octave band analysis.
 *
 * Captures materially relevant factors affecting the numerical meaning of raw band measurements:
 * - Filter architecture / implementation / coefficient revision
 * - Band center-frequency preset (e.g. ISO 266 nominal 31 bands)
 * - Q / fractional-octave bandwidth definition (e.g. 1/3-octave with cascade compensation)
 * - Cascade and normalization behavior (e.g. 4th-order cascade with 0 dB peak unity gain)
 * - Edge-anchoring design (e.g. bilinear pre-warping with Nyquist tangent compression)
 *
 * This revision must change whenever any future DSP modification alters the numerical
 * meaning of raw band measurements.
 */
data class DspResponseRevision(
    val revisionId: String = CURRENT_REVISION_ID,
    val filterType: String = FILTER_TYPE_4TH_ORDER_CASCADE_BIQUAD,
    val coefficientRevision: Int = COEFFICIENT_REVISION_WAVE1,
    val centerFrequencyPreset: String = PRESET_ISO_266_31_BAND,
    val qBandwidthDefinition: String = Q_1_3_OCTAVE_CASCADE_COMPENSATED,
    val cascadeAndNormalization: String = CASCADE_NORMALIZATION_UNITY_0DB_PEAK,
    val edgeAnchoringDesign: String = EDGE_ANCHORING_BILINEAR_NYQUIST_WARPED
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("revisionId", revisionId)
        put("filterType", filterType)
        put("coefficientRevision", coefficientRevision)
        put("centerFrequencyPreset", centerFrequencyPreset)
        put("qBandwidthDefinition", qBandwidthDefinition)
        put("cascadeAndNormalization", cascadeAndNormalization)
        put("edgeAnchoringDesign", edgeAnchoringDesign)
    }

    companion object {
        const val CURRENT_REVISION_ID = "CABINRTA_DSP_WAVE1_BIQUAD_V1"
        const val FILTER_TYPE_4TH_ORDER_CASCADE_BIQUAD = "4th_order_cascaded_biquad_iir"
        const val COEFFICIENT_REVISION_WAVE1 = 1
        const val PRESET_ISO_266_31_BAND = "ISO_266_31_BAND_NOMINAL"
        const val Q_1_3_OCTAVE_CASCADE_COMPENSATED = "STANDARD_1_3_OCTAVE_CASCADE_COMPENSATED_Q4.318"
        const val CASCADE_NORMALIZATION_UNITY_0DB_PEAK = "DUAL_2ND_ORDER_CASCADE_UNITY_0DB_PEAK"
        const val EDGE_ANCHORING_BILINEAR_NYQUIST_WARPED = "BILINEAR_PREWARPED_CENTER_NYQUIST_TANGENT_WARPED"

        val CURRENT = DspResponseRevision()

        fun fromJson(json: JSONObject?): DspResponseRevision? {
            if (json == null) return null
            val strFields = listOf("revisionId", "filterType", "centerFrequencyPreset", "qBandwidthDefinition", "cascadeAndNormalization", "edgeAnchoringDesign")
            for (f in strFields) {
                if (!json.has(f) || json.isNull(f)) return null
                val raw = json.opt(f)
                if (raw !is String || raw.isBlank()) return null
            }

            if (!json.has("coefficientRevision") || json.isNull("coefficientRevision")) return null
            val rawRev = json.opt("coefficientRevision")
            val cRev = parseStrictInt(rawRev, 1, Int.MAX_VALUE) ?: return null

            return DspResponseRevision(
                revisionId = json.getString("revisionId"),
                filterType = json.getString("filterType"),
                coefficientRevision = cRev,
                centerFrequencyPreset = json.getString("centerFrequencyPreset"),
                qBandwidthDefinition = json.getString("qBandwidthDefinition"),
                cascadeAndNormalization = json.getString("cascadeAndNormalization"),
                edgeAnchoringDesign = json.getString("edgeAnchoringDesign")
            )
        }
    }
}
