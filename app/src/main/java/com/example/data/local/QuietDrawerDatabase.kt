package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotificationEntity::class, AppFilterEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class QuietDrawerDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun appFilterDao(): AppFilterDao

    companion object {
        @Volatile
        private var INSTANCE: QuietDrawerDatabase? = null

        fun getInstance(context: Context): QuietDrawerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    QuietDrawerDatabase::class.java,
                    "quietdrawer.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
