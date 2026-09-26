package com.simpleledger.app.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
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
import com.simpleledger.app.logic.StatsCalculator
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.BarRow
import com.simpleledger.app.ui.components.CategoryBarChart
import com.simpleledger.app.ui.components.ContentMaxWidth
import com.simpleledger.app.ui.components.ContentWidth
import com.simpleledger.app.ui.components.DailyBarChart
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.MonthHeader
import com.simpleledger.app.ui.components.SectionDonutChart
import com.simpleledger.app.ui.theme.SlMotion
import com.simpleledger.app.ui.theme.SlStatus
import com.simpleledger.app.ui.theme.SlType
import androidx.compose.foundation.text.TextAutoSize
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.ui.theme.slFast
import com.simpleledger.app.ui.theme.tapeColor
import com.simpleledger.app.util.Money

/**
 * 统计页。
 *
 * [layout] 只用来决定「是否启用大屏两栏 / 整页限宽」：只有 Expanded 才两栏。
 * Compact / Medium 走与改动前**完全一致**的单列分支（回归底线）。
 *
 * 图表组成与顺序（规范 §2.5 v2.1）：概览卡 → **分区占比环图** → **分类金额条形图** → 每日支出。
 * 由「分类占比环图」拆成前两者，是因为一个扇区有「分区 + 分类」两个身份、却只有一条颜色通道：
 * 环图用颜色表达**分区**，条形图用长度表达**分类**，两个维度都不必妥协。
 */
