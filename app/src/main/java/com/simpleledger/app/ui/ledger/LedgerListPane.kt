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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.MonthHeader
import com.simpleledger.app.ui.theme.TabularNums
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import java.time.YearMonth

/* 列表栏：月份头 + 概览 + 分区筛选 + 账目列表 + 移动到分区对话框的挂载点。 */

@Composable
internal fun LedgerListPane(
    state: LedgerUiState,
    filtersActive: Boolean,
    viewModel: LedgerViewModel,
    selectedEntryId: Long?,
    onRowClick: (Long) -> Unit,
    onFilterClick: () -> Unit,
    onSearchClick: () -> Unit,
    onDuplicate: (Long) -> Unit,
    onMoveTo: (Long, Long) -> Unit,
    onDelete: (Long) -> Unit,
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
                    Text("搜索", fontSize = 13.sp)
                }
                TextButton(
                    onClick = onFilterClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
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
                            onDuplicate = { onDuplicate(full.entry.id) },
                            onMove = { moveTarget = full },
                            onDelete = { onDelete(full.entry.id) },
                        )
                    }
                    item(key = "space_${group.date}") { Spacer(modifier = Modifier.height(6.dp)) }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    moveTarget?.let { target ->
        MoveSectionDialog(
            entry = target,
            sections = state.sections,
            onDismiss = { moveTarget = null },
            onPick = { sectionId ->
                onMoveTo(target.entry.id, sectionId)
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