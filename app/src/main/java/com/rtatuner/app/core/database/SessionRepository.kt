package com.rtatuner.app.core.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Repository mediating access to persisted tuning sessions and snapshots.
 */
class SessionRepository(
    private val sessionDao: TuningSessionDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    val allSessionsWithSnapshots: Flow<List<SessionWithSnapshots>> =
        sessionDao.getAllSessionsWithSnapshots().flowOn(ioDispatcher)

    suspend fun saveSession(
        session: TuningSessionEntity,
        snapshots: List<SessionSnapshotEntity>
    ): Long = withContext(ioDispatcher) {
        val sessionId = sessionDao.insertSession(session)
        if (snapshots.isNotEmpty()) {
            val linkedSnapshots = snapshots.map { it.copy(sessionId = sessionId) }
            sessionDao.insertSnapshots(linkedSnapshots)
        }
        sessionId
    }

    suspend fun updateSessionNotes(sessionId: Long, notes: String) = withContext(ioDispatcher) {
        sessionDao.updateSessionNotes(sessionId, notes)
    }

    suspend fun deleteSession(sessionId: Long) = withContext(ioDispatcher) {
        sessionDao.deleteSessionById(sessionId)
    }

    suspend fun deleteSnapshot(snapshotId: Long) = withContext(ioDispatcher) {
        sessionDao.deleteSnapshotById(snapshotId)
    }

    suspend fun addSnapshotToSession(
        sessionId: Long,
        snapshot: SessionSnapshotEntity
    ): Long = withContext(ioDispatcher) {
        sessionDao.insertSnapshot(snapshot.copy(sessionId = sessionId))
    }

    suspend fun getSessionWithSnapshots(sessionId: Long): SessionWithSnapshots? =
        withContext(ioDispatcher) {
            sessionDao.getSessionWithSnapshots(sessionId)
        }
}
