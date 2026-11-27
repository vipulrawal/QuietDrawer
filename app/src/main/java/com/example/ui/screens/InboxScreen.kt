package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.NotificationEntity
import com.example.service.LaunchResult
import com.example.service.NotificationLauncher
import com.example.ui.QuietDrawerViewModel
import com.example.ui.components.MultiSelectSwipeArchiveBar
import com.example.ui.components.PermissionSetupBanner
import com.example.ui.components.SwipeableAppGroupFooter
import com.example.ui.components.SwipeableAppGroupHeader
import com.example.ui.components.SwipeableNotificationRow

@Composable
fun InboxScreen(
    viewModel: QuietDrawerViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs by viewModel.userPreferences.collectAsStateWithLifecycle()
    val hasAccess by viewModel.isNotificationAccessGranted.collectAsStateWithLifecycle()
    val isConnected by viewModel.isListenerConnected.collectAsStateWithLifecycle()
    val groups by viewModel.inboxGroups.collectAsStateWithLifecycle()
    val searchQuery by viewModel.inboxSearchQuery.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val collapsedPackages by viewModel.collapsedPackages.collectAsStateWithLifecycle()

    var showSearch by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = isSelectionMode) {
        viewModel.clearSelection()
    }

    val openNotificationSettings = {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            viewModel.emitFeedbackMessage("Open Android Settings → Notification access")
        }
    }

    val handleOpenNotification: (NotificationEntity) -> Unit = { item ->
        val result = NotificationLauncher.openNotification(context, item)
        if (result is LaunchResult.Failed) {
            viewModel.emitFeedbackMessage(context.getString(R.string.open_app_failed, result.appName))
        } else {
            viewModel.onNotificationOpened(item)
        }
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 16.dp),
        ) {
            // Editorial Top Header
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.inbox_title),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FilterStatusChip(
                        hasAccess = hasAccess,
                        filteringEnabled = prefs.filteringEnabled,
                        onClick = {
                            if (!hasAccess) {
                                openNotificationSettings()
                            } else {
                                viewModel.setFilteringEnabled(!prefs.filteringEnabled)
                            }
                        },
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (hasAccess && isConnected) {
                        IconButton(
                            onClick = {
                                viewModel.sweepActiveStatusBar()
                                viewModel.emitFeedbackMessage("Swept active status bar notifications")
                            },
                            modifier = Modifier.testTag("inbox_sweep_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoveToInbox,
                                contentDescription = "Sweep status bar now",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.toggleSortOrder() },
                        modifier = Modifier.testTag("inbox_sort_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SwapVert,
                            contentDescription = if (prefs.sortNewestFirst) {
                                stringResource(R.string.sort_newest)
                            } else {
                                stringResource(R.string.sort_oldest)
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (groups.isNotEmpty()) {
                        val allCollapsed = groups.all { it.packageName in collapsedPackages }
                        IconButton(
                            onClick = viewModel::toggleCollapseAllInboxGroups,
                            modifier = Modifier.testTag("inbox_collapse_all_button"),
                        ) {
                            Icon(
                                imageVector = if (allCollapsed) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
                                contentDescription = if (allCollapsed) {
                                    stringResource(R.string.action_expand_all)
                                } else {
                                    stringResource(R.string.action_collapse_all)
                                },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        IconButton(
                            onClick = {
                                if (isSelectionMode) viewModel.clearSelection() else viewModel.startSelection()
                            },
                            modifier = Modifier.testTag("inbox_select_mode_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Checklist,
                                contentDescription = stringResource(R.string.action_select),
                                tint = if (isSelectionMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.setInboxSearchQuery("")
                        },
                        modifier = Modifier.testTag("inbox_search_toggle_button"),
                    ) {
                        Icon(
                            imageVector = if (showSearch) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = "Search inbox",
                            tint = if (showSearch) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // Search bar
            AnimatedVisibility(
                visible = showSearch,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setInboxSearchQuery,
                    placeholder = { Text(stringResource(R.string.inbox_search_hint)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setInboxSearchQuery("") }) {
                                Icon(imageVector = Icons.Rounded.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("inbox_search_input"),
                )
            }

            // Multi-select swipe-to-archive bar (Level 3 archival)
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                MultiSelectSwipeArchiveBar(
                    selectedCount = selectedIds.size,
                    onSelectAll = viewModel::selectAllInbox,
                    onCancel = viewModel::clearSelection,
                    onArchiveSelected = viewModel::archiveSelected,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Inbox Content
            LazyColumn(
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("inbox_list"),
            ) {
                if (!hasAccess) {
                    item(key = "permission_banner") {
                        PermissionSetupBanner(
                            onGrantClick = openNotificationSettings,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }

                if (groups.isEmpty()) {
                    item(key = "empty_state") {
                        CalmEmptyInboxCard(
                            isSearching = searchQuery.isNotBlank(),
                            sortNewestFirst = prefs.sortNewestFirst,
                        )
                    }
                } else {
                    item(key = "sort_caption") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "Swipe left or right on any item, selection, or group to archive",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (prefs.sortNewestFirst) "NEWEST" else "OLDEST",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    groups.forEach { group ->
                        val isExpanded = group.packageName !in collapsedPackages

                        item(key = "group_${group.packageName}") {
                            SwipeableAppGroupHeader(
                                group = group,
                                appsProvider = viewModel.installedAppsProvider,
                                isExpanded = isExpanded,
                                isSelectionMode = isSelectionMode,
                                selectedIds = selectedIds,
                                onToggleExpand = { viewModel.toggleGroupExpanded(group.packageName) },
                                onToggleGroupSelect = { viewModel.toggleGroupSelection(group) },
                                onArchiveGroup = { viewModel.archiveGroup(group.packageName, group.appName) },
                            )
                        }

                        if (isExpanded) {
                            items(
                                items = group.notifications,
                                key = { notif -> "notif_${notif.id}" },
                            ) { item ->
                                val isSelected = item.id in selectedIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp),
                                ) {
                                    // Subtle editorial hairline linking notifications to their app group
                                    Box(
                                        modifier = Modifier
                                            .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                                            .width(2.dp)
                                            .height(48.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.outlineVariant),
                                    )
                                    SwipeableNotificationRow(
                                        item = item,
                                        isSelectionMode = isSelectionMode,
                                        isSelected = isSelected,
                                        selectedCount = selectedIds.size,
                                        onClick = {
                                            if (isSelectionMode) {
                                                viewModel.toggleItemSelection(item.id)
                                            } else {
                                                handleOpenNotification(item)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) {
                                                viewModel.startSelection(item.id)
                                            } else {
                                                viewModel.toggleItemSelection(item.id)
                                            }
                                        },
                                        onSwipeArchive = {
                                            if (isSelectionMode && isSelected && selectedIds.size > 1) {
                                                viewModel.archiveSelected()
                                            } else {
                                                viewModel.archiveSingle(item)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            if (group.notifications.size > 5) {
                                item(key = "group_footer_${group.packageName}") {
                                    SwipeableAppGroupFooter(
                                        group = group,
                                        isSelectionMode = isSelectionMode,
                                        onCollapseGroup = { viewModel.toggleGroupExpanded(group.packageName) },
                                        onArchiveGroup = { viewModel.archiveGroup(group.packageName, group.appName) },
                                    )
                                }
                            }
                        }

                        item(key = "spacer_${group.packageName}") {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterStatusChip(
    hasAccess: Boolean,
    filteringEnabled: Boolean,
    onClick: () -> Unit,
) {
    val dotColor = when {
        !hasAccess -> MaterialTheme.colorScheme.tertiary
        filteringEnabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        !hasAccess -> stringResource(R.string.filter_status_permission_needed)
        filteringEnabled -> stringResource(R.string.filter_status_active)
        else -> stringResource(R.string.filter_status_paused)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag("filter_status_chip"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalmEmptyInboxCard(
    isSearching: Boolean,
    sortNewestFirst: Boolean,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .testTag("inbox_empty_state"),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            if (!isSearching) {
                Image(
                    painter = painterResource(id = R.drawable.img_calm_inbox_empty_1795591027190),
                    contentDescription = "Calm inbox illustration",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            Text(
                text = if (isSearching) "No matching notifications" else stringResource(R.string.inbox_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isSearching) {
                    "Try searching for a different app name or keyword."
                } else {
                    stringResource(R.string.inbox_empty_subtitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!isSearching) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (sortNewestFirst) {
                        "Grouped by app · Sorted newest first"
                    } else {
                        "Grouped by app · Sorted oldest first"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
