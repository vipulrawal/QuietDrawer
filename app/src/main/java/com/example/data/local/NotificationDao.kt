package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Query("SELECT * FROM filtered_notifications WHERE isArchived = 0 ORDER BY postedAt DESC")
    fun observeInboxNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM filtered_notifications WHERE isArchived = 1 ORDER BY COALESCE(archivedAt, postedAt) DESC, postedAt DESC")
    fun observeArchivedNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM filtered_notifications WHERE isArchived = 0")
    fun observeInboxCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM filtered_notifications
        WHERE isArchived = 0
          AND sbnKey = :sbnKey
          AND title = :title
          AND text = :text
        ORDER BY postedAt DESC
        LIMIT 1
        """
    )
    suspend fun findRecentDuplicate(
        sbnKey: String,
        title: String,
        text: String,
    ): NotificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity): Long

    @Query("UPDATE filtered_notifications SET postedAt = :postedAt, bigText = :bigText, subText = :subText WHERE id = :id")
    suspend fun touchNotification(id: Long, postedAt: Long, bigText: String?, subText: String?)

    @Query("UPDATE filtered_notifications SET isArchived = 1, archivedAt = :archivedAt WHERE id = :id")
    suspend fun archiveById(id: Long, archivedAt: Long = System.currentTimeMillis())

    @Query("UPDATE filtered_notifications SET isArchived = 1, archivedAt = :archivedAt WHERE id IN (:ids)")
    suspend fun archiveByIds(ids: List<Long>, archivedAt: Long = System.currentTimeMillis())

    @Query("SELECT id FROM filtered_notifications WHERE isArchived = 0 AND packageName = :packageName")
    suspend fun getActiveIdsForPackage(packageName: String): List<Long>

    @Query("UPDATE filtered_notifications SET isArchived = 1, archivedAt = :archivedAt WHERE isArchived = 0 AND packageName = :packageName")
    suspend fun archiveByPackage(packageName: String, archivedAt: Long = System.currentTimeMillis())

    @Query("UPDATE filtered_notifications SET isArchived = 0, archivedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreByIds(ids: List<Long>)

    @Query("DELETE FROM filtered_notifications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM filtered_notifications WHERE isArchived = 1")
    suspend fun clearAllArchived()
}
