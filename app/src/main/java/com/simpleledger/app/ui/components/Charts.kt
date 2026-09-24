package com.simpleledger.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.R
import com.simpleledger.app.logic.SectionShare
import com.simpleledger.app.logic.StatsCalculator
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.chartGrow
import com.simpleledger.app.ui.theme.rememberSlChartTimeline
import com.simpleledger.app.ui.theme.sweepClipPath
import com.simpleledger.app.util.Money
import kotlin.math.atan2
import kotlin.math.hypot

/** 读屏机密模式下的统一占位串：绝不把真实金额交给 TTS */
private const val HIDDEN_SPEECH = "金额已隐藏"

/**
 * 分区占比环图的读屏摘要（纯函数，便于单测）。
 *
 * Canvas 绘制对读屏是一片空白，故用一段文字替代整块图：先说结论（共 N 个分区），
 * 再按占比从高到低逐个列出（分区数量受用户控制、不会太多，可全列）。
 */
internal fun sectionDonutSpeech(
    shares: List<SectionShare>,
    totalCents: Long,
    hidden: Boolean,
): String {
    if (shares.isEmpty()) return "分区占比：本月暂无支出"
    if (hidden) return "分区占比：共 ${shares.size} 个分区，$HIDDEN_SPEECH"

    val items = shares.joinToString("；") { share ->
        "${share.section.name} ${StatsCalculator.percentLabel(share.fraction)}，" +
            Money.toChineseSpeech(share.section.expense)
    }
    return "分区占比：共 ${shares.size} 个分区，总支出 ${Money.toChineseSpeech(totalCents)}。$items"
}

/** 分类金额条形图的读屏摘要（纯函数）：合计 + 前 5 类 + 其余归为「等 N 类」。 */
internal fun categoryBarSpeech(
    labels: List<String>,
    amounts: List<Long>,
    hidden: Boolean,
    maxItems: Int = 5,
): String {
    if (amounts.isEmpty()) return "分类金额：本月暂无支出"
    val total = amounts.sumOf { it }
    if (hidden) return "分类金额：共 ${amounts.size} 类，$HIDDEN_SPEECH"
    val shown = amounts.indices.take(maxItems).joinToString("；") { i ->
        "${labels[i]} ${Money.toChineseSpeech(amounts[i])}"
    }
    val rest = amounts.size - maxItems
    val tail = if (rest > 0) "；等 $rest 类" else ""
    return "分类金额：共 ${amounts.size} 类，合计 ${Money.toChineseSpeech(total)}。$shown$tail"
}

/** 每日支出柱状图的读屏摘要（纯函数）：给出总天数、峰值日与合计。
 * 逐日朗读 30 多条是噪音，峰值 + 合计才是用户真正需要的两个数。 */
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
 * 环图的一个扇区。颜色由调用方给 —— 环图只管画，不该知道「颜色代表什么」。
 *
 * 这个决定来自 §2.5 v2.1 的裁定：环图的扇区是**分区**、颜色是分区胶带色；
 * 旧版「分类占比环图」把颜色借给分类，让读者把青绿扇区误读成某个分区，已被拆掉。
 */
data class DonutSlice(
    /** 图例 / 读屏串用 */
    val label: String,
    /** 0.0 ~ 1.0 */
    val fraction: Double,
    val color: androidx.compose.ui.graphics.Color,
)

/**
 * 环缘波形三档（2026-09-23「圆弧不自然」排查用）。
 * **定稿裁决（2026-09-23）：正式档 = [SMOOTH]**，用户看真机三案对比后拍板。
 *
 *  · [STEPPED] 历史对照（bug 现场，勿用于生产路径）：每 6° 一个折线顶点、半径按
 *    「6° 分桶方波」抖 ±2.5px。实测顶点折角 mean 6.35° / max 14.64°、每 25.7px 一次
 *    —— 肉眼读作「多边形/手撕」而不是手画的弧（这就是「不自然」的根因）。
 *  · [SMOOTH] ✅ 正式档：抖动为**低频连续波**（整圈 6 + 17 个周期，θ=0/360 相位闭合），
 *    波长 60°/21° 模拟手腕慢起伏；内外缘**同相**抖动 → 环带等宽、径向边不剪斜；
 *    采样步长 3°。折角 → 0，手绘味保留。量化判别「波形连贯度」94%（STEPPED 仅 11%）。
 *  · [CIRCLE] 备选：抖动归零纯圆（多边形逼近的 0.37° 折角不可见）。最规整，
 *    但丢手账的手绘隐喻。
 */
