package com.rtatuner.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rtatuner.app.core.database.SessionWithSnapshots
import com.rtatuner.app.core.database.TuningSessionEntity
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.core.entitlement.ProFeature
import com.rtatuner.app.ui.components.SavedSessionsContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Click the production Compose save button; labels alone do not prove routing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionSaveButtonTest {
    val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostActivityRule()).around(compose)

    @Test
    fun freeAtCapacityOpensProDetailsWithoutOpeningSaveDialog() {
        assertSaveButtonRoute(EntitlementTier.FREE, savedCount = 1, expectedSaveDialogs = 0)
    }

    @Test
    fun freeWithAvailableCapacityOpensSaveDialog() {
        assertSaveButtonRoute(EntitlementTier.FREE, savedCount = 0, expectedSaveDialogs = 1)
    }

    @Test
    fun proWithExistingSessionsOpensSaveDialog() {
        assertSaveButtonRoute(EntitlementTier.PRO_LIFETIME, savedCount = 3, expectedSaveDialogs = 1)
    }

    @Test
    fun updatedCapacityReplacesThePreviouslyAvailableSaveCallback() {
        val policy = FeatureAccessPolicy(FakeEntitlementRepository())
        val sessions = mutableStateOf(emptyList<SessionWithSnapshots>())
        var saveDialogs = 0
        var proDetails = 0
        compose.setContent {
            MaterialTheme {
                SavedSessionsContent(
                    savedSessions = sessions.value,
                    canExportCsv = false,
                    canSaveNewSession = policy.checkSessionCapacity(sessions.value.size) is FeatureAccessResult.Allowed,
                    onOpenSaveDialog = { saveDialogs++ },
                    onOverlaySession = {},
                    onExportSessionCsv = {},
                    onEditNotes = {},
                    onDeleteSession = {},
                    onLearnMore = { proDetails++ }
                )
            }
        }
        compose.onNodeWithText("SAVE CURRENT TO SESSION HISTORY").assertExists()

        compose.runOnIdle { sessions.value = savedSessions(1) }
        compose.onNodeWithText("SAVE NEW SESSION · PRO").performClick()

        compose.runOnIdle {
            assertEquals(0, saveDialogs)
            assertEquals(1, proDetails)
        }
    }

    private fun assertSaveButtonRoute(tier: EntitlementTier, savedCount: Int, expectedSaveDialogs: Int) {
        val policy = FeatureAccessPolicy(FakeEntitlementRepository(tier))
        val canSave = policy.checkSessionCapacity(savedCount) is FeatureAccessResult.Allowed
        var saveDialogs = 0
        var proDetails = 0
        compose.setContent {
            MaterialTheme {
                SavedSessionsContent(
                    savedSessions = savedSessions(savedCount),
                    canExportCsv = policy.check(ProFeature.CSV_EXPORT) is FeatureAccessResult.Allowed,
                    canSaveNewSession = canSave,
                    onOpenSaveDialog = { saveDialogs++ },
                    onOverlaySession = {},
                    onExportSessionCsv = {},
                    onEditNotes = {},
                    onDeleteSession = {},
                    onLearnMore = { proDetails++ }
                )
            }
        }
        compose.onNodeWithText(
            if (canSave) "SAVE CURRENT TO SESSION HISTORY" else "SAVE NEW SESSION · PRO"
        ).performClick()

        compose.runOnIdle {
            assertEquals(expectedSaveDialogs, saveDialogs)
            assertEquals(1 - expectedSaveDialogs, proDetails)
        }
    }

    private fun savedSessions(count: Int) = (1..count).map { index ->
        SessionWithSnapshots(TuningSessionEntity(id = index.toLong(), name = "Saved $index"), emptyList())
    }
}
