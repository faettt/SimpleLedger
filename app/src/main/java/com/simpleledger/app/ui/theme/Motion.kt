package com.simpleledger.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.simpleledger.app.ui.LocalReduceMotion

/* ============================================================
   动效语言 v2 ——「有重量的纸」
   （令牌与规格的唯一事实源：本文件；设计规格与迁移指引：
   docs/design/motion-spec-2026-10-01.md。视觉版式真源：
   docs/design/journal-style-spec-2026-09-20.md，本文件只管运动。）
   ============================================================

   上一代「纸的物理」说：纸很轻、摩擦大、几乎不弹——它把动效做成了一组
   漂亮的定格。v2 保留纸的隐喻，但给纸加上质量与惯性：

   ① 反馈先于表演
      按压 70ms 内响应（契约不变）；反馈量感加深到可感（按压 2.5%→4%）。
      动效是反馈，不是节目。

   ② 页面在节拍器上（场景轨）
      页面转场、容器变换、浮层一律走时长契约（tween + 三档 150/250/320）。
      原因：predictive back（targetSdk 37 强制）经 SeekableTransitionState
      把手势进度灌进转场——seek 需要时长确定的规格；且纸页对偶的层次感
      来自「底页 250 早于顶页 320」的 70ms 时差编队，弹簧会破坏它。

   ③ 微交互在弹簧上（状态轨）
      按压/悬停/选中/列表项 placement 一律弹簧：可中断、可续接速度——
      快速连点时动画「被新目标吸走」而不是从零重启。弹簧经 slState(settleMs)
      从时长档翻译出刚度（整定 ≈ 档位时长），节奏不因弹簧而散。

   ④ 一纸一动
      每次转场只有一个运动主角：页面层与共享元素不许同时运动
      （v1.5.6 双运动史的红线，见规格 §6.3）。

   ⑤ 三条存续契约
      · 滚动路径零入场动画；
      · 图表「生长」语义：柱从基线长起、环从 12 点画出、基准边不动，
        且只生长一次（进度跨导航留存，返回时以完整形态出现）；
      · 减少动效两层降级：时长层经 Compose 内部 MotionDurationScale 自动
        （ANIMATOR_DURATION_SCALE，0 = 瞬时；弹簧同样被缩放）；形变层经
        [LocalReduceMotion] 显式（按压缩放归零留涟漪、悬停关闭、转场
        位移降级为纯淡化）。降级判断内建在新 API 里，调用点无须自带 if。

   ⚠️ M3 内置动画（ModalBottomSheet / AlertDialog / DropdownMenu /
      DatePicker / 涟漪）走 Compose 内部 MotionDurationScale，自动尊重
      系统设置——不需要也不应该重包。
   ============================================================ */

/* ============================================================
   域一 · 缓动（SlEasing）—— 数值即 M3 emphasized 家族（§5）
   ============================================================ */

/** 缓动四条。与 M3 内置组件同曲线族：应用自绘与内置组件天然同一语言。 */
object SlEasing {
    /** 入场 / 展开 / settle：快起慢收，到位即停（= M3 emphasized-decelerate） */
    val Enter: CubicBezierEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 离场 / 收拢 / 抽走：慢起快收，末端加速（= M3 emphasized-accelerate） */
    val Exit: CubicBezierEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 无方向语义的切换 / 形变：两端软中段快（= M3 emphasized） */
    val Standard: CubicBezierEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * 线性：恒定速率、无缓动。**循环与图表时间线专用**——无限循环的首尾
     * 必须相接，缓动会在每圈接缝处顿一下；图表单条时间线同样要求恒速。
     */
    val Linear: Easing = LinearEasing
}

/* ============================================================
   域二 · 节奏（SlTempo）—— 时长契约档（§4.4），数值经真机 A/B 拍板
   ============================================================ */

/** 时长档（ms）。调用点只引用档位，禁止散写时长数值。 */
object SlTempo {
    /** 反馈 · 按下：手指碰到纸，70ms 内压到底（硬契约） */
    const val Press = 70

    /** 反馈 · 抬起：弹簧 settle 整定目标（纸落回桌面那一下） */
    const val Release = 120

    /** 反馈 · 小状态（选中 / chip / 色）；场景 · 快离场 */
    const val Fast = 150

    /** 场景 · 标准：浮层、卡面、共享内容交叉、列表项 */
    const val Base = 250

