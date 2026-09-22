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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.rtatuner.app.core.audio.AudioCaptureUiState
import com.rtatuner.app.core.audio.CaptureStatus
import com.rtatuner.app.core.audio.FftDisplayMode
import com.rtatuner.app.ui.components.LevelMeter
import com.rtatuner.app.ui.components.surface.RtaSurfaceView
import java.util.Locale

@Composable
fun DebugScreen(
    viewModel: AudioCaptureViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF0F1115)
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
                    onRetry = { viewModel.retryCapture() }
                )
            }

            // Stage 3 SurfaceView Spectrum Card
            SurfaceSpectrumCard(
                uiState = uiState,
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

            // Broadband Level Meters (Peak / RMS / Clip)
            LiveMetersCard(
                peakDb = uiState.peakDb,
                peakHoldDb = uiState.peakHoldDb,
                rmsDb = uiState.rmsDb,
                isClipping = uiState.isClipping,
                isLive = uiState.status == CaptureStatus.RUNNING,
                onResetPeak = { viewModel.resetPeakHold() }
            )

            // Hardware & Engine Diagnostics Card
            HardwareDiagnosticsCard(uiState = uiState)

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
                    text = "RTA TUNER",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "STAGE 3 • SURFACEVIEW 60FPS DISPLAY",
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
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SurfaceSpectrumCard(
    uiState: AudioCaptureUiState,
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
            // Row 1: Mode Toggle & Window Size Chips
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
            }

            // Row 2: EMA Averaging Presets & dB Range Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // EMA Averaging Selector
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AVG:",
                        color = Color(0xFF78909C),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0C0E12))
                            .border(1.dp, Color(0xFF242C38), RoundedCornerShape(6.dp))
                            .padding(2.dp)
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
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0C0E12))
                            .border(1.dp, Color(0xFF242C38), RoundedCornerShape(6.dp))
                            .padding(2.dp)
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
                        }
                    },
                    update = { surfaceView ->
                        surfaceView.displayConfig = config
                        surfaceView.updateSpectralData(
                            octave31Db = uiState.octave31Db,
                            rawFftDb = uiState.rawFftDb,
                            binResolutionHz = binResolution
                        )
                    }
                )
            }

            // Telemetry & Advanced Settings Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = String.format(Locale.US, "RENDER: %.0f FPS (%d µs)", diag.renderFps, diag.renderDrawTimeUs),
                        color = Color(0xFF00E676),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(Locale.US, "AUDIO: %.0f FPS (%d µs)", diag.fps, diag.fftExecutionTimeUs),
                        color = Color(0xFF81D4FA),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Toggle for Peak-Hold Ballistics Drawer
                Row(
                    modifier = Modifier
                        .clickable { showAdvancedSettings = !showAdvancedSettings }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = if (showAdvancedSettings) Color(0xFF00E676) else Color(0xFF78909C),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showAdvancedSettings) "HIDE SETTINGS" else "BALLISTICS",
                        color = if (showAdvancedSettings) Color(0xFF00E676) else Color(0xFF78909C),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
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
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) Color(0xFF00E676) else Color(0xFF78909C),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
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
            fontFamily = FontFamily.Monospace
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
    onRetry: () -> Unit
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

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("RESTART CAPTURE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                text = "RTA Tuner requires RECORD_AUDIO to capture raw acoustic measurements directly from the device's microphone hardware.",
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp
            )
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
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
    onResetPeak: () -> Unit
) {
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
                    text = "BROADBAND LEVEL METERS",
                    color = Color(0xFFECEFF1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                ClipIndicatorBadge(isClipping = isClipping)
            }

            LevelMeter(
                label = "PEAK LEVEL (FAST)",
                levelDb = if (isLive) peakDb else -100f,
                peakHoldDb = if (isLive) peakHoldDb else -100f
            )

            LevelMeter(
                label = "RMS LEVEL (AVERAGE)",
                levelDb = if (isLive) rmsDb else -100f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricBox(
                    label = "LIVE RMS",
                    value = String.format(Locale.US, "%.1f", if (isLive) rmsDb else -100f),
                    unit = "dBFS",
                    accent = Color(0xFF00E676)
                )
                MetricBox(
                    label = "LIVE PEAK",
                    value = String.format(Locale.US, "%.1f", if (isLive) peakDb else -100f),
                    unit = "dBFS",
                    accent = Color(0xFFFFEB3B)
                )
                MetricBox(
                    label = "PEAK HOLD",
                    value = String.format(Locale.US, "%.1f", if (isLive) peakHoldDb else -100f),
                    unit = "dBFS",
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
            fontFamily = FontFamily.Monospace
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
                text = "HARDWARE & PERFORMANCE TELEMETRY",
                color = Color(0xFFECEFF1),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            DiagnosticRow("Active Audio Source", diag.activeAudioSource)
            DiagnosticRow("Input Device Name", diag.inputDeviceName)
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
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("STOP CAPTURE", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onStart,
                enabled = hasPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
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
