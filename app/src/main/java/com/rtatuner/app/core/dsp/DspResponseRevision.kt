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

        fun fromJson(json: JSONObject?): DspResponseRevision {
            if (json == null) return CURRENT
            return DspResponseRevision(
                revisionId = json.optString("revisionId", CURRENT_REVISION_ID),
                filterType = json.optString("filterType", FILTER_TYPE_4TH_ORDER_CASCADE_BIQUAD),
                coefficientRevision = json.optInt("coefficientRevision", COEFFICIENT_REVISION_WAVE1),
                centerFrequencyPreset = json.optString("centerFrequencyPreset", PRESET_ISO_266_31_BAND),
                qBandwidthDefinition = json.optString("qBandwidthDefinition", Q_1_3_OCTAVE_CASCADE_COMPENSATED),
                cascadeAndNormalization = json.optString("cascadeAndNormalization", CASCADE_NORMALIZATION_UNITY_0DB_PEAK),
                edgeAnchoringDesign = json.optString("edgeAnchoringDesign", EDGE_ANCHORING_BILINEAR_NYQUIST_WARPED)
            )
        }
    }
}
