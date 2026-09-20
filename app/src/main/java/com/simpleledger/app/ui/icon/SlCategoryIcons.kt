package com.simpleledger.app.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 分类 / 分区图标 50 枚（**与数据库 `iconId` 1–50 严格一一对应**）。
 *
 * 由 docs/design/icons/_generate.py 生成，请勿手改。
 * 映射关系见 `IconMapping`；UI 侧用 `slCategoryIcon(iconId)` 取图并自带兜底。
 *
 * 单列成文件是因为 50 枚一次生成会让 SlIcons.kt 超过 800 行。
 */
object SlCategoryIcons {


    val Pin: ImageVector by lazy {
        buildIcon(
            "pin",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -0.05f,
            translationY = 0.2f,
            p("M11.0 8.9a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0 -7.6 0"),
            p("M12.1 11.6l-6.6 6.9"),
        )
    }

    val RiceBowl: ImageVector by lazy {
        buildIcon(
            "rice-bowl",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -0.1f,
            p("M4 13.3h16"),
            p("M5.7 13.3c.5 3.5 2.7 5.5 6.3 5.5s5.8-2 6.3-5.5"),
            p("M9.6 8.6c0-1.4 1.5-1.8 1.5-3.2"),
            p("M15.5 5.4l1.6 7.3"),
        )
    }

    val NoodleBowl: ImageVector by lazy {
        buildIcon(
            "noodle-bowl",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -1.81f,
            p("M4 12.6h16"),
            p("M5.7 12.6c.5 3.7 2.7 5.8 6.3 5.8s5.8-2.1 6.3-5.8"),
            p("M8.4 9.5c1.2-1 2.4 1 3.6 0s2.4 1 3.6 0"),
        )
    }

    val Coffee: ImageVector by lazy {
        buildIcon(
            "coffee",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -0.6f,
            translationY = 1.6f,
            p("M5.6 8.5h10.2v4.8a4 4 0 0 1-4 4h-2.2a4 4 0 0 1-4-4z"),
            p("M15.8 9.7h1.6a2.2 2.2 0 0 1 0 4.4h-1.6"),
            p("M9.2 5.7c0-1 .9-1.2.9-2.2"),
        )
    }

    val Beer: ImageVector by lazy {
        buildIcon(
            "beer",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = 0.23f,
            p("M6.6 8.6h8.8v9.8H6.6z"),
            p("M15.4 10.7h2v4.8h-2"),
            p("M6.7 8.6a4.4 4.4 0 0 1 8.6 0"),
        )
    }

    val Salad: ImageVector by lazy {
        buildIcon(
            "salad",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -0.25f,
            p("M4.2 12.9h15.6"),
            p("M5.9 12.9c.5 3.5 2.6 5.4 6.1 5.4s5.6-1.9 6.1-5.4"),
            p("M12 10.6c-.5-2 .9-3.7 3.2-3.5-.1 2.3-1.4 3.5-3.2 3.5"),
            p("M11.5 10.6c.6-1.7.2-3.4-1.4-4.4"),
        )
    }

    val Donut: ImageVector by lazy {
        buildIcon(
            "donut",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M4.4 12.0a7.6 7.6 0 1 0 15.2 0a7.6 7.6 0 1 0 -15.2 0"),
            p("M9.3 12.0a2.7 2.7 0 1 0 5.4 0a2.7 2.7 0 1 0 -5.4 0"),
            p("M6.3 8.5c1.6-1 3.4-.6 4.6.5"),
        )
    }

    val Bus: ImageVector by lazy {
        buildIcon(
            "bus",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -0.0f,
            translationY = -0.3f,
            p("M6.1 5.9h12.0a1.6 1.6 0 0 1 1.6 1.6v5.9a1.9 1.9 0 0 1 -1.9 1.9h-11.8a1.7 1.7 0 0 1 -1.7 -1.7v-5.9a1.8 1.8 0 0 1 1.8 -1.8z"),
            p("M5.5 10.2h13"),
            p("M6.6 17.3a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0"),
            p("M14.6 17.3a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0"),
        )
    }

    val Car: ImageVector by lazy {
        buildIcon(
            "car",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -1.4f,
            p("M4.4 15.4v-2.6l2-3.9h11.2l2 3.9v2.6z"),
            p("M4.4 12.8h15.2"),
            p("M6.6 16.5a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0"),
            p("M14.6 16.5a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0"),
        )
    }

