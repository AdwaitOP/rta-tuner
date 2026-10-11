package com.rtatuner.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtatuner.app.BuildConfig
import com.rtatuner.app.core.entitlement.EntitlementState
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.ProFeature

/** A contextual, informational upgrade surface until verified billing ownership is available. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProUpgradeSheet(
    entitlement: EntitlementState,
    feature: ProFeature,
    message: String,
    onDismiss: () -> Unit,
    onSimulateTier: (EntitlementTier) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF10151D),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CABINRTA PRO", color = Color(0xFF00E5FF), fontSize = 22.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Precision tools. More room to compare.", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("CLOSE", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }

            Surface(
                color = Color(0xFF172636), shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF31506C))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(16.dp))
                        Text(feature.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(feature.benefit, color = Color(0xFFCFD8DC), fontSize = 12.sp, lineHeight = 18.sp)
                    if (message != feature.benefit) {
                        Text(message, color = Color(0xFFFFD54F), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YOUR ANALYZER STAYS COMPLETE", color = Color(0xFF90A4AE), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("FEATURE", Modifier.weight(1f), color = Color(0xFF78909C), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("FREE", Modifier.width(56.dp), color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("PRO", Modifier.width(56.dp), color = Color(0xFF00E5FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                listOf(
                    "Complete live 31-band RTA",
                    "Raw FFT · 1024 / 2048-point windows",
                    "All smoothing & graph controls",
                    "Fullscreen analyzer",
                    "Peak / RMS meters",
                    "All microphone selection & routing",
                    "Pink noise generator",
                    "Harman car target curve",
                    "Session notes, viewing & deletion",
                    "PNG screenshot sharing"
                ).forEach { ComparisonRow(it, "Included", "Included") }
                ComparisonRow("4096-point FFT window", "—", "Included")
                ComparisonRow("Frozen spectrum overlays", "1", "Unlimited")
                ComparisonRow("Saved tuning sessions", "1", "Unlimited")
                ComparisonRow("Mic calibration import, activation & correction", "—", "Included")
                ComparisonRow("SPL reference calibration & SPL mode", "—", "Included")
                ComparisonRow("Noise floor capture & subtraction", "—", "Included")
                ComparisonRow("Sine sweep & configuration controls", "—", "Included")
                ComparisonRow("Flat & AudioControl target presets", "—", "Included")
                ComparisonRow("Live, snapshot & session CSV exports", "—", "Included")
            }

            Surface(color = Color(0xFF1D222D), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFF384553))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("ONE-TIME LIFETIME UNLOCK · COMING SOON", color = Color(0xFFFFD54F), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Purchases will be available in a future release. No purchase can be made here yet.",
                        color = Color(0xFFB0BEC5), fontSize = 12.sp, lineHeight = 18.sp)
                }
            }

            if (BuildConfig.DEBUG) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = Color(0xFF303B49))
                    Text("DEBUG ACCESS SIMULATOR", color = Color(0xFF90A4AE), fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Temporary local simulation: ${if (entitlement.isPro) "Pro" else "Free"}. This does not record a purchase or grant ownership. The simulator is unavailable in release builds.",
                        color = Color(0xFF78909C), fontSize = 11.sp, lineHeight = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onSimulateTier(EntitlementTier.FREE) }, modifier = Modifier.weight(1f)) {
                            Text("SIMULATE FREE", color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        OutlinedButton(onClick = { onSimulateTier(EntitlementTier.PRO_LIFETIME) }, modifier = Modifier.weight(1f)) {
                            Text("SIMULATE PRO", color = Color(0xFF00E5FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("BACK TO ANALYZER", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ComparisonRow(label: String, free: String, pro: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = Color(0xFF232E3B))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Color(0xFFCFD8DC), fontSize = 11.sp, lineHeight = 16.sp)
            Text(free, Modifier.width(56.dp), color = Color(0xFF90A4AE), fontSize = 10.sp)
            Text(pro, Modifier.width(56.dp), color = Color(0xFF00E5FF), fontSize = 10.sp)
        }
    }
}

/** Visible, clickable explanation used next to premium controls. */
@Composable
fun ProFeatureNotice(feature: ProFeature, onLearnMore: () -> Unit) {
    Surface(color = Color(0xFF1B2330), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Color(0xFF354559))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(13.dp))
                Text("PRO · ${feature.title}", modifier = Modifier.weight(1f), color = Color(0xFFFFD54F), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                TextButton(onClick = onLearnMore) { Text("DETAILS", color = Color(0xFF00E5FF), fontSize = 10.sp) }
            }
            Text(feature.benefit, color = Color(0xFF90A4AE), fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
