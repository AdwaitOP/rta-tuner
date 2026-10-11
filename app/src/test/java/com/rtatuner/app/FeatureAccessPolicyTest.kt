package com.rtatuner.app

import com.rtatuner.app.core.entitlement.*
import org.junit.Assert.*
import org.junit.Test

class FeatureAccessPolicyTest {
    @Test fun `default entitlement is Free and every core capability remains available`() {
        assertEquals(EntitlementTier.FREE, AppEntitlements.create().state.value.tier)
        val policy = FeatureAccessPolicy()
        FreeFeature.entries.forEach { assertEquals(it.name, FeatureAccessResult.Allowed, policy.check(it)) }
        ProFeature.entries.forEach { assertTrue(it.name, policy.check(it) is FeatureAccessResult.Denied) }
        assertEquals(AccessLimits(1, 1), policy.limits)
    }

    @Test fun `all premium capabilities and limits react to grant and revocation`() {
        val repository = FakeEntitlementRepository()
        val policy = FeatureAccessPolicy(repository)
        repository.setTier(EntitlementTier.PRO_LIFETIME)
        ProFeature.entries.forEach { assertEquals(it.name, FeatureAccessResult.Allowed, policy.check(it)) }
        FreeFeature.entries.forEach { assertEquals(it.name, FeatureAccessResult.Allowed, policy.check(it)) }
        assertEquals(AccessLimits(null, null), policy.limits)
        assertEquals(FeatureAccessResult.Allowed, policy.checkOverlayCapacity(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(FeatureAccessResult.Allowed, policy.checkSessionCapacity(Int.MAX_VALUE))
        repository.setTier(EntitlementTier.FREE)
        ProFeature.entries.forEach { assertTrue(it.name, policy.check(it) is FeatureAccessResult.Denied) }
        assertEquals(AccessLimits(1, 1), policy.limits)
    }

    @Test fun `Free capacity counts all existing records and never requests truncation`() {
        val policy = FeatureAccessPolicy()
        assertEquals(FeatureAccessResult.Allowed, policy.checkOverlayCapacity(0, 1))
        assertEquals(FeatureAccessResult.Allowed, policy.checkOverlayCapacity(12, 0))
        assertTrue(policy.checkOverlayCapacity(0, 2) is FeatureAccessResult.Denied)
        assertTrue(policy.checkOverlayCapacity(1, 1) is FeatureAccessResult.Denied)
        assertTrue(policy.checkOverlayCapacity(Int.MAX_VALUE, Int.MAX_VALUE) is FeatureAccessResult.Denied)
        assertEquals(FeatureAccessResult.Allowed, policy.checkSessionCapacity(0))
        assertTrue(policy.checkSessionCapacity(1) is FeatureAccessResult.Denied)
        assertTrue(policy.checkSessionCapacity(20) is FeatureAccessResult.Denied)
    }

    @Test fun `debug simulation is ephemeral and release has no mutable debug provider`() {
        val provider = AppEntitlements.create()
        val changed = AppEntitlements.simulate(provider, EntitlementTier.PRO_LIFETIME)
        if (BuildConfig.DEBUG) {
            assertTrue(changed)
            assertTrue(provider.state.value.isPro)
            assertFalse(AppEntitlements.create().state.value.isPro)
            assertTrue(AppEntitlements.simulate(provider, EntitlementTier.FREE))
        } else {
            assertFalse(changed)
            assertFalse(provider.state.value.isPro)
            assertThrows(ClassNotFoundException::class.java) {
                Class.forName("com.rtatuner.app.core.entitlement.DebugEntitlementRepository")
            }
        }
        assertFalse(provider.state.value.isPro)
    }
}
