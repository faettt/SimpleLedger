package com.simpleledger.app.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/*
 * 同步四态图标（U-4，T-5 手绘）。
 * =====================================================================
 * ⚠️ 为什么不进 SlIcons.kt：SlIcons.kt 是 docs/design/icons-v3/build.py 生成链
 *    的产物（禁手改），而生成链 `validate()` 硬锁 **72 枚**清单 + iconId 1–50 连续，
    *    产物形态是「整文件 drop-in 替换 SlIcons.kt + SlCategoryIcons.kt」——往源里
 *    加 4 枚意味着重绘全部 72 枚并整体替换两个文件，接入成本远超收益。
 *    故按任务书退路：独立文件手绘 Path，与 v3 同风格（24dp 圆头线稿、单色墨青），
 *    直接复用同包 internal 基建 [buildIcon] / [p] / [pf]，SlIcons.kt 零触碰。
 *
 * 描边取 v3 的 status 档 1.8（四枚都是状态符号）；圆头端点、只描边不填充
 * （语义点除外：墨点与「!」的点是实心的，与 v3「语义点除外」口径一致）。
 */
object SyncIcons {

    /** 从未同步：空心圆 */
    val Never: ImageVector by lazy {
        buildIcon(
            "SyncNever", 24, 1.8f, 0f, 0f,
            p("M4.5 12a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0"),
        )
    }

    /** 已同步：手绘 ✓（微弯两笔，非几何直尺线） */
    val Done: ImageVector by lazy {
        buildIcon(
            "SyncDone", 24, 1.8f, 0f, 0f,
            p("M5.4 12.6c2.1 1.9 3.8 3.4 5.2 4.8c2.5-4.0 5.2-7.5 8.1-10.6"),
        )
    }

    /**
     * 同步中：旋转墨点 —— 实心墨点 + 露出四分之三的轨道。
     * 纯圆点原地旋转看不出运动，轨道缺口给了转向参照；整体由
     * `SyncStatusBadge` 用 SlTempo.Spin 循环档驱动旋转。
     */
    val Syncing: ImageVector by lazy {
        buildIcon(
            "Syncing", 24, 1.8f, 0f, 0f,
            p("M12 5.2a6.8 6.8 0 1 1 -6.8 6.8"),
            pf("M10.2 5.2a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0"),
        )
    }

    /** 失败：手绘「!」（微弯竖笔 + 实心点） */
    val Failed: ImageVector by lazy {
        buildIcon(
            "SyncFailed", 24, 1.8f, 0f, 0f,
            p("M12 5.8c-.2 3.0-.2 5.8-.1 8.0"),
            pf("M10.9 17.4a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0"),
        )
    }
}
