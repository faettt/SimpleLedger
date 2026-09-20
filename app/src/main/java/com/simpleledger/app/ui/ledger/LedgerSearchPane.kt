package com.simpleledger.app.ui.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.util.DateTimes
import java.time.YearMonth

/**
 * 全局搜索界面。
 *
 * 设计取舍：
 * - 不叠加浮层，而是**整体替换**列表栏。搜索是「换一套浏览方式」，浮层会让下面的
 *   月份 / 概览 / 分区条留在视野里争夺注意力，且小屏上浮层空间本就紧张。
 * - 结果跨全部时间，动辄横跨数年，因此先按**月份**归并再按天分组：月份是主要定位锚点，
 *   天是次要粒度。`searchResults` 已按时间倒序，直接 groupBy 即可保住倒序。
 * - 关键词为空不查库、也不显示空态，而是给出「能搜什么」的说明——空态文案比一张白屏更有引导性。
 *
 * 结果行与天分组标题都沿用列表页的 [EntryRow] / [DayHeader]（含长按快捷菜单），
 * 保证两处的金额对齐、语义色、点击/长按手感完全一致；差异只在外壳。
 */
@Composable
internal fun LedgerSearchPane(
    keyword: String,
    filterType: Int?,
    results: List<DayGroup>,
    truncated: Boolean,
    sections: List<SectionEntity>,
    allCategories: List<CategoryEntity>,
    onQueryChange: (String) -> Unit,
    onToggleType: () -> Unit,
    onBack: () -> Unit,
    onResultClick: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    onMoveTo: (Long, Long, Long?) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    // 进入搜索即聚焦：省掉「再点一下输入框」这一步，键盘也随焦点自动弹出
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    var moveTarget by remember { mutableStateOf<EntryFull?>(null) }

    // groupBy 是 LinkedHashMap，保序；外层再按月份 key 顺序输出即为时间倒序
    val monthGroups = remember(results) {
        results.groupBy { YearMonth.from(it.date) }
            .map { (month, days) -> MonthGroup(month = month, days = days) }
    }
    val hasKeyword = keyword.isNotBlank()

    Column(modifier = modifier.fillMaxSize()) {
        SearchHeader(
            keyword = keyword,
            filterType = filterType,
            focusRequester = focusRequester,
            onQueryChange = onQueryChange,
            onToggleType = onToggleType,
            onBack = onBack,
        )

        when {
            !hasKeyword -> EmptyHint(
                text = "输入关键词搜索全部账目\n可搜：备注 · 分类名 · 分区名或备注 · 金额数字",
                modifier = Modifier.fillMaxSize(),
            )

            monthGroups.isEmpty() -> EmptyHint(
                text = noResultText(keyword, filterType),
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                monthGroups.forEach { group ->
                    item(key = "month_${group.month}") { SearchMonthHeader(group.month) }
                    group.days.forEach { day ->
                        item(key = "day_${day.date}") {
                            DayHeader(
                                dateLabel = DateTimes.dayLabel(day.date),
                                expenseCents = day.expenseCents,
                                incomeCents = day.incomeCents,
                            )
                        }
                        items(day.entries, key = { it.entry.id }) { full ->
                            EntryRow(
                                full = full,
                                selected = false,
                                onClick = { onResultClick(full.entry.id) },
                                onDuplicate = { onDuplicate(full.entry.id) },
                                onMove = { moveTarget = full },
                                onDelete = { onDelete(full.entry.id) },
                            )
                        }
                        item(key = "space_${day.date}") { Spacer(modifier = Modifier.height(6.dp)) }
                    }
                }
                // 截断提示独立于月份分组：它是整份结果的终点说明，不属于任何一个月。
                // 单条 item 而非分组内的 footer，才能保证「第 200 条恰为某天最后一条」时
                // 仍然渲染——否则列表没有视觉终点，用户会把截断误当成「真的只有这些」。
                if (truncated) {
                    item(key = "truncated_notice") { TruncatedNotice() }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    // 长按「移动到其它分区」的结果行：复用与列表页完全相同的分区选择弹窗
    moveTarget?.let { target ->
        MoveSectionDialog(
            entry = target,
            sections = sections,
            allCategories = allCategories,
            onDismiss = { moveTarget = null },
            onConfirm = { sectionId, newCategoryId ->
                onMoveTo(target.entry.id, sectionId, newCategoryId)
                moveTarget = null
            },
        )
    }
}

/** 按月份归并后的一组天（`days` 保持时间倒序） */
private data class MonthGroup(val month: YearMonth, val days: List<DayGroup>)

/** 搜索头部：返回 + 输入框 + 收支过滤 chip */
@Composable
private fun SearchHeader(
    keyword: String,
    filterType: Int?,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onToggleType: () -> Unit,
    onBack: () -> Unit,
) {
    val clearButton: (@Composable () -> Unit)? = if (keyword.isNotEmpty()) {
        {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, contentDescription = "清除关键词")
            }
        }
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.back))
        }
        OutlinedTextField(
            value = keyword,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = { Text("搜索备注 / 分类 / 分区 / 金额", fontSize = 13.5.sp, maxLines = 1) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailingIcon = clearButton,
        )
        Spacer(modifier = Modifier.width(6.dp))
        // 三态循环（不限 → 仅支出 → 仅收入），一次点击即可收敛到想要的类型
        FilterChip(
            selected = filterType != null,
            onClick = onToggleType,
            label = { Text(filterLabel(filterType), fontSize = 12.5.sp) },
        )
    }
}

/** 月份标题：搜索结果的最高层级锚点（跨年份时 `01` 补零便于纵向扫视） */
@Composable
private fun SearchMonthHeader(month: YearMonth) {
    Text(
        text = "%04d年%02d月".format(month.year, month.monthValue),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

/**
 * 截断提示：结果达到上限时压在列表最末尾。
 * 不做成按钮——用户此刻并不需要「加载更多」（数据层就没有这个能力），
 * 只需要知道「还有更多，换个词可能更快找到」，以及列表确实到此为止。
 */
@Composable
private fun TruncatedNotice() {
    Text(
        text = "结果较多，仅显示前 200 条 · 试试更具体的关键词",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** 收支过滤当前态的文案 */
private fun filterLabel(type: Int?): String = when (type) {
    EntryType.EXPENSE -> "仅支出"
    EntryType.INCOME -> "仅收入"
    else -> "不限"
}

/**
 * 无结果文案。
 *
 * 建议语必须跟着真实原因走：过滤点亮时，用户多半是**被自己刚点的过滤挡掉**了，
 * 此时再让他「试试金额 / 分区名」是把人往错方向引——正确的下一步是改回「不限」。
 */
private fun noResultText(keyword: String, filterType: Int?): String {
    val filterHint = when (filterType) {
        EntryType.EXPENSE -> "（当前仅看支出）"
        EntryType.INCOME -> "（当前仅看收入）"
        else -> ""
    }
    val advice = if (filterType == null) {
        "换个关键词，或试试金额 / 分区名"
    } else {
        "换个关键词，或点上方过滤改为「不限」"
    }
    return "没有找到与「$keyword」匹配的账目$filterHint\n$advice"
}