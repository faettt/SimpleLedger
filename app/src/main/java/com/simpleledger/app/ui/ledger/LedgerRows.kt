package com.simpleledger.app.ui.ledger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.ui.theme.TabularNums
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money

/*
 * 共享的账目展示组件：日分组标题 / 账目行 / 金额文本。
 *
 * 这几个组件同时被「列表栏」与「搜索结果栏」（LedgerSearchPane）复用，金额文本还被统计页
 * （StatsScreen）引用，因此单独成文件、保持 `internal` / `public` 可见性不变。
 * 它们在同一个包 `com.simpleledger.app.ui.ledger` 内，全限定名与拆分前完全一致，
 * 故所有既有引用（含跨包的 `import ...ledger.AmountText`）无需任何改动。
 */

/** 日分组标题：日期 + 当日收支汇总 */
@Composable
internal fun DayHeader(dateLabel: String, expenseCents: Long, incomeCents: Long) {
    val hidden = LocalHideAmounts.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateLabel,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (hidden) {
                "支 ••••　收 ••••"
            } else {
                "支 ${Money.formatCents(expenseCents)}　收 ${Money.formatCents(incomeCents)}"
            },
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = TabularNums,
        )
    }
}

/**
 * 账目行：分类名（主）· 时间/分区/标记（次）· 金额（视觉最重）。
 * 备注不展开全文，仅用 💬 标记存在性——明细页的首要任务是快速扫视。
 *
 * 长按弹出快捷菜单（复制一笔 / 移动到其它分区 / 删除）。菜单锚定在本行而非手指坐标：
 * 行内交互用锚定行更可预期，也能保住 combinedClickable 带来的涟漪反馈与读屏语义
 * （自行处理指针事件会失去这两者）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryRow(
    full: EntryFull,
    selected: Boolean,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val isIncome = full.entry.type == EntryType.INCOME
    val hidden = LocalHideAmounts.current
    val meta = buildString {
        append(DateTimes.timeLabel(DateTimes.toLocalTime(full.entry.entryTime)))
        full.section?.let { append(" · ${it.emoji}${it.name}") }
        if (full.images.isNotEmpty()) append(" · 📷${full.images.size}")
        if (full.entry.note.isNotBlank()) append(" · 💬")
    }
    var menuOpen by remember { mutableStateOf(false) }

    // 读屏串：行内三个 Text 节点会被 TalkBack 分三次朗读，失去「这是一笔账」的整体语义。
    // 顺序按「最重要在前」：方向 → 分类 → 金额 → 时间 → 分区 → 备注/单据。
    // 方向词必须显式给出：视觉靠 −/+ 与颜色，读屏念不出颜色、也不会把符号当方向。
    val speech = buildString {
        append(if (isIncome) "收入" else "支出")
        append("，")
        append(full.category?.name ?: "未分类")
        append("，")
        // 隐私模式：绝不把真实金额交给读屏，否则打码只挡了眼睛、挡不住耳朵
        append(if (hidden) "金额已隐藏" else Money.toChineseSpeech(full.entry.amountCents))
        append("，")
        append(DateTimes.timeLabel(DateTimes.toLocalTime(full.entry.entryTime)))
        full.section?.let { append("，${it.name}") }
        if (full.entry.note.isNotBlank()) append("，有备注")
        if (full.images.isNotEmpty()) append("，有 ${full.images.size} 张单据")
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
                // mergeDescendants 把行内子文本合并成一个语义节点；下拉菜单是 Box 的兄弟节点、
                // 不在本 Row 内，因此不会被吞进来（否则会一次念出全部菜单项）
                .semantics(mergeDescendants = true) { contentDescription = speech }
                .combinedClickable(
                    onClick = onClick,
                    onLongClickLabel = "更多操作",
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isIncome) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(full.category?.emoji ?: "🏷️", fontSize = 17.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = full.category?.name ?: "未分类",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            AmountText(amountCents = full.entry.amountCents, isIncome = isIncome)
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            DropdownMenuItem(
                text = { Text("复制一笔") },
                onClick = {
                    menuOpen = false
                    onDuplicate()
                },
            )
            DropdownMenuItem(
                text = { Text("移动到其它分区") },
                onClick = {
                    menuOpen = false
                    onMove()
                },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/* ---------------------------------------------------------------- 金额 */

/** 列表内金额：语义色 + 正负号 + 等宽数字，保证纵向可扫视 */
@Composable
fun AmountText(
    amountCents: Long,
    isIncome: Boolean,
    fontSize: TextUnit = 15.sp,
) {
    val hidden = LocalHideAmounts.current
    Text(
        text = if (hidden) {
            "••••"
        } else {
            (if (isIncome) "+" else "−") + Money.formatWithSymbol(kotlin.math.abs(amountCents))
        },
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        color = if (isIncome) incomeColor() else expenseColor(),
        style = TabularNums,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

/** 移动到其它分区：列出全部分区，选中即完成（分区数量有限，无需二次确认） */
@Composable
internal fun MoveSectionDialog(
    entry: EntryFull,
    sections: List<SectionEntity>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val targets = sections.filter { it.id != entry.entry.sectionId }
    // 隐私模式：对话框抬头即以真实金额列明「要移动的是哪一笔」，是容易被忽略的视觉泄露点
    val hidden = LocalHideAmounts.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到其它分区") },
        text = {
            Column {
                Text(
                    text = "${entry.category?.emoji ?: ""}${entry.category?.name ?: "未分类"} · " +
                        (if (hidden) "金额已隐藏" else Money.formatWithSymbol(entry.entry.amountCents)) +
                        "　当前：${entry.section?.emoji ?: ""}${entry.section?.name ?: "无分区"}",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (targets.isEmpty()) {
                    Text("没有其它分区可选", fontSize = 13.sp)
                } else {
                    targets.forEach { section ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(section.id) }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${section.emoji} ${section.name}", fontSize = 14.5.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}