package com.simpleledger.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/* ============================================================
   设计令牌 —— 温和人文派
   与 docs/design/tokens.json 一一对应，改动请同步设计文档
   ============================================================ */

// 品牌青绿
private val Brand50 = Color(0xFFE4F5F1)   // 选中态底色
private val Brand100 = Color(0xFFC9EBE4)  // chip 选中 / 徽标
private val Brand500 = Color(0xFF12968A)  // 按压反馈 / 图表主色
private val Brand600 = Color(0xFF0F766E)  // 主品牌色
private val Brand900 = Color(0xFF073B37)  // 深色主题容器

// 暖中性（R>G>B 微偏暖，避免纯灰带来的「财务软件感」）
private val WarmBg = Color(0xFFFAF9F7)
private val WarmSurface = Color(0xFFFFFFFF)
private val WarmSurface2 = Color(0xFFF4F1ED)
private val WarmSurface3 = Color(0xFFEDE9E2)
private val WarmLine = Color(0xFFE8E4DE)
private val WarmLineStrong = Color(0xFFD6D0C8)
private val Ink = Color(0xFF1C1917)
private val Ink2 = Color(0xFF57534E)
private val Ink3 = Color(0xFF8A837C)

// 语义色（浅色）—— 支出用暖红而非警示红，降低记账的焦虑感
private val ExpenseLight = Color(0xFFB84742)
private val IncomeLight = Color(0xFF3D7A55)
private val WarnLight = Color(0xFFA16207)
private val InfoLight = Color(0xFF2B6CB0)

// 语义色（深色，明度提高以维持识别度）
private val ExpenseDark = Color(0xFFEFA79F)
private val IncomeDark = Color(0xFF8FCFAA)
private val WarnDark = Color(0xFFE0A94A)
private val InfoDark = Color(0xFF8FB8E8)

// 深色主题表面（暖黑，层级靠表面抬升而非阴影）
private val DarkBg = Color(0xFF1A1917)
private val DarkSurface = Color(0xFF232220)
private val DarkSurface2 = Color(0xFF2C2A26)
private val DarkLine = Color(0xFF3A3731)
private val DarkInk = Color(0xFFF5F2EE)
private val DarkInk2 = Color(0xFFB8B1A9)
private val BrandDarkOn = Color(0xFF6EE7D8)
private val BrandSoftDark = Color(0xFF123B37)

private val LightColors = lightColorScheme(
    primary = Brand600,
    onPrimary = Color.White,
    primaryContainer = Brand50,
    onPrimaryContainer = Brand900,
    secondary = Brand500,
    onSecondary = Color.White,
    secondaryContainer = Brand100,
    onSecondaryContainer = Brand900,
    tertiary = IncomeLight,
    onTertiary = Color.White,
    background = WarmBg,
    onBackground = Ink,
    surface = WarmSurface,
    onSurface = Ink,
    surfaceVariant = WarmSurface2,
    onSurfaceVariant = Ink2,
    // 表面容器色阶全部映射到同一级「卡片白」：
    // 本设计系统只有三级——页面暖白（background）/ 卡片纯白（surface）/ 次级填充（surfaceVariant）。
    // 未覆盖的角色会退回 Material 基线（带紫调），在暖底色上会显得发灰发紫。
    surfaceDim = WarmSurface3,
    surfaceBright = WarmSurface,
    surfaceContainerLowest = WarmSurface,
    surfaceContainerLow = WarmSurface,
    surfaceContainer = WarmSurface,
    surfaceContainerHigh = WarmSurface,
    surfaceContainerHighest = WarmSurface,
    surfaceTint = Brand600,
    inverseSurface = Color(0xFF2E2B28),
    inverseOnSurface = DarkInk,
    scrim = Color(0xFF000000),
    outline = WarmLine,
    outlineVariant = WarmLineStrong,
    error = ExpenseLight,
    onError = Color.White,
    errorContainer = Color(0xFFF7E4E3),
    onErrorContainer = Color(0xFF5C2320),
)

private val DarkColors = darkColorScheme(
    primary = BrandDarkOn,
    onPrimary = Color(0xFF12211F),
    primaryContainer = BrandSoftDark,
    onPrimaryContainer = BrandDarkOn,
    secondary = Brand500,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1E4A46),
    onSecondaryContainer = BrandDarkOn,
    tertiary = IncomeDark,
    onTertiary = Color(0xFF12211F),
    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = DarkInk2,
    // 深色同样是两级：底色 #1A1917 / 卡片 #232220（层级靠表面抬升表达）
    surfaceDim = Color(0xFF131210),
    surfaceBright = Color(0xFF3A3733),
    surfaceContainerLowest = DarkSurface,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurface,
    surfaceContainerHighest = DarkSurface,
    surfaceTint = BrandDarkOn,
    inverseSurface = DarkInk,
    inverseOnSurface = Color(0xFF2E2B28),
    scrim = Color(0xFF000000),
    outline = DarkLine,
    outlineVariant = Color(0xFF48443C),
    error = ExpenseDark,
    onError = Color(0xFF3A1A17),
    errorContainer = Color(0xFF4A211E),
    onErrorContainer = ExpenseDark,
)

/** 圆角只用四档：20 卡片 / 14 按钮与输入 / 999 chip / 28 对话框 */
val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 支出语义色（随明暗主题切换） */
@Composable
fun expenseColor(): Color = if (isSystemInDarkTheme()) ExpenseDark else ExpenseLight

/** 收入语义色 */
@Composable
fun incomeColor(): Color = if (isSystemInDarkTheme()) IncomeDark else IncomeLight

/** 预算预警色 */
@Composable
fun warnColor(): Color = if (isSystemInDarkTheme()) WarnDark else WarnLight

/** 信息色 */
@Composable
fun infoColor(): Color = if (isSystemInDarkTheme()) InfoDark else InfoLight

/**
 * 金额文本一律启用等宽数字（tabular figures）。
 * 比例数字会让 ¥1,111.00 比 ¥999.00 更宽，破坏流水列表的纵向对齐节奏。
 */
val TabularNums = TextStyle(fontFeatureSettings = "tnum")

/** 图表色板：色相均布、明度接近，红绿不相邻 */
private val ChartPaletteLight = listOf(
    Color(0xFF0F766E), Color(0xFF2B6CB0), Color(0xFFA16207), Color(0xFFB84742),
    Color(0xFF6D5BB8), Color(0xFF3D7A55), Color(0xFFB0577F), Color(0xFF5C6B7A),
)

private val ChartPaletteDark = listOf(
    Color(0xFF6EE7D8), Color(0xFF7FB3E8), Color(0xFFE0A94A), Color(0xFFEFA79F),
    Color(0xFFA99BE0), Color(0xFF8FCFAA), Color(0xFFE39BBE), Color(0xFF9DAAB8),
)

/** 按明暗主题取图表色板 */
@Composable
fun chartPalette(): List<Color> =
    if (isSystemInDarkTheme()) ChartPaletteDark else ChartPaletteLight

@Composable
fun SimpleLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 默认使用设计系统固定色；「我的 → 外观」开启动态取色时才跟随壁纸 */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = LedgerShapes,
        content = content,
    )
}
