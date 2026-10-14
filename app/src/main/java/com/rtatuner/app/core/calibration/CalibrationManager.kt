package com.rtatuner.app.core.calibration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.audio.CaptureAuthoritySnapshot
import com.rtatuner.app.core.audio.CaptureFingerprint
import com.rtatuner.app.core.audio.CapturedFrameContext
import com.rtatuner.app.core.audio.NoiseFloorCompatibility
import com.rtatuner.app.core.dsp.DspResponseRevision
import com.rtatuner.app.core.dsp.Iso31Bands
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.core.entitlement.ProFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
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
    val splOffsetDb: Float,
    val shouldResetSmoothing: Boolean = false
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
        if (shouldResetSmoothing != other.shouldResetSmoothing) return false
        return true
    }

    override fun hashCode(): Int {
        var result = calibrated31Db.contentHashCode()
        result = 31 * result + calibratedRawFftDb.contentHashCode()
        result = 31 * result + unreliableBands.contentHashCode()
        result = 31 * result + (targetDeltas31Db?.contentHashCode() ?: 0)
        result = 31 * result + isSplMode.hashCode()
        result = 31 * result + splOffsetDb.hashCode()
        result = 31 * result + shouldResetSmoothing.hashCode()
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
    val isNoiseSubtractionEffective: Boolean = false,
    val isCapturingNoiseFloor: Boolean = false,
    val noiseCaptureProgress: Float = 0.0f,
    val noiseFloorCompatibility: NoiseFloorCompatibility = NoiseFloorCompatibility.Uncaptured
)

/**
 * Orchestrates mic calibration curves, dB SPL reference calibration, target curve overlays,
 * and power-domain noise floor capture & subtraction with persistence.
 */
