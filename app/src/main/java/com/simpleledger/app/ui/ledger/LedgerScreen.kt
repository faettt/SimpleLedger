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
import com.simpleledger.app.ui.Routes
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.hingeConstrainedWidth
import com.simpleledger.app.ui.hingeDetailInsetPx
import com.simpleledger.app.ui.entry.EntryEditHost
import com.simpleledger.app.ui.entry.EntryEditHostStyle
import com.simpleledger.app.ui.section.SectionPickerDialog
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color

/** 保存结果通过 SavedStateHandle 回传给明细页 / 分区详情页 */
const val RESULT_SAVED_ENTRY_ID = "result_saved_entry_id"

/** 编辑态传 [Routes.NEW_ENTRY_ID] 表示「记一笔」，否则为要编辑的账目 id */
const val NEW_ENTRY_ID = Routes.NEW_ENTRY_ID

/** 列表栏与竖直铰链之间保留的安全边距：内容贴到折痕上会明显增加误读 */
private val HINGE_SAFE_GAP = 8.dp

/** 列表栏在铰链避让下的最小可读宽度：再窄下去账目行就不是「被压窄」而是「不可用」 */
private val MIN_LIST_PANE_WIDTH = 280.dp

/** 双栏之间分隔线宽度：既是栏位边界的视觉提示，也计入详情栏起点的坐标 */
private val PANE_DIVIDER_WIDTH = 1.dp

