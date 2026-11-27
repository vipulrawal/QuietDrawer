package com.example.service

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.example.data.local.NotificationEntity

sealed interface LaunchResult {
    data object OpenedDeepLink : LaunchResult
    data object OpenedApp : LaunchResult
    data object OpenedAppSettings : LaunchResult
    data class Failed(val appName: String) : LaunchResult
}

/**
 * 6-tier resilient notification launcher designed to maximize deep-link survival across
 * status-bar hiding, Battery Saver, launcher switches, process kills, and phone reboots:
 *
 * 1. Cached live [PendingIntent] candidates (contentIntent + bubbleIntent, by ID / sbnKey / content)
 *    dispatched from the foreground [Activity] context with API 34-36 BAL opt-in.
 * 2. On-demand live re-hydration from Android's snoozed & active notification pools right at tap time.
 * 3. Persisted `https://` [NotificationEntity.deepLinkUrl] scoped to the originating app package
 *    (survives even full phone reboots when OS PendingIntent binders are destroyed).
 * 4. Conversation/dynamic shortcut via [LauncherApps] (when permitted) or persisted [NotificationEntity.personUri]
 *    scoped to the originating app package.
 * 5. Standard launcher Activity via [PackageManager.getLaunchIntentForPackage].
 * 6. Android App Info screen fallback for headless packages with no launcher Activity.
 */
object NotificationLauncher {
    private const val TAG = "NotificationLauncher"

    fun openNotification(context: Context, item: NotificationEntity): LaunchResult {
        val launchContext = context.findActivity() ?: context

        // Tier 1: Cached live PendingIntent candidates (exact screen in native app)
        val cachedCandidates = PendingIntentStore.getCandidates(
            notificationId = item.id,
            sbnKey = item.sbnKey,
            packageName = item.packageName,
            title = item.title,
            text = item.text,
        )
        for (candidate in cachedCandidates) {
            if (trySendPendingIntent(launchContext, candidate, item)) {
                return LaunchResult.OpenedDeepLink
            }
        }

        // Tier 2: On-demand live re-hydration from Android's snoozed/active notifications
        val liveCandidates = QuietNotificationListenerService.findLiveIntentsOnDemand(item)
            .filter { live -> cachedCandidates.none { it == live } }
        for (candidate in liveCandidates) {
            if (trySendPendingIntent(launchContext, candidate, item)) {
                return LaunchResult.OpenedDeepLink
            }
        }

        // Tier 3: Persisted https:// deep link handled natively by the originating app (survives reboot)
        if (DeepLinkExtractor.isValidDeepLinkUrl(item.deepLinkUrl)) {
            if (tryLaunchScopedUri(launchContext, item.packageName, item.deepLinkUrl!!)) {
                return LaunchResult.OpenedDeepLink
            }
        }

        // Tier 4a: Conversation/dynamic shortcut (when available and permitted)
        if (!item.shortcutId.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            if (tryStartShortcut(launchContext, item.packageName, item.shortcutId)) {
                return LaunchResult.OpenedDeepLink
            }
        }

        // Tier 4b: Persisted safe contact URI (mailto:/tel:/smsto:) handled by originating app
        if (DeepLinkExtractor.isValidPersonUri(item.personUri)) {
            if (tryLaunchScopedUri(launchContext, item.packageName, item.personUri!!)) {
                return LaunchResult.OpenedDeepLink
            }
        }

        // Tier 5: Originating app's primary launch intent (works forever, even after reboot or in Archive)
        if (tryLaunchAppPackage(launchContext, item.packageName)) {
            return LaunchResult.OpenedApp
        }

        // Tier 6: App Info screen fallback so taps never silently fail
        if (tryOpenAppDetails(launchContext, item.packageName)) {
            return LaunchResult.OpenedAppSettings
        }

        return LaunchResult.Failed(item.appName)
    }

    private fun trySendPendingIntent(
        context: Context,
        pendingIntent: PendingIntent,
        item: NotificationEntity,
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    if (Build.VERSION.SDK_INT >= 36) {
                        setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }
                }.toBundle()
                pendingIntent.send(context, 0, null, null, null, null, options)
            } else {
                pendingIntent.send(context, 0, null)
            }
            true
        } catch (canceled: PendingIntent.CanceledException) {
            Log.d(TAG, "PendingIntent expired for ${item.packageName}; trying next fallback.")
            PendingIntentStore.removeCanceled(
                notificationId = item.id,
                sbnKey = item.sbnKey,
                packageName = item.packageName,
                title = item.title,
                text = item.text,
                canceledIntent = pendingIntent,
            )
            false
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent failed for ${item.packageName}: ${e.message}")
            false
        }
    }

    private fun tryLaunchScopedUri(
        context: Context,
        packageName: String,
        uriString: String,
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return false
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Scoped URI launch unavailable for $packageName: ${e.message}")
            false
        }
    }

    private fun tryStartShortcut(
        context: Context,
        packageName: String,
        shortcutId: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false
        return try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return false
            if (!launcherApps.hasShortcutHostPermission()) return false
            launcherApps.startShortcut(packageName, shortcutId, null, null, Process.myUserHandle())
            true
        } catch (e: Exception) {
            Log.d(TAG, "Shortcut launch unavailable for $packageName/$shortcutId: ${e.message}")
            false
        }
    }

    private fun tryLaunchAppPackage(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
                ?: pm.getLeanbackLaunchIntentForPackage(packageName)
                ?: return false
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch app $packageName: ${e.message}")
            false
        }
    }

    private fun tryOpenAppDetails(context: Context, packageName: String): Boolean {
        return try {
            val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open settings for $packageName: ${e.message}")
            false
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
