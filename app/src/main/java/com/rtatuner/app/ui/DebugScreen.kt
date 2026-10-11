package com.rtatuner.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.rtatuner.app.core.audio.GeneratorMode
import com.rtatuner.app.core.audio.SweepRangePreset
import com.rtatuner.app.core.audio.SignalGeneratorState
import com.rtatuner.app.core.calibration.CalibrationUiState
import com.rtatuner.app.core.calibration.MicCalibrationCurve
import com.rtatuner.app.core.calibration.TargetCurve
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rtatuner.app.R
import com.rtatuner.app.BuildConfig
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.rtatuner.app.core.audio.AudioCaptureUiState
import com.rtatuner.app.core.audio.CaptureStatus
import com.rtatuner.app.core.audio.FftDisplayMode
import com.rtatuner.app.core.session.RtaSnapshot
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.core.entitlement.ProFeature
import com.rtatuner.app.ui.components.LevelMeter
import com.rtatuner.app.ui.components.SessionToolsCard
import com.rtatuner.app.ui.components.ProUpgradeSheet
import com.rtatuner.app.ui.components.ProFeatureNotice
import com.rtatuner.app.ui.components.AudioInputDeviceCard
import com.rtatuner.app.ui.components.surface.RtaSurfaceView
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Usb
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.DisposableEffect
import java.util.Locale
import kotlinx.coroutines.flow.Flow