    /** 场景 · 整页：纸页对偶顶页、铺页 */
    const val Slow = 320

    /** 图表：单根柱 / 单根条 / 环扫的生长时间 */
    const val ChartGrow = 420

    /** 图表：错峰间隔（第 9 个起与第 8 个同时起跑，见 [ChartStaggerCap]） */
    const val ChartStagger = 30

    /** 图表：错峰参与上限 */
    const val ChartStaggerCap = 8

    /** 图表：入场时间线总长（线性）= 生长时间 + 错峰尾 */
    val ChartTotal: Int = ChartGrow + ChartStagger * ChartStaggerCap

    /** 循环类专用：同步墨点转一圈（Linear，无限循环；勿从 UI 档派生） */
    const val Spin = 1920
}

/* ============================================================
   域三 · 位移（SlShift）—— 三档距离
   ============================================================ */

/** 位移三档。纸片是被「轻放在桌上」的，不是被抛进来的。 */
object SlShift {
    /** 微位移：小元素浮起（换章入场微升） */
    val Micro: Dp = 8.dp

    /** 标准位移：卡片入场、底页让位/归位、居中浮层、侧板滑入 */
    val Standard: Dp = 24.dp

    /** 页面位移：翻页（前进/后退的横向推移） */
    val Page: Dp = 32.dp
}

/* ============================================================
   域四 · 形变量（SlFeel）—— 反馈的量感与转场纵深
   ============================================================ */

/** 形变量。位移/缩放是装饰不是信息：减少动效下全部让位于涟漪与色反馈。 */
object SlFeel {
    /** 按压缩放：纸被手指压下 4%（v1 为 2.5%，小控件上不可感——规格 §7 Δ） */
    const val PressScale = 0.96f

    /** 按压抬起弹簧的阻尼：略欠阻尼的一次轻 settle，不是果冻 */
    const val PressBounce = 0.8f

    /** 鼠标悬停拈起量（dp，负值向上）。仅指针设备触发，触屏无 hover 事件 */
    const val HoverLiftDp = -2f

    /** 纸页对偶：底页缩沉量（让位压到 94%、归位从它回升的纵深） */
    const val NavScaleSink = 0.94f

    /** 纸页对偶：push 底页让位的变暗终点（pop 归位起点见 [NavAlphaReturnStart]） */
    const val NavAlphaUnder = 0.5f

    /**
     * 纸页对偶：pop 底页归位的透明度起点（0 = 全透明起亮）。
     * 与 [NavAlphaUnder] 解耦（v1 裁定保留）：AnimatedContent 恒把进场页
     * （pop 时 = 底页）画在离场顶页上层，从 0.5 起步会隔一层半透明面纱
     * 盖住正被抽走的顶页。让位终点是 push 的层次语义；归位起点是 pop 的
     * 绘制顺序约束。
     */
    const val NavAlphaReturnStart = 0f
}

/* ============================================================
   双轨规格生成器（§4）：场景轨 tween · 状态轨 spring
   时长缩放交给 Compose 内部 MotionDurationScale，此处不换算。
   ============================================================ */

/**
 * 场景轨规格生成器：时长契约 + 缓动 → [TweenSpec]。
 *
 * 用于页面转场、容器变换、浮层等**场景层**。必须（而不是可选）是 tween：
 * predictive back 经 SeekableTransitionState 拖拽 seek，需要时长确定的规格；
 * 多层编队（底页早 70ms 收尾）需要确定性时序。easing 传单调曲线
 * （[SlEasing.Enter] / [SlEasing.Exit] / [SlEasing.Standard] / [SlEasing.Linear]）。
 */
fun <T> slScene(durationMs: Int, easing: Easing = SlEasing.Standard): TweenSpec<T> =
    tween(durationMs, easing = easing)

/**
 * 状态轨规格生成器：整定目标（ms）+ 阻尼 → [SpringSpec]。
 *
 * 用于按压/悬停/选中/列表项 placement 等**状态层**：可中断、可续接速度。
 * 刚度由整定判据从时长档翻译：临界阻尼下 settle ≈ 4/ω（2% 残余），
 * 即 ω = 4000/settleMs（rad/s）、stiffness = ω²。档位感知等价
 * （弹簧约在 settleMs 内收敛），但连点时续速而非重启——节奏不因弹簧而散。
 *
 * @param settleMs 整定目标（毫秒），取 [SlTempo] 档位
 * @param dampingRatio 1 = 临界（不弹）；按下抬起等需要一次轻 settle 的用 0.8
 */
