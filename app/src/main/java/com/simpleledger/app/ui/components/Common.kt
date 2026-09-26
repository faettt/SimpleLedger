package com.simpleledger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.R
import com.simpleledger.app.data.settings.LocalHideAmounts
import androidx.compose.ui.text.TextStyle
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.SlButtonShape
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import java.time.YearMonth
import com.simpleledger.app.ui.icon.SlIcons

/**
 * 月份切换头部：‹ 2026年9月 ›  今天  [尾部插槽]
 *
 * [todayVisible] 由调用方决定：「今天」只在非当前月时才有意义。明细页尾部还要放
 * 「搜索」「筛选」，四个控件挤在一行会互相压迫，所以回到本月时把「今天」收起来。
 */
@Composable
fun MonthHeader(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
    todayVisible: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(SlIcons.Ui.ArrowLeft, contentDescription = "上个月")
        }
        Text(
            text = DateTimes.monthLabel(month),
            // 「页码」位：手账翻月即翻页。title（楷体，字族一元）
            style = SlType.title,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onNext) {
            Icon(SlIcons.Ui.ArrowRight, contentDescription = "下个月")
        }
        if (todayVisible) {
            TextButton(
                onClick = onToday,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("今天", style = SlType.label)
            }
        }
        trailing?.invoke()
    }
}

/**
 * 筛选 chip 统一选中样式（P2-3）：选中态 = primaryContainer 浅底 + onPrimaryContainer 文字，
 * 对比拉开同幅度。此前「浅底」与「✓ 前缀」两套选中语言并存，用户分不清哪个是选中标记 ——
 * 现在**浅底是唯一选中语言**，chip 文案里的 ✓/○/● 只表语义（对齐账目行的状态符号）。
 */
@Composable
fun slFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * 手账内页**签名笔触**（规范 §2.2）：标题下的双线 —— 2dp 主墨主线 + 1dp 弱线（宽约 76%）。
 *
 * 挂在标题 [Text] 上，双线宽度因此**跟标题文字走**（不是横贯整行），像手写划线。
 * 手绘感来自**固定的几何不对称**（硬规则 R4）：主线右端略出、弱线左端略缩 ——
 * 写死的偏移，不随重组抖动。
 *
 * ⚠️ 双线画在 Text 布局边界之外，父级若有 `clip` 会被裁掉；标题容器不要加 clip。
 */
@Composable
fun Modifier.slTitleRule(): Modifier {
    // 颜色必须在这里取好再传进 drawBehind —— drawBehind 的 lambda 不是 @Composable
    val ink = MaterialTheme.colorScheme.onSurface
    val weak = MaterialTheme.colorScheme.onSurfaceVariant
    return drawBehind {
        val y = size.height + 1.dp.toPx()
        drawLine(
            color = ink,
            start = Offset(0f, y),
            // 主线右端出 0.5dp：收笔不对称
            end = Offset(size.width + 0.5.dp.toPx(), y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Square,
        )
        drawLine(
            color = weak,
            start = Offset(0.8.dp.toPx(), y + 3.dp.toPx()),
            end = Offset(size.width * 0.76f, y + 3.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Square,
        )
    }
}

/** 支出 / 收入金额文本：支出为默认色、收入为绿色，带正负号 */
@Composable
fun MoneyText(
    amountCents: Long,
    isIncome: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = SlType.title,
) {
    val hidden = LocalHideAmounts.current
    val color = if (isIncome) incomeColor() else MaterialTheme.colorScheme.onSurface
    // 符号语序统一「−¥」/「+¥」（与 Money.formatWithSymbol 同规）。
    // 负号用真减号 U+2212：楷体子集 v2 已收录（Fonts.kt）
    val sign = if (isIncome) "+" else "−"
    Text(
        text = if (hidden) "$sign••••" else sign + Money.formatWithSymbol(kotlin.math.abs(amountCents)),
        color = color,
        // 金额：token（字族一元楷体；R1 等宽数字已随决策二作废）
        style = style,
        modifier = modifier,
    )
}

/** 空状态提示：可附带一个明确的下一步动作 */
@Composable
fun EmptyHint(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // v4：空态插图从 emoji 🗒️ 换成手绘的「翻开的手账本」（32 网格 lg 档）
                // 装饰性 → contentDescription null，语义由下方 text 承担
                Icon(
                    imageVector = SlIcons.Illustration.EmptyLg,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = text,
                // 「空态」位：body（楷体 Regular，字族一元）
                style = SlType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp),
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    // M3 Button 默认胶囊，显式收 4dp（规范 D2，P1-1）
                    shape = SlButtonShape,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * 通用确认弹窗。
 *
 * [confirmEnabled] = false 时确认按钮禁用（用于「当前不可删」这类阻塞态，如最后一个分区仍有账目）。
 *
 * 确认即关窗：确认按钮执行 [onConfirm] 后统一走 [onDismiss] 收尾（调用方无须再自行关窗，
 * 重复关窗是幂等无害的）。此前确认按钮只回调 onConfirm，调用方漏清锚点会导致弹窗不消失
 * （2026-09-26 实机问题 ①），故把「确认 = 终态」固化进组件契约。
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                enabled = confirmEnabled,
            ) {
                Text(
                    text = confirmLabel ?: stringResource(R.string.delete),
                    color = if (confirmEnabled) {
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

/** 分组标题（设置类页面） */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SlType.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
    )
}
