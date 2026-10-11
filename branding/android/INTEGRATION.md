# CabinRTA Phase 1 integration

Implementation branch: `codex/cabinrta-branding`, based on local `main` at
`8a9af4fcb1dc6db75950a8bcefbf23a728adcee6`. Work uses the original checkout,
so untracked Antigravity assets were accessible throughout. The selected final
assets are committed under `branding/final/`; earlier untracked concepts remain
in the working directory and are excluded from this implementation commit.

## Asset review and conversion

- All final SVGs were inspected. Master: 512×512 viewport; adaptive layers:
  108×108. Foreground has no canvas background; background covers the complete
  canvas with an opaque radial gradient.
- Play Store artwork was corrected after independent review: the existing master
  now extends its navy gradient across the full square, with no pre-rounded
  corners or black padding. The PNG is 512×512, 32-bit RGBA, fully opaque, with
  explicit sRGB metadata. CabinRTA emblem geometry is unchanged.
- Foreground paths, reflection, response-curve gradient, underfill, white core,
  and calibration sensor are converted to native VectorDrawable geometry.
  Gradient coordinates are resolved from SVG object bounds. Native radial
  background preserves the supplied stops and center.
- SVG `feDropShadow` has no direct VectorDrawable equivalent. Only that
  decorative shadow is omitted from Android resources. Adaptive SVG originals
  remain unchanged; the corrected Play Store master and PNG retain this shadow.
- Source geometry was slightly outside the 33dp safe circle. Foreground and
  monochrome receive a 0.98 scale about (54,54). At 4× rasterization their
  visible alpha>127 pixels reach radius 32.74dp. Background alpha is 255
  throughout; foreground and monochrome contain fully transparent pixels.
- Monochrome has exclusively white geometry with transparent surroundings,
  no colors, gradients, or opaque canvas. Its shell, curve, and sensor preserve
  the supplied silhouette. Contrary to the source comment, the curve joins
  the shell; a continuous negative-space gap is not present in the artwork.
- `mask-review.png` is rendered from reconstructed **shipped vector XML**:
  circle, squircle, rounded square, teardrop; color and themed silhouette;
  launcher sizes 24, 32, 48, 64, 96px. The curve stays legible; fine sensor
  and reflection details become less distinct at small sizes.

Adaptive icons follow the [Android guidance](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive).
API 26 resources use foreground/background; API 33 overrides add the dedicated
monochrome vector. A narrow lint suppression on the API 26 definitions documents
the API 33 override, which the monochrome detector does not recognize there.
Both regular and round launcher references resolve through these resources.
Lossless legacy WebP icons are supplied at 48/72/96/144/192px in the five density
folders. Their backgrounds use rounded-square and circle masks respectively.
The app still requires API 26 or newer; these serve legacy resource consumers.

## Branding and scope

Launcher label, Compose heading, microphone permission explanation, CSV headers,
sharing subjects, screenshot filenames, and live CSV filenames use CabinRTA.
Android-facing text is centralized in strings.xml; pure CSV formatters keep one
brand constant so JVM tests do not require an Android Context. Play listing title
and future tier name are metadata only in `branding/play-store-listing.json`.

Preserved: application ID and Kotlin packages (`com.rtatuner.app`), Room schema
and `rta_tuner.db`, FileProvider authority, calibration, sessions, capture,
microphone routing, DSP, FFT, and signal generation. No billing, feature gating,
UI redesign, or audio-engine edits.

Remaining legacy identifiers are intentional internal compatibility references:
`com.rtatuner.app`, `Theme.RTATuner`, `rta_tuner.db`, and existing internal classes.
The Gradle root-project name, ProGuard comment, and historical repository docs
also retain the old name. Production UI and export searches contain no remaining
`RTA Tuner`/`RTA TUNER` text. Previously exported files and user-entered session
names are not rewritten.

## Verification

Executed with Android Studio JBR and the installed Gradle user cache:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = 'C:\Users\adwai\.gradle'
./gradlew.bat assembleDebug testDebugUnitTest lintDebug --console=plain --rerun-tasks
```

The full rebuild, resource compilation, and all 66 unit tests succeeded with
zero failures/errors/skips. A subsequent incremental verification also checks
the added multi-snapshot export branding assertion and lint annotation; it passed
with 0 lint errors, 66 warnings, and 5 hints.
APK badging confirms label `CabinRTA`, ID `com.rtatuner.app`, minSdk 26, targetSdk
35. APK XML inspection confirms the API 33 icon contains background, foreground,
and dedicated monochrome references. Implementation changes pass whitespace
checks. The staged originals contain five existing trailing-whitespace lines
in final/index.html and the original final/render_pngs.py. The export-script
correction removes the latter's whitespace; index.html remains unchanged.

No connected Android device/emulator was available (`adb devices` returned an
empty list). Device installation, live launcher/theme rendering, and microphone
runtime checks were therefore not performed. Visual checks use the reconstructed
vector previews, not an Android runtime. Existing audio routing deprecation
warnings and unrelated lint warnings remain outside Phase 1.

## Reproduce icon resources and previews

### Play Store correction verification

Executed the master generator with `--play-store-only`, the PNG exporter, and two
independent `render_pngs.py --check` runs. Both fresh exports were byte-for-byte
identical to the corrected file. Master SVG regeneration was also byte-identical.
PNG IHDR: 512×512, bit depth 8, color type 6. Alpha range: 255–255. sRGB intent:
0 (perceptual). File size: 124,412 bytes, below 1 MiB.

Corner RGBA pixels, top-left/top-right/bottom-left/bottom-right:
`(6,8,14,255)`, `(7,9,15,255)`, `(5,7,12,255)`, `(5,7,12,255)`.

PNG SHA-256: `e2bbb150ec4866a06fe791c314e84460c4c2779a0e257fa31d7df78423207795`.
Only Play Store source/export assets and their documentation changed in this
correction. Application code, branding strings, and Android launcher resources
were verified unchanged. Android builds/tests above belong to the original
integration; they were not rerun for this asset-only correction.

### Android launcher resources

Requires Python 3 (stdlib), Node.js, and `sharp` discoverable through NODE_PATH:

```powershell
python branding/android/generate_vectors.py
python branding/android/prepare_previews.py
node branding/android/render_icons.cjs
```

The last command validates alpha, safe radius, and monochrome color, generates
the density icons, and writes previews under ignored `build/branding/`.
Copy `build/branding/mask-review.png` here when updating the reviewed artwork.
