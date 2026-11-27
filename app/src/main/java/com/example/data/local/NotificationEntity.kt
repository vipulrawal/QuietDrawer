package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "filtered_notifications",
    indices = [
        Index(value = ["packageName"]),
        Index(value = ["isArchived", "postedAt"]),
        Index(value = ["sbnKey"]),
    ],
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sbnKey: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val bigText: String? = null,
    val subText: String? = null,
    val category: String? = null,
    val shortcutId: String? = null,
    val channelId: String? = null,
    val deepLinkUrl: String? = null,
    val personUri: String? = null,
    val postedAt: Long,
    val isArchived: Boolean = false,
    val archivedAt: Long? = null,
)
