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
        assertEquals("🔨", decoration.emoji)
        assertEquals(26_000_000L, decoration.budgetCents)
        assertEquals(SectionFirstSeed.ZHUANGXIU_BUDGET_CENTS, decoration.budgetCents)
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
