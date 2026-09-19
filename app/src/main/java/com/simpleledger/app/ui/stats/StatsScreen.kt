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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.data.local.entity.SectionTotal
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.ui.components.CategoryPieChart
import com.simpleledger.app.ui.components.DailyBarChart
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.MonthHeader
import com.simpleledger.app.ui.ledger.AmountText
import com.simpleledger.app.ui.theme.TabularNums
import com.simpleledger.app.ui.theme.chartPalette
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.ui.theme.warnColor
import com.simpleledger.app.util.Money

@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory)) {
    val state by viewModel.state.collectAsState()
    val hidden = LocalHideAmounts.current

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
            // 总览：支出 / 收入 / 结余
            item {
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
                        StatCell(
                            label = "本月支出",
                            value = if (hidden) "••••" else Money.formatWithSymbol(state.expenseCents),
                            color = expenseColor(),
                            modifier = Modifier.weight(1f),
                        )
                        CellDivider()
                        StatCell(
                            label = "本月收入",
                            value = if (hidden) "••••" else Money.formatWithSymbol(state.incomeCents),
                            color = incomeColor(),
                            modifier = Modifier.weight(1f),
                        )
                        CellDivider()
                        StatCell(
                            label = "结余",
                            value = if (hidden) {
                                "••••"
                            } else {
                                Money.formatWithSymbol(state.incomeCents - state.expenseCents)
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 分类占比
            item {
                SectionTitle("分类占比")
                Card(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        CategoryPieChart(shares = state.shares, totalCents = state.expenseCents)
                        Spacer(modifier = Modifier.height(14.dp))
                        val palette = chartPalette()
                        state.shares.forEachIndexed { index, share ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .background(palette[index % palette.size], RoundedCornerShape(3.dp)),
                                )
                                Spacer(modifier = Modifier.width(9.dp))
                                Text(
                                    "${share.total.emoji} ${share.total.name}",
                                    fontSize = 13.5.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = if (hidden) {
                                        "${(share.fraction * 100).toInt()}%"
                                    } else {
                                        "${(share.fraction * 100).toInt()}% · ${Money.formatCents(share.total.total)}"
                                    },
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = TabularNums,
                                )
                            }
                        }
                    }
                }
            }

            // 每日支出
            item {
                SectionTitle("每日支出")
                Card(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        DailyBarChart(daily = state.daily, daysInMonth = state.daysInMonth)
                    }
                }
            }

            // 分区预算进度（含超支预警）
            if (state.sectionTotals.isNotEmpty()) {
                item {
                    SectionTitle("分区预算")
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        state.sectionTotals.forEach { section ->
                            SectionBudgetCard(section = section)
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun CellDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
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

/**
 * 分区预算卡：预算有值才显示进度条；未设预算时只显示支出金额。
 * 颜色按用量分级（正常 / 接近上限 / 超支），但那只是加速识别——结论始终由文字给出。
 */
@Composable
private fun SectionBudgetCard(section: SectionTotal) {
    val hidden = LocalHideAmounts.current
    val budget = section.budgetCents
    val hasBudget = budget > 0
    val ratio = if (hasBudget) (section.expense.toFloat() / budget).coerceIn(0f, 1f) else 0f
    val overspent = hasBudget && section.expense > budget
    val nearLimit = hasBudget && !overspent && section.expense.toFloat() / budget >= 0.9f

    val barColor = when {
        overspent -> expenseColor()
        nearLimit -> warnColor()
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(section.emoji, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.name,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = buildString {
                            append("${section.count} 笔")
                            if (hasBudget) {
                                append(" · 已用 ${(ratio * 100).toInt()}%")
                            } else {
                                append(" · 未设预算")
                            }
                        },
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(
                    amountCents = section.expense,
                    isIncome = false,
                    fontSize = 15.sp,
                )
            }

            if (section.note.isNotBlank()) {
                Text(
                    text = "📌 ${section.note}",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (hasBudget) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = barColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (hidden) {
                            "已用 •••• / ••••"
                        } else {
                            "已用 ${Money.formatWithSymbol(section.expense)} / ${Money.formatWithSymbol(budget)}"
                        },
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = TabularNums,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = when {
                            hidden -> ""
                            overspent -> "⚠ 已超支 ${Money.formatWithSymbol(section.expense - budget)}"
                            else -> "剩余 ${Money.formatWithSymbol(budget - section.expense)}"
                        },
                        fontSize = 11.5.sp,
                        fontWeight = if (overspent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (overspent) expenseColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = TabularNums,
                    )
                }
            }

            if (section.income > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (hidden) "收入 ••••" else "收入 ${Money.formatWithSymbol(section.income)}",
                    fontSize = 11.5.sp,
                    color = incomeColor(),
                    style = TabularNums,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
