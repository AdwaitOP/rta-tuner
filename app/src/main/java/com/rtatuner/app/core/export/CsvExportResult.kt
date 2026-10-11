package com.rtatuner.app.core.export

import com.rtatuner.app.core.entitlement.FeatureAccessResult

/** Result of initiating CSV sharing; opening the chooser does not confirm delivery. */
sealed interface CsvExportResult {
    data class AccessDenied(val denial: FeatureAccessResult.Denied) : CsvExportResult
    data object ShareChooserLaunched : CsvExportResult
    data class Failed(val cause: Exception) : CsvExportResult
}

/** An authorized export could not prepare its file or launch the Android chooser. */
class CsvShareException(cause: Exception) : Exception("Could not prepare or open CSV sharing.", cause)
