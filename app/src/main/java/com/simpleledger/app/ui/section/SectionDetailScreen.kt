package com.simpleledger.app.ui.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.logic.SectionDetailFilterPlan
import com.simpleledger.app.ui.Routes
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.slFilterChipColors
import com.simpleledger.app.ui.entry.EntryEditHost
import com.simpleledger.app.ui.entry.EntryEditHostStyle
import com.simpleledger.app.ui.ledger.DayHeader
import com.simpleledger.app.ui.ledger.EntryRow
import com.simpleledger.app.ui.ledger.RESULT_SAVED_ENTRY_ID
import com.simpleledger.app.ui.slSharedSectionHeader
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.slAnimateItem
import com.simpleledger.app.ui.theme.SlButtonShape
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.icon.slCategoryIcon
import androidx.compose.ui.graphics.Color
import com.simpleledger.app.ui.components.SlSnackbarHost
import com.simpleledger.app.ui.components.slTitleRule
import java.time.YearMonth

/**
 * 分区详情（FR-16~20）。
 *
 * - 顶部：返回 + 「emoji 分区名」+ 右上「管理」
 * - 筛选条：时间窗「全部」chip + 月份入口 / ‹ 月 › 步进（默认「全部时间」，任务书裁定原文）
 * - 列表：按天分组的该分区账目（Q-12，复用 [DayHeader] / [EntryRow]）；全部模式插月份分隔头
 * - 底部：「记一笔」（拇指可达）
 *
 * 承载形态随窗口变化（同步点 #7）：Compact 走全屏路由；Medium 居中浮层；Expanded 右侧面板。
 * 保存后**停留**在分区详情（EC-11d），可连续记账；列表与首屏卡片数据源同为 Flow，自动刷新（EC-11b）。
 * 月筛选下保存 / 复制「账目落哪月筛哪月」（仅月筛选生效，裁定见
 * [SectionDetailFilterPlan.landTarget]）；离开页面筛选复位「全部」（活在 VM）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionDetailScreen(
    sectionId: Long,
    resultHandle: SavedStateHandle,
    onBack: () -> Unit,
    onManage: (Long) -> Unit,
    onCreateEntry: (Long) -> Unit,
    onEditEntry: (Long) -> Unit,
    layout: WindowLayout = WindowLayout.Compact,
    dense: Boolean = false,
    viewModel: SectionDetailViewModel = viewModel(
        key = "section_detail_$sectionId",
        factory = SectionDetailViewModel.factory(sectionId),
    ),
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val inPlaceEdit = layout != WindowLayout.Compact
    var editingEntryId by rememberSaveable { mutableStateOf<Long?>(null) }

    suspend fun showSavedNotice(entryId: Long) {
        // 「账目落哪月筛哪月」：月模式下跟随账目落月（编辑改期跟去新月 / 保存同月空操作）；
        // 全部模式不跳（landTarget null 分支裁定，见 SectionDetailFilterPlan.landTarget）
        viewModel.entryMonthOf(entryId)?.let { viewModel.landOn(it) }
        val label = viewModel.describeEntry(entryId) ?: return
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.saved_toast, label),
            actionLabel = context.getString(R.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            runCatching { viewModel.deleteEntryWithSnapshot(entryId) }
        }
    }

    // Compact：全屏记账页保存后回到本页——读取回传的 entryId 并显示「已记入…」+撤销（EC-11d）
    LaunchedEffect(Unit) {
        resultHandle.getStateFlow(RESULT_SAVED_ENTRY_ID, -1L).collect { savedId ->
            if (savedId > 0) {
                resultHandle[RESULT_SAVED_ENTRY_ID] = -1L
                showSavedNotice(savedId)
            }
        }
    }

    suspend fun duplicateWithNotice(entryId: Long) {
        val newId = viewModel.duplicateEntry(entryId)
        // 复制的时间改为此刻：月筛选下跟随落月（通常即当月 = 空操作）；全部模式不跳
        if (newId != null) viewModel.entryMonthOf(newId)?.let { viewModel.landOn(it) }
        snackbarHostState.showSnackbar(
            message = context.getString(
                if (newId != null) R.string.duplicate_done else R.string.duplicate_failed
            ),
            duration = SnackbarDuration.Short,
        )
    }

    suspend fun deleteWithUndo(entryId: Long) {
        val snapshot = viewModel.deleteEntryWithSnapshot(entryId) ?: return
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.delete_done),
            actionLabel = context.getString(R.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            // P1 修复：恢复失败不得谎报「已恢复」——如实提示并指向回收站
            val restored = viewModel.restoreDeleted(snapshot)
            snackbarHostState.showSnackbar(
                context.getString(
                    if (restored) R.string.restored else R.string.undo_restore_failed,
                ),
                duration = SnackbarDuration.Short,
            )
        } else {
            viewModel.discardParkedImages()
        }
    }

    val startCreate = {
        if (inPlaceEdit) editingEntryId = Routes.NEW_ENTRY_ID else onCreateEntry(sectionId)
    }
    val rowClick: (Long) -> Unit = { id ->
        if (inPlaceEdit) editingEntryId = id else onEditEntry(id)
    }

    // 显式回顶：仅筛选值真的变化时拽顶（切月 / 回全部）。标记刻意用 remember 而非
    // rememberSaveable——进程死亡重建时 VM 的 monthFilter 复位 null、标记初值同为 null，
    // 两者相等即跳过滚动，rememberLazyListState 内部 saver 恢复的阅读位置得以保留。
    val listState = rememberLazyListState()
    var lastFilterKey by remember { mutableStateOf<YearMonth?>(null) }
    LaunchedEffect(state.monthFilter) {
        if (state.monthFilter != lastFilterKey) {
            lastFilterKey = state.monthFilter
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        // ⚠️ 外层 AppRoot 已消费 systemBars insets，内层归零防双重避让（详见 SectionHomeScreen 注释）
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
        snackbarHost = { SlSnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            SlIcons.Ui.ArrowLeft,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    // v4：标题栏改「图标 + 分区名」（设计 §2.3），emoji 退场
                    // Container Transform 落点：与 SectionCard 头部行同一共享键，
                    // 返回时详情页眉飞回卡片原位（详见 SlSharedTransition.kt）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .slSharedSectionHeader("section-header-$sectionId"),
                    ) {
                        state.section?.let {
                            Icon(
                                imageVector = slCategoryIcon(it.iconId),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = state.section?.name ?: "",
                            // 分区名装饰位：headlineK（楷体 Regular）+ 页眉双线（规范 §2.3）
                            style = SlType.headline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.slTitleRule(),
                        )
                    }
                    TextButton(onClick = { onManage(sectionId) }) {
                        Text(stringResource(R.string.section_manage))
                    }
                }

                SectionSummary(
                    expenseCents = state.expenseCents,
                    incomeCents = state.incomeCents,
                    monthFilter = state.monthFilter,
                    allTimeExpenseCents = state.allTimeExpenseCents,
                    allTimeIncomeCents = state.allTimeIncomeCents,
                    thisMonthExpenseCents = state.thisMonthExpenseCents,
                    thisMonthIncomeCents = state.thisMonthIncomeCents,
                )

                // 时间窗筛选条（纸感 chip 行，规范 §2.4 口径）
                MonthFilterBar(
                    monthFilter = state.monthFilter,
                    onShowAll = { viewModel.setMonthFilter(null) },
                    onEnterMonth = { viewModel.enterMonthMode() },
                    onStep = { delta -> viewModel.stepMonth(delta) },
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (SectionDetailFilterPlan.emptyBranch(
                        hasAnyEntry = state.hasAnyEntry,
                        windowEmpty = state.isEmpty,
                        monthFilter = state.monthFilter,
                    )) {
                        // 分区从无账目：引导记一笔
                        SectionDetailFilterPlan.SectionDetailBranch.NO_ENTRY_EVER -> EmptyHint(
                            text = stringResource(R.string.empty_all),
                            actionLabel = stringResource(R.string.add_entry),
                            onAction = startCreate,
                        )

                        // 分区有账目、当前筛选月为空：一键清筛选回全部时间
                        SectionDetailFilterPlan.SectionDetailBranch.MONTH_EMPTY -> EmptyHint(
                            text = stringResource(R.string.empty_month),
                            actionLabel = stringResource(R.string.show_all),
                            onAction = { viewModel.setMonthFilter(null) },
                        )

                        SectionDetailFilterPlan.SectionDetailBranch.CONTENT -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            var lastMonth: YearMonth? = null
                            state.groups.forEach { group ->
                                val groupMonth = YearMonth.from(group.date)
                                // 月份分隔头仅全部模式插头：月模式整表同月，筛选条标签即锚点
                                if (state.monthFilter == null) {
                                    SectionDetailFilterPlan.monthBoundary(lastMonth, groupMonth)
                                        ?.let { headerMonth ->
                                            item(key = "month_$headerMonth") {
                                                SectionMonthHeader(headerMonth)
                                            }
                                        }
                                }
                                lastMonth = groupMonth
                                item(key = "header_${group.date}") {
                                    DayHeader(
                                        dateLabel = DateTimes.dayLabel(group.date),
                                        expenseCents = group.expenseCents,
                                        incomeCents = group.incomeCents,
                                    )
                                }
                                items(group.entries, key = { it.entry.id }) { full ->
                                    EntryRow(
                                        full = full,
                                        selected = false,
                                        onClick = { rowClick(full.entry.id) },
                                        onDuplicate = { scope.launch { duplicateWithNotice(full.entry.id) } },
                                        onMove = {},
                                        onDelete = { scope.launch { deleteWithUndo(full.entry.id) } },
                                        // 增删移位动效（Motion）
                                        modifier = slAnimateItem(),
                                    )
                                }
                                item(key = "space_${group.date}") { Spacer(modifier = Modifier.height(8.dp)) }
                            }
                            item { Spacer(modifier = Modifier.height(12.dp)) }
                        }
                    }
                }

                // 底部「记一笔」：拇指可达
                Button(
                    onClick = startCreate,
                    shape = SlButtonShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.add_entry))
                }
            }

            // 非 Compact：编辑以列表页内浮层 / 右侧面板承载（不打断上下文）
            if (inPlaceEdit && editingEntryId != null) {
                EntryEditHost(
                    entryId = editingEntryId ?: Routes.NEW_ENTRY_ID,
                    sectionId = sectionId,
                    style = if (layout == WindowLayout.Expanded) {
                        EntryEditHostStyle.Panel
                    } else {
                        EntryEditHostStyle.Centered
                    },
                    dense = dense,
                    snackbarHostState = snackbarHostState,
                    onDismiss = { editingEntryId = null },
                    onSaved = { savedId ->
                        editingEntryId = null
                        savedId?.let { scope.launch { showSavedNotice(it) } }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 时间窗筛选条：首位「全部」chip 常驻（口径同明细页 chip 行首位 = 清时间窗筛选，
 * 月模式下也是回全部的出口）；右侧按模式换形——
 * 全部模式给「筛选」入口（进月模式，落点=最新账目月），月模式给 ‹ 月 › 步进。
 * 「筛选」复用明细页同名通用词，不为本条另立新字符串。
 */
