package com.simpleledger.app.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.ui.components.ConfirmDialog
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.MonthHeader
import com.simpleledger.app.ui.theme.TabularNums
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import kotlinx.coroutines.launch
import java.io.File

/** 保存结果通过 SavedStateHandle 回传给明细页 */
const val RESULT_SAVED_ENTRY_ID = "result_saved_entry_id"

/**
 * 大屏（≥840dp 窗口）启用列表–详情双栏。
 * 注意：判定必须基于**窗口宽度**而非内容区宽度——内容区已被 Navigation Rail 占去约 108dp，
 * 若在内容区里再判断 840dp，就会永远触发不了双栏。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LedgerScreen(
    resultHandle: SavedStateHandle,
    onEditEntry: (Long) -> Unit,
    twoPane: Boolean = false,
    viewModel: LedgerViewModel = viewModel(factory = LedgerViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selectedEntry.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilterSheet by remember { mutableStateOf(false) }

    // 保存成功 → 提示「已记入 …」并提供撤销（可撤销的反馈比二次确认更高效）
    LaunchedEffect(Unit) {
        resultHandle.getStateFlow(RESULT_SAVED_ENTRY_ID, -1L).collect { entryId ->
            if (entryId > 0) {
                resultHandle[RESULT_SAVED_ENTRY_ID] = -1L
                val label = viewModel.describeEntry(entryId) ?: return@collect
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.saved_toast, label),
                    actionLabel = context.getString(R.string.undo),
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.undoDelete(entryId)
                }
            }
        }
    }

    val filtersActive = state.filters.sectionId != null ||
        state.filters.categoryId != null ||
        state.filters.type != null

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (twoPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    LedgerListPane(
                        state = state,
                        filtersActive = filtersActive,
                        viewModel = viewModel,
                        selectedEntryId = selected?.entry?.id,
                        onRowClick = { id -> viewModel.selectEntry(id) },
                        onFilterClick = { showFilterSheet = true },
                        modifier = Modifier.width(400.dp),
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    EntryDetailPane(
                        full = selected,
                        onEdit = { id -> onEditEntry(id) },
                        onDeleted = { viewModel.selectEntry(null) },
                        onDelete = { id -> scope.launch { viewModel.deleteEntryNow(id) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                LedgerListPane(
                    state = state,
                    filtersActive = filtersActive,
                    viewModel = viewModel,
                    selectedEntryId = null,
                    onRowClick = { id -> onEditEntry(id) },
                    onFilterClick = { showFilterSheet = true },
                    modifier = Modifier.fillMaxSize(),
                )
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

/* ---------------------------------------------------------------- 列表栏 */

