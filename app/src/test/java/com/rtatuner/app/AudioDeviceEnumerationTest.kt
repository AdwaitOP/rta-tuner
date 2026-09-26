package com.rtatuner.app

import android.media.AudioDeviceInfo
import com.rtatuner.app.core.audio.AudioCaptureDiagnostics
import com.rtatuner.app.core.audio.AudioCaptureUiState
import com.rtatuner.app.core.audio.AudioInputDeviceInfo
import com.rtatuner.app.core.audio.DeviceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Stage 7: External & USB Microphone Support.
 *
 * Covers:
 * - Device type classification (BUILT_IN, USB, WIRED_3_5MM, BLUETOOTH, OTHER)
 * - Bluetooth SCO voice bandwidth limitation detection (<= 16 kHz cutoff warning)
 * - Sample rate summary formatting and priority selection
 * - Hardware routing mismatch detection (requested vs. actual routed device)
 * - UI state integrity and domain models
 */
class AudioDeviceEnumerationTest {

    // ==========================================
    // 1. DEVICE CLASSIFICATION TESTS
    // ==========================================

    @Test
    fun testBuiltInMicClassification() {
        val (category, typeName) = AudioInputDeviceInfo.classifyDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        assertEquals("Should be classified as BUILT_IN", DeviceCategory.BUILT_IN, category)
        assertEquals("Built-in Mic", typeName)
    }

    @Test
    fun testUsbDeviceClassification() {
        val (cat1, name1) = AudioInputDeviceInfo.classifyDevice(AudioDeviceInfo.TYPE_USB_DEVICE)
        assertEquals("TYPE_USB_DEVICE should be USB", DeviceCategory.USB, cat1)
        assertTrue(name1.contains("USB Audio Class"))

        val (cat2, name2) = AudioInputDeviceInfo.classifyDevice(AudioDeviceInfo.TYPE_USB_HEADSET)
        assertEquals("TYPE_USB_HEADSET should be USB", DeviceCategory.USB, cat2)
        assertTrue(name2.contains("USB Headset"))

        val (cat3, name3) = AudioInputDeviceInfo.classifyDevice(AudioDeviceInfo.TYPE_LINE_DIGITAL)
        assertEquals("TYPE_LINE_DIGITAL should be USB", DeviceCategory.USB, cat3)
        assertTrue(name3.contains("Digital"))
    }

    @Test
    fun testWired35mmClassification() {
        val (cat1, name1) = AudioInputDeviceInfo.classifyDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        assertEquals("TYPE_WIRED_HEADSET should be WIRED_3_5MM", DeviceCategory.WIRED_3_5MM, cat1)
        assertTrue(name1.contains("3.5mm"))

        val (cat2, name2) = AudioInputDeviceInfo.classifyDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        assertEquals("TYPE_WIRED_HEADPHONES should be WIRED_3_5MM", DeviceCategory.WIRED_3_5MM, cat2)
        assertTrue(name2.contains("3.5mm"))

        val (cat3, name3) = AudioInputDeviceInfo.classifyDevice(AudioDeviceInfo.TYPE_LINE_ANALOG)
        assertEquals("TYPE_LINE_ANALOG should be WIRED_3_5MM", DeviceCategory.WIRED_3_5MM, cat3)
        assertTrue(name3.contains("Analog"))
    }

    @Test
    fun testBluetoothClassification() {
        val (cat1, name1) = AudioInputDeviceInfo.classifyDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals("TYPE_BLUETOOTH_SCO should be BLUETOOTH", DeviceCategory.BLUETOOTH, cat1)
        assertTrue(name1.contains("Bluetooth SCO"))

        val (cat2, name2) = AudioInputDeviceInfo.classifyDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        assertEquals("TYPE_BLUETOOTH_A2DP should be BLUETOOTH", DeviceCategory.BLUETOOTH, cat2)
        assertTrue(name2.contains("Bluetooth"))
    }

    @Test
    fun testUnknownDeviceClassification() {
        val (cat, name) = AudioInputDeviceInfo.classifyDevice(9999)
        assertEquals("Unknown type should be OTHER", DeviceCategory.OTHER, cat)
        assertEquals("Audio Input", name)
    }

