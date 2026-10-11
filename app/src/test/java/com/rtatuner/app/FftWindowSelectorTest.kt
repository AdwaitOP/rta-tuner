package com.rtatuner.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.ui.FftWindowSelector
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production selector remains clickable so the ViewModel can explain a denied request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FftWindowSelectorTest {
    val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostActivityRule()).around(compose)

    @Test
    fun free4096ShowsProAndForwardsClickForContextualUpgrade() {
        val selections = mutableListOf<Int>()
        setSelector(FakeEntitlementRepository(), selections)

        compose.onNodeWithText("4096 · PRO").assertIsDisplayed().performClick()
        compose.onNodeWithText("4096").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(4096), selections) }
    }

    @Test
    fun free1024And2048RemainVisibleAndClickableWithoutProLabels() {
        val selections = mutableListOf<Int>()
        setSelector(FakeEntitlementRepository(), selections)

        compose.onNodeWithText("1024").assertIsDisplayed().performClick()
        compose.onNodeWithText("2048").assertIsDisplayed().performClick()
        compose.onNodeWithText("1024 · PRO").assertDoesNotExist()
        compose.onNodeWithText("2048 · PRO").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(1024, 2048), selections) }
    }

    @Test
    fun pro4096UsesNormalSelectableLabel() {
        val selections = mutableListOf<Int>()
        setSelector(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME), selections)

        compose.onNodeWithText("4096").assertIsDisplayed().performClick()
        compose.onNodeWithText("4096 · PRO").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(4096), selections) }
    }

    @Test
    fun entitlementChangesReactivelyUpdateTheLabelWithoutDisablingUpgradeClick() {
        val repository = FakeEntitlementRepository()
        val selections = mutableListOf<Int>()
        setSelector(repository, selections)
        compose.onNodeWithText("4096 · PRO").assertIsDisplayed()

        compose.runOnIdle { repository.setTier(EntitlementTier.PRO_LIFETIME) }
        compose.onNodeWithText("4096").assertIsDisplayed().performClick()
        compose.runOnIdle { repository.setTier(EntitlementTier.FREE) }
        compose.onNodeWithText("4096 · PRO").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(listOf(4096, 4096), selections) }
    }

    private fun setSelector(repository: FakeEntitlementRepository, selections: MutableList<Int>) {
        val policy = FeatureAccessPolicy(repository)
        compose.setContent {
            val entitlement by repository.state.collectAsState()
            val canUse4096 = remember(entitlement) {
                policy.checkFftWindow(4096) is FeatureAccessResult.Allowed
            }
            MaterialTheme {
                FftWindowSelector(
                    windowSize = 2048,
                    canUse4096 = canUse4096,
                    onSelectWindowSize = { selections += it }
                )
            }
        }
    }
}
