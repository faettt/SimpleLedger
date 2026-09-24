package com.simpleledger.app.ui.ledger

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.MonthHeader
import com.simpleledger.app.ui.components.slFilterChipColors
import com.simpleledger.app.ui.icon.slCategoryIcon
import com.simpleledger.app.ui.theme.SlButtonShape
import com.simpleledger.app.ui.theme.SlChipShape
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.slAnimateItem
import androidx.compose.foundation.text.TextAutoSize
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import java.time.YearMonth
import androidx.compose.ui.graphics.vector.ImageVector
import com.simpleledger.app.data.local.entity.ReimburseState

/* 列表栏：月份头 + 概览 + 分区筛选 + 账目列表 + 移动到分区对话框的挂载点。 */

@Composable
internal fun LedgerListPane(
    state: LedgerUiState,
    filtersActive: Boolean,
    viewModel: LedgerViewModel,
    selectedEntryId: Long?,
    onRowClick: (Long) -> Unit,
    onRecord: () -> Unit,
    onFilterClick: () -> Unit,
    onSearchClick: () -> Unit,
    onGoToSections: () -> Unit,
    onDuplicate: (Long) -> Unit,
    onMoveTo: (Long, Long, Long?) -> Unit,
    onDelete: (Long) -> Unit,
    /** 长按菜单：切换核对维度（规范 §3.5 入口） */
    onSetReconciled: (Long, Boolean) -> Unit,
    /** 长按菜单：设置报销维度 */
    onSetReimburse: (Long, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var moveTarget by remember { mutableStateOf<EntryFull?>(null) }
    Column(modifier = modifier) {
        MonthHeader(
            month = state.month,
            onPrev = viewModel::prevMonth,
            onNext = viewModel::nextMonth,
            onToday = viewModel::goToday,
            // 本月时「今天」是空操作，收起它给「搜索 / 筛选」腾出宽度
            todayVisible = state.month != YearMonth.now(),
            trailing = {
                // 用文字而非符号字符：⛃ 等生僻符号在部分设备上会渲染为豆腐块
                TextButton(
                    onClick = onSearchClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.search), style = SlType.label)
                }
                TextButton(
                    onClick = onFilterClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Box {
                        Text(stringResource(R.string.filter), style = SlType.label)
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
            modifier = Modifier.padding(vertical = 2.dp),
            // 尾部 chip 不硬切在屏幕边：contentPadding 让最后一枚能滚进页边距内（与页面 16dp 边距一致）
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 首位「全部」= 清空一切筛选（含状态维）。用 isActive 而不是
            // 「sectionId == null」判断选中 —— 后者在「筛了待报销」时仍显示选中，
            // 而列表明明被筛过，chip 与列表会互相矛盾。
            item {
                FilterChip(
                    selected = !state.filters.isActive,
                    onClick = { viewModel.clearFilters() },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    colors = slFilterChipColors(),
                    label = { Text(stringResource(R.string.filter_all), style = SlType.label) },
                )
            }
            // 两个状态快捷入口（规范 §2.4 的 chip 行：全部 / 待报销 / 待核对 / 分区…）。
            // 放在分区之前：对「装修季一天十几笔」的场景，先看「还要跟哪几笔钱」
            // 比先看「钱花在哪个分区」更日常。
            // 它们是开关：与分区 chip 可叠加（待报销 + 装修 = 装修里还要报的），
            // 两个状态维也互相独立（待核对 + 待报销 = 也没对也还没报）。
            item {
                FilterChip(
                    selected = state.filters.reimburseState == ReimburseState.PENDING,
                    onClick = viewModel::togglePendingReimburse,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    colors = slFilterChipColors(),
                    label = {
                        // 用账目行上同一个符号：○ —— 让 chip 与行内符号建立对应，
                        // 比再画一个图标更省，也更不容易看错
                        Text(
                            "○ " + stringResource(R.string.status_reimburse_pending),
                            style = SlType.label,
                        )
                    },
                )
            }
            item {
                FilterChip(
                    selected = state.filters.reconciled == false,
                    onClick = viewModel::togglePendingReconcile,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    colors = slFilterChipColors(),
                    label = {
                        // 待核对 = 空槽无符号（P2-3 语义对齐）：✓ 是「已核对」的符号，
                        // 挂在「待核对」上会跟账目行的 ✓ 打架
                        Text(
                            stringResource(R.string.status_pending_reconcile),
                            style = SlType.label,
                        )
                    },
                )
            }
            items(state.sections, key = { it.id }) { section ->
                FilterChip(
                    selected = state.filters.sectionId == section.id,
                    onClick = { viewModel.filterSection(section.id) },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    colors = slFilterChipColors(),
                    label = {
                        // v4：筛选 chip 用「图标 + 名称」渲染（emoji 退场）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = slCategoryIcon(section.iconId),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(section.name, style = SlType.label)
                        }
                    },
                )
            }
        }

        if (filtersActive) {
            ActiveFilterBar(state = state, onClearAll = viewModel::clearFilters)
        }

        // 「记一笔」入口（Q-13）：点击先弹分区选择器，分区不可跳过
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onRecord,
                shape = SlButtonShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Text(stringResource(R.string.record_entry), style = SlType.label)
            }
        }

        if (state.isEmpty) {
            EmptyHint(
                text = if (filtersActive) {
                    stringResource(R.string.filters_none_result)
                } else {
                    // 同步点 #1：空态不再指向已移除的中央「＋」，改为引导去「分区」记账
                    stringResource(R.string.ledger_empty)
                },
                actionLabel = if (filtersActive) {
                    stringResource(R.string.filter_clear_all)
                } else {
                    stringResource(R.string.go_record_in_section)
                },
                onAction = if (filtersActive) viewModel::clearFilters else onGoToSections,
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
                            onDuplicate = { onDuplicate(full.entry.id) },
                            onMove = { moveTarget = full },
                            onDelete = { onDelete(full.entry.id) },
                            // F4 例外：只有「全部分区」视图需要补回分区前缀（规范 §188）
                            showSectionPrefix = state.filters.sectionId == null,
                            onToggleReconciled = { onSetReconciled(full.entry.id, it) },
                            onSetReimburseState = { onSetReimburse(full.entry.id, it) },
                            // 增删移位动效（Motion：记一笔插入 / 删除 / 撤销恢复时滑移淡入淡出）
                            modifier = slAnimateItem(),
                        )
                    }
                    item(key = "space_${group.date}") { Spacer(modifier = Modifier.height(8.dp)) }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    moveTarget?.let { target ->
        MoveSectionDialog(
            entry = target,
            sections = state.sections,
            allCategories = state.categories,
            onDismiss = { moveTarget = null },
            onConfirm = { sectionId, newCategoryId ->
                onMoveTo(target.entry.id, sectionId, newCategoryId)
                moveTarget = null
            },
        )
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
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverviewCell(
                label = stringResource(R.string.expense),
                value = if (hidden) "••••" else Money.formatWithSymbol(expenseCents),
                color = expenseColor(),
                modifier = Modifier.weight(1f),
            )
            VerticalDivider()
            OverviewCell(
                label = stringResource(R.string.income),
                value = if (hidden) "••••" else Money.formatWithSymbol(incomeCents),
                color = incomeColor(),
                modifier = Modifier.weight(1f),
            )
            VerticalDivider()
            OverviewCell(
                label = stringResource(R.string.stats_balance),
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
        Text(label, style = SlType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            // 概览数字 = 列表主信息位（title，字族一元楷体）。
            // P2-1（fs2.0 尾数截断）：autoSize 收缩适配三栏等宽槽，绝不截尾
            style = SlType.title,
            color = color,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.filters.type?.let { type ->
            SmallTag(stringResource(if (type == EntryType.EXPENSE) R.string.expense else R.string.income))
        }
        // 状态维也要在摘要条里出现，否则用户看不出列表被状态筛过 ——
        // 快捷 chip 行会被横向滚动遮住，摘要条是唯一常在的提示。
        if (state.filters.reconciled == false) {
            SmallTag(stringResource(R.string.status_pending_reconcile))
        }
        state.filters.reimburseState?.let { value ->
            SmallTag(
                when (value) {
                    ReimburseState.PENDING -> stringResource(R.string.status_reimburse_pending)
                    ReimburseState.CLEARED -> stringResource(R.string.status_reimburse_cleared)
                    else -> stringResource(R.string.status_reimburse_none)
                }
            )
        }
        selectedCategory?.let {
            // v4：激活筛选摘要用「图标 + 名称」
            SmallTag(it.name, icon = slCategoryIcon(it.iconId))
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onClearAll) {
            Text(stringResource(R.string.clear_all), style = SlType.label)
        }
    }
}

@Composable
private fun SmallTag(text: String, icon: ImageVector? = null) {
    Box(
        modifier = Modifier
            .clip(SlChipShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = SlType.meta,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}