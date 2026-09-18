package com.simpleledger.app.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.MoneyText
import com.simpleledger.app.ui.components.MonthHeader
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    onEditEntry: (Long) -> Unit,
    onAddEntry: () -> Unit,
    viewModel: LedgerViewModel = viewModel(factory = LedgerViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    var showCategorySheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            MonthHeader(
                month = state.month,
                onPrev = viewModel::prevMonth,
                onNext = viewModel::nextMonth,
                onToday = viewModel::goToday,
            )

            // 本月小结
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("支出", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            Money.formatWithSymbol(state.expenseCents),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Card(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("收入", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            Money.formatWithSymbol(state.incomeCents),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = incomeColor(),
                        )
                    }
                }
            }

            // 分区筛选
            LazyRow(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.filters.sectionId == null,
                        onClick = { viewModel.filterSection(null) },
                        label = { Text("全部分区") },
                    )
                }
                items(state.sections, key = { it.id }) { section ->
                    FilterChip(
                        selected = state.filters.sectionId == section.id,
                        onClick = { viewModel.filterSection(section.id) },
                        label = { Text("${section.emoji} ${section.name}") },
                    )
                }
            }

            // 类型 + 分类筛选
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.filters.type == null,
                    onClick = { viewModel.filterType(null) },
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = state.filters.type == EntryType.EXPENSE,
                    onClick = { viewModel.filterType(EntryType.EXPENSE) },
                    label = { Text("支出") },
                )
                FilterChip(
                    selected = state.filters.type == EntryType.INCOME,
                    onClick = { viewModel.filterType(EntryType.INCOME) },
                    label = { Text("收入") },
                )
                AssistChip(
                    onClick = { showCategorySheet = true },
                    label = {
                        val selected = state.categories.firstOrNull { it.id == state.filters.categoryId }
                        Text(selected?.let { "${it.emoji} ${it.name}" } ?: "分类")
                    },
                )
            }

            if (state.isEmpty) {
                EmptyHint("本月暂无账目，点右下角记一笔吧")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    state.groups.forEach { group ->
                        item(key = "header_${group.date}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    DateTimes.dayLabel(group.date),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    "支 ${Money.formatCents(group.expenseCents)}　收 ${Money.formatCents(group.incomeCents)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(group.entries, key = { it.entry.id }) { full ->
                            EntryRow(full = full, onClick = { onEditEntry(full.entry.id) })
                        }
                        item(key = "space_${group.date}") { Spacer(modifier = Modifier.height(6.dp)) }
                    }
                    item { Spacer(modifier = Modifier.height(88.dp)) }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAddEntry,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("记一笔")
        }
    }

    if (showCategorySheet) {
        ModalBottomSheet(onDismissRequest = { showCategorySheet = false }) {
            Text(
                "按分类筛选",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.categories, key = { it.id }) { category ->
                    FilterChip(
                        selected = state.filters.categoryId == category.id,
                        onClick = {
                            viewModel.filterCategory(category.id)
                            showCategorySheet = false
                        },
                        label = { Text("${category.emoji} ${category.name}") },
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EntryRow(full: EntryFull, onClick: () -> Unit) {
    val category = full.category
    val section = full.section
    val isIncome = full.entry.type == EntryType.INCOME

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(category?.emoji ?: "🏷️", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                category?.name ?: "未分类",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val info = buildString {
                append(DateTimes.timeLabel(DateTimes.toLocalTime(full.entry.entryTime)))
                if (section != null) append(" · ${section.emoji}${section.name}")
                if (full.images.isNotEmpty()) append(" · 📷${full.images.size}")
            }
            Text(
                info,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (full.entry.note.isNotBlank()) {
                Text(
                    full.entry.note,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MoneyText(amountCents = full.entry.amountCents, isIncome = isIncome, fontSize = 16.sp)
    }
}
