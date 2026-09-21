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
 * 手绘图标集 v3「纸墨手绘」（由 docs/design/icons-v3/build.py 生成，请勿手改）
 * =====================================================================
 * 规范：24dp 画布（插图 32dp）· 圆头端点 · 只描边不填充（语义点除外）· 单色
 *      描边按光学尺寸分档 —— full 1.5 / inline 1.7 / xs 2.0 / lg 1.6 / status 1.8
 *      光学居中修正量由生成器采样包围盒算出，已写入 buildIcon 的平移参数。
 *
 * 本文件为 drop-in 候选：整文件替换 SlIcons.kt + SlCategoryIcons.kt（API 兼容）。
 */

/** 一条路径：[d] 为 SVG path data；[filled] 为 true 时填充而非描边（语义需要时使用） */
internal data class IconPath(val d: String, val filled: Boolean = false)

internal fun p(d: String) = IconPath(d)

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
 * A 组 · 分类图标（iconId 1–50，存库集合）
 */
object SlCategoryIcons {
    /** iconId 1 · 分区默认 / 图钉（替换 📌） */
    val Pin: ImageVector by lazy {
        buildIcon("Pin", 24, 1.5f, -0.1f, 0.3f,
            p("M10.80 8.90a3.90 3.90 0 1 0 7.80 0a3.90 3.90 0 1 0 -7.80 0"), p("M11.9 11.8L5.6 18.3"), p("M10.0 12.1l2.8 2.8"),
        )
    }
    /** iconId 2 · 餐饮（替换 🍚） */
    val RiceBowl: ImageVector by lazy {
        buildIcon("RiceBowl", 24, 1.5f, 0f, -0.4f,
            p("M4.2 13.2h15.6"), p("M5.8 13.2c.4 3.4 2.6 5.4 6.2 5.4s5.8-2.0 6.2-5.4"), p("M8.6 13.1a3.4 3.4 0 0 1 6.8-.1"), p("M9.7 9.1c0-1.3 1.3-1.7 1.3-3.0"),
        )
    }
    /** iconId 3 · 餐饮 / 面食（替换 🍜） */
    val NoodleBowl: ImageVector by lazy {
        buildIcon("NoodleBowl", 24, 1.5f, 0f, 0.3f,
            p("M4.2 12.9h15.6"), p("M5.8 12.9c.4 3.6 2.6 5.7 6.2 5.7s5.8-2.1 6.2-5.7"), p("M8.2 9.9c1.2-1.1 2.3 1.1 3.5 0s2.4 1.1 3.6 0"), p("M13.9 4.8l4.6 4.9"),
        )
    }
    /** iconId 4 · 餐饮 / 饮品（替换 ☕） */
    val Coffee: ImageVector by lazy {
        buildIcon("Coffee", 24, 1.5f, -0.5f, 1.5f,
            p("M5.8 8.7h9.8v4.5a4.2 4.2 0 0 1-4.2 4.2h-1.4a4.2 4.2 0 0 1-4.2-4.2z"), p("M15.6 9.9h1.4a2.2 2.2 0 0 1 0 4.4h-1.4"), p("M9.3 6.0c0-1.0.9-1.3.9-2.3"),
        )
    }
    /** iconId 5 · 餐饮 / 酒水（替换 🍺） */
    val Beer: ImageVector by lazy {
        buildIcon("Beer", 24, 1.5f, 0.2f, 0.3f,
            p("M7.8 8.5h5.8a1.6 1.6 0 0 1 1.6 1.6v6.8a1.4 1.4 0 0 1 -1.4 1.4h-6.1a1.2 1.2 0 0 1 -1.2 -1.2v-7.3a1.3 1.3 0 0 1 1.3 -1.3z"), p("M15.2 10.7h1.9v4.6h-1.9"), p("M6.7 8.4a4.3 4.3 0 0 1 8.4 0"),
        )
    }
    /** iconId 6 · 餐饮 / 轻食（替换 🥗） */
    val Salad: ImageVector by lazy {
        buildIcon("Salad", 24, 1.5f, 0f, -0.3f,
            p("M4.2 12.9h15.6"), p("M5.8 12.9c.4 3.4 2.5 5.4 6.1 5.4s5.7-2.0 6.1-5.4"), p("M12.1 10.5c-.4-1.9 1.0-3.5 3.1-3.3-.1 2.2-1.3 3.3-3.1 3.3"), p("M11.6 10.5c.5-1.6.1-3.2-1.3-4.2"),
        )
    }
    /** iconId 7 · 餐饮 / 甜品（替换 🍩） */
    val Donut: ImageVector by lazy {
        buildIcon("Donut", 24, 1.5f, 0f, 0f,
            p("M4.50 12.00a7.50 7.50 0 1 0 15.00 0a7.50 7.50 0 1 0 -15.00 0"), p("M9.40 12.00a2.60 2.60 0 1 0 5.20 0a2.60 2.60 0 1 0 -5.20 0"), p("M8.6 7.9l1.4-.5M14.0 7.0l1.4.4M17.1 11.5l.6 1.3M11.6 17.2l1.4-.4M6.9 10.9l-.5 1.4"),
        )
    }
    /** iconId 8 · 交通 / 公交（替换 🚌） */
    val Bus: ImageVector by lazy {
        buildIcon("Bus", 24, 1.5f, 0f, -0.4f,
            p("M6.1 6.0h12.0a1.5 1.5 0 0 1 1.5 1.5v6.3a1.4 1.4 0 0 1 -1.4 1.4h-12.0a1.8 1.8 0 0 1 -1.8 -1.8v-5.7a1.7 1.7 0 0 1 1.7 -1.7z"), p("M5.7 10.4h12.6"), p("M6.70 17.40a1.40 1.40 0 1 0 2.80 0a1.40 1.40 0 1 0 -2.80 0"), p("M14.50 17.40a1.40 1.40 0 1 0 2.80 0a1.40 1.40 0 1 0 -2.80 0"),
        )
    }
    /** iconId 9 · 交通 / 自驾（替换 🚗） */
    val Car: ImageVector by lazy {
        buildIcon("Car", 24, 1.5f, 0f, -1.5f,
            p("M4.4 15.5v-2.6l2.1-3.9h11.0l2.1 3.9v2.6z"), p("M4.5 12.9h15.0"), p("M6.60 16.60a1.40 1.40 0 1 0 2.80 0a1.40 1.40 0 1 0 -2.80 0"), p("M14.60 16.60a1.40 1.40 0 1 0 2.80 0a1.40 1.40 0 1 0 -2.80 0"),
        )
    }
    /** iconId 10 · 交通 / 打车（替换 🚕） */
    val Taxi: ImageVector by lazy {
        buildIcon("Taxi", 24, 1.5f, 0f, -0.8f,
            p("M4.4 15.7v-2.6l2.1-3.9h11.0l2.1 3.9v2.6z"), p("M4.5 13.1h15.0"), p("M9.6 9.2V7.3h4.8v1.9"), p("M6.60 16.80a1.40 1.40 0 1 0 2.80 0a1.40 1.40 0 1 0 -2.80 0"), p("M14.60 16.80a1.40 1.40 0 1 0 2.80 0a1.40 1.40 0 1 0 -2.80 0"),
        )
    }
    /** iconId 11 · 交通 / 骑行（替换 🚲） */
    val Bicycle: ImageVector by lazy {
        buildIcon("Bicycle", 24, 1.5f, 0f, -1.7f,
            p("M3.20 16.10a3.30 3.30 0 1 0 6.60 0a3.30 3.30 0 1 0 -6.60 0"), p("M14.20 16.10a3.30 3.30 0 1 0 6.60 0a3.30 3.30 0 1 0 -6.60 0"), p("M6.5 16.1l3.3-6.3h5.5l2.2 6.3"), p("M9.8 9.8h4.5l.8-1.9"),
        )
    }
    /** iconId 12 · 交通 / 加油（替换 ⛽） */
    val Fuel: ImageVector by lazy {
        buildIcon("Fuel", 24, 1.5f, 0.8f, -0.4f,
            p("M5.7 19.3V6.7c0-.6.5-1.1 1.1-1.1h4.8c.6 0 1.1.5 1.1 1.1v12.6"), p("M4.5 19.3h9.4"), p("M8.2 8.5h2.3v3.2H8.2z"), p("M12.7 9.9h2.1v5.2a1.6 1.6 0 0 0 3.2 0V9.1"),
        )
    }
    /** iconId 13 · 交通 / 出行（替换 ✈️） */
    val Plane: ImageVector by lazy {
        buildIcon("Plane", 24, 1.5f, 0.1f, -0.6f,
            p("M4.0 11.7l15.8-6.9-6.5 15.7-2.6-6.8z"), p("M10.8 13.7l9.0-9.0"),
        )
    }
    /** iconId 14 · 旅行 / 住宿（替换 🏨） */
    val Hotel: ImageVector by lazy {
        buildIcon("Hotel", 24, 1.5f, 0f, -1.4f,
            p("M4.0 18.3v-5.1a2.4 2.4 0 0 1 2.4-2.4h11.2a2.4 2.4 0 0 1 2.4 2.4v5.1"), p("M4.1 15.7h15.8"), p("M6.9 10.8V8.5h3.4v2.3"),
        )
    }
    /** iconId 15 · 购物（替换 🛍️） */
    val Bag: ImageVector by lazy {
        buildIcon("Bag", 24, 1.5f, 0f, 0.4f,
            p("M5.6 8.8h12.8l-1.2 10.0H6.8z"), p("M9.2 8.8V7.2a2.8 2.8 0 0 1 5.6 0v1.6"),
        )
    }
    /** iconId 16 · 居住（替换 🏠） */
    val House: ImageVector by lazy {
        buildIcon("House", 24, 1.5f, 0f, 0.1f,
            p("M4.2 12.0L12.1 5.4l7.7 6.3"), p("M6.2 11.3v7.2h11.6v-7.2"), p("M10.3 18.4v-4.2h3.4v4.2"),
        )
    }
    /** iconId 17 · 装修（替换 🔨） */
    val Hammer: ImageVector by lazy {
        buildIcon("Hammer", 24, 1.5f, 0f, -0.5f,
            p("M7.6 5.7h8.6a1.3 1.3 0 0 1 1.3 1.3v1.8a1.2 1.2 0 0 1 -1.2 1.2h-8.8a1.0 1.0 0 0 1 -1.0 -1.0v-2.2a1.1 1.1 0 0 1 1.1 -1.1z"), p("M12 10.0v9.4"),
        )
    }
    /** iconId 18 · 装修 / 工具（替换 🛠️） */
    val Tools: ImageVector by lazy {
        buildIcon("Tools", 24, 1.5f, 0.2f, 0.3f,
            p("M15.9 4.7a3.5 3.5 0 0 0-4.2 4.2l-6.5 6.5a2.1 2.1 0 1 0 3.0 3.0l6.5-6.5a3.5 3.5 0 0 0 4.2-4.2l-2.4 2.4-3.0-3.0z"),
        )
    }
    /** iconId 19 · 水电 / 能耗（替换 💡） */
    val Bulb: ImageVector by lazy {
        buildIcon("Bulb", 24, 1.5f, 0.2f, -0.8f,
            p("M12 5.0a5.1 5.1 0 0 1 3.5 8.8c-.7.6-1.1 1.3-1.2 2.1H9.7c-.1-.8-.5-1.5-1.2-2.1A5.1 5.1 0 0 1 12 5.0z"), p("M9.9 18.5h4.2"), p("M10.6 20.5h2.8"),
        )
    }
    /** iconId 20 · 医疗 / 药品（替换 💊） */
    val Pill: ImageVector by lazy {
        buildIcon("Pill", 24, 1.5f, -0.1f, 0.1f,
            p("M10.2 19.5L19.7 10.0a3.9 3.9 0 0 0-5.6-5.6L4.6 13.9a3.9 3.9 0 0 0 5.6 5.6z"), p("M12.1 7.9l4.0 4.0"),
        )
    }
    /** iconId 21 · 医疗 / 就诊（替换 🏥） */
    val Hospital: ImageVector by lazy {
        buildIcon("Hospital", 24, 1.5f, 0f, -0.1f,
            p("M6.4 4.8h11.0a1.6 1.6 0 0 1 1.6 1.6v11.5a1.5 1.5 0 0 1 -1.5 1.5h-11.2a1.3 1.3 0 0 1 -1.3 -1.3v-11.9a1.4 1.4 0 0 1 1.4 -1.4z"), p("M12 8.7v5.0"), p("M9.5 11.2h5.0"),
        )
    }
    /** iconId 22 · 娱乐 / 游戏（替换 🎮） */
    val Gamepad: ImageVector by lazy {
        buildIcon("Gamepad", 24, 1.5f, 0f, -0.4f,
            p("M7.2 8.3h9.2a3.4 3.4 0 0 1 3.4 3.4v1.6a3.2 3.2 0 0 1 -3.2 3.2h-9.6a2.8 2.8 0 0 1 -2.8 -2.8v-2.4a3.0 3.0 0 0 1 3.0 -3.0z"), p("M8.1 10.8v3.2M6.5 12.4h3.2"), pf("M14.35 11.50a0.95 0.95 0 1 0 1.90 0a0.95 0.95 0 1 0 -1.90 0"), pf("M16.25 13.30a0.95 0.95 0 1 0 1.90 0a0.95 0.95 0 1 0 -1.90 0"),
        )
    }
    /** iconId 23 · 娱乐 / 影音（替换 🎬） */
    val Film: ImageVector by lazy {
        buildIcon("Film", 24, 1.5f, 0f, -0.6f,
            p("M5.9 10.0h12.2a1.4 1.4 0 0 1 1.4 1.4v6.5a1.5 1.5 0 0 1 -1.5 1.5h-12.2a1.3 1.3 0 0 1 -1.3 -1.3v-6.7a1.4 1.4 0 0 1 1.4 -1.4z"), p("M4.8 9.9L6.3 5.8l12.4 4.0"), p("M10.5 7.1l1.2 2.7"), p("M14.7 8.4l1.2 1.4"),
        )
    }
    /** iconId 24 · 运动（替换 ⚽） */
    val Ball: ImageVector by lazy {
        buildIcon("Ball", 24, 1.5f, 0f, 0f,
            p("M4.40 12.00a7.60 7.60 0 1 0 15.20 0a7.60 7.60 0 1 0 -15.20 0"), p("M12 9.0l2.8 2.0-1.1 3.3h-3.4l-1.1-3.3z"), p("M12 9.0V4.4"), p("M14.8 11.0l3.4-1.1"), p("M13.7 14.3l2.1 2.9"), p("M10.3 14.3l-2.1 2.9"), p("M9.2 11.0L5.8 9.9"),
        )
    }
    /** iconId 25 · 娱乐 / 乐器（替换 🎸） */
    val Guitar: ImageVector by lazy {
        buildIcon("Guitar", 24, 1.5f, 0f, 0.4f,
            p("M7.50 15.40a4.50 4.50 0 1 0 9.00 0a4.50 4.50 0 1 0 -9.00 0"), p("M9.10 9.40a2.90 2.90 0 1 0 5.80 0a2.90 2.90 0 1 0 -5.80 0"), p("M12 6.5V3.3"), p("M10.9 3.4h2.2"), p("M10.75 13.90a1.25 1.25 0 1 0 2.50 0a1.25 1.25 0 1 0 -2.50 0"),
        )
    }
    /** iconId 26 · 学习 / 书籍（替换 📚） */
    val Book: ImageVector by lazy {
        buildIcon("Book", 24, 1.5f, 0f, -0.2f,
            p("M12 6.8C10.3 5.4 7.8 4.9 4.6 5.2v12.4c3.2-.3 5.7.2 7.4 1.6"), p("M12 6.8c1.7-1.4 4.2-1.9 7.4-1.6v12.4c-3.2-.3-5.7.2-7.4 1.6"), p("M12 6.8v12.4"),
        )
    }
    /** iconId 27 · 学习 / 课程（替换 🎓） */
    val GradCap: ImageVector by lazy {
        buildIcon("GradCap", 24, 1.5f, -0.4f, 0.3f,
            p("M12 5.7l8.3 3.5-8.3 3.5-8.3-3.5z"), p("M7.6 11.4v3.9c0 1.3 2.0 2.4 4.4 2.4s4.4-1.1 4.4-2.4v-3.9"), p("M20.3 9.2v4.9"), pf("M19.40 15.10a0.90 0.90 0 1 0 1.80 0a0.90 0.90 0 1 0 -1.80 0"),
        )
    }
    /** iconId 28 · 学习 / 数码（替换 💻） */
    val Laptop: ImageVector by lazy {
        buildIcon("Laptop", 24, 1.5f, 0f, 0.7f,
            p("M6.9 5.3h10.0a1.5 1.5 0 0 1 1.5 1.5v5.9a1.2 1.2 0 0 1 -1.2 1.2h-10.2a1.4 1.4 0 0 1 -1.4 -1.4v-5.9a1.3 1.3 0 0 1 1.3 -1.3z"), p("M5.2 14.0h13.6l1.5 3.3H3.7z"),
        )
    }
    /** iconId 29 · 通讯 / 数码（替换 📱） */
    val Phone: ImageVector by lazy {
        buildIcon("Phone", 24, 1.5f, 0f, 0f,
            p("M8.9 3.9h6.0a1.8 1.8 0 0 1 1.8 1.8v12.7a1.7 1.7 0 0 1 -1.7 1.7h-6.2a1.5 1.5 0 0 1 -1.5 -1.5v-13.1a1.6 1.6 0 0 1 1.6 -1.6z"), p("M10.7 6.2h2.6"), pf("M11.10 17.50a0.90 0.90 0 1 0 1.80 0a0.90 0.90 0 1 0 -1.80 0"),
        )
    }
    /** iconId 30 · 宠物（替换 🐱） */
    val Pet: ImageVector by lazy {
        buildIcon("Pet", 24, 1.5f, 0f, 0.1f,
            p("M5.5 13.0c0-4.1 2.9-7.2 6.5-7.2s6.5 3.1 6.5 7.2c0 3.5-2.9 6.2-6.5 6.2s-6.5-2.7-6.5-6.2z"), p("M6.7 8.4L5.5 4.7l3.7 1.3"), p("M17.3 8.4l1.2-3.7-3.7 1.3"), pf("M8.65 12.10a0.85 0.85 0 1 0 1.70 0a0.85 0.85 0 1 0 -1.70 0"), pf("M13.65 12.10a0.85 0.85 0 1 0 1.70 0a0.85 0.85 0 1 0 -1.70 0"), p("M12 14.4v1.1"),
        )
    }
    /** iconId 31 · 礼物 / 人情（替换 🎁） */
    val Gift: ImageVector by lazy {
        buildIcon("Gift", 24, 1.5f, 0f, 0.2f,
            p("M6.0 10.0h11.8a1.3 1.3 0 0 1 1.3 1.3v7.1a1.2 1.2 0 0 1 -1.2 1.2h-12.0a1.0 1.0 0 0 1 -1.0 -1.0v-7.5a1.1 1.1 0 0 1 1.1 -1.1z"), p("M4.3 7.5h15.4v2.5H4.3z"), p("M12 7.5v12.1"), p("M12 7.4c-.6-2.5-2.1-3.8-3.5-3.3-1.2.4-1.1 2.2.4 3.3"), p("M12 7.4c.6-2.5 2.1-3.8 3.5-3.3 1.2.4 1.1 2.2-.4 3.3"),
        )
    }
    /** iconId 32 · 红包（替换 🧧） */
    val RedEnvelope: ImageVector by lazy {
        buildIcon("RedEnvelope", 24, 1.5f, 0f, 0f,
            p("M6.8 4.6h10.2a1.6 1.6 0 0 1 1.6 1.6v11.7a1.5 1.5 0 0 1 -1.5 1.5h-10.4a1.3 1.3 0 0 1 -1.3 -1.3v-12.1a1.4 1.4 0 0 1 1.4 -1.4z"), p("M5.6 4.9c2.0 3.5 4.0 5.3 6.4 5.3s4.4-1.8 6.4-5.3"), p("M9.60 14.50a2.40 2.40 0 1 0 4.80 0a2.40 2.40 0 1 0 -4.80 0"),
        )
    }
    /** iconId 33 · 育儿（替换 👶） */
    val Baby: ImageVector by lazy {
        buildIcon("Baby", 24, 1.5f, 0f, 0.3f,
            p("M5.10 12.40a6.90 6.90 0 1 0 13.80 0a6.90 6.90 0 1 0 -13.80 0"), p("M12 5.6c.2-1.2 1.2-1.8 2.2-1.5"), pf("M8.90 11.90a0.80 0.80 0 1 0 1.60 0a0.80 0.80 0 1 0 -1.60 0"), pf("M13.50 11.90a0.80 0.80 0 1 0 1.60 0a0.80 0.80 0 1 0 -1.60 0"), p("M10.2 14.9c.6.9 3.0.9 3.6 0"),
        )
    }
    /** iconId 34 · 美容（替换 💅） */
    val Nail: ImageVector by lazy {
        buildIcon("Nail", 24, 1.5f, 1.8f, -0.1f,
            p("M9.7 10.6h4.4a1.3 1.3 0 0 1 1.3 1.3v6.4a1.2 1.2 0 0 1 -1.2 1.2h-4.6a1.0 1.0 0 0 1 -1.0 -1.0v-6.8a1.1 1.1 0 0 1 1.1 -1.1z"), p("M11.1 6.7h1.7a0.8 0.8 0 0 1 0.8 0.8v2.3a0.8 0.8 0 0 1 -0.8 0.8h-1.7a0.7 0.7 0 0 1 -0.7 -0.7v-2.5a0.7 0.7 0 0 1 0.7 -0.7z"), p("M5.0 6.1c2.5-1.6 5.3-1.9 7.9-.9"),
        )
    }
    /** iconId 35 · 理发（替换 💇） */
    val Haircut: ImageVector by lazy {
        buildIcon("Haircut", 24, 1.5f, 0f, -0.9f,
            p("M6.3 5.5l10.3 11.0"), p("M17.7 5.5L7.4 16.5"), p("M4.20 18.50a1.90 1.90 0 1 0 3.80 0a1.90 1.90 0 1 0 -3.80 0"), p("M16.00 18.50a1.90 1.90 0 1 0 3.80 0a1.90 1.90 0 1 0 -3.80 0"), pf("M11.20 11.60a0.80 0.80 0 1 0 1.60 0a0.80 0.80 0 1 0 -1.60 0"),
        )
    }
    /** iconId 36 · 收入（替换 💰） */
    val MoneyBag: ImageVector by lazy {
        buildIcon("MoneyBag", 24, 1.5f, 0f, -0.8f,
            p("M9.3 8.1h5.4c2.6 1.7 4.0 4.2 4.0 6.9 0 3.1-2.9 5.2-6.7 5.2s-6.7-2.1-6.7-5.2c0-2.7 1.4-5.2 4.0-6.9z"), p("M9.5 8.0L8.0 5.5h8.0l-1.5 2.5"), p("M10.4 12.4l1.6 2.3 1.6-2.3"), p("M12 14.7v2.8"), p("M10.8 15.7h2.4"),
        )
    }
    /** iconId 37 · 理财 / 投资（替换 📈） */
    val Invest: ImageVector by lazy {
        buildIcon("Invest", 24, 1.5f, -0.1f, 0.1f,
            p("M4.6 4.4v14.2c0 .5.4.9.9.9h14.1"), p("M7.4 15.3l3.3-3.8 2.6 2.2 4.6-5.4"), p("M15.4 8.0l2.5.3-.3 2.5"),
        )
    }
    /** iconId 38 · 账单 / 票据（替换 🧾） */
    val Receipt: ImageVector by lazy {
        buildIcon("Receipt", 24, 1.5f, 0f, 0.6f,
            p("M6.4 4.4h11.2v14.1l-1.87-1.3-1.87 1.3-1.86-1.3-1.87 1.3-1.86-1.3-1.87 1.3z"), p("M9.2 8.7h5.6"), p("M9.2 11.9h5.6"),
        )
    }
    /** iconId 39 · 日用（替换 🧴） */
    val Lotion: ImageVector by lazy {
        buildIcon("Lotion", 24, 1.5f, 0f, -0.3f,
            p("M9.7 9.4h4.4a1.7 1.7 0 0 1 1.7 1.7v7.0a1.6 1.6 0 0 1 -1.6 1.6h-4.6a1.4 1.4 0 0 1 -1.4 -1.4v-7.4a1.5 1.5 0 0 1 1.5 -1.5z"), p("M10.6 9.4V6.9h4.3V4.9"), p("M10.1 14.3h3.8"),
        )
    }
    /** iconId 40 · 玩具（替换 🧸） */
    val Teddy: ImageVector by lazy {
        buildIcon("Teddy", 24, 1.5f, 0f, 2.2f,
            p("M6.60 10.20a5.40 5.40 0 1 0 10.80 0a5.40 5.40 0 1 0 -10.80 0"), p("M5.90 5.90a1.80 1.80 0 1 0 3.60 0a1.80 1.80 0 1 0 -3.60 0"), p("M14.50 5.90a1.80 1.80 0 1 0 3.60 0a1.80 1.80 0 1 0 -3.60 0"), pf("M9.40 9.40a0.80 0.80 0 1 0 1.60 0a0.80 0.80 0 1 0 -1.60 0"), pf("M13.00 9.40a0.80 0.80 0 1 0 1.60 0a0.80 0.80 0 1 0 -1.60 0"), pf("M11.10 11.70a0.90 0.90 0 1 0 1.80 0a0.90 0.90 0 1 0 -1.80 0"), p("M10.6 13.5c.5.7 2.3.7 2.8 0"),
        )
    }
    /** iconId 41 · 其他收入（替换 ✨） */
    val Sparkle: ImageVector by lazy {
        buildIcon("Sparkle", 24, 1.5f, -0.6f, -0.5f,
            p("M11.4 4.6q1.2 5.9 7.0 7.1-5.8 1.2-7.0 7.1-1.2-5.9-7.0-7.1 5.8-1.2 7.0-7.1z"), p("M18.3 15.4q.4 2.1 2.5 2.5-2.1.4-2.5 2.5-.4-2.1-2.5-2.5 2.1-.4 2.5-2.5z"),
        )
    }
    /** iconId 42 · 其他支出（替换 📦） */
    val Box: ImageVector by lazy {
        buildIcon("Box", 24, 1.5f, 0f, 0.1f,
            p("M4.6 8.2L12 4.6l7.4 3.6L12 11.8z"), p("M4.6 8.2v7.4l7.4 3.6 7.4-3.6V8.2"), p("M12 11.8v7.4"),
        )
    }
    /** iconId 43 · 分类默认（替换 🏷️） */
    val Tag: ImageVector by lazy {
        buildIcon("Tag", 24, 1.5f, 0f, 0.3f,
            p("M4.6 10.5V5.0c0-.4.3-.7.7-.7h5.5c.4 0 .7.1 1.0.4l7.2 7.2c.5.5.5 1.3 0 1.8l-5.0 5.0c-.5.5-1.3.5-1.8 0l-7.2-7.2a1.4 1.4 0 0 1-.4-1.0z"), p("M6.40 7.30a1.30 1.30 0 1 0 2.60 0a1.30 1.30 0 1 0 -2.60 0"),
        )
    }
    /** iconId 44 · 装修 / 主材（替换 🧱） */
    val Brick: ImageVector by lazy {
        buildIcon("Brick", 24, 1.5f, 0f, 1.3f,
            p("M5.4 6.4h13.0a1.2 1.2 0 0 1 1.2 1.2v2.0a1.1 1.1 0 0 1 -1.1 1.1h-13.2a0.9 0.9 0 0 1 -0.9 -0.9v-2.4a1.0 1.0 0 0 1 1.0 -1.0z"), p("M5.4 10.7h13.0a1.2 1.2 0 0 1 1.2 1.2v2.0a1.1 1.1 0 0 1 -1.1 1.1h-13.2a0.9 0.9 0 0 1 -0.9 -0.9v-2.4a1.0 1.0 0 0 1 1.0 -1.0z"), p("M9.8 6.5v4.1M14.8 6.5v4.1M7.3 10.8v4.1M12.1 10.8v4.1M16.9 10.8v4.1"),
        )
    }
    /** iconId 45 · 装修 / 人工（替换 👷） */
    val Helmet: ImageVector by lazy {
        buildIcon("Helmet", 24, 1.5f, 0f, 1.1f,
            p("M5.1 14.3a6.9 6.9 0 0 1 13.8 0"), p("M3.9 14.3h16.2"), p("M12 7.4v6.9"),
        )
    }
    /** iconId 46 · 装修 / 家具（替换 🛋️） */
    val Sofa: ImageVector by lazy {
        buildIcon("Sofa", 24, 1.5f, 0f, -0.4f,
            p("M18.6 10.3V7.5a1.9 1.9 0 0 0-1.9-1.9H7.3a1.9 1.9 0 0 0-1.9 1.9v2.8"), p("M2.9 15.5a1.9 1.9 0 0 0 1.9 1.9h14.4a1.9 1.9 0 0 0 1.9-1.9v-4.1a1.9 1.9 0 0 0-3.8 0v1.9H6.7v-1.9a1.9 1.9 0 0 0-3.8 0z"), p("M5.7 17.4v1.8M18.3 17.4v1.8"),
        )
    }
    /** iconId 47 · 装修 / 家电（替换 📺） */
    val Tv: ImageVector by lazy {
        buildIcon("Tv", 24, 1.5f, 0f, 1.4f,
            p("M5.5 6.5h12.8a1.8 1.8 0 0 1 1.8 1.8v8.4a1.6 1.6 0 0 1 -1.6 1.6h-13.2a1.4 1.4 0 0 1 -1.4 -1.4v-8.8a1.6 1.6 0 0 1 1.6 -1.6z"), p("M8.9 6.1L12 2.9l3.1 3.2"),
        )
    }
    /** iconId 48 · 装修 / 设计费（替换 📐） */
    val Ruler: ImageVector by lazy {
        buildIcon("Ruler", 24, 1.5f, 0f, 0.1f,
            p("M4.8 19.0h14.4V4.8z"), p("M8.4 15.4l1.4-1.4M11.2 12.6l1.4-1.4M14.0 9.8l1.4-1.4"),
        )
    }
    /** iconId 49 · 装修 / 报销（替换 💵） */
    val Banknote: ImageVector by lazy {
        buildIcon("Banknote", 24, 1.5f, 0f, 0f,
            p("M5.1 7.1h13.6a1.4 1.4 0 0 1 1.4 1.4v7.1a1.3 1.3 0 0 1 -1.3 1.3h-13.8a1.1 1.1 0 0 1 -1.1 -1.1v-7.5a1.2 1.2 0 0 1 1.2 -1.2z"), p("M9.40 12.00a2.60 2.60 0 1 0 5.20 0a2.60 2.60 0 1 0 -5.20 0"), pf("M6.00 12.00a0.70 0.70 0 1 0 1.40 0a0.70 0.70 0 1 0 -1.40 0"), pf("M16.60 12.00a0.70 0.70 0 1 0 1.40 0a0.70 0.70 0 1 0 -1.40 0"),
        )
    }
    /** iconId 50 · 装修 / 退款（替换 ↩️） */
    val Refund: ImageVector by lazy {
        buildIcon("Refund", 24, 1.5f, 0f, -0.2f,
            p("M9.0 5.7L4.5 10.2l4.5 4.5"), p("M4.5 10.2h9.7a5.3 5.3 0 0 1 5.3 5.3v3.2"),
        )
    }

