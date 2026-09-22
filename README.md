# RTA Tuner 🎵🚗

**RTA Tuner** is a high-performance, real-time audio spectrum analyzer (Real-Time Analyzer / RTA) built natively for Android, designed specifically for **car audio equalizer (EQ) tuning and acoustic calibration**. 

By processing acoustic measurements directly from the device's microphone or external measurement hardware, RTA Tuner provides real-time frequency distribution, broadband level metering, and acoustic diagnostics to help car audio enthusiasts and sound technicians eliminate cabin acoustic anomalies, flatten response curves, and achieve reference-grade sound quality.

---

> [!NOTE]
> **Agentic AI Development**  
> This project is engineered and developed entirely through **agentic AI pair programming** using **Google Antigravity** powered by **Gemini 3.8 Flash**. From audio architecture design and DSP implementation to 60 FPS GPU-accelerated rendering and unit test suites, every module is crafted autonomously under human supervision.

---

## 🚀 Features & Architecture Overview

- **Low-Latency Audio Capture Pipeline**: Direct PCM 16-bit 48 kHz mono recording via Android `AudioRecord` decoupled using a high-throughput lock-free circular ring buffer.
- **Fast Fourier Transform (FFT) Engine**: Precision frequency transformation powered by `JTransforms` with Hann windowing for minimal spectral leakage and selectable FFT sizes (1024, 2048, 4096 bins).
- **ISO 266 1/3-Octave RTA Aggregation**: Real-time bin mapping across 31 standardized acoustic bands from 20 Hz up to 20 kHz.
- **Temporal Ballistics & Smoothing**: Exponential Moving Average (EMA) temporal smoothing with selectable presets (Fast $\approx 100\text{ ms}$, Medium $\approx 300\text{ ms}$, Slow $\approx 1000\text{ ms}$) and configurable peak-hold decay dynamics.
- **Dedicated 60 FPS SurfaceView Display**: Hardware-accelerated GPU Canvas rendering (`lockHardwareCanvas`) completely decoupled from Jetpack Compose UI recomposition, featuring dual visualization modes (1/3 Octave Bars and Raw FFT Polyline) and dynamic dB range adjustment (-60 dB to -90 dB).
- **Broadband Audio Metrics**: True peak detection, RMS power calculation (in dBFS), and instant visual clipping overload indicators.

---

## 📋 Development Roadmap & Progress Checklist

- [x] **Stage 1: Audio Capture Pipeline & Diagnostics**
  - [x] Low-latency hardware capture thread (`AudioRecord`, PCM 16-bit, 48 kHz, mono).
  - [x] Non-blocking circular ring buffer to isolate audio capture from processing.
  - [x] Broadband level metering (RMS power, instantaneous peak, clipping detection).
  - [x] Lifecycle management, microphone runtime permission handling, and capture error recovery.
- [x] **Stage 2: Real-Time FFT & 1/3-Octave Spectral Analysis**
  - [x] `JTransforms` FFT integration with Hann windowing.
  - [x] Configurable FFT window sizes (1024, 2048, 4096 bins) for flexible time-frequency resolution.
  - [x] Standard ISO 266 31-band 1/3-octave acoustic frequency aggregation (20 Hz to 20 kHz).
  - [x] Exponential Moving Average (EMA) temporal smoothing engine with fast/medium/slow presets.
- [x] **Stage 3: High-Performance SurfaceView Spectrum Display**
  - [x] Dedicated rendering thread using `SurfaceView` with hardware-accelerated Canvas (`lockHardwareCanvas`).
  - [x] Decoupled 60 FPS render loop with isolated plot gutters preventing frequency label clipping.
  - [x] Dual display modes: 31-band 1/3-octave bar visualizer and continuous raw FFT polyline curve.
  - [x] Configurable peak-hold indicators with custom hold duration (0.5s - 2.5s) and decay rates (15 - 40 dB/s).
  - [x] Dynamic vertical dB range selection (-60 dB, -80 dB, -90 dB) and real-time execution telemetry.
