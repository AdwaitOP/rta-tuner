package com.rtatuner.app.core.entitlement

/** This variant has no mutable provider or ownership override. */
object AppEntitlements {
    fun create(): EntitlementRepository = FreeEntitlementRepository
    fun simulate(
        @Suppress("UNUSED_PARAMETER") repository: EntitlementRepository,
        @Suppress("UNUSED_PARAMETER") tier: EntitlementTier
    ): Boolean = false
}