enum class DonutEdge { STEPPED, SMOOTH, CIRCLE }

/**
 * 环形图：**通用的环图绘制器**，不知道也不关心扇区代表什么。
 *
 * 两条实现约定（都来自「读图兜底」）：
 * ① 百分比**直接画在扇区上**（规范 §2.5 兜底②）。只在「放得下」的扇区上画：
 *    以该扇区在环带中线处的弧长是否容得下这段文字为准，而不是拍一个固定角度阈值
 *    ——阈值写死会在窄图上溢出、在宽图上白留空间。
 * ② 选中扇区**加粗环带**（stroke + 12f）——规范 §2.1 的「选中态不止变色，还有形变」
 *    同样适用于图表：形变比变色更有信息量。
 *
 * @param onSliceClick 点到某个扇区时回调其下标（双图联动的入口）；null = 不可点。
 *   命中判定用环带内外半径 + 极角，与 Canvas 的几何完全共用 [DonutGeometry]。
 */
/**
 * 画布文字的楷体（字族一元制，决策二）。
 *
 * Canvas 原生画笔（android.graphics.Paint）不走 Compose 字体系统，
 * 必须手动从 res/font 加载 LXGW WenKai；缺字形由系统按 Fonts.kt 的
 * 回退链语义兜底（原生画笔只有单 Typeface，缺字自动走平台 fallback）。
 */
@Composable
private fun rememberKaiTypeface(): android.graphics.Typeface? {
    val context = LocalContext.current
    return remember(context) { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.lxgw_wenkai) }
}

