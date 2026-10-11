# CabinRTA Phase 2: Free/Pro entitlement architecture

## Review identity and scope

- Repository: `AdwaitOP/rta-tuner`
- Base: `main` at `c479099895c90d72de7ef0654ae4528330a3af44`, including the merged CabinRTA branding milestone.
- Implementation branch: `codex/cabinrta-pro-entitlements`
- Final implementation commit: provided in the delivery report after this document is committed.
- Existing user assets under `branding/concepts/` are preserved and excluded from implementation changes.

This phase centralizes access enforcement and adds the contextual upgrade interface. It contains no Google Play Billing dependency, purchase action, price, or claim of verified purchase ownership. Automatic EQ suggestions and custom target editing remain outside scope.

## Architecture

`EntitlementRepository` exposes `StateFlow<EntitlementState>` with `FREE` and `PRO_LIFETIME` tiers. `FeatureAccessPolicy` owns all capability checks and numerical limits. Authorization checks return `FeatureAccessResult` or throw its structured `FeatureAccessDeniedException` before mutation; `AudioCaptureViewModel` publishes contextual denials for Compose. CSV application actions separately return `CsvExportResult` for denial, chooser initiation, or operational failure.

One repository and policy are shared by the ViewModel's calibration manager, generator, overlay store, session repository, and export operations. Constructors support injected providers for tests and future verified ownership. The release source set's `AppEntitlements.create()` always returns `FreeEntitlementRepository`; its simulation operation returns `false`. `DebugEntitlementRepository` exists only in the debug source set, starts Free, and stores simulation in memory. Creating a new provider resets it to Free. Tests use a separate test-only fake.

`DebugScreen` and `SessionToolsCard` observe entitlement state and retain visible contextual premium controls. `ProUpgradeSheet` explains the requested feature, compares tiers, and describes a future one-time lifetime unlock as coming soon. Debug simulation is explicitly identified as local simulation; release has no mutable simulator provider.

## Implemented feature matrix

| Capability | Free | Pro lifetime tier |
| --- | --- | --- |
| Complete live 31-band RTA | Included | Included |
| Raw FFT | Included | Included |
| All existing FFT window sizes | Included | Included |
| All existing smoothing and graph controls | Included | Included |
| Fullscreen analyzer | Included | Included |
| Peak/RMS meters | Included | Included |
| Existing microphone selection and routing | Included | Included |
| Pink noise generator and shared output level | Included | Included |
| Harman car target curve | Included | Included |
| Frozen overlays, including saved-session loading | One | Unlimited |
| New saved tuning sessions | One total saved session | Unlimited |
| Existing session viewing, notes, and deletion | Included, including historical Pro records | Included |
| PNG screenshot sharing | Included | Included |
| Mic calibration file import, activation, and correction | Requires Pro | Included |
| SPL reference calibration and SPL mode | Requires Pro | Included |
| Noise floor capture and subtraction | Requires Pro | Included |
| Sine sweep and existing range/duration/loop controls | Requires Pro | Included |
| Flat and AudioControl target presets | Requires Pro | Included |
| Live, frozen-snapshot, and saved-session CSV exports | Requires Pro | Included |

The Pro column describes policy behavior for an authorized injected provider or debug simulation. This release does not sell or verify Pro ownership.

## Enforcement locations

All paths below are application or processing boundaries; visible locks supplement these checks.

