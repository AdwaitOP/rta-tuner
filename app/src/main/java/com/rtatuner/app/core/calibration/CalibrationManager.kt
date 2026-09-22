package com.rtatuner.app.core.calibration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.dsp.Iso31Bands
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.pow

/**
 * Output of calibration pipeline applied to an incoming audio frame.
 */
data class CalibratedSpectralFrame(
    val calibrated31Db: FloatArray,
    val calibratedRawFftDb: FloatArray,
    val unreliableBands: BooleanArray, // True if SNR < 6 dB or signal <= noise floor
    val targetDeltas31Db: FloatArray?, // measured - target (null if target inactive)
    val isSplMode: Boolean,
    val splOffsetDb: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CalibratedSpectralFrame
        if (!calibrated31Db.contentEquals(other.calibrated31Db)) return false
        if (!calibratedRawFftDb.contentEquals(other.calibratedRawFftDb)) return false
        if (!unreliableBands.contentEquals(other.unreliableBands)) return false
        if (targetDeltas31Db != null) {
            if (other.targetDeltas31Db == null || !targetDeltas31Db.contentEquals(other.targetDeltas31Db)) return false
        } else if (other.targetDeltas31Db != null) return false
        if (isSplMode != other.isSplMode) return false
        if (splOffsetDb != other.splOffsetDb) return false
        return true
    }

    override fun hashCode(): Int {
        var result = calibrated31Db.contentHashCode()
        result = 31 * result + calibratedRawFftDb.contentHashCode()
        result = 31 * result + unreliableBands.contentHashCode()
        result = 31 * result + (targetDeltas31Db?.contentHashCode() ?: 0)
        result = 31 * result + isSplMode.hashCode()
        result = 31 * result + splOffsetDb.hashCode()
        return result
    }
}

/**
 * UI State for Stage 5 calibration & target curve status.
 */
data class CalibrationUiState(
    val micCurve: MicCalibrationCurve? = null,
    val isMicCurveActive: Boolean = false,
    val micCurveStatusText: String = "Calib.: None",
    val splCalibration: SplCalibration = SplCalibration(),
    val targetCurve: TargetCurve? = TargetCurve.HARMAN_CAR,
    val isTargetCurveActive: Boolean = false,
    val noiseFloor: NoiseFloorSnapshot = NoiseFloorSnapshot(),
    val isNoiseSubtractionActive: Boolean = false,
    val isCapturingNoiseFloor: Boolean = false,
    val noiseCaptureProgress: Float = 0.0f
)

/**
 * Orchestrates mic calibration curves, dB SPL reference calibration, target curve overlays,
 * and power-domain noise floor capture & subtraction with persistence.
 */
class CalibrationManager(context: Context) {

