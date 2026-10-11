package com.rtatuner.app.core.entitlement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EntitlementTier { FREE, PRO_LIFETIME }

/** Ownership is in memory only in Phase 2. Billing will supply verified state in Phase 3. */
data class EntitlementState(val tier: EntitlementTier = EntitlementTier.FREE) {
    val isPro: Boolean get() = tier == EntitlementTier.PRO_LIFETIME
}

interface EntitlementRepository {
    val state: StateFlow<EntitlementState>
}

/** Fail-closed production provider until verified purchase ownership exists. */
object FreeEntitlementRepository : EntitlementRepository {
    override val state: StateFlow<EntitlementState> =
        MutableStateFlow(EntitlementState()).asStateFlow()
}