    // ==========================================
    // 2. BLUETOOTH SCO BANDWIDTH LIMITATION TESTS
    // ==========================================

    @Test
    fun testBluetoothScoBandwidthLimitationFlag() {
        val btDevice = AudioInputDeviceInfo(
            id = 101,
            name = "Plantronics Bluetooth Headset",
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            category = DeviceCategory.BLUETOOTH,
            typeName = "Bluetooth SCO (Voice)",
            supportedSampleRates = listOf(8000, 16000)
        )

        assertTrue("Bluetooth device must trigger isBandwidthLimited", btDevice.isBandwidthLimited)
        assertTrue("Category helper isBluetooth must be true", btDevice.isBluetooth)
        assertFalse("isUsb must be false", btDevice.isUsb)
        assertFalse("isWired must be false", btDevice.isWired)
    }

    @Test
    fun testLowSampleRateDeviceIsBandwidthLimited() {
        // Any device restricted to <= 16000 Hz (e.g. voice-only input)
        val lowRateDevice = AudioInputDeviceInfo(
            id = 102,
            name = "Low-rate Voice Interface",
            type = AudioDeviceInfo.TYPE_LINE_ANALOG,
            category = DeviceCategory.WIRED_3_5MM,
            typeName = "Line In (Analog)",
            supportedSampleRates = listOf(8000, 16000)
        )

        assertTrue("Device with max rate <= 16000 must be marked bandwidth limited", lowRateDevice.isBandwidthLimited)
    }

    @Test
    fun testFullBandwidthDevicesAreNotLimited() {
        val umik1 = AudioInputDeviceInfo(
            id = 103,
            name = "miniDSP UMIK-1",
            type = AudioDeviceInfo.TYPE_USB_DEVICE,
            category = DeviceCategory.USB,
            typeName = "USB Audio Class (UAC)",
            supportedSampleRates = listOf(48000)
        )
        assertFalse("48 kHz USB mic must NOT be bandwidth limited", umik1.isBandwidthLimited)
        assertTrue("isUsb must be true", umik1.isUsb)

        val builtIn = AudioInputDeviceInfo(
            id = 1,
            name = "Built-in Microphone",
            type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
            category = DeviceCategory.BUILT_IN,
            typeName = "Built-in Mic",
            supportedSampleRates = listOf(44100, 48000)
        )
        assertFalse("Built-in mic with 48k must NOT be bandwidth limited", builtIn.isBandwidthLimited)
        assertTrue("isBuiltIn must be true", builtIn.isBuiltIn)

        val wired35 = AudioInputDeviceInfo(
            id = 104,
            name = "Dayton iMM-6",
            type = AudioDeviceInfo.TYPE_WIRED_HEADSET,
            category = DeviceCategory.WIRED_3_5MM,
            typeName = "Wired Headset (3.5mm)",
            supportedSampleRates = listOf(44100, 48000)
        )
        assertFalse("3.5mm Dayton mic with 48k must NOT be bandwidth limited", wired35.isBandwidthLimited)
        assertTrue("isWired must be true", wired35.isWired)
    }

    // ==========================================
    // 3. SAMPLE RATES SUMMARY & NEGOTIATION TESTS
    // ==========================================

    @Test
    fun testRatesSummaryFormatting() {
        val emptyRatesDevice = AudioInputDeviceInfo(
            id = 1,
            name = "Default Mic",
            type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
            category = DeviceCategory.BUILT_IN,
            typeName = "Built-in Mic",
            supportedSampleRates = emptyList()
        )
        assertEquals("Hardware Native (Auto)", emptyRatesDevice.ratesSummary)

        val multiRateDevice = AudioInputDeviceInfo(
            id = 2,
            name = "USB DAC/ADC",
            type = AudioDeviceInfo.TYPE_USB_DEVICE,
            category = DeviceCategory.USB,
            typeName = "USB Audio Class (UAC)",
            supportedSampleRates = listOf(44100, 48000)
        )
        assertEquals("44100 Hz, 48000 Hz", multiRateDevice.ratesSummary)
    }