| Location | Enforcement |
| --- | --- |
| `core/entitlement/FeatureAccessPolicy.kt` | `check`, `requireAccess`, `checkOverlayCapacity`, and `checkSessionCapacity` provide the shared capability and capacity rules. Both Free limits are one; Pro limits have no ceiling. |
| `CalibrationManager.loadMicCurve` | Requires `MIC_CALIBRATION` before parsing or persisting an import. |
| `CalibrationManager.mutateWithAccess` | Guards `setMicCurveActive` and `clearMicCurve`; `calibrateSplReference`, `setSplMode`, and `setSplOffset`; and `startNoiseFloorCapture`, `setNoiseSubtractionActive`, and `clearNoiseFloor` before modifying state/preferences. |
| `CalibrationManager.setTargetCurve` and `setTargetCurveActive` | Require `ADVANCED_TARGETS` for presets other than Harman; selecting Harman or no target remains Free. |
| `effectiveCalibrationState` and `CalibrationManager.publishEffectiveStateLocked` | Derive authorized runtime mic/SPL/noise/advanced-target flags from retained requested preferences. `processFrame` reads one volatile effective snapshot with precomputed mic offsets; no per-bin entitlement checks are added. |
| `CalibrationManager` entitlement collector and `finalizeNoiseCapture` | Recompute effective state and cancel unauthorized capture on revocation. Finalization rechecks access before writing a captured floor, including when revocation precedes collector dispatch. |
| `SignalGenerator.startSineSweep` and `mutateSweepConfiguration` | Require `SINE_SWEEP` before stopping another generator or changing playback/configuration. The configuration helper covers `setSweepDuration`, `setSweepRange`, and `setSweepLooping`. Pink noise and the shared volume remain Free. |
| `SignalGenerator` entitlement collector and `stopSync` | Stop a revoked sweep, release its track, and invalidate its playback generation so delayed initialization cannot restart it. |
| `FrozenSnapshotStore.addAll` | Checks overlay capacity under the same lock as publication. Both `add` and `loadSession` use it. Loading accepts explicit saved snapshot IDs and validates membership; additions are accepted in full or rejected in full. |
| `AudioCaptureViewModel.freezeCurrentSnapshot` and `loadSessionSnapshotsToOverlay` | Route actual live-freeze and whole/selected saved-session operations through the shared store and publish restriction messages. |
| `SessionRepository.saveSession` and `TuningSessionDao.saveSessionWithSnapshots` | Room `@Transaction` encloses `countSessions`, the capacity check, the session insert, and all linked snapshot inserts. Separate repository instances share database transaction serialization. A rejection changes no rows; insertion failures roll back both tables. |
| `AudioCaptureViewModel.exportCsv` | Checks `CSV_EXPORT` before formatting. `exportLiveCsv`, `exportSnapshotCsv`, and `exportSessionCsv` all use it and route denials to the upgrade sheet. |
| `RtaExportManager.shareCsv` | Requires `CSV_EXPORT` again before cache access, writing a file, or opening the share chooser. The guard is outside the Android error handler so denial reaches callers. |
| `AppEntitlements` source sets and `ProUpgradeSheet` | Release always creates Free and refuses simulation; the mutable provider is debug-only. Simulator controls are displayed only when `BuildConfig.DEBUG`. |

`RtaExportManager.formatLiveCurveCsv`, `formatSnapshotCsv`, and `formatSessionWithSnapshotsCsv` remain pure, ungated formatters. `captureAndShareSurfacePng` retains Free access. Session viewing, note updates, deletion, and snapshot record access remain available without Pro.

## Persistence and entitlement transitions

Room entities, database version, and schema are unchanged. Existing sessions and snapshot records are retained even when their count exceeds the Free limit. Free rejects further new saves until capacity is available; it never removes existing sessions to satisfy that limit. Notes and deletion continue to work, and existing foreign-key deletion behavior is preserved.

Overlay revocation keeps all existing measurements. Additional freezes/imports are blocked while capacity is full. Users can remove overlays themselves or choose one saved snapshot when there is room. Whole-session loading never silently truncates a session or replaces existing overlays.

Calibration preferences remain separate from effective processing. Historical curves, offsets, floor measurements, target choices, and requested flags are retained while Free; their premium effects are disabled. Free-to-Pro publication restores authorized requested settings. Pro-to-Free publication disables premium processing and cancels an in-flight noise capture while retaining the previous completed floor. Regranting Pro does not restart a cancelled noise capture or a stopped sweep. Generation validation also prevents a revoked sweep still initializing on another thread from resurrecting playback.

Entitlement state and contextual locks react through `StateFlow`. No ownership is inferred from persisted calibration settings, saved-session metadata, or debug simulation preferences.

## Files changed

Production paths below are under `app/src/main/java/com/rtatuner/app/` unless a source set is specified.

