package com.simpleledger.app

import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.logic.CategoryDeletePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删除分类「去向单选」纯逻辑单测（B4/FR-41）。
 *
 * 定案锚点：
 * - 候选 = 同类型其余分类 + 「未分类」哨兵；
 * - **哨兵恒排末尾**（兜底语义，不与用户自建分类抢视线）；
 * - 默认预选 = 哨兵；候选为空时默认去向 = null；
 * - resolveDestinationId 越权（目标自身 / 候选外 id）→ 落回哨兵。
 */
class CategoryDeletePlanTest {

    /** 哨兵行（与 SectionFirstSeed.Unclassified.categoryRow 同构造，带本地 id） */
    private fun sentinel(id: Long, type: Int = EntryType.EXPENSE) =
        SectionFirstSeed.Unclassified.categoryRow(type).copy(id = id)

    private fun cat(id: Long, name: String, sortOrder: Int, type: Int = EntryType.EXPENSE) =
        CategoryEntity(id = id, name = name, iconId = 2, type = type, sortOrder = sortOrder)

    // -------- 展示序：其余分类按 (sortOrder, id) 升序，哨兵恒排末尾 --------

    @Test
    fun `destinations exclude target and put sentinel last`() {
        val sameType = listOf(
            cat(3, "交通", sortOrder = 1),
            sentinel(9),
            cat(1, "餐饮", sortOrder = 0),
            cat(2, "购物", sortOrder = 2),
            cat(4, "医疗", sortOrder = 0), // 与餐饮同 sortOrder，按 id 升序 → 餐饮在前
        )
        val plan = CategoryDeletePlan.plan(sameType, targetId = 3L)
        assertEquals(listOf(1L, 4L, 2L, 9L), plan.destinations.map { it.id })
    }

    @Test
    fun `default preselect is sentinel`() {
        val sameType = listOf(cat(1, "餐饮", sortOrder = 0), sentinel(9))
        val plan = CategoryDeletePlan.plan(sameType, targetId = 2L)
        assertEquals(9L, plan.defaultDestinationId)
    }

    @Test
    fun `default preselect falls back to first candidate without sentinel`() {
        val sameType = listOf(cat(1, "餐饮", sortOrder = 0), cat(2, "交通", sortOrder = 1))
        val plan = CategoryDeletePlan.plan(sameType, targetId = 2L)
        assertEquals(1L, plan.defaultDestinationId)
    }

    @Test
    fun `empty destinations give null default`() {
        val plan = CategoryDeletePlan.plan(emptyList(), targetId = 1L)
        assertTrue(plan.destinations.isEmpty())
        assertNull(plan.defaultDestinationId)
    }

    @Test
    fun `sentinel identity is syncId-based even for other type rows`() {
        // 哨兵身份只按 syncId 判定（isUnclassified 不看 type）：数据异常时收入哨兵
        // 混进支出候选，也按「哨兵」处理——恒排末尾且作默认预选（误宽不误漏）。
        val incomeSentinel = sentinel(9, type = EntryType.INCOME)
        val sameType = listOf(incomeSentinel, cat(1, "餐饮", sortOrder = 0))
        val plan = CategoryDeletePlan.plan(sameType, targetId = 3L)
        assertEquals(listOf(1L, 9L), plan.destinations.map { it.id })
        assertEquals(9L, plan.defaultDestinationId)
    }

    // -------- resolveDestinationId：越权回退哨兵 --------

    @Test
    fun `valid requested id wins`() {
        val destinations = listOf(cat(1, "餐饮", sortOrder = 0), sentinel(9))
        assertEquals(1L, CategoryDeletePlan.resolveDestinationId(destinations, 1L))
    }

    @Test
    fun `null request falls back to sentinel`() {
        val destinations = listOf(cat(1, "餐饮", sortOrder = 0), sentinel(9))
        assertEquals(9L, CategoryDeletePlan.resolveDestinationId(destinations, null))
    }

    @Test
    fun `invalid request falls back to sentinel`() {
        val destinations = listOf(cat(1, "餐饮", sortOrder = 0), sentinel(9))
        // 越权 id（目标自身 2 / 候选外 99）都不可信
        assertEquals(9L, CategoryDeletePlan.resolveDestinationId(destinations, 2L))
        assertEquals(9L, CategoryDeletePlan.resolveDestinationId(destinations, 99L))
    }

    @Test
    fun `all invalid gives null means cannot delete`() {
        assertNull(CategoryDeletePlan.resolveDestinationId(emptyList(), null))
        assertNull(CategoryDeletePlan.resolveDestinationId(emptyList(), 5L))
    }
}
