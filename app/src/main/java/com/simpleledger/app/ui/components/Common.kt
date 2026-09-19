package com.simpleledger.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import java.time.YearMonth

/** 月份切换头部：‹ 2026年9月 ›  今天 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthHeader(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
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
        TextButton(onClick = onToday) {
            Text("今天")
        }
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
    val color = if (isIncome) incomeColor() else MaterialTheme.colorScheme.onSurface
    val sign = if (isIncome) "+" else "-"
    Text(
        text = sign + Money.formatWithSymbol(kotlin.math.abs(amountCents)),
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

/** 空状态提示 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🗒️", fontSize = 42.sp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 通用确认弹窗 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 饼图配色循环 */
val ChartColors = listOf(
    Color(0xFF0F766E), Color(0xFF0E7490), Color(0xFF7C3AED), Color(0xFFDB2777),
    Color(0xFFEA580C), Color(0xFF16A34A), Color(0xFF2563EB), Color(0xFFCA8A04),
    Color(0xFF9333EA), Color(0xFFDC2626),
)