    /** 全部 50 枚（iconId 升序），供图标选择网格枚举 */
    val allIcons: List<Pair<Int, ImageVector>> = listOf(
        1 to Pin,
        2 to RiceBowl,
        3 to NoodleBowl,
        4 to Coffee,
        5 to Beer,
        6 to Salad,
        7 to Donut,
        8 to Bus,
        9 to Car,
        10 to Taxi,
        11 to Bicycle,
        12 to Fuel,
        13 to Plane,
        14 to Hotel,
        15 to Bag,
        16 to House,
        17 to Hammer,
        18 to Tools,
        19 to Bulb,
        20 to Pill,
        21 to Hospital,
        22 to Gamepad,
        23 to Film,
        24 to Ball,
        25 to Guitar,
        26 to Book,
        27 to GradCap,
        28 to Laptop,
        29 to Phone,
        30 to Pet,
        31 to Gift,
        32 to RedEnvelope,
        33 to Baby,
        34 to Nail,
        35 to Haircut,
        36 to MoneyBag,
        37 to Invest,
        38 to Receipt,
        39 to Lotion,
        40 to Teddy,
        41 to Sparkle,
        42 to Box,
        43 to Tag,
        44 to Brick,
        45 to Helmet,
        46 to Sofa,
        47 to Tv,
        48 to Ruler,
        49 to Banknote,
        50 to Refund,
    )

