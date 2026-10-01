package com.simpleledger.app.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.simpleledger.app.ui.theme.SlMotion
import com.simpleledger.app.ui.theme.slTween

/**
 * Container Transform（M3 容器变换 · 2026-10-01 采纳）的装配件：
 * 分区卡 ↔ 分区详情的**头部块**做共享边界形变——点击进详情时，卡片上的
 * 「图标 + 分区名 + 金额」那张小纸头飞到详情页页眉处长大成形，返回时原路归位。
 *
 * 分层方式：页面的「纸页对偶」推入/弹出编排（SlMotion.NavEnter/NavExit 四段）
 * **保持不变**，作为背景的翻页；共享块经 [SharedTransitionScope] 渲染进 overlay，
 * 独立于页面位移从卡片矩形形变到页眉矩形——是叠在翻页之上的「同一张纸头」，
 * 不是又一层页面动画。
 *
 * 降级：减少动效（[LocalReduceMotion]）下返回原 Modifier（无位移形变，
 * 仅剩页面转场）；作用域缺失（平板双栏 / 预览 / 非导航场景）同样静默跳过。
 */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** 当前导航目的地的 AnimatedContent 作用域（NavHost 各 composable 内提供） */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 分区头部块的共享边界形变。**两端必须用同一个 [key]**（当前约定
 * `"section-header-$sectionId"`：SectionCard 头部行 ↔ SectionDetailScreen 页眉行）。
 *
 * 边界形变 = 慢档 320ms PaperOut（与推入顶页同步）；内容交叉淡化 = 快档 150ms
 * Standard（纸头的内容在飞行途中换装，不与边界抢戏）。
 */
@Composable
fun Modifier.slSharedSectionHeader(key: String): Modifier {
    val sts = LocalSharedTransitionScope.current
    val avs = LocalNavAnimatedVisibilityScope.current
    if (sts == null || avs == null || LocalReduceMotion.current) return this
    return with(sts) {
        this@slSharedSectionHeader.sharedBounds(
            rememberSharedContentState(key),
            animatedVisibilityScope = avs,
            enter = fadeIn(slTween(SlMotion.FastMs, SlMotion.Standard)),
            exit = fadeOut(slTween(SlMotion.FastMs, SlMotion.Standard)),
            boundsTransform = { _, _ ->
                slTween(SlMotion.NavEnterMs, SlMotion.PaperOut)
            },
        )
    }
}
