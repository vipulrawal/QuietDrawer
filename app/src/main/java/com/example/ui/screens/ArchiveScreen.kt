package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.ArchiveGroupMode
import com.example.service.LaunchResult
import com.example.service.NotificationLauncher
import com.example.ui.QuietDrawerViewModel
import com.example.ui.components.AppIconImage
import com.example.ui.components.SwipeableArchivedRow

@Composable
fun ArchiveScreen(
    viewModel: QuietDrawerViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sections by viewModel.archiveSections.collectAsStateWithLifecycle()
    val searchQuery by viewModel.archiveSearchQuery.collectAsStateWithLifecycle()
    val groupMode by viewModel.archiveGroupMode.collectAsStateWithLifecycle()
    val collapsedSections by viewModel.collapsedArchiveSections.collectAsStateWithLifecycle()

    var showClearConfirm by remember { mutableStateOf(false) }

    val totalArchived = remember(sections) {
        sections.sumOf { it.notifications.size }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.archive_clear_confirm_title)) },
            text = { Text(stringResource(R.string.archive_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearAllArchived()
                    },
                    modifier = Modifier.testTag("archive_confirm_clear_button"),
                ) {
                    Text(
                        text = stringResource(R.string.archive_clear_confirm_cta),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.archive_title),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "$totalArchived archived in history",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (totalArchived > 0) {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.testTag("archive_clear_all_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.archive_clear_all))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setArchiveSearchQuery,
                placeholder = { Text(stringResource(R.string.inbox_search_hint)) },
                singleLine = true,
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setArchiveSearchQuery("") }) {
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
                    .testTag("archive_search_input"),
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Group mode toggle + collapse/expand all
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = groupMode == ArchiveGroupMode.BY_DATE,
                    onClick = { viewModel.setArchiveGroupMode(ArchiveGroupMode.BY_DATE) },
                    label = { Text(stringResource(R.string.archive_group_by_date)) },
                    modifier = Modifier.testTag("archive_mode_date_chip"),
                )
                FilterChip(
                    selected = groupMode == ArchiveGroupMode.BY_APP,
                    onClick = { viewModel.setArchiveGroupMode(ArchiveGroupMode.BY_APP) },
                    label = { Text(stringResource(R.string.archive_group_by_app)) },
                    modifier = Modifier.testTag("archive_mode_app_chip"),
                )
                Spacer(modifier = Modifier.weight(1f))
                if (sections.isNotEmpty()) {
                    val allCollapsed = sections.all { it.key in collapsedSections }
                    IconButton(
                        onClick = viewModel::toggleCollapseAllArchiveSections,
                        modifier = Modifier.testTag("archive_collapse_all_button"),
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
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (sections.isEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("archive_empty_state"),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "No archived notifications match"
                            } else {
                                stringResource(R.string.archive_empty_title)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.archive_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("archive_list"),
                ) {
                    item(key = "archive_hint") {
                        Text(
                            text = "Tap to open in app · Swipe right to restore · Swipe left to delete",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }

                    sections.forEach { section ->
                        val isExpanded = section.key !in collapsedSections
                        item(key = "section_${section.key}") {
                            Surface(
                                onClick = { viewModel.toggleArchiveSectionExpanded(section.key) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .testTag("archive_section_header_${section.key}"),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                ) {
                                    if (section.packageName != null) {
                                        AppIconImage(
                                            packageName = section.packageName,
                                            appName = section.title,
                                            appsProvider = viewModel.installedAppsProvider,
                                            size = 26.dp,
                                        )
                                    }
                                    Text(
                                        text = section.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            text = section.notifications.size.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.toggleArchiveSectionExpanded(section.key) },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("toggle_archive_section_${section.key}"),
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

                        if (isExpanded) {
                            items(
                                items = section.notifications,
                                key = { item -> "archived_${item.id}" },
                            ) { item ->
                                SwipeableArchivedRow(
                                    item = item,
                                    appsProvider = viewModel.installedAppsProvider,
                                    showAppHeader = groupMode == ArchiveGroupMode.BY_DATE,
                                    onClick = {
                                        val result = NotificationLauncher.openNotification(context, item)
                                        if (result is LaunchResult.Failed) {
                                            viewModel.emitFeedbackMessage(
                                                context.getString(R.string.open_app_failed, result.appName)
                                            )
                                        }
                                    },
                                    onRestore = { viewModel.restoreSingleFromArchive(item) },
                                    onDelete = { viewModel.deleteArchivedItem(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
