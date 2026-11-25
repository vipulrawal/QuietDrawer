package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_filters")
data class AppFilterEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    /**
     * When true, notifications from this app remain in Android's status bar.
     * When false (default), notifications are quietly routed into QuietDrawer's Inbox.
     */
    val allowInStatusBar: Boolean = false,
    val lastSeenAt: Long = System.currentTimeMillis(),
)
