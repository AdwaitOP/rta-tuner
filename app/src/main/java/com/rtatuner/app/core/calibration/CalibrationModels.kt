package com.rtatuner.app.core.calibration

import kotlin.math.log10

/**
 * A single frequency / dB-offset point on a microphone frequency response calibration curve.
 */
data class MicCalibrationPoint(
    val freqHz: Float,
    val dbOffset: Float // Additive correction: calibrated_dB = raw_dB + dbOffset
)

/**
 * Microphone calibration curve with smooth log-frequency interpolation.
 */
data class MicCalibrationCurve(
    val name: String,
    val points: List<MicCalibrationPoint>
) {
    init {
        // Ensure points are sorted by frequency ascending
        require(points.isEmpty() || points.zipWithNext { a, b -> a.freqHz <= b.freqHz }.all { it }) {
            "Calibration points must be sorted in ascending frequency order"
        }
    }

    /**
     * Evaluates the calibration correction offset at any given frequency using
     * piecewise linear interpolation in logarithmic frequency space.
     */
    fun getOffsetAt(freqHz: Float): Float {
        if (freqHz.isNaN() || freqHz.isInfinite()) return 0.0f
        if (points.isEmpty()) return 0.0f
        if (points.size == 1) return points[0].dbOffset

        if (freqHz <= points.first().freqHz) return points.first().dbOffset
        if (freqHz >= points.last().freqHz) return points.last().dbOffset

        var low = 0
        var high = points.size - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (points[mid].freqHz <= freqHz) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        val i0 = (low - 1).coerceAtLeast(0)
        val i1 = (i0 + 1).coerceAtMost(points.size - 1)
        val p0 = points[i0]
        val p1 = points[i1]
        if (p0.freqHz >= p1.freqHz || p0.freqHz <= 0f || p1.freqHz <= 0f) return p0.dbOffset

        // Log-frequency interpolation: t = log10(f / f0) / log10(f1 / f0)
        val logF = log10(freqHz / p0.freqHz)
        val logSpan = log10(p1.freqHz / p0.freqHz)
        val rawT = if (logSpan > 1e-6f && !logSpan.isNaN() && !logF.isNaN()) (logF / logSpan) else 0.0f
        val t = if (rawT.isNaN() || rawT.isInfinite()) 0.0f else rawT.coerceIn(0.0f, 1.0f)
        val offset = p0.dbOffset + t * (p1.dbOffset - p0.dbOffset)
        return if (offset.isNaN() || offset.isInfinite()) 0.0f else offset
    }
}

/**
 * Target curve point (frequency Hz, target relative dB).
 */
data class TargetCurvePoint(
    val freqHz: Float,
    val targetDb: Float
)

/**
 * Target acoustic response curve with log-frequency interpolation.
 */
