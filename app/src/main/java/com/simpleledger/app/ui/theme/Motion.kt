package com.simpleledger.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.simpleledger.app.ui.LocalReduceMotion

/* ============================================================
   动效语言 —— 「纸的物理」（唯一事实源：docs/design/motion-spec）
   ============================================================

   整套动效只说一件事：**纸很轻、摩擦大、几乎不弹**。

   ① 反馈先于表演
      按压 70ms 内响应、小状态 150ms 内完成。任何"好看的动画"都不许
      拖慢操作节奏——动效是反馈，不是节目。

   ② 位移小、时间短
      位移只有三档（8 / 24 / 32dp），时长只有四档（70/120/150/250/320ms）。
      纸片是被"轻放在桌上"的，不是被抛进来的。滚动路径**零入场动画**。

   ③ 曲线讲物理
      · 进场 / 展开（PaperOut）：快起慢收 —— 手把纸放下，到位即停；
      · 出场 / 折叠（PaperIn）：慢起快收 —— 纸被抽走，末端加速消失；
      · 切换 / 形变（Standard）：两端软、中段快 —— 索引贴凸出的形变。
      弹簧只允许**接近临界阻尼**的轻回弹（damping 0.85），不做果冻。

   ④ 图表长出来，不飞进来
      柱从基线长起、环从 12 点顺时针画出、条从左端生长——数据图形用
      「生长」入场，与「柱底严格贴基线」的准确性底线一致；文字标注随
      生长显形，不单独飞入。

   ⑤ 减少动效（无障碍）＝ 两层降级
      · **时长层（自动）**：自研动画全部基于 Compose 动画原语
        （tween/spring/Animatable），系统动画时长缩放
        （ANIMATOR_DURATION_SCALE，0 = 移除动画、0.5 = 慢速动画）
        经 MotionDurationScale 自动作用于每一个规格——不需要也不允许
        在调用点换算时长。
      · **形变层（显式）**：减少动效下位移/缩放类的**装饰性**反馈改为
        非运动形态——按压缩放归零（保留涟漪/色反馈）、悬停拈起整体关闭
        （hover 本就是装饰，不是必需反馈）。这一层走 [LocalReduceMotion]。
      · 图表错峰走**单条线性时间线**（[rememberSlChartTimeline]），不含
        协程 delay，故无需单独降级；系统关动画时时间线瞬时到 1，
        所有几何立即成形。

   ⚠️ Material3 内置动画（ModalBottomSheet / AlertDialog / DropdownMenu /
      DatePicker / 涟漪 / NavigationRail 指示器）走 Compose 内部
      MotionDurationScale，同样自动尊重系统设置，**不需要也不应该**重包。
   ============================================================ */

/** 动效 token 唯一事实源。调用点只引用这里的档位，禁止散写时长/曲线数值。 */
object SlMotion {
    // ——— 时长档（ms）———
    /** 按压按下：手指碰到纸，70ms 内压到底 */
    const val PressDownMs = 70

    /** 按压抬起回弹：120ms 弹回（唯一允许轻回弹的弹簧，见 [pressSpring]） */
    const val PressUpMs = 120

    /** 快档：小状态切换（chip 选中、图标换形、颜色过渡、进度色变化） */
    const val FastMs = 150

    /** 标准档：卡片/浮层进出、索引贴形变、内容入场 */
    const val StandardMs = 250

    /** 慢档：整页翻动（32dp 大位移）、全屏表单铺入 */
    const val SlowMs = 320

    // ——— 图表生长 ———
    /** 单根柱 / 单根条 / 环扫的生长时间 */
    const val ChartGrowMs = 420

    /** 图表元素错峰间隔（第 9 个起与第 8 个同时起跑，见 [ChartStaggerCap]） */
    const val ChartStaggerMs = 30

    /** 错峰参与上限 */
    const val ChartStaggerCap = 8

    /** 图表入场时间线总长（线性）：生长时间 + 错峰尾 */
    val ChartTotalMs: Int = ChartGrowMs + ChartStaggerMs * ChartStaggerCap

