package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "quietdrawer_prefs"
)

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val filteringEnabled: Boolean = true,
    val skipOngoingNotifications: Boolean = true,
    val archiveOnClick: Boolean = false,
    val sortNewestFirst: Boolean = true,
    val updateFeedUrl: String = "",
    val signedInEmail: String? = null,
    val signedInDisplayName: String? = null,
    val lastDriveBackupAt: Long? = null,
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val FILTERING_ENABLED = booleanPreferencesKey("filtering_enabled")
        val SKIP_ONGOING = booleanPreferencesKey("skip_ongoing_notifications")
        val ARCHIVE_ON_CLICK = booleanPreferencesKey("archive_on_click")
        val SORT_NEWEST_FIRST = booleanPreferencesKey("sort_newest_first")
        val UPDATE_FEED_URL = stringPreferencesKey("update_feed_url")
        val SIGNED_IN_EMAIL = stringPreferencesKey("signed_in_email")
        val SIGNED_IN_DISPLAY_NAME = stringPreferencesKey("signed_in_display_name")
        val LAST_DRIVE_BACKUP_AT = longPreferencesKey("last_drive_backup_at")
    }

    val preferencesFlow: Flow<UserPreferences> = context.userPreferencesDataStore.data.map { prefs ->
        UserPreferences(
            themeMode = ThemeMode.fromKey(prefs[Keys.THEME_MODE]),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            filteringEnabled = prefs[Keys.FILTERING_ENABLED] ?: true,
            skipOngoingNotifications = prefs[Keys.SKIP_ONGOING] ?: true,
            archiveOnClick = prefs[Keys.ARCHIVE_ON_CLICK] ?: false,
            sortNewestFirst = prefs[Keys.SORT_NEWEST_FIRST] ?: true,
            updateFeedUrl = prefs[Keys.UPDATE_FEED_URL] ?: "",
            signedInEmail = prefs[Keys.SIGNED_IN_EMAIL]?.takeIf { it.isNotBlank() },
            signedInDisplayName = prefs[Keys.SIGNED_IN_DISPLAY_NAME]?.takeIf { it.isNotBlank() },
            lastDriveBackupAt = prefs[Keys.LAST_DRIVE_BACKUP_AT]?.takeIf { it > 0L },
        )
    }

    suspend fun getSnapshot(): UserPreferences = preferencesFlow.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.userPreferencesDataStore.edit { it[Keys.THEME_MODE] = mode.storageKey }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.userPreferencesDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setFilteringEnabled(enabled: Boolean) {
        context.userPreferencesDataStore.edit { it[Keys.FILTERING_ENABLED] = enabled }
    }

    suspend fun setSkipOngoingNotifications(skip: Boolean) {
        context.userPreferencesDataStore.edit { it[Keys.SKIP_ONGOING] = skip }
    }

    suspend fun setArchiveOnClick(enabled: Boolean) {
        context.userPreferencesDataStore.edit { it[Keys.ARCHIVE_ON_CLICK] = enabled }
    }

    suspend fun setSortNewestFirst(newestFirst: Boolean) {
        context.userPreferencesDataStore.edit { it[Keys.SORT_NEWEST_FIRST] = newestFirst }
    }

    suspend fun setUpdateFeedUrl(url: String) {
        context.userPreferencesDataStore.edit { it[Keys.UPDATE_FEED_URL] = url.trim() }
    }

    suspend fun setSignedInAccount(email: String?, displayName: String?) {
        context.userPreferencesDataStore.edit { prefs ->
            if (email.isNullOrBlank()) {
                prefs.remove(Keys.SIGNED_IN_EMAIL)
                prefs.remove(Keys.SIGNED_IN_DISPLAY_NAME)
            } else {
                prefs[Keys.SIGNED_IN_EMAIL] = email.trim()
                if (displayName.isNullOrBlank()) {
                    prefs.remove(Keys.SIGNED_IN_DISPLAY_NAME)
                } else {
                    prefs[Keys.SIGNED_IN_DISPLAY_NAME] = displayName.trim()
                }
            }
        }
    }

    suspend fun setLastDriveBackupAt(timestampMs: Long?) {
        context.userPreferencesDataStore.edit { prefs ->
            if (timestampMs == null || timestampMs <= 0L) {
                prefs.remove(Keys.LAST_DRIVE_BACKUP_AT)
            } else {
                prefs[Keys.LAST_DRIVE_BACKUP_AT] = timestampMs
            }
        }
    }

    suspend fun applyRestoredPreferences(
        themeMode: ThemeMode,
        dynamicColor: Boolean,
        filteringEnabled: Boolean,
        skipOngoingNotifications: Boolean,
        archiveOnClick: Boolean,
        sortNewestFirst: Boolean,
        updateFeedUrl: String,
    ) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = themeMode.storageKey
            prefs[Keys.DYNAMIC_COLOR] = dynamicColor
            prefs[Keys.FILTERING_ENABLED] = filteringEnabled
            prefs[Keys.SKIP_ONGOING] = skipOngoingNotifications
            prefs[Keys.ARCHIVE_ON_CLICK] = archiveOnClick
            prefs[Keys.SORT_NEWEST_FIRST] = sortNewestFirst
            if (updateFeedUrl.isNotBlank()) {
                prefs[Keys.UPDATE_FEED_URL] = updateFeedUrl.trim()
            }
        }
    }
}
