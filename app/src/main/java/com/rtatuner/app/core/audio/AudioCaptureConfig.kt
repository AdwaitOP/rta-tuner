package com.rtatuner.app.core.audio

import android.media.MediaRecorder

/**
 * Configuration parameters for the AudioRecord pipeline.
 */
data class AudioCaptureConfig(
    /** Analysis window size (must be power of 2, typically 1024, 2048, or 4096) */
    val windowSize: Int = 2048,
    /** Hop size for 50% overlap (windowSize / 2) */
    val hopSize: Int = 1024,
    /** Specific requested sample rate, or null to auto-detect hardware native rate */
    val targetSampleRate: Int? = null,
    /** Preferred audio source (defaults to UNPROCESSED to avoid AGC / noise suppression) */
    val preferredAudioSource: Int = MediaRecorder.AudioSource.UNPROCESSED,
    /** Specific hardware input device ID (null for system default auto-routing) */
    val preferredDeviceId: Int? = null
)