fun <T> slState(settleMs: Int = SlTempo.Fast, dampingRatio: Float = Spring.DampingRatioNoBouncy): SpringSpec<T> {
    val omega = SPRING_SETTLE_RADIANS * 1000f / settleMs.coerceAtLeast(1)
    return spring(
        dampingRatio = dampingRatio,
        // 刚度 = ω²：settle 150ms → 711、250ms → 256（Spring 常量刻度内）
        stiffness = (omega * omega).coerceIn(1f, Spring.StiffnessHigh),
    )
}

/** 临界阻尼 2% 整定判据：settle ≈ 4/ω 秒（slState 的刚度推导常数） */
private const val SPRING_SETTLE_RADIANS = 4f

/* ============================================================
   页面转场模式学（§6）：关系表 + 装配
   ============================================================ */

/**
 * 页面路由关系——转场模式的唯一判定输入。
 *
 * UI 层只保留一张「路由对 → [SlRelation]」映射；四模式 × 四方向（进/出/
 * pop 进/pop 出）的规格由 [SlPageMotion] 独家装配。v1 散在 AppRoot when 链
 * 里的方向判定（双运动 bug 的根因）由此封死。
 */
enum class SlRelation {
    /** 换章：底部 4 签互切、签 ↔ 记一笔。同层无方向语义，不做横移 */
    Chapter,

    /** 层级压栈/回栈：签 ↔ 层级页（详情/管理/设置）。纸页对偶四段 */
    Push,

    /** 铺页：全屏表单（记一笔）盖上来。新纸从下方轻铺，下层只淡化不位移 */
    Form,

    /**
     * 容器变换对：分区卡 ↔ 分区详情。**一纸一动**：共享纸头独家承担空间
     * 运动，页面层只做短淡化（页面层做位移/缩放 = 双运动，规格红线）
     */
    Container,
}

/**
 * 页面转场四方向装配（v1「纸页对偶」数值原样收拢，几何 push/pop 空间互逆——
 * predictive back 拖拽 = 倒放压栈；时间允许不对称：点按回退 250 求快，
 * 拖拽只消费空间路径）。减少动效一律降级为纯淡化（时长层自动瞬时）。
 *
 * 经 [rememberSlPageMotion] 构造；dp 位移已在构造时换算成 px。
 */
