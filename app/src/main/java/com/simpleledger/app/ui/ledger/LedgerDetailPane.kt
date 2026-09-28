package com.simpleledger.app.ui.ledger

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import com.simpleledger.app.ui.theme.SlMotion
import com.simpleledger.app.ui.theme.SlipShape
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.slStandard
import com.simpleledger.app.ui.theme.slTween
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import java.io.File

/*
 * 大屏右侧详情栏：展示选中账目的金额、备注、贴图与所属分区。
 * 贴图可点击放大查看，放大层内提供「保存到相册」（D3 分档流程由 LedgerScreen 承担）。
 * 编辑宿主（EntryEditHost）不在此文件——它由 LedgerScreen 依据窗口形态选择承载方式。
 */

@Composable
internal fun EntryDetailPane(
    full: EntryFull?,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    /** 贴图「保存到相册」入口（批次 B）；null = 放大层不显示保存动作 */
    onSaveImage: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hidden = LocalHideAmounts.current

    // 贴图放大查看（与 EntryEditForm 表单内贴图同款交互）。键在选中账目 id 上：
    // 切换账目时放大层随之收起，上一笔的贴图不盖在新一笔的详情上。
    var enlargedImagePath by remember(full?.entry?.id) { mutableStateOf<String?>(null) }

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
                // U-6 成员标识：头部 meta 行追加「· 成员名」（楷体小字，与列表同一口径）
                val memberLabel = full.member?.name ?: stringResource(R.string.entry_member_unknown)
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
                            append(" · $memberLabel")
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
                                    contentDescription = stringResource(R.string.a11y_entry_image_tap_enlarge),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(SlipShape)
                                        .clickable { enlargedImagePath = image.filePath },
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

        // 贴图放大查看 + 「保存到相册」（批次 B）。进出场与 EntryEditForm 的贴图放大层
        // 同规格（纸片菜单语言）：进场标准档 PaperOut 淡入 + 从 0.96 居中放大（纸被轻放），
        // 出场快档 PaperIn 淡出 + 缩回（纸被抽走）。
        //
        // enlargedImagePath 置 null 只应触发退场：visible 已为 false 而内容仍在播退场动画，
        // 仿 EntryEditHostGate 的 lastValue 保尾值模式，用 lastEnlargedPath 锁住最后一次
        // 非空路径——否则退场期间图片瞬间消失、只剩空壳动画。
        var lastEnlargedPath by remember(full?.entry?.id) { mutableStateOf(enlargedImagePath) }
        if (enlargedImagePath != null) {
            lastEnlargedPath = enlargedImagePath
        }
        AnimatedVisibility(
            visible = enlargedImagePath != null,
            enter = fadeIn(slStandard(SlMotion.PaperOut)) +
                scaleIn(slStandard(SlMotion.PaperOut), 0.96f, TransformOrigin.Center),
            exit = fadeOut(slTween(SlMotion.FastMs, SlMotion.PaperIn)) +
                scaleOut(slTween(SlMotion.FastMs, SlMotion.PaperIn), 0.96f, TransformOrigin.Center),
        ) {
            lastEnlargedPath?.let { path ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.92f))
                        .clickable { enlargedImagePath = null },
                    contentAlignment = Alignment.Center,
                ) {
                    // 放大图装饰性（contentDescription null）：语义由下方保存按钮与关闭提示承担
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
                    ) {
                        // 保存动作交 LedgerScreen 走 D3 分档：29+ 直写相册 / 26–28 转 SAF，
                        // 结果以 Snackbar 反馈；未接入口（onSaveImage == null）则不显示
                        if (onSaveImage != null) {
                            TextButton(onClick = { onSaveImage(path) }) {
                                Text(
                                    text = stringResource(R.string.save_to_gallery),
                                    color = Color.White,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.image_viewer_close_hint),
                            color = Color.White.copy(alpha = 0.7f),
                            style = SlType.bodySm,
                        )
                    }
                }
            }
        }
    }
}

private fun percentOf(part: Long, total: Long): String {
    if (total <= 0) return "0%"
    return "${((part.toDouble() / total) * 100).toInt()}%"
}