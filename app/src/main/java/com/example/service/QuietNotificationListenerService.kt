package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.QuietDrawerApplication
import com.example.R
import com.example.data.local.AppFilterEntity
import com.example.data.local.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Core Android [NotificationListenerService] inspired by Before Launcher.
 *
 * Notifications from apps marked "In Status Bar" remain untouched in Android's status bar.
 * Notifications from all other apps are saved to QuietDrawer's local Inbox, their live
 * [PendingIntent]s (contentIntent + bubbleIntent) are cached in [PendingIntentStore], and
 * on Android 8+ they are hidden via [snoozeNotification] instead of [cancelNotification].
 *
 * Snoozing hides notifications from the status bar WITHOUT firing the originating app's
 * deleteIntent (which would otherwise revoke the PendingIntent) and keeps the live
 * [StatusBarNotification] inside Android's NotificationManagerService so QuietDrawer can
 * re-hydrate deep links automatically after Battery Saver, launcher switches, or process restarts.
 */
class QuietNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        activeInstance = this
        Log.i(TAG, "QuietNotificationListenerService connected")
        serviceScope.launch {
            rehydrateLiveIntentsInternal()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
        if (activeInstance === this) {
            activeInstance = null
        }
        Log.i(TAG, "QuietNotificationListenerService disconnected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance === this) {
            activeInstance = null
        }
        _isConnected.value = false
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        serviceScope.launch {
            processNotification(sbn)
        }
    }

    private suspend fun processNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras

        // Ignore Android's container group summary notifications to avoid duplicate rows
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        val isTestNotification = extras.getBoolean(EXTRA_IS_TEST_NOTIFICATION, false)
        if (sbn.packageName == packageName && !isTestNotification) {
            return
        }

        val app = application as? QuietDrawerApplication ?: return
        val prefs = app.preferencesRepository.getSnapshot()
        if (!prefs.filteringEnabled && !isTestNotification) {
            return
        }

        // Respect ongoing/media/call/alarm alerts if enabled
        if (prefs.skipOngoingNotifications && !isTestNotification && shouldBypassAsOngoingOrCritical(sbn, notification)) {
            return
        }

        val appName = if (isTestNotification) {
            getString(R.string.app_name)
        } else {
            app.installedAppsProvider.resolveAppName(sbn.packageName)
        }

        // Record package in app_filters so even non-launcher packages become configurable in Filters
        if (!isTestNotification) {
            app.database.appFilterDao().insertIfAbsent(
                AppFilterEntity(
                    packageName = sbn.packageName,
                    appName = appName,
                    allowInStatusBar = false,
                    lastSeenAt = System.currentTimeMillis(),
                )
            )
            val allowInStatusBar = app.database.appFilterDao().isAllowedInStatusBar(sbn.packageName) ?: false
            if (allowInStatusBar) {
                return
            }
        }

        val extracted = extractNotificationContent(notification, appName) ?: return
        val contentIntent = notification.contentIntent
        val bubbleIntent = extractBubbleIntent(notification)
        val deepLinkUrl = DeepLinkExtractor.extractDeepLinkUrl(extras)
        val personUri = DeepLinkExtractor.extractPersonUri(extras)

        val shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.shortcutId
        } else {
            null
        }
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.channelId
        } else {
            null
        }

        val dao = app.database.notificationDao()
        val existingAny = dao.findLatestBySbnKeyAndContent(
            sbnKey = sbn.key,
            title = extracted.title,
            text = extracted.text,
        )

        // Handle 24h snooze expiry re-post of an already-archived notification cleanly:
        // cancel it in Android so it never reappears in the status bar or loops forever.
        if (existingAny != null && existingAny.isArchived) {
            val archivedCutoff = existingAny.archivedAt ?: existingAny.postedAt
            if (sbn.postTime <= archivedCutoff) {
                cancelSafely(sbn.key)
                return
            }
        }

        val existingActive = existingAny?.takeIf { !it.isArchived }
        val savedId = if (existingActive != null) {
            // If Android un-snoozed an unchanged Inbox notification after 24h, silently re-snooze
            // and refresh its PendingIntent without bumping its timestamp in Inbox.
            val isUnchangedSnoozeRepost = sbn.postTime <= existingActive.postedAt &&
                (extracted.bigText == null || extracted.bigText == existingActive.bigText) &&
                (extracted.subText == null || extracted.subText == existingActive.subText)

            if (!isUnchangedSnoozeRepost) {
                dao.touchNotification(
                    id = existingActive.id,
                    postedAt = maxOf(sbn.postTime, existingActive.postedAt),
                    bigText = extracted.bigText ?: existingActive.bigText,
                    subText = extracted.subText ?: existingActive.subText,
                    deepLinkUrl = deepLinkUrl,
                    personUri = personUri,
                )
            }
            existingActive.id
        } else {
            dao.insert(
                NotificationEntity(
                    sbnKey = sbn.key,
                    packageName = sbn.packageName,
                    appName = appName,
                    title = extracted.title,
                    text = extracted.text,
                    bigText = extracted.bigText,
                    subText = extracted.subText,
                    category = notification.category,
                    shortcutId = shortcutId,
                    channelId = channelId,
                    deepLinkUrl = deepLinkUrl,
                    personUri = personUri,
                    postedAt = sbn.postTime,
                    isArchived = false,
                    archivedAt = null,
                )
            )
        }

        // Capture live PendingIntents BEFORE hiding from status bar so deep-links stay intact
        PendingIntentStore.put(
            notificationId = savedId,
            sbnKey = sbn.key,
            packageName = sbn.packageName,
            title = extracted.title,
            text = extracted.text,
            contentIntent = contentIntent,
            bubbleIntent = bubbleIntent,
        )

        hideFromStatusBar(sbn.key)
    }

    /**
     * Hides the notification from Android's status bar using [snoozeNotification] on API 26+
     * so the originating app's deleteIntent is NOT triggered and the live [PendingIntent]
     * stays preserved inside Android's system NotificationManagerService across process kills.
     */
    private fun hideFromStatusBar(sbnKey: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                snoozeNotification(sbnKey, SNOOZE_DURATION_MS)
                return
            } catch (e: Exception) {
                Log.w(TAG, "snoozeNotification failed for $sbnKey, falling back to cancel: ${e.message}")
            }
        }
        cancelSafely(sbnKey)
    }

    private fun cancelSafely(sbnKey: String) {
        try {
            cancelNotification(sbnKey)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to cancel notification $sbnKey: ${e.message}")
        }
    }

    /**
     * Restores live [PendingIntent]s from Android's snoozed and active notification pools into
     * [PendingIntentStore]. Recovers deep links automatically after Battery Saver, Doze,
     * launcher switches, or low-memory process kills.
     */
    private suspend fun rehydrateLiveIntentsInternal() {
        val app = application as? QuietDrawerApplication ?: return
        val liveNotifications = collectLiveStatusBarNotifications()
        if (liveNotifications.isEmpty()) return

        val recentRows = try {
            app.database.notificationDao().getRecentNotificationsForRehydration(limit = 400)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load notifications for rehydration: ${e.message}")
            emptyList()
        }

        val rowsBySbnKey = recentRows.groupBy { it.sbnKey }
        val rowsByContentKey = recentRows.groupBy {
            PendingIntentStore.contentKey(it.packageName, it.title, it.text)
        }

        for (sbn in liveNotifications) {
            val notification = sbn.notification ?: continue
            val contentIntent = notification.contentIntent
            val bubbleIntent = extractBubbleIntent(notification)
            if (contentIntent == null && bubbleIntent == null) continue

            val appName = app.installedAppsProvider.resolveAppName(sbn.packageName)
            val extracted = extractNotificationContent(notification, appName)
            val title = extracted?.title.orEmpty()
            val text = extracted?.text.orEmpty()

            val matchedRows = rowsBySbnKey[sbn.key]
                ?: if (extracted != null) {
                    rowsByContentKey[PendingIntentStore.contentKey(sbn.packageName, title, text)]
                } else {
                    null
                }

            if (!matchedRows.isNullOrEmpty()) {
                for (row in matchedRows) {
                    PendingIntentStore.put(
                        notificationId = row.id,
                        sbnKey = row.sbnKey,
                        packageName = row.packageName,
                        title = row.title,
                        text = row.text,
                        contentIntent = contentIntent,
                        bubbleIntent = bubbleIntent,
                    )
                }
            } else {
                PendingIntentStore.put(
                    notificationId = null,
                    sbnKey = sbn.key,
                    packageName = sbn.packageName,
                    title = title,
                    text = text,
                    contentIntent = contentIntent,
                    bubbleIntent = bubbleIntent,
                )
            }
        }
    }

    private fun collectLiveStatusBarNotifications(): List<StatusBarNotification> {
        val result = ArrayList<StatusBarNotification>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                snoozedNotifications?.let { result.addAll(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read snoozedNotifications: ${e.message}")
            }
        }
        try {
            activeNotifications?.let { result.addAll(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read activeNotifications: ${e.message}")
        }
        return result
    }

    private fun findLiveIntentsForItemInternal(item: NotificationEntity): List<PendingIntent> {
        val liveList = collectLiveStatusBarNotifications()
        if (liveList.isEmpty()) return emptyList()

        val found = ArrayList<PendingIntent>(2)
        fun addUnique(pi: PendingIntent?) {
            if (pi != null && found.none { it == pi }) found.add(pi)
        }

        // Priority 1: Exact sbnKey match
        val exactMatch = liveList.firstOrNull { it.key == item.sbnKey }
        if (exactMatch?.notification != null) {
            val contentIntent = exactMatch.notification.contentIntent
            val bubbleIntent = extractBubbleIntent(exactMatch.notification)
            PendingIntentStore.put(
                notificationId = item.id,
                sbnKey = item.sbnKey,
                packageName = item.packageName,
                title = item.title,
                text = item.text,
                contentIntent = contentIntent,
                bubbleIntent = bubbleIntent,
            )
            addUnique(contentIntent)
            addUnique(bubbleIntent)
        }

        // Priority 2: Content fingerprint match within same package (if Android rotated sbnKey)
        if (found.isEmpty()) {
            val contentMatch = liveList.firstOrNull { sbn ->
                if (sbn.packageName != item.packageName) return@firstOrNull false
                val notif = sbn.notification ?: return@firstOrNull false
                val extracted = extractNotificationContent(notif, item.appName) ?: return@firstOrNull false
                extracted.title == item.title && extracted.text == item.text
            }
            if (contentMatch?.notification != null) {
                val contentIntent = contentMatch.notification.contentIntent
                val bubbleIntent = extractBubbleIntent(contentMatch.notification)
                PendingIntentStore.put(
                    notificationId = item.id,
                    sbnKey = item.sbnKey,
                    packageName = item.packageName,
                    title = item.title,
                    text = item.text,
                    contentIntent = contentIntent,
                    bubbleIntent = bubbleIntent,
                )
                addUnique(contentIntent)
                addUnique(bubbleIntent)
            }
        }

        return found
    }

    private fun shouldBypassAsOngoingOrCritical(
        sbn: StatusBarNotification,
        notification: Notification,
    ): Boolean {
        if (sbn.isOngoing || !sbn.isClearable) return true
        val flags = notification.flags
        if ((flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return true
        if ((flags and Notification.FLAG_NO_CLEAR) != 0) return true
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return true

        val category = notification.category
        if (category == Notification.CATEGORY_CALL ||
            category == Notification.CATEGORY_ALARM ||
            category == Notification.CATEGORY_NAVIGATION ||
            category == Notification.CATEGORY_STOPWATCH ||
            category == Notification.CATEGORY_TRANSPORT
        ) {
            return true
        }

        if (notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            return true
        }
        return false
    }

    private data class ExtractedContent(
        val title: String,
        val text: String,
        val bigText: String?,
        val subText: String?,
    )

    private fun extractNotificationContent(
        notification: Notification,
        appName: String,
    ): ExtractedContent? {
        val extras = notification.extras ?: return null
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && it != rawText }
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }

        if (rawTitle.isBlank() && rawText.isBlank() && bigText.isNullOrBlank()) {
            return null
        }

        return ExtractedContent(
            title = rawTitle.ifBlank { appName },
            text = rawText.ifBlank { bigText.orEmpty() },
            bigText = bigText,
            subText = subText,
        )
    }

    private fun extractBubbleIntent(notification: Notification): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                notification.bubbleMetadata?.intent
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "QuietListenerService"
        private const val TEST_CHANNEL_ID = "quietdrawer_test_channel"
        private const val EXTRA_IS_TEST_NOTIFICATION = "com.example.EXTRA_IS_TEST_NOTIFICATION"
        private const val SNOOZE_DURATION_MS = 24L * 60L * 60L * 1000L // 24 hours

        @Volatile
        private var activeInstance: QuietNotificationListenerService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun isNotificationAccessGranted(context: Context): Boolean {
            return NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)
        }

        /**
         * Ensures the notification listener is bound (recovering automatically after launcher
         * switches or Battery Saver) and refreshes live [PendingIntent]s from Android's snoozed pool.
         */
        fun ensureConnectedAndRehydrate(context: Context) {
            if (!isNotificationAccessGranted(context)) return
            val instance = activeInstance
            if (instance == null || !_isConnected.value) {
                try {
                    val component = ComponentName(context, QuietNotificationListenerService::class.java)
                    requestRebind(component)
                } catch (e: Exception) {
                    Log.w(TAG, "requestRebind failed: ${e.message}")
                }
            } else {
                instance.serviceScope.launch {
                    instance.rehydrateLiveIntentsInternal()
                }
            }
        }

        /**
         * Synchronous tap-time lookup against Android's live snoozed & active notification pools
         * in case [PendingIntentStore] was cleared by a process restart right before the user tapped.
         */
        fun findLiveIntentsOnDemand(item: NotificationEntity): List<PendingIntent> {
            val instance = activeInstance ?: return emptyList()
            return try {
                instance.findLiveIntentsForItemInternal(item)
            } catch (e: Exception) {
                Log.w(TAG, "On-demand live intent lookup failed: ${e.message}")
                emptyList()
            }
        }

        /**
         * Cleanly cancels snoozed notifications in Android when the user permanently deletes
         * archived items or clears archive history.
         */
        fun cancelSnoozedKeys(sbnKeys: Collection<String>) {
            val instance = activeInstance ?: return
            val uniqueKeys = sbnKeys.filter { it.isNotBlank() }.distinct()
            if (uniqueKeys.isEmpty()) return
            instance.serviceScope.launch {
                for (key in uniqueKeys) {
                    instance.cancelSafely(key)
                }
            }
        }

        /**
         * Sweeps any currently visible status-bar notifications from filtered apps into the Inbox.
         */
        fun sweepActiveStatusBar() {
            val instance = activeInstance ?: return
            instance.serviceScope.launch {
                val active = try {
                    instance.activeNotifications?.toList().orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
                for (sbn in active) {
                    instance.processNotification(sbn)
                }
            }
        }

        /**
         * Posts a real Android notification on a dedicated test channel so the user can verify
         * that QuietDrawer intercepts, groups, and launches notifications end-to-end on device.
         */
        fun postVerificationNotification(context: Context): Boolean {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    TEST_CHANNEL_ID,
                    "Filter Verification",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Used when you test QuietDrawer notification filtering"
                }
                manager.createNotificationChannel(channel)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                9001,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notif = NotificationCompat.Builder(context, TEST_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_quietdrawer)
                .setContentTitle("Filter check passed")
                .setContentText("QuietDrawer caught this alert and kept your status bar calm. Tap to open or swipe left/right to archive.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .apply {
                    extras.putBoolean(EXTRA_IS_TEST_NOTIFICATION, true)
                }
                .build()

            return try {
                NotificationManagerCompat.from(context)
                    .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