    val Taxi: ImageVector by lazy {
        buildIcon(
            "taxi",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -0.85f,
            p("M4.4 15.8v-2.6l2-3.9h11.2l2 3.9v2.6z"),
            p("M4.4 13.2h15.2"),
            p("M9.4 9.3V7.4h5.2v1.9"),
            p("M6.6 16.9a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0"),
            p("M14.6 16.9a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0"),
        )
    }

    val Bicycle: ImageVector by lazy {
        buildIcon(
            "bicycle",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -2.7f,
            p("M3.0 16.2a3.4 3.4 0 1 0 6.8 0a3.4 3.4 0 1 0 -6.8 0"),
            p("M14.2 16.2a3.4 3.4 0 1 0 6.8 0a3.4 3.4 0 1 0 -6.8 0"),
            p("M6.4 16.2l3.4-6.4h5.6l2.2 6.4"),
            p("M9.9 9.8h4.4"),
        )
    }

    val Fuel: ImageVector by lazy {
        buildIcon(
            "fuel",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.7f,
            translationY = -0.45f,
            p("M5.6 19.4V6.6c0-.6.5-1.1 1.1-1.1h5c.6 0 1.1.5 1.1 1.1v12.8"),
            p("M4.4 19.4h9.4"),
            p("M8.1 8.6h2.4v3.3H8.1z"),
            p("M12.8 9.7h2.2v5.5a1.6 1.6 0 0 0 3.2 0V9"),
        )
    }

    val Plane: ImageVector by lazy {
        buildIcon(
            "plane",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.1f,
            translationY = -0.6f,
            p("M3.9 11.6l16-7-6.6 16-2.6-6.9z"),
            p("M10.8 13.7l9.1-9.1"),
        )
    }

    val Hotel: ImageVector by lazy {
        buildIcon(
            "hotel",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -1.2f,
            p("M3.9 18.2v-5.4a2.4 2.4 0 0 1 2.4-2.4h11.4a2.4 2.4 0 0 1 2.4 2.4v5.4"),
            p("M3.9 15.6h16.2"),
            p("M6.7 10.4V8.2h3.6v2.2"),
        )
    }

    val Bag: ImageVector by lazy {
        buildIcon(
            "bag",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = 0.23f,
            p("M5.5 8.7h13l-1.3 10.2H6.8z"),
            p("M9.1 8.7V7.1a3 3 0 0 1 5.9 0v1.6"),
        )
    }

    val House: ImageVector by lazy {
        buildIcon(
            "house",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M4.1 11.9L12 5.3l7.9 6.6"),
            p("M6.2 11.3v7.3h11.6v-7.3"),
            p("M10.3 18.6v-4.3h3.4v4.3"),
        )
    }

    val Hammer: ImageVector by lazy {
        buildIcon(
            "hammer",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -0.15f,
            translationY = -0.25f,
            p("M14.1 5.6h5.4v4.6h-5.4z"),
            p("M14.1 8L5.8 16.5"),
            p("M5.8 16.5l-1 2.4"),
        )
    }

    val Tools: ImageVector by lazy {
        buildIcon(
            "tools",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -0.4f,
            translationY = -0.7f,
            p("M17.9 6.1a3.7 3.7 0 0 0-5.2 4.4l-7.3 7.3 1.9 1.9 7.3-7.3a3.7 3.7 0 0 0 4.4-5.2l-2.4 2.4-2-2z"),
        )
    }

    val Bulb: ImageVector by lazy {
        buildIcon(
            "bulb",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -0.2f,
            p("M12 3.9a5.6 5.6 0 0 1 3.3 10.1v2.2H8.7v-2.2A5.6 5.6 0 0 1 12 3.9z"),
            p("M9.4 18.4h5.2"),
            p("M10.4 20.5h3.2"),
        )
    }

    val Pill: ImageVector by lazy {
        buildIcon(
            "pill",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -2.3f,
            translationY = -2.4f,
            p("M9.1 15.5l6.3-6.3a2.9 2.9 0 0 1 4.1 4.1l-6.3 6.3a2.9 2.9 0 0 1-4.1-4.1z"),
            p("M11.4 13.2l4.1 4.1"),
        )
    }

    val Hospital: ImageVector by lazy {
        buildIcon(
            "hospital",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M4.4 19.4V10.2L12 4.6l7.6 5.6v9.2z"),
            p("M12 9.4v5.2M9.4 12h5.2"),
        )
    }