    /**
     * iconId → ImageVector。**UI 层取分类图标的唯一入口**。
     * 越界或未知值兜底到 [Tag]（43）—— 与数据库迁移 MIGRATION_3_4 的 ELSE 同一兜底。
     */
    fun slCategoryIcon(iconId: Int): ImageVector = when (iconId) {
        1 -> Pin
        2 -> RiceBowl
        3 -> NoodleBowl
        4 -> Coffee
        5 -> Beer
        6 -> Salad
        7 -> Donut
        8 -> Bus
        9 -> Car
        10 -> Taxi
        11 -> Bicycle
        12 -> Fuel
        13 -> Plane
        14 -> Hotel
        15 -> Bag
        16 -> House
        17 -> Hammer
        18 -> Tools
        19 -> Bulb
        20 -> Pill
        21 -> Hospital
        22 -> Gamepad
        23 -> Film
        24 -> Ball
        25 -> Guitar
        26 -> Book
        27 -> GradCap
        28 -> Laptop
        29 -> Phone
        30 -> Pet
        31 -> Gift
        32 -> RedEnvelope
        33 -> Baby
        34 -> Nail
        35 -> Haircut
        36 -> MoneyBag
        37 -> Invest
        38 -> Receipt
        39 -> Lotion
        40 -> Teddy
        41 -> Sparkle
        42 -> Box
        43 -> Tag
        44 -> Brick
        45 -> Helmet
        46 -> Sofa
        47 -> Tv
        48 -> Ruler
        49 -> Banknote
        50 -> Refund
        else -> Tag
    }
}

