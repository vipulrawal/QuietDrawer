package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.QuietDrawerApplication
import com.example.apps.InstalledAppInfo
import com.example.data.local.NotificationEntity
import com.example.data.model.AppFilterTab
import com.example.data.model.ArchiveGroupMode
import com.example.data.model.ThemeMode
import com.example.data.prefs.UserPreferences
import com.example.service.QuietNotificationListenerService
import com.example.ui.util.TimeFormatter
import com.example.update.UpdateCheckResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationAppGroup(
    val packageName: String,
    val appName: String,
    val notifications: List<NotificationEntity>,
    val latestPostedAt: Long,
    val earliestPostedAt: Long,
)

data class ArchiveSection(
    val key: String,
    val title: String,
    val packageName: String? = null,
    val notifications: List<NotificationEntity>,
)

data class AppFilterItem(
    val packageName: String,
    val appName: String,
    val allowInStatusBar: Boolean,
    val isEssentialCandidate: Boolean,
)

sealed interface UiFeedbackEvent {
    data class ArchivedWithUndo(
        val message: String,
        val archivedIds: List<Long>,
    ) : UiFeedbackEvent

    data class Message(val text: String) : UiFeedbackEvent
}

class QuietDrawerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as QuietDrawerApplication
    private val repository = app.notificationRepository
    private val prefsRepo = app.preferencesRepository
    private val updateChecker = app.updateChecker

    val installedAppsProvider = app.installedAppsProvider

    private val _events = MutableSharedFlow<UiFeedbackEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiFeedbackEvent> = _events.asSharedFlow()

    val userPreferences: StateFlow<UserPreferences> = prefsRepo.preferencesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences(),
    )

    private val _isNotificationAccessGranted = MutableStateFlow(
        QuietNotificationListenerService.isNotificationAccessGranted(application)
    )
    val isNotificationAccessGranted: StateFlow<Boolean> = _isNotificationAccessGranted.asStateFlow()

    val isListenerConnected: StateFlow<Boolean> = QuietNotificationListenerService.isConnected

    val inboxCount: StateFlow<Int> = repository.inboxCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )

    // Inbox search, selection, and collapse state
    private val _inboxSearchQuery = MutableStateFlow("")
    val inboxSearchQuery: StateFlow<String> = _inboxSearchQuery.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _collapsedPackages = MutableStateFlow<Set<String>>(emptySet())
    val collapsedPackages: StateFlow<Set<String>> = _collapsedPackages.asStateFlow()

    val inboxGroups: StateFlow<List<NotificationAppGroup>> = combine(
        repository.inboxNotifications,
        _inboxSearchQuery,
        userPreferences,
    ) { items, query, prefs ->
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            items
        } else {
            items.filter { item ->
                item.appName.contains(trimmed, ignoreCase = true) ||
                    item.title.contains(trimmed, ignoreCase = true) ||
                    item.text.contains(trimmed, ignoreCase = true) ||
                    (item.bigText?.contains(trimmed, ignoreCase = true) == true)
            }
        }

        val grouped = filtered.groupBy { it.packageName }.map { (pkg, list) ->
            val sortedItems = if (prefs.sortNewestFirst) {
                list.sortedByDescending { it.postedAt }
            } else {
                list.sortedBy { it.postedAt }
            }
            NotificationAppGroup(
                packageName = pkg,
                appName = sortedItems.firstOrNull()?.appName ?: pkg,
                notifications = sortedItems,
                latestPostedAt = list.maxOf { it.postedAt },
                earliestPostedAt = list.minOf { it.postedAt },
            )
        }

        if (prefs.sortNewestFirst) {
            grouped.sortedByDescending { it.latestPostedAt }
        } else {
            grouped.sortedBy { it.earliestPostedAt }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    // Archive state
    private val _archiveSearchQuery = MutableStateFlow("")
    val archiveSearchQuery: StateFlow<String> = _archiveSearchQuery.asStateFlow()

    private val _archiveGroupMode = MutableStateFlow(ArchiveGroupMode.BY_DATE)
    val archiveGroupMode: StateFlow<ArchiveGroupMode> = _archiveGroupMode.asStateFlow()

    private val _collapsedArchiveSections = MutableStateFlow<Set<String>>(emptySet())
    val collapsedArchiveSections: StateFlow<Set<String>> = _collapsedArchiveSections.asStateFlow()

    val archiveSections: StateFlow<List<ArchiveSection>> = combine(
        repository.archivedNotifications,
        _archiveSearchQuery,
        _archiveGroupMode,
    ) { items, query, mode ->
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            items
        } else {
            items.filter { item ->
                item.appName.contains(trimmed, ignoreCase = true) ||
                    item.title.contains(trimmed, ignoreCase = true) ||
                    item.text.contains(trimmed, ignoreCase = true) ||
                    (item.bigText?.contains(trimmed, ignoreCase = true) == true)
            }
        }

        when (mode) {
            ArchiveGroupMode.BY_DATE -> {
                filtered
                    .sortedByDescending { it.archivedAt ?: it.postedAt }
                    .groupBy { TimeFormatter.formatDateSectionHeader(it.archivedAt ?: it.postedAt) }
                    .map { (dateLabel, list) ->
                        ArchiveSection(
                            key = "date_$dateLabel",
                            title = dateLabel,
                            packageName = null,
                            notifications = list,
                        )
                    }
            }
            ArchiveGroupMode.BY_APP -> {
                filtered
                    .groupBy { it.packageName }
                    .map { (pkg, list) ->
                        val sorted = list.sortedByDescending { it.postedAt }
                        ArchiveSection(
                            key = "app_$pkg",
                            title = sorted.firstOrNull()?.appName ?: pkg,
                            packageName = pkg,
                            notifications = sorted,
                        )
                    }
                    .sortedByDescending { section ->
                        section.notifications.maxOfOrNull { it.postedAt } ?: 0L
                    }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    // Filters state
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val _isLoadingApps = MutableStateFlow(true)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _filterSearchQuery = MutableStateFlow("")
    val filterSearchQuery: StateFlow<String> = _filterSearchQuery.asStateFlow()

    private val _filterTab = MutableStateFlow(AppFilterTab.ALL)
    val filterTab: StateFlow<AppFilterTab> = _filterTab.asStateFlow()

    val allAppFilterItems: StateFlow<List<AppFilterItem>> = combine(
        _installedApps,
        repository.appFilters,
    ) { launchable, storedRules ->
        val rulesByPkg = storedRules.associateBy { it.packageName }
        val merged = linkedMapOf<String, AppFilterItem>()

        for (appInfo in launchable) {
            val stored = rulesByPkg[appInfo.packageName]
            merged[appInfo.packageName] = AppFilterItem(
                packageName = appInfo.packageName,
                appName = appInfo.appName,
                allowInStatusBar = stored?.allowInStatusBar ?: false,
                isEssentialCandidate = appInfo.isEssentialCandidate,
            )
        }

        // Also surface any non-launcher package that posted a notification
        for (rule in storedRules) {
            if (!merged.containsKey(rule.packageName) && rule.packageName != app.packageName) {
                merged[rule.packageName] = AppFilterItem(
                    packageName = rule.packageName,
                    appName = rule.appName,
                    allowInStatusBar = rule.allowInStatusBar,
                    isEssentialCandidate = false,
                )
            }
        }

        merged.values.sortedWith(
            compareByDescending<AppFilterItem> { it.allowInStatusBar }
                .thenBy { it.appName.lowercase() }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val filteredAppItems: StateFlow<List<AppFilterItem>> = combine(
        allAppFilterItems,
        _filterSearchQuery,
        _filterTab,
    ) { allItems, query, tab ->
        val byTab = when (tab) {
            AppFilterTab.ALL -> allItems
            AppFilterTab.ALLOWED_IN_BAR -> allItems.filter { it.allowInStatusBar }
            AppFilterTab.FILTERED_TO_INBOX -> allItems.filter { !it.allowInStatusBar }
        }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            byTab
        } else {
            byTab.filter {
                it.appName.contains(trimmed, ignoreCase = true) ||
                    it.packageName.contains(trimmed, ignoreCase = true)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    // Update checker state
    private val _updateCheckResult = MutableStateFlow<UpdateCheckResult>(UpdateCheckResult.Idle)
    val updateCheckResult: StateFlow<UpdateCheckResult> = _updateCheckResult.asStateFlow()

    init {
        refreshInstalledApps()
    }

    fun refreshNotificationAccess() {
        _isNotificationAccessGranted.value =
            QuietNotificationListenerService.isNotificationAccessGranted(app)
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            _installedApps.value = repository.loadLaunchableApps()
            _isLoadingApps.value = false
        }
    }

    fun sweepActiveStatusBar() {
        QuietNotificationListenerService.sweepActiveStatusBar()
    }

    // Inbox controls
    fun setInboxSearchQuery(query: String) {
        _inboxSearchQuery.value = query
    }

    fun toggleSortOrder() {
        viewModelScope.launch {
            val current = userPreferences.value.sortNewestFirst
            prefsRepo.setSortNewestFirst(!current)
        }
    }

    fun toggleGroupExpanded(packageName: String) {
        _collapsedPackages.update { current ->
            if (packageName in current) current - packageName else current + packageName
        }
    }

    fun toggleCollapseAllInboxGroups() {
        val allPackages = inboxGroups.value.map { it.packageName }.toSet()
        if (allPackages.isEmpty()) return
        val allCollapsed = allPackages.all { it in _collapsedPackages.value }
        _collapsedPackages.value = if (allCollapsed) emptySet() else allPackages
    }

    fun toggleArchiveSectionExpanded(sectionKey: String) {
        _collapsedArchiveSections.update { current ->
            if (sectionKey in current) current - sectionKey else current + sectionKey
        }
    }

    fun toggleCollapseAllArchiveSections() {
        val allKeys = archiveSections.value.map { it.key }.toSet()
        if (allKeys.isEmpty()) return
        val allCollapsed = allKeys.all { it in _collapsedArchiveSections.value }
        _collapsedArchiveSections.value = if (allCollapsed) emptySet() else allKeys
    }

    // Multi-selection controls
    fun startSelection(initialId: Long? = null) {
        _isSelectionMode.value = true
        if (initialId != null) {
            _selectedIds.update { it + initialId }
        }
    }

    fun toggleItemSelection(id: Long) {
        _selectedIds.update { current ->
            val next = if (id in current) current - id else current + id
            if (next.isEmpty()) {
                _isSelectionMode.value = false
            } else {
                _isSelectionMode.value = true
            }
            next
        }
    }

    fun toggleGroupSelection(group: NotificationAppGroup) {
        val groupIds = group.notifications.map { it.id }.toSet()
        _selectedIds.update { current ->
            val allSelected = groupIds.all { it in current }
            val next = if (allSelected) current - groupIds else current + groupIds
            if (next.isEmpty()) {
                _isSelectionMode.value = false
            } else {
                _isSelectionMode.value = true
            }
            next
        }
    }

    fun selectAllInbox() {
        val allIds = inboxGroups.value.flatMap { group -> group.notifications.map { it.id } }.toSet()
        if (allIds.isNotEmpty()) {
            _isSelectionMode.value = true
            _selectedIds.value = allIds
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        _isSelectionMode.value = false
    }

    // Level 1 Archival: Single notification
    fun archiveSingle(item: NotificationEntity) {
        viewModelScope.launch {
            val archived = repository.archiveSingle(item.id)
            _selectedIds.update { current ->
                val next = current - item.id
                if (next.isEmpty() && _isSelectionMode.value) {
                    _isSelectionMode.value = false
                }
                next
            }
            if (archived.isNotEmpty()) {
                _events.emit(
                    UiFeedbackEvent.ArchivedWithUndo(
                        message = "Archived notification from ${item.appName}",
                        archivedIds = archived,
                    )
                )
            }
        }
    }

    // Level 2 Archival: Full app group
    fun archiveGroup(packageName: String, appName: String) {
        viewModelScope.launch {
            val archived = repository.archiveGroup(packageName)
            if (archived.isNotEmpty()) {
                val archivedSet = archived.toSet()
                _selectedIds.update { current ->
                    val next = current - archivedSet
                    if (next.isEmpty() && _isSelectionMode.value) {
                        _isSelectionMode.value = false
                    }
                    next
                }
                val label = if (archived.size == 1) "1 notification" else "${archived.size} notifications"
                _events.emit(
                    UiFeedbackEvent.ArchivedWithUndo(
                        message = "Archived $label from $appName",
                        archivedIds = archived,
                    )
                )
            }
        }
    }

    // Level 3 Archival: Multi-selected notifications
    fun archiveSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val archived = repository.archiveSelected(ids)
            clearSelection()
            if (archived.isNotEmpty()) {
                val label = if (archived.size == 1) "1 notification" else "${archived.size} notifications"
                _events.emit(
                    UiFeedbackEvent.ArchivedWithUndo(
                        message = "Archived $label",
                        archivedIds = archived,
                    )
                )
            }
        }
    }

    fun restoreNotifications(ids: List<Long>) {
        viewModelScope.launch {
            repository.restoreNotifications(ids)
        }
    }

    fun restoreSingleFromArchive(item: NotificationEntity) {
        viewModelScope.launch {
            repository.restoreNotifications(listOf(item.id))
            _events.emit(UiFeedbackEvent.Message("Restored to Inbox"))
        }
    }

    fun deleteArchivedItem(item: NotificationEntity) {
        viewModelScope.launch {
            repository.deletePermanently(item.id)
            _events.emit(UiFeedbackEvent.Message("Removed from history"))
        }
    }

    fun clearAllArchived() {
        viewModelScope.launch {
            repository.clearAllArchived()
            _events.emit(UiFeedbackEvent.Message("Archived history cleared"))
        }
    }

    fun onNotificationOpened(item: NotificationEntity) {
        if (!item.isArchived && userPreferences.value.archiveOnClick) {
            archiveSingle(item)
        }
    }

    // Archive controls
    fun setArchiveSearchQuery(query: String) {
        _archiveSearchQuery.value = query
    }

    fun setArchiveGroupMode(mode: ArchiveGroupMode) {
        _archiveGroupMode.value = mode
    }

    // Filter controls
    fun setFilterSearchQuery(query: String) {
        _filterSearchQuery.value = query
    }

    fun setFilterTab(tab: AppFilterTab) {
        _filterTab.value = tab
    }

    fun setAppAllowedInStatusBar(item: AppFilterItem, allow: Boolean) {
        viewModelScope.launch {
            repository.setAppAllowedInStatusBar(
                packageName = item.packageName,
                appName = item.appName,
                allowInStatusBar = allow,
            )
        }
    }

    fun allowEssentialApps() {
        viewModelScope.launch {
            repository.allowEssentialApps(_installedApps.value)
            _events.emit(UiFeedbackEvent.Message("Essential apps kept in status bar"))
        }
    }

    fun filterAllApps() {
        viewModelScope.launch {
            repository.filterAllApps()
            _events.emit(UiFeedbackEvent.Message("All apps routed to QuietDrawer Inbox"))
        }
    }

    // Preferences controls
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefsRepo.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setDynamicColor(enabled) }
    }

    fun setFilteringEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setFilteringEnabled(enabled) }
    }

    fun setSkipOngoingNotifications(skip: Boolean) {
        viewModelScope.launch { prefsRepo.setSkipOngoingNotifications(skip) }
    }

    fun setArchiveOnClick(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setArchiveOnClick(enabled) }
    }

    fun setUpdateFeedUrl(url: String) {
        viewModelScope.launch { prefsRepo.setUpdateFeedUrl(url) }
    }

    fun checkForUpdates(sourceUrl: String) {
        viewModelScope.launch {
            prefsRepo.setUpdateFeedUrl(sourceUrl)
            _updateCheckResult.value = UpdateCheckResult.Checking
            _updateCheckResult.value = updateChecker.checkForUpdates(sourceUrl)
        }
    }

    fun emitFeedbackMessage(message: String) {
        viewModelScope.launch {
            _events.emit(UiFeedbackEvent.Message(message))
        }
    }
}
