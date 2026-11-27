package com.example.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.ui.screens.ArchiveScreen
import com.example.ui.screens.FiltersScreen
import com.example.ui.screens.InboxScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.QuietDrawerTheme

object QuietDrawerRoutes {
    const val INBOX = "inbox"
    const val ARCHIVE = "archive"
    const val FILTERS = "filters"
    const val SETTINGS = "settings"
}

private data class NavDestinationItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

private val NAV_ITEMS = listOf(
    NavDestinationItem(
        route = QuietDrawerRoutes.INBOX,
        labelRes = R.string.nav_inbox,
        selectedIcon = Icons.Rounded.Inbox,
        unselectedIcon = Icons.Outlined.Inbox,
        testTag = "nav_tab_inbox",
    ),
    NavDestinationItem(
        route = QuietDrawerRoutes.ARCHIVE,
        labelRes = R.string.nav_archive,
        selectedIcon = Icons.Rounded.History,
        unselectedIcon = Icons.Outlined.History,
        testTag = "nav_tab_archive",
    ),
    NavDestinationItem(
        route = QuietDrawerRoutes.FILTERS,
        labelRes = R.string.nav_filters,
        selectedIcon = Icons.Rounded.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        testTag = "nav_tab_filters",
    ),
    NavDestinationItem(
        route = QuietDrawerRoutes.SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_tab_settings",
    ),
)

@Composable
fun QuietDrawerApp(viewModel: QuietDrawerViewModel) {
    val prefs by viewModel.userPreferences.collectAsStateWithLifecycle()
    val inboxCount by viewModel.inboxCount.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onAppResumed()
        viewModel.refreshInstalledApps()
    }

    QuietDrawerTheme(
        themeMode = prefs.themeMode,
        dynamicColor = prefs.dynamicColor,
    ) {
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }
        val undoLabel = stringResource(R.string.action_undo)

        LaunchedEffect(Unit) {
            viewModel.events.collect { event ->
                snackbarHostState.currentSnackbarData?.dismiss()
                when (event) {
                    is UiFeedbackEvent.ArchivedWithUndo -> {
                        val result = snackbarHostState.showSnackbar(
                            message = event.message,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.restoreNotifications(event.archivedIds)
                        }
                    }
                    is UiFeedbackEvent.Message -> {
                        snackbarHostState.showSnackbar(
                            message = event.text,
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            }
        }

        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        val navigateToTab: (String) -> Unit = { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        windowInsets = WindowInsets.safeDrawing,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        NAV_ITEMS.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(item.route) },
                                icon = {
                                    NavTabIcon(
                                        item = item,
                                        selected = selected,
                                        inboxCount = inboxCount,
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.testTag),
                            )
                        }
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets.navigationBars,
                            ) {
                                NAV_ITEMS.forEach { item ->
                                    val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navigateToTab(item.route) },
                                        icon = {
                                            NavTabIcon(
                                                item = item,
                                                selected = selected,
                                                inboxCount = inboxCount,
                                            )
                                        },
                                        label = { Text(stringResource(item.labelRes)) },
                                        modifier = Modifier.testTag(item.testTag),
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = QuietDrawerRoutes.INBOX,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        composable(QuietDrawerRoutes.INBOX) {
                            InboxScreen(viewModel = viewModel)
                        }
                        composable(QuietDrawerRoutes.ARCHIVE) {
                            ArchiveScreen(viewModel = viewModel)
                        }
                        composable(QuietDrawerRoutes.FILTERS) {
                            FiltersScreen(viewModel = viewModel)
                        }
                        composable(QuietDrawerRoutes.SETTINGS) {
                            SettingsScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavTabIcon(
    item: NavDestinationItem,
    selected: Boolean,
    inboxCount: Int,
) {
    val icon = if (selected) item.selectedIcon else item.unselectedIcon
    val label = stringResource(item.labelRes)
    if (item.route == QuietDrawerRoutes.INBOX && inboxCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(text = if (inboxCount > 99) "99+" else inboxCount.toString())
                }
            }
        ) {
            Icon(imageVector = icon, contentDescription = label)
        }
    } else {
        Icon(imageVector = icon, contentDescription = label)
    }
}
