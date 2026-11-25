package com.example.service

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
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
 * 4-tier notification launcher designed so clicking a notification NEVER becomes a broken link:
 * 1. Live captured [PendingIntent] sent from the foreground Activity with BAL opt-in (API 34+).
 * 2. Conversation / dynamic shortcut launch via [LauncherApps] if shortcutId is present.
 * 3. Standard launcher Activity via [PackageManager.getLaunchIntentForPackage].
 * 4. Android App Info screen fallback for headless packages with no launcher Activity.
 */
object NotificationLauncher {
    private const val TAG = "NotificationLauncher"

    fun openNotification(context: Context, item: NotificationEntity): LaunchResult {
        // Tier 1: Live PendingIntent (preserves deep link to exact chat/email/screen)
        val pendingIntent = PendingIntentStore.get(item.id, item.sbnKey)
        if (pendingIntent != null) {
            val sent = trySendPendingIntent(context, pendingIntent, item)
            if (sent) {
                return LaunchResult.OpenedDeepLink
            }
        }

        // Tier 2: Shortcut deep-link (when available and permitted)
        if (!item.shortcutId.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val launchedShortcut = tryStartShortcut(context, item.packageName, item.shortcutId)
            if (launchedShortcut) {
                return LaunchResult.OpenedDeepLink
            }
        }

        // Tier 3: Originating app's primary launch intent (works forever, even after reboot or in Archive)
        if (tryLaunchAppPackage(context, item.packageName)) {
            return LaunchResult.OpenedApp
        }

        // Tier 4: App Info screen fallback so taps never silently fail
        if (tryOpenAppDetails(context, item.packageName)) {
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
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
                pendingIntent.send(context, 0, null, null, null, null, options)
            } else {
                pendingIntent.send()
            }
            true
        } catch (canceled: PendingIntent.CanceledException) {
            Log.d(TAG, "PendingIntent expired for ${item.packageName}; falling back to app launch.")
            PendingIntentStore.removeCanceled(item.id, item.sbnKey)
            false
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent failed for ${item.packageName}: ${e.message}")
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
}