@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    centerLabel: String,
    centerValue: String,
    speech: String,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    onSliceClick: ((Int) -> Unit)? = null,
    edge: DonutEdge = DonutEdge.SMOOTH,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.surface
    // 小扇区引出线与环外标签（P2-1）：线用弱墨、字用主墨
    val leadColor = MaterialTheme.colorScheme.onSurfaceVariant
    val leadTextColor = MaterialTheme.colorScheme.onSurface
    // sp → px 必须经 LocalDensity 换算：Canvas 原生画笔只认像素，
    // 直接写 textSize = 24f 在 420dpi 屏上只有 9dp，小到读不出来。
    val labelPx = with(LocalDensity.current) { 11.sp.toPx() }
    val kaiTypeface = rememberKaiTypeface()
    // 入场「画出来」时间线（Motion④）：环带与标注按扇形扫出，起止角保持精确
    val timeline = rememberSlChartTimeline()
    // 配色已在调用方算好（[DonutSlice.color]）。这里只取与主题相关、与数据无关的颜色。
    //
    // ⚠️ 手势处理要用「最新值」而不是闭包捕获：pointerInput 的 key 若传每次重组
    // 都新建的 List（slices），手势协程会随重组不断取消重启 —— 真机上实测
    // 点扇区毫无反应。标准写法是 key 传 Unit，数据经 rememberUpdatedState 注入。
    val currentSlices = androidx.compose.runtime.rememberUpdatedState(slices)
    val currentOnSliceClick = androidx.compose.runtime.rememberUpdatedState(onSliceClick)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clearAndSetSemantics { contentDescription = speech },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // key 传 Unit：手势协程只启动一次，数据经上面的 rememberUpdatedState 注入
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val g = DonutGeometry(size.width.toFloat(), size.height.toFloat())
                        val dx = offset.x - g.cx
                        val dy = offset.y - g.cy
                        val dist = hypot(dx.toDouble(), dy.toDouble())
                        // 只认环带本身：点到环心（那是总额数字）或环外都不算
                        if (dist < g.innerR || dist > g.outerR) return@detectTapGestures
                        // Canvas 的 drawArc 以 3 点方向为 0°、顺时针增；
                        // 环的起点在 12 点方向，故先把极角转成「从环起点顺时针」的相对角
                        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                        val rel = ((deg - 270.0) + 360.0) % 360.0
                        var acc = 0.0
                        var hit = currentSlices.value.lastIndex
                        for ((i, s) in currentSlices.value.withIndex()) {
                            acc += s.fraction * 360.0
                            if (rel <= acc) {
                                hit = i
                                break
                            }
                        }
                        currentOnSliceClick.value?.invoke(hit)
                    }
                },
        ) {
            val g = DonutGeometry(size.width, size.height)
            val paint = android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = labelPx
                isAntiAlias = true
                // 楷体 Regular：11sp 在禁粗档（决策一），不再用 DEFAULT_BOLD 合成粗
                typeface = kaiTypeface
                textAlign = android.graphics.Paint.Align.CENTER
            }
            // 环外标签画笔：与 [paint] 同字号同字重（P2-1「统一」：数字样式只有一套）
            val leadPaint = android.graphics.Paint().apply {
                color = leadTextColor.toArgb()
                textSize = labelPx
                isAntiAlias = true
                typeface = kaiTypeface
            }

            if (slices.isEmpty()) {
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = g.topLeft,
                    size = g.arcSize,
                    style = Stroke(width = g.stroke, cap = StrokeCap.Butt),
                )
            } else {
                // 入场「画出来」（Motion④）：扇形裁剪窗从 12 点顺时针扫过 ——
                // 环带与百分比标注随窗显形，数据角度完全不变（只是被遮住的还没画出来）。
                // save/restore 成对：漏 restore 会把后面的中心文字一起裁掉。
                val revealCanvas = drawContext.canvas.nativeCanvas
                revealCanvas.save()
                revealCanvas.clipPath(
                    sweepClipPath(g.cx, g.cy, size.width, 360f * chartGrow(timeline)),
                )
                // ---- 手绘环带（规范 §2.5「手绘甜甜圈：外圈抖动描边」）----
                // 沿外缘/内缘各采样一排点，半径按角度散列微抖（确定性，同手绘隐喻与
                // markerJitter 同源）——完美圆弧是「屏幕控件语言」，抖动才是手绘痕迹。
                //
                // 三条底线：
                //  · 扇区的**起止角保持精确**（采样的起止点不抖）——数据的准确性
                //    不许被手绘感吃掉；
                //  · 采样点按角度分桶（每 6°），相邻扇区在公共边界上取到同一个抖动值
                //    → 接缝处不会裂开或重叠；
                //  · 抖动幅度 ±2.5px ≈ ±1dp，肉眼是"手画的"，不影响读数。
                //
                // 每个扇区画成一个「环形扇面」路径（外缘顺时针 + 内缘逆时针 + close），
                // 环心是自然留出的空腔 —— 不再需要单独画一个挖空圆。
                // 波形三档见 [DonutEdge]；SMOOTH 用 3° 采样加密顶点
                val stepDeg = if (edge == DonutEdge.SMOOTH) 3f else 6f
                fun ringPoint(ringDeg: Float, radius: Float) = Offset(
                    g.cx + radius * kotlin.math.sin(Math.toRadians(ringDeg.toDouble())).toFloat(),
                    g.cy - radius * kotlin.math.cos(Math.toRadians(ringDeg.toDouble())).toFloat(),
                )
                // 同一角度在同一 salt 下永远得到同一个抖动值（确定性）
                fun wob(ringDeg: Float, salt: Int): Float = when (edge) {
                    DonutEdge.CIRCLE -> 0f
                    DonutEdge.STEPPED ->
                        (markerJitter(kotlin.math.round(ringDeg / stepDeg).toInt(), salt) - 0.5f) * 2f * 2.5f
                    // 低频连续波：2.0px@60° 主波（手腕慢起伏）+ 0.8px@21° 次波（笔触小纹）。
                    // 周期数取整数（6 与 17）保证 θ=0°/360° 相位闭合，接缝无折痕；
                    // salt 忽略 → 内外缘同相 → 环带等宽 56px 恒定。
                    DonutEdge.SMOOTH -> {
                        val rad = Math.toRadians(ringDeg.toDouble())
                        2.0f * kotlin.math.sin(rad * 6.0).toFloat() +
                            0.8f * kotlin.math.sin(rad * 17.0 + 1.7).toFloat()
                    }
                }
                fun edge(ringDeg: Float, radius: Float, salt: Int) =
                    ringPoint(ringDeg, radius + wob(ringDeg, salt))

                var ringStart = 0f
                slices.forEachIndexed { index, slice ->
                    val sweep = (slice.fraction * 360f).toFloat()
                    val ringEnd = ringStart + sweep
                    // 选中扇区环带加粗（形变，见组件说明②）
                    val band = if (index == selectedIndex) g.stroke + 12f else g.stroke
                    val rOut = g.diameter / 2f + band / 2f
                    val rIn = g.diameter / 2f - band / 2f

                    val path = Path().apply {
                        // 外缘：起点 → 终点（顺时针）
                        var a = ringStart
                        moveTo(edge(a, rOut, 11).x, edge(a, rOut, 11).y)
                        while (a < ringEnd - 0.01f) {
                            a = minOf(a + stepDeg, ringEnd)
                            lineTo(edge(a, rOut, 11).x, edge(a, rOut, 11).y)
                        }
                        // 内缘：终点 → 起点（逆时针）
                        var b = ringEnd
                        lineTo(edge(b, rIn, 23).x, edge(b, rIn, 23).y)
                        while (b > ringStart + 0.01f) {
                            b = maxOf(b - stepDeg, ringStart)
                            lineTo(edge(b, rIn, 23).x, edge(b, rIn, 23).y)
                        }
                        close()
                    }
                    drawPath(path, color = slice.color)

                    // 兜底②：占比数值直接标在图上 —— 放得下 → 环带中线内标（反白）；
                    // 放不下（小扇区）→ 引出线 + 环外标（P2-1）。两种落点同一套字体字号，
                    // 数字样式完全一致，只是位置随空间走，不再出现「有的扇区有数、有的没有」。
                    val text = StatsCalculator.percentLabel(slice.fraction)
                    val textWidth = paint.measureText(text)
                    // 环带中线半径 = diameter/2（Stroke 以路径为中心向两侧各扩 stroke/2）
                    val arcLength = Math.toRadians(sweep.toDouble()).toFloat() * (g.diameter / 2f)
                    val midRing = ringStart + sweep / 2f
                    if (textWidth + 10f <= arcLength) {
                        val px = g.cx + (g.diameter / 2f) * kotlin.math.sin(Math.toRadians(midRing.toDouble())).toFloat()
                        val py = g.cy - (g.diameter / 2f) * kotlin.math.cos(Math.toRadians(midRing.toDouble())).toFloat()
                        // baseline 垂直居中：加回约半行高（0.36em 是常见近似值）
                        drawContext.canvas.nativeCanvas.drawText(
                            text, px, py + labelPx * 0.36f, paint,
                        )
                    } else {
                        // 手绘标注式引出线：径向短线从环外沿指向标签
                        val rad = Math.toRadians(midRing.toDouble())
                        val sin = kotlin.math.sin(rad).toFloat()
                        val cos = kotlin.math.cos(rad).toFloat()
                        val p1 = Offset(g.cx + (g.outerR + 3f) * sin, g.cy - (g.outerR + 3f) * cos)
                        val p2 = Offset(g.cx + (g.outerR + 13f) * sin, g.cy - (g.outerR + 13f) * cos)
                        drawLine(leadColor, p1, p2, strokeWidth = 1.5f)
                        // 右半环向右排、左半向左排；顶部扇区文字落线**下方**，
                        // 避开 Canvas 上沿（否则 11sp 的字会被裁掉半个头）
                        val isRight = sin >= 0f
                        leadPaint.textAlign =
                            if (isRight) android.graphics.Paint.Align.LEFT else android.graphics.Paint.Align.RIGHT
                        val tx = p2.x + if (isRight) 4f else -4f
                        val baseline =
                            if (cos > 0.5f) p2.y + labelPx * 1.15f
                            else p2.y + labelPx * 0.36f
                        drawContext.canvas.nativeCanvas.drawText(text, tx, baseline, leadPaint)
                    }
                    ringStart = ringEnd
                }
                revealCanvas.restore()
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerLabel, style = SlType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 环心汇总数字 = 「结余数字」位 → titleL（自带 tnum，硬规则 R1）
            Text(text = centerValue, style = SlType.titleL)
        }
    }
}