@Composable
private fun LedgerListPane(
    state: LedgerUiState,
    filtersActive: Boolean,
    viewModel: LedgerViewModel,
    selectedEntryId: Long?,
    onRowClick: (Long) -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        MonthHeader(
            month = state.month,
            onPrev = viewModel::prevMonth,
            onNext = viewModel::nextMonth,
            onToday = viewModel::goToday,
            trailing = {
                // 用文字而非符号字符：⛃ 等生僻符号在部分设备上会渲染为豆腐块
                TextButton(onClick = onFilterClick) {
                    Box {
                        Text("筛选", fontSize = 13.sp)
                        if (filtersActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(50),
                                    ),
                            )
                        }
                    }
                }
            },
        )

        OverviewRow(
            expenseCents = state.expenseCents,
            incomeCents = state.incomeCents,
        )

        // 分区筛选（最常用，常驻页面；类型与分类收进筛选面板）
        LazyRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = state.filters.sectionId == null,
                    onClick = { viewModel.filterSection(null) },
                    label = { Text("全部分区", fontSize = 12.5.sp) },
                )
            }
            items(state.sections, key = { it.id }) { section ->
                FilterChip(
                    selected = state.filters.sectionId == section.id,
                    onClick = { viewModel.filterSection(section.id) },
                    label = { Text("${section.emoji} ${section.name}", fontSize = 12.5.sp) },
                )
            }
        }

        if (filtersActive) {
            ActiveFilterBar(state = state, onClearAll = viewModel::clearFilters)
        }

        if (state.isEmpty) {
            EmptyHint(
                text = if (filtersActive) {
                    "当前筛选条件下没有账目"
                } else {
                    "本月还没有账目，点下方「＋」记一笔"
                },
                actionLabel = if (filtersActive) "清除全部筛选" else null,
                onAction = viewModel::clearFilters,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                state.groups.forEach { group ->
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
                            selected = selectedEntryId == full.entry.id,
                            onClick = { onRowClick(full.entry.id) },
                        )
                    }
                    item(key = "space_${group.date}") { Spacer(modifier = Modifier.height(6.dp)) }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

/* ---------------------------------------------------------------- 概览 */

@Composable
private fun OverviewRow(expenseCents: Long, incomeCents: Long) {
    val hidden = LocalHideAmounts.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverviewCell(
                label = "支出",
                value = if (hidden) "••••" else Money.formatWithSymbol(expenseCents),
                color = expenseColor(),
                modifier = Modifier.weight(1f),
            )
            VerticalDivider()
            OverviewCell(
                label = "收入",
                value = if (hidden) "••••" else Money.formatWithSymbol(incomeCents),
                color = incomeColor(),
                modifier = Modifier.weight(1f),
            )
            VerticalDivider()
            OverviewCell(
                label = "结余",
                value = if (hidden) "••••" else Money.formatWithSymbol(incomeCents - expenseCents),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(30.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@Composable
private fun OverviewCell(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            style = TabularNums,
            maxLines = 1,
        )
    }
}

/* ---------------------------------------------------------------- 筛选态 */

@Composable
private fun ActiveFilterBar(state: LedgerUiState, onClearAll: () -> Unit) {
    val selectedCategory = state.categories.firstOrNull { it.id == state.filters.categoryId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.filters.type?.let { type ->
            SmallTag(if (type == EntryType.EXPENSE) "支出" else "收入")
        }
        selectedCategory?.let { SmallTag("${it.emoji} ${it.name}") }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onClearAll) {
            Text("清除全部", fontSize = 12.5.sp)
        }
    }
}

@Composable
private fun SmallTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/* ---------------------------------------------------------------- 列表 */

@Composable
private fun DayHeader(dateLabel: String, expenseCents: Long, incomeCents: Long) {
    val hidden = LocalHideAmounts.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateLabel,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (hidden) {
                "支 ••••　收 ••••"
            } else {
                "支 ${Money.formatCents(expenseCents)}　收 ${Money.formatCents(incomeCents)}"
            },
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = TabularNums,
        )
    }
}

/**
 * 账目行：分类名（主）· 时间/分区/标记（次）· 金额（视觉最重）。
 * 备注不展开全文，仅用 💬 标记存在性——明细页的首要任务是快速扫视。
 */
@Composable
private fun EntryRow(full: EntryFull, selected: Boolean, onClick: () -> Unit) {
    val isIncome = full.entry.type == EntryType.INCOME
    val meta = buildString {
        append(DateTimes.timeLabel(DateTimes.toLocalTime(full.entry.entryTime)))
        full.section?.let { append(" · ${it.emoji}${it.name}") }
        if (full.images.isNotEmpty()) append(" · 📷${full.images.size}")
        if (full.entry.note.isNotBlank()) append(" · 💬")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isIncome) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(full.category?.emoji ?: "🏷️", fontSize = 17.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = full.category?.name ?: "未分类",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        AmountText(amountCents = full.entry.amountCents, isIncome = isIncome)
    }
}

/* ---------------------------------------------------------------- 大屏详情栏 */