    // ——— 位移三档 ———
    /** 微位移：小元素浮起（页面入场微升） */
    val ShiftMicro: Dp = 8.dp

    /** 标准位移：卡片入场、居中浮层、侧板滑入 */
    val ShiftStandard: Dp = 24.dp

    /** 页面位移：翻页（前进/后退的横向推移） */
    val ShiftPage: Dp = 32.dp

    // ——— 形变 ———
    /** 按压缩放：纸被手指压下 2.5% */
    const val PressScale = 0.975f

    /** 鼠标悬停拈起量（dp，负值向上）。仅鼠标/触控板触发，触屏无 hover 事件 */
    const val HoverLiftDp = -1f

    // ——— 曲线三档 ———
    /** 进场 / 展开：快起慢收（手把纸放下，到位即停） */
    val PaperOut = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 出场 / 折叠：慢起快收（纸被抽走，末端加速） */
    val PaperIn = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 切换 / 形变：两端软中段快（索引贴凸出、状态形变） */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * 线性：恒定速率、无缓动（图表生长时间线 / 同步角标无限旋转必需）。
     * 无限循环的首尾必须相接，缓动会在每圈接缝处顿一下；图表单条时间线同样要求恒速。
     */
    val Linear: Easing = LinearEasing

    /**
     * 按压回弹弹簧：唯一允许的轻回弹（damping 0.85 ≈ 略欠阻尼）。
     * 更低的阻尼会有果冻感，与「纸几乎不弹」的隐喻冲突。
     */
    fun <T> pressSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/* ============================================================
   动画规格生成（时长缩放交给 MotionDurationScale，此处不换算）
   ============================================================ */

/** 生成 tween 规格 */
fun <T> slTween(durationMs: Int, easing: CubicBezierEasing = SlMotion.Standard): TweenSpec<T> =
    tween(durationMs, easing = easing)

/** 标准档 tween（250ms） */
fun <T> slStandard(easing: CubicBezierEasing = SlMotion.PaperOut): TweenSpec<T> =
    slTween(SlMotion.StandardMs, easing)

/** 快档 tween（150ms） */
fun <T> slFast(easing: CubicBezierEasing = SlMotion.Standard): TweenSpec<T> =
    slTween(SlMotion.FastMs, easing)

/* ============================================================
   图表生长时间线
   ============================================================ */

/**
 * 图表入场的**线性**时间线（0f → 1f，总长 [SlMotion.ChartTotalMs]）。
 *
 * 单条时间线 + 纯函数映射（[chartGrow]），不给每根柱各开协程——
 * 时间线里没有 delay，系统关动画时瞬时到 1、所有几何立即成形，
 * 天然完整降级。
 */
@Composable
fun rememberSlChartTimeline(): Float {
    val timeline = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        timeline.animateTo(
            1f,
            tween(SlMotion.ChartTotalMs, easing = SlMotion.Linear),
        )
    }
    return timeline.value
}

/**
 * 把时间线映射为第 [index] 个图表元素的生长进度（0f → 1f，自带 PaperOut 缓动）。
 *
 * 调用方用它乘几何量：柱高、条长、环扫角。**基准边不动**——
 * 柱底贴基线、条左端贴起跑线、环从 12 点起扫。
 */
fun chartGrow(timeline: Float, index: Int = 0): Float {
    val delayMs = minOf(index, SlMotion.ChartStaggerCap) * SlMotion.ChartStaggerMs
    val local = ((timeline * SlMotion.ChartTotalMs - delayMs) / SlMotion.ChartGrowMs)
        .coerceIn(0f, 1f)
    return SlMotion.PaperOut.transform(local)
}

/* ============================================================
   修饰符：按压 / 悬停（形变层降级走 LocalReduceMotion）
   ============================================================ */