/** 顶层委托：调用点可 `import …ui.icon.slCategoryIcon` 后短名调用 */
fun slCategoryIcon(iconId: Int): ImageVector = SlCategoryIcons.slCategoryIcon(iconId)

/** 图标集入口：SlIcons.Category.* / SlIcons.Nav.* / SlIcons.Ui.* / SlIcons.Illustration.* / SlIcons.Status.* */
object SlIcons {

    val Category: SlCategoryIcons get() = SlCategoryIcons

    /** B 组 · 底部导航 */
    object Nav {
        val Section: ImageVector by lazy {
            buildIcon("Section", 24, 1.5f, 0f, 0.1f,
                p("M7.6 4.6h10.2a1.6 1.6 0 0 1 1.6 1.6v5.3a1.5 1.5 0 0 1 -1.5 1.5h-10.4a1.3 1.3 0 0 1 -1.3 -1.3v-5.7a1.4 1.4 0 0 1 1.4 -1.4z"), p("M5.8 7.9h12.2a1.6 1.6 0 0 1 1.6 1.6v8.2a1.5 1.5 0 0 1 -1.5 1.5h-12.4a1.3 1.3 0 0 1 -1.3 -1.3v-8.6a1.4 1.4 0 0 1 1.4 -1.4z"), p("M7.5 10.8v5.6"),
            )
        }
        val Ledger: ImageVector by lazy {
            buildIcon("Ledger", 24, 1.5f, 0f, 0f,
                p("M6.1 4.5h11.6a1.7 1.7 0 0 1 1.7 1.7v11.7a1.6 1.6 0 0 1 -1.6 1.6h-11.8a1.4 1.4 0 0 1 -1.4 -1.4v-12.1a1.5 1.5 0 0 1 1.5 -1.5z"), p("M8.1 4.7v14.6"), p("M10.8 8.5h5.2M10.8 12.0h5.2M10.8 15.5h3.2"),
            )
        }
        val Stats: ImageVector by lazy {
            buildIcon("Stats", 24, 1.5f, 0f, -2.1f,
                p("M4.4 19.2h15.2"), p("M7.1 12.4h1.2a1.0 1.0 0 0 1 1.0 1.0v4.7a0.9 0.9 0 0 1 -0.9 0.9h-1.4a0.7 0.7 0 0 1 -0.7 -0.7v-5.1a0.8 0.8 0 0 1 0.8 -0.8z"), p("M11.3 8.9h1.2a1.0 1.0 0 0 1 1.0 1.0v8.2a0.9 0.9 0 0 1 -0.9 0.9h-1.4a0.7 0.7 0 0 1 -0.7 -0.7v-8.6a0.8 0.8 0 0 1 0.8 -0.8z"), p("M15.5 14.1h1.2a1.0 1.0 0 0 1 1.0 1.0v3.0a0.9 0.9 0 0 1 -0.9 0.9h-1.4a0.7 0.7 0 0 1 -0.7 -0.7v-3.4a0.8 0.8 0 0 1 0.8 -0.8z"),
            )
        }
        val Mine: ImageVector by lazy {
            buildIcon("Mine", 24, 1.5f, 0f, -0.1f,
                p("M8.40 8.50a3.60 3.60 0 1 0 7.20 0a3.60 3.60 0 1 0 -7.20 0"), p("M5.4 19.4c.6-3.6 3.2-5.6 6.6-5.6s6.0 2.0 6.6 5.6"),
            )
        }
    }

