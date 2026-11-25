package com.example.backup

import com.example.BuildConfig
import com.example.data.local.AppFilterEntity
import com.example.data.local.NotificationEntity
import com.example.data.model.ThemeMode
import com.example.data.prefs.UserPreferences
import org.json.JSONArray
import org.json.JSONObject

data class BackupPreferencesSnapshot(
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val filteringEnabled: Boolean,
    val skipOngoingNotifications: Boolean,
    val archiveOnClick: Boolean,
    val sortNewestFirst: Boolean,
    val updateFeedUrl: String,
)

data class QuietDrawerBackupData(
    val schemaVersion: Int,
    val exportedAtMs: Long,
    val appVersion: String,
    val preferences: BackupPreferencesSnapshot,
    val appFilters: List<AppFilterEntity>,
    val notifications: List<NotificationEntity>,
)

object BackupSerializer {
    const val CURRENT_SCHEMA_VERSION = 1
    const val MAX_BACKUP_BYTES = 10 * 1024 * 1024 // 10 MB safety limit
    private const val MAX_FILTER_ENTRIES = 2_000
    private const val MAX_NOTIFICATION_ENTRIES = 10_000

    fun encode(
        prefs: UserPreferences,
        filters: List<AppFilterEntity>,
        notifications: List<NotificationEntity>,
        exportedAtMs: Long = System.currentTimeMillis(),
    ): String {
        val root = JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            put("exportedAtMs", exportedAtMs)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("preferences", JSONObject().apply {
                put("themeMode", prefs.themeMode.storageKey)
                put("dynamicColor", prefs.dynamicColor)
                put("filteringEnabled", prefs.filteringEnabled)
                put("skipOngoingNotifications", prefs.skipOngoingNotifications)
                put("archiveOnClick", prefs.archiveOnClick)
                put("sortNewestFirst", prefs.sortNewestFirst)
                put("updateFeedUrl", prefs.updateFeedUrl)
            })
            put("appFilters", JSONArray().apply {
                filters.take(MAX_FILTER_ENTRIES).forEach { filter ->
                    put(JSONObject().apply {
                        put("packageName", filter.packageName)
                        put("appName", filter.appName)
                        put("allowInStatusBar", filter.allowInStatusBar)
                        put("lastSeenAt", filter.lastSeenAt)
                    })
                }
            })
            put("notifications", JSONArray().apply {
                notifications.take(MAX_NOTIFICATION_ENTRIES).forEach { notif ->
                    put(JSONObject().apply {
                        put("sbnKey", notif.sbnKey)
                        put("packageName", notif.packageName)
                        put("appName", notif.appName)
                        put("title", notif.title)
                        put("text", notif.text)
                        put("bigText", notif.bigText ?: JSONObject.NULL)
                        put("subText", notif.subText ?: JSONObject.NULL)
                        put("category", notif.category ?: JSONObject.NULL)
                        put("shortcutId", notif.shortcutId ?: JSONObject.NULL)
                        put("channelId", notif.channelId ?: JSONObject.NULL)
                        put("postedAt", notif.postedAt)
                        put("isArchived", notif.isArchived)
                        put("archivedAt", notif.archivedAt ?: JSONObject.NULL)
                    })
                }
            })
        }
        return root.toString()
    }

    fun decode(rawJson: String): QuietDrawerBackupData {
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES) {
            throw IllegalArgumentException("Backup file exceeds maximum supported size.")
        }

        val root = JSONObject(rawJson)
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw IllegalArgumentException("Unsupported QuietDrawer backup version ($schemaVersion).")
        }

        val exportedAtMs = root.optLong("exportedAtMs", System.currentTimeMillis())
        val appVersion = root.optString("appVersion", "1.0.0").take(32)

        val prefsObj = root.optJSONObject("preferences") ?: JSONObject()
        val preferences = BackupPreferencesSnapshot(
            themeMode = ThemeMode.fromKey(prefsObj.optString("themeMode", "system")),
            dynamicColor = prefsObj.optBoolean("dynamicColor", false),
            filteringEnabled = prefsObj.optBoolean("filteringEnabled", true),
            skipOngoingNotifications = prefsObj.optBoolean("skipOngoingNotifications", true),
            archiveOnClick = prefsObj.optBoolean("archiveOnClick", false),
            sortNewestFirst = prefsObj.optBoolean("sortNewestFirst", true),
            updateFeedUrl = prefsObj.optString("updateFeedUrl", "").trim().take(512),
        )

        val filtersArray = root.optJSONArray("appFilters") ?: JSONArray()
        val filterCount = minOf(filtersArray.length(), MAX_FILTER_ENTRIES)
        val filters = buildList(filterCount) {
            for (i in 0 until filterCount) {
                val obj = filtersArray.optJSONObject(i) ?: continue
                val pkg = obj.optString("packageName", "").trim()
                if (pkg.isBlank()) continue
                add(
                    AppFilterEntity(
                        packageName = pkg.take(256),
                        appName = obj.optString("appName", pkg).trim().ifBlank { pkg }.take(128),
                        allowInStatusBar = obj.optBoolean("allowInStatusBar", false),
                        lastSeenAt = obj.optLong("lastSeenAt", exportedAtMs),
                    )
                )
            }
        }

        val notifArray = root.optJSONArray("notifications") ?: JSONArray()
        val notifCount = minOf(notifArray.length(), MAX_NOTIFICATION_ENTRIES)
        val notifications = buildList(notifCount) {
            for (i in 0 until notifCount) {
                val obj = notifArray.optJSONObject(i) ?: continue
                val pkg = obj.optString("packageName", "").trim()
                if (pkg.isBlank()) continue
                val postedAt = obj.optLong("postedAt", exportedAtMs)
                add(
                    NotificationEntity(
                        id = 0L,
                        sbnKey = obj.optString("sbnKey", "$pkg:$postedAt").take(256),
                        packageName = pkg.take(256),
                        appName = obj.optString("appName", pkg).trim().ifBlank { pkg }.take(128),
                        title = obj.optString("title", "").take(512),
                        text = obj.optString("text", "").take(2_000),
                        bigText = obj.optNullableString("bigText")?.take(4_000),
                        subText = obj.optNullableString("subText")?.take(256),
                        category = obj.optNullableString("category")?.take(128),
                        shortcutId = obj.optNullableString("shortcutId")?.take(256),
                        channelId = obj.optNullableString("channelId")?.take(128),
                        postedAt = postedAt,
                        isArchived = obj.optBoolean("isArchived", false),
                        archivedAt = if (obj.isNull("archivedAt")) null else obj.optLong("archivedAt", postedAt),
                    )
                )
            }
        }

        return QuietDrawerBackupData(
            schemaVersion = schemaVersion,
            exportedAtMs = exportedAtMs,
            appVersion = appVersion,
            preferences = preferences,
            appFilters = filters,
            notifications = notifications,
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() }
    }
}
