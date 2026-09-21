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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.simpleledger.app.ui.LocalReduceMotion

/* ============================================================
   设计令牌 —— 手账风格 2.0（取代「温和人文派」1.0）
   ============================================================
   与 docs/design/tokens-journal.json 一一对应，改动请同步设计文档。

   三个模型，理解它们就理解了这个主题：

   ① 纸与墨，而不是背景与文字
      页面是一张暖纸（#F7F3E9），卡片是叠在上面的一张更亮的纸片（#FDFBF6），
      输入框是纸面凹下去的一块（#EFE9DC）。文字是**墨**——深墨青（#17403A）
      而非纯黑，像钢笔墨水而不是屏幕像素。

   ② 层级靠「纸叠纸」，不靠阴影
      手账里没有卡片投影，只有一张纸压在另一张纸上。所以：
        · 全主题 elevation 归零（不用 Material 的阴影）
        · 需要强调时在纸片背后垫一层错位 3dp 的 #EAE3D2（见 design §2.2）
        · 深色主题同理——靠表面抬升，不靠投影

   ③ 颜色语义：分区独占
      同一行里颜色的**唯一含义是分区**（胶带色板 8 色）。
      因此分类图标一律单色、状态符号只用语义色，不得再引入第三套色彩语义。

   ⚠️ 与 1.0 的破坏性差异（换皮会一次性改变全 App 观感）：
      · #FAF9F7 暖白 → #F7F3E9 暖纸（更黄一档）
      · #1C1917 暖黑 → #17403A 墨青（10.35:1）
      · 支出 #B84742 → #A83E33 朱砂；收入 #3D7A55 → #3E6B4E 松烟
        （旧色换到新纸色后只剩 4.68 / 4.60，余量不足 0.2，必须换）
      · 圆角 20/14/999/28 → 3/1/6/4/8
   ============================================================ */

// ——— 纸（浅色）———
private val Paper = Color(0xFFF7F3E9)        // 页面底色（暖纸）
private val Slip = Color(0xFFFDFBF6)         // 纸片：卡片 / 浮层（比页面亮一层）
private val SlipUnder = Color(0xFFEAE3D2)    // 垫纸：纸片背后错位 3dp 的那层
private val Sunken = Color(0xFFEFE9DC)       // 凹面：输入框底 / chip 底
private val Rule = Color(0xFFE4DCC8)         // 1dp 分隔线 / 纸纹线
private val RuleStrong = Color(0xFFC9BFA8)   // 纸片描边

// ——— 墨（浅色）———
private val Ink = Color(0xFF17403A)          // 主墨 10.35:1（正文 / 标题 / 金额）
private val Ink2 = Color(0xFF55736B)         // 次要墨 4.67:1（元信息，**能用的最浅一档**）
private val Ink3 = Color(0xFF7A8F87)         // 装饰墨 3.10:1（仅 ≥18px，见硬规则 R1）

// ——— 语义色（浅色）———
private val ExpenseLight = Color(0xFFA83E33) // 印章朱砂 5.52:1
private val IncomeLight = Color(0xFF3E6B4E)  // 松烟墨绿 5.54:1
private val WarnLight = Color(0xFF92570A)    // 预警 / 超支 5.29:1
private val InfoLight = Color(0xFF2B6CB0)    // 信息 4.89:1

// ——— 纸与墨（深色）：同一套逻辑换向，纸变深、墨变浅 ———
private val DarkPaper = Color(0xFF1C1A16)
private val DarkSlip = Color(0xFF262320)
private val DarkSlipUnder = Color(0xFF131210)
private val DarkSunken = Color(0xFF2F2B26)
private val DarkRule = Color(0xFF3A352D)
private val DarkRuleStrong = Color(0xFF4A4438)
private val DarkInk = Color(0xFFF5F0E4)      // 米白墨 15.35:1
private val DarkInk2 = Color(0xFFC0B8A6)     // 8.83:1
private val DarkInk3 = Color(0xFF8E8778)

private val ExpenseDark = Color(0xFFE8A79C)
private val IncomeDark = Color(0xFF8FC4A4)
private val WarnDark = Color(0xFFE0A94A)
private val InfoDark = Color(0xFF8FB8E8)

/**
 * 和纸胶带色板（浅色）—— **分区身份色，同时充当按分区的图表配色**。
 *
 * 顺序即 `sections.colorIndex` 的取值 0–7，**不可调换顺序**（换序会让所有已存分区换色）。
 * 约束：色相均布、明度接近、红绿不相邻、在白纸底上全部 ≥3:1（图形元素阈值）。
 * ⚠️ 这些色**不作为承载文字的底色**——需要在色块上放字时用 [Ink] 作底。
 */
