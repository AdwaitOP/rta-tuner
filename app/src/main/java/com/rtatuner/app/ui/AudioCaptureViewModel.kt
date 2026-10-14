package com.rtatuner.app.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rtatuner.app.core.audio.AudioCaptureConfig
import com.rtatuner.app.core.audio.AudioCaptureManager
import com.rtatuner.app.core.audio.AudioCaptureUiState
import com.rtatuner.app.core.audio.AudioInputDeviceInfo
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.audio.CaptureStatus
import com.rtatuner.app.core.audio.FftDisplayMode
import com.rtatuner.app.core.audio.SignalGenerator
import com.rtatuner.app.core.audio.SweepRangePreset
import com.rtatuner.app.core.calibration.CalibrationFileConvention
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.calibration.MicCalibrationCurve
import com.rtatuner.app.core.calibration.TargetCurve
import com.rtatuner.app.core.database.RtaDatabase
import com.rtatuner.app.core.database.SessionRepository
import com.rtatuner.app.core.database.SessionSnapshotEntity
import com.rtatuner.app.core.database.SessionWithSnapshots
import com.rtatuner.app.core.database.TuningSessionEntity
import com.rtatuner.app.core.entitlement.AppEntitlements
import com.rtatuner.app.core.entitlement.EntitlementRepository
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FeatureAccessDeniedException
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.core.entitlement.ProFeature
import com.rtatuner.app.core.export.RtaExportManager
import com.rtatuner.app.core.export.CsvExportResult
import com.rtatuner.app.core.export.CsvShareException
import com.rtatuner.app.core.session.FrozenSnapshotStore
import com.rtatuner.app.core.session.RtaSnapshot
import com.rtatuner.app.core.session.SnapshotPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * ViewModel orchestrating audio capture lifecycle, signal generation, calibration, and UI state.
 */