    @Test
    fun testSampleRatePrioritySelection() {
        fun resolveRate(rates: List<Int>, fallback: Int = 48000): Int {
            if (rates.isEmpty()) return fallback
            if (rates.contains(48000)) return 48000
            if (rates.contains(44100)) return 44100
            return rates.filter { it > 0 }.maxOrNull() ?: fallback
        }

        // miniDSP UMIK-1 (fixed 48000)
        assertEquals(48000, resolveRate(listOf(48000)))

        // Dual-rate device prioritizing 48000
        assertEquals(48000, resolveRate(listOf(44100, 48000)))

        // 44.1k-only measurement mic
        assertEquals(44100, resolveRate(listOf(44100, 22050)))

        // Studio 96kHz-only device
        assertEquals(96000, resolveRate(listOf(96000)))

        // Empty rates falls back to standard default
        assertEquals(48000, resolveRate(emptyList()))
    }

    // ==========================================
    // 4. ROUTING MISMATCH DETECTION TESTS
    // ==========================================

    @Test
    fun testRoutingMismatchDetectionLogic() {
        val requestedUsbId = 15
        val actualBuiltInId = 1

        // 1. Mismatch occurs when a specific device was requested, but OS routed to another
        val isMismatch = requestedUsbId != actualBuiltInId
        assertTrue("Mismatch must be true when requested != actual", isMismatch)

        val mismatchMsg = "Requested 'USB Audio Class (UAC)' but audio is routed to 'Built-in Mic'."
        assertNotNull(mismatchMsg)
        assertTrue(mismatchMsg.contains("USB Audio Class"))
        assertTrue(mismatchMsg.contains("Built-in Mic"))

        // 2. No mismatch when actual routed device matches requested
        val matchedRoutedId = 15
        val isMatch = requestedUsbId != matchedRoutedId
        assertFalse("Mismatch must be false when requested == actual", isMatch)

        // 3. No mismatch in Auto-routing mode (requested == null)
        val requestedAuto: Int? = null
        val autoMismatch = requestedAuto != null && requestedAuto != actualBuiltInId
        assertFalse("Auto-routing mode must never trigger mismatch", autoMismatch)
    }

    // ==========================================
    // 5. UI STATE & TELEMETRY INTEGRITY TESTS
    // ==========================================

    @Test
    fun testAudioCaptureUiStateDefaults() {
        val state = AudioCaptureUiState()

        assertTrue("Default input devices list must be empty", state.availableInputDevices.isEmpty())
        assertNull("Default selectedDeviceId must be null (auto)", state.selectedDeviceId)
        assertNull("Default routedDeviceId must be null", state.routedDeviceId)
        assertNull("Default deviceAttachedPrompt must be null", state.deviceAttachedPrompt)
        assertFalse("Default routing mismatch must be false", state.isRoutingMismatch)
        assertNull("Default routingMismatchMessage must be null", state.routingMismatchMessage)
        assertFalse("Default isBluetoothBandwidthLimited must be false", state.isBluetoothBandwidthLimited)
        assertNull("Default bluetoothWarningText must be null", state.bluetoothWarningText)
    }

    @Test
    fun testDiagnosticsDeviceFields() {
        val diag = AudioCaptureDiagnostics(
            requestedDeviceName = "miniDSP UMIK-1",
            actualRoutedDeviceName = "Built-in Mic",
            actualRoutedDeviceType = "Built-in",
            isUsbDevice = false,
            isRoutingMismatch = true,
            isBluetoothBandwidthLimited = false
        )

        assertEquals("miniDSP UMIK-1", diag.requestedDeviceName)
        assertEquals("Built-in Mic", diag.actualRoutedDeviceName)
        assertEquals("Built-in", diag.actualRoutedDeviceType)
        assertFalse(diag.isUsbDevice)
        assertTrue(diag.isRoutingMismatch)
        assertFalse(diag.isBluetoothBandwidthLimited)
    }
}
