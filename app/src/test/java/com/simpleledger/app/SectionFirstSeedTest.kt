package com.simpleledger.app

import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.entity.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 示例数据（理想初始态）结构断言。
 *
 * 新装（`seed()`）与升级（`MIGRATION_2_3`）共用 [SectionFirstSeed]，因此这里锁住的结构
 * 就等于两条路径共同承诺的初始态：分区含 26 万装修预算、装修专属分类 5 支出 + 2 收入、
 * 全局分类支出与收入各若干。
 */
class SectionFirstSeedTest {

    @Test
    fun `keeps the three sections including decoration with 260k budget`() {
        assertEquals(3, SectionFirstSeed.sections.size)
        val decoration = SectionFirstSeed.sections.first { it.name == "装修" }
        // v4：图标是 iconId（17 = hammer 手绘图标），不再是 emoji 字符串
        assertEquals(17, decoration.iconId)
        assertEquals(26_000_000L, decoration.budgetCents)
        assertEquals(SectionFirstSeed.ZHUANGXIU_BUDGET_CENTS, decoration.budgetCents)
        // 胶带色：装修 = 赭黄(2)，与 tokens-journal.json 的 tape.palette 顺序一致
        assertEquals(SectionFirstSeed.TapeColor.ZHE_HUANG, decoration.colorIndex)
    }

    @Test
    fun `seed sections carry valid icon ids and tape colors`() {
        // 与 IconMappingTest 一起构成「种子数据 ↔ 图标集」的双向契约：
        // iconId 越界会让 slCategoryIcon() 兜底成 tag，胶带色越界会让色条取到空
        SectionFirstSeed.sections.forEach { section ->
            assertTrue(
                "分区 ${section.name} 的 iconId 越界：${section.iconId}",
                section.iconId in 1..50,
            )
            assertTrue(
                "分区 ${section.name} 的 colorIndex 越界：${section.colorIndex}",
                section.colorIndex in 0..7,
            )
        }
        // 三个初始分区的胶带色互不相同（选择网格里一眼可辨）
        assertEquals(
            3,
            SectionFirstSeed.sections.map { it.colorIndex }.toSet().size,
        )
    }

    @Test
    fun `seed categories carry icon ids in range`() {
        SectionFirstSeed.categories.forEach { category ->
            assertTrue(
                "分类 ${category.name} 的 iconId 越界：${category.iconId}",
                category.iconId in 1..50,
            )
        }
    }

    @Test
    fun `decoration owns five expense and two income exclusive categories`() {
        val exclusive = SectionFirstSeed.sectionCategories
            .filter { it.sectionName == SectionFirstSeed.ZHUANGXIU_SECTION_NAME }
        val expense = exclusive.filter { it.type == EntryType.EXPENSE }
        val income = exclusive.filter { it.type == EntryType.INCOME }

        assertEquals(5, expense.size)
        assertEquals(2, income.size)
        assertEquals(
            listOf("主材", "人工", "家具", "家电", "设计费"),
            expense.map { it.name },
        )
        assertEquals(listOf("报销", "退款"), income.map { it.name })
    }

    @Test
    fun `global categories cover both expense and income`() {
        val global = SectionFirstSeed.globalCategories
        assertTrue(global.all { it.sectionName == null })
        assertTrue(global.any { it.type == EntryType.EXPENSE })
        assertTrue(global.any { it.type == EntryType.INCOME })
        assertEquals(12, global.size)
    }

    @Test
    fun `global categories are all null-section while exclusive ones are not`() {
        SectionFirstSeed.globalCategories.forEach { assertNull(it.sectionName) }
        SectionFirstSeed.sectionCategories.forEach { assertNotNull(it.sectionName) }
    }

    @Test
    fun `combined categories equals global plus exclusive`() {
        assertEquals(
            SectionFirstSeed.globalCategories.size + SectionFirstSeed.sectionCategories.size,
            SectionFirstSeed.categories.size,
        )
    }
}