/** 环图的几何：**绘制与点击命中判定必须共用这一份**，否则点到的扇区和看到的不一致。 */
private class DonutGeometry(width: Float, height: Float) {
    val stroke = 56f
    val diameter = minOf(width, height) - stroke * 2
    val cx = width / 2f
    val cy = height / 2f
    /** 环带内沿 / 外沿半径（Stroke 以路径为中心向两侧各扩 stroke/2） */
    val innerR = (diameter - stroke) / 2f
    val outerR = (diameter + stroke) / 2f
    val topLeft = Offset((width - diameter) / 2f, (height - diameter) / 2f)
    val arcSize = Size(diameter, diameter)
}

/**
 * 分区占比环图（规范 §2.5 v2.1）。
 *
 * 扇区 = 分区、颜色 = 该分区的胶带色 —— **颜色语义在这里回归分区身份**。
 * 旧版「分类占比环图」让颜色兼任分类标识，8 色板循环到第 9 类必然撞色，
 * 且读者会把青绿扇区误读成某个分区；拆成「分区环图 + 分类条形图」后两个问题都消失。
 *
 * 不做「小扇区并入其他」（规范兜底①的原机制）：分区是用户亲手建的一级对象，
 * 把它藏进一个无名的桶里，比藏掉一个分类伤害更大；且分区数天然 ≤ 色板容量，
 * 不存在撞色问题。小扇区的可读性由兜底②（环上百分比）与完整图例承担。
 *
 * @param selectedIndex 当前被点选的分区下标（双图联动），null = 未选中
 */
