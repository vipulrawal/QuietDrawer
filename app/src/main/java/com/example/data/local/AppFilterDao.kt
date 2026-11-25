package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppFilterDao {

    @Query("SELECT * FROM app_filters ORDER BY appName COLLATE NOCASE ASC")
    fun observeAllFilters(): Flow<List<AppFilterEntity>>

    @Query("SELECT allowInStatusBar FROM app_filters WHERE packageName = :packageName LIMIT 1")
    suspend fun isAllowedInStatusBar(packageName: String): Boolean?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(filter: AppFilterEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(filter: AppFilterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(filters: List<AppFilterEntity>)

    @Query("UPDATE app_filters SET allowInStatusBar = 0")
    suspend fun filterAllApps()

    @Query("SELECT * FROM app_filters ORDER BY appName COLLATE NOCASE ASC")
    suspend fun getAllFiltersSnapshot(): List<AppFilterEntity>
}