@Composable
private fun EntryDetailPane(
    full: EntryFull?,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hidden = LocalHideAmounts.current
    var confirmDelete by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (full == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("👈", fontSize = 26.sp)
                }
                Text(
                    text = "从左侧选择一笔账目查看详情",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            return@Box
        }

        val isIncome = full.entry.type == EntryType.INCOME
        val section = full.section

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(full.category?.emoji ?: "🏷️", fontSize = 19.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = full.category?.name ?: "未分类",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = buildString {
                            append(DateTimes.dateLabel(DateTimes.toLocalDate(full.entry.entryTime)))
                            append(" ")
                            append(DateTimes.timeLabel(DateTimes.toLocalTime(full.entry.entryTime)))
                            section?.let { append(" · ${it.emoji}${it.name}") }
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { onEdit(full.entry.id) }) { Text("编辑") }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { confirmDelete = true }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("金额", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (hidden) {
                            "••••"
                        } else {
                            (if (isIncome) "+" else "−") +
                                Money.formatWithSymbol(full.entry.amountCents)
                        },
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isIncome) incomeColor() else expenseColor(),
                        style = TabularNums,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("备注", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = full.entry.note.ifBlank { "无备注" },
                        fontSize = 14.sp,
                        color = if (full.entry.note.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            if (full.images.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "贴图 ${full.images.size} 张",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(full.images, key = { it.id }) { image ->
                                AsyncImage(
                                    model = File(image.filePath),
                                    contentDescription = "贴图",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                )
                            }
                        }
                    }
                }
            }

            if (section != null && (section.note.isNotBlank() || section.budgetCents > 0)) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "所属分区",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${section.emoji} ${section.name}",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (section.note.isNotBlank()) {
                            Text(
                                text = section.note,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (section.budgetCents > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "月度预算",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = if (hidden) {
                                        "••••"
                                    } else {
                                        Money.formatWithSymbol(section.budgetCents)
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    style = TabularNums,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = {
                                    if (section.budgetCents > 0) {
                                        (full.entry.amountCents.toFloat() / section.budgetCents)
                                            .coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(99.dp)),
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                            Text(
                                "本笔占预算的 ${percentOf(full.entry.amountCents, section.budgetCents)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除这笔账目？",
            text = "删除后可用提示条里的「撤销」恢复，贴图也会一并删除。",
            onConfirm = {
                confirmDelete = false
                full?.let { onDelete(it.entry.id) }
                onDeleted()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun percentOf(part: Long, total: Long): String {
    if (total <= 0) return "0%"
    return "${((part.toDouble() / total) * 100).toInt()}%"
}

/* ---------------------------------------------------------------- 筛选面板 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheetContent(
    state: LedgerUiState,
    onTypeChange: (Int?) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "筛选",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text("类型", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf<Int?>(null, EntryType.EXPENSE, EntryType.INCOME)
            options.forEachIndexed { index, type ->
                SegmentedButton(
                    selected = state.filters.type == type,
                    onClick = { onTypeChange(type) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(
                        text = when (type) {
                            EntryType.EXPENSE -> "支出"
                            EntryType.INCOME -> "收入"
                            else -> "全部"
                        },
                        fontSize = 13.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("分类", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.categories.forEach { category ->
                FilterChip(
                    selected = state.filters.categoryId == category.id,
                    onClick = { onCategoryChange(category.id) },
                    label = { Text("${category.emoji} ${category.name}", fontSize = 12.5.sp) },
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onClearAll, modifier = Modifier.weight(1f)) {
                Text("清除全部筛选")
            }
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("完成")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/* ---------------------------------------------------------------- 金额 */

/** 列表内金额：语义色 + 正负号 + 等宽数字，保证纵向可扫视 */
@Composable
fun AmountText(
    amountCents: Long,
    isIncome: Boolean,
    fontSize: TextUnit = 15.sp,
) {
    val hidden = LocalHideAmounts.current
    Text(
        text = if (hidden) {
            "••••"
        } else {
            (if (isIncome) "+" else "−") + Money.formatWithSymbol(kotlin.math.abs(amountCents))
        },
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        color = if (isIncome) incomeColor() else expenseColor(),
        style = TabularNums,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}