@Composable
fun SectionDonutChart(
    shares: List<SectionShare>,
    totalCents: Long,
    hidden: Boolean,
    selectedIndex: Int? = null,
    onSliceClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    edge: DonutEdge = DonutEdge.SMOOTH,
) {
    // 颜色必须在调用方（这里是本包装）算好：tapeColor 是 @Composable，
    // 而 Canvas / pointerInput 的 lambda 都不是组合上下文，在那里读会编译失败。
    val slices = shares.map { share ->
        com.simpleledger.app.ui.theme.tapeColor(share.section.colorIndex).let { color ->
            DonutSlice(
                label = share.section.name,
                fraction = share.fraction,
                color = color,
            )
        }
    }
    DonutChart(
        slices = slices,
        centerLabel = "总支出",
        centerValue = if (hidden) "••••" else Money.formatWithSymbol(totalCents),
        speech = sectionDonutSpeech(shares, totalCents, hidden),
        selectedIndex = selectedIndex,
        onSliceClick = onSliceClick,
        modifier = modifier,
        edge = edge,
    )
}

/** 分类金额条形图的一根条。颜色由调用方给（＝该分类所属分区的胶带色）。 */
data class BarRow(
    val label: String,
    val amountCents: Long,
    val color: androidx.compose.ui.graphics.Color,
)

/**
 * 分类金额横向条形图（规范 §2.5 v2.1）。
 *
 * **颜色不参与区分类别** —— 条形图是「长度编码」，颜色在它身上是冗余通道；
 * 每根条的颜色表示它**属于哪个分区**（与环图同一条颜色通道），类别靠标签区分。
 *
 * 「读图兜底②」（数值直接标在图上）在这里是**必须项而不是装饰**：
 * 线性刻度下 21 元与 18,000 元的条长差近三个数量级，尾部的条必然接近 0，
 * 数值标注是让尾条仍可读的唯一办法。
 *
 * @param rows 已按金额降序排列（调用方负责排序），条形图不重排
 */
