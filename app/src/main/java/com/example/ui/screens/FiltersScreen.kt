package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterListOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.AppFilterTab
import com.example.ui.AppFilterItem
import com.example.ui.QuietDrawerViewModel
import com.example.ui.components.AppIconImage

@Composable
fun FiltersScreen(
    viewModel: QuietDrawerViewModel,
    modifier: Modifier = Modifier,
) {
    val allApps by viewModel.allAppFilterItems.collectAsStateWithLifecycle()
    val filteredApps by viewModel.filteredAppItems.collectAsStateWithLifecycle()
    val searchQuery by viewModel.filterSearchQuery.collectAsStateWithLifecycle()
    val selectedTab by viewModel.filterTab.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingApps.collectAsStateWithLifecycle()

    val allowedCount = remember(allApps) { allApps.count { it.allowInStatusBar } }
    val inboxCount = remember(allApps) { allApps.count { !it.allowInStatusBar } }

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
            Text(
                text = stringResource(R.string.filters_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.filters_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Live summary counters
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Column {
                            Text(
                                text = allowedCount.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.filters_tab_allowed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.NotificationsPaused,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Column {
                            Text(
                                text = inboxCount.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.filters_tab_filtered),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search installed apps
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setFilterSearchQuery,
                placeholder = { Text(stringResource(R.string.filters_search_hint)) },
                singleLine = true,
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setFilterSearchQuery("") }) {
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
                    .testTag("filters_search_input"),
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Tab chips & quick bulk actions
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    FilterChip(
                        selected = selectedTab == AppFilterTab.ALL,
                        onClick = { viewModel.setFilterTab(AppFilterTab.ALL) },
                        label = { Text("${stringResource(R.string.filters_tab_all)} (${allApps.size})") },
                        modifier = Modifier.testTag("filter_tab_all"),
                    )
                }
                item {
                    FilterChip(
                        selected = selectedTab == AppFilterTab.ALLOWED_IN_BAR,
                        onClick = { viewModel.setFilterTab(AppFilterTab.ALLOWED_IN_BAR) },
                        label = { Text("${stringResource(R.string.filters_tab_allowed)} ($allowedCount)") },
                        modifier = Modifier.testTag("filter_tab_allowed"),
                    )
                }
                item {
                    FilterChip(
                        selected = selectedTab == AppFilterTab.FILTERED_TO_INBOX,
                        onClick = { viewModel.setFilterTab(AppFilterTab.FILTERED_TO_INBOX) },
                        label = { Text("${stringResource(R.string.filters_tab_filtered)} ($inboxCount)") },
                        modifier = Modifier.testTag("filter_tab_inbox"),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                AssistChip(
                    onClick = viewModel::allowEssentialApps,
                    label = { Text(stringResource(R.string.filters_allow_essentials)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.testTag("filters_allow_essentials_button"),
                )
                AssistChip(
                    onClick = viewModel::filterAllApps,
                    label = { Text(stringResource(R.string.filters_filter_all)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.FilterListOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.testTag("filters_filter_all_button"),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (isLoading && allApps.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.filters_empty_search),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("filters_app_list"),
                ) {
                    items(
                        items = filteredApps,
                        key = { it.packageName },
                    ) { appItem ->
                        AppFilterRow(
                            item = appItem,
                            viewModel = viewModel,
                            onToggle = { allow -> viewModel.setAppAllowedInStatusBar(appItem, allow) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppFilterRow(
    item: AppFilterItem,
    viewModel: QuietDrawerViewModel,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (item.allowInStatusBar) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onToggle(!item.allowInStatusBar) }
            .testTag("app_filter_row_${item.packageName}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            AppIconImage(
                packageName = item.packageName,
                appName = item.appName,
                appsProvider = viewModel.installedAppsProvider,
                size = 38.dp,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.appName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (item.allowInStatusBar) {
                        stringResource(R.string.filters_badge_bar)
                    } else {
                        stringResource(R.string.filters_badge_inbox)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.allowInStatusBar) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Switch(
                checked = item.allowInStatusBar,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("app_filter_switch_${item.packageName}"),
            )
        }
    }
}
