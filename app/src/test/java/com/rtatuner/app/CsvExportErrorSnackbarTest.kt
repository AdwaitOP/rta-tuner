package com.rtatuner.app

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.export.CsvExportResult
import com.rtatuner.app.ui.AudioCaptureViewModel
import com.rtatuner.app.ui.CsvExportErrorEffect
import java.io.File
import java.io.IOException
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses the same failure event collector and SnackbarHost as DebugScreen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CsvExportErrorSnackbarTest {
    val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(ComposeHostActivityRule()).around(compose)

    @Test fun authorizedFailureAppearsAsFriendlySnackbarWithoutProUpgrade() {
        val application = RuntimeEnvironment.getApplication()
        val model = AudioCaptureViewModel(application, FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME))
        val store = ViewModelStore().apply { put("csv-error", model) }
        try {
            compose.setContent {
                MaterialTheme {
                    val snackbar = remember { SnackbarHostState() }
                    CsvExportErrorEffect(model.csvExportErrors, snackbar)
                    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                        Box(Modifier.padding(padding))
                    }
                }
            }
            compose.runOnIdle {
                val failingContext = object : ContextWrapper(application) {
                    override fun getCacheDir(): File = throw IOException("Technical detail must stay in logs")
                }
                assertTrue(model.exportLiveCsv(failingContext) is CsvExportResult.Failed)
                assertNull(model.accessDenial.value)
            }
            compose.onNodeWithText("Could not share CSV. Please try again.").assertIsDisplayed()
            compose.onNodeWithText("Technical detail must stay in logs").assertDoesNotExist()
            compose.onNodeWithText("CABINRTA PRO").assertDoesNotExist()
        } finally { store.clear() }
    }
}
