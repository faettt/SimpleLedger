package com.simpleledger.app.ui.ledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.ui.FoldInfo
import com.simpleledger.app.ui.FoldState
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.hingeConstrainedWidth
import com.simpleledger.app.ui.hingeDetailInsetPx
import com.simpleledger.app.ui.entry.EntryEditHost
import com.simpleledger.app.ui.entry.EntryEditHostStyle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 保存结果通过 SavedStateHandle 回传给明细页 */
const val RESULT_SAVED_ENTRY_ID = "result_saved_entry_id"

/** 编辑态传 NEW_ENTRY_ID 表示「记一笔」，否则为要编辑的账目 id */
const val NEW_ENTRY_ID = -1L

/** 列表栏与竖直铰链之间保留的安全边距：内容贴到折痕上会明显增加误读 */
private val HINGE_SAFE_GAP = 8.dp

/** 列表栏在铰链避让下的最小可读宽度：再窄下去账目行就不是「被压窄」而是「不可用」 */
private val MIN_LIST_PANE_WIDTH = 280.dp

/** 双栏之间分隔线宽度：既是栏位边界的视觉提示，也计入详情栏起点的坐标 */
private val PANE_DIVIDER_WIDTH = 1.dp

/**
 * 明细页。
 *
 * 编辑账目的承载形态随窗口变化：
 * - Compact（手机）：跳转到全屏路由
 * - Medium（600–840dp）：列表页内的居中浮层
 * - Expanded（≥840dp）：与列表并列的右侧面板
 *
 * 判定必须基于**窗口宽度**——内容区已被 Navigation Rail 占去约 108dp，
 * 若在内容区里再判断宽度，双栏与浮层都会永远触发不了。
 *
 * 本文件只保留页面骨架（几何避让 + 形态分派）；列表栏、详情栏、筛选面板、账目行
 * 已分别拆分到 LedgerListPane.kt / LedgerDetailPane.kt / LedgerFilterSheet.kt / LedgerRows.kt，
 * 同包同名，拆分纯属搬移、行为不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    resultHandle: SavedStateHandle,
    onEditEntry: (Long) -> Unit,
    layout: WindowLayout = WindowLayout.Compact,
    dense: Boolean = false,
    foldInfo: FoldInfo = FoldInfo(),
    editingEntryId: Long? = null,
    onStartEdit: (Long) -> Unit = {},
    onStopEdit: () -> Unit = {},
    viewModel: LedgerViewModel = viewModel(factory = LedgerViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selectedEntry.collectAsState()
    val searchActive by viewModel.searchActive.collectAsState()
    val searchKeyword by viewModel.searchKeyword.collectAsState()
    val searchFilterType by viewModel.searchFilterType.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searchTruncated by viewModel.searchTruncated.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilterSheet by remember { mutableStateOf(false) }

    // 保存成功 → 提示「已记入 …」并提供撤销（可撤销的反馈比二次确认更高效）
    suspend fun showSavedNotice(entryId: Long) {
        val label = viewModel.describeEntry(entryId) ?: return
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.saved_toast, label),
            actionLabel = context.getString(R.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete(entryId)
        }
    }

    LaunchedEffect(Unit) {
        resultHandle.getStateFlow(RESULT_SAVED_ENTRY_ID, -1L).collect { entryId ->
            if (entryId > 0) {
                resultHandle[RESULT_SAVED_ENTRY_ID] = -1L
                showSavedNotice(entryId)
            }
        }
    }

    /* ---------- 长按快捷操作 ---------- */

    suspend fun duplicateWithNotice(entryId: Long) {
        val newId = viewModel.duplicateEntry(entryId)
        snackbarHostState.showSnackbar(
            message = if (newId != null) "已复制一笔（时间改为此刻）" else "复制失败",
            duration = SnackbarDuration.Short,
        )
    }

    suspend fun moveWithNotice(entryId: Long, sectionId: Long) {
        val moved = viewModel.moveEntryToSection(entryId, sectionId)
        val name = state.sections.firstOrNull { it.id == sectionId }?.name.orEmpty()
        snackbarHostState.showSnackbar(
            message = if (moved) "已移动到「$name」" else "移动失败",
            duration = SnackbarDuration.Short,
        )
    }

    /**
     * 列表级删除：不弹确认框，直接删除并给 4 秒撤销（设计规格 §07）。
     * 贴图文件在撤销窗口内先移入暂存区，因此「撤销」能完整恢复这笔账目。
     */
    suspend fun deleteWithUndo(entryId: Long) {
        val label = viewModel.describeEntry(entryId)
        val snapshot = viewModel.deleteEntryWithSnapshot(entryId) ?: return
        val result = snackbarHostState.showSnackbar(
            message = "已删除${if (label != null) " $label" else ""}",
            actionLabel = context.getString(R.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.restoreDeleted(snapshot)
            snackbarHostState.showSnackbar("已恢复", duration = SnackbarDuration.Short)
        } else {
            viewModel.discardParkedImages()
        }
    }

    val filtersActive = state.filters.sectionId != null ||
        state.filters.categoryId != null ||
        state.filters.type != null

    val twoPane = layout == WindowLayout.Expanded
    val inPlaceEdit = layout != WindowLayout.Compact

    /* ---------- 折叠屏竖直铰链避让 ----------
     * 原则：宽度决定形态，高度决定密度，折叠态修正「内容落点」。
     *
     * 竖直铰链下用户在物理上是两块独立的竖屏，列表栏若越过铰链就会被折痕切成两半。
     * 关键在于：`FoldingFeature.bounds` 与 `Modifier.positionInWindow()` **同属窗口坐标**，
     * 因此列表栏的实际左偏移可与铰链左边界直接相减——既不用猜窗口中线，也不用硬编码
     * Navigation Rail 的宽度（Rail 宽度随 Expanded / dense 变化，写死必然出错）。
     * 左偏移首帧尚未测得（为 0），此时不做避让，下一帧测得后收敛。
     */
    val density = LocalDensity.current
    var listPaneLeftPx by remember { mutableIntStateOf(0) }
    val hingeLeftPx = foldInfo.hingeBounds?.left
    // 双栏时列表栏的基础宽度：矮窗口收紧到 340dp
    val basePaneWidth = if (dense) 340.dp else 400.dp
    /* 是否施加铰链约束由调用方判定，几何夹取交给纯函数 hingeConstrainedWidth（见 WindowMetrics.kt）。
     * 判定条件里的 `listPaneLeftPx > 0`（左偏移尚未测得）属于 UI 生命周期状态而非几何事实，
     * 故刻意留在调用方，不并入纯函数。纯函数本身只做 px 算术，因此可被 JVM 单测直接覆盖。
     * 与抽函数前逐条等价：同一条件 `VerticalFold && hingeLeftPx != null && leftPx > 0`；
     * 同一夹取链 `min(可用宽度, base)` 再 `max(·, 可读下限)`；无约束时返回 basePaneWidth。 */
    val listPaneWidth: Dp = if (
        foldInfo.state == FoldState.VerticalFold && hingeLeftPx != null && listPaneLeftPx > 0
    ) {
        with(density) {
            hingeConstrainedWidth(
                // 铰链左边界之前可占用的宽度 = 边界 − 列表栏左偏移 − 安全边距，再夹到 [下限, base]
                hingeLeftPx = hingeLeftPx,
                paneLeftPx = listPaneLeftPx,
                gapPx = HINGE_SAFE_GAP.toPx(),
                baseWidthPx = basePaneWidth.toPx(),
                minWidthPx = MIN_LIST_PANE_WIDTH.toPx(),
            ).toDp()
        }
    } else {
        // 无铰链 / 水平铰链（上层已降级） / 尚未测量：不做限制
        basePaneWidth
    }

    /* ---------- 详情栏内容避让铰链 ----------
     * 上面只夹了左侧列表栏，右侧详情栏仍会跨过铰链，故给它加左侧内边距把**内容**推到铰链右侧
     * （栏位背景允许跨折痕，内容不可被折痕切开）。详情栏起点 = 列表栏左偏移 + 列表栏宽度 + 分隔线宽。
     * 门控与列表栏避让逐字一致；非竖直铰链 / 非折叠 / 尚未测量时一律为 0.dp，行为不变。
     */
    val hingeRightPx = foldInfo.hingeBounds?.right
    val detailPaneInset: Dp = if (
        foldInfo.state == FoldState.VerticalFold && hingeRightPx != null && listPaneLeftPx > 0
    ) {
        with(density) {
            hingeDetailInsetPx(
                hingeRightPx = hingeRightPx,
                detailPaneLeftPx = listPaneLeftPx + listPaneWidth.toPx() + PANE_DIVIDER_WIDTH.toPx(),
            ).toDp()
        }
    } else {
        0.dp
    }

    // 实测列表栏 / 搜索栏在窗口中的左偏移，供上面的铰链避让使用。
    // 仅在值变化时写回状态，避免 onGloballyPositioned 触发无休止重组。
    val listPaneAnchor = Modifier.onGloballyPositioned { coordinates ->
        val left = coordinates.positionInWindow().x.roundToInt()
        if (left != listPaneLeftPx) listPaneLeftPx = left
    }

    // 搜索态下返回键先退出搜索，而不是退出明细页（否则一次误触就丢了整个搜索上下文）
    BackHandler(enabled = searchActive) { viewModel.closeSearch() }

    // 搜索结果点击：与列表态走同一条编辑路径——大屏 in-place 宿主，手机跳全屏路由
    val onSearchResultClick: (Long) -> Unit = { id ->
        if (inPlaceEdit) onStartEdit(id) else onEditEntry(id)
    }

    // Medium 的居中编辑浮层：搜索态与列表态共用同一外壳，保证编辑体验一致
    val centeredEditHost: @Composable () -> Unit = {
        if (inPlaceEdit && editingEntryId != null) {
            EntryEditHost(
                entryId = editingEntryId,
                style = EntryEditHostStyle.Centered,
                dense = dense,
                snackbarHostState = snackbarHostState,
                onDismiss = onStopEdit,
                onSaved = { savedId ->
                    onStopEdit()
                    savedId?.let { scope.launch { showSavedNotice(it) } }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (twoPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (searchActive) {
                        LedgerSearchPane(
                            keyword = searchKeyword,
                            filterType = searchFilterType,
                            results = searchResults,
                            truncated = searchTruncated,
                            sections = state.sections,
                            onQueryChange = viewModel::setSearchQuery,
                            onToggleType = viewModel::toggleSearchType,
                            onBack = viewModel::closeSearch,
                            onResultClick = onSearchResultClick,
                            onDuplicate = { id -> scope.launch { duplicateWithNotice(id) } },
                            onMoveTo = { id, sectionId -> scope.launch { moveWithNotice(id, sectionId) } },
                            onDelete = { id -> scope.launch { deleteWithUndo(id) } },
                            modifier = Modifier.width(listPaneWidth).then(listPaneAnchor),
                        )
                    } else {
                        LedgerListPane(
                            state = state,
                            filtersActive = filtersActive,
                            viewModel = viewModel,
                            selectedEntryId = selected?.entry?.id,
                            onRowClick = { id -> viewModel.selectEntry(id) },
                            onFilterClick = { showFilterSheet = true },
                            onSearchClick = viewModel::openSearch,
                            onDuplicate = { id -> scope.launch { duplicateWithNotice(id) } },
                            onMoveTo = { id, sectionId -> scope.launch { moveWithNotice(id, sectionId) } },
                            onDelete = { id -> scope.launch { deleteWithUndo(id) } },
                            modifier = Modifier.width(listPaneWidth).then(listPaneAnchor),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(PANE_DIVIDER_WIDTH)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            // 竖直铰链下把详情栏内容推到铰链右侧；其余情况 detailPaneInset = 0.dp
                            .padding(start = detailPaneInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (editingEntryId != null) {
                            // 右侧面板与列表并列：不遮挡，可边看已有账目边录
                            EntryEditHost(
                                entryId = editingEntryId,
                                style = EntryEditHostStyle.Panel,
                                dense = dense,
                                snackbarHostState = snackbarHostState,
                                onDismiss = onStopEdit,
                                onSaved = { savedId ->
                                    onStopEdit()
                                    savedId?.let { scope.launch { showSavedNotice(it) } }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            EntryDetailPane(
                                full = selected,
                                onEdit = { id -> onStartEdit(id) },
                                onDelete = { id -> scope.launch { deleteWithUndo(id) } },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            } else {
                if (searchActive) {
                    LedgerSearchPane(
                        keyword = searchKeyword,
                        filterType = searchFilterType,
                        results = searchResults,
                        truncated = searchTruncated,
                        sections = state.sections,
                        onQueryChange = viewModel::setSearchQuery,
                        onToggleType = viewModel::toggleSearchType,
                        onBack = viewModel::closeSearch,
                        onResultClick = onSearchResultClick,
                        onDuplicate = { id -> scope.launch { duplicateWithNotice(id) } },
                        onMoveTo = { id, sectionId -> scope.launch { moveWithNotice(id, sectionId) } },
                        onDelete = { id -> scope.launch { deleteWithUndo(id) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LedgerListPane(
                        state = state,
                        filtersActive = filtersActive,
                        viewModel = viewModel,
                        selectedEntryId = null,
                        onRowClick = { id -> if (inPlaceEdit) onStartEdit(id) else onEditEntry(id) },
                        onFilterClick = { showFilterSheet = true },
                        onSearchClick = viewModel::openSearch,
                        onDuplicate = { id -> scope.launch { duplicateWithNotice(id) } },
                        onMoveTo = { id, sectionId -> scope.launch { moveWithNotice(id, sectionId) } },
                        onDelete = { id -> scope.launch { deleteWithUndo(id) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                centeredEditHost()
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            FilterSheetContent(
                state = state,
                onTypeChange = viewModel::filterType,
                onCategoryChange = viewModel::filterCategory,
                onClearAll = viewModel::clearFilters,
                onDismiss = { showFilterSheet = false },
            )
        }
    }
}