package com.simpleledger.app.ui.ledger

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.ui.amountSpeech
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.icon.slCategoryIcon
import com.simpleledger.app.ui.theme.BarShape
import com.simpleledger.app.ui.theme.SlButtonShape
import com.simpleledger.app.ui.theme.SlipShape
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import java.io.File

/*
 * 大屏右侧详情栏：展示选中账目的金额、备注、贴图与所属分区。
 * 编辑宿主（EntryEditHost）不在此文件——它由 LedgerScreen 依据窗口形态选择承载方式。
 */

@Composable
internal fun EntryDetailPane(
    full: EntryFull?,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hidden = LocalHideAmounts.current

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (full == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // v4：emoji 👈 → 手绘插图 hint-lg（32 网格 lg 档）；装饰性 → 描述置 null
                    Icon(
                        imageVector = SlIcons.Illustration.HintLg,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Text(
                    text = "从左侧选择一笔账目查看详情",
                    style = SlType.bodySm,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            return@Box
        }

        val isIncome = full.entry.type == EntryType.INCOME
        val section = full.section

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(SlButtonShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    // v4：emoji → 手绘图标（兜底 43 = tag）；装饰性，读屏由下方文字承担
                    Icon(
                        imageVector = slCategoryIcon(full.category?.iconId ?: 43),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = full.category?.name ?: stringResource(R.string.uncategorized),
                        style = SlType.title,
                    )
                    Text(
                        text = buildString {
                            append(DateTimes.dateLabel(DateTimes.toLocalDate(full.entry.entryTime)))
                            append(" ")
                            append(DateTimes.timeLabel(DateTimes.toLocalTime(full.entry.entryTime)))
                            section?.let { append(" · ${it.name}") }
                        },
                        style = SlType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { onEdit(full.entry.id) }, shape = SlButtonShape) { Text(stringResource(R.string.edit)) }
                Spacer(modifier = Modifier.width(8.dp))
                // 与列表长按一致：直接删除 + 4 秒撤销，不弹确认框
                TextButton(onClick = { onDelete(full.entry.id) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("金额", style = SlType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (hidden) {
                            "••••"
                        } else {
                            // 符号语序统一「−¥」/「+¥」；真减号 U+2212（楷体子集 v2 已收录）
                            (if (isIncome) "+" else "−") +
                                Money.formatWithSymbol(full.entry.amountCents)
                        },
                        // 主金额展示 = display（40/44/700，字族一元楷体）
                        style = SlType.display,
                        color = if (isIncome) incomeColor() else expenseColor(),
                        // 视觉靠 −/+ 与颜色表达方向，读屏两条都拿不到；这里显式给出方向词+中文金额。
                        // 隐私模式下 clearAndSetSemantics 用「支出，金额已隐藏」覆盖，不泄露真值。
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = amountSpeech(isIncome, full.entry.amountCents, hidden)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("备注", style = SlType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = full.entry.note.ifBlank { "无备注" },
                        style = SlType.body,
                        color = if (full.entry.note.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            if (full.images.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "贴图 ${full.images.size} 张",
                            style = SlType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(full.images, key = { it.id }) { image ->
                                AsyncImage(
                                    model = File(image.filePath),
                                    contentDescription = "贴图",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(SlipShape),
                                )
                            }
                        }
                    }
                }
            }

            if (section != null && (section.note.isNotBlank() || section.budgetCents > 0)) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "所属分区",
                            style = SlType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // v4：所属分区用「图标 + 名称」渲染（emoji 退场）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = slCategoryIcon(section.iconId),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = section.name,
                                // 分区名装饰位：titleK（楷体 Regular）
                                style = SlType.title,
                            )
                        }
                        if (section.note.isNotBlank()) {
                            Text(
                                text = section.note,
                                style = SlType.bodySm,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (section.budgetCents > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "月度预算",
                                    style = SlType.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = if (hidden) {
                                        "••••"
                                    } else {
                                        Money.formatWithSymbol(section.budgetCents)
                                    },
                                    style = SlType.bodySm,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = {
                                    if (section.budgetCents > 0) {
                                        (full.entry.amountCents.toFloat() / section.budgetCents)
                                            .coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(BarShape),
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                            Text(
                                "本笔占预算的 ${percentOf(full.entry.amountCents, section.budgetCents)}",
                                style = SlType.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun percentOf(part: Long, total: Long): String {
    if (total <= 0) return "0%"
    return "${((part.toDouble() / total) * 100).toInt()}%"
}