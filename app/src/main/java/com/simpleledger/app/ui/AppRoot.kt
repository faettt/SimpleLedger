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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.simpleledger.app.ui.category.GlobalCategoriesScreen
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.entry.EntryEditScreen
import com.simpleledger.app.ui.ledger.LedgerScreen
import com.simpleledger.app.ui.ledger.RESULT_SAVED_ENTRY_ID
import com.simpleledger.app.ui.mine.MineScreen
import com.simpleledger.app.ui.section.SectionDetailScreen
import com.simpleledger.app.ui.section.SectionHomeScreen
import com.simpleledger.app.ui.section.SectionManageScreen
import com.simpleledger.app.ui.stats.StatsScreen
import com.simpleledger.app.ui.theme.paperTexture

/*
 * 应用外壳：窗口形态判定 + 4 槽一级导航 + 路由挂载。
 *
 * 「分区优先」重构要点（FR-06/07、同步点 #2）：
 * - 一级导航从 5 槽收敛为 **4 槽**：分区 · 明细 · 统计 · 我的，**分区升为首屏**（startDestination）；
 * - **移除底部中央凸起「＋」与大屏 Rail 的「记一笔」项**——记账入口统一收敛到
 *   「分区详情底部」与「明细页先选分区」两条路径，App 内不存在任何「跳过分区」的记账入口；
 * - `Routes` 改由独立文件 `ui/Routes.kt` 提供（唯一真源），本文件不再自带路由常量。
 */

/** 导航项：v4 起图标是**手绘 ImageVector**（原 emoji 已退场，见 docs/design/icons/） */
private data class NavItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

/** 一级导航（4 槽，顺序即 UI 顺序）：分区 · 明细 · 统计 · 我的 */
private val navItems = listOf(
    NavItem(Routes.SECTIONS, R.string.nav_sections, SlIcons.Nav.Section),
    NavItem(Routes.LEDGER, R.string.nav_ledger, SlIcons.Nav.Ledger),
    NavItem(Routes.STATS, R.string.nav_stats, SlIcons.Nav.Stats),
    NavItem(Routes.MINE, R.string.nav_mine, SlIcons.Nav.Mine),
)

/** 窗口尺寸类：基于**窗口宽度**判定（内容区会被 Navigation Rail 占宽，不能用作判据） */
enum class WindowLayout { Compact, Medium, Expanded }

/** 窗口尺寸类阈值（dp）：<600 手机 · 600–840 折叠屏 / 小平板 · ≥840 大屏 */
private val RAIL_THRESHOLD = 600.dp
private val EXPANDED_THRESHOLD = 840.dp

/**
 * 矮窗口阈值（dp）：低于此高度视为「紧凑高度」。
 *
 * 宽度决定**布局形态**（单栏 / Rail+单栏 / 列表–详情双栏），高度决定**信息密度**——
 * 两者正交。手机横屏（如 891×412dp）宽度足以触发 Rail，但可用高度只有 412dp，
 * 再用大屏的宽松尺寸会横向挤占本就稀缺的内容宽度，故此处单独收紧。
 */
