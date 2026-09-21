package com.simpleledger.app.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/*
 * 手绘图标集（由 docs/design/icons/_generate.py 生成，请勿手改）
 * ==================================================================
 * 规范：24dp 画布（插图 32dp）· 圆头端点 · 只描边不填充 · 单色
 *      描边按光学尺寸分档 —— full 1.5 / inline 1.7 / xs 2.0 / lg 1.6 / status 1.8
 *
 * 为什么小尺寸要单独出图标：同一图形在 11dp 与 24dp 下不能共用一套细节。
 * 笔画少的在小尺寸下会糊成一团灰，笔画多的在大尺寸下会显空。所以
 * inline / xs 档**减笔画并把描边加粗**——这不是冗余资源，是必需项。
 *
 * 颜色：路径一律用 `Color.Black` 构建，实际颜色由 `Icon(tint = …)` 或
 * `LocalContentColor` 决定。**分类图标不得上色**——颜色语义已被「分区身份」独占
 * （分区色条 / 胶带色板），若分类图标也上色，同一行会出现两套色彩语义。
 *
 * 手工感来自路径几何本身的不对称，**不在运行时抖动**：抖动会在滚动时闪烁，
 * 且在不同屏幕密度下渲染成糊团。
 */

/** 一条路径：[d] 为 SVG path data；[filled] 为 true 时填充而非描边（语义需要时使用） */
internal data class IconPath(val d: String, val filled: Boolean = false)

/** 描边路径 */
internal fun p(d: String) = IconPath(d)

/** 填充路径（仅用于「已完成」实心圆、动物眼睛等语义必需处） */
internal fun pf(d: String) = IconPath(d, filled = true)