private val TapeLight = listOf(
    Color(0xFF0F766E), // 0 青绿 4.95:1
    Color(0xFF4E7396), // 1 灰蓝 4.49:1
    Color(0xFFA8792C), // 2 赭黄 3.48:1
    Color(0xFFA85C4A), // 3 陶土 3.62:1
    Color(0xFF7367A0), // 4 藤紫 4.21:1
    Color(0xFF5C7A4A), // 5 苔绿 4.36:1
    Color(0xFFA86B85), // 6 藕粉 3.72:1
    Color(0xFF7E7062), // 7 灰褐 3.88:1
)

/** 和纸胶带色板（深色）：同色相提亮，保证在深炭纸上可辨 */
private val TapeDark = listOf(
    Color(0xFF6EE7D8), Color(0xFF7FB3E8), Color(0xFFE0A94A), Color(0xFFEFA79F),
    Color(0xFFA99BE0), Color(0xFF8FCFAA), Color(0xFFE39BBE), Color(0xFFB0A392),
)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Sunken,
    onPrimaryContainer = Ink,
    secondary = Ink2,
    onSecondary = Paper,
    secondaryContainer = Sunken,
    onSecondaryContainer = Ink,
    tertiary = IncomeLight,
    onTertiary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Slip,
    onSurface = Ink,
    surfaceVariant = Sunken,
    onSurfaceVariant = Ink2,
    // 表面容器色阶**全部映射到纸片**：本设计系统只有三级——页面纸 / 纸片 / 凹面。
    // 未覆盖的角色会退回 Material 基线（带紫调），在暖纸底上会显灰发紫。
    surfaceDim = SlipUnder,
    surfaceBright = Slip,
    surfaceContainerLowest = Slip,
    surfaceContainerLow = Slip,
    surfaceContainer = Slip,
    surfaceContainerHigh = Slip,
    surfaceContainerHighest = Slip,
    surfaceTint = Ink,
    inverseSurface = DarkInk,
    inverseOnSurface = DarkPaper,
    scrim = Color(0xFF000000),
    outline = Rule,
    outlineVariant = RuleStrong,
    error = ExpenseLight,
    onError = Paper,
    errorContainer = Color(0xFFF7E4E3),
    onErrorContainer = Color(0xFF5C2320),
)

private val DarkColors = darkColorScheme(
    primary = DarkInk,
    onPrimary = DarkPaper,
    primaryContainer = DarkSunken,
    onPrimaryContainer = DarkInk,
    secondary = DarkInk2,
    onSecondary = DarkPaper,
    secondaryContainer = DarkSunken,
    onSecondaryContainer = DarkInk,
    tertiary = IncomeDark,
    onTertiary = DarkPaper,
    background = DarkPaper,
    onBackground = DarkInk,
    surface = DarkSlip,
    onSurface = DarkInk,
    surfaceVariant = DarkSunken,
    onSurfaceVariant = DarkInk2,
    // 深色同样是两级：纸 #1C1A16 / 纸片 #262320（层级靠表面抬升）
    surfaceDim = DarkSlipUnder,
    surfaceBright = DarkRuleStrong,
    surfaceContainerLowest = DarkSlip,
    surfaceContainerLow = DarkSlip,
    surfaceContainer = DarkSlip,
    surfaceContainerHigh = DarkSlip,
    surfaceContainerHighest = DarkSlip,
    surfaceTint = DarkInk,
    inverseSurface = DarkInk,
    inverseOnSurface = DarkPaper,
    scrim = Color(0xFF000000),
    outline = DarkRule,
    outlineVariant = DarkRuleStrong,
    error = ExpenseDark,
    onError = Color(0xFF3A1A17),
    errorContainer = Color(0xFF4A211E),
    onErrorContainer = ExpenseDark,
)

/**
 * 圆角体系：**大而收敛 → 纸张**。
 *
 * Material 组件与角色的对应关系（决定了下面这组数值）：
 *   extraSmall → OutlinedTextField（表单输入）
 *   small      → Chip（筛选 / 标签）
 *   medium     → Card（纸片）
 *   large      → FAB 等大容器
 *   extraLarge → AlertDialog
 *
 * ⚠️ Button 走的是 Material 的 `CornerFull`（胶囊），不受本 Shapes 控制，
 *    需在各调用点显式传 `shape = RoundedCornerShape(4.dp)`（见设计 §5）。
 */
