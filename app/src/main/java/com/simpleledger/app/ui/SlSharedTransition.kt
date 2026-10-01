package com.simpleledger.app.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.simpleledger.app.ui.theme.SlEasing
import com.simpleledger.app.ui.theme.SlTempo
import com.simpleledger.app.ui.theme.slScene

/**
 * Container Transform（M3 容器变换 · 2026-10-01 采纳；v2 起按规格 §6.3「一纸一动」
 * 红线装配）的装配件：分区卡 ↔ 分区详情的**头部块**做共享边界形变——点击进详情时，
 * 卡片上的「图标 + 分区名 + 金额」那张小纸头飞到详情页页眉处长大成形，返回时原路归位。
 *
 * 分层方式（§6.3 红线：**一次转场只有一个运动主角**）：页面层只做短淡化
 * （SlPageMotion 的 Container 配方，经 AppRoot 关系表装配），空间运动由本共享块
 * **独家承担**——共享块经 [SharedTransitionScope] 渲染进 overlay，独立于页面
 * 从卡片矩形形变到页眉矩形。页面层若再做位移/缩放即双运动（v1.5.6 双运动史的
 * 红线，禁止——该对曾因此实测 95 分位 800ms/帧）。
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
 * 边界形变 = 场景轨 [slScene] 320ms（SlTempo.Slow，时长契约 §4.1）。**可 seek 契约**
 * （tween + 单调缓动，§6.4）：predictive back（targetSdk 37 强制开启）拖拽返回时，
 * 本块挂在 NavHost 的同一 transition 上，随 SeekableTransitionState 逐帧跟手飞回
 * 卡位——勿改弹簧规格。内容交叉淡化 = 快档 150ms Standard（纸头的内容在飞行途中
 * 换装，不与边界抢戏）。
 *
 * 回程缓动镜像（§11.3 / 审计疑点⑥裁定，v1 两条腿都用 Enter 未镜像）：去程**长大**
 * 用 Enter（快起慢收，纸头到位 settle）；回程**缩回**用 Exit（慢起快收，与「纸被
 * 抽走」的离场语言一致）。以目标矩形面积判向——目标更大 = 去程，否则 = 回程；
 * 两条腿都是单调 tween，seek 可逆性不受判向影响（判向在转场启动时即确定）。
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
            enter = fadeIn(slScene(SlTempo.Fast, SlEasing.Standard)),
            exit = fadeOut(slScene(SlTempo.Fast, SlEasing.Standard)),
            boundsTransform = { initial, target ->
                if (target.width * target.height >= initial.width * initial.height) {
                    slScene(SlTempo.Slow, SlEasing.Enter) // 开：卡片纸头 → 详情页眉（长大）
                } else {
                    slScene(SlTempo.Slow, SlEasing.Exit) // 收：页眉 → 卡片原位（缩回；含 predictive back 拖拽）
                }
            },
        )
    }
}