    val Gamepad: ImageVector by lazy {
        buildIcon(
            "gamepad",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -0.5f,
            p("M7.1 8.5h10.0a3.4 3.4 0 0 1 3.4 3.4v0.9a3.7 3.7 0 0 1 -3.7 3.7h-9.8a3.5 3.5 0 0 1 -3.5 -3.5v-0.9a3.6 3.6 0 0 1 3.6 -3.6z"),
            p("M8 11.5v3M6.5 13h3"),
            pf("M15.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0"),
            pf("M17.0 14.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0"),
        )
    }

    val Film: ImageVector by lazy {
        buildIcon(
            "film",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M5.8 5.6h12.4a1.6 1.6 0 0 1 1.6 1.6v9.6a1.6 1.6 0 0 1 -1.6 1.6h-12.4a1.6 1.6 0 0 1 -1.6 -1.6v-9.6a1.6 1.6 0 0 1 1.6 -1.6z"),
            p("M8.6 5.6v3.4M15.4 5.6v3.4M8.6 15v3.4M15.4 15v3.4"),
        )
    }

    val Ball: ImageVector by lazy {
        buildIcon(
            "ball",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M4.5 12.0a7.5 7.5 0 1 0 15.0 0a7.5 7.5 0 1 0 -15.0 0"),
            p("M6.1 8.6c3.5 2 8.3 2 11.8 0"),
            p("M6.1 15.4c3.5-2 8.3-2 11.8 0"),
        )
    }

    val Guitar: ImageVector by lazy {
        buildIcon(
            "guitar",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -0.4f,
            translationY = 0.1f,
            p("M5.0 14.4a4.6 4.6 0 1 0 9.2 0a4.6 4.6 0 1 0 -9.2 0"),
            p("M12.8 11.2l6-6"),
            p("M17.6 4.8l2.2 2.2"),
        )
    }

    val Book: ImageVector by lazy {
        buildIcon(
            "book",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M12 7.7c-1.6-1.4-4.2-2.1-7-2.1v10.6c2.8 0 5.4.7 7 2.1 1.6-1.4 4.2-2.1 7-2.1V5.6c-2.8 0-5.4.7-7 2.1z"),
            p("M12 7.7v10.6"),
        )
    }

    val GradCap: ImageVector by lazy {
        buildIcon(
            "grad-cap",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = 0.15f,
            p("M3.6 9.8L12 5.6l8.4 4.2L12 13.9z"),
            p("M6.8 11.5v4.2c0 1.4 2.3 2.4 5.2 2.4s5.2-1 5.2-2.4v-4.2"),
        )
    }

    val Laptop: ImageVector by lazy {
        buildIcon(
            "laptop",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -0.0f,
            translationY = -0.3f,
            p("M6.8 6.4h10.6a1.2 1.2 0 0 1 1.2 1.2v6.3a1.5 1.5 0 0 1 -1.5 1.5h-10.4a1.3 1.3 0 0 1 -1.3 -1.3v-6.3a1.4 1.4 0 0 1 1.4 -1.4z"),
            p("M3.6 18.2h16.8"),
        )
    }

    val Phone: ImageVector by lazy {
        buildIcon(
            "phone",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M9.5 3.7h5.2a1.8 1.8 0 0 1 1.8 1.8v12.7a2.1 2.1 0 0 1 -2.1 2.1h-5.0a1.9 1.9 0 0 1 -1.9 -1.9v-12.7a2.0 2.0 0 0 1 2.0 -2.0z"),
            p("M10.7 17.6h2.6"),
        )
    }

    val Pet: ImageVector by lazy {
        buildIcon(
            "pet",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = 0.9f,
            p("M6.2 10.5L5.1 5.4l4.2 1.9h5.4l4.2-1.9-1.1 5.1"),
            p("M6.2 10.5c-.2 3.8 2.4 6.3 5.8 6.3s6-2.5 5.8-6.3"),
            pf("M9.1 11.6a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0"),
            pf("M13.3 11.6a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0"),
        )
    }

    val Gift: ImageVector by lazy {
        buildIcon(
            "gift",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -0.23f,
            p("M5.5 8.6h13.2a1.4 1.4 0 0 1 1.4 1.4v7.5a1.7 1.7 0 0 1 -1.7 1.7h-13.0a1.5 1.5 0 0 1 -1.5 -1.5v-7.5a1.6 1.6 0 0 1 1.6 -1.6z"),
            p("M3.9 12.4h16.2"),
            p("M12 8.6v10.6"),
            p("M12 8.6c-1.7 0-3.4-.7-3.4-2.2S10.5 4.2 12 8.6z"),
            p("M12 8.6c1.7 0 3.4-.7 3.4-2.2S13.5 4.2 12 8.6z"),
        )
    }

