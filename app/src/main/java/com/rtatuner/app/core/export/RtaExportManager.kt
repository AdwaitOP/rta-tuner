package com.rtatuner.app.core.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.content.FileProvider
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.database.SessionWithSnapshots
import com.rtatuner.app.core.dsp.Iso31Bands
import com.rtatuner.app.core.session.RtaSnapshot
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles CSV data export and SurfaceView PNG image capture & sharing.
 */
object RtaExportManager {

    private const val TAG = "RtaExportManager"
    private const val EXPORTS_DIR = "exports"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // ==========================================
    // CSV EXPORT METHODS
    // ==========================================

    fun formatSnapshotCsv(snapshot: RtaSnapshot, sessionName: String? = null): String {
        val sb = StringBuilder()
        val nowStr = dateFormat.format(Date(snapshot.timestamp))
        val modeStr = if (snapshot.isSplMode) {
            String.format(Locale.US, "Calibrated SPL (Offset: %+.2f dB)", snapshot.splOffsetDb)
        } else {
            "Relative dBFS"
        }

        sb.append("# RTA Tuner Spectrum Export\n")
        sb.append("# Timestamp: $nowStr\n")
        if (!sessionName.isNullOrBlank()) {
            sb.append("# Session: $sessionName\n")
        }
        sb.append("# Snapshot: ${snapshot.name}\n")
        sb.append("# Calibration Curve: ${snapshot.calibrationName ?: "None"}\n")
        sb.append("# Target Curve: ${snapshot.targetCurveName ?: "None"}\n")
        sb.append("# Noise Subtraction: ${if (snapshot.isNoiseSubtractionActive) "Active" else "Off"}\n")
        sb.append("# Measurement Mode: $modeStr\n")
        sb.append("# Window Size: ${snapshot.windowSize}\n")
        sb.append("# ----------------------------------------\n")
        sb.append("Frequency_Hz,Level_dB\n")

        for (i in 0 until Iso31Bands.NUM_BANDS) {
            val freq = Iso31Bands.CENTER_FREQUENCIES[i]
            val v = if (i < snapshot.bands31Db.size) snapshot.bands31Db[i] else AudioMath.MIN_DB_FS
            val safeV = if (v.isNaN() || v.isInfinite()) AudioMath.MIN_DB_FS else v
            val freqStr = if (freq == freq.toInt().toFloat()) freq.toInt().toString() else String.format(Locale.US, "%.1f", freq)
            sb.append(String.format(Locale.US, "%s,%.2f\n", freqStr, safeV))
        }

        return sb.toString()
    }

    fun formatLiveCurveCsv(
        liveBands31Db: FloatArray,
        calibrationName: String?,
        targetCurveName: String?,
        isSplMode: Boolean,
        splOffsetDb: Float,
        windowSize: Int,
        isNoiseSubtractionActive: Boolean
    ): String {
        val snapshot = RtaSnapshot(
            name = "Live Spectrum",
            timestamp = System.currentTimeMillis(),
            bands31Db = liveBands31Db,
            isSplMode = isSplMode,
            splOffsetDb = splOffsetDb,
            calibrationName = calibrationName,
            targetCurveName = targetCurveName,
            windowSize = windowSize,
            isNoiseSubtractionActive = isNoiseSubtractionActive
        )
        return formatSnapshotCsv(snapshot, sessionName = "Live Measurement")
    }

