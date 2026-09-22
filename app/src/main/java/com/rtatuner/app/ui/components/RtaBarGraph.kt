package com.rtatuner.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtatuner.app.core.audio.FftDisplayMode
import com.rtatuner.app.core.dsp.Iso31Bands
import kotlin.math.log10

/**
 * Real-time spectrum visualizer supporting:
 * - 31-Band 1/3-octave bar graph
 * - Continuous logarithmic raw FFT line graph
 */
@Composable
fun RtaBarGraph(
    octave31Db: FloatArray,
    rawFftDb: FloatArray,
    displayMode: FftDisplayMode,
    binResolutionHz: Float,
    minDb: Float = -90.0f,
    maxDb: Float = 0.0f,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF101318))
                .border(1.dp, Color(0xFF242C38), RoundedCornerShape(6.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val dbRange = maxDb - minDb

                // 1. Draw horizontal dB grid lines
                val dbGridSteps = listOf(0f, -12f, -24f, -36f, -48f, -60f, -72f, -84f)
                for (gridDb in dbGridSteps) {
                    val y = canvasHeight * ((maxDb - gridDb) / dbRange)
                    drawLine(
                        color = if (gridDb == 0f) Color(0x66FF1744) else Color(0x22FFFFFF),
                        start = Offset(0f, y),
                        end = Offset(canvasWidth, y),
                        strokeWidth = if (gridDb == 0f) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                }

                // 2. Render spectrum depending on display mode
                if (displayMode == FftDisplayMode.OCTAVE_1_3) {
                    // --- 31-Band 1/3-Octave Bar Graph ---
                    val numBands = Iso31Bands.NUM_BANDS
                    val totalSpacing = (numBands + 1) * 2.dp.toPx()
                    val barWidth = (canvasWidth - totalSpacing) / numBands

                    val barGradient = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFFF1744),    // 0 dBFS Red
                            0.15f to Color(0xFFFF9100),   // -12 dBFS Orange
                            0.30f to Color(0xFFFFEA00),   // -24 dBFS Yellow
                            0.60f to Color(0xFF00E676),   // -48 dBFS Green
                            1.0f to Color(0xFF00B0FF)     // -90 dBFS Blue
                        ),
                        startY = 0f,
                        endY = canvasHeight
                    )

                    for (i in 0 until numBands) {
                        val dbVal = if (i < octave31Db.size) octave31Db[i].coerceIn(minDb, maxDb) else minDb
                        val fraction = ((dbVal - minDb) / dbRange).coerceIn(0f, 1f)
                        val barHeight = canvasHeight * fraction

                        val left = 2.dp.toPx() + i * (barWidth + 2.dp.toPx())
                        val top = canvasHeight - barHeight

                        if (barHeight > 0f) {
                            drawRect(
                                brush = barGradient,
                                topLeft = Offset(left, top),
                                size = Size(barWidth, barHeight)
                            )
                        }
                    }
                } else {
                    // --- Full Resolution Log-Frequency Raw FFT Graph ---
                    val minLogFreq = 20.0f
                    val maxLogFreq = 20000.0f
                    val logSpan = log10(maxLogFreq / minLogFreq)

                    val fftPath = Path()
                    var hasStarted = false

                    val lineBrush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFFF1744),
                            0.3f to Color(0xFFFFD600),
                            0.7f to Color(0xFF00E676),
                            1.0f to Color(0xFF00B0FF)
                        ),
                        startY = 0f,
                        endY = canvasHeight
                    )

                    for (k in 1 until rawFftDb.size) {
                        val freq = k * binResolutionHz
                        if (freq in minLogFreq..maxLogFreq) {
                            val xNorm = (log10(freq / minLogFreq) / logSpan).coerceIn(0f, 1f)
                            val x = canvasWidth * xNorm

                            val dbVal = rawFftDb[k].coerceIn(minDb, maxDb)
                            val y = canvasHeight * ((maxDb - dbVal) / dbRange)

                            if (!hasStarted) {
                                fftPath.moveTo(x, y)
                                hasStarted = true
                            } else {
                                fftPath.lineTo(x, y)
                            }
                        }
                    }

                    drawPath(
                        path = fftPath,
                        brush = lineBrush,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Frequency axis labels
        if (displayMode == FftDisplayMode.OCTAVE_1_3) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Display key subset of ISO labels to prevent visual overlap
                listOf("20", "40", "80", "160", "315", "630", "1.25k", "2.5k", "5k", "10k", "20k").forEach { label ->
                    Text(
                        text = label,
                        color = Color(0xFF78909C),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("20 Hz", "100 Hz", "500 Hz", "1 kHz", "5 kHz", "10 kHz", "20 kHz").forEach { label ->
                    Text(
                        text = label,
                        color = Color(0xFF78909C),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
