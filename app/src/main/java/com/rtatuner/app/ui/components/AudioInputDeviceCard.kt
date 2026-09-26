package com.rtatuner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtatuner.app.core.audio.AudioCaptureUiState
import com.rtatuner.app.core.audio.AudioInputDeviceInfo
import com.rtatuner.app.core.audio.DeviceCategory

/**
 * UI Component for Stage 7: Input Device Enumeration, Routing Status,
 * Sample Rate Renegotiation, and Hotplug Detection.
 */
@Composable
fun AudioInputDeviceCard(
    uiState: AudioCaptureUiState,
    onSelectDevice: (Int?) -> Unit,
    onRefreshDevices: () -> Unit
) {
    val diag = uiState.diagnostics
    val routedId = uiState.routedDeviceId
    val selectedId = uiState.selectedDeviceId

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14171E)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E3846))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Title & Refresh Button
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
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "AUDIO INPUT HARDWARE",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                }

                IconButton(
                    onClick = onRefreshDevices,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Devices",
                        tint = Color(0xFF78909C),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Active Routed Device Summary Badge
            ActiveRoutedDeviceBadge(
                actualDeviceName = diag.actualRoutedDeviceName,
                deviceType = diag.actualRoutedDeviceType,
                isUsb = diag.isUsbDevice,
                isMismatch = uiState.isRoutingMismatch
            )

            // Routing Mismatch Alert
            if (uiState.isRoutingMismatch && uiState.routingMismatchMessage != null) {
                RoutingMismatchWarning(message = uiState.routingMismatchMessage)
            }

            // Bluetooth Bandwidth Limitation Warning
            if (uiState.isBluetoothBandwidthLimited && uiState.bluetoothWarningText != null) {
                BluetoothBandwidthWarning(warningText = uiState.bluetoothWarningText)
            }

            Text(
                text = "AVAILABLE INPUT DEVICES",
                color = Color(0xFF78909C),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )

            // System Auto-Routing Option
            DeviceItemRow(
                title = "System Auto-Routing (Default)",
                subtitle = "OS dynamically routes to primary mic or connected accessories",
                ratesSummary = "Auto Hardware Rate",
                category = DeviceCategory.BUILT_IN,
                isSelected = selectedId == null,
                isCurrentlyRouted = selectedId == null && (routedId == null || uiState.availableInputDevices.none { it.id == routedId && !it.isBuiltIn }),
                onClick = { onSelectDevice(null) }
            )

            // Physical / Enumerated Input Devices List
            if (uiState.availableInputDevices.isEmpty()) {
                Text(
                    text = "No hardware input devices enumerated. Built-in mic active.",
                    color = Color(0xFF546E7A),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                uiState.availableInputDevices.forEach { device ->
                    DeviceItemRow(
                        title = device.name,
                        subtitle = device.typeName,
                        ratesSummary = device.ratesSummary,
                        category = device.category,
                        isSelected = selectedId == device.id,
                        isCurrentlyRouted = routedId == device.id,
                        isBandwidthLimited = device.isBandwidthLimited,
                        onClick = { onSelectDevice(device.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveRoutedDeviceBadge(
    actualDeviceName: String,
    deviceType: String,
    isUsb: Boolean,
    isMismatch: Boolean
) {
    Surface(
        color = if (isMismatch) Color(0xFF332008) else Color(0xFF0C161C),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isMismatch) Color(0xFFFFB300) else Color(0xFF00E5FF).copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ACTIVE ROUTED INPUT",
                    color = if (isMismatch) Color(0xFFFFB300) else Color(0xFF80DEEA),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = actualDeviceName,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Surface(
                color = if (isUsb) Color(0xFF004D40) else Color(0xFF1E2836),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = deviceType.uppercase(),
                    color = if (isUsb) Color(0xFF00E5FF) else Color(0xFF90A4AE),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun RoutingMismatchWarning(message: String) {
    Surface(
        color = Color(0xFF2C1E0A),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB300)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFFB300),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "HARDWARE ROUTING MISMATCH",
                    color = Color(0xFFFFB300),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message,
                    color = Color(0xFFFFE082),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Android audio HAL did not honor preferred device routing. Check cable connection or reconnect mic.",
                    color = Color(0xFFB0BEC5),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun BluetoothBandwidthWarning(warningText: String) {
    Surface(
        color = Color(0xFF2B1D14),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9100)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9100),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "BLUETOOTH VOICE BANDWIDTH LIMITATION",
                    color = Color(0xFFFF9100),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = warningText,
                    color = Color(0xFFFFCC80),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun DeviceItemRow(
    title: String,
    subtitle: String,
    ratesSummary: String,
    category: DeviceCategory,
    isSelected: Boolean,
    isCurrentlyRouted: Boolean,
    isBandwidthLimited: Boolean = false,
    onClick: () -> Unit
) {
    val borderColor = when {
        isCurrentlyRouted -> Color(0xFF00E5FF).copy(alpha = 0.7f)
        isSelected -> Color(0xFF00E676).copy(alpha = 0.5f)
        else -> Color(0xFF242C38)
    }

    val backgroundColor = when {
        isCurrentlyRouted -> Color(0xFF0F1A24)
        isSelected -> Color(0xFF131F1C)
        else -> Color(0xFF0D1117)
    }

    val icon: ImageVector = when (category) {
        DeviceCategory.USB -> Icons.Default.Usb
        DeviceCategory.WIRED_3_5MM -> Icons.Default.Headphones
        DeviceCategory.BLUETOOTH -> Icons.AutoMirrored.Filled.VolumeUp
        else -> Icons.Default.Mic
    }

    val iconTint = when (category) {
        DeviceCategory.USB -> Color(0xFF00E5FF)
        DeviceCategory.WIRED_3_5MM -> Color(0xFF00E676)
        DeviceCategory.BLUETOOTH -> Color(0xFFFF9100)
        else -> Color(0xFF90A4AE)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) Color(0xFF00E676) else Color(0xFF546E7A),
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (isCurrentlyRouted) {
                        Surface(
                            color = Color(0xFF004D40),
                            shape = RoundedCornerShape(3.dp)
                        ) {
                            Text(
                                text = "ROUTED",
                                color = Color(0xFF00E5FF),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (isBandwidthLimited) {
                        Surface(
                            color = Color(0xFF4E2600),
                            shape = RoundedCornerShape(3.dp)
                        ) {
                            Text(
                                text = "SCO LIMITED",
                                color = Color(0xFFFF9100),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "$subtitle • $ratesSummary",
                    color = Color(0xFF90A4AE),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
