package com.rtatuner.app.core.dsp

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
            if (!json.has("revisionId") || json.isNull("revisionId")) return null
            if (!json.has("filterType") || json.isNull("filterType")) return null
            if (!json.has("coefficientRevision") || json.isNull("coefficientRevision")) return null
            if (!json.has("centerFrequencyPreset") || json.isNull("centerFrequencyPreset")) return null
            if (!json.has("qBandwidthDefinition") || json.isNull("qBandwidthDefinition")) return null
            if (!json.has("cascadeAndNormalization") || json.isNull("cascadeAndNormalization")) return null
            if (!json.has("edgeAnchoringDesign") || json.isNull("edgeAnchoringDesign")) return null

            val revId = json.optString("revisionId")
            val fType = json.optString("filterType")
            val cRev = json.optInt("coefficientRevision", -1)
            val preset = json.optString("centerFrequencyPreset")
            val qDef = json.optString("qBandwidthDefinition")
            val cNorm = json.optString("cascadeAndNormalization")
            val edge = json.optString("edgeAnchoringDesign")

            if (revId.isBlank() || fType.isBlank() || cRev <= 0 ||
                preset.isBlank() || qDef.isBlank() || cNorm.isBlank() || edge.isBlank()) {
                return null
            }

            return DspResponseRevision(
                revisionId = revId,
                filterType = fType,
                coefficientRevision = cRev,
                centerFrequencyPreset = preset,
                qBandwidthDefinition = qDef,
                cascadeAndNormalization = cNorm,
                edgeAnchoringDesign = edge
            )
        }
    }
}