    val RedEnvelope: ImageVector by lazy {
        buildIcon(
            "red-envelope",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M6.4 4.6h11.4a1.6 1.6 0 0 1 1.6 1.6v11.3a1.9 1.9 0 0 1 -1.9 1.9h-11.2a1.7 1.7 0 0 1 -1.7 -1.7v-11.3a1.8 1.8 0 0 1 1.8 -1.8z"),
            p("M4.6 9.4h14.8"),
            p("M9.6 13.6a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0"),
        )
    }

    val Baby: ImageVector by lazy {
        buildIcon(
            "baby",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -0.2f,
            p("M9.4 9.6h5.2v8.6a1.8 1.8 0 0 1-1.8 1.8h-1.6a1.8 1.8 0 0 1-1.8-1.8z"),
            p("M10.4 9.6V6.8h3.2v2.8"),
            p("M10.8 4.4h2.4l.4 2.4"),
            p("M9.6 13.6h4.8"),
        )
    }

    val Nail: ImageVector by lazy {
        buildIcon(
            "nail",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = 0.2f,
            p("M9.8 9.6h4.4v9.8H9.8z"),
            p("M10.7 9.6V6.6h2.6v3"),
            p("M11.1 4.2h1.8l.5 2.4"),
            p("M9.8 12.8h4.4"),
        )
    }

    val Haircut: ImageVector by lazy {
        buildIcon(
            "haircut",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.25f,
            translationY = 0.0f,
            p("M5.1 7.6a2.5 2.5 0 1 0 5.0 0a2.5 2.5 0 1 0 -5.0 0"),
            p("M5.1 16.4a2.5 2.5 0 1 0 5.0 0a2.5 2.5 0 1 0 -5.0 0"),
            p("M9.7 9.1l8.7 7.3"),
            p("M9.7 14.9l8.7-7.3"),
        )
    }

    val MoneyBag: ImageVector by lazy {
        buildIcon(
            "money-bag",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -1.0f,
            p("M9.6 8.4h4.8l1.4-2.6H8.2z"),
            p("M7.4 8.4c-2.4 2.4-3.4 4.6-3.4 7.2 0 3 2.6 4.6 8 4.6s8-1.6 8-4.6c0-2.6-1-4.8-3.4-7.2"),
        )
    }

    val Invest: ImageVector by lazy {
        buildIcon(
            "invest",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -1.7f,
            p("M4.2 18.6l5.2-5.4 3.4 3.2 6.8-7.6"),
            p("M15.4 8.8h4.4v4.4"),
        )
    }

    val Receipt: ImageVector by lazy {
        buildIcon(
            "receipt",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.2f,
            translationY = -0.05f,
            p("M6.4 4.6v14.9l1.8-1.3 1.8 1.3 1.8-1.3 1.8 1.3 1.8-1.3 1.8 1.3V4.6z"),
            p("M9 9.2h6M9 12.4h6"),
            p("M9 15.4h3.4"),
        )
    }

    val Lotion: ImageVector by lazy {
        buildIcon(
            "lotion",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -0.1f,
            translationY = 0.2f,
            p("M8.6 9.4h6.8v9.6H8.6z"),
            p("M10.6 9.4V7.2h2.8v2.2"),
            p("M12 7.2V4.6h3.6"),
            p("M15.6 4.6v1.9"),
        )
    }

    val Teddy: ImageVector by lazy {
        buildIcon(
            "teddy",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = 0.1f,
            p("M6.4 12.8a5.6 5.6 0 1 0 11.2 0a5.6 5.6 0 1 0 -11.2 0"),
            p("M5.3 7.6a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0"),
            p("M14.3 7.6a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0"),
            pf("M9.5 11.6a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0"),
            pf("M12.9 11.6a0.8 0.8 0 1 0 1.6 0a0.8 0.8 0 1 0 -1.6 0"),
            p("M10.6 14.8c.9.9 2.2.9 3.1 0"),
        )
    }

    val Sparkle: ImageVector by lazy {
        buildIcon(
            "sparkle",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -1.1f,
            translationY = 0.6f,
            p("M12 4.4l1.9 5.1 5.1 1.9-5.1 1.9L12 18.4l-1.9-5.1L5 11.4l5.1-1.9z"),
            p("M18.6 5.4l.7 1.9 1.9.7-1.9.7-.7 1.9-.7-1.9-1.9-.7 1.9-.7z"),
        )
    }

