package com.rtatuner.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [TuningSessionEntity::class, SessionSnapshotEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(BandsConverter::class)
abstract class RtaDatabase : RoomDatabase() {

    abstract fun tuningSessionDao(): TuningSessionDao

    companion object {
        private const val DATABASE_NAME = "rta_tuner.db"

        @Volatile
        private var INSTANCE: RtaDatabase? = null

        fun getInstance(context: Context): RtaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RtaDatabase::class.java,
                    DATABASE_NAME
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