    /** C 组功能 + D 组行内 + E 组极小（使用场景见注释） */
    object Ui {
    /** 新增（新建分区 / 分类）（替换 Icons.Filled.Add） */
        val Add: ImageVector by lazy {
            buildIcon("Add", 24, 1.5f, 0f, 0f,
                p("M12 5.2v13.6M5.2 12h13.6"),
            )
        }
    /** 关闭（表单标题栏在左）（替换 Icons.Filled.Close） */
        val Close: ImageVector by lazy {
            buildIcon("Close", 24, 1.5f, 0.1f, 0.1f,
                p("M6.2 6.2l11.6 11.6M17.8 6.1L6.1 17.8"),
            )
        }
    /** 删除账目 / 分区 / 分类（替换 Icons.Filled.Delete） */
        val Delete: ImageVector by lazy {
            buildIcon("Delete", 24, 1.5f, 0f, -0.4f,
                p("M4.6 6.4h14.8"), p("M9.8 6.4V4.9h4.4v1.5"), p("M6.6 6.6v11.1c0 1.2.9 2.1 2.1 2.1h6.6c1.2 0 2.1-.9 2.1-2.1V6.6"), p("M10.2 10.3v6.1M13.8 10.3v6.1"),
            )
        }
    /** 确认 / 保存 / 已核对（替换 Icons.Filled.Check） */
        val Check: ImageVector by lazy {
            buildIcon("Check", 24, 1.5f, -0.1f, -0.3f,
                p("M5.0 12.6l4.6 4.6L19.2 7.4"),
            )
        }
    /** 编辑（替换 Icons.Filled.Edit） */
        val Edit: ImageVector by lazy {
            buildIcon("Edit", 24, 1.5f, 0.3f, -0.3f,
                p("M4.9 19.1l.9-3.6L15.5 5.8a2.0 2.0 0 0 1 2.8 2.8L8.6 18.2l-3.7.9z"), p("M13.9 7.4l2.8 2.8"),
            )
        }
    /** 返回 / 上一月（替换 Icons.AutoMirrored.KeyboardArrowLeft） */
        val ArrowLeft: ImageVector by lazy {
            buildIcon("ArrowLeft", 24, 1.5f, 0.7f, 0f,
                p("M14.6 5.4L8.0 12.0l6.6 6.6"),
            )
        }
    /** 进入 / 下一月（替换 Icons.AutoMirrored.KeyboardArrowRight） */
        val ArrowRight: ImageVector by lazy {
            buildIcon("ArrowRight", 24, 1.5f, -0.7f, 0f,
                p("M9.4 5.4L16.0 12.0l-6.6 6.6"),
            )
        }
    /** 展开 / 上移排序（替换 Icons.Filled.KeyboardArrowUp） */
        val ArrowUp: ImageVector by lazy {
            buildIcon("ArrowUp", 24, 1.5f, 0f, 0.7f,
                p("M5.4 14.6L12.0 8.0l6.6 6.6"),
            )
        }
    /** 收起 / 下移排序（替换 Icons.Filled.KeyboardArrowDown） */
        val ArrowDown: ImageVector by lazy {
            buildIcon("ArrowDown", 24, 1.5f, 0f, -0.7f,
                p("M5.4 9.4l6.6 6.6 6.6-6.6"),
            )
        }
    /** 日期字段 label 前缀（~15dp）· 省去日期点阵（替换 📅） */
        val CalendarInline: ImageVector by lazy {
            buildIcon("CalendarInline", 24, 1.7f, 0f, 0.4f,
                p("M6.1 5.8h11.6a1.7 1.7 0 0 1 1.7 1.7v10.1a1.6 1.6 0 0 1 -1.6 1.6h-11.8a1.4 1.4 0 0 1 -1.4 -1.4v-10.5a1.5 1.5 0 0 1 1.5 -1.5z"), p("M8.4 3.9v3.3M15.6 3.9v3.3"),
            )
        }
    /** 时间字段 label 前缀（~15dp）（替换 🕐） */
        val ClockInline: ImageVector by lazy {
            buildIcon("ClockInline", 24, 1.7f, 0f, 0f,
                p("M4.40 12.00a7.60 7.60 0 1 0 15.20 0a7.60 7.60 0 1 0 -15.20 0"), p("M12 7.7V12l2.9 1.8"),
            )
        }
    /** 账目行「有备注」标记（~12dp）· 气泡内不画线（替换 💬） */
        val NoteInline: ImageVector by lazy {
            buildIcon("NoteInline", 24, 1.7f, 0f, -0.3f,
                p("M6.9 5.3h10.0a2.7 2.7 0 0 1 2.7 2.7v6.0a2.3 2.3 0 0 1 -2.3 2.3h-10.8a2.1 2.1 0 0 1 -2.1 -2.1v-6.4a2.5 2.5 0 0 1 2.5 -2.5z"), p("M8.9 16.2L7.5 19.3l3.2-1.7"),
            )
        }
    /** 账目行「有图 N 张」标记（~12dp）（替换 📷） */
        val CameraInline: ImageVector by lazy {
            buildIcon("CameraInline", 24, 1.7f, 0f, 0.1f,
                p("M5.8 7.5h12.2a1.8 1.8 0 0 1 1.8 1.8v7.5a1.6 1.6 0 0 1 -1.6 1.6h-12.6a1.4 1.4 0 0 1 -1.4 -1.4v-7.9a1.6 1.6 0 0 1 1.6 -1.6z"), p("M9.1 7.4l1.0-2.1h3.8l1.0 2.1"), p("M9.20 12.90a2.80 2.80 0 1 0 5.60 0a2.80 2.80 0 1 0 -5.60 0"),
            )
        }
    /** 选项已生效标记（11dp，白色）（替换 ✓） */
        val CheckXs: ImageVector by lazy {
            buildIcon("CheckXs", 24, 2.0f, -0.1f, -0.2f,
                p("M5.8 12.9l4.1 4.1L18.4 7.4"),
            )
        }
    }

