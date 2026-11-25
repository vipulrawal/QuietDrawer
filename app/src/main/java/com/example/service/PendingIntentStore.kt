package com.example.service

import android.app.PendingIntent
import java.util.LinkedHashMap

/**
 * Bounded, thread-safe in-memory cache holding live [PendingIntent] references captured
 * from intercepted notifications before they are cleared from the Android status bar.
 *
 * Keeping intents indexed by both Room notification ID and Android sbnKey ensures clicking
 * a notification (in Inbox OR Archive) launches directly into the exact originating screen
 * whenever the OS token is still valid, before gracefully falling back to the app launcher.
 */
object PendingIntentStore {
    private const val MAX_ENTRIES = 500

    private val lock = Any()

    private val byNotificationId = object : LinkedHashMap<Long, PendingIntent>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PendingIntent>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    private val bySbnKey = object : LinkedHashMap<String, PendingIntent>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun put(notificationId: Long, sbnKey: String, pendingIntent: PendingIntent?) {
        if (pendingIntent == null) return
        synchronized(lock) {
            byNotificationId[notificationId] = pendingIntent
            bySbnKey[sbnKey] = pendingIntent
        }
    }

    fun get(notificationId: Long, sbnKey: String): PendingIntent? {
        synchronized(lock) {
            return byNotificationId[notificationId] ?: bySbnKey[sbnKey]
        }
    }

    fun hasLiveIntent(notificationId: Long, sbnKey: String): Boolean {
        return get(notificationId, sbnKey) != null
    }

    fun removeCanceled(notificationId: Long, sbnKey: String) {
        synchronized(lock) {
            byNotificationId.remove(notificationId)
            bySbnKey.remove(sbnKey)
        }
    }
}