@Immutable
class SlPageMotion internal constructor(
    private val shiftPagePx: Int,
    private val shiftUnderPx: Int,
    private val microShiftPx: Int,
    private val reduceMotion: Boolean,
) {
    /** 进场（push 前进 / 换章 / 铺页 / 容器对）。NavHost enterTransition 用 */
    fun enter(relation: SlRelation): EnterTransition = when (relation) {
        SlRelation.Chapter ->
            if (reduceMotion) {
                fadeIn(slScene(SlTempo.Base, SlEasing.Enter))
            } else {
                fadeIn(slScene(SlTempo.Base, SlEasing.Enter)) +
                    slideInVertically(slScene(SlTempo.Base, SlEasing.Enter)) { microShiftPx }
            }

        SlRelation.Push ->
            if (reduceMotion) {
                fadeIn(slScene(SlTempo.Base, SlEasing.Enter))
            } else {
                // 顶页：右 32dp 推进 + 缩沉回升 + 淡入（320ms Enter）
                fadeIn(slScene(SlTempo.Slow, SlEasing.Enter)) +
                    slideInHorizontally(slScene(SlTempo.Slow, SlEasing.Enter)) { shiftPagePx } +
                    scaleIn(slScene(SlTempo.Slow, SlEasing.Enter), initialScale = SlFeel.NavScaleSink)
            }

        SlRelation.Form ->
            if (reduceMotion) {
                fadeIn(slScene(SlTempo.Base, SlEasing.Enter))
            } else {
                // 铺页：新纸从下方 24dp 轻铺上来（320ms Enter）
                fadeIn(slScene(SlTempo.Slow, SlEasing.Enter)) +
                    slideInVertically(slScene(SlTempo.Slow, SlEasing.Enter)) { shiftUnderPx }
            }

        SlRelation.Container ->
            // 一纸一动：页面层原地短淡化，运动归共享纸头（§6.3 红线）
            fadeIn(slScene(SlTempo.Fast, SlEasing.Standard))
    }

    /** 离场（被 push 覆盖 / 换章让位 / 表单抽走 / 容器对）。NavHost exitTransition 用 */
    fun exit(relation: SlRelation): ExitTransition = when (relation) {
        SlRelation.Chapter ->
            if (reduceMotion) {
                fadeOut(slScene(SlTempo.Base, SlEasing.Enter))
            } else {
                fadeOut(slScene(SlTempo.Fast, SlEasing.Exit))
            }

        SlRelation.Push ->
            if (reduceMotion) {
                fadeOut(slScene(SlTempo.Base, SlEasing.Enter))
            } else {
                // 底页让位：24dp 退让 + 缩沉 0.94 + 变暗至 0.5（250ms，早顶页 70ms 收）
                slideOutHorizontally(slScene(SlTempo.Base, SlEasing.Enter)) { -shiftUnderPx } +
                    scaleOut(slScene(SlTempo.Base, SlEasing.Enter), targetScale = SlFeel.NavScaleSink) +
                    fadeOut(slScene(SlTempo.Base, SlEasing.Enter), targetAlpha = SlFeel.NavAlphaUnder)
            }

        SlRelation.Form ->
            if (reduceMotion) {
                fadeOut(slScene(SlTempo.Base, SlEasing.Enter))
            } else {
                // 表单向下轻抽 + 淡出（150ms Exit）
                fadeOut(slScene(SlTempo.Fast, SlEasing.Exit)) +
                    slideOutVertically(slScene(SlTempo.Fast, SlEasing.Exit)) { shiftUnderPx }
            }

        SlRelation.Container -> fadeOut(slScene(SlTempo.Fast, SlEasing.Standard))
    }

    /** pop 进场（回栈时底页归位 / 返回换章页）。NavHost popEnterTransition 用 */
    fun popEnter(relation: SlRelation): EnterTransition = when (relation) {
        SlRelation.Chapter -> enter(SlRelation.Chapter)

        SlRelation.Push ->
            if (reduceMotion) {
                fadeIn(slScene(SlTempo.Base, SlEasing.Enter))
            } else {
                // 底页归位：24dp 归位 + 回升 + 从全透明亮起
                // （起点 α=0 而非让位终点 0.5：进场页画在上层，见 SlFeel.NavAlphaReturnStart）
                slideInHorizontally(slScene(SlTempo.Base, SlEasing.Enter)) { -shiftUnderPx } +
                    scaleIn(slScene(SlTempo.Base, SlEasing.Enter), initialScale = SlFeel.NavScaleSink) +
                    fadeIn(slScene(SlTempo.Base, SlEasing.Enter), initialAlpha = SlFeel.NavAlphaReturnStart)
            }

        SlRelation.Form -> enter(SlRelation.Chapter)

        SlRelation.Container -> enter(SlRelation.Container)
    }

    /** pop 离场（顶页被抽走 / 返回时表单抽走）。NavHost popExitTransition 用 */
    fun popExit(relation: SlRelation): ExitTransition = when (relation) {
        SlRelation.Chapter -> exit(SlRelation.Chapter)

        SlRelation.Push ->
            if (reduceMotion) {
                fadeOut(slScene(SlTempo.Base, SlEasing.Enter))
            } else {
                // 顶页抽走：向右 32dp + 缩沉 + 淡出（250ms Exit，加速离场）
                slideOutHorizontally(slScene(SlTempo.Base, SlEasing.Exit)) { shiftPagePx } +
                    scaleOut(slScene(SlTempo.Base, SlEasing.Exit), targetScale = SlFeel.NavScaleSink) +
                    fadeOut(slScene(SlTempo.Base, SlEasing.Exit))
            }

        SlRelation.Form -> exit(SlRelation.Form)

        SlRelation.Container -> exit(SlRelation.Container)
    }
}

/**
 * 构造 [SlPageMotion]：内部读取密度（dp→px）与 [LocalReduceMotion]
 * （降级判断内建，调用点无须自带 if）。设置变更经重组在此捕获新值；
 * 进行中的转场保持旧值，下一次转场生效（转场 lambda 非 @Composable 的约束）。
 */