    val Box: ImageVector by lazy {
        buildIcon(
            "box",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M4.6 8.4l7.4-3.6 7.4 3.6v7.2L12 19.2l-7.4-3.6z"),
            p("M4.6 8.4L12 12l7.4-3.6"),
            p("M12 12v7.2"),
        )
    }

    val Tag: ImageVector by lazy {
        buildIcon(
            "tag",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.4f,
            translationY = 0.0f,
            p("M11.6 4.6H19v7.4l-7.4 7.4-7.4-7.4z"),
            p("M14.9 7.6a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0 -2.6 0"),
        )
    }

    val Brick: ImageVector by lazy {
        buildIcon(
            "brick",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -0.0f,
            translationY = -1.0f,
            p("M4.7 8.4h6.1a0.6 0.6 0 0 1 0.6 0.6v2.6a0.8 0.8 0 0 1 -0.8 0.8h-5.9a0.7 0.7 0 0 1 -0.7 -0.7v-2.6a0.7 0.7 0 0 1 0.7 -0.7z"),
            p("M13.3 8.4h5.9a0.8 0.8 0 0 1 0.8 0.8v2.6a0.6 0.6 0 0 1 -0.6 0.6h-6.1a0.7 0.7 0 0 1 -0.7 -0.7v-2.6a0.7 0.7 0 0 1 0.7 -0.7z"),
            p("M9.0 13.6h6.1a0.6 0.6 0 0 1 0.6 0.6v2.6a0.8 0.8 0 0 1 -0.8 0.8h-5.9a0.7 0.7 0 0 1 -0.7 -0.7v-2.6a0.7 0.7 0 0 1 0.7 -0.7z"),
        )
    }

    val Helmet: ImageVector by lazy {
        buildIcon(
            "helmet",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = 1.6f,
            p("M6.2 14.4a5.9 5.9 0 0 1 11.7 0"),
            p("M4.6 14.4h14.8"),
            p("M12 8.5v-2.1"),
            p("M9.3 9.4l-1-1.8M14.7 9.4l1-1.8"),
        )
    }

    val Sofa: ImageVector by lazy {
        buildIcon(
            "sofa",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.0f,
            translationY = -1.1f,
            p("M5.8 11.4h12.6a1.4 1.4 0 0 1 1.4 1.4v2.9a1.7 1.7 0 0 1 -1.7 1.7h-12.4a1.5 1.5 0 0 1 -1.5 -1.5v-2.9a1.6 1.6 0 0 1 1.6 -1.6z"),
            p("M6.6 11.4V9.4a2 2 0 0 1 2-2h6.8a2 2 0 0 1 2 2v2"),
            p("M8.6 17.4v1.4M15.4 17.4v1.4"),
        )
    }

    val Tv: ImageVector by lazy {
        buildIcon(
            "tv",
            size = 24,
            strokeWidth = 1.5f,
            translationX = -0.0f,
            translationY = -1.2f,
            p("M5.6 6.2h13.0a1.6 1.6 0 0 1 1.6 1.6v7.5a1.9 1.9 0 0 1 -1.9 1.9h-12.8a1.7 1.7 0 0 1 -1.7 -1.7v-7.5a1.8 1.8 0 0 1 1.8 -1.8z"),
            p("M9.6 20.2h4.8"),
            p("M12 17.2v3"),
        )
    }

    val Ruler: ImageVector by lazy {
        buildIcon(
            "ruler",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M4.6 19.4V4.6l14.8 14.8z"),
            p("M4.6 16.4h3.2M4.6 12.4h6.2M8.4 19.4v-3.2M12.4 19.4v-6.2"),
        )
    }

    val Banknote: ImageVector by lazy {
        buildIcon(
            "banknote",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0f,
            translationY = 0f,
            p("M4.8 7.4h14.5a1.3 1.3 0 0 1 1.3 1.3v6.4a1.5 1.5 0 0 1 -1.5 1.5h-14.3a1.4 1.4 0 0 1 -1.4 -1.4v-6.4a1.4 1.4 0 0 1 1.4 -1.4z"),
            p("M9.6 12.0a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0"),
            p("M6.4 10.2v3.6M17.6 10.2v3.6"),
        )
    }

    val Refund: ImageVector by lazy {
        buildIcon(
            "refund",
            size = 24,
            strokeWidth = 1.5f,
            translationX = 0.1f,
            translationY = -0.58f,
            p("M18.9 12.6a7 7 0 1 1-2.4-5.3"),
            p("M18.9 6.9v4.5h-4.5"),
        )
    }

}
