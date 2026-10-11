package com.rtatuner.app

import com.rtatuner.app.core.database.*
import com.rtatuner.app.core.entitlement.*
import com.rtatuner.app.core.session.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class FrozenSnapshotStoreTest {
    private fun snapshot(name: String = "Frozen") = RtaSnapshot(name = name, bands31Db = FloatArray(31) { -30f })
    private fun session(size: Int) = SessionWithSnapshots(
        TuningSessionEntity(id = 10, name = "Historical", notes = "Keep this", isSplMode = true),
        (1..size).map { index -> SessionSnapshotEntity(
            id = index.toLong(), sessionId = 10, name = "Capture $index", timestamp = index.toLong(),
            bands31Db = FloatArray(31) { -index.toFloat() }, isSplMode = true, splOffsetDb = 100f
        ) }
    )

    @Test fun `live freeze limit rejects additions without changing original measurements`() {
        val store = FrozenSnapshotStore()
        val original = snapshot()
        assertEquals(FeatureAccessResult.Allowed, store.add(original))
        store.toggleVisibility(original.id)
        assertTrue(store.add(snapshot("Extra")) is FeatureAccessResult.Denied)
        assertEquals(listOf(original.copy(isVisible = false)), store.snapshots.value)
        store.remove(original.id)
        assertEquals(FeatureAccessResult.Allowed, store.add(snapshot("Replacement")))
    }

    @Test fun `whole session load rejects multi snapshot import and supports explicit single selection`() {
        val store = FrozenSnapshotStore()
        val saved = session(3)
        assertTrue(store.loadSession(saved) is FeatureAccessResult.Denied)
        assertTrue(store.snapshots.value.isEmpty())
        assertEquals(FeatureAccessResult.Allowed, store.loadSession(saved, listOf(2)))
        val overlay = store.snapshots.value.single()
        assertEquals("Historical - Capture 2", overlay.name)
        assertTrue(overlay.isSplMode)
        assertArrayEquals(saved.snapshots[1].bands31Db, overlay.bands31Db, 0f)
        assertNotSame(saved.snapshots[1].bands31Db, overlay.bands31Db)
        assertTrue(store.loadSession(session(1)) is FeatureAccessResult.Denied)
        assertEquals(listOf(overlay), store.snapshots.value)
        assertEquals(3, saved.snapshots.size)
        assertEquals("Keep this", saved.session.notes)
    }

    @Test fun `Pro overlays remain intact after downgrade with further loads blocked`() {
        val repository = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val store = FrozenSnapshotStore(FeatureAccessPolicy(repository))
        assertEquals(FeatureAccessResult.Allowed, store.loadSession(session(100)))
        assertEquals(FeatureAccessResult.Allowed, store.add(snapshot()))
        val existing = store.snapshots.value
        repository.setTier(EntitlementTier.FREE)
        assertEquals(existing, store.snapshots.value)
        assertTrue(store.add(snapshot()) is FeatureAccessResult.Denied)
        assertTrue(store.loadSession(session(1), listOf(1)) is FeatureAccessResult.Denied)
        assertEquals(existing, store.snapshots.value)
        repository.setTier(EntitlementTier.PRO_LIFETIME)
        assertEquals(FeatureAccessResult.Allowed, store.loadSession(session(3)))
        assertEquals(104, store.snapshots.value.size)
    }

    @Test fun `concurrent overlay additions cannot bypass Free capacity`() {
        val store = FrozenSnapshotStore()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..20).map { Callable { store.add(snapshot()) } }).map { it.get() }
            assertEquals(1, results.count { it == FeatureAccessResult.Allowed })
            assertEquals(1, store.snapshots.value.size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `invalid saved snapshot selection never changes overlay state`() {
        val store = FrozenSnapshotStore()
        listOf(emptyList(), listOf(9L), listOf(1L, 1L)).forEach { ids ->
            assertThrows(IllegalArgumentException::class.java) { store.loadSession(session(2), ids) }
            assertTrue(store.snapshots.value.isEmpty())
        }
    }
}