private val DENSE_HEIGHT_THRESHOLD = 480.dp

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showNavigation = currentRoute in Routes.topLevel

    // 编辑态上提到这里：大屏的「记一笔」应打开列表页内的面板，而不是跳转路由。
    // 用 rememberSaveable 而非 remember：折叠屏展开/折叠、旋转都会触发配置变更，
    // 若 Activity 因故重建（例如系统回收或未覆盖的配置项），remember 会丢掉正在编辑的账目。
    var editingEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    // 与 editingEntryId 同生共死：编辑态所属分区（新建=入口带入；编辑=NEW_SECTION 由 VM 从账目读取）
    var editingSectionId by rememberSaveable { mutableStateOf(Routes.NEW_SECTION) }

    // 折叠态：真读 FoldingFeature（非分隔铰链已被过滤），带铰链矩形供明细页做避让计算
    val foldInfo = rememberFoldInfo()

    // v4 手账皮肤：纸底 + 极弱纸纹画在最外层，Scaffold 容器置透明透出它。
    // 放在最外层而不是 Scaffold 内部，是因为 Scaffold 会用自己的 containerColor 覆盖背景，
    // 把纸纹画在它里面就得给每个页面各加一次，且底部栏/侧边栏会各盖一块。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .paperTexture(),
    ) {
        val layout = when {
            maxWidth >= EXPANDED_THRESHOLD -> WindowLayout.Expanded
            maxWidth >= RAIL_THRESHOLD -> WindowLayout.Medium
            else -> WindowLayout.Compact
        }
        // 只影响尺寸参数，不翻转上面按宽度做的形态判定
        val dense = maxHeight < DENSE_HEIGHT_THRESHOLD
        // 水平铰链（上下分屏 / 桌面姿态）下左右双栏没有意义，降级为「Rail + 单栏」
        val effectiveLayout =
            if (foldInfo.state == FoldState.HorizontalFold && layout == WindowLayout.Expanded) {
                WindowLayout.Medium
            } else {
                layout
            }

        // 离开明细页时收起编辑面板，避免切回来时它又冒出来
        LaunchedEffect(currentRoute) {
            if (currentRoute != Routes.LEDGER) {
                editingEntryId = null
                editingSectionId = Routes.NEW_SECTION
            }
        }

        Scaffold(
            // 透明容器：纸底与纸纹由外层 BoxWithConstraints 提供，这里不再铺一层不透明色
            containerColor = Color.Transparent,
            bottomBar = {
                if (effectiveLayout == WindowLayout.Compact && showNavigation) {
                    LedgerBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = navController::navigateTopLevel,
                    )
                }
            },
        ) { innerPadding ->
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (effectiveLayout != WindowLayout.Compact && showNavigation) {
                    LedgerNavRail(
                        currentRoute = currentRoute,
                        expanded = effectiveLayout == WindowLayout.Expanded,
                        dense = dense,
                        onNavigate = navController::navigateTopLevel,
                    )
                }
                AppNavHost(
                    navController = navController,
                    layout = effectiveLayout,
                    dense = dense,
                    foldInfo = foldInfo,
                    editingEntryId = editingEntryId,
                    editingSectionId = editingSectionId,
                    onStartEdit = { id, sectionId ->
                        editingEntryId = id
                        editingSectionId = sectionId
                    },
                    onStopEdit = {
                        editingEntryId = null
                        editingSectionId = Routes.NEW_SECTION
                    },
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
    layout: WindowLayout,
    dense: Boolean,
    foldInfo: FoldInfo,
    editingEntryId: Long?,
    editingSectionId: Long,
    onStartEdit: (Long, Long) -> Unit,
    onStopEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SECTIONS, // 分区升首屏（FR-07）
        modifier = modifier,
    ) {
        // 分区首屏（默认落点）
        composable(Routes.SECTIONS) {
            SectionHomeScreen(
                onOpenSection = { sectionId -> navController.navigate(Routes.sectionDetail(sectionId)) },
                layout = layout,
            )
        }

        // 分区详情：按天分组账目 + 底部「记一笔」+ 右上「管理」
        composable(Routes.SECTION_DETAIL) { entry ->
            val sectionId = entry.arguments?.getString(Routes.ARG_SECTION_ID)?.toLongOrNull() ?: 0L
            SectionDetailScreen(
                sectionId = sectionId,
                resultHandle = entry.savedStateHandle,
                onBack = { navController.popBackStack() },
                onManage = { id -> navController.navigate(Routes.sectionManage(id)) },
                onCreateEntry = { id -> navController.navigate(Routes.entryEdit(Routes.NEW_ENTRY_ID, id)) },
                onEditEntry = { id -> navController.navigate(Routes.entryEdit(id)) },
                layout = layout,
                dense = dense,
            )
        }

        // 分区管理：编辑分区信息 + 该分区专属分类
        composable(Routes.SECTION_MANAGE) { entry ->
            val sectionId = entry.arguments?.getString(Routes.ARG_SECTION_ID)?.toLongOrNull() ?: 0L
            SectionManageScreen(
                sectionId = sectionId,
                onBack = { navController.popBackStack() },
                layout = layout,
            )
        }

        // 全局分类管理（从「我的 → 记账 → 全局分类」进入）
        composable(Routes.GLOBAL_CATEGORIES) {
            GlobalCategoriesScreen(
                onBack = { navController.popBackStack() },
                layout = layout,
            )
        }

        // 明细：跨分区总览 + 搜索 / 筛选，「记一笔」先弹分区选择器（Q-13）
        composable(Routes.LEDGER) { entry ->
            LedgerScreen(
                resultHandle = entry.savedStateHandle,
                onEditEntry = { id -> navController.navigate(Routes.entryEdit(id)) },
                onCreateEntry = { sectionId ->
                    navController.navigate(Routes.entryEdit(Routes.NEW_ENTRY_ID, sectionId))
                },
                onGoToSections = { navController.navigateTopLevel(Routes.SECTIONS) },
                layout = layout,
                dense = dense,
                foldInfo = foldInfo,
                editingEntryId = editingEntryId,
                editingSectionId = editingSectionId,
                onStartEdit = onStartEdit,
                onStopEdit = onStopEdit,
            )
        }

        // A3：统计页两栏网格 + 管理/我的页限宽，均以 Expanded 为唯一触发条件。
        // 这里传入的 `layout` 即 AppRoot 计算出的 effectiveLayout，故水平铰链降级同样作用于这几页，
        // 与 Rail / 明细页保持一致；非折叠设备上 effectiveLayout == 宽度判定结果，回归不变。
        composable(Routes.STATS) { StatsScreen(layout = layout) }
        composable(Routes.MINE) {
            MineScreen(
                layout = layout,
                onNavigateGlobalCategories = { navController.navigate(Routes.GLOBAL_CATEGORIES) },
            )
        }

        // 记一笔 / 编辑账目（Compact 全屏）——分区由入口带入，表单内只读（Q-07）
        composable(Routes.ENTRY_EDIT) { entry ->
            val entryId = entry.arguments?.getString(Routes.ARG_ENTRY_ID)?.toLongOrNull()
                ?: Routes.NEW_ENTRY_ID
            val sectionId = entry.arguments?.getString(Routes.ARG_SECTION_ID)?.toLongOrNull()
                ?: Routes.NEW_SECTION
            EntryEditScreen(
                entryId = entryId,
                sectionId = sectionId,
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
   手机：底部 4 槽位导航（已移除中央凸起「记一笔」，同步点 #2）
   ============================================================ */
@Composable
private fun LedgerBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val barHeight = 64.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // navigationBarsPadding 必须排在 height **之前**：它要撑在 64dp 内容高度之外。
            // 若写在 height 之后，手势条高度会从 64dp 内部被扣掉，内容区只剩约 40dp，
            // 放不下 emoji(20sp)+标签(10.5sp)，标签会被整条裁掉（只看得见图标）。
            // 从五槽改四槽时曾丢掉旧版的 `height(barHeight + 20.dp)` 缓冲，即由此产生。
            .navigationBarsPadding()
            .height(barHeight),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {}

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            navItems.forEach { item ->
                BottomBarSlot(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onNavigate(item.route) },
                )
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
                // v4：emoji → 手绘图标；选中/未选中沿用原有透明度表达（不新增变色维度）
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier
                        .size(22.dp)
                        .alpha(if (selected) 1f else 0.72f),
                )
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
   大屏：Navigation Rail（4 项，已移除「记一笔」，同步点 #2）
   ============================================================ */
@Composable
private fun LedgerNavRail(
    currentRoute: String?,
    expanded: Boolean,
    dense: Boolean,
    onNavigate: (String) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        // 矮窗口（手机横屏 / 折叠半折）退回 84dp：加宽 Rail 换来的文字标签，
        // 抵不上它横向吃掉的账目内容宽度
        modifier = Modifier.width(if (expanded && !dense) 108.dp else 84.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        navItems.forEach { item ->
            NavigationRailItem(
                selected = currentRoute == item.route,
                onClick = { onNavigate(item.route) },
                icon = {
                    // v4：emoji → 手绘图标；选中色由 NavigationRailItemDefaults 统一给
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                    )
                },
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
