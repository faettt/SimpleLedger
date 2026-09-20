package com.simpleledger.app.data.local

import com.simpleledger.app.data.local.entity.EntryType

/**
 * 「分区优先」重构后的**理想初始态**（纯 Kotlin 数据结构，无 Android 依赖）。
 *
 * 首次安装的 `seed()` 与升级路径的 `MIGRATION_2_3` **同源**：都用这份结构保证「新装」与
 * 「升级」两条路径的初始态一致（见设计文档 §4.2 / §4.3）。
 *
 * 结构要点：
 * - 3 个分区：日常开支（5000 元）/ 装修（**26 万元**）/ 旅行（未设预算）
 * - 12 个**全局**分类（`sectionName == null` ⇒ 全局），沿用既有分类
 * - 7 个**装修专属**分类（支出 5 + 收入 2，`sectionName == "装修"`）
 */
object SectionFirstSeed {

    /** 分区示例数据 */
    data class SeedSection(
        val name: String,
        val emoji: String,
        val note: String,
        val budgetCents: Long,
    )

    /** 分类示例数据；`sectionName == null` ⇒ 全局，非空 ⇒ 该分区专属 */
    data class SeedCategory(
        val name: String,
        val emoji: String,
        val type: Int,
        val sectionName: String?,
    )

    /** 装修分区名（迁移与种子共用，避免两处写死） */
    const val ZHUANGXIU_SECTION_NAME = "装修"

    /** 装修分区预算：26 万元（分） */
    const val ZHUANGXIU_BUDGET_CENTS = 26_000_000L

    val sections: List<SeedSection> = listOf(
        SeedSection("日常开支", "📌", "日常生活开销", 500_000L),
        SeedSection(ZHUANGXIU_SECTION_NAME, "🔨", "主材与人工，控制在 26 万内", ZHUANGXIU_BUDGET_CENTS),
        SeedSection("旅行", "✈️", "出发前把大头订完", 0L),
    )

    /** 全局分类：沿用既有 12 个（支出 8 + 收入 4） */
    val globalCategories: List<SeedCategory> = listOf(
        SeedCategory("餐饮", "🍚", EntryType.EXPENSE, null),
        SeedCategory("交通", "🚌", EntryType.EXPENSE, null),
        SeedCategory("购物", "🛍️", EntryType.EXPENSE, null),
        SeedCategory("居住", "🏠", EntryType.EXPENSE, null),
        SeedCategory("医疗", "💊", EntryType.EXPENSE, null),
        SeedCategory("娱乐", "🎮", EntryType.EXPENSE, null),
        SeedCategory("学习", "📚", EntryType.EXPENSE, null),
        SeedCategory("其他支出", "📦", EntryType.EXPENSE, null),
        SeedCategory("工资", "💰", EntryType.INCOME, null),
        SeedCategory("理财", "📈", EntryType.INCOME, null),
        SeedCategory("红包", "🧧", EntryType.INCOME, null),
        SeedCategory("其他收入", "✨", EntryType.INCOME, null),
    )

    /** 分区专属分类：装修专属（支出 5 + 收入 2） */
    val sectionCategories: List<SeedCategory> = listOf(
        SeedCategory("主材", "🧱", EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),
        SeedCategory("人工", "👷", EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),
        SeedCategory("家具", "🛋️", EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),
        SeedCategory("家电", "📺", EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),
        SeedCategory("设计费", "📐", EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),
        SeedCategory("报销", "💵", EntryType.INCOME, ZHUANGXIU_SECTION_NAME),
        SeedCategory("退款", "↩️", EntryType.INCOME, ZHUANGXIU_SECTION_NAME),
    )

    /** 全部分类（全局 + 分区专属），便于单测整体断言 */
    val categories: List<SeedCategory> = globalCategories + sectionCategories
}