val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),  // 输入框 4
    small = RoundedCornerShape(6.dp),       // chip 6
    medium = RoundedCornerShape(3.dp),      // 纸片 3（近直角）
    large = RoundedCornerShape(4.dp),       // 按钮 / 大容器
    extraLarge = RoundedCornerShape(8.dp),  // 对话框 8
)

/** 纸片圆角：卡片/列表容器统一取它，避免各处手写 3.dp */
val SlipShape = RoundedCornerShape(3.dp)

/** 进度条 / 色条 / 色块专用：1dp 微圆角（规范里的「条 1」） */
val BarShape = RoundedCornerShape(1.dp)

/**
 * 垫纸错位量：用「纸叠纸」表达层级时的固定偏移（规范 §1.4）。
 *
 * **纸片与 Snackbar 共用同一个数值** —— 层级语言的偏移量是全局约定，
 * 一旦两处各写 3.dp，某天调了其中一处就会让两种浮层的"厚度"不一致。
 */
val SlipStackOffset = 3.dp

/** 出账金额色（随明暗主题切换）—— 印章朱砂 */
@Composable
fun expenseColor(): Color = if (isSystemInDarkTheme()) ExpenseDark else ExpenseLight

/** 入账金额色 —— 松烟墨绿 */
@Composable
fun incomeColor(): Color = if (isSystemInDarkTheme()) IncomeDark else IncomeLight

/** 预算预警 / 超支色 */
@Composable
fun warnColor(): Color = if (isSystemInDarkTheme()) WarnDark else WarnLight

/** 信息提示色 */
@Composable
fun infoColor(): Color = if (isSystemInDarkTheme()) InfoDark else InfoLight

/**
 * 取分区胶带色。
 *
 * [index] 即 `sections.colorIndex`（0–7）。越界一律兜底到 0（青绿）——
 * 与数据库迁移的 `ELSE 0` 是同一个兜底，保证脏数据只会取到默认色而不是崩或透明。
 */
@Composable
fun tapeColor(index: Int): Color {
    val palette = if (isSystemInDarkTheme()) TapeDark else TapeLight
    return palette.getOrElse(index) { palette[0] }
}

/** 当前主题下完整的胶带色板（供选择器枚举；顺序即 colorIndex 0–7） */
@Composable
fun tapePalette(): List<Color> = if (isSystemInDarkTheme()) TapeDark else TapeLight

/**
 * 金额文本一律启用等宽数字（tabular figures）。
 * 比例数字会让 ¥1,111.00 比 ¥999.00 更宽，破坏流水列表的纵向对齐节奏。
 */
val TabularNums = TextStyle(fontFeatureSettings = "tnum")

/**
 * 图表配色 = **分区胶带色板**。
 *
 * v4 起不再维护独立的 chartPalette —— 分区色与图表色是同一套，
 * 从此不会出现「图表里的绿不是分区的绿」这种语义割裂。
 *
 * ⚠️ 取色**由调用方按「身份」决定**，不再有通用的 `shareColor(index)`：
 * 分区占比环图 / 分类金额条形图的颜色都表示「属于哪个分区」，直接用
 * `tapeColor(分区.colorIndex)`。旧版 `shareColor(index, isMerged)` 按序号
 * 轮转色板、还给合并桶固定墨灰，那是写给已退役的「分类占比环图」的
 * —— 颜色在它身上要同时表达「分类」这个身份，8 色轮转必然撞色（已实测）。
 * 拆成双图后，颜色语义回归单一的「分区身份」，取色也就回归一行 `tapeColor`。
 */
@Composable
fun chartPalette(): List<Color> = tapePalette()

@Composable
fun SimpleLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * 默认使用设计系统固定色。
     *
     * ⚠️ 开启后跟随壁纸取色，会**直接破坏手账的纸墨模型**（纸不再是暖纸、墨不再是墨青，
     * 对比度也不再是实测过的那组）。「我的 → 外观」仍保留这个开关（尊重用户选择），
     * 但选择它等于放弃本设计系统的视觉保证。
     */
    dynamicColor: Boolean = false,
    /** 是否「跟随系统减少动效」；由 MainActivity 读取系统设置后下发，默认 false 不影响现有行为 */
    reduceMotion: Boolean = false,
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
    // 动效偏好必须在 MaterialTheme 之外下发：Material3 的内置动画走的是 Compose 内部的
    // MotionDurationScale（已自动跟随系统 ANIMATOR_DURATION_SCALE），与本 local 是两条并行通道，
    // 本 local 只服务应用自研动效；放在外层保证主题内外都能读到同一个值。
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = LedgerShapes,
            content = content,
        )
    }
}
