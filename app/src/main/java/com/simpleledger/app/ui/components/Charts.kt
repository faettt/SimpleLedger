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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.logic.CategoryShare
import com.simpleledger.app.logic.StatsCalculator
import com.simpleledger.app.ui.theme.shareColor
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
        val percent = StatsCalculator.percentLabel(share.fraction)
        "${share.total.name} $percent，${Money.toChineseSpeech(share.total.total)}"
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
 *
 * 两条实现约定（都来自「读图兜底」）：
 * ① 扇区配色走 [shareColor]——与图例**共用同一个函数**，颜色不会两处各飞；
 *    合并桶（「其他 N 类」）固定中性墨灰。
 * ② 百分比**直接画在扇区上**（规范 §2.5 兜底②）。只在「放得下」的扇区上画：
 *    以该扇区在环带中线处的弧长是否容得下这段文字为准，而不是拍一个固定角度阈值
 *    ——阈值写死会在窄图上溢出、在宽图上白留空间。
 */
@Composable
fun CategoryPieChart(
    shares: List<CategoryShare>,
    totalCents: Long,
    modifier: Modifier = Modifier,
    /** 隐私模式：真实金额不得进入读屏 */
    hidden: Boolean = false,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val holeColor = MaterialTheme.colorScheme.surface
    val labelColor = MaterialTheme.colorScheme.surface
    // sp → px 必须经 LocalDensity 换算：Canvas 原生画笔只认像素，
    // 直接写 textSize = 24f 在 3x 屏上只有 8dp，小到读不出来。
    val labelPx = with(LocalDensity.current) { 11.sp.toPx() }
    // 配色必须在 Canvas 之外算好：shareColor 是 @Composable，而 Canvas 的
    // DrawScope 只是绘制回调、不是组合上下文，在那里调用会编译失败。
    val sliceColors = shares.mapIndexed { index, share -> shareColor(index, share.isMerged) }
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
            val cx = size.width / 2f
            val cy = size.height / 2f
            val paint = android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = labelPx
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }

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
                        color = sliceColors[index],
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )

                    // 兜底②：占比数值直接标在图上
                    if (!hidden) {
                        val text = StatsCalculator.percentLabel(share.fraction)
                        val textWidth = paint.measureText(text)
                        // 环带中线半径 = diameter/2（Stroke 以路径为中心向两侧各扩 stroke/2）
                        val arcLength = Math.toRadians(sweep.toDouble()).toFloat() * (diameter / 2f)
                        if (textWidth + 10f <= arcLength) {
                            val midRad = Math.toRadians((startAngle + sweep / 2f).toDouble())
                            val px = cx + (diameter / 2f) * kotlin.math.cos(midRad).toFloat()
                            val py = cy + (diameter / 2f) * kotlin.math.sin(midRad).toFloat()
                            // baseline 垂直居中：加回约半行高（0.36em 是常见近似值）
                            drawContext.canvas.nativeCanvas.drawText(
                                text, px, py + labelPx * 0.36f, paint,
                            )
                        }
                    }
                    startAngle += sweep
                }
            }
            // 中心挖空成环
            drawCircle(
                color = holeColor,
                radius = (diameter - stroke * 2) / 2f,
                center = Offset(cx, cy),
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

/** 手绘马克笔涂条的确定性微扰（0.0 ~ 1.0）。 */
private fun markerJitter(day: Int, salt: Int): Float {
    // 必须是**确定性**的：随机数会让每根柱在每次重组时都换一个宽度，
    // 看起来像画面在抖。用「日期 × 大素数」做散列，同一天永远得到同一个值。
    val h = day * 73_856_093 xor (salt * 19_349_663)
    return ((h and 0x7FFF_FFFF) % 1000) / 1000f
}

/**
 * 每日支出柱状图 —— 手绘「马克笔涂条」（规范 §2.5）。
 *
 * 手绘感由三件事承担，**都不许动数据本身**：
 *  ① 柱宽轻微不等（0.62~0.78 槽宽），每根柱的宽度由日期派生 → 确定性、不抖；
 *  ② 柱顶左右圆角**不等**（真人下笔两侧力度不同）；
 *  ③ 柱底严格贴齐基线（数据准确性的底线，绝不做手绘抖动）。
 *
 * 另加两处「读图兜底」的等价物 —— 环图那三条是写给角度图的，
 * 但「不依赖肉眼估算」的精神对柱高同样成立：本图上给出**峰值水平参考线**与
 * **峰值金额标注**，让人一眼读出「最高那天到了多少」，而不是只看出谁高谁矮。
 *
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
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisLabelPx = with(LocalDensity.current) { 10.sp.toPx() }
    val peakLabelPx = with(LocalDensity.current) { 10.5.sp.toPx() }
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

        val labelBand = axisLabelPx * 1.7f          // 底部日期刻度带
        val baseline = size.height - labelBand
        val topPad = peakLabelPx * 1.9f             // 顶部峰值金额标注带
        val plotHeight = (baseline - topPad).coerceAtLeast(1f)

        val maxCents = daily.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
        val slot = size.width / daysInMonth
        val peakDay = daily.maxByOrNull { it.second }?.first

        // 峰值水平参考线：手绘里那条用铅笔轻轻拉出来的基准线。
        // 没有它，30 根柱只有相对高低，读者无法回答「最高那天是多少」。
        if (daily.isNotEmpty()) {
            drawLine(
                color = axisColor,
                start = Offset(0f, topPad),
                end = Offset(size.width, topPad),
                strokeWidth = 2f,
            )
        }

        // 底部轴线
        drawLine(
            color = axisColor,
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = 2f,
        )

        val barPaint = android.graphics.Paint().apply {
            color = axisLabelColor.toArgb()
            textSize = peakLabelPx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }

        daily.forEach { (day, cents) ->
            val barHeight = (cents.toFloat() / maxCents) * plotHeight
            if (barHeight <= 1f) return@forEach

            // ①② 手绘：宽度微不等 + 顶部左右圆角不等
            val ratio = 0.62f + markerJitter(day, 1) * 0.16f
            val barWidth = slot * ratio
            val left = slot * (day - 1) + (slot - barWidth) / 2f
            val top = baseline - barHeight
            // 圆角不能超过柱高的一半，否则细柱会被削成枣核
            val rMax = minOf(barWidth, barHeight) * 0.42f
            val rLeft = rMax * (0.62f + markerJitter(day, 2) * 0.38f)
            val rRight = rMax * (0.62f + markerJitter(day, 3) * 0.38f)
            drawPath(
                path = markerBarPath(left, top, barWidth, barHeight, rLeft, rRight),
                color = barColor,
            )

            // 峰值金额标注：整个图里唯一一个具体数字，放在最高柱顶上
            if (!hidden && day == peakDay) {
                drawContext.canvas.nativeCanvas.drawText(
                    Money.formatCents(cents),
                    left + barWidth / 2f,
                    top - peakLabelPx * 0.35f,
                    barPaint,
                )
            }
        }

        // 日期刻度：1 / 中旬 / 月末
        // ⚠️ textSize 只认像素。原先写死 24f，在 3x 屏上只有 8dp —— 缩到根本读不出。
        // 必须经 LocalDensity 换算，才能在任意密度下都是设计稿里的 10sp。
        val paint = android.graphics.Paint().apply {
            color = axisLabelColor.toArgb()
            textSize = axisLabelPx
            isAntiAlias = true
        }
        listOf(1, daysInMonth / 2, daysInMonth).forEach { day ->
            val x = slot * (day - 1) + slot / 2f
            drawContext.canvas.nativeCanvas.drawText(
                "$day",
                x - paint.measureText("$day") / 2f,
                baseline + axisLabelPx,
                paint,
            )
        }
    }
}

/**
 * 马克笔涂条的轮廓：底边直角（严格贴基线），顶边左右各有不同圆角。
 * 左上/右上半径分开传入 —— 这是「手画」与「矩形」在轮廓上最省力的差别。
 */
private fun markerBarPath(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    rLeft: Float,
    rRight: Float,
): Path = Path().apply {
    val right = left + width
    val bottom = top + height
    moveTo(left, bottom)
    lineTo(left, top + rLeft)
    quadraticTo(left, top, left + rLeft, top)
    lineTo(right - rRight, top)
    quadraticTo(right, top, right, top + rRight)
    lineTo(right, bottom)
    close()
}