    companion object {
        private const val TAG = "CalibrationManager"
        private const val PREFS_NAME = "rta_calibration_prefs"

        private const val KEY_MIC_CURVE_NAME = "mic_curve_name"
        private const val KEY_MIC_CURVE_POINTS = "mic_curve_points"
        private const val KEY_MIC_CURVE_ACTIVE = "mic_curve_active"

        private const val KEY_SPL_MODE = "spl_mode"
        private const val KEY_SPL_OFFSET_DB = "spl_offset_db"

        private const val KEY_TARGET_CURVE_ID = "target_curve_id"
        private const val KEY_TARGET_CURVE_ACTIVE = "target_curve_active"

        private const val KEY_NOISE_FLOOR_BANDS = "noise_floor_bands"
        private const val KEY_NOISE_FLOOR_TIMESTAMP = "noise_floor_timestamp"
        private const val KEY_NOISE_FLOOR_CAPTURED = "noise_floor_captured"
        private const val KEY_NOISE_SUBTRACTION_ACTIVE = "noise_subtraction_active"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    // Pre-allocated per-band calibration correction offsets
    private val micOffsets31 = FloatArray(Iso31Bands.NUM_BANDS) { 0.0f }

    // Noise floor capture accumulator
    private var isCapturingNoise = false
    private var noiseCaptureStartMs = 0L
    private var noiseCaptureDurationMs = 2000L
    private var noiseAccumFrames = 0
    private val noiseAccumPower = DoubleArray(Iso31Bands.NUM_BANDS) { 0.0 }

    init {
        loadPersistedState()
    }

    private fun loadPersistedState() {
        try {
            // 1. Load Mic Calibration Curve
            val micName = prefs.getString(KEY_MIC_CURVE_NAME, null)
            val micPointsJson = prefs.getString(KEY_MIC_CURVE_POINTS, null)
            val micActive = prefs.getBoolean(KEY_MIC_CURVE_ACTIVE, false)

            var loadedMicCurve: MicCalibrationCurve? = null
            if (micName != null && micPointsJson != null) {
                val jsonArr = JSONArray(micPointsJson)
                val points = mutableListOf<MicCalibrationPoint>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    points.add(
                        MicCalibrationPoint(
                            freqHz = obj.getDouble("f").toFloat(),
                            dbOffset = obj.getDouble("o").toFloat()
                        )
                    )
                }
                if (points.isNotEmpty()) {
                    loadedMicCurve = MicCalibrationCurve(micName, points.sortedBy { it.freqHz })
                }
            }

            // 2. Load SPL Calibration
            val isSplMode = prefs.getBoolean(KEY_SPL_MODE, false)
            val splOffset = prefs.getFloat(KEY_SPL_OFFSET_DB, 100.0f)

            // 3. Load Target Curve
            val targetId = prefs.getString(KEY_TARGET_CURVE_ID, TargetCurve.HARMAN_CAR.id)
            val targetActive = prefs.getBoolean(KEY_TARGET_CURVE_ACTIVE, false)
            val loadedTarget = TargetCurve.PRESETS.find { it.id == targetId } ?: TargetCurve.HARMAN_CAR

            // 4. Load Noise Floor Snapshot
            val noiseBandsStr = prefs.getString(KEY_NOISE_FLOOR_BANDS, null)
            val noiseTimestamp = prefs.getLong(KEY_NOISE_FLOOR_TIMESTAMP, 0L)
            val noiseCaptured = prefs.getBoolean(KEY_NOISE_FLOOR_CAPTURED, false)
            val noiseSubtractionActive = prefs.getBoolean(KEY_NOISE_SUBTRACTION_ACTIVE, false)

            val noiseBands = FloatArray(Iso31Bands.NUM_BANDS) { -100.0f }
            if (noiseBandsStr != null) {
                val parts = noiseBandsStr.split(",")
                for (i in 0 until minOf(parts.size, Iso31Bands.NUM_BANDS)) {
                    noiseBands[i] = parts[i].toFloatOrNull() ?: -100.0f
                }
            }

            val snapshot = NoiseFloorSnapshot(
                bands31Db = noiseBands,
                captureTimestampMs = noiseTimestamp,
                isCaptured = noiseCaptured
            )

            // Update state
            val statusText = if (micActive && loadedMicCurve != null) {
                "Calib.: Active (${loadedMicCurve.name})"
            } else {
                "Calib.: None"
            }

            _uiState.value = CalibrationUiState(
                micCurve = loadedMicCurve,
                isMicCurveActive = micActive,
                micCurveStatusText = statusText,
                splCalibration = SplCalibration(isSplMode, splOffset),
                targetCurve = loadedTarget,
                isTargetCurveActive = targetActive,
                noiseFloor = snapshot,
                isNoiseSubtractionActive = noiseSubtractionActive
            )

            recomputeMicOffsets()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading calibration preferences", e)
        }
    }

    private fun recomputeMicOffsets() {
        val curve = _uiState.value.micCurve
        val active = _uiState.value.isMicCurveActive
        for (i in 0 until Iso31Bands.NUM_BANDS) {
            micOffsets31[i] = if (active && curve != null) {
                curve.getOffsetAt(Iso31Bands.CENTER_FREQUENCIES[i])
            } else {
                0.0f
            }
        }
    }

