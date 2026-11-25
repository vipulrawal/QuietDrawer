package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * [PendingIntent] is preserved in [PendingIntentStore], and they are quietly dismissed
 * from the status bar.
 */
class QuietNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        activeInstance = this
        Log.i(TAG, "QuietNotificationListenerService connected")
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

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && it != text }
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }

        // Skip completely empty silent system pings
        if (title.isBlank() && text.isBlank() && bigText.isNullOrBlank()) {
            return
        }

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
        val existing = dao.findRecentDuplicate(
            sbnKey = sbn.key,
            title = title.ifBlank { appName },
            text = text.ifBlank { bigText.orEmpty() },
        )

        val savedId = if (existing != null) {
            dao.touchNotification(
                id = existing.id,
                postedAt = sbn.postTime,
                bigText = bigText ?: existing.bigText,
                subText = subText ?: existing.subText,
            )
            existing.id
        } else {
            dao.insert(
                NotificationEntity(
                    sbnKey = sbn.key,
                    packageName = sbn.packageName,
                    appName = appName,
                    title = title.ifBlank { appName },
                    text = text.ifBlank { bigText.orEmpty() },
                    bigText = bigText,
                    subText = subText,
                    category = notification.category,
                    shortcutId = shortcutId,
                    channelId = channelId,
                    postedAt = sbn.postTime,
                    isArchived = false,
                    archivedAt = null,
                )
            )
        }

        // Capture live PendingIntent BEFORE dismissing from status bar so deep-links stay intact
        PendingIntentStore.put(
            notificationId = savedId,
            sbnKey = sbn.key,
            pendingIntent = notification.contentIntent,
        )

        try {
            cancelNotification(sbn.key)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to cancel notification ${sbn.key}: ${e.message}")
        }
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

    companion object {
        private const val TAG = "QuietListenerService"
        private const val TEST_CHANNEL_ID = "quietdrawer_test_channel"
        private const val EXTRA_IS_TEST_NOTIFICATION = "com.example.EXTRA_IS_TEST_NOTIFICATION"

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
