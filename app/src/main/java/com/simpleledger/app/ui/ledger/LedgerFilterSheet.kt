package com.simpleledger.app.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.ui.components.slFilterChipColors
import com.simpleledger.app.ui.icon.slCategoryIcon
import com.simpleledger.app.ui.theme.SlButtonShape
import com.simpleledger.app.ui.theme.slSegmentShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import com.simpleledger.app.data.local.entity.ReimburseState

/*
 * 筛选面板：类型（全部/支出/收入）+ 分类多选 + 清除/完成。
 * 由 LedgerScreen 的 ModalBottomSheet 承载。
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterSheetContent(
    state: LedgerUiState,
    onTypeChange: (Int?) -> Unit,
    onCategoryChange: (Long?) -> Unit,
    /** 核对维：null = 全部 / false = 待核对 / true = 已核对 */
    onReconcileChange: (Boolean?) -> Unit,
    /** 报销维：null = 全部 / 其余取 [ReimburseState] 取值 */
    onReimburseChange: (Int?) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(R.string.filter),
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
                    shape = slSegmentShape(index = index, count = options.size),
                ) {
                    Text(
                        text = when (type) {
                            EntryType.EXPENSE -> stringResource(R.string.expense)
                            EntryType.INCOME -> stringResource(R.string.income)
                            else -> "全部"
                        },
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // ---- 状态维两组（规范 §3.5「筛选：面板加两组状态维」/ §559「用两组 chip 分栏」）----
        // 两组各自独立、可自由组合（待核对 + 待报销 = 也没对也还没报），
        // 这正是 A2/D4 把两个字段做成正交的收益 —— 面板不必再加「组合模式」。
        // 快捷 chip 行只放最常用的两个开关，完整的四态选择收在这里。
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            stringResource(R.string.filter_group_reconcile),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf<Boolean?>(null, false, true).forEach { value ->
                FilterChip(
                    selected = state.filters.reconciled == value,
                    onClick = { onReconcileChange(value) },
                    // M3 1.4 的 Chip 不内置 48dp 触控（已解包核实），显式补（R5 / P1-8）
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    colors = slFilterChipColors(),
                    label = {
                        Text(
                            text = when (value) {
                                // 符号对齐账目行语义（P2-3）：✓=已核对、待核对空槽无符号
                                true -> "✓ " + stringResource(R.string.status_reconciled)
                                false -> stringResource(R.string.status_pending_reconcile)
                                else -> stringResource(R.string.filter_all)
                            },
                            fontSize = 12.5.sp,
                        )
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            stringResource(R.string.filter_group_reimburse),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf<Int?>(null, ReimburseState.NONE, ReimburseState.PENDING, ReimburseState.CLEARED)
                .forEach { value ->
                    FilterChip(
                        selected = state.filters.reimburseState == value,
                        onClick = { onReimburseChange(value) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        colors = slFilterChipColors(),
                        label = {
                            Text(
                                text = when (value) {
                                    ReimburseState.PENDING -> "○ " + stringResource(R.string.status_reimburse_pending)
                                    ReimburseState.CLEARED -> "● " + stringResource(R.string.status_reimburse_cleared)
                                    ReimburseState.NONE -> stringResource(R.string.status_reimburse_none)
                                    else -> stringResource(R.string.filter_all)
                                },
                                fontSize = 12.5.sp,
                            )
                        },
                    )
                }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(stringResource(R.string.category), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            state.categories.forEach { category ->
                FilterChip(
                    selected = state.filters.categoryId == category.id,
                    onClick = { onCategoryChange(category.id) },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                    colors = slFilterChipColors(),
                    label = {
                        // v4：分类筛选 chip 用「图标 + 名称」渲染（emoji 退场）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = slCategoryIcon(category.iconId),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(category.name, fontSize = 12.5.sp)
                        }
                    },
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
                Text(stringResource(R.string.filter_clear_all))
            }
            Button(onClick = onDismiss, shape = SlButtonShape, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.section_sort_done))
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}