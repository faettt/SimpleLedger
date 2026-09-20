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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.simpleledger.app.R
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.ui.theme.TabularNums
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
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月")
        }
        Text(
            text = DateTimes.monthLabel(month),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下个月")
        }
        if (todayVisible) {
            TextButton(
                onClick = onToday,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("今天", fontSize = 13.sp)
            }
        }
        trailing?.invoke()
    }
}

/** 支出 / 收入金额文本：支出为默认色、收入为绿色，带正负号 */
@Composable
fun MoneyText(
    amountCents: Long,
    isIncome: Boolean,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
) {
    val hidden = LocalHideAmounts.current
    val color = if (isIncome) incomeColor() else MaterialTheme.colorScheme.onSurface
    val sign = if (isIncome) "+" else "-"
    Text(
        text = if (hidden) "$sign••••" else sign + Money.formatWithSymbol(kotlin.math.abs(amountCents)),
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        style = TabularNums,
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
                    modifier = Modifier.size(34.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, start = 32.dp, end = 32.dp),
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
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
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
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
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp),
    )
}