@Composable
fun DebugScreen(
    viewModel: AudioCaptureViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val frozenSnapshots by viewModel.frozenSnapshots.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState()
    val isFullscreenGraph by viewModel.isFullscreenGraph.collectAsState()
    val entitlement by viewModel.entitlementState.collectAsState()
    val accessDenial by viewModel.accessDenial.collectAsState()
    val csvSnackbarHostState = remember { SnackbarHostState() }
    CsvExportErrorEffect(viewModel.csvExportErrors, csvSnackbarHostState)
    val policy = viewModel.featureAccessPolicy
    val canFreeze = remember(entitlement, frozenSnapshots.size) {
        policy.checkOverlayCapacity(frozenSnapshots.size) is FeatureAccessResult.Allowed
    }
    val canMicCalibrate = remember(entitlement) { policy.check(ProFeature.MIC_CALIBRATION) is FeatureAccessResult.Allowed }
    val canSplCalibrate = remember(entitlement) { policy.check(ProFeature.SPL_CALIBRATION) is FeatureAccessResult.Allowed }
    val canCorrectNoise = remember(entitlement) { policy.check(ProFeature.NOISE_FLOOR) is FeatureAccessResult.Allowed }
    val canUseAdvancedTargets = remember(entitlement) { policy.check(ProFeature.ADVANCED_TARGETS) is FeatureAccessResult.Allowed }
    val canUseSweep = remember(entitlement) { policy.check(ProFeature.SINE_SWEEP) is FeatureAccessResult.Allowed }
    var surfaceViewRef by remember { mutableStateOf<RtaSurfaceView?>(null) }

    accessDenial?.let { denial ->
        ProUpgradeSheet(
            entitlement = entitlement,
            feature = denial.feature,
            message = denial.message,
            onDismiss = { viewModel.dismissAccessDenial() },
            onSimulateTier = {
                if (viewModel.simulateEntitlement(it)) viewModel.dismissAccessDenial()
            }
        )
    }

    val activity = context as? Activity

    BackHandler(enabled = isFullscreenGraph) {
        viewModel.setFullscreenGraph(false)
    }

    DisposableEffect(isFullscreenGraph) {
        val window = activity?.window
        val insetsController = if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView)
        } else null

        if (isFullscreenGraph) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            viewModel.startCapture()
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && uiState.status == CaptureStatus.STOPPED) {
            viewModel.startCapture()
        }
    }

    if (isFullscreenGraph) {
        FullscreenGraphView(
            uiState = uiState,
            frozenSnapshots = frozenSnapshots,
            canFreeze = canFreeze,
            onFreeze = { viewModel.freezeCurrentSnapshot() },
            onUnfreezeSnapshot = { viewModel.unfreezeSnapshot(it) },
            onToggleSnapshotVisibility = { viewModel.toggleSnapshotVisibility(it) },
            onExitFullscreen = { viewModel.setFullscreenGraph(false) },
            onSurfaceViewCreated = { surfaceViewRef = it },
            onUpdateRenderTelemetry = { fps, drawTimeUs ->
                viewModel.updateRenderTelemetry(fps, drawTimeUs)
            }
        )
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = Color(0xFF0F1115),
            snackbarHost = { SnackbarHost(csvSnackbarHostState) }
        ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Bar
            HeaderSection(status = uiState.status)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (entitlement.isPro && BuildConfig.DEBUG) "DEBUG · SIMULATED PRO" else if (entitlement.isPro) "PRO ACCESS" else "FREE · COMPLETE LIVE ANALYZER",
                    color = Color(0xFF78909C), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { viewModel.requestProUpgrade() }) {
                    Text("PRO · COMING SOON", color = Color(0xFF00E5FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Permission Banner
            if (!hasPermission) {
                PermissionRequestCard(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                )
            }

            // Capture Interrupted / Error Banner
            if (uiState.status == CaptureStatus.ERROR) {
                CaptureErrorBanner(
                    errorMessage = uiState.errorMessage ?: "Unknown audio capture error",
                    onRetry = { viewModel.retryCapture() },
                    onRevertToBuiltIn = { viewModel.revertToBuiltInMicAndRetry() }
                )
            }

            // Hotplug Switch Prompt Dialog
            val attachedPrompt = uiState.deviceAttachedPrompt
            if (attachedPrompt != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissDevicePrompt() },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Usb,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "New Audio Input Detected",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "A new audio input device was connected:",
                                color = Color(0xFFB0BEC5),
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${attachedPrompt.name} (${attachedPrompt.typeName})",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Native rates: ${attachedPrompt.ratesSummary}",
                                color = Color(0xFF78909C),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Would you like to switch active capture to this device now?",
                                color = Color(0xFFECEFF1),
                                fontSize = 12.sp
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.switchToDevice(attachedPrompt) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E5FF),
                                contentColor = Color.Black
                            )
                        ) {
                            Text("SWITCH INPUT", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissDevicePrompt() }) {
                            Text("KEEP CURRENT", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)
                        }
                    }
                )
            }

            // SurfaceView Spectrum Card with Frozen Overlays
            SurfaceSpectrumCard(
                uiState = uiState,
                frozenSnapshots = frozenSnapshots,
                canFreeze = canFreeze,
                onFreeze = { viewModel.freezeCurrentSnapshot() },
                onClearAllFrozen = { viewModel.clearAllFrozenSnapshots() },
                onToggleSnapshotVisibility = { viewModel.toggleSnapshotVisibility(it) },
                onUnfreezeSnapshot = { viewModel.unfreezeSnapshot(it) },
                onEnterFullscreen = { viewModel.setFullscreenGraph(true) },
                onSurfaceViewCreated = { surfaceViewRef = it },
                onToggleMode = { viewModel.setFftDisplayMode(it) },
                onSelectWindowSize = { viewModel.setWindowSize(it) },
                onSelectEmaPreset = { viewModel.setEmaPreset(it) },
                onSelectDbRange = { minDb -> viewModel.setDbRange(minDb) },
                onUpdatePeakHold = { durationMs, decayRate ->
                    viewModel.setPeakHoldSettings(durationMs, decayRate)
                },
                onUpdateRenderTelemetry = { fps, drawTimeUs ->
                    viewModel.updateRenderTelemetry(fps, drawTimeUs)
                }
            )

            // Calibration & Target Curves Card
            CalibrationControlsCard(
                uiState = uiState,
                canMicCalibrate = canMicCalibrate,
                canSplCalibrate = canSplCalibrate,
                canCorrectNoise = canCorrectNoise,
                canUseAdvancedTargets = canUseAdvancedTargets,
                onRequestPro = { viewModel.requestProUpgrade(it) },
                onLoadMicCurve = { name, content -> viewModel.loadMicCurve(name, content) },
                onSetMicCurveActive = { viewModel.setMicCurveActive(it) },
                onClearMicCurve = { viewModel.clearMicCurve() },
                onCalibrateSpl = { viewModel.calibrateSplReference(it) },
                onSetSplMode = { viewModel.setSplMode(it) },
                onSetTargetCurve = { viewModel.setTargetCurve(it) },
                onSetTargetCurveActive = { viewModel.setTargetCurveActive(it) },
                onStartNoiseCapture = { viewModel.startNoiseFloorCapture() },
                onSetNoiseSubtractionActive = { viewModel.setNoiseSubtractionActive(it) },
                onClearNoiseFloor = { viewModel.clearNoiseFloor() }
            )

            // Session Tools Card
            SessionToolsCard(
                viewModel = viewModel,
                surfaceViewRef = surfaceViewRef,
                uiState = uiState,
                frozenSnapshots = frozenSnapshots,
                savedSessions = savedSessions
            )

            // Audio Input Hardware & Device Selection Card
            AudioInputDeviceCard(
                uiState = uiState,
                onSelectDevice = { viewModel.setPreferredInputDevice(it) },
                onRefreshDevices = { viewModel.refreshInputDevices() }
            )


            // Hardware Mic Noise Floor Limitation Notice
            MicNoiseFloorNoticeCard()

            // Test Signal Generator Card
            SignalGeneratorCard(
                state = uiState.generator,
                canUseSweep = canUseSweep,
                onRequestPro = { viewModel.requestProUpgrade(ProFeature.SINE_SWEEP) },
                onStartPinkNoise = { viewModel.startPinkNoise() },
                onStartSineSweep = { viewModel.startSineSweep() },
                onStop = { viewModel.stopGenerator() },
                onSetVolume = { viewModel.setGeneratorVolume(it) },
                onSetDuration = { viewModel.setSweepDuration(it) },
                onSetRange = { viewModel.setSweepRange(it) },
                onToggleLooping = { viewModel.setSweepLooping(it) }
            )

            // Broadband Level Meters (Peak / RMS / Clip)
            val isSpl = uiState.calibration.splCalibration.isSplMode
            val splOffset = if (isSpl) uiState.calibration.splCalibration.splOffsetDb else 0.0f
            LiveMetersCard(
                peakDb = uiState.peakDb,
                peakHoldDb = uiState.peakHoldDb,
                rmsDb = uiState.rmsDb,
                isClipping = uiState.isClipping,
                isLive = uiState.status == CaptureStatus.RUNNING,
                isSplMode = isSpl,
                splOffsetDb = splOffset,
                onResetPeak = { viewModel.resetPeakHold() }
            )

            // Advanced Diagnostics Card (collapsed by default)
            var showHardwareDiag by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (showHardwareDiag) Color(0xFF14171E) else Color.Transparent
                    )
                    .clickable { showHardwareDiag = !showHardwareDiag }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Toggle advanced diagnostics",
                    tint = Color(0xFF546E7A),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (showHardwareDiag) "▾ ADVANCED DIAGNOSTICS" else "▸ ADVANCED DIAGNOSTICS",
                    color = Color(0xFF78909C),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (showHardwareDiag) {
                HardwareDiagnosticsCard(uiState = uiState)
            }

            // Audio Controls (Start / Stop / Reset)
            ControlsSection(
                status = uiState.status,
                hasPermission = hasPermission,
                onStart = { viewModel.startCapture() },
                onStop = { viewModel.stopCapture() },
                onResetPeak = { viewModel.resetPeakHold() }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    }
}

@Composable
internal fun CsvExportErrorEffect(errors: Flow<String>, snackbarHostState: SnackbarHostState) {
    LaunchedEffect(errors, snackbarHostState) {
        errors.collect { message -> snackbarHostState.showSnackbar(message) }
    }
}