/**
 * 按压轻压（纸被手指压下 [SlMotion.PressScale]）。
 *
 * 用于**纸片类可按压面**（SlipCard / 索引贴 / 账目行）。M3 标准控件
 * （Button / IconButton / Chip）已有涟漪作按压反馈，**不再叠用**——
 * 双重反馈会让按钮"又缩又闪"，正是要避免的过度动画。
 *
 * 减少动效下缩放归零（涟漪/色反馈仍在）：缩放是装饰，不是信息。
 *
 * ⚠️ 必须与 `clickable(interactionSource = 同一个)` 配对使用：
 * 按压状态从 interactionSource 收集，自己造一个不接线的源永远收不到按下。
 */
@Composable
fun Modifier.slPress(interactionSource: MutableInteractionSource): Modifier {
    if (LocalReduceMotion.current) return this
    val pressed by interactionSource.collectIsPressedAsState()
    // 按下走快 tween（70ms 压下），抬起走轻回弹弹簧（120ms 观感）
    val scale by animateFloatAsState(
        targetValue = if (pressed) SlMotion.PressScale else 1f,
        animationSpec = if (pressed) {
            slTween(SlMotion.PressDownMs, SlMotion.Standard)
        } else {
            SlMotion.pressSpring()
        },
        label = "slPress",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 鼠标悬停拈起（纸被轻轻拈起 1dp）。
 *
 * **仅指针设备**（桌面 / 平板 + 鼠标 / 触控板）触发 hover 事件；触屏永远不会
 * 进入悬停态，移动端表现零影响——「移动端与桌面端一致」的正确做法是
 * 同一套纸片语言，桌面端多一个拈起提示，而不是另做一套 hover 高亮。
 *
 * 减少动效下整体关闭：hover 是纯装饰反馈，位移（哪怕 1dp）也在
 * 「减少动态效果」的约束范围内。
 */
@Composable
fun Modifier.slHoverLift(): Modifier {
    if (LocalReduceMotion.current) return this
    // hoverable 是 Compose 的标准悬停入口（没有 onHover 这个修饰符）：
    // 只有指针设备会产生 hover 交互，触屏零影响
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val hovered by hoverInteractionSource.collectIsHoveredAsState()
    val density = LocalDensity.current.density
    val lift by animateFloatAsState(
        targetValue = if (hovered) SlMotion.HoverLiftDp else 0f,
        animationSpec = slFast(SlMotion.Standard),
        label = "slHoverLift",
    )
    return this
        .hoverable(hoverInteractionSource)
        .graphicsLayer { translationY = lift * density }
}

/**
 * 列表项增删移位动效（在 LazyColumn/LazyGrid 的 item 内容里调用）。
 *
 * 规格与标准档一致：插入/移动 250ms PaperOut，删除 150ms PaperIn。
 * **不做逐条滚动入场**——滚动是高频路径，条条淡入会拖慢扫读；入场表演
 * 交给页面切换与图表，列表本身只在**数据变化**时动（记一笔插入、
 * 撤销恢复、分区重排都是它的用武之地）。
 */
fun LazyItemScope.slAnimateItem(modifier: Modifier = Modifier): Modifier = modifier.animateItem(
    fadeInSpec = slStandard<Float>(SlMotion.PaperOut),
    placementSpec = slStandard<IntOffset>(SlMotion.PaperOut),
    fadeOutSpec = slTween<Float>(SlMotion.FastMs, SlMotion.PaperIn),
)

/* ============================================================
   画布工具
   ============================================================ */

/**
 * 从 12 点方向顺时针扫过 [sweepDeg] 度的扇形路径（图表「画出来」入场用）。
 * 返回 **android.graphics.Path**：配合 `nativeCanvas.save()/clipPath()/restore()`
 * 做扫入裁剪（与图表里的 nativeCanvas.drawText 同一绘制通道）。
 */
fun sweepClipPath(cx: Float, cy: Float, radius: Float, sweepDeg: Float): android.graphics.Path =
    android.graphics.Path().apply {
        moveTo(cx, cy)
        addArc(
            cx - radius, cy - radius, cx + radius, cy + radius,
            -90f,
            sweepDeg.coerceAtLeast(0.01f),
        )
        close()
    }