internal fun buildIcon(
    name: String,
    size: Int,
    strokeWidth: Float,
    translationX: Float = 0f,
    translationY: Float = 0f,
    vararg paths: IconPath,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = size.dp,
    defaultHeight = size.dp,
    viewportWidth = size.toFloat(),
    viewportHeight = size.toFloat(),
).apply {
    // 光学居中：把字形平移到画布中心（修正量由 tools/audit_icons.py --emit-centering 生成）
    group(translationX = translationX, translationY = translationY) {
    paths.forEach { path ->
        if (path.filled) {
            addPath(pathData = addPathNodes(path.d), fill = SolidColor(Color.Black))
        } else {
            addPath(
                pathData = addPathNodes(path.d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
    }
}.build()

/**
 * 图标集入口。
 *
 * 命名与分组：`SlIcons.Category.RiceBowl` / `SlIcons.Nav.Section` / `SlIcons.Ui.Add` …
 * 与 `docs/design/icons/manifest.json` 的 `name` 一一对应。
 *
 * 用 `by lazy`：72 枚若在类初始化时全建，会拖慢冷启动；实际每次只用其中几枚。
 */
object SlIcons {

    /** 分类 / 分区图标 50 枚（iconId 1–50） */
    val Category: SlCategoryIcons get() = SlCategoryIcons


    /** 底部导航（4 枚） */
    object Nav {

        val Section: ImageVector by lazy {
            buildIcon(
                "section",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M3.9 10.2c0-.9.7-1.6 1.6-1.6h2.9l1.5 1.8h8.2c.9 0 1.6.7 1.6 1.6v6.2c0 .9-.7 1.6-1.6 1.6H5.5c-.9 0-1.6-.7-1.6-1.6z"),
                p("M6.4 7.4h10.8"),
            )
        }

        val Ledger: ImageVector by lazy {
            buildIcon(
                "ledger",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M7.0 6.4h10.2a1.6 1.6 0 0 1 1.6 1.6v8.5a1.9 1.9 0 0 1 -1.9 1.9h-10.0a1.7 1.7 0 0 1 -1.7 -1.7v-8.5a1.8 1.8 0 0 1 1.8 -1.8z"),
                p("M8.4 9.4h7.2M8.4 12h7.2M8.4 14.6h4.4"),
            )
        }

        val Stats: ImageVector by lazy {
            buildIcon(
                "stats",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M4.2 19.2h15.6"),
                p("M7 19.2v-5.4"),
                p("M11 19.2V9.4"),
                p("M15 19.2v-3.6"),
                p("M18.9 19.2V6.6"),
            )
        }

        val Mine: ImageVector by lazy {
            buildIcon(
                "mine",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M8.6 8.4a3.4 3.4 0 1 0 6.8 0a3.4 3.4 0 1 0 -6.8 0"),
                p("M5.4 19.6c.8-3.9 3.4-5.9 6.6-5.9s5.8 2 6.6 5.9"),
            )
        }

    }

    /** 功能图标 + 行内标记 + 极小对勾（13 枚） */
    object Ui {

        val Add: ImageVector by lazy {
            buildIcon(
                "add",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M12.2 5.3c-.3 4.6-.3 9.2-.1 13.7"),
                p("M5.4 12.1c4.5.2 9.1.1 13.5-.1"),
            )
        }

        val Close: ImageVector by lazy {
            buildIcon(
                "close",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M6.2 6.1c3.9 4.1 7.8 8 11.6 12.1"),
                p("M18.1 6.3c-4 3.9-7.9 7.8-11.8 11.6"),
            )
        }

        val Delete: ImageVector by lazy {
            buildIcon(
                "delete",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M5.2 7.1c4.6-.2 9.2-.2 13.7 0"),
                p("M9.2 7V4.9h5.7V7"),
                p("M6.6 7.2l1 11.9h8.9l1-11.9"),
                p("M10.1 10.2v5.8"),
                p("M14 10.4v5.6"),
            )
        }

        val Check: ImageVector by lazy {
            buildIcon(
                "check",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M5.2 12.7c1.5 1.4 3 2.8 4.4 4.3 2.4-3.7 5.6-7 9.4-10.2"),
            )
        }

        val Edit: ImageVector by lazy {
            buildIcon(
                "edit",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M15.9 5.1l3.2 3.2-9.5 9.5-3.9.7.7-3.9z"),
                p("M14.2 6.8l3.2 3.2"),
            )
        }

        val ArrowLeft: ImageVector by lazy {
            buildIcon(
                "arrow-left",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M15.1 5.2c-2.4 2.3-4.7 4.6-7.1 7 2.4 2.3 4.7 4.6 7.1 6.9"),
            )
        }

        val ArrowRight: ImageVector by lazy {
            buildIcon(
                "arrow-right",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M8.9 5.2c2.4 2.3 4.7 4.6 7.1 7-2.4 2.3-4.7 4.6-7.1 6.9"),
            )
        }

        val ArrowUp: ImageVector by lazy {
            buildIcon(
                "arrow-up",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M5.2 15.1c2.3-2.4 4.6-4.7 7-7.1 2.3 2.4 4.6 4.7 6.9 7.1"),
            )
        }

        val ArrowDown: ImageVector by lazy {
            buildIcon(
                "arrow-down",
                size = 24,
                strokeWidth = 1.5f,
                translationX = 0f,
                translationY = 0f,
                p("M5.2 8.9c2.3 2.4 4.6 4.7 7 7.1 2.3-2.4 4.6-4.7 6.9-7.1"),
            )
        }

        val CalendarInline: ImageVector by lazy {
            buildIcon(
                "calendar-inline",
                size = 24,
                strokeWidth = 1.7f,
                translationX = 0f,
                translationY = 0f,
                p("M6.0 5.8h12.0a1.6 1.6 0 0 1 1.6 1.6v10.4a1.6 1.6 0 0 1 -1.6 1.6h-12.0a1.6 1.6 0 0 1 -1.6 -1.6v-10.4a1.6 1.6 0 0 1 1.6 -1.6z"),
                p("M4.4 10.2h15.2"),
                p("M8.6 4.2v3.2M15.4 4.2v3.2"),
            )
        }

        val ClockInline: ImageVector by lazy {
            buildIcon(
                "clock-inline",
                size = 24,
                strokeWidth = 1.7f,
                translationX = 0f,
                translationY = 0f,
                p("M4.4 12.0a7.6 7.6 0 1 0 15.2 0a7.6 7.6 0 1 0 -15.2 0"),
                p("M12 7.8v4.6l3 1.9"),
            )
        }

        val NoteInline: ImageVector by lazy {
            buildIcon(
                "note-inline",
                size = 24,
                strokeWidth = 1.7f,
                translationX = 0f,
                translationY = 0f,
                p("M4.8 7.6a1.6 1.6 0 0 1 1.6-1.6h11.2a1.6 1.6 0 0 1 1.6 1.6v6.2a1.6 1.6 0 0 1-1.6 1.6h-5.8l-4.2 3.4v-3.4H6.4a1.6 1.6 0 0 1-1.6-1.6z"),
            )
        }

        val CameraInline: ImageVector by lazy {
            buildIcon(
                "camera-inline",
                size = 24,
                strokeWidth = 1.7f,
                translationX = 0f,
                translationY = 0f,
                p("M5.6 7.8h12.8a1.8 1.8 0 0 1 1.8 1.8v7.0a1.8 1.8 0 0 1 -1.8 1.8h-12.8a1.8 1.8 0 0 1 -1.8 -1.8v-7.0a1.8 1.8 0 0 1 1.8 -1.8z"),
                p("M8.8 13.1a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0"),
                p("M8.8 7.8l1.2-2.2h4l1.2 2.2"),
            )
        }

        val CheckXs: ImageVector by lazy {
            buildIcon(
                "check-xs",
                size = 24,
                strokeWidth = 2.0f,
                translationX = 0f,
                translationY = 0f,
                p("M6.2 12.4l3.3 3.6 8.3-9.2"),
            )
        }

    }

    /** 空态插图（2 枚，32dp 画布） */
    object Illustration {

        val EmptyLg: ImageVector by lazy {
            buildIcon(
                "empty-lg",
                size = 32,
                strokeWidth = 1.6f,
                translationX = 0f,
                translationY = 0f,
                p("M16 9.4v17.2"),
                p("M16 9.4C13.6 7.1 9.6 6.1 5.2 6.1v17c4.4 0 8.4 1 10.8 3.3"),
                p("M16 9.4c2.4-2.3 6.4-3.3 10.8-3.3v17c-4.4 0-8.4 1-10.8 3.3"),
                p("M8.6 12.6h3.6"),
                p("M19.8 12.6h3.6"),
            )
        }

        val HintLg: ImageVector by lazy {
            buildIcon(
                "hint-lg",
                size = 32,
                strokeWidth = 1.6f,
                translationX = 0f,
                translationY = 0f,
                p("M6.5 12.2h7.6a1.7 1.7 0 0 1 1.7 1.7v0.1a2.0 2.0 0 0 1 -2.0 2.0h-7.4a1.8 1.8 0 0 1 -1.8 -1.8v-0.1a1.9 1.9 0 0 1 1.9 -1.9z"),
                p("M19.6 9.6h3.2a4.2 4.2 0 0 1 4.2 4.2v4.6a5.0 5.0 0 0 1 -5.0 5.0h-2.6a4.4 4.4 0 0 1 -4.4 -4.4v-4.8a4.6 4.6 0 0 1 4.6 -4.6z"),
                p("M18.8 12.2c.2-2.6 1.7-4.0 3.8-4.0"),
                p("M20.6 15.6h4.6"),
                p("M20.6 18.8h3.4"),
            )
        }

    }

    /** 账目状态符号（2 枚，报销维度） */
    object Status {

        val StatusPending: ImageVector by lazy {
            buildIcon(
                "status-pending",
                size = 24,
                strokeWidth = 1.8f,
                translationX = 0f,
                translationY = 0f,
                p("M5.8 12.0a6.2 6.2 0 1 0 12.4 0a6.2 6.2 0 1 0 -12.4 0"),
            )
        }

        val StatusCleared: ImageVector by lazy {
            buildIcon(
                "status-cleared",
                size = 24,
                strokeWidth = 1.8f,
                translationX = 0f,
                translationY = 0f,
                pf("M5.6 12.0a6.4 6.4 0 1 0 12.8 0a6.4 6.4 0 1 0 -12.8 0"),
            )
        }

    }

}
