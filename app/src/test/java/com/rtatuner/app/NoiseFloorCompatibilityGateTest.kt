package com.rtatuner.app

import android.content.Context
import android.content.SharedPreferences
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.audio.CaptureAuthoritySnapshot
import com.rtatuner.app.core.audio.CaptureEffectsInfo
import com.rtatuner.app.core.audio.CaptureFingerprint
import com.rtatuner.app.core.audio.CapturedFrameContext
import com.rtatuner.app.core.audio.NoiseFloorCompatibility
import com.rtatuner.app.core.calibration.CalibrationManager
import com.rtatuner.app.core.calibration.NoiseFloorSnapshot
import com.rtatuner.app.core.calibration.evaluateNoiseFloorCompatibility
import com.rtatuner.app.core.dsp.BiquadFilterBank
import com.rtatuner.app.core.dsp.DspResponseRevision
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoiseFloorCompatibilityGateTest {

    private fun testPreferences(): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("x01-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun sampleFingerprint(
        sampleRate: Int = 48000,
        micId: String = "mic_internal_1",
        devType: String = "TYPE_BUILTIN_MIC",
        selectedSource: Int = 6, // VOICE_RECOGNITION
        actualSource: Int = 6,
        dspRev: DspResponseRevision = DspResponseRevision.CURRENT,
        effects: CaptureEffectsInfo = CaptureEffectsInfo(aecAvailable = false, agcAvailable = false, nsAvailable = false)
    ): CaptureFingerprint = CaptureFingerprint(
        dspRevision = dspRev,
        actualSampleRate = sampleRate,
        routedMicrophoneId = micId,
        routedDeviceType = devType,
        selectedAudioSource = selectedSource,
        actualAudioSource = actualSource,
        isSourceFallback = (selectedSource != actualSource),
        effects = effects
    )

    private fun sampleAuthority(
        managerId: String = "mgr_test_01",
        generation: Long = 1L,
        routeEpoch: Long = 1L,
        fingerprint: CaptureFingerprint = sampleFingerprint(),
        isRunning: Boolean = true,
        sequence: Long = 0L
    ): CaptureAuthoritySnapshot = CaptureAuthoritySnapshot(
        managerId = managerId,
        captureGeneration = generation,
        routeEpoch = routeEpoch,
        fingerprint = fingerprint,
        isRunning = isRunning,
        latestPublishedSequence = sequence
    )

    private fun sampleFrameContext(
        managerId: String = "mgr_test_01",
        generation: Long = 1L,
        sequence: Long = 100L,
        fingerprint: CaptureFingerprint = sampleFingerprint(),
        isRunning: Boolean = true,
        routeEpoch: Long = 1L
    ): CapturedFrameContext = CapturedFrameContext(
        managerId = managerId,
        captureGeneration = generation,
        frameSequence = sequence,
        fingerprint = fingerprint,
        isRunning = isRunning,
        routeEpoch = routeEpoch
    )

    // =========================================================================
    // Requirement A: Version actual DSP response
    // =========================================================================

    @Test
    fun requirementA_dspResponseRevisionIsExplicitAndMatchesBiquadFilterBank() {
        val current = DspResponseRevision.CURRENT
        assertEquals("CABINRTA_DSP_WAVE1_BIQUAD_V1", current.revisionId)
        assertEquals("4th_order_cascaded_biquad_iir", current.filterType)
        assertEquals(1, current.coefficientRevision)
        assertEquals("ISO_266_31_BAND_NOMINAL", current.centerFrequencyPreset)
        assertEquals("STANDARD_1_3_OCTAVE_CASCADE_COMPENSATED_Q4.318", current.qBandwidthDefinition)
        assertEquals("DUAL_2ND_ORDER_CASCADE_UNITY_0DB_PEAK", current.cascadeAndNormalization)
        assertEquals("BILINEAR_PREWARPED_CENTER_NYQUIST_TANGENT_WARPED", current.edgeAnchoringDesign)

        // BiquadFilterBank exposes the exact revision
        assertEquals(current, BiquadFilterBank.CURRENT_RESPONSE_REVISION)

        // An instantiated filter bank carries this exact revision
        val filterBank = BiquadFilterBank(sampleRate = 48000)
        assertEquals(current, filterBank.responseRevision)
    }

    @Test
    fun requirementA_dspResponseRevisionJsonRoundTrip() {
        val original = DspResponseRevision.CURRENT
        val json = original.toJson()
        val restored = DspResponseRevision.fromJson(json)
        assertEquals(original, restored)
    }

    @Test
    fun requirementA_modifyingDspRevisionChangesEquality() {
        val baseline = DspResponseRevision.CURRENT
        val modifiedRevision = baseline.copy(revisionId = "CABINRTA_DSP_WAVE2_V2")
        val modifiedCoeff = baseline.copy(coefficientRevision = 2)
        val modifiedQ = baseline.copy(qBandwidthDefinition = "APPROXIMATE_Q")

        assertNotEquals(baseline, modifiedRevision)
        assertNotEquals(baseline, modifiedCoeff)
        assertNotEquals(baseline, modifiedQ)
    }

    // =========================================================================
    // Requirement B: Immutable actual capture context & Fingerprint
    // =========================================================================

    @Test
    fun requirementB_captureFingerprintJsonRoundTrip() {
        val original = sampleFingerprint(
            sampleRate = 48000,
            micId = "usb_umik1_serial_123",
            devType = "TYPE_USB_DEVICE",
            selectedSource = 6,
            actualSource = 1, // Fallback occurred
            effects = CaptureEffectsInfo(aecAvailable = false, agcAvailable = true, nsAvailable = false, isEffectsKnown = true)
        )
        assertTrue(original.isSourceFallback)
        assertTrue(original.isMicrophoneKnown)

        val jsonString = original.toJsonString()
        val restored = CaptureFingerprint.fromJsonString(jsonString)

        assertNotNull(restored)
        assertEquals(original, restored)
        assertEquals(48000, restored!!.actualSampleRate)
        assertEquals("usb_umik1_serial_123", restored.routedMicrophoneId)
        assertEquals("TYPE_USB_DEVICE", restored.routedDeviceType)
        assertEquals(6, restored.selectedAudioSource)
        assertEquals(1, restored.actualAudioSource)
        assertTrue(restored.isSourceFallback)
        assertEquals(original.effects, restored.effects)
    }

    @Test
    fun requirementB_captureFingerprintHandlesUnknownHardwareConservatively() {
        val unknownMicFingerprint = sampleFingerprint(
            micId = CaptureFingerprint.UNKNOWN_MICROPHONE_ID,
            devType = CaptureFingerprint.UNKNOWN_DEVICE_TYPE
        )
        assertFalse(unknownMicFingerprint.isMicrophoneKnown)

        val pendingMicFingerprint = sampleFingerprint(
            micId = CaptureFingerprint.PENDING_MICROPHONE_ID,
            devType = CaptureFingerprint.PENDING_DEVICE_TYPE
        )
        assertFalse(pendingMicFingerprint.isMicrophoneKnown)
    }

    @Test
    fun requirementB_compatibilityGatingEvaluation() {
        val reference = sampleFingerprint(sampleRate = 48000, micId = "mic1")
        val snapshot = NoiseFloorSnapshot(
            bands31Db = FloatArray(31) { -50f },
            captureTimestampMs = 1000L,
            isCaptured = true,
            fingerprint = reference,
            requiresRecapture = false
        )

        // 1. Identical context -> Compatible
        val matchResult = evaluateNoiseFloorCompatibility(snapshot, reference)
        assertTrue("Matching context must be compatible", matchResult is NoiseFloorCompatibility.Compatible)

        // 2. Sample rate mismatch -> Incompatible
        val rateMismatch = sampleFingerprint(sampleRate = 44100, micId = "mic1")
        val rateResult = evaluateNoiseFloorCompatibility(snapshot, rateMismatch)
        assertTrue("Sample rate mismatch must be incompatible", rateResult is NoiseFloorCompatibility.Incompatible)
        assertTrue("Sample rate mismatch must require recapture", (rateResult as NoiseFloorCompatibility.Incompatible).requiresRecapture)

        // 3. Microphone mismatch -> Incompatible
        val micMismatch = sampleFingerprint(sampleRate = 48000, micId = "mic_bluetooth_2")
        val micResult = evaluateNoiseFloorCompatibility(snapshot, micMismatch)
        assertTrue("Microphone route mismatch must be incompatible", micResult is NoiseFloorCompatibility.Incompatible)
        assertTrue((micResult as NoiseFloorCompatibility.Incompatible).requiresRecapture)

        // 4. DSP revision mismatch -> Incompatible
        val dspMismatch = sampleFingerprint(
            sampleRate = 48000,
            micId = "mic1",
            dspRev = DspResponseRevision.CURRENT.copy(revisionId = "LEGACY_FFT_OR_OLD_BIQUAD")
        )
        val dspResult = evaluateNoiseFloorCompatibility(snapshot, dspMismatch)
        assertTrue("DSP revision mismatch must be incompatible", dspResult is NoiseFloorCompatibility.Incompatible)
        assertTrue((dspResult as NoiseFloorCompatibility.Incompatible).requiresRecapture)

        // 5. Audio source fallback mismatch -> Incompatible
        val sourceMismatch = sampleFingerprint(sampleRate = 48000, micId = "mic1", actualSource = 1)
        val sourceResult = evaluateNoiseFloorCompatibility(snapshot, sourceMismatch)
        assertTrue("Audio source mismatch must be incompatible", sourceResult is NoiseFloorCompatibility.Incompatible)
        assertTrue((sourceResult as NoiseFloorCompatibility.Incompatible).requiresRecapture)

        // 6. Unknown active mic -> Incompatible
        val unknownMic = sampleFingerprint(sampleRate = 48000, micId = CaptureFingerprint.UNKNOWN_MICROPHONE_ID)
        val unknownResult = evaluateNoiseFloorCompatibility(snapshot, unknownMic)
        assertTrue("Unknown active microphone must be incompatible", unknownResult is NoiseFloorCompatibility.Incompatible)
    }

    // =========================================================================
    // Requirement C: Bind actual audio frames to context
    // =========================================================================

    @Test
    fun requirementC_duplicateOrStatusOnlyFrameEmissionsAreIgnoredDuringNoiseCapture() = runTest {
        var now = 1000L
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope, nowMs = { now })

        try {
            val baseFingerprint = sampleFingerprint(sampleRate = 48000, micId = "micA")
            val auth = sampleAuthority(managerId = "mgr1", fingerprint = baseFingerprint)
            manager.updateCaptureAuthority(auth)
            manager.startNoiseFloorCapture(durationMs = 200L, authority = auth)
            assertTrue(manager.uiState.value.isCapturingNoiseFloor)

            // Frame 1: Valid PCM hop (seq = 1) at -40 dB
            now += 50L
            val ctx1 = sampleFrameContext(managerId = "mgr1", generation = 1L, sequence = 1L, fingerprint = baseFingerprint)
            manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = ctx1)
            assertEquals("Progress advances with time", 0.25f, manager.uiState.value.noiseCaptureProgress, 0.01f)

            // Repeated emission of SAME sequence (seq = 1) with loud -10 dB -> must be ignored / not accumulated
            now += 50L
            manager.processFrame(FloatArray(31) { -10f }, FloatArray(8) { -10f }, 100f, frameContext = ctx1)

            // Frame 2: Valid hop (seq = 2) at -50 dB
            now += 150L // Total elapsed = 250L >= 200L -> triggers finalization
            val ctx2 = sampleFrameContext(managerId = "mgr1", generation = 1L, sequence = 2L, fingerprint = baseFingerprint)
            manager.processFrame(FloatArray(31) { -50f }, FloatArray(8) { -50f }, 100f, frameContext = ctx2)

            // Verify capture completed
            assertFalse(manager.uiState.value.isCapturingNoiseFloor)
            assertTrue(manager.uiState.value.noiseFloor.isCaptured)

            // The resulting noise floor must be the exact power average of -40 dB and -50 dB ONLY.
            // If the duplicate (-10 dB) frame had been accumulated, the average power would be ~ -10 dB.
            val expectedAvg = (10.0 * kotlin.math.log10((0.0001 + 0.00001) / 2.0)).toFloat()
            assertArrayEquals(FloatArray(31) { expectedAvg }, manager.uiState.value.noiseFloor.bands31Db, 0.001f)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementC_statusOnlyOrNonRunningFrameCancelsAcquisitionImmediately() = runTest {
        var now = 1000L
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope, nowMs = { now })

        try {
            val baseFingerprint = sampleFingerprint(sampleRate = 48000, micId = "micA")
            val auth = sampleAuthority(managerId = "mgr1", fingerprint = baseFingerprint)
            manager.updateCaptureAuthority(auth)
            manager.startNoiseFloorCapture(durationMs = 200L, authority = auth)
            assertTrue(manager.uiState.value.isCapturingNoiseFloor)

            // Frame 1: Valid PCM hop (seq = 1)
            val ctx1 = sampleFrameContext(managerId = "mgr1", generation = 1L, sequence = 1L, fingerprint = baseFingerprint)
            manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = ctx1)
            assertTrue(manager.uiState.value.isCapturingNoiseFloor)

            // Status-only emission (isRunning = false) -> cancels active acquisition immediately per Stage 5
            val ctxNotRunning = ctx1.copy(isRunning = false, frameSequence = 2L)
            manager.processFrame(FloatArray(31) { -10f }, FloatArray(8) { -10f }, 100f, frameContext = ctxNotRunning)

            assertFalse("Status-only or stopped frame must cancel active acquisition", manager.uiState.value.isCapturingNoiseFloor)
            assertFalse("Cancelled acquisition must not capture noise floor", manager.uiState.value.noiseFloor.isCaptured)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementC_midCaptureRouteChangeCancelsAcquisitionAndPreservesExistingFloor() = runTest {
        var now = 1000L
        val prefs = testPreferences()
        val originalFp = sampleFingerprint(sampleRate = 48000, micId = "micOriginal")
        val originalBands = FloatArray(31) { -55f }
        val originalTimestamp = 1234L

        // Set up pre-existing captured noise floor
        prefs.edit()
            .putString("noise_floor_bands", originalBands.joinToString(","))
            .putLong("noise_floor_timestamp", originalTimestamp)
            .putString("noise_floor_fingerprint", originalFp.toJsonString())
            .putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .commit()

        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope, nowMs = { now })

        try {
            assertTrue("Pre-existing floor is loaded", manager.uiState.value.noiseFloor.isCaptured)
            assertEquals("Pre-existing timestamp preserved", originalTimestamp, manager.uiState.value.noiseFloor.captureTimestampMs)

            val micA = sampleFingerprint(sampleRate = 48000, micId = "micA")
            val micB = sampleFingerprint(sampleRate = 48000, micId = "micB")
            val authA = sampleAuthority(fingerprint = micA)
            manager.updateCaptureAuthority(authA)

            // Start new noise floor capture
            manager.startNoiseFloorCapture(durationMs = 200L, authority = authA)
            assertTrue(manager.uiState.value.isCapturingNoiseFloor)

            // Frame 1 with micA
            now += 50L
            val ctx1 = sampleFrameContext(sequence = 1L, fingerprint = micA)
            manager.processFrame(FloatArray(31) { -10f }, FloatArray(8) { -10f }, 100f, frameContext = ctx1)
            assertTrue(manager.uiState.value.isCapturingNoiseFloor)

            // Routing switched to micB mid-capture!
            val authB = sampleAuthority(routeEpoch = 2L, fingerprint = micB)
            manager.updateCaptureAuthority(authB)
            now += 50L
            val ctx2 = sampleFrameContext(sequence = 2L, fingerprint = micB)
            manager.processFrame(FloatArray(31) { -60f }, FloatArray(8) { -60f }, 100f, frameContext = ctx2)

            // Acquisition must be CANCELED rather than silently restarting accumulation
            assertFalse("Acquisition must be canceled on route change", manager.uiState.value.isCapturingNoiseFloor)
            assertEquals("Progress must be reset to 0", 0.0f, manager.uiState.value.noiseCaptureProgress, 0.0f)

            // Pre-existing floor must be fully preserved
            assertTrue("Pre-existing floor remains captured", manager.uiState.value.noiseFloor.isCaptured)
            assertEquals("Pre-existing timestamp preserved", originalTimestamp, manager.uiState.value.noiseFloor.captureTimestampMs)
            assertEquals("Pre-existing fingerprint preserved", originalFp, manager.uiState.value.noiseFloor.fingerprint)
            assertArrayEquals("Pre-existing bands preserved", originalBands, manager.uiState.value.noiseFloor.bands31Db, 0.001f)
        } finally {
            manager.close()
        }
    }

    // =========================================================================
    // Requirement D: Persist fingerprint with the noise floor & Gate subtraction
    // =========================================================================

    @Test
    fun requirementD_legacyPreferencesWithoutFingerprintPreservesDataAndRequiresRecapture() = runTest {
        val prefs = testPreferences()
        val legacyBands = FloatArray(31) { -55.0f }
        val legacyTimestamp = 987654321L

        // Write legacy preferences (lacking noise_floor_fingerprint)
        prefs.edit()
            .putString("noise_floor_bands", legacyBands.joinToString(","))
            .putLong("noise_floor_timestamp", legacyTimestamp)
            .putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .commit()

        val beforeAll = prefs.all.toMap()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)

        try {
            val state = manager.uiState.value
            // 1. Preserves existing historical floor values
            assertTrue("Snapshot must reflect captured status", state.noiseFloor.isCaptured)
            assertEquals("Historical timestamp must be preserved", legacyTimestamp, state.noiseFloor.captureTimestampMs)
            assertArrayEquals("Historical band values must be preserved", legacyBands, state.noiseFloor.bands31Db, 0.001f)

            // 2. Disables effective live subtraction
            assertFalse("Effective subtraction must be disabled for legacy un-fingerprinted floor", state.isNoiseSubtractionEffective)

            // 3. User toggle remains true (not destructively overwritten)
            assertTrue("User preference switch is preserved", state.isNoiseSubtractionActive)

            // 4. Requires recapture
            assertTrue("Must explicitly flag that recapture is required", state.noiseFloor.requiresRecapture)
            assertNull("Must not invent fake fingerprint metadata", state.noiseFloor.fingerprint)

            // 5. Processing audio frames does NOT subtract noise floor
            val liveFingerprint = sampleFingerprint(sampleRate = 48000, micId = "mic1")
            val frameCtx = sampleFrameContext(fingerprint = liveFingerprint)
            val inputBands = FloatArray(31) { -40f }
            val frame = manager.processFrame(inputBands, FloatArray(8) { -40f }, 100f, frameContext = frameCtx)

            // Since noise subtraction is not effective, calibrated output bands must equal input bands (flat)
            assertArrayEquals("Output bands must not undergo noise subtraction", inputBands, frame.calibrated31Db, 0.001f)

            // 6. Preferences were not deleted or modified
            assertEquals("SharedPreferences must remain completely intact without destructive migrations", beforeAll, prefs.all)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementD_freshCapturePersistsFingerprintAndEnablesSubtraction() = runTest {
        var now = 2_000L
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope, nowMs = { now })

        val fp = sampleFingerprint(
            sampleRate = 48000,
            micId = "authenticated_mic_007",
            devType = "TYPE_BUILTIN_MIC"
        )

        try {
            val auth = sampleAuthority(managerId = "mgr1", fingerprint = fp)
            manager.updateCaptureAuthority(auth)
            manager.startNoiseFloorCapture(durationMs = 100L, authority = auth)
            val ctx1 = sampleFrameContext(managerId = "mgr1", generation = 1L, sequence = 1L, fingerprint = fp)
            manager.processFrame(FloatArray(31) { -50f }, FloatArray(8) { -50f }, 100f, frameContext = ctx1)

            now += 150L
            val ctx2 = sampleFrameContext(managerId = "mgr1", generation = 1L, sequence = 2L, fingerprint = fp)
            manager.processFrame(FloatArray(31) { -50f }, FloatArray(8) { -50f }, 100f, frameContext = ctx2)

            // Capture complete
            val state = manager.uiState.value
            assertFalse(state.isCapturingNoiseFloor)
            assertTrue(state.noiseFloor.isCaptured)
            assertFalse("Fresh capture must NOT require recapture", state.noiseFloor.requiresRecapture)
            assertNotNull("Fresh capture must have persisted fingerprint", state.noiseFloor.fingerprint)
            assertEquals(fp, state.noiseFloor.fingerprint)

            // SharedPreferences check
            val persistedFingerprintJson = prefs.getString("noise_floor_fingerprint", null)
            assertNotNull("Fingerprint must be saved in SharedPreferences", persistedFingerprintJson)
            val reloadedFp = CaptureFingerprint.fromJsonString(persistedFingerprintJson)
            assertEquals(fp, reloadedFp)

            // Subtraction is effective with matching live frame
            val liveCtx = sampleFrameContext(managerId = "mgr1", generation = 1L, sequence = 3L, fingerprint = fp)
            val processed = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = liveCtx)

            val expectedBand = AudioMath.subtractNoiseFloor(-40f, -50f, AudioMath.MIN_DB_FS).correctedDb
            assertArrayEquals(FloatArray(31) { expectedBand }, processed.calibrated31Db, 0.001f)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementD_liveRouteMismatchDynamicallyDisablesSubtractionWithoutCorruptingSnapshot() = runTest {
        var now = 3_000L
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope, nowMs = { now })

        val micA = sampleFingerprint(sampleRate = 48000, micId = "headset_mic", devType = "TYPE_WIRED_HEADSET")
        val micB = sampleFingerprint(sampleRate = 48000, micId = "bluetooth_sco", devType = "TYPE_BLUETOOTH_SCO")

        try {
            // 1. Capture on micA
            val authA = sampleAuthority(fingerprint = micA)
            manager.updateCaptureAuthority(authA)
            manager.startNoiseFloorCapture(durationMs = 100L, authority = authA)
            manager.processFrame(FloatArray(31) { -50f }, FloatArray(8) { -50f }, 100f, frameContext = sampleFrameContext(sequence = 1L, fingerprint = micA))
            now += 150L
            manager.processFrame(FloatArray(31) { -50f }, FloatArray(8) { -50f }, 100f, frameContext = sampleFrameContext(sequence = 2L, fingerprint = micA))

            // Subtraction is active on micA
            val frameMicA = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = sampleFrameContext(sequence = 3L, fingerprint = micA))
            val expectedSubtracted = AudioMath.subtractNoiseFloor(-40f, -50f, AudioMath.MIN_DB_FS).correctedDb
            assertArrayEquals(FloatArray(31) { expectedSubtracted }, frameMicA.calibrated31Db, 0.001f)

            // 2. Microphone switches to micB
            val authB = sampleAuthority(routeEpoch = 2L, fingerprint = micB)
            manager.updateCaptureAuthority(authB)
            val frameMicB = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = sampleFrameContext(sequence = 4L, fingerprint = micB, routeEpoch = 2L))
            // Live subtraction is gated off dynamically due to route mismatch
            assertArrayEquals("Noise subtraction must NOT apply when route differs from capture context", FloatArray(31) { -40f }, frameMicB.calibrated31Db, 0.001f)

            // Snapshot is still intact and not corrupted
            assertEquals(micA, manager.uiState.value.noiseFloor.fingerprint)

            // 3. Microphone switches back to micA
            val authAReturn = sampleAuthority(routeEpoch = 3L, fingerprint = micA)
            manager.updateCaptureAuthority(authAReturn)
            val frameMicAReturn = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = sampleFrameContext(sequence = 5L, fingerprint = micA, routeEpoch = 3L))
            // Subtraction resumes automatically
            assertArrayEquals("Noise subtraction resumes when route matches snapshot again", FloatArray(31) { expectedSubtracted }, frameMicAReturn.calibrated31Db, 0.001f)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementD_clearNoiseFloorRemovesPersistedFingerprint() = runTest {
        val prefs = testPreferences()
        val fp = sampleFingerprint()
        prefs.edit()
            .putString("noise_floor_bands", FloatArray(31) { -50f }.joinToString(","))
            .putLong("noise_floor_timestamp", 1234L)
            .putString("noise_floor_fingerprint", fp.toJsonString())
            .putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .commit()

        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)

        try {
            assertTrue(manager.uiState.value.noiseFloor.isCaptured)
            manager.clearNoiseFloor()

            val state = manager.uiState.value
            assertFalse(state.noiseFloor.isCaptured)
            assertNull(state.noiseFloor.fingerprint)
            assertFalse(state.isNoiseSubtractionActive)
            assertFalse(prefs.contains("noise_floor_fingerprint"))
            assertFalse(prefs.getBoolean("noise_floor_captured", false))
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementB_requestedMicDifferingFromActualRoutedMic() {
        // Fingerprint binds observed routed microphone identity, NOT requested mic identity
        val fp = sampleFingerprint(
            selectedSource = 6, // requested VOICE_RECOGNITION
            actualSource = 1,   // actual fallback to MIC
            micId = "observed_usb_mic_01",
            devType = "TYPE_USB_DEVICE"
        )
        assertEquals("observed_usb_mic_01", fp.routedMicrophoneId)
        assertEquals("TYPE_USB_DEVICE", fp.routedDeviceType)
        assertTrue(fp.isSourceFallback)
        assertEquals(6, fp.selectedAudioSource)
        assertEquals(1, fp.actualAudioSource)
    }

    @Test
    fun requirementC_staleGenerationOrReplacedManagerCannotAdvanceAcquisition() = runTest {
        var now = 1000L
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope, nowMs = { now })

        try {
            val baseFp = sampleFingerprint()
            val auth = sampleAuthority(managerId = "mgr_A", generation = 2L, fingerprint = baseFp)
            manager.updateCaptureAuthority(auth)
            manager.startNoiseFloorCapture(durationMs = 200L, authority = auth)

            // Frame 1: generation = 2L, sequence = 1L
            now += 50L
            val ctxValid = sampleFrameContext(managerId = "mgr_A", generation = 2L, sequence = 1L, fingerprint = baseFp)
            manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = ctxValid)
            assertEquals(0.25f, manager.uiState.value.noiseCaptureProgress, 0.01f)

            // Stale generation frame: generation = 1L -> cancels acquisition immediately per Stage 5
            now += 50L
            val ctxStaleGen = sampleFrameContext(managerId = "mgr_A", generation = 1L, sequence = 2L, fingerprint = baseFp)
            manager.processFrame(FloatArray(31) { -10f }, FloatArray(8) { -10f }, 100f, frameContext = ctxStaleGen)

            assertFalse("Stale or changed generation must cancel acquisition immediately", manager.uiState.value.isCapturingNoiseFloor)
            assertFalse("Cancelled acquisition must not capture noise floor", manager.uiState.value.noiseFloor.isCaptured)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementC_replacedManagerCancelsAcquisitionImmediately() = runTest {
        var now = 1000L
        val prefs = testPreferences()
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope, nowMs = { now })

        try {
            val baseFp = sampleFingerprint()
            val auth = sampleAuthority(managerId = "mgr_A", generation = 1L, fingerprint = baseFp)
            manager.updateCaptureAuthority(auth)
            manager.startNoiseFloorCapture(durationMs = 200L, authority = auth)

            // Frame 1: valid hop
            val ctxValid = sampleFrameContext(managerId = "mgr_A", generation = 1L, sequence = 1L, fingerprint = baseFp)
            manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = ctxValid)
            assertTrue(manager.uiState.value.isCapturingNoiseFloor)

            // Replaced manager frame: managerId = "mgr_B" -> cancels acquisition immediately per Stage 5
            val ctxOtherMgr = sampleFrameContext(managerId = "mgr_B", generation = 1L, sequence = 2L, fingerprint = baseFp)
            manager.processFrame(FloatArray(31) { -10f }, FloatArray(8) { -10f }, 100f, frameContext = ctxOtherMgr)

            assertFalse("Replaced manager must cancel acquisition immediately", manager.uiState.value.isCapturingNoiseFloor)
            assertFalse("Cancelled acquisition must not capture noise floor", manager.uiState.value.noiseFloor.isCaptured)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementD_malformedPersistedFingerprintHandledSafely() = runTest {
        val prefs = testPreferences()
        val legacyBands = FloatArray(31) { -52.0f }
        val legacyTimestamp = 8888L

        // Test non-JSON string
        prefs.edit()
            .putString("noise_floor_bands", legacyBands.joinToString(","))
            .putLong("noise_floor_timestamp", legacyTimestamp)
            .putString("noise_floor_fingerprint", "<<<CORRUPTED_NON_JSON>>>")
            .putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .commit()

        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)

        try {
            val state = manager.uiState.value
            assertTrue("Snapshot reflects captured status", state.noiseFloor.isCaptured)
            assertEquals("Historical timestamp preserved", legacyTimestamp, state.noiseFloor.captureTimestampMs)
            assertArrayEquals("Historical bands preserved", legacyBands, state.noiseFloor.bands31Db, 0.001f)

            // Non-JSON string causes fromJsonString to return null -> requiresRecapture = true, fingerprint = null
            assertTrue("Requires recapture for malformed fingerprint", state.noiseFloor.requiresRecapture)
            assertNull("Fingerprint is null when malformed", state.noiseFloor.fingerprint)
            assertFalse("Effective subtraction disabled", state.isNoiseSubtractionEffective)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementD_proToFreeRevocationAndProRestoration() = runTest {
        val prefs = testPreferences()
        val fp = sampleFingerprint(sampleRate = 48000, micId = "mic1")
        prefs.edit()
            .putString("noise_floor_bands", FloatArray(31) { -50f }.joinToString(","))
            .putLong("noise_floor_timestamp", 1234L)
            .putString("noise_floor_fingerprint", fp.toJsonString())
            .putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .commit()

        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)

        try {
            val auth = sampleAuthority(fingerprint = fp)
            manager.updateCaptureAuthority(auth)
            val liveCtx = sampleFrameContext(fingerprint = fp)
            val expectedSubtracted = AudioMath.subtractNoiseFloor(-40f, -50f, AudioMath.MIN_DB_FS).correctedDb

            // 1. Pro mode: subtraction is effective
            val framePro1 = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = liveCtx)
            assertTrue(manager.uiState.value.isNoiseSubtractionEffective)
            assertArrayEquals(FloatArray(31) { expectedSubtracted }, framePro1.calibrated31Db, 0.001f)

            // 2. Revoke to Free
            entitlements.setTier(EntitlementTier.FREE)
            runCurrent()
            val frameFree = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = liveCtx)
            assertFalse(manager.uiState.value.isNoiseSubtractionEffective)
            assertArrayEquals(FloatArray(31) { -40f }, frameFree.calibrated31Db, 0.001f)
            assertTrue("Snapshot remains preserved on Free tier", manager.uiState.value.noiseFloor.isCaptured)

            // 3. Restore to Pro
            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            runCurrent()
            val framePro2 = manager.processFrame(FloatArray(31) { -40f }, FloatArray(8) { -40f }, 100f, frameContext = liveCtx)
            assertTrue(manager.uiState.value.isNoiseSubtractionEffective)
            assertArrayEquals(FloatArray(31) { expectedSubtracted }, framePro2.calibrated31Db, 0.001f)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementD_fftWindowSizeChangeDoesNotInvalidateRawDomainNoiseFloor() = runTest {
        val prefs = testPreferences()
        val fp = sampleFingerprint(sampleRate = 48000, micId = "mic1")
        prefs.edit()
            .putString("noise_floor_bands", FloatArray(31) { -50f }.joinToString(","))
            .putLong("noise_floor_timestamp", 1234L)
            .putString("noise_floor_fingerprint", fp.toJsonString())
            .putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .commit()

        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)

        try {
            val auth = sampleAuthority(fingerprint = fp)
            manager.updateCaptureAuthority(auth)
            val liveCtx = sampleFrameContext(fingerprint = fp)
            val expectedSubtracted = AudioMath.subtractNoiseFloor(-40f, -50f, AudioMath.MIN_DB_FS).correctedDb

            // 4096-point FFT: 2049 bins, binResolution = 48000 / 4096 = 11.71875 Hz
            val frame4096 = manager.processFrame(
                FloatArray(31) { -40f },
                FloatArray(2049) { -40f },
                11.71875f,
                frameContext = liveCtx
            )
            assertArrayEquals(FloatArray(31) { expectedSubtracted }, frame4096.calibrated31Db, 0.001f)

            // Reconfigured / restarted to 2048-point FFT: 1025 bins, binResolution = 48000 / 2048 = 23.4375 Hz
            val frame2048 = manager.processFrame(
                FloatArray(31) { -40f },
                FloatArray(1025) { -40f },
                23.4375f,
                frameContext = liveCtx
            )
            // 31-band raw-domain noise subtraction remains compatible and fully effective
            assertArrayEquals(FloatArray(31) { expectedSubtracted }, frame2048.calibrated31Db, 0.001f)
            assertTrue(manager.uiState.value.isNoiseSubtractionEffective)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementD_micCalibrationAndTargetChangesDoNotInvalidateCompatibleNoiseFloor() = runTest {
        val prefs = testPreferences()
        val fp = sampleFingerprint(sampleRate = 48000, micId = "mic1")
        prefs.edit()
            .putString("noise_floor_bands", FloatArray(31) { -50f }.joinToString(","))
            .putLong("noise_floor_timestamp", 1234L)
            .putString("noise_floor_fingerprint", fp.toJsonString())
            .putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .commit()

        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)

        try {
            val auth = sampleAuthority(fingerprint = fp)
            manager.updateCaptureAuthority(auth)
            val liveCtx = sampleFrameContext(fingerprint = fp)
            assertTrue(manager.uiState.value.isNoiseSubtractionEffective)

            // Load mic curve
            manager.loadMicCurve("Test Mic", "20 2.0\n20000 2.0")
            assertTrue("Noise floor remains captured after mic curve load", manager.uiState.value.noiseFloor.isCaptured)
            assertTrue("Noise subtraction remains effective", manager.uiState.value.isNoiseSubtractionEffective)

            // Set target curve
            manager.setTargetCurve(com.rtatuner.app.core.calibration.TargetCurve.HARMAN_CAR)
            manager.setTargetCurveActive(true)
            assertTrue("Noise subtraction remains effective after target curve change", manager.uiState.value.isNoiseSubtractionEffective)
            assertEquals(fp, manager.uiState.value.noiseFloor.fingerprint)
        } finally {
            manager.close()
        }
    }

    @Test
    fun requirementD_endToEndRawBandSubtractionOccursBeforeMicAndSplCorrection() = runTest {
        val prefs = testPreferences()
        val fp = sampleFingerprint(sampleRate = 48000, micId = "mic1")
        prefs.edit()
            .putString("noise_floor_bands", FloatArray(31) { -50f }.joinToString(","))
            .putLong("noise_floor_timestamp", 1234L)
            .putString("noise_floor_fingerprint", fp.toJsonString())
            .putBoolean("noise_floor_captured", true)
            .putBoolean("noise_subtraction_active", true)
            .commit()

        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val manager = CalibrationManager(prefs, FeatureAccessPolicy(entitlements), backgroundScope)

        try {
            val auth = sampleAuthority(fingerprint = fp)
            manager.updateCaptureAuthority(auth)
            // Apply mic curve with +3 dB offset
            manager.loadMicCurve("Flat+3dB", "20 3.0\n20000 3.0", com.rtatuner.app.core.calibration.CalibrationFileConvention.ADDITIVE_CORRECTION)
            manager.setMicCurveActive(true)

            // Apply SPL offset (+100 dB)
            manager.setSplOffset(100f)
            manager.setSplMode(true)

            val liveCtx = sampleFrameContext(fingerprint = fp)
            val frame = manager.processFrame(
                FloatArray(31) { -40f },
                FloatArray(8) { -40f },
                100f,
                frameContext = liveCtx
            )

            // Order must be:
            // 1. Raw noise subtraction: AudioMath.subtractNoiseFloor(-40, -50)
            // 2. Mic correction: + 3 dB
            // 3. SPL calibration: + 100 dB
            val expectedRawSub = AudioMath.subtractNoiseFloor(-40f, -50f, AudioMath.MIN_DB_FS).correctedDb
            val expectedEndToEnd = expectedRawSub + 3.0f + 100.0f

            assertArrayEquals(FloatArray(31) { expectedEndToEnd }, frame.calibrated31Db, 0.001f)
            assertTrue(frame.isSplMode)
        } finally {
            manager.close()
        }
    }
}
