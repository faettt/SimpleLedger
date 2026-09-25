package com.simpleledger.app.ui.sync

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.RowKindValue
import com.simpleledger.app.data.local.entity.TrashKind
import com.simpleledger.app.logic.TrashAggregation
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.ConfirmDialog
import com.simpleledger.app.ui.components.ContentMaxWidth
import com.simpleledger.app.ui.components.ContentWidth
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.SlSnackbarHost
import com.simpleledger.app.ui.components.SlipCard
import com.simpleledger.app.ui.components.slTitleRule
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.ledger.AmountText
import com.simpleledger.app.ui.theme.SlStatus
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.ui.theme.tapeColor
import com.simpleledger.app.util.DateTimes

/*
 * 冲突回收站（U-3/R-09/R-21 + A1 整包聚合）：删除留底 + 「被修改覆盖」留底。
 *
 * 列表按整包聚合：分区删除 = 「分区『X』及 N 笔账目」一个包，恢复 = 整包恢复；
 * 单笔 / 散条照旧按行展示。行卡片沿 F4：原分区胶带色 3dp 左色条。
 * 恢复 ✓ 复用收入松烟绿、彻底删除 ✕ 复用支出朱砂（状态语义豁免口，零新增色值）。
 */

@Composable
fun ConflictTrashScreen(
    onBack: () -> Unit,
    layout: WindowLayout = WindowLayout.Compact,
    viewModel: ConflictTrashViewModel = viewModel(factory = ConflictTrashViewModel.Factory),
) {
    val groups by viewModel.groups.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(messageText(context, m))
        viewModel.clearMessage()
    }

    Scaffold(
        // ⚠️ 外层 AppRoot 已消费 systemBars insets，内层归零防双重避让
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
        snackbarHost = { SlSnackbarHost(snackbarHostState) },
    ) { padding ->
        val body: @Composable () -> Unit = {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(SlIcons.Ui.ArrowLeft, contentDescription = stringResource(R.string.back))
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.trash_title),
                            style = SlType.headline,
                            modifier = Modifier.slTitleRule(),
                        )
                    }
                    // 手动清空（二次确认在下方 ConfirmDialog）
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = groups.isNotEmpty(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(stringResource(R.string.trash_clear_all), style = SlType.label)
                    }
                }
                Text(
                    text = stringResource(R.string.trash_auto_note),
                    style = SlType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                )

                if (groups.isEmpty()) {
                    // 空态：楷体一句话
                    EmptyHint(stringResource(R.string.trash_empty))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    ) {
                        items(groups, key = { it.deleteOpId }) { group ->
                            TrashGroupCard(
                                group = group,
                                onRestore = { viewModel.restore(group.deleteOpId) },
                                onPurge = { viewModel.purge(group.deleteOpId) },
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        if (layout == WindowLayout.Expanded) {
            ContentWidth(maxWidth = ContentMaxWidth.Standard) { body() }
        } else {
            body()
        }
    }

    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.trash_clear_confirm_title),
            text = stringResource(R.string.trash_clear_confirm),
            confirmLabel = stringResource(R.string.confirm),
            onConfirm = {
                showClearConfirm = false
                viewModel.clearAll()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

/** 留底纸片（按聚合包）：原分区 3dp 色条 + 包标题 + 冲突说明 + 时间摘要 + 恢复/彻底删除 */
@Composable
private fun TrashGroupCard(
    group: TrashUiGroup,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val context = LocalContext.current
    val head = group.head
    // ——— 成句文案在 UI 层组装（strings.xml 收口），VM 只给原料 ———
    val deletedByName = head.deletedByName.ifBlank { stringResource(R.string.trash_member_unknown) }
    val actorLabel = stringResource(
        if (head.conflictActorIsSelf) R.string.trash_actor_self else R.string.trash_actor_other,
    )
    // 「被 X 删除，被 Y 修改」（删改冲突）/「被 X 删除」（纯删除）；OVERWRITE 标「被修改覆盖」
    val line1 = when (head.entity.kind) {
        TrashKind.OVERWRITE ->
            stringResource(R.string.trash_line_overwrite) +
                if (head.entity.conflict) {
                    "，" + stringResource(R.string.trash_line_overwrite_by, actorLabel)
                } else {
                    ""
                }
        else ->
            stringResource(R.string.trash_line_deleted, deletedByName) +
                if (head.entity.conflict) {
                    "，" + stringResource(R.string.trash_line_modified, actorLabel)
                } else {
                    ""
                }
    }
    // 标题行：分区包 = 「分区『X』及 N 笔账目」；单笔 = 备注摘要；散条按行类型给名
    val title = when {
        group.kind == TrashAggregation.GROUP_SECTION ->
            stringResource(R.string.trash_group_section_title, head.summaryName, group.entryCount)
        head.entity.rowKind == RowKindValue.SECTION ->
            stringResource(R.string.trash_summary_section, head.summaryName)
        head.entity.rowKind == RowKindValue.CATEGORY ->
            stringResource(R.string.trash_summary_category, head.summaryName)
        head.entity.rowKind == RowKindValue.IMAGE ->
            stringResource(R.string.trash_summary_image)
        else -> head.summaryName
    }
    SlipCard(
        edgeColor = tapeColor(head.sectionColorIndex),
        edgeWidth = 3.dp,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = SlType.body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        append(trashTimeText(context, head.entity.deletedAt))
                        append(" · ").append(line1)
                        // 分区包补一句贴图数（有贴图时）
                        if (group.kind == TrashAggregation.GROUP_SECTION && group.imageCount > 0) {
                            append(" · ")
                            append(stringResource(R.string.trash_group_section_images, group.imageCount))
                        }
                    },
                    style = SlType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            val amountCents = head.amountCents
            if (amountCents != null) {
                AmountText(amountCents = amountCents, isIncome = head.isIncome)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 恢复 ✓：复用收入松烟绿系 token（状态语义豁免口）；分区包按钮标「整包恢复」
            val isPackage = group.kind == TrashAggregation.GROUP_SECTION
            TextButton(onClick = onRestore) {
                Text(
                    text = stringResource(
                        if (isPackage) R.string.trash_group_restore else R.string.trash_restore,
                    ),
                    style = SlType.bodySm.merge(SlStatus.successSm),
                    color = incomeColor(),
                )
            }
            // 彻底删除 ✕：复用支出朱砂系 token（同上）
            TextButton(onClick = onPurge) {
                Text(
                    text = stringResource(R.string.trash_purge),
                    style = SlType.bodySm.merge(SlStatus.errorSm),
                    color = expenseColor(),
                )
            }
        }
    }
}

private fun trashTimeText(context: Context, at: Long): String =
    DateTimes.dateLabel(DateTimes.toLocalDate(at)) + " " +
        DateTimes.timeLabel(DateTimes.toLocalTime(at))

private fun messageText(context: Context, event: TrashEvent): String = when (event.type) {
    TrashMessage.RESTORE_OK -> context.getString(R.string.trash_restored_done)
    TrashMessage.RESTORE_REHOME -> context.getString(
        R.string.trash_restored_rehome,
        event.rehomeSectionName ?: "",
    )
    TrashMessage.PURGE_OK -> context.getString(R.string.trash_purged_done)
    TrashMessage.CLEAR_OK -> context.getString(R.string.trash_cleared)
    TrashMessage.OP_FAILED -> context.getString(R.string.trash_action_failed)
}
