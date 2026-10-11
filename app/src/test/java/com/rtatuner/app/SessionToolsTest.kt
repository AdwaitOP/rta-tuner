package com.rtatuner.app

import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.database.BandsConverter
import com.rtatuner.app.core.database.SessionSnapshotEntity
import com.rtatuner.app.core.database.SessionWithSnapshots
import com.rtatuner.app.core.database.TuningSessionEntity
import com.rtatuner.app.core.dsp.Iso31Bands
import com.rtatuner.app.core.export.RtaExportManager
import com.rtatuner.app.core.session.RtaSnapshot
import com.rtatuner.app.core.session.SnapshotPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SessionToolsTest {

    // ==========================================
    // 1. BANDS CONVERTER & DATA INTEGRITY TESTS
    // ==========================================

    @Test
    fun testBandsConverterRoundTrip() {
        val converter = BandsConverter()
        val originalBands = FloatArray(Iso31Bands.NUM_BANDS) { i ->
            -60.0f + (i * 1.5f) // values from -60 to -15 dB
        }

        val encoded = converter.fromFloatArray(originalBands)
        assertNotNull("Encoded string must not be null", encoded)
        assertTrue("Encoded string should contain commas", encoded!!.contains(","))

        val decoded = converter.toFloatArray(encoded)
        assertNotNull("Decoded array must not be null", decoded)
        assertEquals("Decoded array must have 31 bands", Iso31Bands.NUM_BANDS, decoded!!.size)

        for (i in originalBands.indices) {
            assertEquals("Band $i level must match within 0.02 dB", originalBands[i], decoded[i], 0.02f)
        }
    }

    @Test
    fun testBandsConverterHandlesNullEmptyAndNaN() {
        val converter = BandsConverter()

        assertNull("Null array converts to null", converter.fromFloatArray(null))
        assertNull("Null string converts to null", converter.toFloatArray(null))
        assertNull("Empty string converts to null", converter.toFloatArray(""))
        assertNull("Blank string converts to null", converter.toFloatArray("   "))

        // Corrupt array containing NaN and Infinity
        val corruptArray = floatArrayOf(-20.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.0f)
        val encoded = converter.fromFloatArray(corruptArray)
        assertNotNull(encoded)
        assertFalse("Encoded string must not contain NaN", encoded!!.contains("NaN"))
        assertFalse("Encoded string must not contain Infinity", encoded.contains("Infinity"))

        val decoded = converter.toFloatArray(encoded)
        assertNotNull(decoded)
        assertEquals(5, decoded!!.size)
        assertEquals(-20.0f, decoded[0], 0.02f)
        assertEquals(AudioMath.MIN_DB_FS, decoded[1], 0.02f) // NaN clamped
        assertEquals(AudioMath.MIN_DB_FS, decoded[2], 0.02f) // +Inf clamped
        assertEquals(AudioMath.MIN_DB_FS, decoded[3], 0.02f) // -Inf clamped
        assertEquals(0.0f, decoded[4], 0.02f)
    }

    @Test
    fun testSnapshotDataIntegrityWithFullAcousticContext() {
        val originalBands = FloatArray(Iso31Bands.NUM_BANDS) { -30.0f }
        val noiseBands = FloatArray(Iso31Bands.NUM_BANDS) { -70.0f }

        val snapshot = RtaSnapshot(
            name = "Before EQ - Front Left",
            timestamp = 1700000000000L,
            bands31Db = originalBands,
            isSplMode = true,
            splOffsetDb = 104.2f,
            calibrationName = "miniDSP UMIK-1 (90 deg)",
            targetCurveName = "Harman Car (+9 dB)",
            windowSize = 2048,
            isNoiseSubtractionActive = true,
            activeNoiseFloorBands = noiseBands,
            colorHex = SnapshotPalette.getColorForIndex(0),
            isVisible = true
        )

        assertEquals("Before EQ - Front Left", snapshot.name)
        assertEquals(1700000000000L, snapshot.timestamp)
        assertTrue(snapshot.isSplMode)
        assertEquals(104.2f, snapshot.splOffsetDb, 0.01f)
        assertEquals("miniDSP UMIK-1 (90 deg)", snapshot.calibrationName)
        assertEquals("Harman Car (+9 dB)", snapshot.targetCurveName)
        assertEquals(2048, snapshot.windowSize)
        assertTrue(snapshot.isNoiseSubtractionActive)
        assertNotNull(snapshot.activeNoiseFloorBands)
        assertEquals(31, snapshot.activeNoiseFloorBands!!.size)
        assertEquals(0xFF00E5FF, snapshot.colorHex) // Cyan
        assertTrue(snapshot.isVisible)

        // Verify copy and value equality
        val copySnapshot = snapshot.copy()
        assertEquals(snapshot, copySnapshot)
        assertEquals(snapshot.hashCode(), copySnapshot.hashCode())
    }

    // ==========================================
    // 2. CSV EXPORT FORMAT TESTS
    // ==========================================

    @Test
    fun testCsvExportFormatSingleSnapshot() {
        val bands = FloatArray(Iso31Bands.NUM_BANDS) { i -> -40.0f + i }
        val snapshot = RtaSnapshot(
            name = "Baseline Measurement",
            timestamp = 1710000000000L,
            bands31Db = bands,
            isSplMode = true,
            splOffsetDb = 94.0f,
            calibrationName = "Dayton iMM-6",
            targetCurveName = "AudioControl (+6 dB)",
            windowSize = 2048,
            isNoiseSubtractionActive = true
        )

        val csv = RtaExportManager.formatSnapshotCsv(snapshot, sessionName = "Aura Front Stage")

        // Verify header metadata comments
        assertTrue("CSV must have CabinRTA header", csv.contains("# CabinRTA Spectrum Export"))
        assertTrue("CSV must contain session name", csv.contains("# Session: Aura Front Stage"))
        assertTrue("CSV must contain snapshot name", csv.contains("# Snapshot: Baseline Measurement"))
        assertTrue("CSV must contain calibration name", csv.contains("# Calibration Curve: Dayton iMM-6"))
        assertTrue("CSV must contain target curve", csv.contains("# Target Curve: AudioControl (+6 dB)"))
        assertTrue("CSV must contain noise subtraction state", csv.contains("# Noise Subtraction: Active"))
        assertTrue("CSV must contain SPL mode and offset", csv.contains("# Measurement Mode: Calibrated SPL (Offset: +94.00 dB)"))
        assertTrue("CSV must contain window size", csv.contains("# Window Size: 2048"))

        // Verify column headers
        assertTrue("CSV must contain column header", csv.contains("Frequency_Hz,Level_dB"))

        // Verify frequency bands and values
        val lines = csv.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("Should have header row + 31 data rows", 32, lines.size)
        assertEquals("Frequency_Hz,Level_dB", lines[0])

        // Check first band (20 Hz)
        val firstRow = lines[1].split(",")
        assertEquals("20", firstRow[0])
        assertEquals(-40.0f, firstRow[1].toFloat(), 0.01f)

        // Check 1 kHz band (index 17: 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000)
        val oneKhzRow = lines[18].split(",")
        assertEquals("1000", oneKhzRow[0])
        assertEquals(-40.0f + 17, oneKhzRow[1].toFloat(), 0.01f)

        // Check last band (20 kHz)
        val lastRow = lines.last().split(",")
        assertEquals("20000", lastRow[0])
        assertEquals(-40.0f + 30, lastRow[1].toFloat(), 0.01f)
    }

    @Test
    fun testCsvExportFormatMultiSnapshotSession() {
        val sessionEntity = TuningSessionEntity(
            id = 1,
            name = "Civic Audio Tune",
            notes = "Replaced tweeters, smoothed 4kHz spike",
            createdAt = 1710000000000L,
            activeCalibrationName = "UMIK-1",
            activeTargetCurveName = "Harman Car (+9 dB)",
            isSplMode = false,
            splOffsetDb = 0.0f,
            windowSize = 2048,
            isNoiseSubtractionActive = false
        )

        val snap1Bands = FloatArray(Iso31Bands.NUM_BANDS) { -30.0f }
        val snap2Bands = FloatArray(Iso31Bands.NUM_BANDS) { -25.0f }

        val snap1 = SessionSnapshotEntity(
            id = 1,
            sessionId = 1,
            name = "Before EQ",
            timestamp = 1710000000000L,
            bands31Db = snap1Bands,
            colorHex = 0xFF00E5FF
        )
        val snap2 = SessionSnapshotEntity(
            id = 2,
            sessionId = 1,
            name = "After EQ",
            timestamp = 1710000060000L,
            bands31Db = snap2Bands,
            colorHex = 0xFFFF4081
        )

        val sessionWithSnapshots = SessionWithSnapshots(sessionEntity, listOf(snap1, snap2))
        val csv = RtaExportManager.formatSessionWithSnapshotsCsv(sessionWithSnapshots)

        assertTrue(csv.contains("# CabinRTA Session Multi-Snapshot Export"))
        assertTrue(csv.contains("# Session: Civic Audio Tune"))
        assertTrue(csv.contains("# Notes: Replaced tweeters, smoothed 4kHz spike"))
        assertTrue(csv.contains("# Snapshot Count: 2"))
        assertTrue(csv.contains("Frequency_Hz,\"Before EQ (dB)\",\"After EQ (dB)\""))

        val dataLines = csv.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(32, dataLines.size) // header + 31 bands

        val firstRow = dataLines[1].split(",")
        assertEquals("20", firstRow[0])
        assertEquals(-30.0f, firstRow[1].toFloat(), 0.01f)
        assertEquals(-25.0f, firstRow[2].toFloat(), 0.01f)
    }

    // ==========================================
    // 3. CROSS-MODE OFFSET NORMALIZATION TESTS
    // ==========================================

    @Test
    fun testCrossModeOffsetNormalization() {
        val snapshotDbFs = -20.0f
        val splOffset = 104.0f

        // Case A: Snapshot taken in dBFS (splOffset = 0), viewed in SPL mode (current offset = 104)
        val crossModeOffsetToSpl = splOffset
        val effectiveSpl = snapshotDbFs + crossModeOffsetToSpl
        assertEquals(84.0f, effectiveSpl, 0.01f) // -20 dBFS + 104 = 84 dBSPL

        // Case B: Snapshot taken in SPL mode (snapshot offset = 104, value = 84 dBSPL), viewed in dBFS mode
        val snapshotSpl = 84.0f
        val crossModeOffsetToDbFs = -splOffset
        val effectiveDbFs = snapshotSpl + crossModeOffsetToDbFs
        assertEquals(-20.0f, effectiveDbFs, 0.01f) // 84 dBSPL - 104 = -20 dBFS

        // Case C: Both in SPL mode with different calibration offsets
        val snapOffset1 = 100.0f
        val snapVal1 = 80.0f
        val currentOffset2 = 105.0f
        val crossModeOffsetSplDiff = currentOffset2 - snapOffset1 // +5 dB
        val effectiveAdjustedSpl = snapVal1 + crossModeOffsetSplDiff
        assertEquals(85.0f, effectiveAdjustedSpl, 0.01f)
    }

    @Test
    fun testSnapshotPaletteCycling() {
        val color0 = SnapshotPalette.getColorForIndex(0)
        val color1 = SnapshotPalette.getColorForIndex(1)
        val color2 = SnapshotPalette.getColorForIndex(2)

        assertTrue(color0 != color1)
        assertTrue(color1 != color2)

        // Verify wrap-around
        val count = SnapshotPalette.COLORS.size
        assertEquals(color0, SnapshotPalette.getColorForIndex(count))
    }
}