@Composable
private fun MonthFilterBar(
    monthFilter: YearMonth?,
    onShowAll: () -> Unit,
    onEnterMonth: () -> Unit,
    onStep: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = monthFilter == null,
            onClick = onShowAll,
            modifier = Modifier.minimumInteractiveComponentSize(),
            colors = slFilterChipColors(),
            label = { Text(stringResource(R.string.filter_all), style = SlType.label) },
        )
        if (monthFilter == null) {
            TextButton(
                onClick = onEnterMonth,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(stringResource(R.string.filter), style = SlType.label)
            }
        } else {
            IconButton(
                onClick = { onStep(-1) },
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Icon(
                    SlIcons.Ui.ArrowLeft,
                    contentDescription = stringResource(R.string.a11y_month_prev),
                )
            }
            Text(
                text = DateTimes.monthLabel(monthFilter),
                style = SlType.label,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            IconButton(
                onClick = { onStep(1) },
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Icon(
                    SlIcons.Ui.ArrowRight,
                    contentDescription = stringResource(R.string.a11y_month_next),
                )
            }
        }
    }
}

/**
 * 月份分隔头（仅全部模式渲染）：照搜索结果 SearchMonthHeader 先例（label + primary），
 * 格式统一 [DateTimes.monthLabel]「2026年9月」不补零。
 */