    /**
     * Applies mic curve corrections, noise subtraction, target deltas, and SPL offset to a live frame.
     * Operates zero-allocation inside the real-time capture stream.
     */
    fun processFrame(
        raw31Bands: FloatArray,
        rawFftDb: FloatArray,
        binResolutionHz: Float
    ): CalibratedSpectralFrame {
        val state = _uiState.value
        val numBands = Iso31Bands.NUM_BANDS

        val out31 = FloatArray(numBands)
        val unreliable = BooleanArray(numBands) { false }

        // Check if noise floor capture is in progress
        if (isCapturingNoise) {
            val now = System.currentTimeMillis()
            val elapsed = now - noiseCaptureStartMs
            val progress = (elapsed.toFloat() / noiseCaptureDurationMs).coerceIn(0.0f, 1.0f)

            // Accumulate energy in linear power domain
            noiseAccumFrames++
            for (i in 0 until numBands) {
                val db = raw31Bands[i]
                noiseAccumPower[i] += 10.0.pow(db / 10.0)
            }

            if (elapsed >= noiseCaptureDurationMs && noiseAccumFrames > 0) {
                finalizeNoiseCapture()
            } else {
                _uiState.value = _uiState.value.copy(
                    isCapturingNoiseFloor = true,
                    noiseCaptureProgress = progress
                )
            }
        }

        val noiseSnapshot = state.noiseFloor
        val isSubtractingNoise = state.isNoiseSubtractionActive && noiseSnapshot.isCaptured
        val rawSplOffset = if (state.splCalibration.isSplMode) state.splCalibration.splOffsetDb else 0.0f
        val splOffset = if (rawSplOffset.isNaN() || rawSplOffset.isInfinite()) 0.0f else rawSplOffset

        for (i in 0 until numBands) {
            val rawVal = if (i < raw31Bands.size) raw31Bands[i] else AudioMath.MIN_DB_FS
            var valDb = if (rawVal.isNaN() || rawVal.isInfinite()) AudioMath.MIN_DB_FS else rawVal

            // 1. Apply Mic Calibration offset
            val micOffset = if (i < micOffsets31.size) micOffsets31[i] else 0.0f
            valDb += if (micOffset.isNaN() || micOffset.isInfinite()) 0.0f else micOffset

            // 2. Apply Power-Domain Noise Subtraction
            if (isSubtractingNoise) {
                val noiseDb = if (i < noiseSnapshot.bands31Db.size) noiseSnapshot.bands31Db[i] else AudioMath.MIN_DB_FS
                val safeNoiseDb = if (noiseDb.isNaN() || noiseDb.isInfinite()) AudioMath.MIN_DB_FS else noiseDb
                val subResult = AudioMath.subtractNoiseFloor(valDb, safeNoiseDb, AudioMath.MIN_DB_FS)
                valDb = subResult.correctedDb
                unreliable[i] = subResult.isUnreliable
            }

            // 3. Apply SPL offset if in SPL mode
            valDb += splOffset
            if (valDb.isNaN() || valDb.isInfinite()) {
                valDb = AudioMath.MIN_DB_FS + splOffset
            }
            out31[i] = valDb
        }

        // 4. Calibrate Raw FFT Curve
        val outRawFft = FloatArray(rawFftDb.size)
        val micCurve = if (state.isMicCurveActive) state.micCurve else null
        for (k in rawFftDb.indices) {
            val rawFft = rawFftDb[k]
            var rawVal = if (rawFft.isNaN() || rawFft.isInfinite()) AudioMath.MIN_DB_FS else rawFft
            if (micCurve != null) {
                val f = k * binResolutionHz
                val mOffset = micCurve.getOffsetAt(f)
                rawVal += if (mOffset.isNaN() || mOffset.isInfinite()) 0.0f else mOffset
            }
            rawVal += splOffset
            if (rawVal.isNaN() || rawVal.isInfinite()) {
                rawVal = AudioMath.MIN_DB_FS + splOffset
            }
            outRawFft[k] = rawVal
        }

        // 5. Compute Target Deltas (measured - target) if target curve is active
        // Target curves are relative response shapes positioned at nominal reference baseline (-20 dBFS or 85 dBSPL)
        var targetDeltas: FloatArray? = null
        if (state.isTargetCurveActive && state.targetCurve != null) {
            targetDeltas = FloatArray(numBands)
            val targetBaselineDb = if (state.splCalibration.isSplMode) {
                -20.0f + splOffset
            } else {
                -20.0f
            }
            for (i in 0 until numBands) {
                val centerF = Iso31Bands.CENTER_FREQUENCIES[i]
                val curveVal = state.targetCurve.getTargetDbAt(centerF)
                val safeCurveVal = if (curveVal.isNaN() || curveVal.isInfinite()) 0.0f else curveVal
                val targetVal = targetBaselineDb + safeCurveVal
                val delta = out31[i] - targetVal
                targetDeltas[i] = if (delta.isNaN() || delta.isInfinite()) 0.0f else delta
            }
        }

        return CalibratedSpectralFrame(
            calibrated31Db = out31,
            calibratedRawFftDb = outRawFft,
            unreliableBands = unreliable,
            targetDeltas31Db = targetDeltas,
            isSplMode = state.splCalibration.isSplMode,
            splOffsetDb = state.splCalibration.splOffsetDb
        )
    }

