package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NotificationEntity::class, AppFilterEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class QuietDrawerDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun appFilterDao(): AppFilterDao

    companion object {
        @Volatile
        private var INSTANCE: QuietDrawerDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE filtered_notifications ADD COLUMN deepLinkUrl TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE filtered_notifications ADD COLUMN personUri TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): QuietDrawerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    QuietDrawerDatabase::class.java,
                    "quietdrawer.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