- [ ] **Stage 4: Acoustic Test Signal Generator** *(Planned)*
  - [ ] Real-time pink noise generator using Paul Kellet's 3-pole IIR filter algorithm ($-3\text{ dB/octave}$ PSD).
  - [ ] Calibrated logarithmic sine sweep engine ($20\text{ Hz} - 20\text{ kHz}$) with configurable duration.
  - [ ] Low-latency streaming via `AudioTrack` with 50 ms anti-click Hann fade ramps to safeguard vehicle speakers.
- [ ] **Stage 5: Target Curves & Equalizer Recommendation Engine** *(Planned)*
  - [ ] Reference acoustic target curve overlays (Harman In-Room, JBL / AudioControl House Curves, Flat, Custom Tilt).
  - [ ] Automated real-time deviation analysis comparing cabin response against target curves.
  - [ ] Parametric EQ (PEQ) correction recommendation engine (frequency, Q-factor, and boost/cut gain suggestions).
- [ ] **Stage 6: Multi-Measurement & Spatial Averaging** *(Planned)*
  - [ ] Multi-point measurement collection (driver head position, passenger seat, spatial ear clusters).
  - [ ] Spatial averaging algorithms (RMS, Vector, and Variance-weighted) to eliminate cabin standing-wave anomalies.
  - [ ] Acoustic delay and impulse response estimation for speaker time-alignment.
- [ ] **Stage 7: USB Measurement Microphone & Calibration** *(Planned)*
  - [ ] Native USB Audio Class 2.0 (UAC2) support for external calibrated mics (miniDSP UMIK-1, Dayton Audio UMM-6).
  - [ ] Import and interpolation of microphone frequency calibration files (`.cal`, `.txt` at 0° and 90° orientations).
  - [ ] Absolute SPL (dB-SPL) calibration and hardware sensitivity offsets.

---

## 🛠️ Tech Stack

- **Platform**: Android (Native)
- **Minimum SDK**: API 26 (Android 8.0 Oreo)
- **Target SDK / Compile SDK**: API 35 (Android 15)
- **Language**: Kotlin 2.0.21
- **UI Framework**: Jetpack Compose + Material 3 (declarative UI & controls)
- **Rendering Engine**: Hardware-Accelerated `SurfaceView` Canvas (GPU Skia)
- **DSP / Math**: `JTransforms 2.4`, Kotlin Coroutines
- **Build System**: Gradle 8.13.0 with Kotlin DSL (`build.gradle.kts`)

---

## 📦 Build & Setup Instructions

### Prerequisites
1. **Java Development Kit (JDK)**: JDK 21 configured in your environment or Android Studio.
2. **Android SDK**: Android SDK Build-Tools 35.0.0 and Platform API 35 installed.
3. **Hardware / Emulator**: Physical Android device recommended for accurate microphone latency and acoustics, or an Android emulator with microphone audio forwarding enabled.

### Clone the Repository
```bash
git clone https://github.com/<username>/<repo-name>.git
cd RTA
```

### Build Debug APK
Using the Gradle wrapper:

**Linux / macOS:**
```bash
./gradlew assembleDebug
```

**Windows (PowerShell / Command Prompt):**
```powershell
.\gradlew.bat assembleDebug
```
The output APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`.

### Run Unit Tests
To execute the comprehensive unit test suite (audio math, FFT transformation, ballistics, ring buffer):

```powershell
.\gradlew.bat testDebugUnitTest
```

### Install Directly to Connected Device
Ensure USB Debugging is enabled on your device:

```powershell
.\gradlew.bat installDebug
```

---

## 🔒 Permissions

RTA Tuner requires the following permission:
- `android.permission.RECORD_AUDIO`: Required to capture acoustic audio data from the device microphone hardware. Audio data is processed entirely on-device in volatile memory and is never recorded to disk or transmitted over any network.

---

## 📄 License

This project is developed for acoustic analysis and audio tuning. Refer to project repository terms for licensing details.
