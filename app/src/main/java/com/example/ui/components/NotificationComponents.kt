package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.apps.InstalledAppsProvider
import com.example.data.local.NotificationEntity
import com.example.ui.NotificationAppGroup
import com.example.ui.util.TimeFormatter

@Composable
fun PermissionSetupBanner(
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("permission_setup_banner"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.permission_banner_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.permission_banner_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onGrantClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.testTag("grant_notification_access_button"),
                ) {
                    Text(
                        text = stringResource(R.string.permission_banner_cta),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * Level 3 Archival Bar: Supports swiping left or right directly on the selection pill
 * OR tapping the Archive button to archive all multi-selected notifications at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiSelectSwipeArchiveBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onArchiveSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val latestSelectedCount = rememberUpdatedState(selectedCount)
    val latestOnArchiveSelected = rememberUpdatedState(onArchiveSelected)
    val hasTriggered = remember { mutableStateOf(false) }

    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd,
                SwipeToDismissBoxValue.EndToStart -> {
                    if (latestSelectedCount.value > 0 && !hasTriggered.value) {
                        hasTriggered.value = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnArchiveSelected.value()
                        true
                    } else {
                        false
                    }
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.35f },
    )

    // Always snap back to Settled when selection changes or after an archive swipe
    LaunchedEffect(selectedCount, hasTriggered.value) {
        if (swipeState.currentValue != SwipeToDismissBoxValue.Settled) {
            swipeState.snapTo(SwipeToDismissBoxValue.Settled)
        }
        hasTriggered.value = false
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("multi_select_bar"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("multi_select_cancel_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_cancel_selection),
                    )
                }
                Text(
                    text = stringResource(R.string.selected_count, selectedCount),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onSelectAll,
                    modifier = Modifier.testTag("multi_select_all_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DoneAll,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.action_select_all))
                }
                IconButton(
                    onClick = onArchiveSelected,
                    enabled = selectedCount > 0,
                    modifier = Modifier.testTag("multi_select_archive_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Archive,
                        contentDescription = stringResource(R.string.action_archive),
                        tint = if (selectedCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                    )
                }
            }

            // Dedicated left-or-right swipe strip to archive all multi-selected notifications
            SwipeToDismissBox(
                state = swipeState,
                enableDismissFromStartToEnd = selectedCount > 0,
                enableDismissFromEndToStart = selectedCount > 0,
                backgroundContent = {
                    ArchiveSwipeBackground(
                        label = stringResource(R.string.action_archive),
                        dismissDirection = swipeState.dismissDirection,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .testTag("multi_select_swipe_archive_pill"),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Archive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.swipe_to_archive_selected, selectedCount),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Level 2 Archival: Swipe left or right on the App Group header to archive the entire group.
 * Disabled while multi-select mode is active so selecting/swiping individual notifications
 * can never accidentally archive the whole group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableAppGroupHeader(
    group: NotificationAppGroup,
    appsProvider: InstalledAppsProvider,
    isExpanded: Boolean,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onToggleExpand: () -> Unit,
    onToggleGroupSelect: () -> Unit,
    onArchiveGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val latestIsSelectionMode = rememberUpdatedState(isSelectionMode)
    val latestOnArchiveGroup = rememberUpdatedState(onArchiveGroup)
    val hasTriggered = remember { mutableStateOf(false) }

    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd,
                SwipeToDismissBoxValue.EndToStart -> {
                    if (!latestIsSelectionMode.value && !hasTriggered.value) {
                        hasTriggered.value = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnArchiveGroup.value()
                        true
                    } else {
                        false
                    }
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.45f },
    )

    // Reset saved dismiss state on Undo, re-entry, or when group notifications change
    LaunchedEffect(group.packageName, group.notifications.size, isSelectionMode) {
        hasTriggered.value = false
        if (swipeState.currentValue != SwipeToDismissBoxValue.Settled) {
            swipeState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    val allInGroupSelected = group.notifications.isNotEmpty() &&
        group.notifications.all { it.id in selectedIds }

    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = !isSelectionMode,
        enableDismissFromEndToStart = !isSelectionMode,
        backgroundContent = {
            ArchiveSwipeBackground(
                label = "${stringResource(R.string.action_archive_group)} (${group.notifications.size})",
                dismissDirection = swipeState.dismissDirection,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .testTag("app_group_header_${group.packageName}"),
    ) {
        Surface(
            onClick = onToggleExpand,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (isSelectionMode) {
                    IconButton(
                        onClick = onToggleGroupSelect,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("select_group_checkbox_${group.packageName}"),
                    ) {
                        Icon(
                            imageVector = if (allInGroupSelected) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.RadioButtonUnchecked
                            },
                            contentDescription = "Select all from ${group.appName}",
                            tint = if (allInGroupSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                AppIconImage(
                    packageName = group.packageName,
                    appName = group.appName,
                    appsProvider = appsProvider,
                    size = 34.dp,
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = group.appName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = group.notifications.size.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = TimeFormatter.formatCompactTimestamp(context, group.latestPostedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!isSelectionMode) {
                    IconButton(
                        onClick = onArchiveGroup,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("archive_group_button_${group.packageName}"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Archive,
                            contentDescription = stringResource(R.string.action_archive_group),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toggle_group_expand_${group.packageName}"),
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (isExpanded) {
                            stringResource(R.string.action_collapse_group)
                        } else {
                            stringResource(R.string.action_expand_group)
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * Level 1 Archival (and Level 3 when in multi-select mode):
 * Swipe left or right on any individual notification to archive ONLY that notification
 * (or archive ONLY the selected notifications if this row is part of an active multi-selection).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableNotificationRow(
    item: NotificationEntity,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    selectedCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val latestOnSwipeArchive = rememberUpdatedState(onSwipeArchive)
    val hasTriggered = remember { mutableStateOf(false) }

    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd,
                SwipeToDismissBoxValue.EndToStart -> {
                    if (!hasTriggered.value) {
                        hasTriggered.value = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnSwipeArchive.value()
                        true
                    } else {
                        false
                    }
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.35f },
    )

    // Ensure restored items (via Undo or Archive -> Restore) never stay stuck in dismissed state
    LaunchedEffect(item.id) {
        hasTriggered.value = false
        if (swipeState.currentValue != SwipeToDismissBoxValue.Settled) {
            swipeState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "notification_row_bg",
    )

    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    }

    val swipeLabel = if (isSelectionMode && isSelected && selectedCount > 1) {
        "${stringResource(R.string.action_archive)} ($selectedCount)"
    } else {
        stringResource(R.string.action_archive)
    }

    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            ArchiveSwipeBackground(
                label = swipeLabel,
                dismissDirection = swipeState.dismissDirection,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .testTag("notification_row_${item.id}"),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = containerColor,
            border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                ),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                AnimatedVisibility(
                    visible = isSelectionMode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Icon(
                        imageVector = if (isSelected) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.RadioButtonUnchecked
                        },
                        contentDescription = if (isSelected) "Selected" else "Not selected",
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .padding(end = 12.dp, top = 2.dp)
                            .size(22.dp),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = TimeFormatter.formatCompactTimestamp(context, item.postedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val displayBody = item.bigText ?: item.text
                    if (displayBody.isNotBlank()) {
                        Text(
                            text = displayBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (!item.subText.isNullOrBlank()) {
                        Text(
                            text = item.subText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (!isSelectionMode) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.action_open_app),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Swipeable Archive Row: Swipe Right -> Restore to Inbox; Swipe Left -> Delete permanently.
 * Clicking always still opens the notification in the originating app!
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableArchivedRow(
    item: NotificationEntity,
    appsProvider: InstalledAppsProvider,
    showAppHeader: Boolean,
    onClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val latestOnRestore = rememberUpdatedState(onRestore)
    val latestOnDelete = rememberUpdatedState(onDelete)
    val hasTriggered = remember { mutableStateOf(false) }

    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (!hasTriggered.value) {
                        hasTriggered.value = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnRestore.value()
                        true
                    } else {
                        false
                    }
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (!hasTriggered.value) {
                        hasTriggered.value = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnDelete.value()
                        true
                    } else {
                        false
                    }
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.35f },
    )

    LaunchedEffect(item.id) {
        hasTriggered.value = false
        if (swipeState.currentValue != SwipeToDismissBoxValue.Settled) {
            swipeState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val isRestore = swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val bgColor = if (isRestore) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
            val contentColor = if (isRestore) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
            Box(
                contentAlignment = if (isRestore) Alignment.CenterStart else Alignment.CenterEnd,
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (isRestore) Icons.Rounded.Unarchive else Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (isRestore) {
                            stringResource(R.string.action_restore)
                        } else {
                            stringResource(R.string.action_delete)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .testTag("archive_row_${item.id}"),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (showAppHeader) {
                    AppIconImage(
                        packageName = item.packageName,
                        appName = item.appName,
                        appsProvider = appsProvider,
                        size = 32.dp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (showAppHeader) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = item.appName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = TimeFormatter.formatCompactTimestamp(context, item.postedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!showAppHeader) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = TimeFormatter.formatCompactTimestamp(context, item.postedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val body = item.bigText ?: item.text
                    if (body.isNotBlank()) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = stringResource(R.string.action_open_app),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveSwipeBackground(
    label: String,
    dismissDirection: SwipeToDismissBoxValue,
) {
    val alignment = when (dismissDirection) {
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    Box(
        contentAlignment = alignment,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Archive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
