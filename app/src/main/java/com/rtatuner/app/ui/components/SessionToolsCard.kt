package com.rtatuner.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtatuner.app.core.audio.AudioCaptureUiState
import com.rtatuner.app.core.database.SessionWithSnapshots
import com.rtatuner.app.core.export.RtaExportManager
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.core.entitlement.ProFeature
import com.rtatuner.app.core.session.RtaSnapshot
import com.rtatuner.app.ui.AudioCaptureViewModel
import com.rtatuner.app.ui.components.surface.RtaSurfaceView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionToolsCard(
    viewModel: AudioCaptureViewModel,
    surfaceViewRef: RtaSurfaceView?,
    uiState: AudioCaptureUiState,
    frozenSnapshots: List<RtaSnapshot>,
    savedSessions: List<SessionWithSnapshots>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val entitlement by viewModel.entitlementState.collectAsState()
    val canExportCsv = remember(entitlement) {
        viewModel.featureAccessPolicy.check(ProFeature.CSV_EXPORT) is FeatureAccessResult.Allowed
    }
    val canSaveNewSession = remember(entitlement, savedSessions.size) {
        viewModel.featureAccessPolicy.checkSessionCapacity(savedSessions.size) is FeatureAccessResult.Allowed
    }
    var selectedTab by remember { mutableStateOf(0) } // 0: Overlays & Export, 1: Saved Sessions

    var showSaveDialog by remember { mutableStateOf(false) }
    var sessionToEditNotes by remember { mutableStateOf<SessionWithSnapshots?>(null) }
    var sessionToDelete by remember { mutableStateOf<SessionWithSnapshots?>(null) }
    var snapshotToRename by remember { mutableStateOf<RtaSnapshot?>(null) }
    var sessionToSelectSnapshot by remember { mutableStateOf<SessionWithSnapshots?>(null) }
    var overlayCapacityMessage by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14171E)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E3846))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "SESSION TOOLS & SNAPSHOTS",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Freeze overlays, export & room history",
                            color = Color(0xFF78909C),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Tabs: Overlays & Export vs Saved Sessions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0C0E12))
                    .border(1.dp, Color(0xFF242C38), RoundedCornerShape(6.dp))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val overlayLabel = if (frozenSnapshots.isNotEmpty()) "FREEZE OVERLAYS (${frozenSnapshots.size})" else "FREEZE OVERLAYS"
                val sessionsLabel = if (savedSessions.isNotEmpty()) "SAVED SESSIONS (${savedSessions.size})" else "SAVED SESSIONS"

                SessionTabButton(
                    title = overlayLabel,
                    isSelected = selectedTab == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedTab = 0 }
                )
                SessionTabButton(
                    title = sessionsLabel,
                    isSelected = selectedTab == 1,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedTab = 1 }
                )
            }

            // Tab Content
            when (selectedTab) {
                0 -> {
                    // --- TAB 0: FREEZE OVERLAYS & EXPORT ---
                    FreezeAndExportContent(
                        viewModel = viewModel,
                        surfaceViewRef = surfaceViewRef,
                        frozenSnapshots = frozenSnapshots,
                        onRenameSnapshot = { snapshotToRename = it }
                    )
                }
                1 -> {
                    // --- TAB 1: SAVED SESSIONS (ROOM DB) ---
                    SavedSessionsContent(
                        savedSessions = savedSessions,
                        canExportCsv = canExportCsv,
                        canSaveNewSession = canSaveNewSession,
                        onOpenSaveDialog = { showSaveDialog = true },
                        onOverlaySession = { session ->
                            if (session.snapshots.isEmpty()) {
                                Toast.makeText(context, "This session has no saved snapshots", Toast.LENGTH_SHORT).show()
                            } else {
                                when (val access = viewModel.featureAccessPolicy.checkOverlayCapacity(frozenSnapshots.size, session.snapshots.size)) {
                                    FeatureAccessResult.Allowed -> {
                                        if (viewModel.loadSessionSnapshotsToOverlay(session) is FeatureAccessResult.Allowed) {
                                            Toast.makeText(context, "Added ${session.snapshots.size} snapshots to the RTA overlay", Toast.LENGTH_SHORT).show()
                                            selectedTab = 0
                                        }
                                    }
                                    is FeatureAccessResult.Denied -> {
                                        if (viewModel.featureAccessPolicy.checkOverlayCapacity(frozenSnapshots.size, 1) is FeatureAccessResult.Allowed) {
                                            sessionToSelectSnapshot = session
                                        } else {
                                            overlayCapacityMessage = access.message
                                        }
                                    }
                                }
                            }
                        },
                        onExportSessionCsv = { session ->
                            viewModel.exportSessionCsv(context, session)
                        },
                        onLearnMore = { viewModel.requestProUpgrade(ProFeature.UNLIMITED_SESSIONS) },
                        onEditNotes = { sessionToEditNotes = it },
                        onDeleteSession = { sessionToDelete = it }
                    )
                }
            }
        }
    }

    // --- DIALOGS ---

    sessionToSelectSnapshot?.let { session ->
        SavedSnapshotSelectorDialog(
            session = session,
            canLoadSnapshot = viewModel.featureAccessPolicy.checkOverlayCapacity(frozenSnapshots.size, 1) is FeatureAccessResult.Allowed,
            onDismiss = { sessionToSelectSnapshot = null },
            onLearnMore = { viewModel.requestProUpgrade(ProFeature.UNLIMITED_OVERLAYS) },
            onSelect = { snapshotId ->
                if (viewModel.loadSessionSnapshotsToOverlay(session, listOf(snapshotId)) is FeatureAccessResult.Allowed) {
                    Toast.makeText(context, "Saved snapshot added to the RTA overlay", Toast.LENGTH_SHORT).show()
                    sessionToSelectSnapshot = null
                    selectedTab = 0
                }
            }
        )
    }

    overlayCapacityMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { overlayCapacityMessage = null },
            containerColor = Color(0xFF14171E),
            title = { Text("Overlay capacity reached", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(message, color = Color(0xFFFFD54F), fontSize = 12.sp)
                    Text("Loading adds to your current overlays. Clear or remove an existing overlay yourself, then try again. Your saved measurements remain intact.", color = Color(0xFFB0BEC5), fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedTab = 0; overlayCapacityMessage = null }) {
                    Text("OPEN OVERLAYS", color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    overlayCapacityMessage = null
                    viewModel.requestProUpgrade(ProFeature.UNLIMITED_OVERLAYS)
                }) { Text("PRO DETAILS", color = Color(0xFFFFD54F), fontFamily = FontFamily.Monospace) }
            }
        )
    }

    if (showSaveDialog) {
        SaveSessionDialog(
            uiState = uiState,
            frozenSnapshots = frozenSnapshots,
            onDismiss = { showSaveDialog = false },
            onConfirm = { title, notes, includeLive, includeFrozenIds ->
                viewModel.saveSession(
                    name = title,
                    notes = notes,
                    includeLive = includeLive,
                    includeFrozenIds = includeFrozenIds,
                    onComplete = {
                        Toast.makeText(context, "Session '$title' saved successfully", Toast.LENGTH_SHORT).show()
                    }
                )
                showSaveDialog = false
            }
        )
    }

    sessionToEditNotes?.let { sessionWithSnapshots ->
        EditNotesDialog(
            session = sessionWithSnapshots.session,
            onDismiss = { sessionToEditNotes = null },
            onConfirm = { updatedNotes ->
                viewModel.updateSessionNotes(sessionWithSnapshots.session.id, updatedNotes)
                sessionToEditNotes = null
                Toast.makeText(context, "Notes updated", Toast.LENGTH_SHORT).show()
            }
        )
    }

    sessionToDelete?.let { sessionWithSnapshots ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            containerColor = Color(0xFF14171E),
            title = {
                Text(
                    text = "Delete Session?",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete '${sessionWithSnapshots.session.name}' and its ${sessionWithSnapshots.snapshots.size} snapshots? This cannot be undone.",
                    color = Color(0xFFB0BEC5),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSession(sessionWithSnapshots.session.id)
                        sessionToDelete = null
                        Toast.makeText(context, "Session deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    )
                ) {
                    Text("DELETE", color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("CANCEL", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    snapshotToRename?.let { snap ->
        var newName by remember { mutableStateOf(snap.name) }
        AlertDialog(
            onDismissRequest = { snapshotToRename = null },
            containerColor = Color(0xFF14171E),
            title = {
                Text("Rename Snapshot", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF37474F)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameSnapshot(snap.id, newName)
                        snapshotToRename = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color.Black
                    )
                ) {
                    Text("RENAME", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { snapshotToRename = null }) {
                    Text("CANCEL", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

// =========================================================================
// TAB 0: FREEZE OVERLAYS & EXPORT CONTENT
// =========================================================================

@Composable
private fun FreezeAndExportContent(
    viewModel: AudioCaptureViewModel,
    surfaceViewRef: RtaSurfaceView?,
    frozenSnapshots: List<RtaSnapshot>,
    onRenameSnapshot: (RtaSnapshot) -> Unit
) {
    val context = LocalContext.current
    val entitlement by viewModel.entitlementState.collectAsState()
    val canExportCsv = remember(entitlement) {
        viewModel.featureAccessPolicy.check(ProFeature.CSV_EXPORT) is FeatureAccessResult.Allowed
    }
    val canFreeze = remember(entitlement, frozenSnapshots.size) {
        viewModel.featureAccessPolicy.checkOverlayCapacity(frozenSnapshots.size) is FeatureAccessResult.Allowed
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!canFreeze) {
            Text("Free overlay capacity reached. Remove or clear an existing overlay to freeze another; Pro adds unlimited overlays.",
                color = Color(0xFFFFD54F), fontSize = 10.sp, lineHeight = 15.sp)
        }
        // Quick Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Freeze Button
            Button(
                onClick = {
                    if (viewModel.freezeCurrentSnapshot() is FeatureAccessResult.Allowed) {
                        Toast.makeText(context, "Current spectrum frozen as overlay", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AcUnit,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (canFreeze) "FREEZE LIVE" else "FREEZE · PRO",
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (frozenSnapshots.isNotEmpty()) {
                OutlinedButton(
                    onClick = { viewModel.clearAllFrozenSnapshots() },
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF546E7A))
                ) {
                    Text(
                        text = "CLEAR ALL",
                        color = Color(0xFFFF8A80),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Export Row (Share Screen PNG + Export Live CSV)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (surfaceViewRef != null) {
                        RtaExportManager.captureAndShareSurfacePng(
                            surfaceView = surfaceViewRef,
                            context = context,
                            title = "Spectrum_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}",
                            onComplete = { success ->
                                if (!success) {
                                    Toast.makeText(context, "Failed capturing surface image", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    } else {
                        Toast.makeText(context, "SurfaceView not ready for capture", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF37474F))
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = Color(0xFF81D4FA),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "SHARE PNG",
                    color = Color(0xFF81D4FA),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            OutlinedButton(
                onClick = {
                    viewModel.exportLiveCsv(context)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF37474F))
            ) {
                Icon(
                    imageVector = if (canExportCsv) Icons.Default.FileDownload else Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFF81C784),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (canExportCsv) "EXPORT CSV" else "CSV · PRO",
                    color = Color(0xFF81C784),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (!canExportCsv) {
            ProFeatureNotice(ProFeature.CSV_EXPORT, onLearnMore = { viewModel.requestProUpgrade(ProFeature.CSV_EXPORT) })
        }

        // Frozen Snapshots Overlay List
        if (frozenSnapshots.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0E1217))
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No frozen overlays. Tap 'FREEZE LIVE' to freeze the current spectrum as a comparison trace.",
                    color = Color(0xFF546E7A),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                frozenSnapshots.forEach { snap ->
                    SnapshotOverlayItem(
                        snapshot = snap,
                        canExportCsv = canExportCsv,
                        onToggleVisibility = { viewModel.toggleSnapshotVisibility(snap.id) },
                        onExportCsv = {
                            viewModel.exportSnapshotCsv(context, snap)
                        },
                        onRename = { onRenameSnapshot(snap) },
                        onDelete = { viewModel.unfreezeSnapshot(snap.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapshotOverlayItem(
    snapshot: RtaSnapshot,
    canExportCsv: Boolean,
    onToggleVisibility: () -> Unit,
    onExportCsv: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val snapColor = Color(snapshot.colorHex)
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(snapshot.timestamp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0E1217))
            .border(1.dp, snapColor.copy(alpha = if (snapshot.isVisible) 0.6f else 0.2f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Color dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (snapshot.isVisible) snapColor else Color(0xFF37474F))
            )

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = snapshot.name,
                        color = if (snapshot.isVisible) Color.White else Color(0xFF78909C),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = timeStr,
                        color = Color(0xFF546E7A),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Compact Context Tags
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (snapshot.isSplMode) "SPL" else "dBFS",
                        color = Color(0xFF81D4FA),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    snapshot.calibrationName?.let {
                        Text(
                            text = "• $it",
                            color = Color(0xFFA5D6A7),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    snapshot.targetCurveName?.let {
                        Text(
                            text = "• $it",
                            color = Color(0xFFFFD54F),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Action icons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Visibility toggle
            IconButton(
                onClick = onToggleVisibility,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (snapshot.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "Toggle Visibility",
                    tint = if (snapshot.isVisible) Color(0xFF00E676) else Color(0xFF78909C),
                    modifier = Modifier.size(16.dp)
                )
            }

            // Export CSV
            IconButton(
                onClick = onExportCsv,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (canExportCsv) Icons.Default.FileDownload else Icons.Default.Lock,
                    contentDescription = if (canExportCsv) "Export CSV" else "CSV export · Pro",
                    tint = Color(0xFF81D4FA),
                    modifier = Modifier.size(16.dp)
                )
            }

            // Rename
            IconButton(
                onClick = onRename,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Rename",
                    tint = Color(0xFFFFB74D),
                    modifier = Modifier.size(14.dp)
                )
            }

            // Delete / Unfreeze
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Unfreeze",
                    tint = Color(0xFFFF8A80),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// =========================================================================
// TAB 1: SAVED SESSIONS (ROOM DATABASE) CONTENT
// =========================================================================

@Composable
private fun SavedSessionsContent(
    savedSessions: List<SessionWithSnapshots>,
    canExportCsv: Boolean,
    canSaveNewSession: Boolean,
    onOpenSaveDialog: () -> Unit,
    onOverlaySession: (SessionWithSnapshots) -> Unit,
    onExportSessionCsv: (SessionWithSnapshots) -> Unit,
    onEditNotes: (SessionWithSnapshots) -> Unit,
    onDeleteSession: (SessionWithSnapshots) -> Unit,
    onLearnMore: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!canSaveNewSession) {
            ProFeatureNotice(ProFeature.UNLIMITED_SESSIONS, onLearnMore)
            Text("Free includes one saved session. Existing measurements and notes remain available; a new save needs room or Pro access.",
                color = Color(0xFF90A4AE), fontSize = 10.sp, lineHeight = 15.sp)
        }
        // Save New Session Button
        Button(
            onClick = onOpenSaveDialog,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00E676),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Save,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (canSaveNewSession) "SAVE CURRENT TO SESSION HISTORY" else "SAVE NEW SESSION · PRO",
                color = Color.Black,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        if (savedSessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0E1217))
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No saved tuning sessions. Save a session to store measurements with full acoustic context (mic cal, targets, noise subtraction, notes).",
                    color = Color(0xFF546E7A),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                savedSessions.forEach { sessionWithSnapshots ->
                    SavedSessionItem(
                        sessionWithSnapshots = sessionWithSnapshots,
                        canExportCsv = canExportCsv,
                        onOverlay = { onOverlaySession(sessionWithSnapshots) },
                        onExportCsv = { onExportSessionCsv(sessionWithSnapshots) },
                        onEditNotes = { onEditNotes(sessionWithSnapshots) },
                        onDelete = { onDeleteSession(sessionWithSnapshots) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedSessionItem(
    sessionWithSnapshots: SessionWithSnapshots,
    canExportCsv: Boolean,
    onOverlay: () -> Unit,
    onExportCsv: () -> Unit,
    onEditNotes: () -> Unit,
    onDelete: () -> Unit
) {
    val session = sessionWithSnapshots.session
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(session.createdAt))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1217)),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263238))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: Title and Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = dateStr,
                    color = Color(0xFF78909C),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Acoustic Context Tags Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContextBadge(
                    label = if (session.isSplMode) String.format(Locale.US, "SPL %+.1fdB", session.splOffsetDb) else "dBFS",
                    color = Color(0xFF81D4FA)
                )
                session.activeCalibrationName?.let {
                    ContextBadge(label = "Cal: $it", color = Color(0xFFA5D6A7))
                }
                session.activeTargetCurveName?.let {
                    ContextBadge(label = "Target: $it", color = Color(0xFFFFD54F))
                }
                if (session.isNoiseSubtractionActive) {
                    ContextBadge(label = "Noise Sub: ON", color = Color(0xFFFF8A80))
                }
                ContextBadge(label = "${sessionWithSnapshots.snapshots.size} snaps", color = Color(0xFFE0E0E0))
            }

            // Notes Section
            if (session.notes.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF141A22))
                        .padding(6.dp)
                ) {
                    Text(
                        text = session.notes,
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOverlay,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "OVERLAY ON RTA",
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                OutlinedButton(
                    onClick = onExportCsv,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF37474F))
                ) {
                    Icon(
                        imageVector = if (canExportCsv) Icons.Default.FileDownload else Icons.Default.Lock,
                        contentDescription = if (canExportCsv) "Export session CSV" else "Session CSV export · Pro",
                        tint = Color(0xFF81C784),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (canExportCsv) "CSV" else "PRO", color = Color(0xFF81C784), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }

                OutlinedButton(
                    onClick = onEditNotes,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF37474F))
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(14.dp)
                    )
                }

                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF455A64))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = Color(0xFFFF8A80),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedSnapshotSelectorDialog(
    session: SessionWithSnapshots,
    canLoadSnapshot: Boolean,
    onDismiss: () -> Unit,
    onLearnMore: () -> Unit,
    onSelect: (Long) -> Unit
) {
    var selectedId by remember(session.session.id) { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14171E),
        title = { Text("Choose one saved snapshot", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Free includes one frozen overlay. Choose a snapshot from '${session.session.name}' to add to the RTA. All saved snapshots remain in your session.",
                    color = Color(0xFFB0BEC5), fontSize = 12.sp, lineHeight = 18.sp)
                if (!canLoadSnapshot) {
                    Text("Overlay capacity is now full. Cancel, then remove or clear an existing overlay yourself before loading this snapshot.",
                        color = Color(0xFFFFD54F), fontSize = 12.sp, lineHeight = 18.sp)
                }
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    session.snapshots.forEach { snapshot ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (snapshot.id == selectedId) Color(0xFF1B303D) else Color(0xFF0E1217))
                                .clickable { selectedId = snapshot.id }.padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = snapshot.id == selectedId,
                                onClick = { selectedId = snapshot.id },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00E5FF), unselectedColor = Color(0xFF78909C))
                            )
                            Column(Modifier.weight(1f)) {
                                Text(snapshot.name, color = Color.White, fontSize = 12.sp)
                                Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(snapshot.timestamp)),
                                    color = Color(0xFF78909C), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                TextButton(onClick = onLearnMore) {
                    Text("PRO · LOAD MULTIPLE OVERLAYS", color = Color(0xFFFFD54F), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedId?.let(onSelect) },
                enabled = selectedId != null && canLoadSnapshot,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
            ) { Text("LOAD SNAPSHOT", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace) }
        }
    )
}

@Composable
private fun ContextBadge(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun SessionTabButton(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) Color(0xFF00E5FF) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) Color.Black else Color(0xFF90A4AE),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =========================================================================
// DIALOGS: SAVE SESSION & EDIT NOTES
// =========================================================================

@Composable
private fun SaveSessionDialog(
    uiState: AudioCaptureUiState,
    frozenSnapshots: List<RtaSnapshot>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, notes: String, includeLive: Boolean, includeFrozenIds: List<String>) -> Unit
) {
    val defaultName = "Session ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}"
    var sessionName by remember { mutableStateOf(defaultName) }
    var notes by remember { mutableStateOf("") }
    var includeLive by remember { mutableStateOf(true) }
    val selectedFrozenIds = remember { mutableStateListOf<String>().apply { addAll(frozenSnapshots.map { it.id }) } }

    val cal = uiState.calibration

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14171E),
        title = {
            Text(
                text = "Save Tuning Session",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Session Name
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = { sessionName = it },
                    label = { Text("Session Name", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF37474F)
                    )
                )

                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (DSP setting, crossover, car model)", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF37474F)
                    )
                )

                // Acoustic Context Summary Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF0E1217))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "ACOUSTIC CONTEXT RECORDED:",
                        color = Color(0xFF00E5FF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "• Mic Cal: ${if (cal.isMicCurveActive) cal.micCurve?.name else "None"}",
                        color = Color(0xFFB0BEC5),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "• Target Curve: ${if (cal.isTargetCurveActive) cal.targetCurve?.name else "None"}",
                        color = Color(0xFFB0BEC5),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "• Mode: ${if (cal.splCalibration.isSplMode) String.format(Locale.US, "SPL (%+.1fdB)", cal.splCalibration.splOffsetDb) else "dBFS"}",
                        color = Color(0xFFB0BEC5),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "• Noise Subtraction: ${if (cal.isNoiseSubtractionActive) "Active (${if (cal.noiseFloor.isCaptured) "Saved" else "None"})" else "Off"}",
                        color = Color(0xFFB0BEC5),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Snapshots to include
                Text(
                    text = "INCLUDE IN SESSION:",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { includeLive = !includeLive }
                ) {
                    Checkbox(
                        checked = includeLive,
                        onCheckedChange = { includeLive = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00E676))
                    )
                    Text(
                        text = "Live Spectrum Capture",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                frozenSnapshots.forEach { snap ->
                    val isChecked = selectedFrozenIds.contains(snap.id)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            if (isChecked) selectedFrozenIds.remove(snap.id) else selectedFrozenIds.add(snap.id)
                        }
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = {
                                if (it) selectedFrozenIds.add(snap.id) else selectedFrozenIds.remove(snap.id)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Color(snap.colorHex))
                        )
                        Text(
                            text = snap.name,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(sessionName, notes, includeLive, selectedFrozenIds.toList())
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E676),
                    contentColor = Color.Black
                )
            ) {
                Text("SAVE SESSION", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)
            }
        }
    )
}

@Composable
private fun EditNotesDialog(
    session: com.rtatuner.app.core.database.TuningSessionEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var notes by remember { mutableStateOf(session.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14171E),
        title = {
            Text("Edit Notes: ${session.name}", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        },
        text = {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Tuning Notes", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace) },
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color(0xFF37474F)
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(notes) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                )
            ) {
                Text("SAVE", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color(0xFF90A4AE), fontFamily = FontFamily.Monospace)
            }
        }
    )
}
