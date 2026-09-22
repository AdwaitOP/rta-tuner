package com.rtatuner.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.rtatuner.app.core.audio.AudioMath
import com.rtatuner.app.core.dsp.Iso31Bands
import java.util.Locale

/**
 * Type converter for persisting FloatArray of spectral band measurements as a comma-separated string.
 */
class BandsConverter {

    @TypeConverter
    fun fromFloatArray(array: FloatArray?): String? {
        if (array == null) return null
        val sb = StringBuilder(array.size * 8)
        for (i in array.indices) {
            if (i > 0) sb.append(',')
            val v = array[i]
            val safeV = if (v.isNaN() || v.isInfinite()) AudioMath.MIN_DB_FS else v
            sb.append(String.format(Locale.US, "%.2f", safeV))
        }
        return sb.toString()
    }

    @TypeConverter
    fun toFloatArray(data: String?): FloatArray? {
        if (data.isNullOrBlank()) return null
        return try {
            val tokens = data.split(',')
            val result = FloatArray(tokens.size)
            for (i in tokens.indices) {
                val f = tokens[i].trim().toFloatOrNull() ?: AudioMath.MIN_DB_FS
                result[i] = if (f.isNaN() || f.isInfinite()) AudioMath.MIN_DB_FS else f
            }
            result
        } catch (e: Exception) {
            FloatArray(Iso31Bands.NUM_BANDS) { AudioMath.MIN_DB_FS }
        }
    }
}

/**
 * Room entity representing a persistent tuning session (e.g. "Aura - Front Stage Tune").
 * Preserves the full acoustic context: calibration curve, target curve, SPL offset, window size,
 * and active noise floor snapshot.
 */
@Entity(tableName = "tuning_sessions")
@TypeConverters(BandsConverter::class)
data class TuningSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val activeCalibrationName: String? = null,
    val activeTargetCurveName: String? = null,
    val isSplMode: Boolean = false,
    val splOffsetDb: Float = 0.0f,
    val windowSize: Int = 2048,
    val isNoiseSubtractionActive: Boolean = false,
    val activeNoiseFloorBands: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TuningSessionEntity
        if (id != other.id) return false
        if (name != other.name) return false
        if (notes != other.notes) return false
        if (createdAt != other.createdAt) return false
        if (activeCalibrationName != other.activeCalibrationName) return false
        if (activeTargetCurveName != other.activeTargetCurveName) return false
        if (isSplMode != other.isSplMode) return false
        if (splOffsetDb != other.splOffsetDb) return false
        if (windowSize != other.windowSize) return false
        if (isNoiseSubtractionActive != other.isNoiseSubtractionActive) return false
        if (activeNoiseFloorBands != null) {
            if (other.activeNoiseFloorBands == null || !activeNoiseFloorBands.contentEquals(other.activeNoiseFloorBands)) return false
        } else if (other.activeNoiseFloorBands != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + notes.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (activeCalibrationName?.hashCode() ?: 0)
        result = 31 * result + (activeTargetCurveName?.hashCode() ?: 0)
        result = 31 * result + isSplMode.hashCode()
        result = 31 * result + splOffsetDb.hashCode()
        result = 31 * result + windowSize
        result = 31 * result + isNoiseSubtractionActive.hashCode()
        result = 31 * result + (activeNoiseFloorBands?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Room entity representing an individual snapshot belonging to a tuning session.
 */
@Entity(
    tableName = "session_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = TuningSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
@TypeConverters(BandsConverter::class)
data class SessionSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    val bands31Db: FloatArray,
    val colorHex: Long = 0xFF00E5FF,
    val isSplMode: Boolean = false,
    val splOffsetDb: Float = 0.0f,
    val calibrationName: String? = null,
    val targetCurveName: String? = null,
    val isNoiseSubtractionActive: Boolean = false,
    val activeNoiseFloorBands: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SessionSnapshotEntity
        if (id != other.id) return false
        if (sessionId != other.sessionId) return false
        if (name != other.name) return false
        if (timestamp != other.timestamp) return false
        if (!bands31Db.contentEquals(other.bands31Db)) return false
        if (colorHex != other.colorHex) return false
        if (isSplMode != other.isSplMode) return false
        if (splOffsetDb != other.splOffsetDb) return false
        if (calibrationName != other.calibrationName) return false
        if (targetCurveName != other.targetCurveName) return false
        if (isNoiseSubtractionActive != other.isNoiseSubtractionActive) return false
        if (activeNoiseFloorBands != null) {
            if (other.activeNoiseFloorBands == null || !activeNoiseFloorBands.contentEquals(other.activeNoiseFloorBands)) return false
        } else if (other.activeNoiseFloorBands != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + bands31Db.contentHashCode()
        result = 31 * result + colorHex.hashCode()
        result = 31 * result + isSplMode.hashCode()
        result = 31 * result + splOffsetDb.hashCode()
        result = 31 * result + (calibrationName?.hashCode() ?: 0)
        result = 31 * result + (targetCurveName?.hashCode() ?: 0)
        result = 31 * result + isNoiseSubtractionActive.hashCode()
        result = 31 * result + (activeNoiseFloorBands?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 1-to-Many relation model joining a session with its associated snapshots.
 */
data class SessionWithSnapshots(
    @Embedded
    val session: TuningSessionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val snapshots: List<SessionSnapshotEntity>
)
