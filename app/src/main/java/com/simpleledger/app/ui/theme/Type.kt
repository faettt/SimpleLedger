package com.simpleledger.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/* ============================================================
   排版 token —— 手账风格 typography v2
   与 docs/design/tokens-journal.json 的 "typography" 节一一对应。
   ============================================================
   字体体系 v2（2026-09-24 三决策定案，取代 2026-09-23 字族二元制）：

   【决策一·案 A】字重二档制 —— 楷体只有 Regular（400），粗档全是系统合成：
     ● Bold（合成粗）允许档：**≥15sp**（display / headline / titleL / title，
       及 body 档的状态强调）。22sp/40sp 实测饱满微糊不脏。
     ● Regular 独占档：**≤13.5sp**（bodySm / label / meta / navTab）。
       12.5sp 实测合成粗发糊，小字层级只靠字号 / 字距 / 墨色 / 下划线。
     ● 全工程只允许 FontWeight.Normal / Bold 两档，禁止 SemiBold 等野字重
       （400-only 字体上它们只是另一档合成粗，不可控）。

   【决策二·案 B】字族一元制 —— 全部文字（含英数）走楷体，见 Fonts.kt。
     缺字形走回退链（衬线 → 无衬线）。**R1 等宽数字规则作废**（楷体无 tnum，
     代价：长列表金额列数字宽窄不齐——知情取舍，氛围优先）。

   【决策三·案 A】状态区分 = 排版属性 + 状态墨色（见 [SlStatus]）。
     成功绿 / 警告琥珀 / 错误朱砂是「颜色只表达分区身份」规则的唯一豁免口。

   其余硬规则不变：
     ● 任何 Text 禁止裸写 fontSize / fontWeight / lineHeight / letterSpacing，
       一律取 [SlType] / [SlStatus] 的 token（唯一例外：navTab 10.5 布局锁定）。
     ● navTab / navTabOn 10.5sp 布局锁定（54dp 签身的行盒计算绑定，勿归级）。
   ============================================================ */

/**
 * 简账排版 token。**所有界面文字从这里取样式**。
 *
 * 字族由 [kai] 基座统一携带（楷体 + 中英文回退链）；
 * 状态文字叠加 [SlStatus]（`.merge(SlStatus.success)` 等）。
 */
object SlType {

    // ============ 标题档（Bold 合成粗允许档 ≥15sp）============

    /** display 40/44/700 · 金额输入、主金额展示 */
    val display = kai.copy(
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Bold,
    )

    /** headline 28/34/700 · 页面主标题 */
    val headline = kai.copy(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
    )

    /** titleL 22/28/700 · 区块标题、结余数字 */
    val titleL = kai.copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
    )

    /** title 16/22/700 · 卡片标题、列表主信息、分区名、页码 */
    val title = kai.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
    )

    // ============ 正文档（Regular 独占）============

    /** body 15/24/400 · 正文、备注、空态（状态强调可升 Bold——15sp 在允许档边缘） */
    val body = kai.copy(
        fontSize = 15.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    )

    /** bodySm 13.5/20/400 · 密集列表副文本、说明段（<15sp：禁合成粗） */
    val bodySm = kai.copy(
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    )

    // ============ 辅助档（Regular 独占 + 字距分级）============

    /** label 12.5/18/400 + 0.02em · 表单标签、按钮文字、chips */
    val label = kai.copy(
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.02.em,
    )

    /** meta 11.5/16/400 · 时间、分区、笔数等元信息、账目行小日期 */
    val meta = kai.copy(
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    )

    // ============ 布局锁定例外 ============

    /**
     * 索引贴标签（10.5/14）。⚠️ 勿归级——54dp 签身的行盒计算绑定这个字号。
     */
    val navTab = kai.copy(
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Normal,
    )

    /**
     * 选中签标签：索引贴选中态信号④（spec §2.1）。
     * v2 起由「半粗」改为「+0.04em 字距」——10.5sp 在禁粗档，字距是合规替代。
     */
    val navTabOn = navTab.copy(letterSpacing = 0.04.em)
}

/**
 * 状态 / 功能文字样式 —— 决策三·案 A：**排版属性 + 状态墨色**。
 *
 * 用法：`style = SlType.body.merge(SlStatus.success), color = incomeColor()`
 * 两档制（决策一联动）：
 *   ● 无 Sm 后缀（≥15sp 档）：Bold 合成粗 + 字距，配 body/title 等大档 token；
 *   ● Sm 后缀（≤13.5sp 档）：Regular + 同款字距，配 bodySm/label/meta。
 * 墨色（状态墨色豁免口，明暗分支走 LocalAppIsDark）：
 *   成功 = [incomeColor] · 警告 = [warnColor] · 错误 = [expenseColor]
 *   禁用 = onSurface × [DisabledAlpha] · 可交互 = onSurface + 下划线
 */
object SlStatus {

    // ---- 成功：Bold + 0.04em + 收入墨 ----
    val success = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = 0.04.em)
    val successSm = TextStyle(letterSpacing = 0.04.em)

    // ---- 警告：Bold + 0.02em + 预警墨 ----
    val warning = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = 0.02.em)
    val warningSm = TextStyle(letterSpacing = 0.02.em)

    // ---- 错误：Bold + 0.06em + 支出墨 ----
    val error = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = 0.06.em)
    val errorSm = TextStyle(letterSpacing = 0.06.em)

    // ---- 可交互：Bold + 下划线（小档去 Bold 留下划线）----
    val interactive = TextStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
    val interactiveSm = TextStyle(textDecoration = TextDecoration.Underline)

    // ---- 选中 / 强调：Bold + 0.04em（小档去 Bold 留字距）----
    // 图例选中、超支以外的「当前生效项」用它；与 navTabOn 信号④同一语言
    val selected = TextStyle(fontWeight = FontWeight.Bold, letterSpacing = 0.04.em)
    val selectedSm = TextStyle(letterSpacing = 0.04.em)

    // ---- 禁用：字形不变，墨色降 45%（两档通用，调用点给 alpha）----
    const val DisabledAlpha = 0.45f
}

/**
 * M3 Typography 兜底映射 —— 给 Material 组件**内部**文字（AlertDialog 标题、
 * TextField 输入文字、SegmentedButton 标签等）统一收口到同一套层级。
 *
 * 显式调用点一律用 [SlType]；这里只是保证漏网的 M3 内部文字也同声同气。
 */
val SlTypography = Typography(
    displayLarge = SlType.display,
    displayMedium = SlType.display.copy(fontSize = 34.sp, lineHeight = 40.sp),
    displaySmall = SlType.headline,
    headlineLarge = SlType.headline,
    headlineMedium = SlType.headline.copy(fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = SlType.titleL,
    titleLarge = SlType.titleL,
    titleMedium = SlType.title,
    titleSmall = SlType.bodySm,
    bodyLarge = SlType.body,
    bodyMedium = SlType.bodySm,
    bodySmall = SlType.meta,
    labelLarge = SlType.label,
    labelMedium = SlType.label.copy(fontSize = 11.5.sp, lineHeight = 16.sp, letterSpacing = 0.04.em),
    labelSmall = SlType.meta.copy(letterSpacing = 0.02.em),
)
