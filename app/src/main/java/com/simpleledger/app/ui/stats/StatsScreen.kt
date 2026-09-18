package com.simpleledger.app.ui.stats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.ui.components.CategoryPieChart
import com.simpleledger.app.ui.components.ChartColors
import com.simpleledger.app.ui.components.DailyBarChart
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.MonthHeader
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.Money

@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory)) {
    val state by viewModel.state.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            MonthHeader(
                month = state.month,
                onPrev = viewModel::prevMonth,
                onNext = viewModel::nextMonth,
                onToday = viewModel::goToday,
            )
        }

        if (state.isEmpty) {
            item { EmptyHint("这个月还没有账目，去记一笔吧") }
        } else {
            // 总览卡片
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard("本月支出", Money.formatWithSymbol(state.expenseCents), Modifier.weight(1f))
                    StatCard(
                        "本月收入",
                        Money.formatWithSymbol(state.incomeCents),
                        Modifier.weight(1f),
                        valueColor = incomeColor(),
                    )
                    StatCard(
                        "结余",
                        Money.formatWithSymbol(StatsCalculatorPublic.balance(state.incomeCents, state.expenseCents)),
                        Modifier.weight(1f),
                    )
                }
            }

            // 分类占比
            item {
                SectionTitle("📊 分类占比")
                Card(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CategoryPieChart(shares = state.shares, totalCents = state.expenseCents)
                        Spacer(modifier = Modifier.height(12.dp))
                        state.shares.forEachIndexed { index, share ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(ChartColors[index % ChartColors.size], CircleShape),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${share.total.emoji} ${share.total.name}", modifier = Modifier.weight(1f))
                                Text(
                                    "${(share.fraction * 100).toInt()}% · ${Money.formatCents(share.total.total)}",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 每日支出
            item {
                SectionTitle("📈 每日支出")
                Card(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DailyBarChart(daily = state.daily, daysInMonth = state.daysInMonth)
                    }
                }
            }

            // 分区汇总（含分区备注）
            item {
                SectionTitle("🗂️ 分区汇总")
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.sectionTotals.forEach { section ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(section.emoji, fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        section.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${section.count} 笔",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (section.note.isNotBlank()) {
                                    Text(
                                        "📌 ${section.note}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row {
                                    Text(
                                        "支出 ${Money.formatWithSymbol(section.expense)}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        "收入 ${Money.formatWithSymbol(section.income)}",
                                        fontSize = 14.sp,
                                        color = incomeColor(),
                                    )
                                }
                                val fraction = if (state.expenseCents > 0) {
                                    (section.expense.toFloat() / state.expenseCents).coerceIn(0f, 1f)
                                } else 0f
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** 让 UI 层不必直接依赖 logic 包内部实现 */
private object StatsCalculatorPublic {
    fun balance(income: Long, expense: Long): Long = income - expense
}