class CalibrationManager internal constructor(
    private val prefs: SharedPreferences,
    private val featurePolicy: FeatureAccessPolicy = FeatureAccessPolicy(),
    scope: CoroutineScope? = null,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    constructor(
        context: Context,
        featurePolicy: FeatureAccessPolicy = FeatureAccessPolicy(),
        scope: CoroutineScope? = null
    ) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        featurePolicy,
        scope
    )

    companion object {
        private const val TAG = "CalibrationManager"
        private const val PREFS_NAME = "rta_calibration_prefs"

        private const val KEY_MIC_CURVE_NAME = "mic_curve_name"
        private const val KEY_MIC_CURVE_POINTS = "mic_curve_points"
        private const val KEY_MIC_CURVE_ACTIVE = "mic_curve_active"
        private const val KEY_MIC_CURVE_CONVENTION = "mic_curve_convention"

        private const val KEY_SPL_MODE = "spl_mode"
        private const val KEY_SPL_OFFSET_DB = "spl_offset_db"

        private const val KEY_TARGET_CURVE_ID = "target_curve_id"
        private const val KEY_TARGET_CURVE_ACTIVE = "target_curve_active"

        private const val KEY_NOISE_FLOOR_BANDS = "noise_floor_bands"
        private const val KEY_NOISE_FLOOR_TIMESTAMP = "noise_floor_timestamp"
        private const val KEY_NOISE_FLOOR_CAPTURED = "noise_floor_captured"
        private const val KEY_NOISE_SUBTRACTION_ACTIVE = "noise_subtraction_active"
        private const val KEY_NOISE_FLOOR_FINGERPRINT = "noise_floor_fingerprint"
    }

    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    // Requested preferences survive entitlement changes. Only effective state reaches DSP.
    private var requestedState = CalibrationUiState()
    private val stateLock = Any()
    private val ownedScope = if (scope == null) CoroutineScope(SupervisorJob() + Dispatchers.Default) else null

    /** Arrays are owned by this snapshot and never mutated after publication. */
    private data class EffectiveFrameState(
        val calibration: CalibrationUiState,
        val micOffsets31: FloatArray
    )

    @Volatile
    private var effectiveFrame = EffectiveFrameState(CalibrationUiState(), FloatArray(Iso31Bands.NUM_BANDS))

    // Noise floor capture accumulator
    @Volatile
    private var isCapturingNoise = false
    private var noiseCaptureStartMs = 0L
    private var noiseCaptureDurationMs = 2000L
    private var noiseAccumFrames = 0
    private val noiseAccumPower = DoubleArray(Iso31Bands.NUM_BANDS) { 0.0 }
    private var activeNoiseCaptureManagerId: String? = null
    private var activeNoiseCaptureGeneration: Long? = null
    private var activeNoiseCaptureEpoch: Long? = null
    private var activeNoiseCaptureFingerprint: CaptureFingerprint? = null
    private var lastAccumulatedFrameSequence: Long = -1L
    @Volatile
    private var currentAuthority: CaptureAuthoritySnapshot? = null
    @Volatile
    private var liveCaptureFingerprint: CaptureFingerprint? = null
    private val shouldResetSmoothing = AtomicBoolean(false)

    init {
        loadPersistedState()
    }

    private val entitlementJob = (scope ?: requireNotNull(ownedScope)).launch(start = CoroutineStart.UNDISPATCHED) {
        featurePolicy.entitlements.state.collect {
            synchronized(stateLock) {
                if (featurePolicy.check(ProFeature.NOISE_FLOOR) is FeatureAccessResult.Denied) {
                    cancelNoiseCaptureLocked()
                }
                publishEffectiveStateLocked()
                shouldResetSmoothing.set(true)
            }
        }
    }

    fun updateCaptureAuthority(authority: CaptureAuthoritySnapshot) {
        synchronized(stateLock) {
            currentAuthority = authority

            if (isCapturingNoise) {
                val shouldCancel = !authority.isRunning ||
                    !authority.isValidRunningAuthority ||
                    (activeNoiseCaptureManagerId != null && authority.managerId != activeNoiseCaptureManagerId) ||
                    (activeNoiseCaptureGeneration != null && authority.captureGeneration != activeNoiseCaptureGeneration) ||
                    (activeNoiseCaptureEpoch != null && authority.routeEpoch != activeNoiseCaptureEpoch) ||
                    (activeNoiseCaptureFingerprint != null && authority.fingerprint != activeNoiseCaptureFingerprint)

                if (shouldCancel) {
                    cancelNoiseCaptureLocked()
                }
            }

            publishEffectiveStateLocked()
            shouldResetSmoothing.set(true)
        }
    }

    fun cancelNoiseCapture() {
        synchronized(stateLock) {
            cancelNoiseCaptureLocked()
            publishEffectiveStateLocked()
        }
    }

    fun close() {
        entitlementJob.cancel()
        ownedScope?.cancel()
        synchronized(stateLock) { cancelNoiseCaptureLocked() }
    }

    private fun loadPersistedState() {
        try {
            // 1. Load Mic Calibration Curve
            val micName = prefs.getString(KEY_MIC_CURVE_NAME, null)
            val micPointsJson = prefs.getString(KEY_MIC_CURVE_POINTS, null)
            val micActive = prefs.getBoolean(KEY_MIC_CURVE_ACTIVE, false)
            val conventionStr = prefs.getString(KEY_MIC_CURVE_CONVENTION, null)

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
                    // Legacy stored curves did not have KEY_MIC_CURVE_CONVENTION; their "o" values were already stored
                    // directly as additive correction offsets. Preserving them as ADDITIVE_CORRECTION prevents double negation.
                    val convention = if (conventionStr != null) {
                        CalibrationFileConvention.fromId(conventionStr)
                    } else {
                        CalibrationFileConvention.ADDITIVE_CORRECTION
                    }
                    loadedMicCurve = MicCalibrationCurve(
                        name = micName,
                        points = points.sortedBy { it.freqHz },
                        convention = convention
                    )
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
            val noiseFingerprintJson = prefs.getString(KEY_NOISE_FLOOR_FINGERPRINT, null)

            val noiseBands = FloatArray(Iso31Bands.NUM_BANDS) { -100.0f }
            if (noiseBandsStr != null) {
                val parts = noiseBandsStr.split(",")
                for (i in 0 until minOf(parts.size, Iso31Bands.NUM_BANDS)) {
                    noiseBands[i] = parts[i].toFloatOrNull() ?: -100.0f
                }
            }

            val loadedFingerprint = CaptureFingerprint.fromJsonString(noiseFingerprintJson)
            val isLegacy = noiseCaptured && loadedFingerprint == null
            val requiresRecapture = isLegacy || (noiseCaptured && loadedFingerprint?.isMicrophoneKnown != true)
            val compatibilityReason = when {
                isLegacy -> "Legacy noise floor capture is incompatible with corrected DSP. Recapture required."
                noiseCaptured && loadedFingerprint?.isMicrophoneKnown != true -> "Captured microphone route was unknown. Recapture required."
                else -> null
            }

            val snapshot = NoiseFloorSnapshot(
                bands31Db = noiseBands,
                captureTimestampMs = noiseTimestamp,
                isCaptured = noiseCaptured,
                fingerprint = loadedFingerprint,
                requiresRecapture = requiresRecapture,
                compatibilityReason = compatibilityReason
            )

            // Update state
            val statusText = if (micActive && loadedMicCurve != null) {
                "Calib.: Active (${loadedMicCurve.name})"
            } else {
                "Calib.: None"
            }

            requestedState = CalibrationUiState(
                micCurve = loadedMicCurve,
                isMicCurveActive = micActive,
                micCurveStatusText = statusText,
                splCalibration = SplCalibration(isSplMode, splOffset),
                targetCurve = loadedTarget,
                isTargetCurveActive = targetActive,
                noiseFloor = snapshot,
                isNoiseSubtractionActive = noiseSubtractionActive
            )

            publishEffectiveStateLocked()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading calibration preferences", e)
        }
    }

    private fun publishEffectiveStateLocked() {
        val effective = effectiveCalibrationState(requestedState, featurePolicy, currentAuthority)
        val curve = effective.micCurve?.copy(points = effective.micCurve.points.toList())
        val processingState = effective.copy(
            micCurve = curve,
            noiseFloor = effective.noiseFloor.copy(bands31Db = effective.noiseFloor.bands31Db.copyOf()),
            targetCurve = effective.targetCurve?.let { it.copy(points = it.points.toList()) }
        )
        val offsets = FloatArray(Iso31Bands.NUM_BANDS) { i ->
            if (effective.isMicCurveActive && curve != null) curve.getOffsetAt(Iso31Bands.CENTER_FREQUENCIES[i]) else 0.0f
        }
        effectiveFrame = EffectiveFrameState(processingState, offsets)
        _uiState.value = effective.copy(
            micCurve = effective.micCurve?.let { it.copy(points = it.points.toList()) },
            noiseFloor = effective.noiseFloor.copy(bands31Db = effective.noiseFloor.bands31Db.copyOf()),
            targetCurve = effective.targetCurve?.let { it.copy(points = it.points.toList()) }
        )
    }

    /**
     * Applies mic curve corrections, noise subtraction, target deltas, and SPL offset to a live frame.
     * Reads one atomically published effective snapshot; there are no per-bin entitlement checks.
     */
    fun processFrame(
        raw31Bands: FloatArray,
        rawFftDb: FloatArray,
        binResolutionHz: Float,
        frameContext: CapturedFrameContext? = null
    ): CalibratedSpectralFrame {
        if (frameContext != null) {
            liveCaptureFingerprint = frameContext.fingerprint
        }

        val numBands = Iso31Bands.NUM_BANDS

        val out31 = FloatArray(numBands)
        val unreliable = BooleanArray(numBands) { false }

        // Check if noise floor capture is in progress
        if (isCapturingNoise) synchronized(stateLock) {
            if (isCapturingNoise) {
                var canAccumulate = true
                if (frameContext == null || !frameContext.isRunning) {
                    cancelNoiseCaptureLocked()
                    canAccumulate = false
                } else {
                    if (frameContext.managerId != activeNoiseCaptureManagerId ||
                        frameContext.captureGeneration != activeNoiseCaptureGeneration ||
                        (activeNoiseCaptureEpoch != null && frameContext.routeEpoch != activeNoiseCaptureEpoch) ||
                        frameContext.fingerprint != activeNoiseCaptureFingerprint ||
                        !frameContext.fingerprint.isMicrophoneKnown
                    ) {
                        cancelNoiseCaptureLocked()
                        canAccumulate = false
                    } else if (frameContext.frameSequence <= lastAccumulatedFrameSequence) {
                        canAccumulate = false
                    }
                }

                if (!isCapturingNoise) {
                    return@synchronized
                }

                if (canAccumulate && frameContext != null) {
                    lastAccumulatedFrameSequence = frameContext.frameSequence
                    noiseAccumFrames++
                    for (i in 0 until numBands) {
                        val db = raw31Bands[i]
                        noiseAccumPower[i] += 10.0.pow(db / 10.0)
                    }

                    val now = nowMs()
                    val elapsed = now - noiseCaptureStartMs
                    val progress = (elapsed.toFloat() / noiseCaptureDurationMs).coerceIn(0.0f, 1.0f)

                    if (elapsed >= noiseCaptureDurationMs && noiseAccumFrames > 0 && activeNoiseCaptureFingerprint != null) {
                        finalizeNoiseCaptureLocked(activeNoiseCaptureFingerprint!!)
                    } else if (isCapturingNoise) {
                        requestedState = requestedState.copy(
                            isCapturingNoiseFloor = true,
                            noiseCaptureProgress = progress
                        )
                        _uiState.value = _uiState.value.copy(isCapturingNoiseFloor = true, noiseCaptureProgress = progress)
                    }
                }
            }
        }

        // Read state AFTER any potential finalizeNoiseCaptureLocked() call to ensure consistency in this frame
        val frameState = effectiveFrame
        val state = frameState.calibration
        val micOffsets31 = frameState.micOffsets31
        val noiseSnapshot = state.noiseFloor

        // One current live compatibility decision:
        val liveAuthority = currentAuthority
        val isSubtractingNoise: Boolean
        if (liveAuthority != null) {
            val matchesAuthority = frameContext != null &&
                frameContext.isRunning &&
                frameContext.managerId == liveAuthority.managerId &&
                frameContext.captureGeneration == liveAuthority.captureGeneration &&
                frameContext.routeEpoch == liveAuthority.routeEpoch

            isSubtractingNoise = !isCapturingNoise && state.isNoiseSubtractionEffective && matchesAuthority
        } else {
            val fallbackFp = if (frameContext != null && frameContext.isRunning && frameContext.fingerprint.isMicrophoneKnown) {
                frameContext.fingerprint
            } else {
                null
            }
            val fallbackCompatibility = if (!noiseSnapshot.isCaptured) {
                NoiseFloorCompatibility.Uncaptured
            } else if (fallbackFp == null) {
                NoiseFloorCompatibility.Incompatible("Live audio capture context is stopped or inactive.", requiresRecapture = false)
            } else {
                evaluateNoiseFloorCompatibility(noiseSnapshot, fallbackFp)
            }
            val isCompatible = fallbackCompatibility is NoiseFloorCompatibility.Compatible
            isSubtractingNoise = !isCapturingNoise &&
                state.isNoiseSubtractionActive &&
                noiseSnapshot.isCaptured &&
                !noiseSnapshot.requiresRecapture &&
                isCompatible

            if (_uiState.value.noiseFloorCompatibility != fallbackCompatibility ||
                _uiState.value.isNoiseSubtractionEffective != isSubtractingNoise) {
                _uiState.value = _uiState.value.copy(
                    noiseFloorCompatibility = fallbackCompatibility,
                    isNoiseSubtractionEffective = isSubtractingNoise
                )
            }
        }

        val rawSplOffset = if (state.splCalibration.isSplMode) state.splCalibration.splOffsetDb else 0.0f
        val splOffset = if (rawSplOffset.isNaN() || rawSplOffset.isInfinite()) 0.0f else rawSplOffset

        for (i in 0 until numBands) {
            val rawVal = if (i < raw31Bands.size) raw31Bands[i] else AudioMath.MIN_DB_FS
            var valDb = if (rawVal.isNaN() || rawVal.isInfinite()) AudioMath.MIN_DB_FS else rawVal

            // 1. Power-Domain Noise Subtraction (operating purely in raw power domain)
            if (isSubtractingNoise) {
                val noiseDb = if (i < noiseSnapshot.bands31Db.size) noiseSnapshot.bands31Db[i] else AudioMath.MIN_DB_FS
                val safeNoiseDb = if (noiseDb.isNaN() || noiseDb.isInfinite()) AudioMath.MIN_DB_FS else noiseDb
                val subResult = AudioMath.subtractNoiseFloor(valDb, safeNoiseDb, AudioMath.MIN_DB_FS)
                valDb = subResult.correctedDb
                unreliable[i] = subResult.isUnreliable
            }

            // 2. Apply Mic Calibration offset to cleaned acoustic signal
            if (!isSubtractingNoise || valDb > AudioMath.MIN_DB_FS) {
                val micOffset = if (i < micOffsets31.size) micOffsets31[i] else 0.0f
                val safeMicOffset = if (micOffset.isNaN() || micOffset.isInfinite()) 0.0f else micOffset
                valDb += safeMicOffset
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

        val reset = shouldResetSmoothing.getAndSet(false)

        return CalibratedSpectralFrame(
            calibrated31Db = out31,
            calibratedRawFftDb = outRawFft,
            unreliableBands = unreliable,
            targetDeltas31Db = targetDeltas,
            isSplMode = state.splCalibration.isSplMode,
            splOffsetDb = state.splCalibration.splOffsetDb,
            shouldResetSmoothing = reset
        )
    }

    // --- Mic Calibration Curve Actions ---

    fun loadMicCurve(
        name: String,
        textContent: String,
        convention: CalibrationFileConvention = CalibrationFileConvention.MEASURED_RESPONSE
    ): Result<MicCalibrationCurve> = synchronized(stateLock) {
        val authorization = runCatching { featurePolicy.requireAccess(ProFeature.MIC_CALIBRATION) }
        if (authorization.isFailure) return@synchronized Result.failure(authorization.exceptionOrNull()!!)
        val parseResult = CalibrationCurveParser.parse(name, textContent, convention)
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
                .putString(KEY_MIC_CURVE_CONVENTION, convention.id)
                .putBoolean(KEY_MIC_CURVE_ACTIVE, true)
                .apply()

            requestedState = requestedState.copy(
                micCurve = curve,
                isMicCurveActive = true,
                micCurveStatusText = "Calib.: Active (${curve.name})"
            )
            publishEffectiveStateLocked()
        }
        parseResult
    }

    fun setMicCurveActive(active: Boolean): FeatureAccessResult = mutateWithAccess(ProFeature.MIC_CALIBRATION) {
        val curve = requestedState.micCurve
        prefs.edit().putBoolean(KEY_MIC_CURVE_ACTIVE, active).apply()
        val statusText = if (active && curve != null) {
            "Calib.: Active (${curve.name})"
        } else {
            "Calib.: None"
        }
        requestedState = requestedState.copy(
            isMicCurveActive = active,
            micCurveStatusText = statusText
        )
    }

    fun clearMicCurve(): FeatureAccessResult = mutateWithAccess(ProFeature.MIC_CALIBRATION) {
        prefs.edit()
            .remove(KEY_MIC_CURVE_NAME)
            .remove(KEY_MIC_CURVE_POINTS)
            .remove(KEY_MIC_CURVE_CONVENTION)
            .putBoolean(KEY_MIC_CURVE_ACTIVE, false)
            .apply()

        requestedState = requestedState.copy(
            micCurve = null,
            isMicCurveActive = false,
            micCurveStatusText = "Calib.: None"
        )
    }

    // --- dB SPL Reference Calibration Actions ---

    fun calibrateSplReference(measuredRmsDbFs: Float, knownSplDb: Float): FeatureAccessResult = mutateWithAccess(ProFeature.SPL_CALIBRATION) {
        // Real SPL meters measure RMS acoustic power (e.g. Fast/Slow time-weighting Leq).
        // Using RMS instead of Peak eliminates systematic errors caused by signal crest factors.
        if (measuredRmsDbFs.isNaN() || measuredRmsDbFs.isInfinite() ||
            knownSplDb.isNaN() || knownSplDb.isInfinite()) {
            Log.w(TAG, "Rejecting invalid SPL calibration inputs: rms=$measuredRmsDbFs, knownSpl=$knownSplDb")
            return@mutateWithAccess
        }

        // Clamp to sane acoustic ranges (target 40-140 dBSPL, measured -100 to 0 dBFS)
        val safeSpl = knownSplDb.coerceIn(40.0f, 140.0f)
        val safeRms = measuredRmsDbFs.coerceIn(AudioMath.MIN_DB_FS, 0.0f)
        val offset = safeSpl - safeRms
        if (offset.isNaN() || offset.isInfinite()) {
            Log.w(TAG, "Computed invalid SPL offset: $offset")
            return@mutateWithAccess
        }

        prefs.edit()
            .putFloat(KEY_SPL_OFFSET_DB, offset)
            .putBoolean(KEY_SPL_MODE, true)
            .apply()

        requestedState = requestedState.copy(
            splCalibration = SplCalibration(isSplMode = true, splOffsetDb = offset)
        )
    }

    fun setSplMode(enabled: Boolean): FeatureAccessResult = mutateWithAccess(ProFeature.SPL_CALIBRATION) {
        prefs.edit().putBoolean(KEY_SPL_MODE, enabled).apply()
        requestedState = requestedState.copy(
            splCalibration = requestedState.splCalibration.copy(isSplMode = enabled)
        )
    }

    fun setSplOffset(offsetDb: Float): FeatureAccessResult = mutateWithAccess(ProFeature.SPL_CALIBRATION) {
        if (offsetDb.isNaN() || offsetDb.isInfinite()) return@mutateWithAccess
        prefs.edit().putFloat(KEY_SPL_OFFSET_DB, offsetDb).apply()
        requestedState = requestedState.copy(
            splCalibration = requestedState.splCalibration.copy(splOffsetDb = offsetDb)
        )
    }

    // --- Target Curve Actions ---

    fun setTargetCurve(curve: TargetCurve?): FeatureAccessResult = synchronized(stateLock) {
        if (curve != null && curve != TargetCurve.HARMAN_CAR) {
            val result = featurePolicy.check(ProFeature.ADVANCED_TARGETS)
            if (result is FeatureAccessResult.Denied) return@synchronized result
        }
        prefs.edit()
            .putString(KEY_TARGET_CURVE_ID, curve?.id ?: "")
            .apply()

        requestedState = requestedState.copy(targetCurve = curve)
        publishEffectiveStateLocked()
        FeatureAccessResult.Allowed
    }

    fun setTargetCurveActive(active: Boolean): FeatureAccessResult = synchronized(stateLock) {
        if (requestedState.targetCurve != null && requestedState.targetCurve != TargetCurve.HARMAN_CAR) {
            val result = featurePolicy.check(ProFeature.ADVANCED_TARGETS)
            if (result is FeatureAccessResult.Denied) return@synchronized result
        }
        prefs.edit().putBoolean(KEY_TARGET_CURVE_ACTIVE, active).apply()
        requestedState = requestedState.copy(isTargetCurveActive = active)
        publishEffectiveStateLocked()
        FeatureAccessResult.Allowed
    }

    // --- Noise Floor Capture & Subtraction Actions ---

    fun startNoiseFloorCapture(
        durationMs: Long = 2000L,
        authority: CaptureAuthoritySnapshot? = null
    ): FeatureAccessResult = synchronized(stateLock) {
        val access = featurePolicy.check(ProFeature.NOISE_FLOOR)
        if (access is FeatureAccessResult.Denied) return@synchronized access

        if (authority != null) {
            currentAuthority = authority
        }
        val currentAuth = currentAuthority
        if (currentAuth == null || !currentAuth.isValidRunningAuthority) {
            return@synchronized FeatureAccessResult.Denied(
                ProFeature.NOISE_FLOOR,
                "Active audio capture with a valid route is required to capture noise floor."
            )
        }

        noiseCaptureDurationMs = durationMs.coerceAtLeast(1L)
        noiseCaptureStartMs = nowMs()
        noiseAccumFrames = 0
        noiseAccumPower.fill(0.0)
        isCapturingNoise = true

        activeNoiseCaptureManagerId = currentAuth.managerId
        activeNoiseCaptureGeneration = currentAuth.captureGeneration
        activeNoiseCaptureEpoch = currentAuth.routeEpoch
        activeNoiseCaptureFingerprint = currentAuth.fingerprint
        lastAccumulatedFrameSequence = currentAuth.latestPublishedSequence
        shouldResetSmoothing.set(true)

        requestedState = requestedState.copy(
            isCapturingNoiseFloor = true,
            noiseCaptureProgress = 0.0f
        )
        publishEffectiveStateLocked()
        FeatureAccessResult.Allowed
    }

    private fun finalizeNoiseCaptureLocked(capturedFingerprint: CaptureFingerprint) {
        // Finalization is an operation boundary: revoke even if the observer has not run yet.
        if (featurePolicy.check(ProFeature.NOISE_FLOOR) is FeatureAccessResult.Denied) {
            cancelNoiseCaptureLocked()
            publishEffectiveStateLocked()
            return
        }
        if (noiseAccumFrames <= 0) {
            cancelNoiseCaptureLocked()
            publishEffectiveStateLocked()
            return
        }
        isCapturingNoise = false
        val numBands = Iso31Bands.NUM_BANDS
        val capturedBands = FloatArray(numBands)
        val frames = noiseAccumFrames

        for (i in 0 until numBands) {
            val avgPower = noiseAccumPower[i] / frames
            capturedBands[i] = if (avgPower > 1e-12 && !avgPower.isNaN() && !avgPower.isInfinite()) {
                val db = (10.0 * log10(avgPower)).toFloat()
                if (db.isNaN() || db.isInfinite()) AudioMath.MIN_DB_FS else db
            } else {
                AudioMath.MIN_DB_FS
            }
        }

        val timestamp = nowMs()
        val validatedFingerprint = capturedFingerprint
        val requiresRecapture = !validatedFingerprint.isMicrophoneKnown
        val snapshot = NoiseFloorSnapshot(
            bands31Db = capturedBands,
            captureTimestampMs = timestamp,
            isCaptured = true,
            fingerprint = validatedFingerprint,
            requiresRecapture = requiresRecapture,
            compatibilityReason = if (requiresRecapture) "Captured microphone route was unknown. Recapture required." else null
        )

        // Persist noise snapshot with complete validated fingerprint
        val bandsStr = capturedBands.joinToString(",") { it.toString() }
        prefs.edit()
            .putString(KEY_NOISE_FLOOR_BANDS, bandsStr)
            .putLong(KEY_NOISE_FLOOR_TIMESTAMP, timestamp)
            .putBoolean(KEY_NOISE_FLOOR_CAPTURED, true)
            .putBoolean(KEY_NOISE_SUBTRACTION_ACTIVE, true)
            .putString(KEY_NOISE_FLOOR_FINGERPRINT, validatedFingerprint.toJsonString())
            .apply()

        shouldResetSmoothing.set(true)
        requestedState = requestedState.copy(
            noiseFloor = snapshot,
            isNoiseSubtractionActive = true,
            isCapturingNoiseFloor = false,
            noiseCaptureProgress = 1.0f
        )
        publishEffectiveStateLocked()
    }

    fun setNoiseSubtractionActive(active: Boolean): FeatureAccessResult = mutateWithAccess(ProFeature.NOISE_FLOOR) {
        shouldResetSmoothing.set(true)
        prefs.edit().putBoolean(KEY_NOISE_SUBTRACTION_ACTIVE, active).apply()
        requestedState = requestedState.copy(isNoiseSubtractionActive = active)
    }

    fun clearNoiseFloor(): FeatureAccessResult = mutateWithAccess(ProFeature.NOISE_FLOOR) {
        shouldResetSmoothing.set(true)
        cancelNoiseCaptureLocked()
        prefs.edit()
            .remove(KEY_NOISE_FLOOR_BANDS)
            .remove(KEY_NOISE_FLOOR_TIMESTAMP)
            .remove(KEY_NOISE_FLOOR_FINGERPRINT)
            .putBoolean(KEY_NOISE_FLOOR_CAPTURED, false)
            .putBoolean(KEY_NOISE_SUBTRACTION_ACTIVE, false)
            .apply()

        requestedState = requestedState.copy(
            noiseFloor = NoiseFloorSnapshot(),
            isNoiseSubtractionActive = false
        )
    }

    private fun mutateWithAccess(feature: ProFeature, mutate: () -> Unit): FeatureAccessResult = synchronized(stateLock) {
        val access = featurePolicy.check(feature)
        if (access is FeatureAccessResult.Denied) return@synchronized access
        mutate()
        publishEffectiveStateLocked()
        FeatureAccessResult.Allowed
    }

    private fun cancelNoiseCaptureLocked() {
        if (!isCapturingNoise) return
        isCapturingNoise = false
        noiseAccumFrames = 0
        noiseAccumPower.fill(0.0)
        activeNoiseCaptureManagerId = null
        activeNoiseCaptureGeneration = null
        activeNoiseCaptureEpoch = null
        activeNoiseCaptureFingerprint = null
        lastAccumulatedFrameSequence = -1L
        requestedState = requestedState.copy(isCapturingNoiseFloor = false, noiseCaptureProgress = 0.0f)
        _uiState.value = _uiState.value.copy(isCapturingNoiseFloor = false, noiseCaptureProgress = 0.0f)
        shouldResetSmoothing.set(true)
    }
}
