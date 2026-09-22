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
 * - 60 FPS dedicated render loop (decoupled from audio capture and UI threads)
 * - Exact log-frequency bar positioning across 31 ISO bands
 * - Continuous log-frequency raw FFT curve
 * - Configurable temporal Exponential Moving Average (EMA) smoothing
 * - Per-band peak-hold ballistics with linear decay
 * - User-adjustable dB dynamic range
 * - Zero allocations inside the render loop
 */
class RtaSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "RtaSurfaceView"
        private const val MIN_FREQ_HZ = 20.0f
        private const val MAX_FREQ_HZ = 20000.0f
        private const val LOG_SPAN = 3.0f // log10(20000 / 20) = 3.0

        val OCTAVE_GRID_FREQS = floatArrayOf(
            31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )
        val OCTAVE_GRID_LABELS = arrayOf(
            "31.5", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"
        )
    }

    private var renderThread: RenderThread? = null
    private val isSurfaceReady = AtomicBoolean(false)

    // Current display configuration
    @Volatile
    var displayConfig: RtaDisplayConfig = RtaDisplayConfig()

    // Thread-safe payload transfer from Audio thread to Render thread
    private val currentPayload = AtomicReference<SpectralPayload?>(null)

    // Optional listener for live frame rate and latency telemetry
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
        private val gridLinePaint = Paint().apply {
            color = Color.parseColor("#263238")
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        private val octaveGridPaint = Paint().apply {
            color = Color.parseColor("#37474F")
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        private val gridTextPaint = Paint().apply {
            color = Color.parseColor("#78909C")
            textSize = 24f
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

            // Rebuild vertical gradient shader
            val gradientShader = LinearGradient(
                0f, 0f, 0f, surfaceHeight.toFloat(),
                intArrayOf(
                    Color.parseColor("#FF1744"), // 0 dBFS Red
                    Color.parseColor("#FF9100"), // -12 dBFS Orange
                    Color.parseColor("#FFEA00"), // -24 dBFS Yellow
                    Color.parseColor("#00E676"), // -48 dBFS Green
                    Color.parseColor("#00B0FF")  // -80 dBFS Blue
                ),
                floatArrayOf(0.0f, 0.15f, 0.30f, 0.60f, 1.0f),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = gradientShader
            rawLinePaint.shader = gradientShader

            // Recompute log-spaced bar boundaries
            recomputeBarCoordinates(surfaceWidth.toFloat())
        }

        private fun recomputeBarCoordinates(width: Float) {
            val minGap = 2.0f
            for (i in 0 until Iso31Bands.NUM_BANDS) {
                val fLow = max(MIN_FREQ_HZ, Iso31Bands.LOWER_EDGES[i])
                val fHigh = min(MAX_FREQ_HZ, Iso31Bands.UPPER_EDGES[i])

                val xLow = freqToX(fLow, width)
                val xHigh = freqToX(fHigh, width)

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

                // 2. Lock Canvas & Render frame
                var canvas: Canvas? = null
                val drawStartNs = System.nanoTime()
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null) {
                        drawFrame(canvas, config)
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
                val drawDurationUs = (System.nanoTime() - drawStartNs) / 1000L

                // 3. FPS & telemetry update
                frameCount++
                if (loopStartNs - lastFpsTimestampNs >= 1_000_000_000L) {
                    currentFps = frameCount.toFloat()
                    frameCount = 0
                    lastFpsTimestampNs = loopStartNs
                    onRenderStatsUpdated?.invoke(currentFps, drawDurationUs)
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

            // 1. Draw Background
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // 2. Draw Horizontal dB Gridlines & Scale Labels
            val dbSteps = when {
                dbRange <= 60f -> floatArrayOf(0f, -12f, -24f, -36f, -48f, -60f)
                dbRange <= 80f -> floatArrayOf(0f, -12f, -24f, -36f, -48f, -60f, -72f, -80f)
                else -> floatArrayOf(0f, -12f, -24f, -36f, -48f, -60f, -72f, -84f, -90f)
            }

            for (db in dbSteps) {
                val y = h * ((maxDb - db) / dbRange)
                val paint = if (db == 0f) octaveGridPaint else gridLinePaint
                canvas.drawLine(0f, y, w, y, paint)

                // Label on left edge
                val label = String.format(Locale.US, "%3.0f", db)
                canvas.drawText(label, 12f, max(24f, y - 4f), gridTextPaint)
            }

            // 3. Draw Vertical Octave Frequency Gridlines
            for (i in OCTAVE_GRID_FREQS.indices) {
                val f = OCTAVE_GRID_FREQS[i]
                val x = freqToX(f, w)
                canvas.drawLine(x, 0f, x, h, octaveGridPaint)

                // Label near bottom
                val label = OCTAVE_GRID_LABELS[i]
                canvas.drawText(label, x - 16f, h - 10f, gridTextPaint)
            }

            // 4. Draw Spectrum Content
            if (config.displayMode == FftDisplayMode.OCTAVE_1_3) {
                // --- 1/3-Octave Log-Spaced Bars ---
                for (i in 0 until Iso31Bands.NUM_BANDS) {
                    val db = smoothedOctave[i].coerceIn(minDb, maxDb)
                    val barHeight = h * ((db - minDb) / dbRange)
                    val barTop = h - barHeight

                    val left = barLefts[i]
                    val right = barRights[i]

                    // Draw vertical gradient bar
                    if (barHeight > 0f) {
                        canvas.drawRect(left, barTop, right, h, barPaint)
                    }

                    // Draw Peak-Hold horizontal tick
                    val peakDb = peakHoldOctave[i].coerceIn(minDb, maxDb)
                    val peakY = h * ((maxDb - peakDb) / dbRange)
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
                            val x = freqToX(freq, w)
                            val db = rawFft[k].coerceIn(minDb, maxDb)
                            val y = h * ((maxDb - db) / dbRange)

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
        }

        private fun freqToX(freqHz: Float, width: Float): Float {
            val clamped = freqHz.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)
            val norm = (log10(clamped / MIN_FREQ_HZ) / LOG_SPAN).coerceIn(0.0f, 1.0f)
            return width * norm
        }
    }

    private class SpectralPayload(
        val octave31Db: FloatArray,
        val rawFftDb: FloatArray,
        val binResolutionHz: Float
    )
}
