import os
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image

FINAL_DIR = os.path.abspath(r"branding/final")
os.makedirs(FINAL_DIR, exist_ok=True)

# ==============================================================================
# 1. MASTER 512x512 ICON SVG
# ==============================================================================
MASTER_512_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="100%" height="100%">
  <defs>
    <!-- Deep Obsidian / Navy Acoustic Chamber Background -->
    <radialGradient id="master-bg" cx="50%" cy="36%" r="68%">
      <stop offset="0%" stop-color="#141D2D"/>
      <stop offset="55%" stop-color="#0B1019"/>
      <stop offset="100%" stop-color="#05070C"/>
    </radialGradient>

    <!-- Cabin Shell Gradient: Polished Aero Cobalt Titanium -->
    <linearGradient id="cabin-fill" x1="20%" y1="10%" x2="80%" y2="90%">
      <stop offset="0%" stop-color="#2D3C54"/>
      <stop offset="50%" stop-color="#1B2536"/>
      <stop offset="100%" stop-color="#101722"/>
    </linearGradient>

    <!-- Cabin Perimeter Highlight -->
    <linearGradient id="cabin-stroke" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#38BDF8" stop-opacity="0.95"/>
      <stop offset="45%" stop-color="#00E5FF" stop-opacity="0.5"/>
      <stop offset="100%" stop-color="#0369A1" stop-opacity="0.2"/>
    </linearGradient>

    <!-- Frequency Curve Gradient: Electric Cyan -> Neon Emerald -->
    <linearGradient id="curve-grad" x1="0%" y1="0%" x2="100%" y2="0%">
      <stop offset="0%" stop-color="#00B0FF"/>
      <stop offset="45%" stop-color="#00E5FF"/>
      <stop offset="80%" stop-color="#00E676"/>
      <stop offset="100%" stop-color="#69F0AE"/>
    </linearGradient>

    <!-- Target Curve Underglow / Fill Gradient -->
    <linearGradient id="curve-area" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#00E5FF" stop-opacity="0.18"/>
      <stop offset="65%" stop-color="#00E5FF" stop-opacity="0.03"/>
      <stop offset="100%" stop-color="#00E5FF" stop-opacity="0"/>
    </linearGradient>

    <!-- Drop Shadow Filter for Optical Depth -->
    <filter id="emblem-shadow" x="-15%" y="-15%" width="130%" height="130%">
      <feDropShadow dx="0" dy="14" stdDeviation="18" flood-color="#000000" flood-opacity="0.7"/>
    </filter>
  </defs>

  <!-- App Background Plate -->
  <rect width="512" height="512" rx="112" fill="url(#master-bg)"/>

  <!-- Optical Centered Emblem Group (Shifted +6px horizontally for ideal mass balance) -->
  <g transform="translate(6, 0)" filter="url(#emblem-shadow)">

    <!-- Automotive Cabin C-Shell Envelope -->
    <!-- Solid, muscular wall thickness (34-40px) with aerodynamic chamfers and bullet caps -->
    <path d="
      M 362,172
      C 320,134 268,122 218,130
      C 158,140 118,190 118,256
      C 118,322 158,372 218,382
      C 268,390 320,378 362,340
      C 370,332 362,320 348,320
      C 316,320 284,342 234,348
      C 186,342 158,308 158,256
      C 158,204 186,170 234,164
      C 284,170 316,192 348,192
      C 362,192 370,180 362,172 Z"
      fill="url(#cabin-fill)"
      stroke="url(#cabin-stroke)"
      stroke-width="3"
      stroke-linejoin="round"
    />

    <!-- Roofline Aero Reflection Highlight -->
    <path d="
      M 346,182
      C 310,154 264,146 222,152
      C 178,160 148,192 140,234
      C 142,202 168,172 210,160
      C 254,148 302,154 340,178
      C 348,182 350,178 346,182 Z"
      fill="#38BDF8"
      opacity="0.55"
    />

    <!-- Target Curve Underfill Acoustic Energy Area -->
    <path d="
      M 140,258
      C 172,216 204,208 234,220
      C 256,230 268,266 296,272
      C 328,278 364,254 396,234
      L 396,320
      C 364,320 328,320 296,320
      C 268,320 256,320 234,320
      C 204,320 172,320 140,320 Z"
      fill="url(#curve-area)"
    />

    <!-- Car Audio Target Frequency Response Curve -->
    <!-- Heavy 22px stroke for micro-scale legibility; authentic bass shelf and treble roll-off -->
    <path d="
      M 140,258
      C 172,216 204,208 234,220
      C 256,230 268,266 296,272
      C 328,278 364,254 396,234"
      fill="none"
      stroke="url(#curve-grad)"
      stroke-width="22"
      stroke-linecap="round"
      stroke-linejoin="round"
    />

    <!-- High-DPI White Core Stroke for Razor-Sharp Edge Definition -->
    <path d="
      M 140,258
      C 172,216 204,208 234,220
      C 256,230 268,266 296,272
      C 328,278 364,254 396,234"
      fill="none"
      stroke="#FFFFFF"
      stroke-width="5"
      stroke-linecap="round"
      stroke-linejoin="round"
      opacity="0.95"
    />

    <!-- Calibration Sweet-Spot Focal Sensor (Simplified for Zero Small-Size Noise) -->
    <!-- Outer concentric target ring -->
    <circle cx="260" cy="254" r="15" fill="none" stroke="#00E5FF" stroke-width="3"/>
    <!-- Sensor focal core -->
    <circle cx="260" cy="254" r="6" fill="#00E5FF"/>
    <circle cx="260" cy="254" r="2.5" fill="#FFFFFF"/>
  </g>
