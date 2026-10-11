package com.rtatuner.app

import androidx.room.Room
import com.rtatuner.app.core.database.RtaDatabase
import com.rtatuner.app.core.database.SessionRepository
import com.rtatuner.app.core.database.SessionSnapshotEntity
import com.rtatuner.app.core.database.TuningSessionDao
import com.rtatuner.app.core.database.TuningSessionEntity
import com.rtatuner.app.core.entitlement.EntitlementTier
import com.rtatuner.app.core.entitlement.FakeEntitlementRepository
import com.rtatuner.app.core.entitlement.FeatureAccessDeniedException
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.ProFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Tests the generated Room transactions, including concurrent repository instances. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionRepositoryEntitlementTest {
    private lateinit var database: RtaDatabase
    private lateinit var dao: TuningSessionDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), RtaDatabase::class.java
        ).build()
        dao = database.tuningSessionDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun defaultFreeAllowsOneSaveAndRejectsBeforeAnyRowsChange() = runBlocking {
        val repository = SessionRepository(dao)
        val id = repository.saveSession(session("First"), listOf(snapshot("First spectrum")))
        val original = repository.getSessionWithSnapshots(id)

        val rejected = runCatching {
            repository.saveSession(session("Rejected"), listOf(snapshot("Rejected spectrum")))
        }.exceptionOrNull()

        assertTrue(rejected is FeatureAccessDeniedException)
        assertEquals(ProFeature.UNLIMITED_SESSIONS, (rejected as FeatureAccessDeniedException).denial.feature)
        assertEquals(1, dao.countSessions())
        assertEquals(original, repository.getSessionWithSnapshots(id))
        assertEquals(id, original!!.snapshots.single().sessionId)
    }

    @Test
    fun concurrentFreeSavesAcrossRepositoriesCannotBypassTheLimit() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val attempts = (1..24).map { index ->
            async(Dispatchers.Default) {
                val repository = SessionRepository(dao)
                start.await()
                runCatching {
                    repository.saveSession(session("Session $index"), listOf(snapshot("Spectrum $index")))
                }
            }
        }
        start.complete(Unit)
        val outcomes = attempts.awaitAll()

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(23, outcomes.count { it.exceptionOrNull() is FeatureAccessDeniedException })
        assertEquals(1, dao.countSessions())
        val acceptedId = outcomes.single { it.isSuccess }.getOrThrow()
        assertEquals(1, dao.getSnapshotsForSession(acceptedId).size)
    }

    @Test
    fun proAllowsConcurrentUnlimitedSavesWithAllSnapshotsLinked() = runBlocking {
        val policy = FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME))
        val ids = (1..12).map { index ->
            async(Dispatchers.Default) {
                SessionRepository(dao, featureAccessPolicy = policy).saveSession(
                    session("Pro $index"), listOf(snapshot("Before $index"), snapshot("After $index"))
                )
            }
        }.awaitAll()

        assertEquals(12, ids.toSet().size)
        assertEquals(12, dao.countSessions())
        ids.forEach { id ->
            val saved = dao.getSessionWithSnapshots(id)!!
            assertEquals(2, saved.snapshots.size)
            assertTrue(saved.snapshots.all { it.sessionId == id })
        }
    }

    @Test
    fun downgradeRetainsHistoricalSessionsAndNotesAndDeletionRemainAvailable() = runBlocking {
        val entitlements = FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME)
        val repository = SessionRepository(dao, featureAccessPolicy = FeatureAccessPolicy(entitlements))
        val firstId = repository.saveSession(session("Historical 1"), listOf(snapshot("Before")))
        val secondId = repository.saveSession(session("Historical 2"), listOf(snapshot("After")))
        val historicalFirst = repository.getSessionWithSnapshots(firstId)
        val historicalSecond = repository.getSessionWithSnapshots(secondId)

        entitlements.setTier(EntitlementTier.FREE)
        assertEquals(historicalFirst, repository.getSessionWithSnapshots(firstId))
        assertEquals(historicalSecond, repository.getSessionWithSnapshots(secondId))
        assertTrue(runCatching { repository.saveSession(session("Blocked"), emptyList()) }
            .exceptionOrNull() is FeatureAccessDeniedException)
        repository.updateSessionNotes(firstId, "Still editable while Free")
        assertEquals("Still editable while Free", repository.getSessionWithSnapshots(firstId)!!.session.notes)
        assertEquals(historicalFirst!!.snapshots, repository.getSessionWithSnapshots(firstId)!!.snapshots)
        repository.deleteSession(secondId)
        assertNull(repository.getSessionWithSnapshots(secondId))
        assertTrue(dao.getSnapshotsForSession(secondId).isEmpty())
        // One retained session still fills Free capacity; removing it permits a new save.
        assertTrue(runCatching { repository.saveSession(session("Still blocked"), emptyList()) }
            .exceptionOrNull() is FeatureAccessDeniedException)
        repository.deleteSession(firstId)
        assertNotNull(repository.getSessionWithSnapshots(repository.saveSession(session("New"), emptyList())))
    }

    @Test
    fun freeToProTransitionUsesCurrentOwnershipAtPersistenceBoundary() = runBlocking {
        val entitlements = FakeEntitlementRepository()
        val repository = SessionRepository(dao, featureAccessPolicy = FeatureAccessPolicy(entitlements))
        repository.saveSession(session("Free"), emptyList())
        assertTrue(runCatching { repository.saveSession(session("Blocked"), emptyList()) }
            .exceptionOrNull() is FeatureAccessDeniedException)

        entitlements.setTier(EntitlementTier.PRO_LIFETIME)
        repository.saveSession(session("Now allowed"), listOf(snapshot("Pro spectrum")))
        assertEquals(2, dao.countSessions())
    }

    @Test
    fun snapshotInsertionFailureRollsBackNewSessionAndRetainsEarlierData() = runBlocking {
        val repository = SessionRepository(
            dao, featureAccessPolicy = FeatureAccessPolicy(FakeEntitlementRepository(EntitlementTier.PRO_LIFETIME))
        )
        val existingId = repository.saveSession(session("Existing"), listOf(snapshot("Retained")))
        val existing = repository.getSessionWithSnapshots(existingId)
        // A database failure after the session insert proves both tables share one transaction.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_snapshot BEFORE INSERT ON session_snapshots " +
                "BEGIN SELECT RAISE(ABORT, 'simulated snapshot write failure'); END"
        )

        assertTrue(runCatching {
            repository.saveSession(session("Must roll back"), listOf(snapshot("Failure")))
        }.isFailure)

        assertEquals(1, dao.countSessions())
        assertEquals(existing, repository.getSessionWithSnapshots(existingId))
    }

    private fun session(name: String) = TuningSessionEntity(name = name)

    private fun snapshot(name: String) = SessionSnapshotEntity(
        sessionId = 0, name = name, bands31Db = FloatArray(31) { -30f }
    )
}