@Composable
private fun FullscreenGraphView(
    uiState: AudioCaptureUiState,
    frozenSnapshots: List<RtaSnapshot>,
    canFreeze: Boolean,
    onFreeze: () -> Unit,
    onUnfreezeSnapshot: (String) -> Unit,
    onToggleSnapshotVisibility: (String) -> Unit,
    onExitFullscreen: () -> Unit,
    onSurfaceViewCreated: ((RtaSurfaceView) -> Unit)? = null,
    onUpdateRenderTelemetry: (Float, Long) -> Unit
) {
    val diag = uiState.diagnostics
    val config = uiState.displayConfig
    val binResolution = if (diag.windowSize > 0 && diag.actualSampleRate > 0) {
        diag.actualSampleRate.toFloat() / diag.windowSize
    } else {
        23.4375f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1117))
    ) {
        // High-performance SurfaceView filling the entire screen in landscape
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                RtaSurfaceView(ctx).apply {
                    this.displayConfig = config
                    this.onRenderStatsUpdated = { fps, drawTimeUs ->
                        onUpdateRenderTelemetry(fps, drawTimeUs)
                    }
                    onSurfaceViewCreated?.invoke(this)
                }
            },
            update = { surfaceView ->
                surfaceView.displayConfig = config
                surfaceView.updateSpectralData(
                    octave31Db = uiState.octave31Db,
                    rawFftDb = uiState.rawFftDb,
                    binResolutionHz = binResolution,
                    unreliableBands = uiState.unreliableBands,
                    targetDeltas31Db = uiState.targetDeltas31Db,
                    frozenSnapshots = frozenSnapshots,
                    resetSmoothing = uiState.shouldResetSmoothing
                )
            }
        )

        // Unobtrusive corner controls overlay in Top-Right
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Active frozen snapshots badges
            if (frozenSnapshots.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    frozenSnapshots.forEach { snap ->
                        val color = Color(snap.colorHex)
                        Surface(
                            color = Color(0xCC14171E),
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (snap.isVisible) color else Color(0xFF37474F)
                            ),
                            modifier = Modifier.clickable { onToggleSnapshotVisibility(snap.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (snap.isVisible) color else Color(0xFF546E7A))
                                )
                                Text(
                                    text = snap.name,
                                    color = if (snap.isVisible) Color.White else Color(0xFF78909C),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = Color(0xFFFF8A80),
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clickable { onUnfreezeSnapshot(snap.id) }
                                )
                            }
                        }
                    }
                }
            }

            // Freeze button overlay
            Surface(
                color = Color(0xD900E5FF),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.clickable { onFreeze() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AcUnit,
                        contentDescription = "Freeze",
                        tint = Color.Black,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (canFreeze) "FREEZE" else "FREEZE · PRO",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Exit fullscreen collapse button
            Surface(
                color = Color(0xCC263238),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF455A64)),
                modifier = Modifier.clickable { onExitFullscreen() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FullscreenExit,
                        contentDescription = "Exit Fullscreen",
                        tint = Color(0xFFECEFF1),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "EXIT",
                        color = Color(0xFFECEFF1),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(status: CaptureStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = Color(0xFF00E676),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Real-Time Audio Spectrum Analyzer",
                    color = Color(0xFF78909C),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        StatusBadge(status = status)
    }
}

@Composable
private fun StatusBadge(status: CaptureStatus) {
    val (bgColor, textColor, text) = when (status) {
        CaptureStatus.RUNNING -> Triple(Color(0xFF1B5E20), Color(0xFF81C784), "CAPTURING")
        CaptureStatus.INITIALIZING -> Triple(Color(0xFFE65100), Color(0xFFFFB74D), "STARTING")
        CaptureStatus.STOPPED -> Triple(Color(0xFF263238), Color(0xFF90A4AE), "STOPPED")
        CaptureStatus.ERROR -> Triple(Color(0xFFB71C1C), Color(0xFFFF8A80), "INTERRUPTED")
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, textColor.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(textColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun SurfaceSpectrumCard(
    uiState: AudioCaptureUiState,
    frozenSnapshots: List<RtaSnapshot> = emptyList(),
    canFreeze: Boolean = true,
    onFreeze: () -> Unit = {},
    onClearAllFrozen: () -> Unit = {},
    onToggleSnapshotVisibility: (String) -> Unit = {},
    onUnfreezeSnapshot: (String) -> Unit = {},
    onEnterFullscreen: () -> Unit = {},
    onSurfaceViewCreated: ((RtaSurfaceView) -> Unit)? = null,
    onToggleMode: (FftDisplayMode) -> Unit,
    onSelectWindowSize: (Int) -> Unit,
    onSelectEmaPreset: (EmaPreset) -> Unit,
    onSelectDbRange: (Float) -> Unit,
    onUpdatePeakHold: (Long, Float) -> Unit,
    onUpdateRenderTelemetry: (Float, Long) -> Unit
) {
    val diag = uiState.diagnostics
    val config = uiState.displayConfig
    var showAdvancedSettings by remember { mutableStateOf(false) }

    val binResolution = if (diag.windowSize > 0 && diag.actualSampleRate > 0) {
        diag.actualSampleRate.toFloat() / diag.windowSize
    } else {
        23.4375f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14171E)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E3846))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Row 1: Mode Toggle, Window Size Chips & Fullscreen Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode Toggle
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0C0E12))
                        .border(1.dp, Color(0xFF242C38), RoundedCornerShape(6.dp))
                        .padding(2.dp)
                ) {
                    ModeTab(
                        title = "1/3 OCTAVE",
                        isSelected = config.displayMode == FftDisplayMode.OCTAVE_1_3,
                        onClick = { onToggleMode(FftDisplayMode.OCTAVE_1_3) }
                    )
                    ModeTab(
                        title = "RAW FFT",
                        isSelected = config.displayMode == FftDisplayMode.RAW_FFT,
                        onClick = { onToggleMode(FftDisplayMode.RAW_FFT) }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Window Size Selector (1024, 2048, 4096)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0C0E12))
                            .border(1.dp, Color(0xFF242C38), RoundedCornerShape(6.dp))
                            .padding(2.dp)
                    ) {
                        listOf(1024, 2048, 4096).forEach { size ->
                            WindowSizeChip(
                                size = size,
                                isSelected = diag.windowSize == size,
                                onClick = { onSelectWindowSize(size) }
                            )
                        }
                    }

                    // Fullscreen Expand Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0C0E12))
                            .border(1.dp, Color(0xFF242C38), RoundedCornerShape(6.dp))
                            .clickable { onEnterFullscreen() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Fullscreen View",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Row 2: EMA Averaging Presets & dB Range Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // EMA Averaging Selector
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text(
                        text = "AVG:",
                        color = Color(0xFF78909C),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 3.dp)
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0C0E12))
                            .border(1.dp, Color(0xFF242C38), RoundedCornerShape(6.dp))
                            .padding(1.dp)
                    ) {
                        EmaPreset.values().forEach { preset ->
                            SelectChip(
                                label = preset.name,
                                isSelected = config.emaPreset == preset,
                                onClick = { onSelectEmaPreset(preset) }
                            )
                        }
                    }
                }

                // dB Range Selector
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "RANGE:",
                        color = Color(0xFF78909C),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 3.dp)
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0C0E12))
                            .border(1.dp, Color(0xFF242C38), RoundedCornerShape(6.dp))
                            .padding(1.dp)
                    ) {
                        listOf(-60f, -80f, -90f).forEach { minDb ->
                            SelectChip(
                                label = "${minDb.toInt()}dB",
                                isSelected = config.minDb == minDb,
                                onClick = { onSelectDbRange(minDb) }
                            )
                        }
                    }
                }
            }

            // High-Performance SurfaceView Rendering Surface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF283240), RoundedCornerShape(6.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        RtaSurfaceView(ctx).apply {
                            this.displayConfig = config
                            this.onRenderStatsUpdated = { fps, drawTimeUs ->
                                onUpdateRenderTelemetry(fps, drawTimeUs)
                            }
                            onSurfaceViewCreated?.invoke(this)
                        }
                    },
                    update = { surfaceView ->
                        surfaceView.displayConfig = config
                        surfaceView.updateSpectralData(
                            octave31Db = uiState.octave31Db,
                            rawFftDb = uiState.rawFftDb,
                            binResolutionHz = binResolution,
                            unreliableBands = uiState.unreliableBands,
                            targetDeltas31Db = uiState.targetDeltas31Db,
                            frozenSnapshots = frozenSnapshots,
                            resetSmoothing = uiState.shouldResetSmoothing
                        )
                    }
                )
            }

            // Quick Freeze & Active Overlays Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Quick Freeze Action
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF00E5FF))
                        .clickable { onFreeze() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AcUnit,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (canFreeze) "FREEZE" else "FREEZE · PRO",
                            color = Color.Black,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (frozenSnapshots.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        frozenSnapshots.forEach { snap ->
                            val color = Color(snap.colorHex)
                            Surface(
                                color = color.copy(alpha = if (snap.isVisible) 0.2f else 0.05f),
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    0.5.dp,
                                    if (snap.isVisible) color else Color(0xFF37474F)
                                ),
                                modifier = Modifier.clickable { onToggleSnapshotVisibility(snap.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (snap.isVisible) color else Color(0xFF546E7A))
                                    )
                                    Text(
                                        text = snap.name,
                                        color = if (snap.isVisible) Color.White else Color(0xFF78909C),
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = Color(0xFFFF8A80),
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clickable { onUnfreezeSnapshot(snap.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }


            // Performance Diagnostics (collapsed by default)
            var showDiagnostics by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (showDiagnostics) Color(0xFF00E676).copy(alpha = 0.06f)
                        else Color.Transparent
                    )
                    .clickable { showDiagnostics = !showDiagnostics }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Toggle diagnostics",
                    tint = Color(0xFF546E7A),
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (showDiagnostics) "HIDE DIAGNOSTICS" else "DIAGNOSTICS",
                    color = Color(0xFF546E7A),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (showDiagnostics) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(Locale.US, "RENDER: %.0f FPS (%d µs)", diag.renderFps, diag.renderDrawTimeUs),
                        color = Color(0xFF00E676),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = String.format(Locale.US, "AUDIO: %.0f FPS (%d µs)", diag.fps, diag.fftExecutionTimeUs),
                        color = Color(0xFF81D4FA),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Peak-Hold Ballistics Toggle — own row so it always has full width
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .wrapContentWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (showAdvancedSettings) Color(0xFF00E676).copy(alpha = 0.10f)
                            else Color.Transparent
                        )
                        .clickable { showAdvancedSettings = !showAdvancedSettings }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Peak-Hold Ballistics Settings",
                        tint = if (showAdvancedSettings) Color(0xFF00E676) else Color(0xFF78909C),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (showAdvancedSettings) "HIDE SETTINGS" else "BALLISTICS",
                        color = if (showAdvancedSettings) Color(0xFF00E676) else Color(0xFF78909C),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Advanced Peak-Hold Settings Drawer
            if (showAdvancedSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0E1217))
                        .border(1.dp, Color(0xFF212833), RoundedCornerShape(6.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "PEAK-HOLD BALLISTICS CONTROLS",
                        color = Color(0xFFECEFF1),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    // Hold Duration
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Peak Hold Duration",
                            color = Color(0xFF90A4AE),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(500L, 1200L, 2500L).forEach { dur ->
                                SelectChip(
                                    label = String.format(Locale.US, "%.1fs", dur / 1000f),
                                    isSelected = config.peakHoldDurationMs == dur,
                                    onClick = { onUpdatePeakHold(dur, config.peakDecayRateDbPerSec) }
                                )
                            }
                        }
                    }

                    // Decay Rate
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Decay Fall Rate",
                            color = Color(0xFF90A4AE),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(15f, 25f, 40f).forEach { rate ->
                                SelectChip(
                                    label = "${rate.toInt()} dB/s",
                                    isSelected = config.peakDecayRateDbPerSec == rate,
                                    onClick = { onUpdatePeakHold(config.peakHoldDurationMs, rate) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) Color(0xFF37474F) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) Color(0xFF00E676) else Color(0xFF78909C),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun ModeTab(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .wrapContentWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) Color(0xFF00E676) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = title,
            color = if (isSelected) Color.Black else Color(0xFF90A4AE),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun WindowSizeChip(
    size: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) Color(0xFF37474F) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$size",
            color = if (isSelected) Color(0xFF00E676) else Color(0xFF78909C),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun CaptureErrorBanner(
    errorMessage: String,
    onRetry: () -> Unit,
    onRevertToBuiltIn: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1215)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFF1744))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "CAPTURE INTERRUPTED",
                    color = Color(0xFFFF5252),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = errorMessage,
                color = Color(0xFFFFCDD2),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onRevertToBuiltIn != null) {
                    OutlinedButton(
                        onClick = onRevertToBuiltIn,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFCDD2)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("SWITCH TO BUILT-IN MIC", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF1744),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("RETRY", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PermissionRequestCard(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E232A)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E676))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color(0xFF00E676)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "MICROPHONE PERMISSION REQUIRED",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Text(
                text = stringResource(R.string.microphone_permission_description, stringResource(R.string.app_name)),
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp
            )
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "GRANT PERMISSION",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun LiveMetersCard(
    peakDb: Float,
    peakHoldDb: Float,
    rmsDb: Float,
    isClipping: Boolean,
    isLive: Boolean,
    isSplMode: Boolean = false,
    splOffsetDb: Float = 0.0f,
    onResetPeak: () -> Unit
) {
    val rawSpl = if (isSplMode) splOffsetDb else 0.0f
    val safeSpl = if (rawSpl.isNaN() || rawSpl.isInfinite()) 0.0f else rawSpl
    val meterMinDb = if (isSplMode) -90.0f + safeSpl else -90.0f
    val meterMaxDb = if (isSplMode) 0.0f + safeSpl else 0.0f
    val unitStr = if (isSplMode) "dBSPL" else "dBFS"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14171E)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E3846))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isSplMode) "BROADBAND LEVEL METERS (SPL)" else "BROADBAND LEVEL METERS",
                    color = Color(0xFFECEFF1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                ClipIndicatorBadge(isClipping = isClipping)
            }

            LevelMeter(
                label = "PEAK LEVEL (FAST)",
                levelDb = if (isLive) peakDb else meterMinDb,
                peakHoldDb = if (isLive) peakHoldDb else meterMinDb,
                minDb = meterMinDb,
                maxDb = meterMaxDb,
                isSplMode = isSplMode
            )

            LevelMeter(
                label = "RMS LEVEL (AVERAGE)",
                levelDb = if (isLive) rmsDb else meterMinDb,
                minDb = meterMinDb,
                maxDb = meterMaxDb,
                isSplMode = isSplMode
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricBox(
                    label = "LIVE RMS",
                    value = String.format(Locale.US, "%.1f", if (isLive) rmsDb else meterMinDb),
                    unit = unitStr,
                    accent = Color(0xFF00E676)
                )
                MetricBox(
                    label = "LIVE PEAK",
                    value = String.format(Locale.US, "%.1f", if (isLive) peakDb else meterMinDb),
                    unit = unitStr,
                    accent = Color(0xFFFFEB3B)
                )
                MetricBox(
                    label = "PEAK HOLD",
                    value = String.format(Locale.US, "%.1f", if (isLive) peakHoldDb else meterMinDb),
                    unit = unitStr,
                    accent = Color(0xFFFF5252)
                )
            }
        }
    }
}

