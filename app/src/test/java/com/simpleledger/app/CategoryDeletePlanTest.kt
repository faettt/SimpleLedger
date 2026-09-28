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

    // -------- AU-3：去向候选按受影响账目所在分区收窄 --------
    // 「账目的分区 = X、分类只属于 Y」是非法组合（SectionMoveRules/EC-06）——
    // 去向要服务**所有**受影响账目：全局恒合法；专属分类只有当全部受影响账目
    // 都落在它的归属分区时才合法。

    /** 装修(id=7) 专属分类 */
    private fun exclusive(id: Long, name: String, sectionId: Long, sortOrder: Int) =
        CategoryEntity(
            id = id, name = name, iconId = 44,
            type = EntryType.EXPENSE, sectionId = sectionId, sortOrder = sortOrder,
        )

    @Test
    fun `exclusive candidates of other sections are filtered out`() {
        val sameType = listOf(
            cat(1, "餐饮", sortOrder = 0),                    // 全局
            exclusive(2, "主材", sectionId = 7L, sortOrder = 1), // 装修专属
            exclusive(3, "机票", sectionId = 8L, sortOrder = 2), // 旅行专属
            sentinel(9),
        )
        // 受影响账目都在旅行(8)：装修专属「主材」非法，旅行专属「机票」合法；
        // 全局「餐饮」任何分区都可见，恒合法（与本文件 AU-3 节注释口径一致）
        val plan = CategoryDeletePlan.plan(sameType, targetId = 99L, affectedSectionIds = setOf(8L))
        assertEquals(listOf(1L, 3L, 9L), plan.destinations.map { it.id })
        assertEquals(9L, plan.defaultDestinationId)
    }

    @Test
    fun `entries spanning two sections leave only global candidates`() {
        val sameType = listOf(
            cat(1, "餐饮", sortOrder = 0),
            exclusive(2, "主材", sectionId = 7L, sortOrder = 1),
            exclusive(3, "机票", sectionId = 8L, sortOrder = 2),
            sentinel(9),
        )
        // 账目横跨装修+旅行：任何专属分类都服务不了全部账目 → 只剩全局 + 哨兵
        val plan = CategoryDeletePlan.plan(sameType, targetId = 99L, affectedSectionIds = setOf(7L, 8L))
        assertEquals(listOf(1L, 9L), plan.destinations.map { it.id })
    }

    @Test
    fun `placeholder section 0 in affected set blocks all exclusive candidates`() {
        val sameType = listOf(
            cat(1, "餐饮", sortOrder = 0),
            exclusive(2, "主材", sectionId = 7L, sortOrder = 1),
            sentinel(9),
        )
        // 挂死分区（0 占位）的账目不可能合法挂任何专属分类
        val plan = CategoryDeletePlan.plan(sameType, targetId = 99L, affectedSectionIds = setOf(0L))
        assertEquals(listOf(1L, 9L), plan.destinations.map { it.id })
    }

    @Test
    fun `null or empty affected set keeps legacy full candidates`() {
        val sameType = listOf(
            cat(1, "餐饮", sortOrder = 0),
            exclusive(2, "主材", sectionId = 7L, sortOrder = 1),
            sentinel(9),
        )
        // 无受影响账目（迁移不会发生）→ 不过滤，与旧口径一致
        assertEquals(
            listOf(1L, 2L, 9L),
            CategoryDeletePlan.plan(sameType, targetId = 99L, affectedSectionIds = null)
                .destinations.map { it.id },
        )
        assertEquals(
            listOf(1L, 2L, 9L),
            CategoryDeletePlan.plan(sameType, targetId = 99L, affectedSectionIds = emptySet())
                .destinations.map { it.id },
        )
    }

    @Test
    fun `target itself stays excluded after narrowing`() {
        val sameType = listOf(
            exclusive(2, "主材", sectionId = 7L, sortOrder = 1),
            sentinel(9),
        )
        // 收窄不该把被删分类自己放回候选（即便它按分区看似合法）
        val plan = CategoryDeletePlan.plan(sameType, targetId = 2L, affectedSectionIds = setOf(7L))
        assertEquals(listOf(9L), plan.destinations.map { it.id })
    }
}
