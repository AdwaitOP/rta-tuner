package com.rtatuner.app.core.audio

import android.media.AudioDeviceInfo
import java.util.Locale

/**
 * Classification category of an audio input hardware device.
 */
enum class DeviceCategory {
    BUILT_IN,
    USB,
    WIRED_3_5MM,
    BLUETOOTH,
    OTHER
}

/**
 * User-facing representation of an audio input device.
 */
data class AudioInputDeviceInfo(
    val id: Int,
    val name: String,
    val type: Int,
    val category: DeviceCategory,
    val typeName: String,
    val supportedSampleRates: List<Int>,
    val channelCounts: List<Int> = listOf(1),
    val isSelected: Boolean = false,
    val isCurrentlyRouted: Boolean = false
) {
    val isUsb: Boolean get() = category == DeviceCategory.USB
    val isWired: Boolean get() = category == DeviceCategory.WIRED_3_5MM
    val isBluetooth: Boolean get() = category == DeviceCategory.BLUETOOTH
    val isBuiltIn: Boolean get() = category == DeviceCategory.BUILT_IN

    /**
     * Bluetooth SCO inputs are typically restricted to voice telephony bandwidth
     * (~8 kHz narrowband or ~16 kHz wideband). This yields a Nyquist cutoff of at most
     * 4–8 kHz, severely truncating full audio spectrum analysis (20 Hz – 20 kHz).
     */
    val isBandwidthLimited: Boolean
        get() = isBluetooth || (supportedSampleRates.isNotEmpty() && (supportedSampleRates.maxOrNull() ?: 48000) <= 16000)

    val ratesSummary: String
        get() = if (supportedSampleRates.isEmpty()) {
            "Hardware Native (Auto)"
        } else {
            supportedSampleRates.joinToString(", ") { "$it Hz" }
        }

    companion object {
        fun fromAudioDeviceInfo(
            device: AudioDeviceInfo,
            isSelected: Boolean = false,
            isCurrentlyRouted: Boolean = false
        ): AudioInputDeviceInfo {
            val (category, typeName) = classifyDevice(device.type)
            val rawName = device.productName?.toString()?.trim()
            val friendlyName = when {
                !rawName.isNullOrBlank() && !rawName.equals("default", ignoreCase = true) -> rawName
                category == DeviceCategory.BUILT_IN -> "Built-in Microphone"
                category == DeviceCategory.USB -> "USB Audio Microphone"
                category == DeviceCategory.WIRED_3_5MM -> "Wired Headset / Mic (3.5mm)"
                category == DeviceCategory.BLUETOOTH -> "Bluetooth Voice Headset (SCO)"
                else -> "Audio Input Device #${device.id}"
            }

            val rates = device.sampleRates.filter { it > 0 }.sorted()
            val channels = device.channelCounts.filter { it > 0 }.sorted()

            return AudioInputDeviceInfo(
                id = device.id,
                name = friendlyName,
                type = device.type,
                category = category,
                typeName = typeName,
                supportedSampleRates = rates,
                channelCounts = if (channels.isEmpty()) listOf(1) else channels,
                isSelected = isSelected,
                isCurrentlyRouted = isCurrentlyRouted
            )
        }

        fun isBandwidthLimited(device: AudioDeviceInfo): Boolean {
            val (category, _) = classifyDevice(device.type)
            val isBt = category == DeviceCategory.BLUETOOTH
            val rates = device.sampleRates.filter { it > 0 }
            val maxRate = rates.maxOrNull()
            return isBt || (maxRate != null && maxRate <= 16000)
        }

        fun classifyDevice(type: Int): Pair<DeviceCategory, String> {
            return when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> Pair(DeviceCategory.BUILT_IN, "Built-in Mic")
                AudioDeviceInfo.TYPE_USB_DEVICE -> Pair(DeviceCategory.USB, "USB Audio Class (UAC)")
                AudioDeviceInfo.TYPE_USB_HEADSET -> Pair(DeviceCategory.USB, "USB Headset / Interface")
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> Pair(DeviceCategory.WIRED_3_5MM, "Wired Headset (3.5mm)")
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Pair(DeviceCategory.WIRED_3_5MM, "Wired Input (3.5mm)")
                AudioDeviceInfo.TYPE_LINE_ANALOG -> Pair(DeviceCategory.WIRED_3_5MM, "Line In (Analog)")
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> Pair(DeviceCategory.USB, "Line In (Digital)")
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Pair(DeviceCategory.BLUETOOTH, "Bluetooth SCO (Voice)")
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> Pair(DeviceCategory.BLUETOOTH, "Bluetooth Audio")
                else -> Pair(DeviceCategory.OTHER, "Audio Input")
            }
        }
    }
}
