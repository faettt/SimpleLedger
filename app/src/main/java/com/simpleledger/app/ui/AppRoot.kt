package com.simpleledger.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.simpleledger.app.R
import com.simpleledger.app.ui.entry.EntryEditScreen
import com.simpleledger.app.ui.ledger.LedgerScreen
import com.simpleledger.app.ui.manage.ManageScreen
import com.simpleledger.app.ui.stats.StatsScreen

object Routes {
    const val LEDGER = "ledger"
    const val STATS = "stats"
    const val MANAGE = "manage"
    const val ENTRY_EDIT = "entry/{entryId}"

    fun entryEdit(entryId: Long): String = "entry/$entryId"
}

private data class BottomItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector?,
    val emoji: String,
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val bottomItems = listOf(
        BottomItem(Routes.LEDGER, R.string.nav_ledger, null, "📒"),
        BottomItem(Routes.STATS, R.string.nav_stats, null, "📊"),
        BottomItem(Routes.MANAGE, R.string.nav_manage, null, "🗂️"),
    )
    val showBottomBar = currentRoute in bottomItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (item.icon != null) {
                                    Icon(item.icon, contentDescription = null)
                                } else {
                                    Text(item.emoji, fontSize = 22.sp)
                                }
                            },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LEDGER,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.LEDGER) {
                LedgerScreen(
                    onEditEntry = { id -> navController.navigate(Routes.entryEdit(id)) },
                    onAddEntry = { navController.navigate(Routes.entryEdit(-1L)) },
                )
            }
            composable(Routes.STATS) {
                StatsScreen()
            }
            composable(Routes.MANAGE) {
                ManageScreen()
            }
            composable(Routes.ENTRY_EDIT) { entry ->
                val entryId = entry.arguments?.getString("entryId")?.toLongOrNull() ?: -1L
                EntryEditScreen(
                    entryId = entryId,
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}
