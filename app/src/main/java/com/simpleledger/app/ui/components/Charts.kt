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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.logic.CategoryShare
import com.simpleledger.app.ui.theme.chartPalette
import com.simpleledger.app.util.Money

/** 读屏机密模式下的统一占位串：绝不把真实金额交给 TTS */
private const val HIDDEN_SPEECH = "金额已隐藏"

/**
 * 环形饼图的读屏摘要（纯函数，便于单测）。
 *
 * Canvas 绘制对读屏是一片空白，故用一段文字替代整块图：先说结论（共 N 类），
 * 再按占比从高到低列前 [maxItems] 项，其余归入「等 N 类」，避免长列表把读屏淹掉。
 */
internal fun pieChartSpeech(
    shares: List<CategoryShare>,
    totalCents: Long,
    hidden: Boolean,
    maxItems: Int = 5,
): String {
    if (shares.isEmpty()) return "分类占比：暂无数据"
    if (hidden) return "分类占比：共 ${shares.size} 类，$HIDDEN_SPEECH"

    val shown = shares.take(maxItems)
    val items = shown.joinToString("；") { share ->
        val percent = (share.fraction * 100).toInt()
        "${share.total.name} $percent%，${Money.toChineseSpeech(share.total.total)}"
    }
    val rest = shares.size - shown.size
    val tail = if (rest > 0) "；等 $rest 类" else ""
    return "分类占比：共 ${shares.size} 类，总支出 ${Money.toChineseSpeech(totalCents)}。$items$tail"
}

/**
 * 每日支出柱状图的读屏摘要（纯函数）：给出总天数、峰值日与合计。
 * 逐日朗读 30 多条是噪音，峰值 + 合计才是用户真正需要的两个数。
 */
internal fun barChartSpeech(
    daily: List<Pair<Int, Long>>,
    daysInMonth: Int,
    hidden: Boolean,
): String {
    if (daily.isEmpty()) return "每日支出：本月暂无支出"
    val totalCents = daily.sumOf { it.second }
    if (hidden) return "每日支出：本月 $daysInMonth 天，$HIDDEN_SPEECH"

    val peak = daily.maxByOrNull { it.second } ?: daily.first()
    return "每日支出：本月 $daysInMonth 天，最高 ${peak.first} 日 " +
        Money.toChineseSpeech(peak.second) +
        "，合计 ${Money.toChineseSpeech(totalCents)}"
}

/**
 * 环形饼图：分类支出占比。
 * 中心显示当月支出总额。
 */
@Composable
fun CategoryPieChart(
    shares: List<CategoryShare>,
    totalCents: Long,
    modifier: Modifier = Modifier,
    /** 隐私模式：真实金额不得进入读屏 */
    hidden: Boolean = false,
) {
    val chartColors = chartPalette()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val holeColor = MaterialTheme.colorScheme.surface
    // 整块图用一个语义节点替代：clearAndSetSemantics 会清掉 Canvas/中心文字等子节点的默认语义，
    // 避免读屏把「总支出」「¥…」与摘要重复念两遍
    val speech = pieChartSpeech(shares, totalCents, hidden)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clearAndSetSemantics { contentDescription = speech },
        contentAlignment = Alignment.Center,
    ) {
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
                // 隐私模式：环心是屏幕正中最醒目的一处，真实金额绝不能落在视觉上；与图例/概览卡写法一致
                text = if (hidden) "••••" else Money.formatWithSymbol(totalCents),
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
    /** 隐私模式：真实金额不得进入读屏 */
    hidden: Boolean = false,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.surfaceVariant
    val speech = barChartSpeech(daily, daysInMonth, hidden)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2.2f)
            .padding(vertical = 4.dp)
            // 柱状图内容全在 Canvas 里，本体无子语义；这里给整块图一个摘要即可
            .clearAndSetSemantics { contentDescription = speech },
    ) {
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
    }
}
