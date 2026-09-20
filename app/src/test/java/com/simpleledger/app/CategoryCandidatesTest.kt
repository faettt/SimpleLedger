package com.simpleledger.app

import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.logic.CategoryCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类候选纯逻辑单测。
 *
 * 真实 SQL 依赖 Android（跑不了 instrumented test），因此把「专属在前 / 全局在后」的拆分、
 * 占比标签（Q-09 消歧）、空态判定（EC-05）抽成纯函数并在这里钉死。
 * 这些用例同时也是「候选排序口径」的回归护栏。
 */
class CategoryCandidatesTest {

    private fun global(id: Long, name: String, emoji: String = "🍚", type: Int = EntryType.EXPENSE) =
        CategoryEntity(id = id, name = name, emoji = emoji, type = type, sectionId = null, sortOrder = 0)

    private fun exclusive(
        id: Long,
        name: String,
        sectionId: Long,
        emoji: String = "🧱",
        type: Int = EntryType.EXPENSE,
    ) = CategoryEntity(id = id, name = name, emoji = emoji, type = type, sectionId = sectionId, sortOrder = 0)

    @Test
    fun `partition keeps exclusive first and splits groups preserving order`() {
        // 输入即 observeCandidates 的有序结果：专属在前、全局在后
        val ordered = listOf(
            exclusive(10, "主材", sectionId = 5),
            exclusive(11, "人工", sectionId = 5),
            global(1, "餐饮"),
            global(2, "交通"),
        )
        val candidates = CategoryCandidates.partition(ordered)

        assertEquals(listOf("主材", "人工"), candidates.exclusive.map { it.name })
        assertEquals(listOf("餐饮", "交通"), candidates.global.map { it.name })
        assertEquals(listOf("主材", "人工", "餐饮", "交通"), candidates.all.map { it.name })
    }

    @Test
    fun `partition is stable even when input interleaves groups`() {
        val ordered = listOf(
            exclusive(10, "主材", sectionId = 5),
            global(1, "餐饮"),
            exclusive(11, "人工", sectionId = 5),
        )
        val candidates = CategoryCandidates.partition(ordered)
        assertEquals(listOf("主材", "人工"), candidates.exclusive.map { it.name })
        assertEquals(listOf("餐饮"), candidates.global.map { it.name })
    }

    @Test
    fun `filterByType selects only the requested type`() {
        val list = listOf(
            global(1, "餐饮", type = EntryType.EXPENSE),
            global(2, "工资", type = EntryType.INCOME),
        )
        assertEquals(listOf("餐饮"), CategoryCandidates.filterByType(list, EntryType.EXPENSE).map { it.name })
        assertEquals(listOf("工资"), CategoryCandidates.filterByType(list, EntryType.INCOME).map { it.name })
    }

    @Test
    fun `shareLabel disambiguates exclusive with section name - Q09`() {
        val exclusiveTotal = CategoryTotal(
            categoryId = 10,
            name = "材料",
            emoji = "🧱",
            sectionId = 5L,
            sectionName = "装修",
            sectionEmoji = "🔨",
            total = 1000L,
            count = 1,
        )
        assertEquals("🔨 装修 · 材料", CategoryCandidates.shareLabel(exclusiveTotal))
    }

    @Test
    fun `shareLabel uses category emoji for global - Q09`() {
        val globalTotal = CategoryTotal(
            categoryId = 1,
            name = "餐饮",
            emoji = "🍚",
            sectionId = null,
            sectionName = null,
            sectionEmoji = null,
            total = 1000L,
            count = 1,
        )
        assertEquals("🍚 餐饮", CategoryCandidates.shareLabel(globalTotal))
    }

    @Test
    fun `isEmpty true only when both groups are empty - EC05`() {
        assertTrue(CategoryCandidates.isEmpty(CategoryCandidates.partition(emptyList())))
        assertFalse(
            CategoryCandidates.isEmpty(
                CategoryCandidates.partition(listOf(global(1, "餐饮"))),
            ),
        )
        assertFalse(
            CategoryCandidates.isEmpty(
                CategoryCandidates.partition(listOf(exclusive(10, "主材", sectionId = 5))),
            ),
        )
    }
}