    /** F 组 · 空态插图 */
    object Illustration {
        val EmptyLg: ImageVector by lazy {
            buildIcon("EmptyLg", 32, 1.6f, 0f, -0.4f,
                p("M16 10.3c-2.4-2.0-5.9-2.6-9.9-2.2v14.6c4.0-.4 7.5.2 9.9 2.2"), p("M16 10.3c2.4-2.0 5.9-2.6 9.9-2.2v14.6c-4.0-.4-7.5.2-9.9 2.2"), p("M16 10.3v14.6"), p("M20.0 8.7v5.0l1.9-1.5 1.9 1.5V9.3"),
            )
        }
        val HintLg: ImageVector by lazy {
            buildIcon("HintLg", 32, 1.6f, 2.5f, 1.0f,
                p("M4.2 13.4h8.0"), p("M12.2 13.4v-1.8c0-1.0.8-1.8 1.8-1.8h5.4a2.2 2.2 0 0 1 2.2 2.2v2.6a5.6 5.6 0 0 1-5.6 5.6h-.6a5.6 5.6 0 0 1-4.8-2.7l-1.4-2.3"), p("M14.7 10.0v1.7M17.3 10.0v1.7"),
            )
        }
    }

    /** G 组 · 状态符号（报销维度；核对维度复用 Ui.Check） */
    object Status {
        val Pending: ImageVector by lazy {
            buildIcon("Pending", 24, 1.8f, 0f, 0f,
                p("M5.70 12.00a6.30 6.30 0 1 0 12.60 0a6.30 6.30 0 1 0 -12.60 0"),
            )
        }
        val Cleared: ImageVector by lazy {
            buildIcon("Cleared", 24, 1.8f, 0f, 0f,
                pf("M5.70 12.00a6.30 6.30 0 1 0 12.60 0a6.30 6.30 0 1 0 -12.60 0"),
            )
        }
    }
}