@Composable
fun rememberSlPageMotion(): SlPageMotion {
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    return remember(density, reduceMotion) {
        with(density) {
            SlPageMotion(
                shiftPagePx = SlShift.Page.roundToPx(),
                shiftUnderPx = SlShift.Standard.roundToPx(),
                microShiftPx = SlShift.Micro.roundToPx(),
                reduceMotion = reduceMotion,
            )
        }
    }
}

/* ============================================================
   图表生长时间线（§8）
   ============================================================ */

/**
 * 图表入场的**线性**时间线（0f → 1f，总长 [SlTempo.ChartTotal]）。
 *
 * 单条时间线 + 纯函数映射（[chartGrow]），不给每根柱各开协程——
 * 时间线里没有 delay，系统关动画时瞬时到 1、所有几何立即成形，
 * 天然完整降级。
 *
 * **一次生长**（v2）：进度经 `rememberSaveable` 跨导航留存——NavHost 为每个
 * 回退栈条目保存/恢复组合状态，首次到达播放生长，返回时图表以完整形态
 * 直接出现（避免每次切签都重演 660ms 表演）。生长未完就离开则下次从零
 * 重放；数据更新（切月等）瞬时成形，不重播——数据诚实 > 表演。
 */
@Composable
fun rememberSlChartTimeline(): Float {
    val savedProgress = rememberSaveable { mutableStateOf(0f) }
    val timeline = remember { Animatable(savedProgress.value) }
    LaunchedEffect(timeline) {
        if (timeline.value < 1f) {
            timeline.animateTo(1f, tween(SlTempo.ChartTotal, easing = SlEasing.Linear))
            savedProgress.value = timeline.value
        }
    }
    return timeline.value
}

/**
 * 把时间线映射为第 [index] 个图表元素的生长进度（0f → 1f，自带 Enter 缓动）。
 *
 * 调用方用它乘几何量：柱高、条长、环扫角。**基准边不动**——
 * 柱底贴基线、条左端贴起跑线、环从 12 点起扫。
 */
fun chartGrow(timeline: Float, index: Int = 0): Float {
    val delayMs = minOf(index, SlTempo.ChartStaggerCap) * SlTempo.ChartStagger
    val local = ((timeline * SlTempo.ChartTotal - delayMs) / SlTempo.ChartGrow)
        .coerceIn(0f, 1f)
    return SlEasing.Enter.transform(local)
}

/* ============================================================
   修饰符：按压 / 悬停 / 列表项（形变层降级内建，走 LocalReduceMotion）
   ============================================================ */

/**
 * 按压轻压（纸被手指压下 [SlFeel.PressScale] = 4%，抬起一次轻 settle）。
 *
 * 用于**纸片类可按压面**（SlipCard / 索引贴 / 账目行）。M3 标准控件
 * （Button / IconButton / Chip）已有涟漪作按压反馈，**不再叠用**——
 * 双重反馈会让按钮"又缩又闪"。
 *
 * 按下走 [SlTempo.Press]（70ms 契约）tween；抬起走 [slState] 弹簧
 * （[SlFeel.PressBounce] 阻尼、[SlTempo.Release] 整定——纸落回桌面那一下，
 * 可中断，快速连点时续速）。
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
    val scale by animateFloatAsState(
        targetValue = if (pressed) SlFeel.PressScale else 1f,
        animationSpec = if (pressed) {
            slScene(SlTempo.Press, SlEasing.Standard)
        } else {
            slState(SlTempo.Release, SlFeel.PressBounce)
        },
        label = "slPress",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 鼠标悬停拈起（纸被轻轻拈起 2dp）。
 *
 * **仅指针设备**（桌面 / 平板 + 鼠标 / 触控板）触发 hover 事件；触屏永远不
 * 会进入悬停态，移动端表现零影响——「移动端与桌面端一致」的正确做法是
 * 同一套纸片语言，桌面端多一个拈起提示，而不是另做一套 hover 高亮。
 *
 * 走 [slState] 弹簧（[SlTempo.Fast] 整定）：快速掠过多个纸片时拈起可中断续速。
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
        targetValue = if (hovered) SlFeel.HoverLiftDp else 0f,
        animationSpec = slState(SlTempo.Fast),
        label = "slHoverLift",
    )
    return this
        .hoverable(hoverInteractionSource)
        .graphicsLayer { translationY = lift * density }
}

/**
 * 列表项增删移位动效（在 LazyColumn/LazyGrid 的 item 内容里调用）。
 *
 * 进出保持节奏契约：插入淡入 250ms Enter、删除淡出 150ms Exit；
 * **placement 走 [slState] 弹簧**（[SlTempo.Base] 整定）——快速增删/重排时
 * 移位可中断、可续速，不再一顿一顿。**不做逐条滚动入场**——滚动是高频
 * 路径，条条淡入会拖慢扫读；入场表演交给页面切换与图表，列表本身只在
 * **数据变化**时动（记一笔插入、撤销恢复、分区重排）。
 */