@Composable
private fun SectionMonthHeader(month: YearMonth) {
    Text(
        text = DateTimes.monthLabel(month),
        style = SlType.label,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionSummary(
    expenseCents: Long,
    incomeCents: Long,
    monthFilter: YearMonth?,
    allTimeExpenseCents: Long,
    allTimeIncomeCents: Long,
    thisMonthExpenseCents: Long,
    thisMonthIncomeCents: Long,
) {
    val hidden = LocalHideAmounts.current
    val masked = { cents: Long -> if (hidden) "••••" else Money.formatWithSymbol(cents) }
    // P1 双口径头部：当期（随筛选）与另一锚点并列——
    // 月模式：当期 =「9 月」 · 锚点 =「累计」；全部模式：当期 =「累计」 · 锚点 =「本月」。
    // 首屏卡片是「本月」口径，头部只给一个口径时跨月差额无从判断（全面审查 P1 后补）。
    val primary = if (monthFilter == null) {
        listOf(
            stringResource(R.string.expense_all, masked(allTimeExpenseCents)),
            stringResource(R.string.income_all, masked(allTimeIncomeCents)),
        )
    } else {
        listOf(
            stringResource(R.string.section_month_expense, monthFilter.monthValue, masked(expenseCents)),
            stringResource(R.string.section_month_income, monthFilter.monthValue, masked(incomeCents)),
        )
    }
    val anchor = if (monthFilter == null) {
        listOf(
            stringResource(R.string.section_this_month_expense, masked(thisMonthExpenseCents)),
            stringResource(R.string.section_this_month_income, masked(thisMonthIncomeCents)),
        )
    } else {
        listOf(
            stringResource(R.string.expense_all, masked(allTimeExpenseCents)),
            stringResource(R.string.income_all, masked(allTimeIncomeCents)),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(primary[0], style = SlType.bodySm, color = MaterialTheme.colorScheme.onSurface)
            Text(primary[1], style = SlType.bodySm, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(anchor[0], style = SlType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(anchor[1], style = SlType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