- Entitlement foundation: `core/entitlement/EntitlementRepository.kt`, `FeatureAccessPolicy.kt`; variant factories at `app/src/debug/java/com/rtatuner/app/core/entitlement/AppEntitlements.kt` and the corresponding release path.
- Processing: `core/calibration/CalibrationManager.kt`, `EffectiveCalibrationState.kt`, `core/audio/SignalGenerator.kt`.
- Measurements/persistence/export: `core/session/FrozenSnapshotStore.kt`, `core/database/SessionRepository.kt`, `TuningSessionDao.kt`, `core/export/RtaExportManager.kt`.
- UI orchestration and upgrade: `ui/AudioCaptureViewModel.kt`, `DebugScreen.kt`, `ui/components/SessionToolsCard.kt`, `ProUpgradeSheet.kt`.
- Build support: `app/build.gradle.kts` enables generated `BuildConfig`, Android resources for JVM tests, and test-only Robolectric/JSON dependencies.
- Tests under `app/src/test/java/com/rtatuner/app/`: `FeatureAccessPolicyTest.kt`, `FrozenSnapshotStoreTest.kt`, `CalibrationEntitlementTest.kt`, `SignalGeneratorEntitlementTest.kt`, `SessionRepositoryEntitlementTest.kt`, `RtaExportEntitlementTest.kt`, `AudioCaptureEntitlementIntegrationTest.kt`, `CsvFileProviderFixture.kt`, and `core/entitlement/FakeEntitlementRepository.kt`. Existing `SignalGeneratorTest.kt` adds lifecycle cleanup; all 66 existing regression tests are retained.
- Review report: `docs/phase-2-entitlements.md`.

## Verification of the original Phase 2 implementation

Executed with the installed Android SDK, JetBrains JDK 21.0.7 and Gradle 8.13 on 11 October 2026:

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleRelease :app:lint --console=plain
BUILD SUCCESSFUL
```

| Check | Result |
| --- | --- |
| Debug Android build | Passed |
| Debug JVM tests | 103 passed, zero failures/errors/skips across 16 suites |
| Release JVM tests | 103 passed, zero failures/errors/skips across 16 suites |
| Minified release Android build | Passed |
| Android lint | Passed; 0 errors, 70 warnings, 4 hints remain |
| Scoped `git diff --check` | Passed |
| Independent agent source review | No blocking defects identified |
| Emulator UI smoke check | Debug and minified release launch; analyzer and upgrade comparison render; coming-soon disclosure is readable; debug simulation changes the visible tier reactively; release starts Free and its upgrade sheet omits the simulator |

There are 37 new tests in addition to the 66 preserved regression tests. Release-variant tests verify simulation returns false, entitlement remains Free, and the debug provider class is absent. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`; the release APK is at `app/build/outputs/apk/release/app-release.apk`. Test and lint reports are under `app/build/reports/`.

Regression coverage includes every capability and limit, actual ViewModel freeze/whole-session/selected-snapshot operations, actual Room transactions with 24 concurrent Free save attempts across separate repositories, unlimited concurrent Pro saves, rollback after snapshot insertion failure, historical-data retention, all CSV action paths, Free PNG entry access, unauthorized processing mutations, persisted premium preferences while Free, entitlement transitions, and generator revocation during playback/initialization. Robolectric uses the production FileProvider declaration via a test fixture because AGP's JVM manifest omits the app provider. A test-only shadow normalizes host path separators in AndroidX's private root-containment comparison on Windows; real file bytes, provider URI construction and chooser assertions remain exercised.

FFT algorithms, microphone routing, and database schema receive no implementation changes. The generator changes address access checks and safe lifecycle termination; existing pink-noise/sweep signal equations and configuration ranges remain intact.

## Known limitations and Phase 3 requirements

