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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.data.local.entity.EntryType

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