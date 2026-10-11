package com.rtatuner.app.core.entitlement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug source set only: ephemeral simulation, never persisted as purchase ownership. */
class DebugEntitlementRepository : EntitlementRepository {
    private val mutableState = MutableStateFlow(EntitlementState())
    override val state = mutableState.asStateFlow()

    fun simulate(tier: EntitlementTier) {
        mutableState.value = EntitlementState(tier)
    }
}

object AppEntitlements {
    fun create(): EntitlementRepository = DebugEntitlementRepository()
    fun simulate(repository: EntitlementRepository, tier: EntitlementTier): Boolean {
        if (repository !is DebugEntitlementRepository) return false
        repository.simulate(tier)
        return true
    }
}
