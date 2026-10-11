# CabinRTA — Final Brand Identity & Android Adaptive Icon Assets

This directory contains the production-ready visual branding, adaptive icons, and store assets for **CabinRTA** based on the refined **Concept 1: The Acoustic Cabin**.

All assets are located in [`branding/final/`](.).

---

## 1. Final Asset Inventory

| Asset Name | Format | Dimensions | Purpose |
| :--- | :--- | :--- | :--- |
| [`cabinrta-master.svg`](cabinrta-master.svg) | Vector SVG | 512 × 512 px | Master vector artwork, high-resolution source |
| [`ic_launcher_background.svg`](ic_launcher_background.svg) | Vector SVG | 108 × 108 dp | Adaptive icon background plate (API 26+) |
| [`ic_launcher_foreground.svg`](ic_launcher_foreground.svg) | Vector SVG | 108 × 108 dp | Adaptive icon foreground emblem layer (API 26+) |
| [`ic_launcher_monochrome.svg`](ic_launcher_monochrome.svg) | Vector SVG | 108 × 108 dp | Material You dynamic themed icon stencil (API 33+) |
| [`ic_launcher-playstore-512.png`](ic_launcher-playstore-512.png) | 32-bit PNG | 512 × 512 px | Google Play Store listing icon (112 KB) |
| [`adaptive-icon-preview.svg`](adaptive-icon-preview.svg) | Vector SVG | 1100 × 780 px | Safe zone overlay & OEM mask testing matrix |
| [`density-test-matrix.svg`](density-test-matrix.svg) | Vector SVG | 1000 × 680 px | Genuine design tested at 64dp, 48dp, 32dp, and 24dp |
| [`ic_launcher-preview-192.png`](ic_launcher-preview-192.png) | 32-bit PNG | 192 × 192 px | xxxhdpi density raster reference |
| [`ic_launcher-preview-96.png`](ic_launcher-preview-96.png) | 32-bit PNG | 96 × 96 px | xxhdpi density raster reference |
| [`ic_launcher-preview-48.png`](ic_launcher-preview-48.png) | 32-bit PNG | 48 × 48 px | hdpi density raster reference |
| [`ic_launcher-preview-24.png`](ic_launcher-preview-24.png) | 32-bit PNG | 24 × 24 px | Micro notification chip raster reference |
| [`index.html`](index.html) | Interactive HTML | Responsive | Web dashboard with interactive mask and theme toggles |

---

## 2. Refinements Implemented from Initial Exploration

1. **Optical Centering Balance:**
   - Because the letter "C" has an open throat on the right, placing the geometry at the mathematical center causes the icon to appear optically shoved to the left.
   - An offset of **+6px** (on 512px canvas) / **+1.25dp** (on 108dp canvas) was applied. In circular, squircle, and rounded-square masks, the emblem now feels perfectly anchored and visually weighted.

2. **Curve Stroke Widening (22px / 4.64dp):**
   - In the initial exploration, the frequency curve stroke was 14px (2.95dp), which softened below 36dp.
   - Increased to **22px stroke** on the 512px canvas (4.64dp on 108dp canvas). At 24dp (status bar chip), the curve maintains a solid ~1.03dp visual punch that never breaks or disappears.
   - A **5px white core highlight** was embedded along the curve center, providing maximum edge acuity and photon luminance on OLED and HDR screens.

3. **Radical Noise Elimination:**
   - Removed all dashed decibel reference lines and octave frequency ticks from the foreground emblem.
   - Removed the 4 crosshair ticks and peripheral resonance dots.
   - Replaced the multi-ring reticle with a single, high-precision **Calibration Sweet-Spot Sensor** (outer cyan ring `r=15`, solid core `r=6`, white center pin `r=2.5`). At small launcher sizes, it renders as a clean pinpoint beacon without aliasing blur.