    fun formatSessionWithSnapshotsCsv(sessionWithSnapshots: SessionWithSnapshots): String {
        val session = sessionWithSnapshots.session
        val snapshots = sessionWithSnapshots.snapshots

        val sb = StringBuilder()
        val dateStr = dateFormat.format(Date(session.createdAt))
        val modeStr = if (session.isSplMode) {
            String.format(Locale.US, "Calibrated SPL (Offset: %+.2f dB)", session.splOffsetDb)
        } else {
            "Relative dBFS"
        }

        sb.append("# RTA Tuner Session Multi-Snapshot Export\n")
        sb.append("# Session: ${session.name}\n")
        if (session.notes.isNotBlank()) {
            sb.append("# Notes: ${session.notes.replace("\n", " ")}\n")
        }
        sb.append("# Created: $dateStr\n")
        sb.append("# Calibration Curve: ${session.activeCalibrationName ?: "None"}\n")
        sb.append("# Target Curve: ${session.activeTargetCurveName ?: "None"}\n")
        sb.append("# Noise Subtraction: ${if (session.isNoiseSubtractionActive) "Active" else "Off"}\n")
        sb.append("# Measurement Mode: $modeStr\n")
        sb.append("# Window Size: ${session.windowSize}\n")
        sb.append("# Snapshot Count: ${snapshots.size}\n")
        sb.append("# ----------------------------------------\n")

        // Header row
        sb.append("Frequency_Hz")
        if (snapshots.isEmpty()) {
            sb.append(",Level_dB\n")
            for (i in 0 until Iso31Bands.NUM_BANDS) {
                val freq = Iso31Bands.CENTER_FREQUENCIES[i]
                val freqStr = if (freq == freq.toInt().toFloat()) freq.toInt().toString() else String.format(Locale.US, "%.1f", freq)
                sb.append(String.format(Locale.US, "%s,%.2f\n", freqStr, AudioMath.MIN_DB_FS))
            }
        } else {
            for (snap in snapshots) {
                val escapedName = snap.name.replace("\"", "\"\"")
                sb.append(",\"$escapedName (dB)\"")
            }
            sb.append("\n")

            for (i in 0 until Iso31Bands.NUM_BANDS) {
                val freq = Iso31Bands.CENTER_FREQUENCIES[i]
                val freqStr = if (freq == freq.toInt().toFloat()) freq.toInt().toString() else String.format(Locale.US, "%.1f", freq)
                sb.append(freqStr)
                for (snap in snapshots) {
                    val v = if (i < snap.bands31Db.size) snap.bands31Db[i] else AudioMath.MIN_DB_FS
                    val safeV = if (v.isNaN() || v.isInfinite()) AudioMath.MIN_DB_FS else v
                    sb.append(String.format(Locale.US, ",%.2f", safeV))
                }
                sb.append("\n")
            }
        }

        return sb.toString()
    }

    // ==========================================
    // SHARING HELPERS
    // ==========================================

    fun shareCsv(context: Context, csvContent: String, baseFileName: String) {
        try {
            val exportDir = File(context.cacheDir, EXPORTS_DIR).apply { mkdirs() }
            val timeTag = fileTimestampFormat.format(Date())
            val safeName = baseFileName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(exportDir, "${safeName}_$timeTag.csv")

            FileOutputStream(file).use { out ->
                out.write(csvContent.toByteArray(Charsets.UTF_8))
            }

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "RTA Tuner Spectrum Export: $baseFileName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Spectrum CSV")
            if (context !is Activity) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing CSV", e)
        }
    }

    fun captureAndShareSurfacePng(
        surfaceView: SurfaceView,
        context: Context,
        title: String,
        onComplete: (Boolean) -> Unit
    ) {
        if (surfaceView.width <= 0 || surfaceView.height <= 0) {
            Log.w(TAG, "SurfaceView has non-positive dimensions (${surfaceView.width}x${surfaceView.height})")
            onComplete(false)
            return
        }

        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        val mainHandler = Handler(Looper.getMainLooper())

        PixelCopy.request(
            surfaceView,
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    try {
                        val exportDir = File(context.cacheDir, EXPORTS_DIR).apply { mkdirs() }
                        val timeTag = fileTimestampFormat.format(Date())
                        val safeName = title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        val file = File(exportDir, "rta_${safeName}_$timeTag.png")

                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }

                        val authority = "${context.packageName}.fileprovider"
                        val uri = FileProvider.getUriForFile(context, authority, file)

                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "RTA Tuner Spectrum: $title")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        val chooser = Intent.createChooser(shareIntent, "Share Spectrum Screenshot")
                        if (context !is Activity) {
                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                        onComplete(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed saving or sharing bitmap", e)
                        onComplete(false)
                    }
                } else {
                    Log.e(TAG, "PixelCopy failed with result code: $copyResult")
                    onComplete(false)
                }
            },
            mainHandler
        )
    }
}