@Composable
fun StatsScreen(
    layout: WindowLayout = WindowLayout.Compact,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val hidden = LocalHideAmounts.current
    val twoColumn = layout == WindowLayout.Expanded

    // 双图联动的条形图数据：选中分区 → 只保留该分区的分类（§2.5 G4）。
    // 过滤在逻辑层做纯函数（remember 里只能跑非组合代码），这里只负责把它变成「带颜色的条」；
    // tapeColor 是 @Composable，必须在 remember 的计算块**之外**调用（见 Charts.kt 的同类教训）。
    val barRows = remember(state.shares, state.selectedSectionId) {
        StatsCalculator.filterBySection(state.shares, state.selectedSectionId)
    }.map { share ->
        BarRow(
            // 全部分区视图下补回「分区 · 分类」前缀（与账目行同一套 F4 例外口径）
            label = CategoryCandidates.shareLabel(share.total),
            amountCents = share.total.total,
            // 颜色 = 该分类**所属分区**的胶带色 —— 与环图同一条颜色通道，
            // 环上「装修是一片赭黄」在条形图里就变成「装修的分类全是赭黄条」
            color = tapeColor(share.total.sectionColorIndex ?: 0),
        )
    }
    // 图例行的标题：选中分区后标出「只看谁」，否则笼统地说「分类金额」
    val barTitle = state.selectedSectionId
        ?.let { id -> state.sectionShares.firstOrNull { it.section.sectionId == id }?.section?.name }
        ?.let { name -> "分类金额 · 只看$name" }
        ?: "分类金额"

    // U-7/R-18「按人」条形图（G3：长度管数值、不给颜色加语义）——
    // 全部条同墨色（primary 墨青），身份靠楷体名标签，不引入第三套色彩语义
    val memberRows = state.memberShares.map { share ->
        BarRow(
            label = share.name,
            amountCents = share.amountCents,
            color = MaterialTheme.colorScheme.primary,
        )
    }

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

                // 由粗到细：先看「钱花在哪个分区」（环图），再看「该分区里的分类」（条形图），
                // 最后看「什么时候花的」（每日支出）
                if (twoColumn) {
                    // 双栏：左列是「占比 + 分类金额」（同一个维度的两张图，放在一起看），
                    // 右列是「时间」—— 两种编码通道分开看
                    item {
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                SectionShareSection(
                                    shares = state.sectionShares,
                                    totalCents = state.expenseCents,
                                    hidden = hidden,
                                    selectedIndex = state.sectionShares.indexOfFirst {
                                        it.section.sectionId == state.selectedSectionId
                                    }.takeIf { it >= 0 },
                                    onSliceClick = { index ->
                                        state.sectionShares.getOrNull(index)?.let {
                                            viewModel.selectSection(it.section.sectionId)
                                        }
                                    },
                                )
                                CategoryBarSection(
                                    title = barTitle,
                                    rows = barRows,
                                    hidden = hidden,
                                )
                                if (state.memberShares.isNotEmpty()) {
                                    MemberBarSection(rows = memberRows, hidden = hidden)
                                }
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
                        SectionShareSection(
                            shares = state.sectionShares,
                            totalCents = state.expenseCents,
                            hidden = hidden,
                            selectedIndex = state.sectionShares.indexOfFirst {
                                it.section.sectionId == state.selectedSectionId
                            }.takeIf { it >= 0 },
                            onSliceClick = { index ->
                                state.sectionShares.getOrNull(index)?.let { viewModel.selectSection(it.section.sectionId) }
                            },
                        )
                    }
                    item {
                        CategoryBarSection(
                            title = barTitle,
                            rows = barRows,
                            hidden = hidden,
                        )
                    }
                    if (state.memberShares.isNotEmpty()) {
                        item {
                            MemberBarSection(rows = memberRows, hidden = hidden)
                        }
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
                item { Spacer(modifier = Modifier.height(24.dp)) }
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

/**
 * 分区占比：环图 + 分区图例。
 *
 * 图例**只列分区行**，不再逐分类展开 —— 分类明细由紧随其后的条形图承担，
 * 这是「两图各管一个维度」的贯彻；若图例也逐分类列，就会回到旧版那张
 * 19 行的长列表，两个维度又挤回一张图里。
 *
 * 图例行可点（与点环图扇区等效），右侧带 › 提示可点 —— 可点性由箭头明示，
 * 不靠猜。读屏：整块环图已有一段摘要，图例对读屏静音避免重复念。
 */
@Composable
private fun SectionShareSection(
    shares: List<com.simpleledger.app.logic.SectionShare>,
    totalCents: Long,
    hidden: Boolean,
    selectedIndex: Int?,
    onSliceClick: (Int) -> Unit,
) {
    SectionTitle("分区占比")
    Card(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            SectionDonutChart(
                shares = shares,
                totalCents = totalCents,
                hidden = hidden,
                selectedIndex = selectedIndex,
                onSliceClick = onSliceClick,
            )
            Spacer(modifier = Modifier.height(12.dp))
            // 可点性的提示：环图本身没有「可点」的视觉暗示，这句话是唯一的入口说明
            Text(
                text = "点扇区可只看该分区的分类",
                style = SlType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // 图例逐项列出分区占比，与环图摘要内容完全重复；对读屏静音，避免听完摘要再逐行重念一遍
            Column(modifier = Modifier.clearAndSetSemantics {}) {
                shares.forEachIndexed { index, share ->
                    val selected = index == selectedIndex
                    // 图例底垫的过渡（与环图扇区加粗同 150ms slFast(Standard)）：
                    // 选中态在行间移动时，旧行底垫淡出、新行底垫淡入同时进行。
                    //
                    // 刻意**不用 animateColorAsState**（口径：2026-09-26 用户真机 A/B 拍板，
                    // 候选 B′）：Transparent(0x00000000) ↔ surfaceVariant 在 ARGB 空间做线性
                    // 插值时，alpha 0↔1 的过程里的 RGB 仍从 0 起算，中途会经过一帧「比底色更暗」
                    // 的垫（暗沉中间态）；只过渡 alpha、颜色恒为 surfaceVariant，就绕开了色相/
                    // 明度插值，得到的是「同一块纸由淡变实」，与环图上的加粗一一对应。
                    val legendPadAlpha by animateFloatAsState(
                        targetValue = if (selected) 1f else 0f,
                        animationSpec = slFast(SlMotion.Standard),
                        label = "legendPadAlpha",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 选中行用凹面垫底：与环图上加粗的扇区一一对应，两处看得见的是同一件事
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = legendPadAlpha),
                                RoundedCornerShape(3.dp),
                            )
                            .clickable { onSliceClick(index) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                // 与环图共用同一来源（分区胶带色）—— 两处各取各的迟早会漂移成
                                // 「图例的蓝不是环上那块蓝」
                                .background(
                                    tapeColor(share.section.colorIndex),
                                    RoundedCornerShape(1.dp),
                                ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            share.section.name,
                            // 图表内文字同走楷体（字族一元）；选中态只加字距——
                            // bodySm 13.5sp 在禁粗档（决策一），+0.04em 与 navTabOn 同一信号语言
                            style = if (selected) SlType.bodySm.merge(SlStatus.selectedSm) else SlType.bodySm,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (hidden) {
                                StatsCalculator.percentLabel(share.fraction)
                            } else {
                                StatsCalculator.percentLabel(share.fraction) +
                                    " · " + Money.formatCents(share.section.expense)
                            },
                            style = SlType.bodySm,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 分类金额：横向条形图。
 *
 * 标题随联动变化：未选中＝「分类金额」，选中＝「分类金额 · 只看装修」——
 * 让「当前条形图只画了谁」永远可见（环图的加粗扇区在滚动后可能已离开视口）。
 */
@Composable
private fun CategoryBarSection(
    title: String,
    rows: List<BarRow>,
    hidden: Boolean,
) {
    SectionTitle(title)
    Card(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            CategoryBarChart(rows = rows, hidden = hidden)
        }
    }
}

/**
 * 按人支出（U-7/R-18）：横向条形图，复用 [CategoryBarChart]（G3 双图方法论：
 * 长度管数值、不给颜色加语义 —— 全部条同墨色，楷体名标签管身份）。
 * 不参与 G4 双图联动（联动是「分区 ↔ 分类」两个图之间的事，与按人无关）。
 */
@Composable
private fun MemberBarSection(rows: List<BarRow>, hidden: Boolean) {
    SectionTitle(stringResource(R.string.stats_by_member))
    Card(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            CategoryBarChart(rows = rows, hidden = hidden)
        }
    }
}

/** 每日支出：柱状图 */
@Composable
private fun DailyExpenseSection(daily: List<Pair<Int, Long>>, daysInMonth: Int, hidden: Boolean) {
    SectionTitle("每日支出")
    Card(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
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
        Text(label, style = SlType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            // 统计数字 = 结余数字位（title，字族一元楷体）。
            // P2-1（fs2.0 尾数截断）：autoSize 收缩适配三栏等宽槽，绝不截尾
            style = SlType.title,
            color = color,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        // 区块标题 = titleL（22/28/600，规范 scale.use 明文）
        style = SlType.titleL,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

