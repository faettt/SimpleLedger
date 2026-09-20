package com.simpleledger.app

import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.logic.SectionMoveRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「移动到其它分区」的分类兼容性判定单测（QA P1-1 / PRD EC-06）。
 *
 * 覆盖三种判定：
 * 1. 全局分类可**直接**随账目移入任一分区；
 * 2. 当前分类**本就属于目标分区**时可直移；
 * 3. 专属分类属于**其它分区**时判定为「需要重选」，并给出兜底预选（或判无候选）。
 */
class SectionMoveRulesTest {

    private fun global(id: Long, name: String, type: Int = EntryType.EXPENSE) =
        CategoryEntity(id = id, name = name, emoji = "🍚", type = type, sectionId = null, sortOrder = 0)

    private fun exclusive(id: Long, name: String, sectionId: Long, type: Int = EntryType.EXPENSE) =
        CategoryEntity(id = id, name = name, emoji = "🧱", type = type, sectionId = sectionId, sortOrder = 0)

    // -------- 判定 1：全局分类可直移 --------

    @Test
    fun `global category keeps when moving to any section`() {
        val current = global(1, "餐饮")
        assertTrue(SectionMoveRules.canKeepCategory(current, targetSectionId = 5L))
        assertFalse(SectionMoveRules.needsReselect(current, targetSectionId = 5L))
        assertTrue(SectionMoveRules.canKeepCategory(current, targetSectionId = 99L))
    }

    // -------- 判定 2：专属分类归属目标分区可直移 --------

    @Test
    fun `exclusive category keeps when it already belongs to target section`() {
        val current = exclusive(10, "主材", sectionId = 7L)
        assertTrue(SectionMoveRules.canKeepCategory(current, targetSectionId = 7L))
        assertFalse(SectionMoveRules.needsReselect(current, targetSectionId = 7L))
    }

    // -------- 判定 3：专属分类属于其它分区 → 需要重选 --------

    @Test
    fun `exclusive category from another section needs reselect`() {
        val current = exclusive(10, "主材", sectionId = 7L)
        assertFalse(SectionMoveRules.canKeepCategory(current, targetSectionId = 8L))
        assertTrue(SectionMoveRules.needsReselect(current, targetSectionId = 8L))
    }

    @Test
    fun `null category never blocks a move`() {
        assertTrue(SectionMoveRules.canKeepCategory(null, targetSectionId = 8L))
        assertFalse(SectionMoveRules.needsReselect(null, targetSectionId = 8L))
    }

    // -------- 兜底预选 --------

    @Test
    fun `pickFallback prefers other-expense name`() {
        val candidates = listOf(
            exclusive(20, "水电", sectionId = 8L),
            exclusive(21, "其他支出", sectionId = 8L),
            global(1, "餐饮"),
        )
        assertEquals(21L, SectionMoveRules.pickFallback(candidates, EntryType.EXPENSE)?.id)
    }

    @Test
    fun `pickFallback prefers other-income name for income type`() {
        val candidates = listOf(
            exclusive(30, "奖金", sectionId = 8L, type = EntryType.INCOME),
            exclusive(31, "其他收入", sectionId = 8L, type = EntryType.INCOME),
        )
        assertEquals(31L, SectionMoveRules.pickFallback(candidates, EntryType.INCOME)?.id)
    }

    @Test
    fun `pickFallback falls back to first same-type candidate`() {
        val candidates = listOf(
            global(1, "餐饮"),
            exclusive(20, "水电", sectionId = 8L),
        )
        // 无「其他支出」 → 取同类型第一项
        assertEquals(1L, SectionMoveRules.pickFallback(candidates, EntryType.EXPENSE)?.id)
    }

    @Test
    fun `pickFallback returns null when no candidate of the type`() {
        val candidates = listOf(global(1, "工资", type = EntryType.INCOME))
        assertNull(SectionMoveRules.pickFallback(candidates, EntryType.EXPENSE))
        assertNull(SectionMoveRules.pickFallback(emptyList(), EntryType.EXPENSE))
    }
}
