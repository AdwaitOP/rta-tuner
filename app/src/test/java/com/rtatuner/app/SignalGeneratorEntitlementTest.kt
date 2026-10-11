package com.rtatuner.app

import com.rtatuner.app.core.audio.GeneratorMode
import com.rtatuner.app.core.audio.SignalGenerator
import com.rtatuner.app.core.audio.SweepRangePreset
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SignalGeneratorEntitlementTest {
    @Test
    fun defaultFreeRejectsSweepAndEveryConfigurationWithoutMutatingState() = runTest {
        val generator = SignalGenerator(scope = backgroundScope)
        try {
            val before = generator.state.value
            assertTrue(generator.startSineSweep() is FeatureAccessResult.Denied)
            assertTrue(generator.setSweepDuration(10f) is FeatureAccessResult.Denied)
            assertTrue(generator.setSweepRange(SweepRangePreset.SUB_BASS) is FeatureAccessResult.Denied)
            assertTrue(generator.setSweepLooping(false) is FeatureAccessResult.Denied)
            assertEquals(before, generator.state.value)
            // The shared output level is also used by Free pink noise.
            generator.setOutputLevel(-18f)
            assertEquals(-18f, generator.state.value.outputLevelDb, 0f)
        } finally { generator.close() }
    }

    @Test
    fun proAllowsSweepSettingsAndRevocationPreservesThoseSettings() = runTest {
        val entitlements = FakeEntitlementRepository()
        val generator = SignalGenerator(featurePolicy = FeatureAccessPolicy(entitlements), scope = backgroundScope)
        try {
            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            runCurrent()
            assertEquals(FeatureAccessResult.Allowed, generator.setSweepDuration(10f))
            assertEquals(FeatureAccessResult.Allowed, generator.setSweepRange(SweepRangePreset.SUB_BASS))
            assertEquals(FeatureAccessResult.Allowed, generator.setSweepLooping(false))
            val before = generator.state.value
            entitlements.setTier(EntitlementTier.FREE)
            runCurrent()
            assertEquals(before, generator.state.value)
            assertTrue(generator.setSweepDuration(5f) is FeatureAccessResult.Denied)
            assertTrue(generator.startSineSweep() is FeatureAccessResult.Denied)
            assertEquals(before, generator.state.value)
        } finally { generator.close() }
    }

    @Test
    fun proSweepStopsOnRevocationAndDoesNotRestartOnRegrant() = runTest {
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val generator = SignalGenerator(featurePolicy = FeatureAccessPolicy(entitlements), scope = backgroundScope)
        try {
            assertEquals(FeatureAccessResult.Allowed, generator.startSineSweep(range = SweepRangePreset.SUB_BASS))
            assertEquals(GeneratorMode.SINE_SWEEP, generator.state.value.mode)
            assertTrue(generator.state.value.isPlaying)
            // Allow the real generator thread to initialize AudioTrack before revocation.
            val deadline = System.nanoTime() + 2_000_000_000L
            val trackField = SignalGenerator::class.java.getDeclaredField("audioTrack").apply { isAccessible = true }
            while (trackField.get(generator) == null && generator.state.value.errorMessage == null && System.nanoTime() < deadline) {
                Thread.sleep(5L)
            }
            assertNull(generator.state.value.errorMessage)
            assertNotNull("Sweep must have initialized its AudioTrack", trackField.get(generator))

            entitlements.setTier(EntitlementTier.FREE)
            runCurrent()
            assertEquals(GeneratorMode.OFF, generator.state.value.mode)
            assertFalse(generator.state.value.isPlaying)
            assertEquals(SweepRangePreset.SUB_BASS, generator.state.value.sweepRange)
            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            runCurrent()
            assertEquals(GeneratorMode.OFF, generator.state.value.mode)
            assertFalse(generator.state.value.isPlaying)
        } finally { generator.close() }
    }

    @Test
    fun immediateRevocationCannotResurrectAPlaybackThreadStillStarting() = runTest {
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val generator = SignalGenerator(featurePolicy = FeatureAccessPolicy(entitlements), scope = backgroundScope)
        try {
            repeat(4) {
                entitlements.setTier(EntitlementTier.PRO_LIFETIME)
                runCurrent()
                assertEquals(FeatureAccessResult.Allowed, generator.startSineSweep())
                entitlements.setTier(EntitlementTier.FREE)
                runCurrent()
                assertFalse(generator.state.value.isPlaying)
                assertEquals(GeneratorMode.OFF, generator.state.value.mode)
            }
            Thread.sleep(25L)
            assertEquals(GeneratorMode.OFF, generator.state.value.mode)
            assertFalse(generator.state.value.isPlaying)
            assertTrue(generator.startSineSweep() is FeatureAccessResult.Denied)
        } finally { generator.close() }
    }

    @Test
    fun freePinkNoiseSurvivesEntitlementTransitionsAndRejectedSweepRequest() = runTest {
        val entitlements = FakeEntitlementRepository()
        val generator = SignalGenerator(featurePolicy = FeatureAccessPolicy(entitlements), scope = backgroundScope)
        try {
            generator.startPinkNoise(-18f)
            assertEquals(GeneratorMode.PINK_NOISE, generator.state.value.mode)
            assertTrue(generator.state.value.isPlaying)
            val before = generator.state.value
            assertTrue(generator.startSineSweep() is FeatureAccessResult.Denied)
            assertEquals(before, generator.state.value)
            entitlements.setTier(EntitlementTier.PRO_LIFETIME)
            runCurrent()
            entitlements.setTier(EntitlementTier.FREE)
            runCurrent()
            assertEquals(GeneratorMode.PINK_NOISE, generator.state.value.mode)
            assertTrue(generator.state.value.isPlaying)
        } finally { generator.close() }
    }
}
