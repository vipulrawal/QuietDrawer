package com.example

import android.app.Application
import com.example.apps.InstalledAppsProvider
import com.example.backup.GoogleDriveBackupClient
import com.example.data.local.QuietDrawerDatabase
import com.example.data.prefs.UserPreferencesRepository
import com.example.data.repository.NotificationRepository
import com.example.update.UpdateChecker

class QuietDrawerApplication : Application() {

    val database: QuietDrawerDatabase by lazy {
        QuietDrawerDatabase.getInstance(this)
    }

    val preferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(this)
    }

    val installedAppsProvider: InstalledAppsProvider by lazy {
        InstalledAppsProvider(this)
    }

    val notificationRepository: NotificationRepository by lazy {
        NotificationRepository(
            notificationDao = database.notificationDao(),
            appFilterDao = database.appFilterDao(),
            installedAppsProvider = installedAppsProvider,
        )
    }

    val updateChecker: UpdateChecker by lazy {
        UpdateChecker()
    }

    val driveBackupClient: GoogleDriveBackupClient by lazy {
        GoogleDriveBackupClient()
    }
}
