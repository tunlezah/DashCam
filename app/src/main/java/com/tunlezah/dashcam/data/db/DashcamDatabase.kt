package com.tunlezah.dashcam.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SegmentEntity::class, EventEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DashcamDatabase : RoomDatabase() {

    abstract fun segmentDao(): SegmentDao
    abstract fun eventDao(): EventDao

    companion object {
        fun build(context: Context): DashcamDatabase =
            Room.databaseBuilder(context, DashcamDatabase::class.java, "dashcam.db")
                // The filesystem is the source of truth (recovery rebuilds the
                // index); losing the DB on a bad migration must never lose video.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