    // --- Mic Calibration Curve Actions ---

    fun loadMicCurve(name: String, textContent: String): Result<MicCalibrationCurve> {
        val parseResult = CalibrationCurveParser.parse(name, textContent)
        if (parseResult.isSuccess) {
            val curve = parseResult.getOrThrow()
            val pointsArr = JSONArray()
            for (p in curve.points) {
                val obj = JSONObject()
                obj.put("f", p.freqHz.toDouble())
                obj.put("o", p.dbOffset.toDouble())
                pointsArr.put(obj)
            }

            prefs.edit()
                .putString(KEY_MIC_CURVE_NAME, curve.name)
                .putString(KEY_MIC_CURVE_POINTS, pointsArr.toString())
                .putBoolean(KEY_MIC_CURVE_ACTIVE, true)
                .apply()

            _uiState.value = _uiState.value.copy(
                micCurve = curve,
                isMicCurveActive = true,
                micCurveStatusText = "Calib.: Active (${curve.name})"
            )
            recomputeMicOffsets()
        }
        return parseResult
    }

    fun setMicCurveActive(active: Boolean) {
        val curve = _uiState.value.micCurve
        prefs.edit().putBoolean(KEY_MIC_CURVE_ACTIVE, active).apply()
        val statusText = if (active && curve != null) {
            "Calib.: Active (${curve.name})"
        } else {
            "Calib.: None"
        }
        _uiState.value = _uiState.value.copy(
            isMicCurveActive = active,
            micCurveStatusText = statusText
        )
        recomputeMicOffsets()
    }

    fun clearMicCurve() {
        prefs.edit()
            .remove(KEY_MIC_CURVE_NAME)
            .remove(KEY_MIC_CURVE_POINTS)
            .putBoolean(KEY_MIC_CURVE_ACTIVE, false)
            .apply()

        _uiState.value = _uiState.value.copy(
            micCurve = null,
            isMicCurveActive = false,
            micCurveStatusText = "Calib.: None"
        )
        recomputeMicOffsets()
    }

    // --- dB SPL Reference Calibration Actions ---

    fun calibrateSplReference(measuredRmsDbFs: Float, knownSplDb: Float) {
        // Real SPL meters measure RMS acoustic power (e.g. Fast/Slow time-weighting Leq).
        // Using RMS instead of Peak eliminates systematic errors caused by signal crest factors.
        if (measuredRmsDbFs.isNaN() || measuredRmsDbFs.isInfinite() ||
            knownSplDb.isNaN() || knownSplDb.isInfinite()) {
            Log.w(TAG, "Rejecting invalid SPL calibration inputs: rms=$measuredRmsDbFs, knownSpl=$knownSplDb")
            return
        }

        // Clamp to sane acoustic ranges (target 40-140 dBSPL, measured -100 to 0 dBFS)
        val safeSpl = knownSplDb.coerceIn(40.0f, 140.0f)
        val safeRms = measuredRmsDbFs.coerceIn(AudioMath.MIN_DB_FS, 0.0f)
        val offset = safeSpl - safeRms
        if (offset.isNaN() || offset.isInfinite()) {
            Log.w(TAG, "Computed invalid SPL offset: $offset")
            return
        }

        prefs.edit()
            .putFloat(KEY_SPL_OFFSET_DB, offset)
            .putBoolean(KEY_SPL_MODE, true)
            .apply()

        _uiState.value = _uiState.value.copy(
            splCalibration = SplCalibration(isSplMode = true, splOffsetDb = offset)
        )
    }