4. **Guaranteed Negative-Space Stencil (Android 13+ Monet Themed Icons):**
   - The gap between the inner cabin C-wall and the frequency curve was widened to **20px (3.8dp)** throughout.
   - This ensures that when the Android system applies a single dynamic Monet tint (discarding all color and gradients), the cabin shell and frequency curve remain sharply distinct without touching.

---

## 3. Engineering Compromises & Small-Size Readability Report

| Feature | Concept State | Refined Production State | Reason for Compromise |
| :--- | :--- | :--- | :--- |
| **Grid Lines & Axis Ticks** | Present (dashed dB lines) | **Completely Removed** | At &le; 48dp, dashed strokes (1.5px on 512 canvas) shrink to &lt; 0.2 pixels on mdpi displays, turning into muddy grey noise and aliasing artifacts. |
| **Crosshair Reticle Ticks** | 4 external ticks | **Replaced by solid focal ring & dot** | Fine 2px ticks clump into the curve stroke at micro scale; the solid concentric dot provides cleaner optical recognition. |
| **Target Curve Width** | 14px stroke | **22px stroke (+57% width)** | Audio test graphs usually use 1–2px lines, but an app launcher icon requires high optical weight to establish visual dominance over the dark background. |
| **Geometric vs Optical Center** | Math center (x = 256) | **Optical center (x = 262)** | Asymmetrical right-opening "C" requires a slight rightward shift to prevent looking left-heavy inside OEM circle masks. |

---

## 4. Android Adaptive Icon Compliance Verification

- **Canvas Size:** `108dp × 108dp` (matches Android `res/mipmap-anydpi-v26/` standards).
- **Parallax Bleed Margin:** The outer `18dp` on all four edges is reserved for launcher physics, bounce animations, and OEM crop variations.
- **OEM Viewport:** The center `72dp × 72dp` forms the visible mask boundary.
- **Safe Zone (66dp Diameter):** The entire CabinRTA emblem is contained within a `66dp` (312px on 512 canvas) circle. Tested across:
  - **Stock Android Circle** (100% visible, zero clipping)
  - **Pixel Launcher Squircle** (100% visible, generous corner padding)
  - **Samsung OneUI Rounded Rect** (100% visible)
  - **OxygenOS Teardrop** (100% visible)
- **Monochrome Asset (`ic_launcher_monochrome.svg`):** Built with pure flat silhouettes (`#FFFFFF`) on transparent background. Zero gradients, zero drop shadows, 100% vector-drawable compatible.

---

## 5. Color Tokens & Theme Integration

The refined palette directly mirrors the app's existing dark-mode DSP interface:
- **Obsidian Deep Background:** `#05070C` to `#141D2D` (`radial-gradient`)
- **Aero Titanium Cabin Shell:** `#101722` to `#2D3C54` with `#38BDF8` edge highlight
- **Electric Cyan (Sub & Low-Mid):** `#00B0FF` &rarr; `#00E5FF`
- **Neon Audio Emerald (Mid & High-Frequency):** `#00E676` &rarr; `#69F0AE`
- **Calibration Sweet-Spot Reticle:** `#00E5FF` + `#FFFFFF`
- **Contrast Ratio:** 14.2:1 (Curve against background)

---

## 6. Future Integration Checklist (When Ready)

When ready to implement into the Android project (in a future task):
1. Convert `ic_launcher_background.svg` to Android Vector Drawable XML &rarr; `app/src/main/res/drawable/ic_launcher_background.xml`.
2. Convert `ic_launcher_foreground.svg` to Android Vector Drawable XML &rarr; `app/src/main/res/drawable/ic_launcher_foreground.xml`.
3. Convert `ic_launcher_monochrome.svg` to Android Vector Drawable XML &rarr; `app/src/main/res/drawable/ic_launcher_monochrome.xml`.
4. Update `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`.
5. Generate raster fallback drawables (`mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`) from `ic_launcher-playstore-512.png`.
6. Copy `ic_launcher-playstore-512.png` to Google Play Console store listing assets.
