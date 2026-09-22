package com.rtatuner.app.ui.components.surface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Process
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.rtatuner.app.core.audio.FftDisplayMode
import com.rtatuner.app.core.dsp.Iso31Bands
import com.rtatuner.app.ui.EmaPreset
import com.rtatuner.app.ui.RtaDisplayConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance SurfaceView executing real-time spectrum rendering on a dedicated thread.
 *
 * Implements:
 * - Hardware-accelerated Canvas rendering (`lockHardwareCanvas`) on a dedicated thread (~60 FPS)
 * - Strict plot area gutters with hard canvas clipping (`canvas.clipRect`) to prevent bars
 *   from obscuring dB or frequency axis labels
 * - Boundary-clamped log-frequency labels preventing clipping of low-frequency (20/25/31.5Hz) markers
 * - Continuous log-frequency raw FFT curve
 * - Configurable temporal Exponential Moving Average (EMA) smoothing
 * - Per-band peak-hold ballistics with linear decay
 * - User-adjustable dB dynamic range
 * - Zero allocations inside the hot draw path
 * - Isolated pure-draw latency profiling decoupled from display VSYNC wait times
 */
class RtaSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "RtaSurfaceView"
        const val MIN_FREQ_HZ = 20.0f
        const val MAX_FREQ_HZ = 20000.0f
        const val LOG_SPAN = 3.0f // log10(20000 / 20) = 3.0

        // Gutter layout dimensions in pixels
        const val LEFT_GUTTER_PX = 46.0f
        const val BOTTOM_GUTTER_PX = 30.0f
        const val TOP_GUTTER_PX = 8.0f
        const val RIGHT_GUTTER_PX = 8.0f

        val OCTAVE_GRID_FREQS = floatArrayOf(
            20f, 31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f, 20000f
        )
        val OCTAVE_GRID_LABELS = arrayOf(
            "20", "31.5", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k", "20k"
        )
    }

    private var renderThread: RenderThread? = null
    private val isSurfaceReady = AtomicBoolean(false)

    // Current display configuration
    @Volatile
    var displayConfig: RtaDisplayConfig = RtaDisplayConfig()

    // Thread-safe payload transfer from Audio thread to Render thread
    private val currentPayload = AtomicReference<SpectralPayload?>(null)

    // Listener for live frame rate and pure draw execution time (microseconds)
    var onRenderStatsUpdated: ((fps: Float, drawTimeUs: Long) -> Unit)? = null

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
    }

    fun updateSpectralData(
        octave31Db: FloatArray,
        rawFftDb: FloatArray,
        binResolutionHz: Float
    ) {
        currentPayload.set(
            SpectralPayload(
                octave31Db = octave31Db,
                rawFftDb = rawFftDb,
                binResolutionHz = binResolutionHz
            )
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created: launching dedicated render thread")
        isSurfaceReady.set(true)
        val thread = RenderThread(holder)
        renderThread = thread
        thread.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderThread?.onSurfaceDimensionsChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "Surface destroyed: terminating render thread")
        isSurfaceReady.set(false)
        renderThread?.stopRendering()
        try {
            renderThread?.join(500)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while joining render thread", e)
        }
        renderThread = null
    }

    /**
     * Dedicated render thread running at ~60 FPS with zero heap allocations in the draw loop.
     */
    private inner class RenderThread(
        private val surfaceHolder: SurfaceHolder
    ) : Thread("RtaRenderThread") {

        private val isRunning = AtomicBoolean(true)

        private var surfaceWidth = 1
        private var surfaceHeight = 1

        // Plot area dimensions (excluding gutters)
        private var plotLeft = LEFT_GUTTER_PX
        private var plotRight = 1f
        private var plotTop = TOP_GUTTER_PX
        private var plotBottom = 1f
        private var plotWidth = 1f
        private var plotHeight = 1f

        // Pre-allocated smoothing & peak-hold state
        private val smoothedOctave = FloatArray(Iso31Bands.NUM_BANDS) { -100.0f }
        private val peakHoldOctave = FloatArray(Iso31Bands.NUM_BANDS) { -100.0f }
        private val peakHoldTimeMs = LongArray(Iso31Bands.NUM_BANDS) { 0L }

        private var smoothedRawFft: FloatArray? = null

        // Pre-allocated coordinate tables for 31 ISO bands
        private val barLefts = FloatArray(Iso31Bands.NUM_BANDS)
        private val barRights = FloatArray(Iso31Bands.NUM_BANDS)

        // Pre-allocated Paints (Zero allocation inside draw)
        private val bgPaint = Paint().apply { color = Color.parseColor("#0E1117") }
        private val gutterBgPaint = Paint().apply { color = Color.parseColor("#0A0C10") }
        private val gutterDividerPaint = Paint().apply {
            color = Color.parseColor("#263238")
            strokeWidth = 2.0f
            isAntiAlias = true
        }
        private val gridLinePaint = Paint().apply {
            color = Color.parseColor("#1E262C")
            strokeWidth = 1.0f
            isAntiAlias = true
        }
        private val octaveGridPaint = Paint().apply {
            color = Color.parseColor("#2C3B44")
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        private val gridTextPaint = Paint().apply {
            color = Color.parseColor("#90A4AE")
            textSize = 20f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        private val barPaint = Paint().apply { isAntiAlias = false }
        private val rawLinePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            isAntiAlias = true
        }
        private val peakTickPaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 4.0f
            isAntiAlias = true
        }
        private val rawPath = Path()

        // Telemetry counters
        private var frameCount = 0
        private var lastFpsTimestampNs = System.nanoTime()
        private var currentFps = 60.0f

        fun onSurfaceDimensionsChanged(width: Int, height: Int) {
            surfaceWidth = max(1, width)
            surfaceHeight = max(1, height)

            plotLeft = LEFT_GUTTER_PX
            plotRight = max(plotLeft + 10f, surfaceWidth - RIGHT_GUTTER_PX)
            plotTop = TOP_GUTTER_PX
            plotBottom = max(plotTop + 10f, surfaceHeight - BOTTOM_GUTTER_PX)
            plotWidth = plotRight - plotLeft
            plotHeight = plotBottom - plotTop

            // Rebuild vertical gradient shader strictly mapped across the plot area height
            val gradientShader = LinearGradient(
                0f, plotTop, 0f, plotBottom,
                intArrayOf(
                    Color.parseColor("#FF1744"), // 0 dBFS Red
                    Color.parseColor("#FF9100"), // -12 dBFS Orange
                    Color.parseColor("#FFEA00"), // -24 dBFS Yellow
                    Color.parseColor("#00E676"), // -48 dBFS Green
                    Color.parseColor("#00B0FF")  // Floor Blue
                ),
                floatArrayOf(0.0f, 0.15f, 0.30f, 0.60f, 1.0f),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = gradientShader
            rawLinePaint.shader = gradientShader

            // Recompute log-spaced bar boundaries strictly inside [plotLeft, plotRight]
            recomputeBarCoordinates()
        }

        private fun recomputeBarCoordinates() {
            val minGap = 2.0f
            for (i in 0 until Iso31Bands.NUM_BANDS) {
                val fLow = max(MIN_FREQ_HZ, Iso31Bands.LOWER_EDGES[i])
                val fHigh = min(MAX_FREQ_HZ, Iso31Bands.UPPER_EDGES[i])

                val xLow = freqToPlotX(fLow)
                val xHigh = freqToPlotX(fHigh)

                barLefts[i] = xLow + minGap
                barRights[i] = max(barLefts[i] + 1.0f, xHigh - minGap)
            }
        }

        fun stopRendering() {
            isRunning.set(false)
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            var lastLoopTimeNs = System.nanoTime()

            while (isRunning.get() && isSurfaceReady.get()) {
                val loopStartNs = System.nanoTime()
                val dtSec = (loopStartNs - lastLoopTimeNs) / 1_000_000_000f
                lastLoopTimeNs = loopStartNs

                // 1. Process latest audio/spectral payload
                val config = displayConfig
                val payload = currentPayload.get()
                val nowMs = System.currentTimeMillis()

                if (payload != null) {
                    updateBallisticsAndSmoothing(payload, config, dtSec, nowMs)
                }

                // 2. Lock Canvas & Render frame using hardware-accelerated canvas
                var canvas: Canvas? = null
                var pureDrawDurationUs = 0L

                try {
                    // lockHardwareCanvas() leverages GPU Skia rendering on API 26+
                    canvas = try {
                        surfaceHolder.lockHardwareCanvas()
                    } catch (e: Exception) {
                        surfaceHolder.lockCanvas()
                    }

                    if (canvas != null) {
                        val drawStartNs = System.nanoTime()
                        drawFrame(canvas, config)
                        pureDrawDurationUs = (System.nanoTime() - drawStartNs) / 1000L
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Exception during surface draw", e)
                } finally {
                    canvas?.let {
                        try {
                            surfaceHolder.unlockCanvasAndPost(it)
                        } catch (e: Exception) {
                            Log.w(TAG, "Exception unlocking canvas", e)
                        }
                    }
                }

                // 3. FPS & telemetry update (reports pure Canvas execution time)
                frameCount++
                if (loopStartNs - lastFpsTimestampNs >= 1_000_000_000L) {
                    currentFps = frameCount.toFloat()
                    frameCount = 0
                    lastFpsTimestampNs = loopStartNs
                    onRenderStatsUpdated?.invoke(currentFps, pureDrawDurationUs)
                }

                // 4. Frame pacing target: ~60 fps (16.6 ms per frame)
                val elapsedMs = (System.nanoTime() - loopStartNs) / 1_000_000L
                val sleepMs = 16L - elapsedMs
                if (sleepMs > 1) {
                    try {
                        sleep(sleepMs)
                    } catch (ignored: InterruptedException) {
                        break
                    }
                }
            }
        }

        private fun updateBallisticsAndSmoothing(
            payload: SpectralPayload,
            config: RtaDisplayConfig,
            dtSec: Float,
            nowMs: Long
        ) {
            val alpha = config.emaPreset.alpha

            // Update 31-Band EMA Smoothing & Peak Hold
            for (i in 0 until Iso31Bands.NUM_BANDS) {
                val rawVal = if (i < payload.octave31Db.size) payload.octave31Db[i] else config.minDb
                val smoothed = (alpha * rawVal) + ((1.0f - alpha) * smoothedOctave[i])
                smoothedOctave[i] = smoothed

                // Peak hold logic
                if (smoothed >= peakHoldOctave[i]) {
                    peakHoldOctave[i] = smoothed
                    peakHoldTimeMs[i] = nowMs
                } else {
                    val timeSincePeak = nowMs - peakHoldTimeMs[i]
                    if (timeSincePeak > config.peakHoldDurationMs) {
                        val decayed = peakHoldOctave[i] - (config.peakDecayRateDbPerSec * dtSec)
                        peakHoldOctave[i] = max(config.minDb, max(smoothed, decayed))
                    }
                }
            }

            // Update Raw FFT EMA Smoothing
            if (config.displayMode == FftDisplayMode.RAW_FFT) {
                val rawSize = payload.rawFftDb.size
                if (smoothedRawFft == null || smoothedRawFft!!.size != rawSize) {
                    smoothedRawFft = payload.rawFftDb.clone()
                } else {
                    val sArr = smoothedRawFft!!
                    for (k in 0 until rawSize) {
                        sArr[k] = (alpha * payload.rawFftDb[k]) + ((1.0f - alpha) * sArr[k])
                    }
                }
            }
        }

        private fun drawFrame(canvas: Canvas, config: RtaDisplayConfig) {
            val w = surfaceWidth.toFloat()
            val h = surfaceHeight.toFloat()
            val minDb = config.minDb
            val maxDb = config.maxDb
            val dbRange = maxDb - minDb

            // 1. Draw Overall Background
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // 2. Draw Plot Area Gridlines (behind spectrum content)
            val dbSteps = when {
                dbRange <= 60f -> floatArrayOf(0f, -12f, -24f, -36f, -48f, -60f)
                dbRange <= 80f -> floatArrayOf(0f, -12f, -24f, -36f, -48f, -60f, -72f, -80f)
                else -> floatArrayOf(0f, -12f, -24f, -36f, -48f, -60f, -72f, -84f, -90f)
            }

            // Horizontal dB Gridlines across the plot area
            for (db in dbSteps) {
                val y = plotTop + plotHeight * ((maxDb - db) / dbRange)
                val paint = if (db == 0f) octaveGridPaint else gridLinePaint
                canvas.drawLine(plotLeft, y, plotRight, y, paint)
            }

            // Vertical Octave Frequency Gridlines across the plot area
            for (i in OCTAVE_GRID_FREQS.indices) {
                val f = OCTAVE_GRID_FREQS[i]
                val x = freqToPlotX(f)
                canvas.drawLine(x, plotTop, x, plotBottom, octaveGridPaint)
            }

            // 3. Draw Spectrum Content with Hard Canvas Clipping
            // This guarantees bars and curves can NEVER bleed into or paint over the gutters
            canvas.save()
            canvas.clipRect(plotLeft, plotTop, plotRight, plotBottom)

            if (config.displayMode == FftDisplayMode.OCTAVE_1_3) {
                // --- 1/3-Octave Log-Spaced Bars ---
                for (i in 0 until Iso31Bands.NUM_BANDS) {
                    val db = smoothedOctave[i].coerceIn(minDb, maxDb)
                    val barHeight = plotHeight * ((db - minDb) / dbRange)
                    val barTop = plotBottom - barHeight

                    val left = barLefts[i]
                    val right = barRights[i]

                    // Draw vertical gradient bar (from barTop down to plotBottom)
                    if (barHeight > 0f) {
                        canvas.drawRect(left, barTop, right, plotBottom, barPaint)
                    }

                    // Draw Peak-Hold horizontal tick
                    val peakDb = peakHoldOctave[i].coerceIn(minDb, maxDb)
                    val peakY = plotTop + plotHeight * ((maxDb - peakDb) / dbRange)
                    canvas.drawLine(left, peakY, right, peakY, peakTickPaint)
                }
            } else {
                // --- Continuous Log-Frequency Raw FFT Curve ---
                val payload = currentPayload.get()
                val rawFft = smoothedRawFft ?: payload?.rawFftDb
                val resHz = payload?.binResolutionHz ?: 23.4375f

                if (rawFft != null) {
                    rawPath.reset()
                    var started = false

                    for (k in 1 until rawFft.size) {
                        val freq = k * resHz
                        if (freq in MIN_FREQ_HZ..MAX_FREQ_HZ) {
                            val x = freqToPlotX(freq)
                            val db = rawFft[k].coerceIn(minDb, maxDb)
                            val y = plotTop + plotHeight * ((maxDb - db) / dbRange)

                            if (!started) {
                                rawPath.moveTo(x, y)
                                started = true
                            } else {
                                rawPath.lineTo(x, y)
                            }
                        }
                    }
                    canvas.drawPath(rawPath, rawLinePaint)
                }
            }
            canvas.restore()

            // 4. Draw Gutter Overlays & Axis Dividers
            // Clear gutters with an opaque background strip to ensure absolute visual isolation
            canvas.drawRect(0f, 0f, plotLeft, h, gutterBgPaint)
            canvas.drawRect(0f, plotBottom, w, h, gutterBgPaint)
            canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, gutterDividerPaint)
            canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, gutterDividerPaint)

            // 5. Draw dB Scale Labels in Left Gutter
            for (db in dbSteps) {
                val y = plotTop + plotHeight * ((maxDb - db) / dbRange)
                val label = String.format(Locale.US, "%3.0f", db)
                canvas.drawText(label, 6f, max(plotTop + 14f, y + 6f), gridTextPaint)
            }

            // 6. Draw Frequency Axis Labels in Bottom Gutter with Edge Clamping
            var lastDrawnRightX = -100f
            for (i in OCTAVE_GRID_FREQS.indices) {
                val f = OCTAVE_GRID_FREQS[i]
                val label = OCTAVE_GRID_LABELS[i]
                val x = freqToPlotX(f)
                val textWidth = gridTextPaint.measureText(label)

                // Clamp label text so it is never clipped on the left (20/31.5Hz) or right (20kHz)
                val textX = (x - (textWidth / 2f)).coerceIn(plotLeft, plotRight - textWidth)

                // Skip overlapping labels on extremely narrow screens
                if (textX >= lastDrawnRightX + 4f || i == 0) {
                    canvas.drawText(label, textX, h - 8f, gridTextPaint)
                    lastDrawnRightX = textX + textWidth
                }
            }
        }

        private fun freqToPlotX(freqHz: Float): Float {
            val clamped = freqHz.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)
            val norm = (log10(clamped / MIN_FREQ_HZ) / LOG_SPAN).coerceIn(0.0f, 1.0f)
            return plotLeft + (plotWidth * norm)
        }
    }

    private class SpectralPayload(
        val octave31Db: FloatArray,
        val rawFftDb: FloatArray,
        val binResolutionHz: Float
    )
}