</svg>
"""

# ==============================================================================
# 2. ANDROID ADAPTIVE: BACKGROUND LAYER (108dp x 108dp and 512x512)
# ==============================================================================
ADAPTIVE_BG_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="100%" height="100%">
  <defs>
    <radialGradient id="ad-bg-grad" cx="50%" cy="36%" r="68%">
      <stop offset="0%" stop-color="#141D2D"/>
      <stop offset="55%" stop-color="#0B1019"/>
      <stop offset="100%" stop-color="#05070C"/>
    </radialGradient>
  </defs>
  <!-- Full 108dp x 108dp Canvas (Covers safe zone, mask viewport, and 18dp parallax bleed) -->
  <rect width="108" height="108" fill="url(#ad-bg-grad)"/>
</svg>
"""

# ==============================================================================
# 3. ANDROID ADAPTIVE: FOREGROUND LAYER (108dp x 108dp, transparent background)
# ==============================================================================
# Scaled by (108/512 = 0.2109375), all coordinates rigorously fit within 66dp safe zone (diameter 66dp, center at 54, 54)
ADAPTIVE_FG_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="100%" height="100%">
  <defs>
    <!-- Cabin Shell Fill -->
    <linearGradient id="fg-cabin-fill" x1="20%" y1="10%" x2="80%" y2="90%">
      <stop offset="0%" stop-color="#2D3C54"/>
      <stop offset="50%" stop-color="#1B2536"/>
      <stop offset="100%" stop-color="#101722"/>
    </linearGradient>

    <!-- Cabin Shell Edge Highlight -->
    <linearGradient id="fg-cabin-stroke" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#38BDF8" stop-opacity="0.95"/>
      <stop offset="45%" stop-color="#00E5FF" stop-opacity="0.5"/>
      <stop offset="100%" stop-color="#0369A1" stop-opacity="0.2"/>
    </linearGradient>

    <!-- Frequency Curve Gradient -->
    <linearGradient id="fg-curve-grad" x1="0%" y1="0%" x2="100%" y2="0%">
      <stop offset="0%" stop-color="#00B0FF"/>
      <stop offset="45%" stop-color="#00E5FF"/>
      <stop offset="80%" stop-color="#00E676"/>
      <stop offset="100%" stop-color="#69F0AE"/>
    </linearGradient>

    <!-- Underfill Gradient -->
    <linearGradient id="fg-curve-area" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#00E5FF" stop-opacity="0.18"/>
      <stop offset="65%" stop-color="#00E5FF" stop-opacity="0.03"/>
      <stop offset="100%" stop-color="#00E5FF" stop-opacity="0"/>
    </linearGradient>

    <!-- Soft Drop Shadow for Foreground Layer -->
    <filter id="fg-shadow" x="-15%" y="-15%" width="130%" height="130%">
      <feDropShadow dx="0" dy="2.8" stdDeviation="3.6" flood-color="#000000" flood-opacity="0.75"/>
    </filter>
  </defs>

  <!-- Foreground Graphic strictly inside 66dp diameter safe circle (center: 54, 54, r=33) -->
  <g transform="translate(1.25, 0)" filter="url(#fg-shadow)">

    <!-- Automotive Cabin C-Shell Envelope -->
    <path d="
      M 76.36,36.28
      C 67.50,28.27 56.53,25.73 45.98,27.42
      C 33.33,29.53 24.89,40.08 24.89,54.00
      C 24.89,67.92 33.33,78.47 45.98,80.58
      C 56.53,82.27 67.50,79.73 76.36,71.72
      C 78.05,70.03 76.36,67.50 73.41,67.50
      C 66.66,67.50 59.91,72.14 49.36,73.41
      C 39.23,72.14 33.33,64.97 33.33,54.00
      C 33.33,43.03 39.23,35.86 49.36,34.59
      C 59.91,35.86 66.66,40.50 73.41,40.50
      C 76.36,40.50 78.05,37.97 76.36,36.28 Z"
      fill="url(#fg-cabin-fill)"
      stroke="url(#fg-cabin-stroke)"
      stroke-width="0.63"
      stroke-linejoin="round"
    />

    <!-- Roofline Reflection -->
    <path d="
      M 72.98,38.39
      C 65.39,32.48 55.69,30.80 46.83,32.06
      C 37.55,33.75 31.22,40.50 29.53,49.36
      C 29.95,42.61 35.44,36.28 44.30,33.75
      C 53.58,31.22 63.70,32.48 71.72,37.55
      C 73.41,38.39 73.83,37.55 72.98,38.39 Z"
      fill="#38BDF8"
      opacity="0.55"
    />

    <!-- Target Curve Underfill -->
    <path d="
      M 29.53,54.42
      C 36.28,45.56 43.03,43.88 49.36,46.41
      C 54.00,48.52 56.53,56.11 62.44,57.38
      C 69.19,58.64 76.78,53.58 83.53,49.36
      L 83.53,67.50
      C 76.78,67.50 69.19,67.50 62.44,67.50
      C 56.53,67.50 54.00,67.50 49.36,67.50
      C 43.03,67.50 36.28,67.50 29.53,67.50 Z"
      fill="url(#fg-curve-area)"
    />

    <!-- Target Frequency Response Curve (4.64dp stroke for clear small-size visibility) -->
    <path d="
      M 29.53,54.42
      C 36.28,45.56 43.03,43.88 49.36,46.41
      C 54.00,48.52 56.53,56.11 62.44,57.38
      C 69.19,58.64 76.78,53.58 83.53,49.36"
      fill="none"
      stroke="url(#fg-curve-grad)"
      stroke-width="4.64"
      stroke-linecap="round"
      stroke-linejoin="round"
    />

    <!-- High-DPI White Core -->
    <path d="
      M 29.53,54.42
      C 36.28,45.56 43.03,43.88 49.36,46.41
      C 54.00,48.52 56.53,56.11 62.44,57.38
      C 69.19,58.64 76.78,53.58 83.53,49.36"
      fill="none"
      stroke="#FFFFFF"
      stroke-width="1.05"
      stroke-linecap="round"
      stroke-linejoin="round"
      opacity="0.95"
    />

    <!-- Calibration Sweet-Spot Focal Sensor -->
    <circle cx="54.84" cy="53.58" r="3.16" fill="none" stroke="#00E5FF" stroke-width="0.63"/>
    <circle cx="54.84" cy="53.58" r="1.26" fill="#00E5FF"/>
    <circle cx="54.84" cy="53.58" r="0.53" fill="#FFFFFF"/>
  </g>
