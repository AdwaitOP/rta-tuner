package com.rtatuner.app.core.session

import com.rtatuner.app.core.database.SessionWithSnapshots
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All overlay additions share one atomic capacity check, including saved-session imports. */
class FrozenSnapshotStore(private val policy: FeatureAccessPolicy = FeatureAccessPolicy()) {
    private val lock = Any()
    private val mutableSnapshots = MutableStateFlow<List<RtaSnapshot>>(emptyList())
    val snapshots = mutableSnapshots.asStateFlow()

    fun add(snapshot: RtaSnapshot): FeatureAccessResult = addAll(listOf(snapshot))

    private fun addAll(additions: List<RtaSnapshot>): FeatureAccessResult = synchronized(lock) {
        val result = policy.checkOverlayCapacity(mutableSnapshots.value.size, additions.size)
        if (result == FeatureAccessResult.Allowed) {
            mutableSnapshots.value = mutableSnapshots.value + additions
        }
        result
    }

    fun loadSession(
        session: SessionWithSnapshots,
        selectedSnapshotIds: List<Long>? = null
    ): FeatureAccessResult {
        val selected = if (selectedSnapshotIds == null) session.snapshots else {
            require(selectedSnapshotIds.isNotEmpty()) { "Select a saved snapshot to load." }
            require(selectedSnapshotIds.distinct().size == selectedSnapshotIds.size &&
                selectedSnapshotIds.all { id -> session.snapshots.any { it.id == id } }) {
                "Selected snapshot does not belong to this session."
            }
            session.snapshots.filter { it.id in selectedSnapshotIds }
        }
        val loaded = selected.map { entity ->
            RtaSnapshot(
                name = "${session.session.name} - ${entity.name}",
                timestamp = entity.timestamp,
                bands31Db = entity.bands31Db.clone(),
                isSplMode = entity.isSplMode,
                splOffsetDb = entity.splOffsetDb,
                calibrationName = entity.calibrationName,
                targetCurveName = entity.targetCurveName,
                windowSize = session.session.windowSize,
                isNoiseSubtractionActive = entity.isNoiseSubtractionActive,
                activeNoiseFloorBands = entity.activeNoiseFloorBands?.clone(),
                colorHex = entity.colorHex,
                isVisible = true
            )
        }
        return addAll(loaded)
    }

    fun remove(id: String) = synchronized(lock) {
        mutableSnapshots.value = mutableSnapshots.value.filterNot { it.id == id }
    }

    fun clear() = synchronized(lock) { mutableSnapshots.value = emptyList() }

    fun toggleVisibility(id: String) = synchronized(lock) {
        mutableSnapshots.value = mutableSnapshots.value.map {
            if (it.id == id) it.copy(isVisible = !it.isVisible) else it
        }
    }

    fun rename(id: String, name: String) = synchronized(lock) {
        if (name.isNotBlank()) mutableSnapshots.value = mutableSnapshots.value.map {
            if (it.id == id) it.copy(name = name) else it
        }
    }
}
