package com.simpleledger.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.logic.CategoryShare
import com.simpleledger.app.util.Money

/**
 * 环形饼图：分类支出占比。
 * 中心显示当月支出总额。
 */
@Composable
fun CategoryPieChart(
    shares: List<CategoryShare>,
    totalCents: Long,
    modifier: Modifier = Modifier,
) {
    val chartColors = ChartColors
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val holeColor = MaterialTheme.colorScheme.surface

    Box(modifier = modifier.fillMaxWidth().aspectRatio(1.6f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 56f
            val diameter = minOf(size.width, size.height) - stroke * 2
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)

            if (shares.isEmpty()) {
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            } else {
                var startAngle = -90f
                shares.forEachIndexed { index, share ->
                    val sweep = (share.fraction * 360f).toFloat()
                    drawArc(
                        color = chartColors[index % chartColors.size],
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                    startAngle += sweep
                }
            }
            // 中心挖空成环
            drawCircle(
                color = holeColor,
                radius = (diameter - stroke * 2) / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("总支出", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                Money.formatWithSymbol(totalCents),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 每日支出柱状图。
 * @param daily (几号, 当日支出分)，最多 daysInMonth 个点
 */
@Composable
fun DailyBarChart(
    daily: List<Pair<Int, Long>>,
    daysInMonth: Int,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(2.2f).padding(vertical = 4.dp)) {
        if (daysInMonth <= 0) return@Canvas
        val maxCents = daily.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
        val slot = size.width / daysInMonth
        val barWidth = slot * 0.55f
        val chartHeight = size.height * 0.86f

        // 底部轴线
        drawLine(
            color = axisColor,
            start = Offset(0f, chartHeight),
            end = Offset(size.width, chartHeight),
            strokeWidth = 2f,
        )

        daily.forEach { (day, cents) ->
            val barHeight = (cents.toFloat() / maxCents) * (chartHeight - 8f)
            if (barHeight > 1f) {
                drawRect(
                    color = barColor,
                    topLeft = Offset(slot * (day - 1) + (slot - barWidth) / 2f, chartHeight - barHeight),
                    size = Size(barWidth, barHeight),
                )
            }
        }

        // 日期刻度：1 / 中旬 / 月末
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(180, 128, 128, 128)
            textSize = 24f
            isAntiAlias = true
        }
        listOf(1, daysInMonth / 2, daysInMonth).forEach { day ->
            val x = slot * (day - 1) + slot / 2f
            drawContext.canvas.nativeCanvas.drawText(
                "$day",
                x - paint.measureText("$day") / 2f,
                size.height,
                paint,
            )
        }
        labelColor.hashCode() // 保留参数引用，避免编译告警
    }
}