class AudioCaptureViewModel(
    application: Application,
    private val entitlementRepository: EntitlementRepository
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, AppEntitlements.create())

    val entitlementState = entitlementRepository.state
    val featureAccessPolicy = FeatureAccessPolicy(entitlementRepository)
    private val _accessDenial = MutableStateFlow<FeatureAccessResult.Denied?>(null)
    val accessDenial = _accessDenial.asStateFlow()

    private val csvExportErrorChannel = Channel<String>(Channel.BUFFERED)
    val csvExportErrors = csvExportErrorChannel.receiveAsFlow()

    fun dismissAccessDenial() { _accessDenial.value = null }

    fun requestProUpgrade(feature: ProFeature = ProFeature.CSV_EXPORT) {
        _accessDenial.value = FeatureAccessResult.Denied(feature, feature.benefit)
    }

    fun simulateEntitlement(tier: EntitlementTier): Boolean =
        AppEntitlements.simulate(entitlementRepository, tier)

    private fun reportAccess(result: FeatureAccessResult): FeatureAccessResult {
        if (result is FeatureAccessResult.Denied) _accessDenial.value = result
        return result
    }

    companion object {
        private const val HOLD_DURATION_MS = 1200L
        private const val DECAY_RATE_DB_PER_SEC = 25.0f
        private const val BALLISTICS_TICK_MS = 33L // ~30 fps meter update
    }

    private val captureControlLock = Any()
    private val _fftWindowSize = MutableStateFlow(2048)
    val fftWindowSize: StateFlow<Int> = _fftWindowSize.asStateFlow()
    private val currentWindowSize: Int get() = _fftWindowSize.value
    private var preferredDeviceId: Int? = null
    private var captureManager: AudioCaptureManager = createCaptureManager(currentWindowSize)
    val signalGenerator: SignalGenerator = SignalGenerator(
        sampleRate = 48000, featurePolicy = featureAccessPolicy, scope = viewModelScope
    )
    val calibrationManager: CalibrationManager = CalibrationManager(
        application, featurePolicy = featureAccessPolicy, scope = viewModelScope
    )
    private val database = RtaDatabase.getInstance(application)
    val sessionRepository = SessionRepository(database.tuningSessionDao(), featureAccessPolicy = featureAccessPolicy)

    private val _uiState = MutableStateFlow(AudioCaptureUiState())
    val uiState: StateFlow<AudioCaptureUiState> = _uiState.asStateFlow()

    private val snapshotStore = FrozenSnapshotStore(featureAccessPolicy)
    val frozenSnapshots: StateFlow<List<RtaSnapshot>> = snapshotStore.snapshots

    private val _savedSessions = MutableStateFlow<List<SessionWithSnapshots>>(emptyList())
    val savedSessions: StateFlow<List<SessionWithSnapshots>> = _savedSessions.asStateFlow()

    private val _isFullscreenGraph = MutableStateFlow(false)
    val isFullscreenGraph: StateFlow<Boolean> = _isFullscreenGraph.asStateFlow()

    private var captureCollectorJob: Job? = null
    private var generatorCollectorJob: Job? = null
    private var calibrationCollectorJob: Job? = null
    private var sessionsCollectorJob: Job? = null
    private var entitlementCollectorJob: Job? = null
    private var ballisticsJob: Job? = null
    private var peakHoldDb = AudioMath.MIN_DB_FS
    private var peakHoldTimestamp = 0L

    init {
        startCollectingCaptureState()
        entitlementCollectorJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            entitlementState.collect {
                synchronized(captureControlLock) {
                    // Read current policy, since a queued tier emission may already be stale.
                    ensureAllowedWindowLocked()
                }
            }
        }
        startCollectingGeneratorState()
        startCollectingCalibrationState()
        startCollectingSavedSessions()
        startBallisticsTicker()
    }


    private fun startCollectingGeneratorState() {
        generatorCollectorJob?.cancel()
        generatorCollectorJob = viewModelScope.launch {
            signalGenerator.state.collect { genState ->
                _uiState.value = _uiState.value.copy(generator = genState)
            }
        }
    }

    private fun startCollectingCalibrationState() {
        calibrationCollectorJob?.cancel()
        calibrationCollectorJob = viewModelScope.launch {
            calibrationManager.uiState.collect { calState ->
                val previousConfig = _uiState.value.displayConfig
                val updatedConfig = _uiState.value.displayConfig.copy(
                    isSplMode = calState.splCalibration.isSplMode,
                    splOffsetDb = calState.splCalibration.splOffsetDb,
                    isTargetCurveActive = calState.isTargetCurveActive,
                    targetCurve = calState.targetCurve
                )
                _uiState.value = _uiState.value.copy(
                    calibration = calState,
                    displayConfig = updatedConfig
                )
                if (previousConfig.isSplMode != updatedConfig.isSplMode ||
                    previousConfig.splOffsetDb != updatedConfig.splOffsetDb) resetPeakHold()
            }
        }
    }

    private fun createCaptureManager(windowSize: Int): AudioCaptureManager {
        return AudioCaptureManager(
            context = getApplication<Application>().applicationContext,
            config = AudioCaptureConfig(
                windowSize = windowSize,
                hopSize = windowSize / 2,
                preferredDeviceId = preferredDeviceId
            )
        )
    }

    private fun startCollectingCaptureState() {
        captureCollectorJob?.cancel()
        val source = captureManager
        captureCollectorJob = viewModelScope.launch {
            source.uiState.collect { mgrState ->
                synchronized(captureControlLock) {
                    if (source !== captureManager) return@synchronized
                    val now = System.currentTimeMillis()

                    calibrationManager.updateCaptureAuthority(mgrState.captureAuthority)

                    // Pass raw audio spectral data through calibration engine (mic curve, noise subtraction, SPL, target deltas)
                    val diag = mgrState.diagnostics
                    val binRes = if (diag.windowSize > 0 && diag.actualSampleRate > 0) {
                        diag.actualSampleRate.toFloat() / diag.windowSize
                    } else {
                        23.4375f
                    }

                    val calFrame = calibrationManager.processFrame(
                        raw31Bands = mgrState.octave31Db,
                        rawFftDb = mgrState.rawFftDb,
                        binResolutionHz = binRes,
                        frameContext = mgrState.frameContext
                    )

                    val rawSpl = if (calFrame.isSplMode) calFrame.splOffsetDb else 0.0f
                    val splOffset = if (rawSpl.isNaN() || rawSpl.isInfinite()) 0.0f else rawSpl

                    val rawPeak = if (mgrState.peakDb.isNaN() || mgrState.peakDb.isInfinite()) AudioMath.MIN_DB_FS else mgrState.peakDb
                    val rawRms = if (mgrState.rmsDb.isNaN() || mgrState.rmsDb.isInfinite()) AudioMath.MIN_DB_FS else mgrState.rmsDb

                    val effectivePeak = rawPeak + splOffset
                    val effectiveRms = rawRms + splOffset

                    // Broadband Peak hold ballistic update (clamped safely)
                    if (peakHoldDb.isNaN() || peakHoldDb.isInfinite() || effectivePeak >= peakHoldDb) {
                        peakHoldDb = effectivePeak
                        peakHoldTimestamp = now
                    }

                    _uiState.value = mgrState.copy(
                        rmsDb = effectiveRms,
                        peakDb = effectivePeak,
                        peakHoldDb = peakHoldDb,
                        displayMode = _uiState.value.displayMode,
                        displayConfig = _uiState.value.displayConfig,
                        octave31Db = calFrame.calibrated31Db,
                        rawFftDb = calFrame.calibratedRawFftDb,
                        unreliableBands = calFrame.unreliableBands,
                        targetDeltas31Db = calFrame.targetDeltas31Db,
                        shouldResetSmoothing = calFrame.shouldResetSmoothing,
                        diagnostics = mgrState.diagnostics.copy(
                            windowSize = currentWindowSize,
                            hopSize = currentWindowSize / 2,
                            renderFps = _uiState.value.diagnostics.renderFps,
                            renderDrawTimeUs = _uiState.value.diagnostics.renderDrawTimeUs
                        ),
                        generator = _uiState.value.generator,
                        calibration = calibrationManager.uiState.value
                    )
                }
            }
        }
    }

    private fun startBallisticsTicker() {
        ballisticsJob?.cancel()
        ballisticsJob = viewModelScope.launch {
            var lastTick = System.currentTimeMillis()
            while (isActive) {
                delay(BALLISTICS_TICK_MS)
                val now = System.currentTimeMillis()
                val dt = (now - lastTick) / 1000f
                lastTick = now

                val rawSpl = if (_uiState.value.calibration.splCalibration.isSplMode) {
                    _uiState.value.calibration.splCalibration.splOffsetDb
                } else 0.0f
                val splOffset = if (rawSpl.isNaN() || rawSpl.isInfinite()) 0.0f else rawSpl
                val floorDb = AudioMath.MIN_DB_FS + splOffset

                if (_uiState.value.status == CaptureStatus.RUNNING) {
                    val currentPeak = _uiState.value.peakDb
                    val safePeak = if (currentPeak.isNaN() || currentPeak.isInfinite()) floorDb else currentPeak

                    if (peakHoldDb.isNaN() || peakHoldDb.isInfinite()) {
                        peakHoldDb = safePeak
                        peakHoldTimestamp = now
                    } else {
                        val timeSincePeak = now - peakHoldTimestamp
                        if (timeSincePeak > HOLD_DURATION_MS) {
                            val decayed = peakHoldDb - (DECAY_RATE_DB_PER_SEC * dt)
                            peakHoldDb = max(floorDb, max(decayed, safePeak))
                            if (peakHoldDb.isNaN() || peakHoldDb.isInfinite()) {
                                peakHoldDb = floorDb
                            }
                            _uiState.value = _uiState.value.copy(peakHoldDb = peakHoldDb)
                        }
                    }
                }
            }
        }
    }

    fun startCapture() {
        synchronized(captureControlLock) {
            ensureAllowedWindowLocked(resumeCapture = false)
            resetPeakHold()
            captureManager.startCapture()
        }
    }

    fun stopCapture() {
        synchronized(captureControlLock) {
            captureManager.stopCapture()
            calibrationManager.updateCaptureAuthority(captureManager.uiState.value.captureAuthority)
        }
    }

    fun retryCapture() {
        synchronized(captureControlLock) {
            captureManager.stopCapture()
            calibrationManager.updateCaptureAuthority(captureManager.uiState.value.captureAuthority)
            startCapture()
        }
    }

    fun resetPeakHold() {
        val rawSpl = if (_uiState.value.calibration.splCalibration.isSplMode) {
            _uiState.value.calibration.splCalibration.splOffsetDb
        } else 0.0f
        val splOffset = if (rawSpl.isNaN() || rawSpl.isInfinite()) 0.0f else rawSpl
        peakHoldDb = AudioMath.MIN_DB_FS + splOffset
        peakHoldTimestamp = System.currentTimeMillis()
        _uiState.value = _uiState.value.copy(peakHoldDb = peakHoldDb)
    }

    fun setFftDisplayMode(mode: FftDisplayMode) {
        synchronized(captureControlLock) {
            val updatedConfig = _uiState.value.displayConfig.copy(displayMode = mode)
            captureManager.setDisplayMode(mode)
            _uiState.value = _uiState.value.copy(
                displayMode = mode,
                displayConfig = updatedConfig
            )
        }
    }

    fun setEmaPreset(preset: EmaPreset) {
        val updatedConfig = _uiState.value.displayConfig.copy(emaPreset = preset)
        _uiState.value = _uiState.value.copy(displayConfig = updatedConfig)
    }

    fun setDbRange(minDb: Float, maxDb: Float = 0.0f) {
        val updatedConfig = _uiState.value.displayConfig.copy(minDb = minDb, maxDb = maxDb)
        _uiState.value = _uiState.value.copy(displayConfig = updatedConfig)
    }

    fun setPeakHoldSettings(durationMs: Long, decayRateDbPerSec: Float) {
        val updatedConfig = _uiState.value.displayConfig.copy(
            peakHoldDurationMs = durationMs,
            peakDecayRateDbPerSec = decayRateDbPerSec
        )
        _uiState.value = _uiState.value.copy(displayConfig = updatedConfig)
    }

    fun updateRenderTelemetry(fps: Float, drawTimeUs: Long) {
        _uiState.value = _uiState.value.let { current ->
            current.copy(
                diagnostics = current.diagnostics.copy(
                    renderFps = fps,
                    renderDrawTimeUs = drawTimeUs
                )
            )
        }
    }

    fun setWindowSize(newSize: Int): FeatureAccessResult = synchronized(captureControlLock) {
        val access = featureAccessPolicy.checkFftWindow(newSize)
        if (access is FeatureAccessResult.Denied) {
            return@synchronized reportAccess(access)
        }
        reportAccess(reconfigureWindowLocked(newSize))
    }

    /** Only the live configuration changes; stored sessions and overlays keep their metadata. */
    private fun ensureAllowedWindowLocked(resumeCapture: Boolean = true): Boolean {
        if (featureAccessPolicy.checkFftWindow(currentWindowSize) is FeatureAccessResult.Allowed) return false
        reconfigureWindowLocked(2048, resumeCapture)
        return true
    }

    private fun reconfigureWindowLocked(newSize: Int, resumeCapture: Boolean = true): FeatureAccessResult {
        if (newSize == currentWindowSize) return FeatureAccessResult.Allowed
        val wasRunning = captureManager.uiState.value.status == CaptureStatus.RUNNING
        captureCollectorJob?.cancel()
        calibrationManager.cancelNoiseCapture()
        captureManager.release()

        // Release may take time: a downgrade during it must not create a restricted manager.
        val access = featureAccessPolicy.checkFftWindow(newSize)
        val effectiveSize = if (access is FeatureAccessResult.Allowed) newSize else 2048
        captureManager = createCaptureManager(effectiveSize)
        captureManager.setDisplayMode(_uiState.value.displayMode)
        _fftWindowSize.value = effectiveSize
        _uiState.value = _uiState.value.copy(
            diagnostics = _uiState.value.diagnostics.copy(windowSize = effectiveSize, hopSize = effectiveSize / 2)
        )
        startCollectingCaptureState()

        if (wasRunning && resumeCapture) {
            captureManager.startCapture()
        }
        return access
    }

    // --- Stage 4 Test Signal Generator Controls ---

    fun startPinkNoise() {
        signalGenerator.startPinkNoise(_uiState.value.generator.outputLevelDb)
    }

    fun startSineSweep() {
        val gen = _uiState.value.generator
        reportAccess(signalGenerator.startSineSweep(
            range = gen.sweepRange,
            durationSec = gen.sweepDurationSec,
            isLooping = gen.isSweepLooping,
            levelDb = gen.outputLevelDb
        ))
    }

    fun stopGenerator() {
        signalGenerator.stop()
    }

    fun setGeneratorVolume(levelDb: Float) {
        signalGenerator.setOutputLevel(levelDb)
    }

    fun setSweepDuration(durationSec: Float) {
        reportAccess(signalGenerator.setSweepDuration(durationSec))
    }

    fun setSweepRange(range: SweepRangePreset) {
        reportAccess(signalGenerator.setSweepRange(range))
    }

    fun setSweepLooping(looping: Boolean) {
        reportAccess(signalGenerator.setSweepLooping(looping))
    }

    // --- Stage 5 Calibration & Target Curve Actions ---

    fun loadMicCurve(
        name: String,
        textContent: String,
        convention: CalibrationFileConvention = CalibrationFileConvention.MEASURED_RESPONSE
    ): Result<MicCalibrationCurve> {
        return calibrationManager.loadMicCurve(name, textContent, convention).also { result ->
            (result.exceptionOrNull() as? FeatureAccessDeniedException)?.let { reportAccess(it.denial) }
        }
    }

    fun setMicCurveActive(active: Boolean) {
        reportAccess(calibrationManager.setMicCurveActive(active))
    }

    fun clearMicCurve() {
        reportAccess(calibrationManager.clearMicCurve())
    }

    fun calibrateSplReference(targetSplDb: Float) {
        val rawRmsDbFs = if (_uiState.value.status == CaptureStatus.RUNNING) {
            if (_uiState.value.calibration.splCalibration.isSplMode) {
                _uiState.value.rmsDb - _uiState.value.calibration.splCalibration.splOffsetDb
            } else {
                _uiState.value.rmsDb
            }
        } else {
            -23.01f
        }
        if (reportAccess(calibrationManager.calibrateSplReference(rawRmsDbFs, targetSplDb)) == FeatureAccessResult.Allowed) {
            resetPeakHold()
        }
    }

    fun setSplMode(enabled: Boolean) {
        if (reportAccess(calibrationManager.setSplMode(enabled)) == FeatureAccessResult.Allowed) resetPeakHold()
    }

    fun setTargetCurve(curve: TargetCurve?) {
        reportAccess(calibrationManager.setTargetCurve(curve))
    }

    fun setTargetCurveActive(active: Boolean) {
        reportAccess(calibrationManager.setTargetCurveActive(active))
    }

    fun startNoiseFloorCapture() {
        reportAccess(calibrationManager.startNoiseFloorCapture(2000L, captureManager.uiState.value.captureAuthority))
    }

    fun setNoiseSubtractionActive(active: Boolean) {
        reportAccess(calibrationManager.setNoiseSubtractionActive(active))
    }

    fun clearNoiseFloor() {
        reportAccess(calibrationManager.clearNoiseFloor())
    }

    private fun startCollectingSavedSessions() {
        sessionsCollectorJob?.cancel()
        sessionsCollectorJob = viewModelScope.launch {
            sessionRepository.allSessionsWithSnapshots.collect { sessions ->
                _savedSessions.value = sessions
            }
        }
    }

    // ==========================================
    // STAGE 6: SNAPSHOT / FREEZE MANAGEMENT
    // ==========================================

    fun freezeCurrentSnapshot(customName: String? = null): FeatureAccessResult {
        val currentSnapshots = frozenSnapshots.value
        val calState = calibrationManager.uiState.value
        val isSpl = calState.splCalibration.isSplMode
        val splOffset = if (isSpl) calState.splCalibration.splOffsetDb else 0.0f
        val liveBands = _uiState.value.octave31Db.clone()

        val defaultName = if (currentSnapshots.isEmpty()) "Before EQ" else "Snapshot ${currentSnapshots.size + 1}"
        val name = if (customName.isNullOrBlank()) defaultName else customName
        val colorHex = SnapshotPalette.getColorForIndex(currentSnapshots.size)

        val newSnapshot = RtaSnapshot(
            name = name,
            timestamp = System.currentTimeMillis(),
            bands31Db = liveBands,
            isSplMode = isSpl,
            splOffsetDb = splOffset,
            calibrationName = if (calState.isMicCurveActive) calState.micCurve?.name else null,
            targetCurveName = if (calState.isTargetCurveActive) calState.targetCurve?.name else null,
            windowSize = currentWindowSize,
            isNoiseSubtractionActive = calState.isNoiseSubtractionActive,
            activeNoiseFloorBands = if (calState.noiseFloor.isCaptured) calState.noiseFloor.bands31Db.clone() else null,
            colorHex = colorHex,
            isVisible = true
        )

        return reportAccess(snapshotStore.add(newSnapshot))
    }

    fun unfreezeSnapshot(id: String) {
        snapshotStore.remove(id)
    }

    fun clearAllFrozenSnapshots() {
        snapshotStore.clear()
    }

    fun toggleSnapshotVisibility(id: String) {
        snapshotStore.toggleVisibility(id)
    }

    fun renameSnapshot(id: String, newName: String) {
        if (newName.isBlank()) return
        snapshotStore.rename(id, newName)
    }

    fun loadSessionSnapshotsToOverlay(
        sessionWithSnapshots: SessionWithSnapshots,
        selectedSnapshotIds: List<Long>? = null
    ): FeatureAccessResult = reportAccess(snapshotStore.loadSession(sessionWithSnapshots, selectedSnapshotIds))

    // ==========================================
    // STAGE 6: ROOM SESSION PERSISTENCE
    // ==========================================

    fun saveSession(
        name: String,
        notes: String = "",
        includeLive: Boolean = true,
        includeFrozenIds: List<String> = emptyList(),
        onComplete: ((Long) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val calState = calibrationManager.uiState.value
            val isSpl = calState.splCalibration.isSplMode
            val splOffset = if (isSpl) calState.splCalibration.splOffsetDb else 0.0f
            val now = System.currentTimeMillis()

            val sessionEntity = TuningSessionEntity(
                name = name.ifBlank { "Session ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))}" },
                notes = notes,
                createdAt = now,
                activeCalibrationName = if (calState.isMicCurveActive) calState.micCurve?.name else null,
                activeTargetCurveName = if (calState.isTargetCurveActive) calState.targetCurve?.name else null,
                isSplMode = isSpl,
                splOffsetDb = splOffset,
                windowSize = currentWindowSize,
                isNoiseSubtractionActive = calState.isNoiseSubtractionActive,
                activeNoiseFloorBands = if (calState.noiseFloor.isCaptured) calState.noiseFloor.bands31Db.clone() else null
            )

            val snapshotEntities = mutableListOf<SessionSnapshotEntity>()

            // Include live spectrum as a snapshot if selected
            if (includeLive) {
                snapshotEntities.add(
                    SessionSnapshotEntity(
                        sessionId = 0,
                        name = "Live Capture",
                        timestamp = now,
                        bands31Db = _uiState.value.octave31Db.clone(),
                        colorHex = SnapshotPalette.getColorForIndex(0),
                        isSplMode = isSpl,
                        splOffsetDb = splOffset,
                        calibrationName = sessionEntity.activeCalibrationName,
                        targetCurveName = sessionEntity.activeTargetCurveName,
                        isNoiseSubtractionActive = sessionEntity.isNoiseSubtractionActive,
                        activeNoiseFloorBands = sessionEntity.activeNoiseFloorBands
                    )
                )
            }

            // Include selected frozen snapshots
            val currentFrozen = frozenSnapshots.value
            for (frozen in currentFrozen) {
                if (includeFrozenIds.contains(frozen.id)) {
                    snapshotEntities.add(
                        SessionSnapshotEntity(
                            sessionId = 0,
                            name = frozen.name,
                            timestamp = frozen.timestamp,
                            bands31Db = frozen.bands31Db.clone(),
                            colorHex = frozen.colorHex,
                            isSplMode = frozen.isSplMode,
                            splOffsetDb = frozen.splOffsetDb,
                            calibrationName = frozen.calibrationName,
                            targetCurveName = frozen.targetCurveName,
                            isNoiseSubtractionActive = frozen.isNoiseSubtractionActive,
                            activeNoiseFloorBands = frozen.activeNoiseFloorBands
                        )
                    )
                }
            }

            try {
                val id = sessionRepository.saveSession(sessionEntity, snapshotEntities)
                onComplete?.invoke(id)
            } catch (denied: FeatureAccessDeniedException) {
                reportAccess(denied.denial)
            }
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            sessionRepository.deleteSession(sessionId)
        }
    }

    fun updateSessionNotes(sessionId: Long, notes: String) {
        viewModelScope.launch {
            sessionRepository.updateSessionNotes(sessionId, notes)
        }
    }

    /** Authorization precedes formatting and the shared CSV writer checks again before I/O. */
    private fun exportCsv(context: Context, name: String, content: () -> String): CsvExportResult {
        val access = featureAccessPolicy.check(ProFeature.CSV_EXPORT)
        if (access is FeatureAccessResult.Denied) {
            reportAccess(access)
            return CsvExportResult.AccessDenied(access)
        }
        return try {
            RtaExportManager.shareCsv(context, content(), name, featureAccessPolicy)
            CsvExportResult.ShareChooserLaunched
        } catch (denied: FeatureAccessDeniedException) {
            reportAccess(denied.denial)
            CsvExportResult.AccessDenied(denied.denial)
        } catch (failure: CsvShareException) {
            reportCsvFailure(failure.cause as? Exception ?: failure)
        } catch (failure: Exception) {
            // Formatting errors precede the export manager's own error logging.
            Log.e("AudioCaptureViewModel", "Could not format CSV for sharing", failure)
            reportCsvFailure(failure)
        }
    }

    private fun reportCsvFailure(cause: Exception): CsvExportResult.Failed {
        csvExportErrorChannel.trySend("Could not share CSV. Please try again.")
        return CsvExportResult.Failed(cause)
    }

    fun exportLiveCsv(context: Context): CsvExportResult = exportCsv(context, "cabinrta_live_spectrum") {
        val state = uiState.value
        val cal = calibrationManager.uiState.value
        RtaExportManager.formatLiveCurveCsv(
            liveBands31Db = state.octave31Db,
            calibrationName = if (cal.isMicCurveActive) cal.micCurve?.name else null,
            targetCurveName = if (cal.isTargetCurveActive) cal.targetCurve?.name else null,
            isSplMode = cal.splCalibration.isSplMode,
            splOffsetDb = if (cal.splCalibration.isSplMode) cal.splCalibration.splOffsetDb else 0f,
            windowSize = currentWindowSize,
            isNoiseSubtractionActive = cal.isNoiseSubtractionActive
        )
    }

    fun exportSnapshotCsv(context: Context, snapshot: RtaSnapshot): CsvExportResult =
        exportCsv(context, snapshot.name) { RtaExportManager.formatSnapshotCsv(snapshot) }

    fun exportSessionCsv(context: Context, session: SessionWithSnapshots): CsvExportResult =
        exportCsv(context, session.session.name) { RtaExportManager.formatSessionWithSnapshotsCsv(session) }

    // ==========================================
    // STAGE 7: INPUT DEVICE SELECTION & HOTPLUG
    // ==========================================

    fun setPreferredInputDevice(deviceId: Int?) {
        synchronized(captureControlLock) {
            preferredDeviceId = deviceId
            // A replacement already receives this preference; avoid restarting it a second time.
            if (!ensureAllowedWindowLocked()) captureManager.setPreferredInputDevice(deviceId)
        }
    }

    fun dismissDevicePrompt() {
        captureManager.dismissDevicePrompt()
    }

    fun switchToDevice(device: AudioInputDeviceInfo) {
        captureManager.dismissDevicePrompt()
        setPreferredInputDevice(device.id)
    }

    fun revertToBuiltInMicAndRetry() {
        val builtIn = _uiState.value.availableInputDevices.firstOrNull { it.isBuiltIn }
        setPreferredInputDevice(builtIn?.id)
        startCapture()
    }

    fun refreshInputDevices() {
        captureManager.refreshInputDevices()
    }

    fun setFullscreenGraph(enabled: Boolean) {
        _isFullscreenGraph.value = enabled
    }

    override fun onCleared() {
        super.onCleared()
        csvExportErrorChannel.close()
        ballisticsJob?.cancel()
        captureCollectorJob?.cancel()
        generatorCollectorJob?.cancel()
        calibrationCollectorJob?.cancel()
        sessionsCollectorJob?.cancel()
        entitlementCollectorJob?.cancel()
        synchronized(captureControlLock) { captureManager.release() }
        calibrationManager.close()
        signalGenerator.close()
    }
}

