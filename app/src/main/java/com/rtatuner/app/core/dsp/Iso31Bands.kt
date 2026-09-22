package com.rtatuner.app.core.dsp

import kotlin.math.pow

/**
 * Standard 1/3-octave 31-band ISO center frequencies and boundary definitions (IEC 61260 / ISO 266).
 */
object Iso31Bands {

    const val NUM_BANDS = 31

    /** 1/3 octave ratio = 2^(1/6) */
    val OCTAVE_RATIO = 2.0.pow(1.0 / 6.0)

    /** Standard ISO 31 center frequencies in Hz */
    val CENTER_FREQUENCIES = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
        200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
        2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f,
        20000f
    )

    /** Concise axis labels for display */
    val LABELS = arrayOf(
        "20", "25", "31.5", "40", "50", "63", "80", "100", "125", "160",
        "200", "250", "315", "400", "500", "630", "800", "1k", "1.25k", "1.6k",
        "2k", "2.5k", "3.15k", "4k", "5k", "6.3k", "8k", "10k", "12.5k", "16k",
        "20k"
    )

    /** Precomputed lower cutoff frequency for each band in Hz */
    val LOWER_EDGES = FloatArray(NUM_BANDS) { i ->
        (CENTER_FREQUENCIES[i] / OCTAVE_RATIO).toFloat()
    }

    /** Precomputed upper cutoff frequency for each band in Hz */
    val UPPER_EDGES = FloatArray(NUM_BANDS) { i ->
        (CENTER_FREQUENCIES[i] * OCTAVE_RATIO).toFloat()
    }
}