@Composable
private fun ClipIndicatorBadge(isClipping: Boolean) {
    val bgColor = if (isClipping) Color(0xFFFF1744) else Color(0xFF263238)
    val textColor = if (isClipping) Color.White else Color(0xFF546E7A)
    val text = if (isClipping) "OVERLOAD / CLIP" else "HEADROOM OK"

    Box(
        modifier = Modifier
            .wrapContentWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(1.dp, if (isClipping) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun MetricBox(
    label: String,
    value: String,
    unit: String,
    accent: Color
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0C0E12))
            .border(1.dp, Color(0xFF242C38), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color(0xFF78909C),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = unit,
                color = Color(0xFF546E7A),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun HardwareDiagnosticsCard(uiState: AudioCaptureUiState) {
    val diag = uiState.diagnostics

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14171E)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E3846))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                text = "HARDWARE & ENGINE DIAGNOSTICS",
                color = Color(0xFFECEFF1),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            DiagnosticRow("Active Audio Source", diag.activeAudioSource)
            DiagnosticRow("Requested Input Device", diag.requestedDeviceName)
            DiagnosticRow("Actual Routed Device", "${diag.actualRoutedDeviceName} (${diag.actualRoutedDeviceType})")
            if (diag.isRoutingMismatch) {
                DiagnosticRow("Routing Status", "MISMATCH (OS Overrode Routing)")
            }
            if (diag.isBluetoothBandwidthLimited) {
                DiagnosticRow("Bandwidth Profile", "LIMITED (SCO 8-16 kHz)")
            }
            DiagnosticRow(
                "Sample Rate",
                if (diag.actualSampleRate > 0) "${diag.actualSampleRate} Hz" else "Not started"
            )
            DiagnosticRow(
                "Window & 50% Overlap",
                "${diag.windowSize} samples (Hop: ${diag.hopSize} samples)"
            )
            DiagnosticRow(
                "SurfaceView Render Thread",
                String.format(Locale.US, "%.1f FPS (Draw: %d µs)", diag.renderFps, diag.renderDrawTimeUs)
            )
            DiagnosticRow(
                "Audio Capture Thread",
                String.format(Locale.US, "%.1f FPS (FFT: %d µs)", diag.fps, diag.fftExecutionTimeUs)
            )
            DiagnosticRow(
                "Hardware Buffer Size",
                "${diag.bufferSizeInBytes} bytes (Min Floor: ${diag.minBufferSizeInBytes} bytes)"
            )
            DiagnosticRow(
                "Consecutive Zero Reads",
                "${diag.consecutiveZeroReads} (Threshold: 10)"
            )
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF90A4AE),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = Color(0xFFECEFF1),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ControlsSection(
    status: CaptureStatus,
    hasPermission: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onResetPeak: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (status == CaptureStatus.RUNNING) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC62828),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("STOP CAPTURE", color = Color.White, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onStart,
                enabled = hasPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("START CAPTURE", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        OutlinedButton(
            onClick = onResetPeak,
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCFD8DC)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF455A64)),
            modifier = Modifier.height(46.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("RESET PEAK", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MicNoiseFloorNoticeCard() {
    Surface(
        color = Color(0xFF101720),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2D3D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = Color(0xFF4DD0E1),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Hardware note: Internal mic 1/f self-noise naturally elevates readings below ~200Hz. For more accurate sub-bass measurements, use an external USB measurement microphone.",
                color = Color(0xFF90A4AE),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun SignalGeneratorCard(
    state: SignalGeneratorState,
    canUseSweep: Boolean,
    onRequestPro: () -> Unit,
    onStartPinkNoise: () -> Unit,
    onStartSineSweep: () -> Unit,
    onStop: () -> Unit,
    onSetVolume: (Float) -> Unit,
    onSetDuration: (Float) -> Unit,
    onSetRange: (SweepRangePreset) -> Unit,
    onToggleLooping: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14171E)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E3846))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = if (state.isPlaying) Color(0xFF00E676) else Color(0xFF78909C),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TEST SIGNAL GENERATOR",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Surface(
                    color = if (state.isPlaying) Color(0xFF1B5E20) else Color(0xFF263238),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (state.isPlaying) Color(0xFF81C784) else Color(0xFF546E7A)
                    )
                ) {
                    Text(
                        text = if (state.isPlaying) state.mode.name else "MUTED",
                        color = if (state.isPlaying) Color(0xFF81C784) else Color(0xFF90A4AE),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // Error banner if any
            if (state.errorMessage != null) {
                Surface(
                    color = Color(0xFFB71C1C).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = state.errorMessage,
                            color = Color(0xFFFF8A80),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (!canUseSweep) {
                ProFeatureNotice(ProFeature.SINE_SWEEP, onRequestPro)
            }

            // Signal Type Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pink Noise Button
                Button(
                    onClick = onStartPinkNoise,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.mode == GeneratorMode.PINK_NOISE && state.isPlaying)
                            Color(0xFFE91E63) else Color(0xFF1E232E),
                        contentColor = if (state.mode == GeneratorMode.PINK_NOISE && state.isPlaying)
                            Color.White else Color(0xFFB0BEC5)
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Text(
                        text = "PINK NOISE",
                        color = if (state.mode == GeneratorMode.PINK_NOISE && state.isPlaying)
                            Color.White else Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Sine Sweep Button
                Button(
                    onClick = onStartSineSweep,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.mode == GeneratorMode.SINE_SWEEP && state.isPlaying)
                            Color(0xFF0288D1) else Color(0xFF1E232E),
                        contentColor = if (state.mode == GeneratorMode.SINE_SWEEP && state.isPlaying)
                            Color.White else Color(0xFFB0BEC5)
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Text(
                        text = if (canUseSweep) "SINE SWEEP" else "SWEEP · PRO",
                        color = if (state.mode == GeneratorMode.SINE_SWEEP && state.isPlaying)
                            Color.White else Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Stop / Mute Button
                Button(
                    onClick = onStop,
                    enabled = state.isPlaying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB71C1C),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF1E232E),
                        disabledContentColor = Color(0xFF546E7A)
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.width(68.dp).height(38.dp)
                ) {
                    Text(
                        text = "STOP",
                        color = if (state.isPlaying) Color.White else Color(0xFF546E7A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Output Volume Slider Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(Locale.US, "LEVEL: %3.0f dBFS", state.outputLevelDb),
                    color = Color(0xFF90A4AE),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.wrapContentWidth()
                )
                Slider(
                    value = state.outputLevelDb,
                    onValueChange = onSetVolume,
                    valueRange = -30f..0f,
                    steps = 29,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E676),
                        activeTrackColor = Color(0xFF00E676),
                        inactiveTrackColor = Color(0xFF263238)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            // Sweep Configuration Controls (Only when in SINE_SWEEP mode or idle)
            if (state.mode == GeneratorMode.SINE_SWEEP || !canUseSweep) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0A0C10))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Duration Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DURATION:",
                            color = Color(0xFF78909C),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(2.5f, 5.0f, 10.0f).forEach { sec ->
                                SelectChip(
                                    label = "${sec}s",
                                    isSelected = state.sweepDurationSec == sec,
                                    onClick = { onSetDuration(sec) }
                                )
                            }
                        }
                    }

                    // Sweep Range Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RANGE:",
                            color = Color(0xFF78909C),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SweepRangePreset.values().forEach { preset ->
                                SelectChip(
                                    label = when (preset) {
                                        SweepRangePreset.FULL_SPECTRUM -> "FULL"
                                        SweepRangePreset.SUB_BASS -> "BASS"
                                        SweepRangePreset.MID_HIGH -> "MID/HIGH"
                                    },
                                    isSelected = state.sweepRange == preset,
                                    onClick = { onSetRange(preset) }
                                )
                            }
                        }
                    }

                    // Loop Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PLAYBACK:",
                            color = Color(0xFF78909C),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SelectChip(
                                label = "LOOP",
                                isSelected = state.isSweepLooping,
                                onClick = { onToggleLooping(true) }
                            )
                            SelectChip(
                                label = "SINGLE",
                                isSelected = !state.isSweepLooping,
                                onClick = { onToggleLooping(false) }
                            )
                        }
                    }

                    // Sweep Live Progress & Frequency Telemetry
                    if (state.isPlaying && state.mode == GeneratorMode.SINE_SWEEP) {
                        Spacer(modifier = Modifier.height(2.dp))
                        LinearProgressIndicator(
                            progress = { state.currentSweepProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF0288D1),
                            trackColor = Color(0xFF1E2836)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = String.format(Locale.US, "FREQ: %.0f Hz", state.currentSweepFreqHz),
                                color = Color(0xFF81D4FA),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = String.format(Locale.US, "PROGRESS: %.0f%%", state.currentSweepProgress * 100f),
                                color = Color(0xFF81D4FA),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SAMPLE_UMIK1_CAL = """# miniDSP UMIK-1 Sample 90-degree Calibration File
10.000   -1.85
20.000   -0.82
31.500   -0.45
50.000   -0.12
80.000    0.00
100.000   0.05
200.000   0.00
500.000   0.00
1000.000  0.00
2000.000  0.10
4000.000  0.35
8000.000  0.95
10000.000 1.35
16000.000 2.40
20000.000 3.80
"""

@Composable
fun CalibrationControlsCard(
    uiState: AudioCaptureUiState,
    canMicCalibrate: Boolean,
    canSplCalibrate: Boolean,
    canCorrectNoise: Boolean,
    canUseAdvancedTargets: Boolean,
    onRequestPro: (ProFeature) -> Unit,
    onLoadMicCurve: (name: String, content: String) -> Result<MicCalibrationCurve>,
    onSetMicCurveActive: (Boolean) -> Unit,
    onClearMicCurve: () -> Unit,
    onCalibrateSpl: (Float) -> Unit,
    onSetSplMode: (Boolean) -> Unit,
    onSetTargetCurve: (TargetCurve?) -> Unit,
    onSetTargetCurveActive: (Boolean) -> Unit,
    onStartNoiseCapture: () -> Unit,
    onSetNoiseSubtractionActive: (Boolean) -> Unit,
    onClearNoiseFloor: () -> Unit
) {
    val calib = uiState.calibration
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) } // 0: MIC CAL, 1: SPL REF, 2: TARGET, 3: NOISE
    var showPasteDialog by remember { mutableStateOf(false) }
    var showSplDialog by remember { mutableStateOf(false) }
    var pasteNameInput by remember { mutableStateOf("Custom Mic Cal") }
    var pasteContentInput by remember { mutableStateOf("") }
    var parseErrorMsg by remember { mutableStateOf<String?>(null) }
    var splTargetInput by remember { mutableStateOf("85.0") }

    LaunchedEffect(canMicCalibrate, canSplCalibrate) {
        if (!canMicCalibrate) showPasteDialog = false
        if (!canSplCalibrate) showSplDialog = false
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && !canMicCalibrate) {
            onRequestPro(ProFeature.MIC_CALIBRATION)
        } else if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                }
                if (content != null) {
                    val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported Mic Cal"
                    val result = onLoadMicCurve(displayName, content)
                    if (result.isFailure) {
                        parseErrorMsg = result.exceptionOrNull()?.message ?: "Failed to parse calibration file"
                    } else {
                        parseErrorMsg = null
                    }
                }
            } catch (e: Exception) {
                parseErrorMsg = "Error opening file: ${e.localizedMessage}"
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Card Title Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "CALIBRATION & TARGET CURVES",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Explicit Calibration Status Tag (matching RTA Pro requirement)
                Text(
                    text = calib.micCurveStatusText,
                    color = if (calib.isMicCurveActive) Color(0xFF00E676) else Color(0xFF78909C),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (calib.isMicCurveActive) Color(0xFF1B5E20) else Color(0xFF263238))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Tab Row: MIC CAL | SPL REF | TARGET | NOISE FLOOR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0D1117))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val tabs = listOf("MIC CAL", "SPL REF", "TARGET", "NOISE FLOOR")
                tabs.forEachIndexed { idx, title ->
                    ModeTab(
                        title = title,
                        isSelected = selectedTab == idx,
                        onClick = { selectedTab = idx }
                    )
                }
            }

            val lockedFeature = when (selectedTab) {
                0 -> if (canMicCalibrate) null else ProFeature.MIC_CALIBRATION
                1 -> if (canSplCalibrate) null else ProFeature.SPL_CALIBRATION
                2 -> if (canUseAdvancedTargets) null else ProFeature.ADVANCED_TARGETS
                else -> if (canCorrectNoise) null else ProFeature.NOISE_FLOOR
            }
            lockedFeature?.let { feature ->
                ProFeatureNotice(feature, onLearnMore = { onRequestPro(feature) })
            }

            // Tab 0: Microphone Frequency Response Calibration
            if (selectedTab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0D1117))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "MIC CORRECTION FILTER",
                                color = Color(0xFFECEFF1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (calib.micCurve != null) {
                                    "${calib.micCurve.points.size} points (${calib.micCurve.points.first().freqHz.toInt()}Hz - ${calib.micCurve.points.last().freqHz.toInt()}Hz)"
                                } else {
                                    "No calibration profile loaded"
                                },
                                color = Color(0xFF90A4AE),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Switch(
                            checked = calib.isMicCurveActive,
                            onCheckedChange = { onSetMicCurveActive(it) },
                            enabled = calib.micCurve != null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E676),
                                checkedTrackColor = Color(0xFF2E7D32),
                                checkedBorderColor = Color(0xFF00E676),
                                uncheckedThumbColor = Color(0xFFCFD8DC),
                                uncheckedTrackColor = Color(0xFF37474F),
                                uncheckedBorderColor = Color(0xFF78909C),
                                disabledCheckedThumbColor = Color(0xFF81C784),
                                disabledCheckedTrackColor = Color(0xFF1B5E20),
                                disabledUncheckedThumbColor = Color(0xFF546E7A),
                                disabledUncheckedTrackColor = Color(0xFF263238),
                                disabledUncheckedBorderColor = Color(0xFF37474F)
                            )
                        )
                    }

                    // Action Buttons for Mic Cal
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                if (canMicCalibrate) {
                                    val res = onLoadMicCurve("miniDSP UMIK-1 (Demo)", SAMPLE_UMIK1_CAL)
                                    if (res.isFailure) parseErrorMsg = res.exceptionOrNull()?.message
                                } else onRequestPro(ProFeature.MIC_CALIBRATION)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E3A5F),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text("LOAD UMIK-1", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        Button(
                            onClick = {
                                if (canMicCalibrate) filePickerLauncher.launch("*/*")
                                else onRequestPro(ProFeature.MIC_CALIBRATION)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E3B4E),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text("IMPORT FILE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        Button(
                            onClick = {
                                if (canMicCalibrate) showPasteDialog = true
                                else onRequestPro(ProFeature.MIC_CALIBRATION)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E3B4E),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text("PASTE CSV", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        if (calib.micCurve != null) {
                            Button(
                                onClick = { onClearMicCurve() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4A148C),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.weight(0.7f).height(32.dp)
                            ) {
                                Text("CLEAR", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    if (parseErrorMsg != null) {
                        Text(
                            text = "Error: $parseErrorMsg",
                            color = Color(0xFFFF5252),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Tab 1: dB SPL Reference Calibration
            if (selectedTab == 1) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0D1117))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "SPL REFERENCE CALIBRATION",
                                color = Color(0xFFECEFF1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Offset: +${String.format(Locale.US, "%.1f", calib.splCalibration.splOffsetDb)} dB (${if (calib.splCalibration.isSplMode) "dBSPL Mode" else "dBFS Mode"})",
                                color = Color(0xFF00E5FF),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SelectChip(
                                label = "dBFS",
                                isSelected = !calib.splCalibration.isSplMode,
                                onClick = { onSetSplMode(false) }
                            )
                            SelectChip(
                                label = if (canSplCalibrate) "dB SPL" else "SPL · PRO",
                                isSelected = calib.splCalibration.isSplMode,
                                onClick = { onSetSplMode(true) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                if (canSplCalibrate) showSplDialog = true
                                else onRequestPro(ProFeature.SPL_CALIBRATION)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00695C),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text("CALIBRATE TO REF METER", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        Button(
                            onClick = { onCalibrateSpl(94.0f) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF263238),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(0.5f).height(32.dp)
                        ) {
                            Text("94 dB", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }

                        Button(
                            onClick = { onCalibrateSpl(85.0f) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF263238),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(0.5f).height(32.dp)
                        ) {
                            Text("85 dB", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Tab 2: Target Curve Overlay & Delta Tracking
            if (selectedTab == 2) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0D1117))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "TARGET RESPONSE OVERLAY",
                                color = Color(0xFFECEFF1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = calib.targetCurve?.name ?: "None",
                                color = Color(0xFFFFD54F),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Switch(
                            checked = calib.isTargetCurveActive,
                            onCheckedChange = { onSetTargetCurveActive(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFFD54F),
                                checkedTrackColor = Color(0xFFB58900),
                                checkedBorderColor = Color(0xFFFFD54F),
                                uncheckedThumbColor = Color(0xFFCFD8DC),
                                uncheckedTrackColor = Color(0xFF37474F),
                                uncheckedBorderColor = Color(0xFF78909C)
                            )
                        )
                    }

                    // Preset Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SelectChip(
                            label = if (canUseAdvancedTargets) "FLAT (0 dB)" else "FLAT · PRO",
                            isSelected = calib.targetCurve?.id == TargetCurve.FLAT.id,
                            onClick = { onSetTargetCurve(TargetCurve.FLAT) }
                        )
                        SelectChip(
                            label = "HARMAN (+9dB)",
                            isSelected = calib.targetCurve?.id == TargetCurve.HARMAN_CAR.id,
                            onClick = { onSetTargetCurve(TargetCurve.HARMAN_CAR) }
                        )
                        SelectChip(
                            label = if (canUseAdvancedTargets) "AUDIO CONTROL (+6dB)" else "AUDIOCONTROL · PRO",
                            isSelected = calib.targetCurve?.id == TargetCurve.AUDIO_CONTROL_CAR.id,
                            onClick = { onSetTargetCurve(TargetCurve.AUDIO_CONTROL_CAR) }
                        )
                    }

                    // Real-Time Delta Readout
                    val deltas = uiState.targetDeltas31Db
                    if (calib.isTargetCurveActive && deltas != null) {
                        val onTargetCount = deltas.count { kotlin.math.abs(it) <= 1.5f }
                        val hotCount = deltas.count { it > 1.5f }
                        val cutCount = deltas.count { it < -1.5f }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "ON TARGET (±1.5dB): $onTargetCount",
                                color = Color(0xFF00E676),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "HOT: $hotCount",
                                color = Color(0xFFFF5252),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "CUT: $cutCount",
                                color = Color(0xFF40C4FF),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Tab 3: Ambient Noise Floor Capture & Power Subtraction
            if (selectedTab == 3) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0D1117))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "POWER-DOMAIN NOISE SUBTRACTION",
                                color = Color(0xFFECEFF1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (calib.noiseFloor.isCaptured) "Noise profile active (31 bands)" else "No background noise captured",
                                color = if (calib.noiseFloor.isCaptured) Color(0xFF00E676) else Color(0xFF90A4AE),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Switch(
                            checked = calib.isNoiseSubtractionActive,
                            onCheckedChange = { onSetNoiseSubtractionActive(it) },
                            enabled = calib.noiseFloor.isCaptured,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E676),
                                checkedTrackColor = Color(0xFF2E7D32),
                                checkedBorderColor = Color(0xFF00E676),
                                uncheckedThumbColor = Color(0xFFCFD8DC),
                                uncheckedTrackColor = Color(0xFF37474F),
                                uncheckedBorderColor = Color(0xFF78909C),
                                disabledCheckedThumbColor = Color(0xFF81C784),
                                disabledCheckedTrackColor = Color(0xFF1B5E20),
                                disabledUncheckedThumbColor = Color(0xFF546E7A),
                                disabledUncheckedTrackColor = Color(0xFF263238),
                                disabledUncheckedBorderColor = Color(0xFF37474F)
                            )
                        )
                    }

                    if (calib.isCapturingNoiseFloor) {
                        LinearProgressIndicator(
                            progress = { calib.noiseCaptureProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFFFFB300),
                            trackColor = Color(0xFF263238)
                        )
                        Text(
                            text = "CAPTURING BACKGROUND SILENCE... KEEP AUDIO MUTED! (${String.format(Locale.US, "%.0f%%", calib.noiseCaptureProgress * 100f)})",
                            color = Color(0xFFFFD54F),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = { onStartNoiseCapture() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFB71C1C),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Text(if (canCorrectNoise) "CAPTURE NOISE (2s)" else "CAPTURE NOISE · PRO", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }

                            if (calib.noiseFloor.isCaptured) {
                                Button(
                                    onClick = { onClearNoiseFloor() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF37474F),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.weight(0.5f).height(32.dp)
                                ) {
                                    Text("CLEAR", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    Text(
                        text = "Formula: 10*log10(10^(S/10) - 10^(N/10)). Live bands within 6 dB of noise floor are dimmed and marked as unreliable.",
                        color = Color(0xFF78909C),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    // Paste Custom Mic Cal Dialog
    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = {
                Text(
                    text = "Import Calibration Data",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Paste frequency (Hz) and dB offset pairs (CSV, TSV, or whitespace delimited):",
                        fontSize = 11.sp,
                        color = Color(0xFFB0BEC5)
                    )
                    OutlinedTextField(
                        value = pasteNameInput,
                        onValueChange = { pasteNameInput = it },
                        label = { Text("Curve Name", fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pasteContentInput,
                        onValueChange = { pasteContentInput = it },
                        label = { Text("Paste Data (e.g. 1000  0.0)", fontSize = 10.sp) },
                        placeholder = { Text("20.0  1.5\n100.0  0.2\n1000.0  0.0\n10000.0  -1.2") },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val res = onLoadMicCurve(pasteNameInput, pasteContentInput)
                        if (res.isSuccess) {
                            showPasteDialog = false
                            parseErrorMsg = null
                        } else {
                            parseErrorMsg = res.exceptionOrNull()?.message
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E676),
                        contentColor = Color.Black
                    )
                ) {
                    Text("APPLY", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text("CANCEL", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    // Calibrate dB SPL Reference Dialog
    if (showSplDialog) {
        AlertDialog(
            onDismissRequest = { showSplDialog = false },
            title = {
                Text(
                    text = "dB SPL Reference Calibration",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Play a steady reference tone (e.g., 1 kHz sine or pink noise) and measure with a handheld SPL meter.",
                        fontSize = 11.sp,
                        color = Color(0xFFB0BEC5)
                    )
                    Text(
                        text = "Current Measured RMS: ${String.format(Locale.US, "%.1f", uiState.rmsDb)} dBFS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF),
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedTextField(
                        value = splTargetInput,
                        onValueChange = { splTargetInput = it },
                        label = { Text("Known Reference SPL (dBSPL)", fontSize = 10.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetSpl = splTargetInput.toFloatOrNull() ?: 85.0f
                        onCalibrateSpl(targetSpl)
                        showSplDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E676),
                        contentColor = Color.Black
                    )
                ) {
                    Text("CALIBRATE & ENABLE", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplDialog = false }) {
                    Text("CANCEL", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

