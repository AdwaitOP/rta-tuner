package com.rtatuner.app.core.audio

import com.rtatuner.app.core.dsp.FftResult

/**
 * Represents a single analyzed audio frame containing raw PCM samples for a window,
 * instantaneous acoustic metrics, FFT spectral analysis, and capture timing metadata.
 */
data class AudioFrame(
    /** Window of 16-bit PCM samples with 50% overlap applied (e.g. 2048 samples) */
    val samples: ShortArray,
    /** Sampling rate in Hz (e.g. 48000 or 44100) */
    val sampleRate: Int,
    /** Root-Mean-Square level in dBFS, clamped to -100 dBFS floor */
    val rmsDb: Float,
    /** Instantaneous maximum peak level in dBFS */
    val peakDb: Float,
    /** True if any sample in the hop reached or exceeded full-scale digital clipping threshold */
    val isClipping: Boolean,
    /** Real-time FFT spectral results (raw and 1/3-octave smoothed) */
    val fftResult: FftResult? = null,
    /** High-resolution timestamp in nanoseconds */
    val timestampNs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioFrame

        if (!samples.contentEquals(other.samples)) return false
        if (sampleRate != other.sampleRate) return false
        if (rmsDb != other.rmsDb) return false
        if (peakDb != other.peakDb) return false
        if (isClipping != other.isClipping) return false
        if (fftResult != other.fftResult) return false
        if (timestampNs != other.timestampNs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + rmsDb.hashCode()
        result = 31 * result + peakDb.hashCode()
        result = 31 * result + isClipping.hashCode()
        result = 31 * result + (fftResult?.hashCode() ?: 0)
        result = 31 * result + timestampNs.hashCode()
        return result
    }
}
