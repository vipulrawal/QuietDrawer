package com.example.data.repository

import com.example.apps.InstalledAppInfo
import com.example.apps.InstalledAppsProvider
import com.example.data.local.AppFilterDao
import com.example.data.local.AppFilterEntity
import com.example.data.local.NotificationDao
import com.example.data.local.NotificationEntity
import kotlinx.coroutines.flow.Flow

class NotificationRepository(
    private val notificationDao: NotificationDao,
    private val appFilterDao: AppFilterDao,
    private val installedAppsProvider: InstalledAppsProvider,
) {
    val inboxNotifications: Flow<List<NotificationEntity>> = notificationDao.observeInboxNotifications()
    val archivedNotifications: Flow<List<NotificationEntity>> = notificationDao.observeArchivedNotifications()
    val inboxCount: Flow<Int> = notificationDao.observeInboxCount()
    val appFilters: Flow<List<AppFilterEntity>> = appFilterDao.observeAllFilters()

    suspend fun loadLaunchableApps(): List<InstalledAppInfo> {
        return installedAppsProvider.loadLaunchableApps()
    }

    suspend fun archiveSingle(id: Long): List<Long> {
        notificationDao.archiveById(id)
        return listOf(id)
    }

    suspend fun archiveSelected(ids: Collection<Long>): List<Long> {
        val idList = ids.toList()
        if (idList.isEmpty()) return emptyList()
        notificationDao.archiveByIds(idList)
        return idList
    }

    suspend fun archiveGroup(packageName: String): List<Long> {
        val activeIds = notificationDao.getActiveIdsForPackage(packageName)
        if (activeIds.isEmpty()) return emptyList()
        notificationDao.archiveByPackage(packageName)
        return activeIds
    }

    suspend fun restoreNotifications(ids: List<Long>) {
        if (ids.isEmpty()) return
        notificationDao.restoreByIds(ids)
    }

    suspend fun deletePermanently(id: Long) {
        notificationDao.deleteById(id)
    }

    suspend fun clearAllArchived(): List<String> {
        val keys = notificationDao.getArchivedSbnKeys()
        notificationDao.clearAllArchived()
        return keys
    }

    suspend fun setAppAllowedInStatusBar(
        packageName: String,
        appName: String,
        allowInStatusBar: Boolean,
    ) {
        appFilterDao.upsert(
            AppFilterEntity(
                packageName = packageName,
                appName = appName,
                allowInStatusBar = allowInStatusBar,
                lastSeenAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun allowEssentialApps(installedApps: List<InstalledAppInfo>) {
        val essentials = installedApps
            .filter { it.isEssentialCandidate }
            .map {
                AppFilterEntity(
                    packageName = it.packageName,
                    appName = it.appName,
                    allowInStatusBar = true,
                    lastSeenAt = System.currentTimeMillis(),
                )
            }
        if (essentials.isNotEmpty()) {
            appFilterDao.upsertAll(essentials)
        }
    }

    suspend fun filterAllApps() {
        appFilterDao.filterAllApps()
    }

    suspend fun getAllNotificationsSnapshot(): List<NotificationEntity> {
        return notificationDao.getAllNotificationsSnapshot()
    }

    suspend fun getAllFiltersSnapshot(): List<AppFilterEntity> {
        return appFilterDao.getAllFiltersSnapshot()
    }

    /**
     * Restores app filter rules and notifications idempotently without duplicating existing items.
     * Returns pair of (restoredFiltersCount, restoredNotificationsCount).
     */
    suspend fun restoreFromBackup(
        filters: List<AppFilterEntity>,
        notifications: List<NotificationEntity>,
    ): Pair<Int, Int> {
        if (filters.isNotEmpty()) {
            appFilterDao.upsertAll(filters)
        }

        val existingKeys = notificationDao.getAllNotificationsSnapshot()
            .map { Triple(it.packageName, it.postedAt, it.title) }
            .toHashSet()

        val newNotifications = notifications
            .filter { Triple(it.packageName, it.postedAt, it.title) !in existingKeys }
            .map { it.copy(id = 0L) }

        if (newNotifications.isNotEmpty()) {
            notificationDao.insertAll(newNotifications)
        }

        return filters.size to newNotifications.size
    }
}