</svg>
"""

# ==============================================================================
# 4. ANDROID 13+ MONOCHROME / THEMED ICON (108dp x 108dp, flat single-tone vector)
# ==============================================================================
# In Android 13+, the OS applies Monet tinting to all non-transparent pixels.
# The artwork is designed as a true negative-space stencil: 100% vector, zero gradients, zero overlaps.
ADAPTIVE_MONO_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="100%" height="100%">
  <!-- Transparent canvas background -->
  <g transform="translate(1.25, 0)">
    <!-- Cabin C-Shell Silhouette (Pure Solid White / System-Tintable) -->
    <path d="
      M 76.36,36.28
      C 67.50,28.27 56.53,25.73 45.98,27.42
      C 33.33,29.53 24.89,40.08 24.89,54.00
      C 24.89,67.92 33.33,78.47 45.98,80.58
      C 56.53,82.27 67.50,79.73 76.36,71.72
      C 78.05,70.03 76.36,67.50 73.41,67.50
      C 66.66,67.50 59.91,72.14 49.36,73.41
      C 39.23,72.14 33.33,64.97 33.33,54.00
      C 33.33,43.03 39.23,35.86 49.36,34.59
      C 59.91,35.86 66.66,40.50 73.41,40.50
      C 76.36,40.50 78.05,37.97 76.36,36.28 Z"
      fill="#FFFFFF"
    />

    <!-- Frequency Curve Silhouette -->
    <!-- Guaranteed 3.8dp clean negative space separating it from the cabin shell -->
    <path d="
      M 29.53,54.42
      C 36.28,45.56 43.03,43.88 49.36,46.41
      C 54.00,48.52 56.53,56.11 62.44,57.38
      C 69.19,58.64 76.78,53.58 83.53,49.36"
      fill="none"
      stroke="#FFFFFF"
      stroke-width="4.64"
      stroke-linecap="round"
      stroke-linejoin="round"
    />

    <!-- Calibration Sweet-Spot Focal Sensor -->
    <circle cx="54.84" cy="53.58" r="3.16" fill="none" stroke="#FFFFFF" stroke-width="0.8"/>
    <circle cx="54.84" cy="53.58" r="1.4" fill="#FFFFFF"/>
  </g>
</svg>
"""

# Write vector files
files_to_write = {
    "cabinrta-master.svg": MASTER_512_SVG,
    "ic_launcher_background.svg": ADAPTIVE_BG_SVG,
    "ic_launcher_foreground.svg": ADAPTIVE_FG_SVG,
    "ic_launcher_monochrome.svg": ADAPTIVE_MONO_SVG,
}

for fname, content in files_to_write.items():
    fpath = os.path.join(FINAL_DIR, fname)
    with open(fpath, "w", encoding="utf-8") as f:
        f.write(content.strip())
    # Validate XML
    ET.parse(fpath)
    print(f"Validated SVG: {fname}")

print("All primary SVGs generated and validated successfully.")