data class TargetCurve(
    val id: String,
    val name: String,
    val points: List<TargetCurvePoint>
) {
    fun getTargetDbAt(freqHz: Float): Float {
        if (freqHz.isNaN() || freqHz.isInfinite()) return 0.0f
        if (points.isEmpty()) return 0.0f
        if (points.size == 1) return points[0].targetDb

        if (freqHz <= points.first().freqHz) return points.first().targetDb
        if (freqHz >= points.last().freqHz) return points.last().targetDb

        var low = 0
        var high = points.size - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (points[mid].freqHz <= freqHz) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        val i0 = (low - 1).coerceAtLeast(0)
        val i1 = (i0 + 1).coerceAtMost(points.size - 1)
        val p0 = points[i0]
        val p1 = points[i1]
        if (p0.freqHz >= p1.freqHz || p0.freqHz <= 0f || p1.freqHz <= 0f) return p0.targetDb

        val logF = log10(freqHz / p0.freqHz)
        val logSpan = log10(p1.freqHz / p0.freqHz)
        val rawT = if (logSpan > 1e-6f && !logSpan.isNaN() && !logF.isNaN()) (logF / logSpan) else 0.0f
        val t = if (rawT.isNaN() || rawT.isInfinite()) 0.0f else rawT.coerceIn(0.0f, 1.0f)
        val target = p0.targetDb + t * (p1.targetDb - p0.targetDb)
        return if (target.isNaN() || target.isInfinite()) 0.0f else target
    }

    companion object {
        val FLAT = TargetCurve(
            id = "FLAT",
            name = "Flat (0 dB)",
            points = listOf(
                TargetCurvePoint(20.0f, 0.0f),
                TargetCurvePoint(20000.0f, 0.0f)
            )
        )

        val HARMAN_CAR = TargetCurve(
            id = "HARMAN_CAR",
            name = "Harman Automotive (+9dB Sub)",
            points = listOf(
                TargetCurvePoint(20.0f, 9.0f),
                TargetCurvePoint(31.5f, 9.0f),
                TargetCurvePoint(50.0f, 8.0f),
                TargetCurvePoint(80.0f, 6.0f),
                TargetCurvePoint(125.0f, 3.5f),
                TargetCurvePoint(200.0f, 1.0f),
                TargetCurvePoint(500.0f, 0.0f),
                TargetCurvePoint(1000.0f, 0.0f),
                TargetCurvePoint(2000.0f, 0.0f),
                TargetCurvePoint(4000.0f, -0.5f),
                TargetCurvePoint(8000.0f, -1.5f),
                TargetCurvePoint(16000.0f, -3.0f),
                TargetCurvePoint(20000.0f, -4.0f)
            )
        )

        val AUDIO_CONTROL_CAR = TargetCurve(
            id = "AUDIO_CONTROL_CAR",
            name = "AudioControl House Curve (+6dB)",
            points = listOf(
                TargetCurvePoint(20.0f, 6.0f),
                TargetCurvePoint(40.0f, 6.0f),
                TargetCurvePoint(80.0f, 5.0f),
                TargetCurvePoint(160.0f, 2.0f),
                TargetCurvePoint(315.0f, 0.0f),
                TargetCurvePoint(2000.0f, 0.0f),
                TargetCurvePoint(8000.0f, -1.0f),
                TargetCurvePoint(20000.0f, -2.0f)
            )
        )

        val PRESETS = listOf(FLAT, HARMAN_CAR, AUDIO_CONTROL_CAR)
    }
}

/**
 * Snapshot of captured ambient room/cabin noise floor.
 */
data class NoiseFloorSnapshot(
    val bands31Db: FloatArray = FloatArray(31) { -100.0f },
    val captureTimestampMs: Long = 0L,
    val isCaptured: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NoiseFloorSnapshot
        if (!bands31Db.contentEquals(other.bands31Db)) return false
        if (captureTimestampMs != other.captureTimestampMs) return false
        if (isCaptured != other.isCaptured) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bands31Db.contentHashCode()
        result = 31 * result + captureTimestampMs.hashCode()
        result = 31 * result + isCaptured.hashCode()
        return result
    }
}

/**
 * Single-point reference SPL calibration.
 */
data class SplCalibration(
    val isSplMode: Boolean = false,
    val splOffsetDb: Float = 100.0f // dBSPL = dBFS + splOffsetDb
)

/**
 * Calibration curve file parser supporting CSV, TSV, and whitespace-delimited formats
 * with headers, comments, or quotes commonly used by UMIK-1, Dayton IMM-6, and REW files.
 */
object CalibrationCurveParser {

    fun parse(name: String, textContent: String): Result<MicCalibrationCurve> {
        val points = mutableListOf<MicCalibrationPoint>()
        val lines = textContent.lines()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("*") || line.startsWith(";") || line.startsWith("//")) {
                continue
            }

            // Split by comma, tab, or whitespace
            val tokens = line.split(Regex("[,\\t\\s]+")).map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
            if (tokens.size >= 2) {
                val freq = tokens[0].toFloatOrNull()
                val offset = tokens[1].toFloatOrNull()
                if (freq != null && offset != null && freq in 5.0f..100000.0f && offset in -100.0f..100.0f) {
                    points.add(MicCalibrationPoint(freq, offset))
                }
            }
        }

        if (points.size < 2) {
            return Result.failure(IllegalArgumentException("Calibration file must contain at least 2 valid frequency/offset pairs. Found: ${points.size}"))
        }

        // Sort by frequency
        val sortedPoints = points.sortedBy { it.freqHz }
        return Result.success(MicCalibrationCurve(name = name, points = sortedPoints))
    }
}