@Composable
fun CategoryBarChart(
    rows: List<BarRow>,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
) {
    if (rows.isEmpty()) return
    val barColorTrack = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurface
    val amountColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelPx = with(LocalDensity.current) { 12.sp.toPx() }
    val amountPx = with(LocalDensity.current) { 11.sp.toPx() }
    val kaiTypeface = rememberKaiTypeface()
    // 入场生长（Motion④）：条从左端起跑线长出，逐条错峰
    val timeline = rememberSlChartTimeline()

    val rowPitch = 30.dp
    // 全部展开（用户裁定）：条数 = 分类数，高度随条数走，不做限高/折叠
    val height = rowPitch * rows.size + 6.dp

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics {
                contentDescription = categoryBarSpeech(
                    rows.map { it.label }, rows.map { it.amountCents }, hidden,
                )
            },
    ) {
        val labelPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = labelPx
            isAntiAlias = true
            typeface = kaiTypeface
        }
        val amountPaint = android.graphics.Paint().apply {
            color = amountColor.toArgb()
            textSize = amountPx
            isAntiAlias = true
            typeface = kaiTypeface
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        // 标签列宽随最长的标签走（中文 1 字 ≈ 1em），金额列宽随最长的金额走。
        // 写死会要么截断「装修·设计费」，要么在只有两三个分类时留出大片空白。
        val maxLabelW = rows.maxOf { labelPaint.measureText(it.label) }
        val maxAmountW = rows.maxOf { amountPaint.measureText(Money.formatCents(it.amountCents)) }
        val labelW = maxLabelW + 10f
        val amountW = maxAmountW + 10f
        val gap = 10f
        val barW = (size.width - labelW - amountW - gap * 2).coerceAtLeast(20f)
        val barX = labelW + gap
        val maxCents = rows.maxOf { it.amountCents }.coerceAtLeast(1L)

        rows.forEachIndexed { index, row ->
            val centerY = index * rowPitch.toPx() + rowPitch.toPx() / 2f
            val labelY = centerY + labelPx * 0.36f
            drawContext.canvas.nativeCanvas.drawText(
                row.label, 0f, labelY, labelPaint,
            )

            val barH = 16.dp.toPx()
            // 生长系数：条左端（起跑线）不动，只长右端 —— 与「长度编码可比」一致
            val len = (row.amountCents.toFloat() / maxCents) * barW * chartGrow(timeline, index)
            // 马克笔涂条：左端整齐（都在同一条起跑线上才可比），右端微不规则 + 圆角
            val r = minOf(barH / 2f, 4f)
            val path = Path().apply {
                moveTo(barX, centerY - barH / 2f)
                lineTo(barX + len - r, centerY - barH / 2f)
                quadraticTo(barX + len, centerY - barH / 2f, barX + len, centerY - barH / 2f + r)
                lineTo(barX + len, centerY + barH / 2f - r)
                quadraticTo(barX + len, centerY + barH / 2f, barX + len - r, centerY + barH / 2f)
                // ⚠️ 必须补上左边缘这条竖线，再 close。
                // 漏了它，close() 会从「右下角」直接斜连回「左上角」——
                // 每根条都被画成左端尖、右端宽的楔形（真机截图已复现，满宽条最明显）。
                lineTo(barX, centerY + barH / 2f)
                close()
            }
            // 凹槽底：让「几乎为 0」的条也能看出「有这一类」，而不是空出一截
            drawRect(
                color = barColorTrack,
                topLeft = Offset(barX, centerY - barH / 2f),
                size = Size(barW, barH),
            )
            // 生长未起步（len≈0）时不画条：退化路径的圆角计算会画出怪形
            if (len > 1f) drawPath(path = path, color = row.color)

            // 兜底②：金额标在条右侧 —— 尾部条长接近 0 时，数值是唯一的可读通道
            if (!hidden) {
                drawContext.canvas.nativeCanvas.drawText(
                    Money.formatCents(row.amountCents),
                    size.width - amountW * 0.1f,
                    centerY + amountPx * 0.36f,
                    amountPaint,
                )
            }
            // 隐私模式下金额不能上屏，但「这一类有多长」仍可用条长读出（长度不含敏感数字）
        }

        // 与下方区块的分隔（分类金额图没有自己的轴线，用一条细线收尾）
        drawLine(
            color = axisColor,
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1f,
        )
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
    val kaiTypeface = rememberKaiTypeface()
    // 入场生长（Motion④）：柱从基线长起、按日错峰 —— 柱底严格贴基线不参与生长
    val timeline = rememberSlChartTimeline()
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
            typeface = kaiTypeface
            textAlign = android.graphics.Paint.Align.CENTER
        }

        daily.forEachIndexed { index, (day, cents) ->
            // 生长系数只乘柱高（top 上移），柱底恒贴 baseline
            val barHeight = (cents.toFloat() / maxCents) * plotHeight * chartGrow(timeline, index)
            if (barHeight <= 1f) return@forEachIndexed

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
            typeface = kaiTypeface
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