/**
 * 明细页（FR-28/29/30）。
 *
 * 角色 = **跨分区总览 + 搜索 / 筛选入口**（默认「全部分区」，保留搜索与筛选）。
 * 「记一笔」入口**先弹分区选择器**（Q-13），选定后才能进入表单——不存在任何「跳过分区」的记账路径。
 *
 * 编辑账目的承载形态随窗口变化：
 * - Compact（手机）：跳转到全屏路由
 * - Medium（600–840dp）：列表页内的居中浮层
 * - Expanded（≥840dp）：与列表并列的右侧面板
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    resultHandle: SavedStateHandle,
    onEditEntry: (Long) -> Unit,
    onCreateEntry: (Long) -> Unit,
    onGoToSections: () -> Unit,
    layout: WindowLayout = WindowLayout.Compact,
    dense: Boolean = false,
    foldInfo: FoldInfo = FoldInfo(),
    editingEntryId: Long? = null,
    editingSectionId: Long = Routes.NEW_SECTION,
    onStartEdit: (Long, Long) -> Unit = { _, _ -> },
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
    var showSectionPicker by remember { mutableStateOf(false) }

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
            message = if (newId != null) {
                context.getString(R.string.duplicate_done)
            } else {
                context.getString(R.string.duplicate_failed)
            },
            duration = SnackbarDuration.Short,
        )
    }

    suspend fun moveWithNotice(entryId: Long, sectionId: Long, newCategoryId: Long?) {
        val moved = viewModel.moveEntryToSection(entryId, sectionId, newCategoryId)
        val name = state.sections.firstOrNull { it.id == sectionId }?.name.orEmpty()
        snackbarHostState.showSnackbar(
            message = if (moved) {
                context.getString(R.string.move_done, name)
            } else {
                context.getString(R.string.move_failed)
            },
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
            message = if (label != null) {
                context.getString(R.string.delete_done_named, label)
            } else {
                context.getString(R.string.delete_done)
            },
            actionLabel = context.getString(R.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.restoreDeleted(snapshot)
            snackbarHostState.showSnackbar(context.getString(R.string.restored), duration = SnackbarDuration.Short)
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
     * 左偏移首帧尚未测得（为 0），此时不做避让，下一帧测得后收敛。
     */
    val density = LocalDensity.current
    var listPaneLeftPx by remember { mutableIntStateOf(0) }
    val hingeLeftPx = foldInfo.hingeBounds?.left
    // 双栏时列表栏的基础宽度：矮窗口收紧到 340dp
    val basePaneWidth = if (dense) 340.dp else 400.dp
    val listPaneWidth: Dp = if (
        foldInfo.state == FoldState.VerticalFold && hingeLeftPx != null && listPaneLeftPx > 0
    ) {
        with(density) {
            hingeConstrainedWidth(
                hingeLeftPx = hingeLeftPx,
                paneLeftPx = listPaneLeftPx,
                gapPx = HINGE_SAFE_GAP.toPx(),
                baseWidthPx = basePaneWidth.toPx(),
                minWidthPx = MIN_LIST_PANE_WIDTH.toPx(),
            ).toDp()
        }
    } else {
        basePaneWidth
    }

    /* ---------- 详情栏内容避让铰链 ---------- */
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

    val listPaneAnchor = Modifier.onGloballyPositioned { coordinates ->
        val left = coordinates.positionInWindow().x.roundToInt()
        if (left != listPaneLeftPx) listPaneLeftPx = left
    }

    // 搜索态下返回键先退出搜索，而不是退出明细页
    BackHandler(enabled = searchActive) { viewModel.closeSearch() }

    // 搜索结果点击：与列表态走同一条编辑路径
    val onSearchResultClick: (Long) -> Unit = { id ->
        if (inPlaceEdit) onStartEdit(id, Routes.NEW_SECTION) else onEditEntry(id)
    }

    // Q-13：明细页「记一笔」→ 先弹分区选择器（不可跳过），选定后进入表单
    val startRecord: () -> Unit = { showSectionPicker = true }

    val centeredEditHost: @Composable () -> Unit = {
        if (inPlaceEdit && editingEntryId != null) {
            EntryEditHost(
                entryId = editingEntryId,
                sectionId = editingSectionId,
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

    Scaffold(

        containerColor = Color.Transparent,

        snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
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
                            allCategories = state.categories,
                            onQueryChange = viewModel::setSearchQuery,
                            onToggleType = viewModel::toggleSearchType,
                            onBack = viewModel::closeSearch,
                            onResultClick = onSearchResultClick,
                            onDuplicate = { id -> scope.launch { duplicateWithNotice(id) } },
                            onMoveTo = { id, sectionId, newCategoryId -> scope.launch { moveWithNotice(id, sectionId, newCategoryId) } },
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
                            onRecord = startRecord,
                            onFilterClick = { showFilterSheet = true },
                            onSearchClick = viewModel::openSearch,
                            onGoToSections = onGoToSections,
                            onDuplicate = { id -> scope.launch { duplicateWithNotice(id) } },
                            onMoveTo = { id, sectionId, newCategoryId -> scope.launch { moveWithNotice(id, sectionId, newCategoryId) } },
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
                            .padding(start = detailPaneInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (editingEntryId != null) {
                            EntryEditHost(
                                entryId = editingEntryId,
                                sectionId = editingSectionId,
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
                                onEdit = { id -> onStartEdit(id, Routes.NEW_SECTION) },
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
                        allCategories = state.categories,
                        onQueryChange = viewModel::setSearchQuery,
                        onToggleType = viewModel::toggleSearchType,
                        onBack = viewModel::closeSearch,
                        onResultClick = onSearchResultClick,
                        onDuplicate = { id -> scope.launch { duplicateWithNotice(id) } },
                        onMoveTo = { id, sectionId, newCategoryId -> scope.launch { moveWithNotice(id, sectionId, newCategoryId) } },
                        onDelete = { id -> scope.launch { deleteWithUndo(id) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LedgerListPane(
                        state = state,
                        filtersActive = filtersActive,
                        viewModel = viewModel,
                        selectedEntryId = null,
                        onRowClick = { id -> if (inPlaceEdit) onStartEdit(id, Routes.NEW_SECTION) else onEditEntry(id) },
                        onRecord = startRecord,
                        onFilterClick = { showFilterSheet = true },
                        onSearchClick = viewModel::openSearch,
                        onGoToSections = onGoToSections,
                        onDuplicate = { id -> scope.launch { duplicateWithNotice(id) } },
                        onMoveTo = { id, sectionId, newCategoryId -> scope.launch { moveWithNotice(id, sectionId, newCategoryId) } },
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

    if (showSectionPicker) {
        SectionPickerDialog(
            sections = state.sections,
            onDismiss = { showSectionPicker = false },
            onPick = { sectionId ->
                showSectionPicker = false
                if (inPlaceEdit) onStartEdit(Routes.NEW_ENTRY_ID, sectionId) else onCreateEntry(sectionId)
            },
            onGoToSections = onGoToSections,
        )
    }
}
