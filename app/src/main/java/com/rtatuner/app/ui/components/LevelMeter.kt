package com.rtatuner.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Professional audio dBFS level meter with gradient ballistics and peak-hold tick.
 */
@Composable
fun LevelMeter(
    label: String,
    levelDb: Float,
    peakHoldDb: Float? = null,
    minDb: Float = -90.0f,
    maxDb: Float = 0.0f,
    isSplMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val safeMinDb = if (minDb.isNaN() || minDb.isInfinite()) -90.0f else minDb
    val safeMaxDb = if (maxDb.isNaN() || maxDb.isInfinite()) 0.0f else maxDb
    val span = kotlin.math.max(1.0f, safeMaxDb - safeMinDb)

    // Clamps safely to floor (silence) on NaN/Infinity rather than ceiling
    val safeLevel = if (levelDb.isNaN() || levelDb.isInfinite()) safeMinDb else levelDb.coerceIn(safeMinDb, safeMaxDb)
    val fraction = ((safeLevel - safeMinDb) / span).coerceIn(0f, 1f)

    val safePeakHold = if (peakHoldDb == null || peakHoldDb.isNaN() || peakHoldDb.isInfinite()) {
        null
    } else {
        peakHoldDb.coerceIn(safeMinDb, safeMaxDb)
    }
    val peakHoldFraction = safePeakHold?.let { ((it - safeMinDb) / span).coerceIn(0f, 1f) }

    val unitStr = if (isSplMode) "dBSPL" else "dBFS"

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = String.format(Locale.US, "%5.1f %s", safeLevel, unitStr),
                color = if (safeLevel >= safeMaxDb - 3f) Color(0xFFFF5252) else Color(0xFFECEFF1),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Level meter bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1E1E24))
                .border(1.dp, Color(0xFF37474F), RoundedCornerShape(4.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                val barWidth = size.width * fraction
                val barHeight = size.height

                // Meter gradient: Green -> Yellow (-18dB) -> Red (-3dB)
                val meterGradient = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF00E676),      // -90 dBFS Green
                        0.80f to Color(0xFFFFEB3B),     // -18 dBFS Yellow
                        0.966f to Color(0xFFFF5722),    // -3 dBFS Orange
                        1.0f to Color(0xFFFF1744)       // 0 dBFS Red
                    ),
                    startX = 0f,
                    endX = size.width
                )

                // Fill active bar
                if (barWidth > 0f) {
                    drawRect(
                        brush = meterGradient,
                        topLeft = Offset(0f, 0f),
                        size = Size(barWidth, barHeight)
                    )
                }

                // Draw Peak Hold tick indicator
                peakHoldFraction?.let { pFraction ->
                    val peakX = size.width * pFraction
                    drawLine(
                        color = Color.White,
                        start = Offset(peakX, 0f),
                        end = Offset(peakX, barHeight),
                        strokeWidth = 3.dp.toPx()
                    )
                }

                // Draw scale ticks across the meter
                val tickMarks = listOf(-60f, -48f, -36f, -24f, -18f, -12f, -6f, -3f)
                for (tickDb in tickMarks) {
                    val tickFraction = (tickDb - minDb) / (maxDb - minDb)
                    val tickX = size.width * tickFraction
                    drawLine(
                        color = Color(0x66FFFFFF),
                        start = Offset(tickX, barHeight - 6.dp.toPx()),
                        end = Offset(tickX, barHeight),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }

        // Scale label ticks below the bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("-90", "-60", "-36", "-24", "-18", "-12", "-6", "0").forEach { mark ->
                Text(
                    text = mark,
                    color = Color(0xFF78909C),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
