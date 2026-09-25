package com.simpleledger.app

import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.entity.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1「未分类」哨兵单测。
 *
 * 定案锚点：
 * - 哨兵 = 普通分类行，确定性 syncId（`SeedIds.unclassified(type)`），两设备补种同一逻辑行；
 * - 幂等补种：missingCategories 第二次调用返回空列表；
 * - B2 保存口径：不选分类 = 落哨兵；
 * - 哨兵恒排末尾是展示序约定（CategoryDeletePlanTest 锁定），此处锁定 syncId 身份与模板。
 */
class UnclassifiedTest {

    @Test
    fun `syncId is deterministic and type-scoped`() {
        val expense = SectionFirstSeed.Unclassified.syncId(EntryType.EXPENSE)
        val income = SectionFirstSeed.Unclassified.syncId(EntryType.INCOME)
        assertEquals(expense, SectionFirstSeed.Unclassified.syncId(EntryType.EXPENSE))
        assertNotEquals(expense, income)
        // 32 字符小写、sd 前缀（与随机 UUID 身份可区分）
        assertTrue(expense.startsWith("sd"))
        assertEquals(32, expense.length)
    }

    @Test
    fun `isUnclassified checks by syncId identity not name`() {
        val expenseSyncId = SectionFirstSeed.Unclassified.syncId(EntryType.EXPENSE)
        assertTrue(SectionFirstSeed.Unclassified.isUnclassified(expenseSyncId))
        // 用户自建同名「未分类」不算哨兵
        assertFalse(
            SectionFirstSeed.Unclassified.isUnclassified(
                SectionFirstSeed.Unclassified.categoryRow(EntryType.EXPENSE).copy(syncId = "user-made"),
            ),
        )
    }

    @Test
    fun `missingCategories is idempotent`() {
        val existing = emptySet<String>()
        val missing = SectionFirstSeed.Unclassified.missingCategories(existing)
        assertEquals(2, missing.size)
        // 第二次（补种后）：既有 syncId 集合已含哨兵 → 不再补
        val after = missing.map { it.syncId }.toSet()
        assertTrue(SectionFirstSeed.Unclassified.missingCategories(after).isEmpty())
        // 只缺收入哨兵时只补收入
        val onlyExpense = setOf(SectionFirstSeed.Unclassified.syncId(EntryType.EXPENSE))
        assertEquals(
            listOf(EntryType.INCOME),
            SectionFirstSeed.Unclassified.missingCategories(onlyExpense).map { it.type },
        )
    }

    @Test
    fun `categoryRow is a global plain category`() {
        val row = SectionFirstSeed.Unclassified.categoryRow(EntryType.EXPENSE)
        assertEquals(SectionFirstSeed.Unclassified.NAME, row.name)
        assertEquals(EntryType.EXPENSE, row.type)
        assertEquals(null, row.sectionId) // 全局
        assertEquals(SectionFirstSeed.Unclassified.syncId(EntryType.EXPENSE), row.syncId)
    }

    @Test
    fun `saveCategoryId null selection falls to sentinel`() {
        assertEquals(
            42L,
            SectionFirstSeed.Unclassified.saveCategoryId(selectedCategoryId = null, unclassifiedId = 42L),
        )
        assertEquals(
            7L,
            SectionFirstSeed.Unclassified.saveCategoryId(selectedCategoryId = 7L, unclassifiedId = 42L),
        )
    }
}
