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
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable

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
/**
 * 手机：底部**索引贴**导航（M3）。
 *
 * 形态取自手账本侧边的索引贴：4 个色签并排，选中那个向上凸出 12dp。
 * 规范依据 docs/design/journal-style-spec-2026-09-20.md §2.1：
 *
 * · 未选中视觉高 42dp / 选中 54dp（凸出 12dp）。**选中态不止变色，还有形变**
 *   —— 形变比变色更有信息量，这也是整屏最"手账"的地方。
 * · 圆角只做上两角 7dp：下方与屏幕底边平齐，像签从纸边伸出来。
 * · **触控目标 ≥48dp**：签的视觉高度只有 42dp 不达标，因此外层 Box 撑满 54dp 承接点击，
 *   视觉签贴在底部居中。这条不满足就会踩无障碍红线。
 * · 签之间留 3dp 缝隙露出底栏底色，读作"4 张独立的签"而不是一条连续色带。
 *
 * ⚠️ **三条 Modifier 顺序规则，都不能动**（前两条是真机走查实测踩出来的）：
 *
 *  1. `background` 必须在 `navigationBarsPadding` **之前**。
 *     写成 `.padding().height().background()` 时 background 位于链的最内层，只铺满
 *     `height` 那 54dp —— insets 撑出来的 24dp（手势）/ 48dp（三键）完全没铺到，
 *     露出的是下面一层（手势模式＝页面纸、三键模式＝系统导航栏的浅色）。
 *     实测：节点实际高 102dp，背景只覆盖 53.7dp；调序后覆盖到 0.0dp 屏底。
 *  2. `navigationBarsPadding` 必须在 `height` **之前**。写在之后，手势条高度会从固定
 *     高度**内部**被扣掉，内容区被压缩、标签被整条裁掉——表现为"只看得见图标"，
 *     且 TalkBack 读不出页面名。
 *  3. 顶边分隔线画在 `background` 之后（draw modifier 按链序叠，先写的在下）。
 *
 * 底栏底色＝页面纸 `background`，与内容区**同色**，靠顶边那道 0.5dp 细线分界
 * （手账里就是页面下方拉的一道线）。这样做的原因：选中签要用 `primaryContainer`
 * （＝凹面 `#EFE9DC`）来表达"整块卡片换色"，底栏若也用凹面，选中签就会与底栏
 * 融为一体、选中态消失。腾出凹面给选中签，是这套配色成立的前提。
 */
@Composable
private fun LedgerBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val selectedHeight = 54.dp
    val unselectedHeight = 42.dp
    // 分隔线色必须**先在这里读出来**：drawBehind 的 lambda 是 DrawScope，
    // 不是 @Composable 上下文，在里头读 MaterialTheme.colorScheme 会直接编译失败
    // （@Composable invocations can only happen from the context of a @Composable function）。
    val barRuleColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // ① 底栏底色铺满整个节点（含 insets 撑出的那一段），一直连到屏幕最底
            .background(MaterialTheme.colorScheme.background)
            // ② 与内容区的分界：0.5dp 细线横贯全宽。底栏与页面同色时，这是唯一的分界
            .drawBehind { drawRect(color = barRuleColor, size = Size(size.width, 0.5.dp.toPx())) }
            // ③ insets 撑在固定高度**之外**，不参与高度扣减（见上方规则 2）
            .navigationBarsPadding()
            .height(selectedHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                // 16dp 对齐页面内容边距（硬规则「页面边距 16/24/32」）；
                // 原来是 6dp，签几乎贴到屏幕边，在圆角屏上会被裁掉。
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            navItems.forEach { item ->
                IndexTab(
                    item = item,
                    selected = currentRoute == item.route,
                    selectedHeight = selectedHeight,
                    unselectedHeight = unselectedHeight,
                    onClick = { onNavigate(item.route) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 单个索引签。
 *
 * **选中态不用任何"标记"元素**（2026-09-21 改）。此前在签顶边压了一道 2dp 主色实心横条，
 * 用户反馈"有些 AI 感"——诊断下来是因为**它是整套设计里唯一用「屏幕控件语言」表达的东西**：
 * 纯色横条横贯签宽、两端硬切，正是 Material `TabRow` indicator 的形态；
 * 而其余元素（纸片、0.5dp 描边、纸纹、手绘图标）都在说"纸与笔"。
 *
 * 去掉后选中态由 5 个信号共同承担，**全部属于「纸与字」的语言**，选中感并不因此变弱
 * （其中①是强形变）：
 *
 * | | 信号 | 语言 |
 * |---|---|---|
 * | ① | 凸出 12dp | 纸 —— 索引贴被翻到前面 |
 * | ② | 填充 凹面 / 纸片 | 纸 —— 三层纸的明暗 |
 * | ③ | 内容色 主墨 / 次墨 | 字 |
 * | ④ | 字重 SemiBold / Normal | 字 |
 * | ⑤ | 图标 22dp / 20dp | 字 |
 *
 * ⚠️ 配色**不可反转**（选中＝亮纸片）：未选中签若改用凹面填充，其内容色次墨 `#55736B`
 * 在凹面 `#EFE9DC` 上的对比度只有 **4.28:1**，低于硬规则「正文 ≥4.5:1」。
 * 现有配对之所以成立，正是因为**深色填充配深色内容**（主墨 9.48:1）、
 * **浅色填充配浅色内容**（次墨 5.01:1）—— 反转会把这个配对拆散。
 */
@Composable
private fun IndexTab(
    item: NavItem,
    selected: Boolean,
    selectedHeight: Dp,
    unselectedHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)
    val accent = MaterialTheme.colorScheme.primary
    val color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            // 触控区撑满整条栏高（54dp ≥ 48dp）；视觉签再贴到底部
            .fillMaxHeight()
            .clickable(onClick = onClick)
            // 语义：告诉读屏这是标签页且当前选中 —— 仅靠文字节点读不出"选中了几号"
            .semantics {
                role = Role.Tab
                this.selected = selected
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (selected) selectedHeight else unselectedHeight)
                .clip(shape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                    shape,
                )
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                // ⚠️ 内容定位两条规则（padding 必须在 border **之后**，否则纸片本身会被缩掉）：
                //
                // ① 选中签内容下移 12dp：**四签的图标/文字同高**。
                //    实测（像素）：不这样做时，选中签内容底距纸片底 8.4dp、
                //    未选中签只有 0.8dp —— 未选中的文字贴着纸片下边缘，观感「掉下去」。
                //    下移后两者都是 2.3dp，四签文字落在同一水平线上。
                //    凸出的 12dp 变成空白纸 —— 这正是索引贴的样子：
                //    被抽出来的那张，露出的是没有字的纸头。
                //
                // ② 底部留 3dp：让文字不贴纸片底边（未选中签 42dp 里内容占 37dp，
                //    居中后底部只剩 0.8dp）。
                //
                // 两条合起来的效果：选中/未选中的内容区**同为 39dp**，内容在同一位置居中。
                .padding(
                    top = if (selected) selectedHeight - unselectedHeight else 0.dp,
                    bottom = 3.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(if (selected) 22.dp else 20.dp),
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = stringResource(item.labelRes),
                fontSize = 10.5.sp,
                color = color,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
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
