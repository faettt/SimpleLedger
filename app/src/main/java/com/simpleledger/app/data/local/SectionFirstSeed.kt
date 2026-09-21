package com.simpleledger.app.data.local

import com.simpleledger.app.data.local.entity.EntryType

/**
 * 「分区优先」重构后的**理想初始态**（纯 Kotlin 数据结构，无 Android 依赖）。
 *
 * 首次安装的 `seed()` 与升级路径的 `MIGRATION_2_3` / `MIGRATION_3_4` **同源**：
 * 都用这份结构保证「新装」与「升级」两条路径的初始态一致（见设计文档 §4.2 / §4.3）。
 *
 * 结构要点：
 * - 3 个分区：日常开支（5000 元 / 青绿）/ 装修（**26 万元** / 赭黄）/ 旅行（未设预算 / 灰蓝）
 * - 12 个**全局**分类（`sectionName == null` ⇒ 全局），沿用既有分类
 * - 7 个**装修专属**分类（支出 5 + 收入 2，`sectionName == "装修"`）
 *
 * ⚠️ v4 起图标不再是 emoji 字符串，而是 `iconId`（Int，1–50，指向 `docs/design/icons`
 * 的 50 枚手绘图标）。旧数据 emoji 与新 iconId 的对应关系见 [IconMapping]。
 */
object SectionFirstSeed {

    /** 分区示例数据 */
    data class SeedSection(
        val name: String,
        /** 1–50，见 docs/design/icons/manifest.json */
        val iconId: Int,
        val note: String,
        val budgetCents: Long,
        /** 0–7，索引到和纸胶带色板；会沿用到账目行色条与按分区的图表配色 */
        val colorIndex: Int,
    )

    /** 分类示例数据；`sectionName == null` ⇒ 全局，非空 ⇒ 该分区专属 */
    data class SeedCategory(
        val name: String,
        /** 1–50，见 docs/design/icons/manifest.json */
        val iconId: Int,
        val type: Int,
        val sectionName: String?,
    )

    /** 装修分区名（迁移与种子共用，避免两处写死） */
    const val ZHUANGXIU_SECTION_NAME = "装修"

    /** 装修分区预算：26 万元（分） */
    const val ZHUANGXIU_BUDGET_CENTS = 26_000_000L

    /** 分区胶带色索引（与 tokens-journal.json 的 tape.palette 顺序一一对应） */
    object TapeColor {
        const val QING_LV = 0
        const val HUI_LAN = 1
        const val ZHE_HUANG = 2
        const val TAO_TU = 3
        const val TENG_ZI = 4
        const val TAI_LV = 5
        const val OU_FEN = 6
        const val HUI_HE = 7
    }

    val sections: List<SeedSection> = listOf(
        // 1 = pin · 青绿
        SeedSection("日常开支", 1, "日常生活开销", 500_000L, TapeColor.QING_LV),
        // 17 = hammer · 赭黄
        SeedSection(ZHUANGXIU_SECTION_NAME, 17, "主材与人工，控制在 26 万内", ZHUANGXIU_BUDGET_CENTS, TapeColor.ZHE_HUANG),
        // 13 = plane · 灰蓝
        SeedSection("旅行", 13, "出发前把大头订完", 0L, TapeColor.HUI_LAN),
    )

    /** 全局分类：沿用既有 12 个（支出 8 + 收入 4） */
    val globalCategories: List<SeedCategory> = listOf(
        SeedCategory("餐饮", 2, EntryType.EXPENSE, null),      // rice-bowl
        SeedCategory("交通", 8, EntryType.EXPENSE, null),      // bus
        SeedCategory("购物", 15, EntryType.EXPENSE, null),     // bag
        SeedCategory("居住", 16, EntryType.EXPENSE, null),     // house
        SeedCategory("医疗", 20, EntryType.EXPENSE, null),     // pill
        SeedCategory("娱乐", 22, EntryType.EXPENSE, null),     // gamepad
        SeedCategory("学习", 26, EntryType.EXPENSE, null),     // book
        SeedCategory("其他支出", 42, EntryType.EXPENSE, null),  // box
        SeedCategory("工资", 36, EntryType.INCOME, null),      // money-bag
        SeedCategory("理财", 37, EntryType.INCOME, null),      // invest
        SeedCategory("红包", 32, EntryType.INCOME, null),      // red-envelope
        SeedCategory("其他收入", 41, EntryType.INCOME, null),   // sparkle
    )

    /** 分区专属分类：装修专属（支出 5 + 收入 2） */
    val sectionCategories: List<SeedCategory> = listOf(
        SeedCategory("主材", 44, EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),   // brick
        SeedCategory("人工", 45, EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),   // helmet
        SeedCategory("家具", 46, EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),   // sofa
        SeedCategory("家电", 47, EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),   // tv
        SeedCategory("设计费", 48, EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME), // ruler
        SeedCategory("报销", 49, EntryType.INCOME, ZHUANGXIU_SECTION_NAME),    // banknote
        SeedCategory("退款", 50, EntryType.INCOME, ZHUANGXIU_SECTION_NAME),    // refund
    )

    /** 全部分类（全局 + 分区专属），便于单测整体断言 */
    val categories: List<SeedCategory> = globalCategories + sectionCategories
}
