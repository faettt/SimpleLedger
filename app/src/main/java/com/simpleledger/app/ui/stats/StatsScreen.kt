package com.simpleledger.app.ui.stats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.logic.CategoryCandidates
import com.simpleledger.app.logic.CategoryShare
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.CategoryPieChart
import com.simpleledger.app.ui.components.ContentMaxWidth
import com.simpleledger.app.ui.components.ContentWidth
import com.simpleledger.app.ui.components.DailyBarChart
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.MonthHeader
import com.simpleledger.app.ui.theme.TabularNums
import com.simpleledger.app.logic.StatsCalculator
import com.simpleledger.app.ui.theme.shareColor
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.Money

/**
 * 统计页。
 *
 * [layout] 只用来决定「是否启用大屏两栏 / 整页限宽」：只有 Expanded 才两栏。
 * Compact / Medium 走与改动前**完全一致**的单列分支（回归底线）。
 */
@Composable
fun StatsScreen(
    layout: WindowLayout = WindowLayout.Compact,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val hidden = LocalHideAmounts.current
    val twoColumn = layout == WindowLayout.Expanded

    // 把滚动主体抽成一个 lambda：限宽与否只切换外壳，主体只有一份，避免两套代码走样
    val body: @Composable () -> Unit = {
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
                // 总览：支出 / 收入 / 结余，跨满宽（双栏下也独占整行）
                item { OverviewCard(state = state, hidden = hidden) }

                if (twoColumn) {
                    // 环图（钱花在哪类）与柱状图（什么时候花的）是天然对照关系，并排可一眼互看
                    item {
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                CategoryShareSection(
                                    shares = state.shares,
                                    totalCents = state.expenseCents,
                                    hidden = hidden,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                DailyExpenseSection(
                                    daily = state.daily,
                                    daysInMonth = state.daysInMonth,
                                    hidden = hidden,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        CategoryShareSection(
                            shares = state.shares,
                            totalCents = state.expenseCents,
                            hidden = hidden,
                        )
                    }
                    item {
                        DailyExpenseSection(
                            daily = state.daily,
                            daysInMonth = state.daysInMonth,
                            hidden = hidden,
                        )
                    }
                }

                // Q-08：分区预算块已移除——预算进度改由「分区」首屏卡片承载，统计页专注趋势 / 占比 / 每日支出
                item { Spacer(modifier = Modifier.height(28.dp)) }
            }
        }
    }

    if (twoColumn) {
        // 图表类越宽越能表达趋势，只做 1200dp 的极端宽屏兜底
        ContentWidth(maxWidth = ContentMaxWidth.Wide) { body() }
    } else {
        body()
    }
}

/** 总览三格：支出 / 收入 / 结余 */
@Composable
private fun OverviewCard(state: StatsUiState, hidden: Boolean) {
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
                label = stringResource(R.string.stats_balance),
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

/** 分类占比：环图 + 图例 */
@Composable
private fun CategoryShareSection(
    shares: List<CategoryShare>,
    totalCents: Long,
    hidden: Boolean,
) {
    SectionTitle("分类占比")
    Card(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            CategoryPieChart(shares = shares, totalCents = totalCents, hidden = hidden)
            Spacer(modifier = Modifier.height(14.dp))
            // 图例逐项列出分类占比，与环图摘要内容完全重复；对读屏静音，避免听完摘要再逐行重念一遍
            Column(modifier = Modifier.clearAndSetSemantics {}) {
                shares.forEachIndexed { index, share ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                // 与环图共用 shareColor —— 两处各取各的迟早会漂移成
                                // 「图例的蓝不是环上那块蓝」
                                .background(
                                    shareColor(index, share.isMerged),
                                    RoundedCornerShape(1.dp),
                                ),
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            CategoryCandidates.shareLabel(share.total),
                            fontSize = 13.5.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (hidden) {
                                StatsCalculator.percentLabel(share.fraction)
                            } else {
                                StatsCalculator.percentLabel(share.fraction) +
                                    " · " + Money.formatCents(share.total.total)
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
}

/** 每日支出：柱状图 */
@Composable
private fun DailyExpenseSection(daily: List<Pair<Int, Long>>, daysInMonth: Int, hidden: Boolean) {
    SectionTitle("每日支出")
    Card(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            DailyBarChart(daily = daily, daysInMonth = daysInMonth, hidden = hidden)
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}