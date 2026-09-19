package com.simpleledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.simpleledger.app.R
import com.simpleledger.app.ui.entry.EntryEditScreen
import com.simpleledger.app.ui.ledger.LedgerScreen
import com.simpleledger.app.ui.ledger.RESULT_SAVED_ENTRY_ID
import com.simpleledger.app.ui.manage.ManageScreen
import com.simpleledger.app.ui.mine.MineScreen
import com.simpleledger.app.ui.stats.StatsScreen

object Routes {
    const val LEDGER = "ledger"
    const val STATS = "stats"
    const val MANAGE = "manage"
    const val MINE = "mine"
    const val ENTRY_EDIT = "entry/{entryId}"

    fun entryEdit(entryId: Long): String = "entry/$entryId"

    /** 一级导航（不含全局主操作「记一笔」） */
    val topLevel = listOf(LEDGER, STATS, MANAGE, MINE)
}

/** 导航项：emoji 作为图标（分类体系本身就用 emoji 表达，保持语言一致） */
private data class NavItem(
    val route: String,
    val labelRes: Int,
    val emoji: String,
)

private val navItems = listOf(
    NavItem(Routes.LEDGER, R.string.nav_ledger, "📒"),
    NavItem(Routes.STATS, R.string.nav_stats, "📊"),
    // 中间为「记一笔」全局主操作（凸起按钮），见 LedgerBottomBar
    NavItem(Routes.MANAGE, R.string.nav_sections, "🗂️"),
    NavItem(Routes.MINE, R.string.nav_mine, "👤"),
)

/** 窗口尺寸类阈值（dp）：<600 手机 · 600–840 折叠屏/小平板 · ≥840 大屏 */
private val RAIL_THRESHOLD = 600.dp
private val EXPANDED_THRESHOLD = 840.dp

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showNavigation = currentRoute in Routes.topLevel

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= RAIL_THRESHOLD
        val railExpanded = maxWidth >= EXPANDED_THRESHOLD

        Scaffold(
            bottomBar = {
                if (!useRail && showNavigation) {
                    LedgerBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = navController::navigateTopLevel,
                        onRecord = { navController.navigate(Routes.entryEdit(-1L)) },
                    )
                }
            },
        ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (useRail && showNavigation) {
                    LedgerNavRail(
                        currentRoute = currentRoute,
                        expanded = railExpanded,
                        onNavigate = navController::navigateTopLevel,
                        onRecord = { navController.navigate(Routes.entryEdit(-1L)) },
                    )
                }
                AppNavHost(
                    navController = navController,
                    twoPane = railExpanded,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 一级导航的跳转语义：保存状态、单一实例、回到栈顶 */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    twoPane: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.LEDGER,
        modifier = modifier,
    ) {
        composable(Routes.LEDGER) { entry ->
            LedgerScreen(
                resultHandle = entry.savedStateHandle,
                onEditEntry = { id -> navController.navigate(Routes.entryEdit(id)) },
                twoPane = twoPane,
            )
        }
        composable(Routes.STATS) { StatsScreen() }
        composable(Routes.MANAGE) { ManageScreen() }
        composable(Routes.MINE) { MineScreen() }
        composable(Routes.ENTRY_EDIT) { entry ->
            val entryId = entry.arguments?.getString("entryId")?.toLongOrNull() ?: -1L
            EntryEditScreen(
                entryId = entryId,
                onDone = { savedId ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(RESULT_SAVED_ENTRY_ID, savedId ?: -1L)
                    navController.popBackStack()
                },
            )
        }
    }
}

/* ============================================================
   手机：底部 5 槽位导航 + 中央凸起「记一笔」
   ============================================================ */
@Composable
private fun LedgerBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onRecord: () -> Unit,
) {
    val barHeight = 64.dp
    val recordSize = 52.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight + 20.dp)
            .navigationBarsPadding(),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.BottomCenter),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {}

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            navItems.forEachIndexed { index, item ->
                if (index == 2) {
                    // 中央槽位留给凸起按钮
                    Spacer(modifier = Modifier.width(recordSize + 8.dp))
                }
                BottomBarSlot(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onNavigate(item.route) },
                )
            }
        }

        // 中央凸起「记一笔」：最高频动作，独立于导航体系
        Surface(
            onClick = onRecord,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(recordSize)
                .shadow(8.dp, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("＋", fontSize = 26.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun BottomBarSlot(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            onClick = onClick,
            color = Color.Transparent,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(item.emoji, fontSize = 20.sp, modifier = Modifier.alpha(if (selected) 1f else 0.72f))
                Text(
                    text = stringResource(item.labelRes),
                    fontSize = 10.5.sp,
                    color = color,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/* ============================================================
   大屏：Navigation Rail（Medium 图标+文字 / Expanded 加宽常驻文字）
   ============================================================ */
@Composable
private fun LedgerNavRail(
    currentRoute: String?,
    expanded: Boolean,
    onNavigate: (String) -> Unit,
    onRecord: () -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(if (expanded) 108.dp else 84.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        navItems.forEachIndexed { index, item ->
            if (index == 2) {
                // 「记一笔」以品牌填充块呈现，视觉上明显区别于普通导航项
                NavigationRailItem(
                    selected = false,
                    onClick = onRecord,
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "＋",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.nav_record), fontSize = 11.sp) },
                    colors = NavigationRailItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        unselectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
            NavigationRailItem(
                selected = currentRoute == item.route,
                onClick = { onNavigate(item.route) },
                icon = { Text(item.emoji, fontSize = 19.sp) },
                label = { Text(stringResource(item.labelRes), fontSize = 11.sp) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}