    fun setSplMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPL_MODE, enabled).apply()
        _uiState.value = _uiState.value.copy(
            splCalibration = _uiState.value.splCalibration.copy(isSplMode = enabled)
        )
    }

    fun setSplOffset(offsetDb: Float) {
        if (offsetDb.isNaN() || offsetDb.isInfinite()) return
        prefs.edit().putFloat(KEY_SPL_OFFSET_DB, offsetDb).apply()
        _uiState.value = _uiState.value.copy(
            splCalibration = _uiState.value.splCalibration.copy(splOffsetDb = offsetDb)
        )
    }

    // --- Target Curve Actions ---

    fun setTargetCurve(curve: TargetCurve?) {
        prefs.edit()
            .putString(KEY_TARGET_CURVE_ID, curve?.id ?: "")
            .apply()

        _uiState.value = _uiState.value.copy(targetCurve = curve)
    }

    fun setTargetCurveActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_TARGET_CURVE_ACTIVE, active).apply()
        _uiState.value = _uiState.value.copy(isTargetCurveActive = active)
    }

    // --- Noise Floor Capture & Subtraction Actions ---

    fun startNoiseFloorCapture(durationMs: Long = 2000L) {
        noiseCaptureDurationMs = durationMs
        noiseCaptureStartMs = System.currentTimeMillis()
        noiseAccumFrames = 0
        noiseAccumPower.fill(0.0)
        isCapturingNoise = true

        _uiState.value = _uiState.value.copy(
            isCapturingNoiseFloor = true,
            noiseCaptureProgress = 0.0f
        )
    }

    private fun finalizeNoiseCapture() {
        isCapturingNoise = false
        val numBands = Iso31Bands.NUM_BANDS
        val capturedBands = FloatArray(numBands)
        val frames = if (noiseAccumFrames > 0) noiseAccumFrames else 1

        for (i in 0 until numBands) {
            val avgPower = noiseAccumPower[i] / frames
            capturedBands[i] = if (avgPower > 1e-12 && !avgPower.isNaN() && !avgPower.isInfinite()) {
                val db = (10.0 * log10(avgPower)).toFloat()
                if (db.isNaN() || db.isInfinite()) AudioMath.MIN_DB_FS else db
            } else {
                AudioMath.MIN_DB_FS
            }
        }

        val timestamp = System.currentTimeMillis()
        val snapshot = NoiseFloorSnapshot(
            bands31Db = capturedBands,
            captureTimestampMs = timestamp,
            isCaptured = true
        )

        // Persist noise snapshot
        val bandsStr = capturedBands.joinToString(",") { it.toString() }
        prefs.edit()
            .putString(KEY_NOISE_FLOOR_BANDS, bandsStr)
            .putLong(KEY_NOISE_FLOOR_TIMESTAMP, timestamp)
            .putBoolean(KEY_NOISE_FLOOR_CAPTURED, true)
            .putBoolean(KEY_NOISE_SUBTRACTION_ACTIVE, true)
            .apply()

        _uiState.value = _uiState.value.copy(
            noiseFloor = snapshot,
            isNoiseSubtractionActive = true,
            isCapturingNoiseFloor = false,
            noiseCaptureProgress = 1.0f
        )
    }

    fun setNoiseSubtractionActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_NOISE_SUBTRACTION_ACTIVE, active).apply()
        _uiState.value = _uiState.value.copy(isNoiseSubtractionActive = active)
    }

    fun clearNoiseFloor() {
        prefs.edit()
            .remove(KEY_NOISE_FLOOR_BANDS)
            .remove(KEY_NOISE_FLOOR_TIMESTAMP)
            .putBoolean(KEY_NOISE_FLOOR_CAPTURED, false)
            .putBoolean(KEY_NOISE_SUBTRACTION_ACTIVE, false)
            .apply()

        _uiState.value = _uiState.value.copy(
            noiseFloor = NoiseFloorSnapshot(),
            isNoiseSubtractionActive = false
        )
    }
}
