package com.rtatuner.app.core.session

import java.util.UUID

/**
 * Snapshot color palette providing visually distinct, high-contrast traces for multiple
 * frozen overlays on the dark RTA console.
 */
object SnapshotPalette {
    val COLORS = longArrayOf(
        0xFF00E5FF, // Electric Cyan (Snapshot 1)
        0xFFFF4081, // Vibrant Magenta (Snapshot 2)
        0xFFFFB300, // Warm Amber (Snapshot 3)
        0xFF76FF03, // Neon Lime (Snapshot 4)
        0xFFB388FF, // Electric Violet (Snapshot 5)
        0xFFFF6E40  // Bright Tangerine (Snapshot 6)
    )

    fun getColorForIndex(index: Int): Long {
        return COLORS[index % COLORS.size]
    }
}

/**
 * Represents a frozen static snapshot of the 31-band spectrum.
 * Captures the complete acoustic measurement and correction context at the moment of freeze.
 */
data class RtaSnapshot(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    val bands31Db: FloatArray, // 31 band values in dB (post-calib, post-noise)
    val isSplMode: Boolean = false,
    val splOffsetDb: Float = 0.0f,
    val calibrationName: String? = null,
    val targetCurveName: String? = null,
    val windowSize: Int = 2048,
    val isNoiseSubtractionActive: Boolean = false,
    val activeNoiseFloorBands: FloatArray? = null,
    val colorHex: Long = 0xFF00E5FF,
    val isVisible: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RtaSnapshot
        if (id != other.id) return false
        if (name != other.name) return false
        if (timestamp != other.timestamp) return false
        if (!bands31Db.contentEquals(other.bands31Db)) return false
        if (isSplMode != other.isSplMode) return false
        if (splOffsetDb != other.splOffsetDb) return false
        if (calibrationName != other.calibrationName) return false
        if (targetCurveName != other.targetCurveName) return false
        if (windowSize != other.windowSize) return false
        if (isNoiseSubtractionActive != other.isNoiseSubtractionActive) return false
        if (activeNoiseFloorBands != null) {
            if (other.activeNoiseFloorBands == null || !activeNoiseFloorBands.contentEquals(other.activeNoiseFloorBands)) return false
        } else if (other.activeNoiseFloorBands != null) return false
        if (colorHex != other.colorHex) return false
        if (isVisible != other.isVisible) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + bands31Db.contentHashCode()
        result = 31 * result + isSplMode.hashCode()
        result = 31 * result + splOffsetDb.hashCode()
        result = 31 * result + (calibrationName?.hashCode() ?: 0)
        result = 31 * result + (targetCurveName?.hashCode() ?: 0)
        result = 31 * result + windowSize
        result = 31 * result + isNoiseSubtractionActive.hashCode()
        result = 31 * result + (activeNoiseFloorBands?.contentHashCode() ?: 0)
        result = 31 * result + colorHex.hashCode()
        result = 31 * result + isVisible.hashCode()
        return result
    }
}
