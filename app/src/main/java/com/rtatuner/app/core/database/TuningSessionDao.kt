package com.rtatuner.app.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Tuning Sessions and Snapshots.
 */
@Dao
interface TuningSessionDao {

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
