package com.example.service

import android.app.PendingIntent
import java.util.LinkedHashMap

/**
 * Bounded, thread-safe in-memory cache holding live [PendingIntent] references captured
 * from intercepted notifications (both primary contentIntent and backup bubbleMetadata intent).
 *
 * Indexed by Room notification ID, Android sbnKey, and a stable content fingerprint
 * `(packageName, title, text)` so re-hydration after process restarts or sbnKey rotations
 * always reconnects notifications to their exact originating screen.
 */
object PendingIntentStore {
    private const val MAX_ENTRIES = 600

    data class CapturedIntents(
        val contentIntent: PendingIntent? = null,
        val bubbleIntent: PendingIntent? = null,
    ) {
        val isEmpty: Boolean
            get() = contentIntent == null && bubbleIntent == null

        fun mergeWith(other: CapturedIntents): CapturedIntents {
            return CapturedIntents(
                contentIntent = contentIntent ?: other.contentIntent,
                bubbleIntent = bubbleIntent ?: other.bubbleIntent,
            )
        }

        fun without(target: PendingIntent): CapturedIntents {
            return CapturedIntents(
                contentIntent = if (contentIntent == target) null else contentIntent,
                bubbleIntent = if (bubbleIntent == target) null else bubbleIntent,
            )
        }
    }

    private val lock = Any()

    private val byNotificationId = object : LinkedHashMap<Long, CapturedIntents>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CapturedIntents>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    private val bySbnKey = object : LinkedHashMap<String, CapturedIntents>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CapturedIntents>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    private val byContentKey = object : LinkedHashMap<String, CapturedIntents>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CapturedIntents>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun contentKey(packageName: String, title: String, text: String): String {
        return "${packageName.trim()}|${title.trim()}|${text.trim()}"
    }

    fun put(
        notificationId: Long? = null,
        sbnKey: String,
        packageName: String,
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        bubbleIntent: PendingIntent? = null,
    ) {
        val captured = CapturedIntents(
            contentIntent = contentIntent,
            bubbleIntent = bubbleIntent,
        )
        if (captured.isEmpty) return

        val cKey = contentKey(packageName, title, text)
        synchronized(lock) {
            if (notificationId != null && notificationId > 0L) {
                byNotificationId[notificationId] = byNotificationId[notificationId]?.let { existing ->
                    captured.mergeWith(existing)
                } ?: captured
            }
            if (sbnKey.isNotBlank()) {
                bySbnKey[sbnKey] = bySbnKey[sbnKey]?.let { existing ->
                    captured.mergeWith(existing)
                } ?: captured
            }
            byContentKey[cKey] = byContentKey[cKey]?.let { existing ->
                captured.mergeWith(existing)
            } ?: captured
        }
    }

    /**
     * Returns deduplicated, priority-ordered live [PendingIntent] candidates:
     * 1. Exact Room notificationId (contentIntent, then bubbleIntent)
     * 2. Matching Android sbnKey (contentIntent, then bubbleIntent)
     * 3. Matching `(packageName, title, text)` fingerprint (contentIntent, then bubbleIntent)
     */
    fun getCandidates(
        notificationId: Long,
        sbnKey: String,
        packageName: String,
        title: String,
        text: String,
    ): List<PendingIntent> {
        val cKey = contentKey(packageName, title, text)
        val result = ArrayList<PendingIntent>(4)
        fun addUnique(pi: PendingIntent?) {
            if (pi != null && result.none { it == pi }) {
                result.add(pi)
            }
        }
        synchronized(lock) {
            val idEntry = byNotificationId[notificationId]
            val keyEntry = bySbnKey[sbnKey]
            val contentEntry = byContentKey[cKey]

            addUnique(idEntry?.contentIntent)
            addUnique(keyEntry?.contentIntent)
            addUnique(contentEntry?.contentIntent)
            addUnique(idEntry?.bubbleIntent)
            addUnique(keyEntry?.bubbleIntent)
            addUnique(contentEntry?.bubbleIntent)
        }
        return result
    }

    /**
     * Removes ONLY the specific canceled [PendingIntent] token without disturbing newer
     * sibling notifications that share the same sbnKey or content key.
     */
    fun removeCanceled(
        notificationId: Long,
        sbnKey: String,
        packageName: String,
        title: String,
        text: String,
        canceledIntent: PendingIntent,
    ) {
        val cKey = contentKey(packageName, title, text)
        synchronized(lock) {
            byNotificationId[notificationId]?.let { entry ->
                val cleaned = entry.without(canceledIntent)
                if (cleaned.isEmpty) byNotificationId.remove(notificationId) else byNotificationId[notificationId] = cleaned
            }
            bySbnKey[sbnKey]?.let { entry ->
                val cleaned = entry.without(canceledIntent)
                if (cleaned.isEmpty) bySbnKey.remove(sbnKey) else bySbnKey[sbnKey] = cleaned
            }
            byContentKey[cKey]?.let { entry ->
                val cleaned = entry.without(canceledIntent)
                if (cleaned.isEmpty) byContentKey.remove(cKey) else byContentKey[cKey] = cleaned
            }
        }
    }
}
