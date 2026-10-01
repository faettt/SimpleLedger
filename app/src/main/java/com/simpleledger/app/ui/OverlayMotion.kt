package com.simpleledger.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import com.simpleledger.app.ui.theme.SlEasing
import com.simpleledger.app.ui.theme.SlTempo
import com.simpleledger.app.ui.theme.slScene

/* ============================================================
   覆层显隐 —— 「纸片菜单语言」的公共规格
   （动效语言 v2，见 ui/theme/Motion.kt 与 docs/design/motion-spec-2026-10-01.md）
   ============================================================

   同一语态的三处消费收拢于此（v1 各自复制一份 spec 字面量，改一处漏两处）：
   · 贴图放大层：entry/EntryEditForm.kt、ledger/LedgerDetailPane.kt
   · 分区纸片菜单：section/SectionHomeScreen.kt（SectionCardMenu）
   进/退场档位与曲线只此一处，缩放锚点由调用点按锚定方向传入。
   场景轨 slScene：覆层是场景层（predictive back 时长契约），不用弹簧。
 */

/** 覆层显隐的形变量：纸片从 96% 轻放到原大、退场缩回 96%（进出场同值）。 */
private const val OverlayRevealScale = 0.96f

/**
 * 覆层进场：标准档（[SlTempo.Base] 250ms，快起慢收）淡入 + 从 96% 放大到原大
 * —— 纸被「轻放」到锚点。
 *
 * [origin] 按锚定方向传：居中浮层（贴图放大层）用 [TransformOrigin.Center]；
 * 贴边菜单用 `TransformOrigin(1f, 0f)`（右上锚，菜单不漂）。
 */
fun slOverlayEnter(origin: TransformOrigin): EnterTransition =
    fadeIn(slScene(SlTempo.Base, SlEasing.Enter)) +
        scaleIn(
            slScene(SlTempo.Base, SlEasing.Enter),
            initialScale = OverlayRevealScale,
            transformOrigin = origin,
        )

/**
 * 覆层退场：快档（[SlTempo.Fast] 150ms，慢起快收）淡出 + 缩回 96%
 * —— 纸被「抽走」，末端加速消失。
 *
 * [origin] 必须与配对的 [slOverlayEnter] 相同：进出场围绕同一锚点，
 * 观感才是「原处收放」而不是「斜着飞走」。
 */
fun slOverlayExit(origin: TransformOrigin): ExitTransition =
    fadeOut(slScene(SlTempo.Fast, SlEasing.Exit)) +
        scaleOut(
            slScene(SlTempo.Fast, SlEasing.Exit),
            targetScale = OverlayRevealScale,
            transformOrigin = origin,
        )
