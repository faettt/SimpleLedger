package com.simpleledger.app

import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.logic.CategoryReorderRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-3（QA Round 1，2026-09-25）：分类排序的「未分类」哨兵拦截规则。
 *
 * 锁定口径（与 [CategoryDeletePlan]「未分类恒排末尾」同源）：
 * 1. 排序重写计划**剔除哨兵**——哨兵位次不动、不 bump versionSeq、不记 UPSERT；
 * 2. 哨兵在传入序列的任意位置（头/中/尾）都被忽略，普通行保持相对顺序；
 * 3. UI 排序按钮与仓库拦截共用同一判定（isReorderable），哨兵恒 false。
 */
class CategoryReorderRulesTest {

    private fun normal(id: Long, name: String, sortOrder: Int = 0) = CategoryEntity(
        id = id, name = name, iconId = 8, type = EntryType.EXPENSE, sortOrder = sortOrder,
    )

    private fun sentinel(id: Long = 9L) =
        SectionFirstSeed.Unclassified.categoryRow(EntryType.EXPENSE).copy(id = id)

    // -------- 1) 重写计划剔除哨兵，普通行按传入序取位 --------

    @Test
    fun `rewrite plan drops the sentinel and keeps normal rows in input order`() {
        val ordered = listOf(normal(1, "餐饮"), normal(2, "交通"), sentinel())
        val plan = CategoryReorderRules.rewritePlan(ordered)
        // 哨兵被整行忽略：无 id=9 的重写项（位次不动、零埋点的前提）
        assertFalse("哨兵不得出现在重写计划", plan.any { it.first == 9L })
        // 普通行按传入相对顺序重编位次
        assertEquals(listOf(1L to 0, 2L to 1), plan)
    }

    @Test
    fun `sentinel is ignored wherever it sits in the input list`() {
        // 哨兵占位不挤压普通行：普通行的位次 = 传入序列中的原下标（哨兵槽位留空）。
        // 常规形态（哨兵在尾）下与逐行下标完全一致；哨兵在头/中部（历史数据怪相）时
        // 普通行保持原下标即保持相对顺序，语义仍是「哨兵位不动、普通行不被挤压」。
        val byPosition = listOf(
            listOf(sentinel(), normal(1, "餐饮"), normal(2, "交通")),   // 哨兵在头
            listOf(normal(1, "餐饮"), sentinel(), normal(2, "交通")),   // 哨兵在中
            listOf(normal(1, "餐饮"), normal(2, "交通"), sentinel()),   // 哨兵在尾（常规形态）
        )
        val expected = listOf(
            listOf(1L to 1, 2L to 2),
            listOf(1L to 0, 2L to 2),
            listOf(1L to 0, 2L to 1),
        )
        assertEquals(expected, byPosition.map { CategoryReorderRules.rewritePlan(it) })
    }

    @Test
    fun `rewrite plan without sentinel is the identity ordering`() {
        // 分区专属分类作用域无哨兵：重写计划与逐行下标一一对应（回归保护）
        val ordered = listOf(normal(3, "房租"), normal(4, "水电"), normal(5, "话费"))
        assertEquals(
            listOf(3L to 0, 4L to 1, 5L to 2),
            CategoryReorderRules.rewritePlan(ordered),
        )
    }

    // -------- 2) isReorderable：UI 按钮 enable 与仓库拦截共用同一口径 --------

    @Test
    fun `sentinel is never reorderable while user categories always are`() {
        assertFalse(CategoryReorderRules.isReorderable(sentinel()))
        // 收入哨兵同样拦截（两种类型各一行）
        assertFalse(
            CategoryReorderRules.isReorderable(
                SectionFirstSeed.Unclassified.categoryRow(EntryType.INCOME).copy(id = 10L),
            ),
        )
        // 用户自建的同名「未分类」分类不算哨兵，仍可正常排序
        val sameName = normal(7, SectionFirstSeed.Unclassified.NAME)
        assertTrue("同名自建分类不是哨兵，应可排序", CategoryReorderRules.isReorderable(sameName))
    }
}
