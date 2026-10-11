package com.rtatuner.app

import androidx.room.Room
import com.rtatuner.app.core.database.RtaDatabase
import com.rtatuner.app.core.database.SessionRepository
import com.rtatuner.app.core.database.SessionSnapshotEntity
import com.rtatuner.app.core.database.TuningSessionEntity
import com.rtatuner.app.core.entitlement.FeatureAccessDeniedException
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.FeatureAccessResult
import com.rtatuner.app.core.entitlement.ProFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionSaveStaleCapacityTest {
    @Test
    fun databaseRejectsSaveFromAnAlreadyOpenDialogAfterAnotherSaveFillsCapacity() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RtaDatabase::class.java).build()
        try {
            val dao = database.tuningSessionDao()
            val policy = FeatureAccessPolicy()
            // The dialog was opened using a valid, empty UI list before another writer saved.
            assertEquals(FeatureAccessResult.Allowed, policy.checkSessionCapacity(0))
            val otherWriter = SessionRepository(dao)
            val firstId = otherWriter.saveSession(
                TuningSessionEntity(name = "Saved while dialog open", notes = "Retain these notes"),
                listOf(SessionSnapshotEntity(sessionId = 0, name = "Retained spectrum", bands31Db = FloatArray(31) { -30f }))
            )
            val original = otherWriter.getSessionWithSnapshots(firstId)

            val failure = runCatching {
                SessionRepository(dao).saveSession(TuningSessionEntity(name = "Stale dialog save"), emptyList())
            }.exceptionOrNull()

            assertTrue(failure is FeatureAccessDeniedException)
            assertEquals(ProFeature.UNLIMITED_SESSIONS, (failure as FeatureAccessDeniedException).denial.feature)
            assertEquals(1, dao.countSessions())
            assertEquals(original, otherWriter.getSessionWithSnapshots(firstId))
        } finally { database.close() }
    }

    @Test
    fun concurrentSavesFromAvailableUiStateStillPermitOnlyOneDatabaseCommit() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RtaDatabase::class.java).build()
        try {
            val dao = database.tuningSessionDao()
            val start = CompletableDeferred<Unit>()
            val attempts = (1..8).map { index ->
                async(Dispatchers.Default) {
                    // Every caller saw room in the same stale UI list before submitting.
                    assertEquals(FeatureAccessResult.Allowed, FeatureAccessPolicy().checkSessionCapacity(0))
                    start.await()
                    runCatching {
                        SessionRepository(dao).saveSession(TuningSessionEntity(name = "Concurrent $index"), emptyList())
                    }
                }
            }
            start.complete(Unit)
            val outcomes = attempts.awaitAll()

            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(7, outcomes.count { it.exceptionOrNull() is FeatureAccessDeniedException })
            assertEquals(1, dao.countSessions())
        } finally { database.close() }
    }
}