- Pro has no purchase or restoration flow in Phase 2. Release remains Free until verified ownership is supplied. The coming-soon sheet has no functional Buy control or regional price.
- JVM Android behavior is exercised with Robolectric and Compose appearance is smoke-checked on the Pixel 9 Pro emulator. Physical microphone routing, rendered PNG sharing, and real-device audio still require device validation. Lint warnings/hints remain; no lint baseline or suppression was added.
- The repository's existing debug signing configuration for release packaging is unchanged; store distribution needs production signing configuration.
- CSV operational failures retain technical logging and now propagate to a friendly Snackbar. Chooser initiation confirms only that Android's share chooser was launched, not delivery to another app. Same-name exports within one timestamp second fail safely and can be retried, preserving the existing filename convention and prior files.
- Phase 3 must introduce the configured one-time lifetime Play product, a Billing-backed `EntitlementRepository`, and verified ownership publication. It must handle pending/cancelled purchases, acknowledgement, restore/requery, refunds/revocations, and reconnection/offline state without trusting local simulation or unverified flags.
- Product details and localized price must come from Billing. Purchase UI must distinguish pending, verified, failed, and restored ownership, while retaining the existing Free operations and data-preservation rules.
- Verified state changes must continue using the same policy and lifecycle collectors. Billing integration needs tests for failed verification, restore, pending purchases, revocation during active processing, and release builds without any debug bypass.

## Independent audit corrections: P2-A and P2-B

Corrections build on audited commit `b9a888d9e6d9d43dc47f09afb9ea14234c10154f` on `codex/cabinrta-pro-entitlements` as a new commit; the audited commit and `main` are unchanged.

- **P2-A PASS:** The session Save button invokes the existing `UNLIMITED_SESSIONS` upgrade callback immediately when displayed capacity is full. Available Free capacity and Pro retain the Save dialog. The existing Room transaction still checks the actual count at persistence time, preserving historical sessions, snapshots, notes and deletion. Evidence: `SessionSaveButtonTest` (four production Compose button/recomposition cases) and `SessionSaveStaleCapacityTest` (stale-dialog rejection with retained data; eight concurrent callers produce one commit) passed in both variants. Original historical-data/notes/deletion tests remain passing.
- **P2-B PASS:** All three CSV actions return `AccessDenied`, `ShareChooserLaunched`, or `Failed`. `shareCsv` logs and propagates `CsvShareException`; normal failures produce a friendly Snackbar instead of an upgrade sheet or success claim. Authorization remains before formatting and file I/O. Existing CSV content, filename convention and FileProvider behavior are retained. Exclusive file creation prevents overwriting prior exports; failed attempts close streams and remove only their newly created file. Evidence: `RtaExportFailureTest` (eight creation, stream opening/partial writing, URI, chooser, logging and retention cases), `AudioCaptureEntitlementIntegrationTest` (nine cases, including all three CSV application actions) and `CsvExportErrorSnackbarTest` (actual friendly Snackbar display) passed in both variants.
- **Scope:** No DSP/FFT, microphone/capture, Room schema, calibration, entitlement-transition, Billing or `branding/concepts/` changes.
- **Audit verification:** Executed `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:testReleaseUnitTest :app:lint --console=plain` with Gradle 8.13/JDK 21.0.7 on 11 October 2026: `BUILD SUCCESSFUL` (120 tasks executed). Debug and minified release APKs built; complete JVM suites passed **122 tests each across 20 suites**, with zero failures/errors/skips. There are 19 additional tests beyond the audited implementation. Lint passed with **0 errors, 70 warnings and 4 hints**, matching the audited warning count. `git diff --check` passed. The Compose host registration is test-only; no app manifest changes were needed. Existing physical-device validation and lint limitations remain, and sharing success means chooser initiation only.

Exact correction files (repository-relative paths):

```text
app/build.gradle.kts
gradle/libs.versions.toml
app/src/main/java/com/rtatuner/app/core/export/CsvExportResult.kt
app/src/main/java/com/rtatuner/app/core/export/RtaExportManager.kt
app/src/main/java/com/rtatuner/app/ui/AudioCaptureViewModel.kt
app/src/main/java/com/rtatuner/app/ui/DebugScreen.kt
app/src/main/java/com/rtatuner/app/ui/components/SessionToolsCard.kt
app/src/test/java/com/rtatuner/app/AudioCaptureEntitlementIntegrationTest.kt
app/src/test/java/com/rtatuner/app/ComposeHostActivityRule.kt
app/src/test/java/com/rtatuner/app/CsvExportErrorSnackbarTest.kt
app/src/test/java/com/rtatuner/app/RtaExportFailureTest.kt
app/src/test/java/com/rtatuner/app/SessionSaveButtonTest.kt
app/src/test/java/com/rtatuner/app/SessionSaveStaleCapacityTest.kt
docs/phase-2-entitlements.md
```