fun LazyItemScope.slAnimateItem(modifier: Modifier = Modifier): Modifier = modifier.animateItem(
    fadeInSpec = slScene<Float>(SlTempo.Base, SlEasing.Enter),
    placementSpec = slState<IntOffset>(SlTempo.Base),
    fadeOutSpec = slScene<Float>(SlTempo.Fast, SlEasing.Exit),
)

/* ============================================================
   画布工具
   ============================================================ */

/**
 * 从 12 点方向顺时针扫过 [sweepDeg] 度的扇形路径（图表「画出来」入场用）。
 * 写入调用方提供的 [out] 并返回之：配合 `nativeCanvas.save()/clipPath()/restore()`
 * 做扫入裁剪（与图表里的 nativeCanvas.drawText 同一绘制通道）。
 *
 * ⚠️ 生长期间**每帧**调用（OPT-13 同源）：路径由调用方 `remember { android.graphics.Path() }`
 * 提供并复用，本函数只 `reset()` 重建——new Path() 留在这里会随帧数翻倍分配。
 */
fun sweepClipPath(
    out: android.graphics.Path,
    cx: Float,
    cy: Float,
    radius: Float,
    sweepDeg: Float,
): android.graphics.Path = out.apply {
    reset()
    moveTo(cx, cy)
    addArc(
        cx - radius, cy - radius, cx + radius, cy + radius,
        -90f,
        sweepDeg.coerceAtLeast(0.01f),
    )
    close()
}

/* ============================================================
   ⚠️ 兼容层（@Deprecated）—— v1 API 名全保留，供未迁移调用点编译。
   数值保真规则：本层返回的每个值都与 v1 完全一致（含两处有意冻结的
   字面量），观感变化只来自新 API 的实现（见规格 §10.2 映射表）。
   迁移完成后整体删除（规格 §11 第 7 步）。
   ============================================================ */

/**
 * v1 动效令牌（「纸的物理」）。已被 v2「有重量的纸」四域取代：
 * [SlEasing]（缓动）/ [SlTempo]（节奏）/ [SlShift]（位移）/ [SlFeel]（形变量）。
 * 数值映射见 docs/design/motion-spec-2026-10-01.md §10.2。
 */
@Deprecated(
    "v2 四域取代：SlEasing / SlTempo / SlShift / SlFeel（映射见 motion-spec §10.2）",
)
object SlMotion {
    // ——— 时长档（ms）———
    /** 按压按下（→ [SlTempo.Press]） */
    const val PressDownMs = SlTempo.Press

    /** 按压抬起回弹（→ [SlTempo.Release]） */
    const val PressUpMs = SlTempo.Release

    /** 快档：小状态切换（→ [SlTempo.Fast]） */
    const val FastMs = SlTempo.Fast

    /** 标准档（→ [SlTempo.Base]） */
    const val StandardMs = SlTempo.Base

    /** 慢档：整页翻动（→ [SlTempo.Slow]） */
    const val SlowMs = SlTempo.Slow

    // ——— 图表生长（→ SlTempo.Chart*）———
    /** 单根柱 / 单根条 / 环扫的生长时间 */
    const val ChartGrowMs = SlTempo.ChartGrow

    /** 错峰间隔 */
    const val ChartStaggerMs = SlTempo.ChartStagger

    /** 错峰参与上限 */
    const val ChartStaggerCap = SlTempo.ChartStaggerCap

    /** 图表入场时间线总长 */
    val ChartTotalMs: Int = SlTempo.ChartTotal

    // ——— 位移三档（→ SlShift）———
    /** 微位移（→ [SlShift.Micro]） */
    val ShiftMicro: Dp = SlShift.Micro

