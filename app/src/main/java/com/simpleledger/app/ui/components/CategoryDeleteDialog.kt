package com.simpleledger.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simpleledger.app.R
import com.simpleledger.app.data.repo.CategoryDeleteImpact
import com.simpleledger.app.ui.icon.slCategoryIcon
import com.simpleledger.app.ui.theme.SlType

/**
 * 删除分类确认框（B4/FR-41 定案：**去向单选**）。
 *
 * - 该分类下有账目 → 正文 = 「将迁移到」提示 + 单选列表（同类型其余分类 +
 *   「未分类」哨兵，哨兵恒排末尾），默认预选「未分类」；
 * - 该分类下无账目 → 正文 = 「可安全删除」；哨兵自身不可删（blockedReason）；
 * - impact 为 null（影响描述异步加载中）→ 确认保持禁用（P2-4：防首帧闪现可点）。
 *
 * 确认回调携带用户选中的去向 id（null = 交由数据层落默认「未分类」）。
 */
@Composable
fun CategoryDeleteDialog(
    title: String,
    impact: CategoryDeleteImpact?,
    onConfirm: (destinationId: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 默认预选 = 「未分类」哨兵（CategoryDeletePlan）；impact 换目标时重置
    var selectedId by remember(impact) { mutableStateOf(impact?.defaultDestinationId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when {
                impact == null -> {
                    // 影响描述加载中：先给一个最小高度占位，确认按钮保持禁用
                    Spacer(modifier = Modifier.height(8.dp))
                }
                impact.blockedReason != null -> {
                    Text(
                        text = impact.blockedReason,
                        style = SlType.bodySm,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                impact.entryCount == 0 -> {
                    Text(
                        text = stringResource(R.string.delete_category_body_none),
                        style = SlType.bodySm,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Column {
                        Text(
                            text = stringResource(
                                R.string.delete_category_destinations_hint,
                                impact.entryCount,
                            ),
                            style = SlType.bodySm,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            impact.destinations.forEach { destination ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedId = destination.id }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selectedId == destination.id,
                                        onClick = { selectedId = destination.id },
                                    )
                                    Icon(
                                        imageVector = slCategoryIcon(destination.iconId),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .padding(start = 2.dp)
                                            .width(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = destination.name,
                                        style = SlType.body,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 阻塞态（哨兵被删 / 分类不存在 / 无候选）一律禁用；无账目可直接删
            val enabled = impact != null &&
                impact.blockedReason == null &&
                (impact.entryCount == 0 || selectedId != null)
            TextButton(onClick = { onConfirm(selectedId) }, enabled = enabled) {
                Text(
                    text = stringResource(R.string.delete),
                    color = if (enabled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
