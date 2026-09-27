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
import com.rtatuner.app.core.session.RtaSnapshot
import com.rtatuner.app.ui.EmaPreset
import com.rtatuner.app.ui.RtaDisplayConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance SurfaceView executing real-time spectrum rendering on a dedicated thread.
 *
 * Implements:
 * - Hardware-accelerated Canvas rendering (`lockHardwareCanvas`) on a dedicated thread (~60 FPS)
 * - Strict plot area gutters with hard canvas clipping (`canvas.clipRect`)
 * - Boundary-clamped log-frequency labels preventing clipping of low-frequency (20/25/31.5Hz) markers
 * - Continuous log-frequency raw FFT curve
 * - Target curve overlay line (Harman, Flat, House curve) with continuous interpolation
 * - Per-band target delta indicators (On-target green dot, Hot red chevron, Cut blue chevron)
 * - Power-domain noise floor subtraction: visual dimming and marking of unreliable bands (SNR < 6 dB)
 * - Seamless dBFS relative mode vs calibrated dB SPL mode
 * - Zero allocations inside the hot draw path
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
        const val LEFT_GUTTER_PX = 48.0f
        const val BOTTOM_GUTTER_PX = 30.0f
        const val TOP_GUTTER_PX = 12.0f
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
        binResolutionHz: Float,
        unreliableBands: BooleanArray? = null,
        targetDeltas31Db: FloatArray? = null,
        frozenSnapshots: List<RtaSnapshot> = emptyList(),
        resetSmoothing: Boolean = false
    ) {
        currentPayload.set(
            SpectralPayload(
                octave31Db = octave31Db,
                rawFftDb = rawFftDb,
                binResolutionHz = binResolutionHz,
                unreliableBands = unreliableBands,
                targetDeltas31Db = targetDeltas31Db,
                frozenSnapshots = frozenSnapshots,
                resetSmoothing = resetSmoothing
            )
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created: launching dedicated render thread")
        stopRenderThreadInternal()
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
        stopRenderThreadInternal()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isSurfaceReady.set(false)
        stopRenderThreadInternal()
    }

    private fun stopRenderThreadInternal() {
        val thread = renderThread ?: return
        renderThread = null
        thread.stopRendering()
        thread.interrupt()
        try {
            thread.join(500)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while joining render thread", e)
            Thread.currentThread().interrupt()
        }
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
        private val gutterHeaderPaint = Paint().apply {
            color = Color.parseColor("#00E676")
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }
        private val barPaint = Paint().apply { isAntiAlias = false }
        private val dimmedBarPaint = Paint().apply {
            color = Color.parseColor("#37474F")
            alpha = 110 // Dimmed for bands near noise floor
            isAntiAlias = false
        }
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
        private val unreliableTickPaint = Paint().apply {
            color = Color.parseColor("#FFB74D") // Light Amber warning
            strokeWidth = 3.0f
            isAntiAlias = true
        }
        private val targetCurvePaint = Paint().apply {
            color = Color.parseColor("#FFD600") // Bright Gold
            strokeWidth = 3.0f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        private val deltaOkPaint = Paint().apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val deltaHotPaint = Paint().apply {
            color = Color.parseColor("#FF5252")
            strokeWidth = 2.5f
            isAntiAlias = true
        }
        private val deltaCutPaint = Paint().apply {
            color = Color.parseColor("#40C4FF")
            strokeWidth = 2.5f
            isAntiAlias = true
        }

        private val rawPath = Path()
        private val targetPath = Path()
        private val snapshotPath = Path()
        private val snapshotPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        private val snapshotDotPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val snapshotLegendPaint = Paint().apply {
            color = Color.WHITE
            textSize = 16f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

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
                    Color.parseColor("#FF1744"), // 0 dBFS / High SPL Red
                    Color.parseColor("#FF9100"), // Orange
                    Color.parseColor("#FFEA00"), // Yellow
                    Color.parseColor("#00E676"), // Green
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

            while (isRunning.get() && isSurfaceReady.get() && !isInterrupted) {
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
            val rawSpl = if (config.isSplMode) config.splOffsetDb else 0.0f
            val splOffset = if (rawSpl.isNaN() || rawSpl.isInfinite()) 0.0f else rawSpl
            val minDb = config.minDb + splOffset
            val shouldReset = payload.resetSmoothing

            // Update 31-Band EMA Smoothing & Peak Hold
            for (i in 0 until Iso31Bands.NUM_BANDS) {
                val inputVal = if (i < payload.octave31Db.size) payload.octave31Db[i] else minDb
                // Clamp from below to minDb so deep floor values (e.g. -120 dBFS) don't destabilize EMA
                val rawVal = if (inputVal.isNaN() || inputVal.isInfinite()) minDb else max(minDb, inputVal)

                if (shouldReset || smoothedOctave[i].isNaN() || smoothedOctave[i].isInfinite()) {
                    smoothedOctave[i] = rawVal
                } else {
                    smoothedOctave[i] = (alpha * rawVal) + ((1.0f - alpha) * smoothedOctave[i])
                }
                if (smoothedOctave[i].isNaN() || smoothedOctave[i].isInfinite()) {
                    smoothedOctave[i] = minDb
                }

                // Peak hold logic
                if (shouldReset || peakHoldOctave[i].isNaN() || peakHoldOctave[i].isInfinite()) {
                    peakHoldOctave[i] = smoothedOctave[i]
                    peakHoldTimeMs[i] = nowMs
                } else if (smoothedOctave[i] >= peakHoldOctave[i]) {
                    peakHoldOctave[i] = smoothedOctave[i]
                    peakHoldTimeMs[i] = nowMs
                } else {
                    val timeSincePeak = nowMs - peakHoldTimeMs[i]
                    if (timeSincePeak > config.peakHoldDurationMs) {
                        val decayed = peakHoldOctave[i] - (config.peakDecayRateDbPerSec * dtSec)
                        peakHoldOctave[i] = max(minDb, max(smoothedOctave[i], decayed))
                        if (peakHoldOctave[i].isNaN() || peakHoldOctave[i].isInfinite()) {
                            peakHoldOctave[i] = minDb
                        }
                    }
                }
            }

            // Update Raw FFT EMA Smoothing
            if (config.displayMode == FftDisplayMode.RAW_FFT) {
                val rawSize = payload.rawFftDb.size
                if (shouldReset || smoothedRawFft == null || smoothedRawFft!!.size != rawSize) {
                    smoothedRawFft = payload.rawFftDb.clone()
                    smoothedRawFft?.let { arr ->
                        for (k in arr.indices) {
                            if (arr[k].isNaN() || arr[k].isInfinite() || arr[k] < minDb) arr[k] = minDb
                        }
                    }
                } else {
                    val sArr = smoothedRawFft!!
                    for (k in 0 until rawSize) {
                        val rawK = payload.rawFftDb[k]
                        val safeK = if (rawK.isNaN() || rawK.isInfinite()) minDb else max(minDb, rawK)
                        if (sArr[k].isNaN() || sArr[k].isInfinite()) {
                            sArr[k] = safeK
                        } else {
                            sArr[k] = (alpha * safeK) + ((1.0f - alpha) * sArr[k])
                        }
                        if (sArr[k].isNaN() || sArr[k].isInfinite()) {
                            sArr[k] = minDb
                        }
                    }
                }
            }
        }

        private fun drawFrame(canvas: Canvas, config: RtaDisplayConfig) {
            val w = surfaceWidth.toFloat()
            val h = surfaceHeight.toFloat()

            val rawSpl = if (config.isSplMode) config.splOffsetDb else 0.0f
            val splOffset = if (rawSpl.isNaN() || rawSpl.isInfinite()) 0.0f else rawSpl
            val minDb = config.minDb + splOffset
            val maxDb = config.maxDb + splOffset
            val dbRange = maxDb - minDb

            val payload = currentPayload.get()
            val unreliableBands = payload?.unreliableBands
            val targetDeltas = payload?.targetDeltas31Db

            // 1. Draw Overall Background
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // 2. Draw Plot Area Gridlines (behind spectrum content)
            val nominalSpan = config.maxDb - config.minDb
            val nominalSteps = when {
                nominalSpan <= 60f -> floatArrayOf(0f, -12f, -24f, -36f, -48f, -60f)
                nominalSpan <= 80f -> floatArrayOf(0f, -12f, -24f, -36f, -48f, -60f, -72f, -80f)
                else -> floatArrayOf(0f, -12f, -24f, -36f, -48f, -60f, -72f, -84f, -90f)
            }

            // Horizontal dB Gridlines across the plot area
            for (step in nominalSteps) {
                val db = maxDb + step // in dBFS this is step; in SPL it is maxDb + step
                val y = plotTop + plotHeight * ((maxDb - db) / dbRange)
                val paint = if (step == 0f) octaveGridPaint else gridLinePaint
                canvas.drawLine(plotLeft, y, plotRight, y, paint)
            }

            // Vertical Octave Frequency Gridlines across the plot area
            for (i in OCTAVE_GRID_FREQS.indices) {
                val f = OCTAVE_GRID_FREQS[i]
                val x = freqToPlotX(f)
                canvas.drawLine(x, plotTop, x, plotBottom, octaveGridPaint)
            }

            // 3. Draw Spectrum Content with Hard Canvas Clipping
            canvas.save()
            canvas.clipRect(plotLeft, plotTop, plotRight, plotBottom)

            if (config.displayMode == FftDisplayMode.OCTAVE_1_3) {
                // --- 1/3-Octave Log-Spaced Bars ---
                for (i in 0 until Iso31Bands.NUM_BANDS) {
                    val rawOctave = smoothedOctave[i]
                    val safeOctave = if (rawOctave.isNaN() || rawOctave.isInfinite()) minDb else rawOctave
                    val db = safeOctave.coerceIn(minDb, maxDb)
                    val barHeight = plotHeight * ((db - minDb) / dbRange)
                    val barTop = plotBottom - barHeight

                    val left = barLefts[i]
                    val right = barRights[i]
                    val isUnreliable = unreliableBands?.getOrNull(i) == true

                    // Draw bar (dimmed if within 6 dB of noise floor)
                    if (barHeight > 0f) {
                        val paint = if (isUnreliable) dimmedBarPaint else barPaint
                        canvas.drawRect(left, barTop, right, plotBottom, paint)
                    }

                    // Draw Peak-Hold horizontal tick
                    val rawPeak = peakHoldOctave[i]
                    val safePeak = if (rawPeak.isNaN() || rawPeak.isInfinite()) minDb else rawPeak
                    val peakDb = safePeak.coerceIn(minDb, maxDb)
                    val peakY = plotTop + plotHeight * ((maxDb - peakDb) / dbRange)
                    val peakPaint = if (isUnreliable) unreliableTickPaint else peakTickPaint
                    canvas.drawLine(left, peakY, right, peakY, peakPaint)

                    // Draw noise unreliability warning tick at the base of the bar
                    if (isUnreliable) {
                        canvas.drawLine(left, plotBottom - 2f, right, plotBottom - 2f, unreliableTickPaint)
                    }

                    // Draw per-band target delta indicator if target curve is active
                    if (targetDeltas != null && i < targetDeltas.size) {
                        val rawDelta = targetDeltas[i]
                        val delta = if (rawDelta.isNaN() || rawDelta.isInfinite()) 0.0f else rawDelta
                        val centerX = (left + right) / 2f
                        val deltaY = (barTop - 6f).coerceIn(plotTop + 4f, plotBottom - 6f)

                        when {
                            abs(delta) <= 1.5f -> {
                                // Within +/-1.5 dB: Green dot
                                canvas.drawCircle(centerX, deltaY, 2.5f, deltaOkPaint)
                            }
                            delta > 1.5f -> {
                                // Hot > +1.5 dB: Red up chevron
                                canvas.drawLine(centerX - 3f, deltaY + 2f, centerX, deltaY - 2f, deltaHotPaint)
                                canvas.drawLine(centerX, deltaY - 2f, centerX + 3f, deltaY + 2f, deltaHotPaint)
                            }
                            else -> {
                                // Cut < -1.5 dB: Blue down chevron
                                canvas.drawLine(centerX - 3f, deltaY - 2f, centerX, deltaY + 2f, deltaCutPaint)
                                canvas.drawLine(centerX, deltaY + 2f, centerX + 3f, deltaY - 2f, deltaCutPaint)
                            }
                        }
                    }
                }
            } else {
                // --- Continuous Log-Frequency Raw FFT Curve ---
                val rawFft = smoothedRawFft ?: payload?.rawFftDb
                val resHz = payload?.binResolutionHz ?: 23.4375f

                if (rawFft != null) {
                    rawPath.reset()
                    var started = false

                    for (k in 1 until rawFft.size) {
                        val freq = k * resHz
                        if (freq in MIN_FREQ_HZ..MAX_FREQ_HZ) {
                            val x = freqToPlotX(freq)
                            val rawDb = rawFft[k]
                            val safeDb = if (rawDb.isNaN() || rawDb.isInfinite()) minDb else rawDb
                            val db = safeDb.coerceIn(minDb, maxDb)
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

            // Draw Target Curve Overlay Line if active
            if (config.isTargetCurveActive && config.targetCurve != null) {
                targetPath.reset()
                var targetStarted = false
                val tCurve = config.targetCurve!!
                val targetBaseline = if (config.isSplMode) {
                    config.targetBaselineDb + splOffset
                } else {
                    config.targetBaselineDb
                }

                for (i in 0 until Iso31Bands.NUM_BANDS) {
                    val f = Iso31Bands.CENTER_FREQUENCIES[i]
                    val x = freqToPlotX(f)
                    val curveDb = tCurve.getTargetDbAt(f)
                    val safeCurveDb = if (curveDb.isNaN() || curveDb.isInfinite()) 0.0f else curveDb
                    val targetVal = (targetBaseline + safeCurveDb).coerceIn(minDb, maxDb)
                    val y = plotTop + plotHeight * ((maxDb - targetVal) / dbRange)

                    if (!targetStarted) {
                        targetPath.moveTo(x, y)
                        targetStarted = true
                    } else {
                        targetPath.lineTo(x, y)
                    }
                }
                canvas.drawPath(targetPath, targetCurvePaint)
            }

            // 3.5. Draw Frozen Snapshot Overlays
            val snapshots = payload?.frozenSnapshots
            if (!snapshots.isNullOrEmpty()) {
                for (snap in snapshots) {
                    if (!snap.isVisible) continue
                    val colorInt = snap.colorHex.toInt()
                    snapshotPaint.color = colorInt
                    snapshotDotPaint.color = colorInt
                    snapshotPath.reset()
                    var snapStarted = false

                    // Cross-mode offset correction between dBFS and SPL
                    val crossModeOffset = when {
                        snap.isSplMode && !config.isSplMode -> -snap.splOffsetDb
                        !snap.isSplMode && config.isSplMode -> splOffset
                        snap.isSplMode && config.isSplMode -> (splOffset - snap.splOffsetDb)
                        else -> 0.0f
                    }

                    for (i in 0 until Iso31Bands.NUM_BANDS) {
                        val rawDb = if (i < snap.bands31Db.size) snap.bands31Db[i] else minDb
                        val safeDb = if (rawDb.isNaN() || rawDb.isInfinite()) minDb else rawDb
                        val effectiveDb = (safeDb + crossModeOffset).coerceIn(minDb, maxDb)
                        val y = plotTop + plotHeight * ((maxDb - effectiveDb) / dbRange)

                        val left = barLefts[i]
                        val right = barRights[i]
                        val centerX = (left + right) / 2f

                        if (config.displayMode == FftDisplayMode.OCTAVE_1_3) {
                            if (!snapStarted) {
                                snapshotPath.moveTo(left, y)
                                snapshotPath.lineTo(right, y)
                                snapStarted = true
                            } else {
                                snapshotPath.lineTo(left, y)
                                snapshotPath.lineTo(right, y)
                            }
                        } else {
                            val f = Iso31Bands.CENTER_FREQUENCIES[i]
                            val x = freqToPlotX(f)
                            if (!snapStarted) {
                                snapshotPath.moveTo(x, y)
                                snapStarted = true
                            } else {
                                snapshotPath.lineTo(x, y)
                            }
                        }

                        canvas.drawCircle(centerX, y, 3.0f, snapshotDotPaint)
                    }
                    canvas.drawPath(snapshotPath, snapshotPaint)
                }
            }

            canvas.restore()

            // 4. Draw Gutter Overlays & Axis Dividers
            canvas.drawRect(0f, 0f, plotLeft, h, gutterBgPaint)
            canvas.drawRect(0f, plotBottom, w, h, gutterBgPaint)
            canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, gutterDividerPaint)
            canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, gutterDividerPaint)

            // 5. Draw dB / SPL Scale Labels in Left Gutter
            // Mode Header (SPL / dBFS)
            canvas.drawText(
                if (config.isSplMode) "SPL" else "dBFS",
                4f,
                plotTop + 4f,
                gutterHeaderPaint
            )

            for (step in nominalSteps) {
                val db = maxDb + step
                val y = plotTop + plotHeight * ((maxDb - db) / dbRange)
                val label = String.format(Locale.US, "%3.0f", db)
                canvas.drawText(label, 6f, max(plotTop + 18f, y + 6f), gridTextPaint)
            }

            // 6. Draw Frequency Axis Labels in Bottom Gutter with Edge Clamping
            var lastDrawnRightX = -100f
            for (i in OCTAVE_GRID_FREQS.indices) {
                val f = OCTAVE_GRID_FREQS[i]
                val label = OCTAVE_GRID_LABELS[i]
                val x = freqToPlotX(f)
                val textWidth = gridTextPaint.measureText(label)

                val textX = (x - (textWidth / 2f)).coerceIn(plotLeft, plotRight - textWidth)

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
        val binResolutionHz: Float,
        val unreliableBands: BooleanArray? = null,
        val targetDeltas31Db: FloatArray? = null,
        val frozenSnapshots: List<RtaSnapshot> = emptyList(),
        val resetSmoothing: Boolean = false
    )
}