    /** 标准位移（→ [SlShift.Standard]） */
    val ShiftStandard: Dp = SlShift.Standard

    /** 页面位移（→ [SlShift.Page]） */
    val ShiftPage: Dp = SlShift.Page

    // ——— 形变 ———
    /**
     * 按压缩放。**冻结为 v1 值 0.975f**（v2 新值 [SlFeel.PressScale] = 0.96
     * 只在新修饰符内生效——迁移前不改变未迁移点的观感）。
     */
    const val PressScale = 0.975f

    /**
     * 悬停拈起量。**冻结为 v1 值 −1f**：SlipCard 直读此令牌做自己的悬停
     * 动画，改指向会让未迁移文件观感突变；迁移时换 [SlFeel.HoverLiftDp]（−2）。
     */
    const val HoverLiftDp = -1f

    // ——— 页面转场 · 纸页对偶（装配层，→ SlPageMotion / SlFeel.Nav*）———
    /** push 顶页进场/离场横移（→ [SlShift.Page]） */
    val NavShiftIn: Dp = SlShift.Page

    /** 底页让位/归位横移（→ [SlShift.Standard]） */
    val NavShiftUnder: Dp = SlShift.Standard

    /** push 顶页进场时长（→ [SlTempo.Slow]） */
    const val NavEnterMs = SlTempo.Slow

    /** 顶页离场与底页让位/归位时长（→ [SlTempo.Base]） */
    const val NavExitMs = SlTempo.Base

    /** 底页缩沉量（→ [SlFeel.NavScaleSink]） */
    const val NavScaleSink = SlFeel.NavScaleSink

    /** push 底页让位变暗终点（→ [SlFeel.NavAlphaUnder]） */
    const val NavAlphaUnder = SlFeel.NavAlphaUnder

    /** pop 底页归位透明度起点（→ [SlFeel.NavAlphaReturnStart]；解耦裁定见该处） */
    const val NavAlphaReturnStart = SlFeel.NavAlphaReturnStart

    // ——— 曲线三档（→ SlEasing）———
    /** 进场 / 展开（→ [SlEasing.Enter]） */
    val PaperOut: CubicBezierEasing = SlEasing.Enter

    /** 出场 / 折叠（→ [SlEasing.Exit]） */
    val PaperIn: CubicBezierEasing = SlEasing.Exit

    /** 切换 / 形变（→ [SlEasing.Standard]） */
    val Standard: CubicBezierEasing = SlEasing.Standard

    /** 线性（→ [SlEasing.Linear]） */
    val Linear: Easing = SlEasing.Linear

    /**
     * 按压回弹弹簧。**冻结为 v1 行为** spring(0.85, StiffnessMediumLow)；
     * 新代码用 [slState]`(SlTempo.Release, SlFeel.PressBounce)`。
     */
    fun <T> pressSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/** v1 规格：生成 tween（→ [slScene]，签名与默认缓动逐字保真） */
@Deprecated(
    "改用 slScene(durationMs, easing)",
    ReplaceWith(
        "slScene(durationMs, easing)",
        "com.simpleledger.app.ui.theme.slScene",
    ),
)
fun <T> slTween(durationMs: Int, easing: CubicBezierEasing = SlEasing.Standard): TweenSpec<T> =
    slScene(durationMs, easing)

/** v1 标准档 tween（250ms，默认缓动 = v1 PaperOut = [SlEasing.Enter]）（→ [slScene]） */
@Deprecated(
    "改用 slScene(SlTempo.Base, easing)",
    ReplaceWith(
        "slScene(SlTempo.Base, easing)",
        "com.simpleledger.app.ui.theme.slScene",
        "com.simpleledger.app.ui.theme.SlTempo",
    ),
)
fun <T> slStandard(easing: CubicBezierEasing = SlEasing.Enter): TweenSpec<T> =
    slScene(SlTempo.Base, easing)

/** v1 快档 tween（150ms）（→ [slScene]） */
@Deprecated(
    "改用 slScene(SlTempo.Fast, easing)",
    ReplaceWith(
        "slScene(SlTempo.Fast, easing)",
        "com.simpleledger.app.ui.theme.slScene",
        "com.simpleledger.app.ui.theme.SlTempo",
    ),
)
fun <T> slFast(easing: CubicBezierEasing = SlEasing.Standard): TweenSpec<T> =
    slScene(SlTempo.Fast, easing)
