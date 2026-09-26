package com.simpleledger.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simpleledger.app.ui.LocalReduceMotion
import com.simpleledger.app.ui.theme.SlMotion
import com.simpleledger.app.ui.theme.SlipShape
import com.simpleledger.app.ui.theme.SlipStackOffset
import com.simpleledger.app.ui.theme.slFast
import com.simpleledger.app.ui.theme.slPress

/**
 * 纸片：手账里承载内容的「一张纸」。
 *
 * 规范依据（docs/design/journal-style-spec-2026-09-20.md §2.2 / §5）：
 *
 * · **不用阴影**。手账里没有卡片投影，只有一张纸压在另一张纸上。
 *   层级由三样东西表达：纸片填充（比页面亮一层）、0.5dp 描边、
 *   以及可选的 [stacked]「垫纸」（背后错位 3dp 的更深一层纸）。
 * · 圆角 3dp（近直角）。大圆角会退回「便当盒贴纸」的观感。
 * · 内边距默认 20dp（规范 `spacing.slipPadding`）。
 *
 * ## 动效（Motion.kt「纸的物理」）
 *
 * · **按压轻压**：整张纸（含垫纸）压下 2.5%，70ms 压下 / 轻回弹抬起；
 * · **悬停拈起**（仅指针设备）：纸片上浮 1dp、垫纸浮现 —— 纸被拈起时
 *   下面那层才露出来。触屏无 hover 事件，移动端零影响；
 * · 减少动效下两者都关闭（缩放/位移是装饰，不是信息）。
 *
 * @param stacked 是否在背后垫一层错位纸。用于需要"这张纸被特别放在上面"的强调位
 *               （如分区首屏的选中卡、空态引导卡）。注意它会向右下溢出 3dp，
 *               调用方应确保四周有余量。
 * @param edgeColor 左侧竖边色。传入分区胶带色即成「分区卡」——分区身份的第一识别通道，
 *                  比读文字快。**必须画在内部**：画在 modifier 上会被纸片自身的填充盖住。
 * @param onClick 传入则整张纸可点（触控目标由调用方保证 ≥48dp）。
 *                ⚠️ 可点必须走这个参数而不是在 modifier 里自己加 `clickable` ——
 *                按压反馈要从同一个 interactionSource 收集，点击写在 modifier 里
 *                会拿不到按压状态（slPress 静默失效）。
 * @param onLongClick 传入则支持长按（combinedClickable，与 onClick 共用同一
 *                    interactionSource；A1「长按分区卡 → 纸片菜单」的承载口）。
 *                    长按会依官方做法**显式**触发一次 `HapticFeedbackType.LongPress`
 *                    （combinedClickable 默认不产生触觉，详见实现处注释）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlipCard(
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    edgeColor: Color? = null,
    edgeWidth: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 按压/悬停共用外层节点：垫纸与纸片一起被压下、一起被拈起。
    // 悬停经 hoverable 收集（Compose 标准悬停入口，没有 onHover 修饰符）：
    // 只有指针设备产生 hover 交互，触屏零影响
    val interactionSource = remember { MutableInteractionSource() }
    val hoverInteractionSource = remember { MutableInteractionSource() }
    val hovered by hoverInteractionSource.collectIsHoveredAsState()
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current.density
    // 触觉入口（= View.performHapticFeedback 的 Compose 封装）。仅在 onLongClick 里使用一次。
    val haptics = LocalHapticFeedback.current

    val hoverLift by animateFloatAsState(
        targetValue = if (hovered && !reduceMotion) SlMotion.HoverLiftDp else 0f,
        animationSpec = slFast(SlMotion.Standard),
        label = "slipHoverLift",
    )
    val underlayAlpha by animateFloatAsState(
        targetValue = if (stacked || (hovered && !reduceMotion)) 1f else 0f,
        animationSpec = slFast(SlMotion.PaperOut),
        label = "slipUnderlay",
    )

    // 按需挂按压反馈：slPress 必须与 clickable(interactionSource = 同一个) 配对才有意义。
    // 不可点的信息卡（回收站/同步设置/成员卡等）永远收不到 PressInteraction，
    // 无条件挂载只会多出一个恒等 graphicsLayer 节点和一个永不触发的收集协程。
    // hoverLift 不受此处影响（悬停去留是独立的观感项，另行拍板）。
    val interactive = onClick != null || onLongClick != null

    Box(
        modifier = modifier
            .hoverable(hoverInteractionSource)
            .then(if (interactive) Modifier.slPress(interactionSource) else Modifier),
    ) {
        // 垫纸：与纸片同尺寸、错位 3dp，露出右下两条边 → 读作「下面还压着一张」。
        // 必须声明在纸签**之前**（Box 内后声明的画在上层）。
        // stacked 恒显；非 stacked 时随 hover 浮现（拈起才见垫纸）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = underlayAlpha }
                .offset(x = SlipStackOffset, y = SlipStackOffset)
                .background(MaterialTheme.colorScheme.surfaceDim, SlipShape),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 拈起：只有纸片脸面上浮，垫纸留在原位 —— 缝隙就是「厚度」
                .graphicsLayer { translationY = hoverLift * density }
                .clip(SlipShape)
                .background(MaterialTheme.colorScheme.surface, SlipShape)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, SlipShape)
                // 左侧边：画在填充与描边**之后**（内层），否则会被纸片填充盖掉
                .then(
                    if (edgeColor != null) {
                        Modifier.drawBehind {
                            drawRect(edgeColor, size = Size(edgeWidth.toPx(), size.height))
                        }
                    } else {
                        Modifier
                    }
                )
                .then(
                    when {
                        onClick != null && onLongClick != null -> Modifier.combinedClickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            onClick = onClick,
                            // ── 长按触觉 ── 本工程触觉的**唯一使用点之一**（另一处为 LedgerRows.EntryRow）。
                            // combinedClickable **默认不产生触觉**，须显式调；官方 Compose「tap-and-press」
                            // 文档的标准示例即如此：haptics.performHapticFeedback(HapticFeedbackType.LongPress)。
                            // 走 LocalHapticFeedback（= View.performHapticFeedback 的 Compose 封装）：
                            //  ① **零权限**——不需要也不加 VIBRATE，不动 Manifest（View 路径官方明确免权限）；
                            //  ② 系统设置 HAPTIC_FEEDBACK_ENABLED=0 时**自动静默**（官方明确 performHapticFeedback
                            //     会尊重该开关），故刻意**不挂 LocalReduceMotion**——那是动画时长开关，与触觉无关；
                            //  ③ LongPress 映射平台 LONG_PRESS（API 3）→ minSdk 26 全机型可用。
                            // ⚠️ 模拟器**无振动器** → 此调用静默 no-op，**无法在模拟器验证**，待真机体感。
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongClick()
                            },
                        )
                        onClick != null -> Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            onClick = onClick,
                        )
                        else -> Modifier
                    }
                )
                .padding(contentPadding),
            content = content,
        )
    }
}
