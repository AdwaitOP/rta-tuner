package com.rtatuner.app.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.rtatuner.app.core.entitlement.FeatureAccessPolicy
import com.rtatuner.app.core.entitlement.getOrThrow
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Tuning Sessions and Snapshots.
 */
@Dao
interface TuningSessionDao {

    @Query("SELECT COUNT(*) FROM tuning_sessions")
    suspend fun countSessions(): Int

    /**
     * Room serializes the capacity check and all inserts in one database transaction.
     * This also protects saves made through separate repository instances and rolls
     * the session back if any snapshot cannot be persisted.
     */
    @Transaction
    suspend fun saveSessionWithSnapshots(
        session: TuningSessionEntity,
        snapshots: List<SessionSnapshotEntity>,
        featureAccessPolicy: FeatureAccessPolicy
    ): Long {
        featureAccessPolicy.checkSessionCapacity(countSessions()).getOrThrow()
        val sessionId = insertSession(session)
        if (snapshots.isNotEmpty()) {
            insertSnapshots(snapshots.map { it.copy(sessionId = sessionId) })
        }
        return sessionId
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TuningSessionEntity): Long

    @Update
    suspend fun updateSession(session: TuningSessionEntity)

    @Delete
    suspend fun deleteSession(session: TuningSessionEntity)

    @Query("DELETE FROM tuning_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    @Query("UPDATE tuning_sessions SET notes = :notes WHERE id = :sessionId")
    suspend fun updateSessionNotes(sessionId: Long, notes: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: SessionSnapshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshots(snapshots: List<SessionSnapshotEntity>)

    @Delete
    suspend fun deleteSnapshot(snapshot: SessionSnapshotEntity)

    @Query("DELETE FROM session_snapshots WHERE id = :snapshotId")
    suspend fun deleteSnapshotById(snapshotId: Long)

    @Transaction
    @Query("SELECT * FROM tuning_sessions ORDER BY createdAt DESC")
    fun getAllSessionsWithSnapshots(): Flow<List<SessionWithSnapshots>>

    @Transaction
    @Query("SELECT * FROM tuning_sessions WHERE id = :sessionId")
    suspend fun getSessionWithSnapshots(sessionId: Long): SessionWithSnapshots?

    @Query("SELECT * FROM session_snapshots WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSnapshotsForSession(sessionId: Long): List<SessionSnapshotEntity>
}
