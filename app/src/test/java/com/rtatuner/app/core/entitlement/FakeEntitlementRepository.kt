package com.rtatuner.app.core.entitlement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeEntitlementRepository(tier: EntitlementTier = EntitlementTier.FREE) : EntitlementRepository {
    private val mutableState = MutableStateFlow(EntitlementState(tier))
    override val state = mutableState.asStateFlow()
    fun setTier(tier: EntitlementTier) { mutableState.value = EntitlementState(tier) }
}
