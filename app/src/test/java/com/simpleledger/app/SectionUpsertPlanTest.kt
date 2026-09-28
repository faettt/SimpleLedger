package com.simpleledger.app

import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.logic.SectionUpsertPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分区「编辑保存」行合并的回归钉子（AU-5，mustFix）。
 *
 * 缺陷背景：编辑分区的调用方构造 [SectionEntity] 只填表单字段、`sortOrder` 恒为
 * 默认 0，仓库编辑分支走 `@Update` 全列覆写——每改一次名 / 预算，该分区就跳到
 * 分区首屏最前（`ORDER BY sortOrder, id`），且 UPSERT 载荷携带 `sortOrder = 0`
 * 经操作日志把错误排序传染给全部设备。修复后编辑分支必须保库内现值
 * （与 saveCategory 编辑分支特意传 `existing?.sortOrder` 同款的有意设计）。
 */
class SectionUpsertPlanTest {

    /** 库内现值：用户手动排到第 3 位的装修分区 */
    private fun current(sortOrder: Int = 3) = SectionEntity(
        id = 7L,
        name = "装修",
        iconId = 17,
        note = "主材与人工",
        budgetCents = 26_000_000L,
        colorIndex = 2,
        sortOrder = sortOrder,
        createdAt = 1_000L,
        syncId = "sync-section-7",
        versionSeq = 4L,
        updatedAt = 2_000L,
    )

    /** 调用方提交的表单实体（只填表单字段，sortOrder 默认 0 —— 缺陷现场） */
    private fun incoming(name: String = "装修焕新", budget: Long = 30_000_000L) = SectionEntity(
        id = 7L,
        name = name,
        iconId = 17,
        note = "",
        budgetCents = budget,
        colorIndex = 2,
    )

    @Test
    fun `edit keeps current sortOrder`() {
        val row = SectionUpsertPlan.editRow(incoming(), current(sortOrder = 3), "sync-section-7", now = 5_000L)
        assertEquals("编辑不得把用户排序归 0", 3, row.sortOrder)
    }

    @Test
    fun `edit bumps versionSeq and keeps createdAt`() {
        val row = SectionUpsertPlan.editRow(incoming(), current(), "sync-section-7", now = 5_000L)
        assertEquals(5L, row.versionSeq) // cur.versionSeq(4) + 1
        assertEquals(1_000L, row.createdAt)
        assertEquals(5_000L, row.updatedAt)
    }

    @Test
    fun `edit applies form fields but preserves out-of-form state`() {
        val row = SectionUpsertPlan.editRow(incoming(), current(), "sync-section-7", now = 5_000L)
        assertEquals("装修焕新", row.name)
        assertEquals(30_000_000L, row.budgetCents)
        assertEquals(2, row.colorIndex) // 表单外字段本就回填，一并钉死
        assertEquals("sync-section-7", row.syncId)
        assertEquals(7L, row.id)
    }

    @Test
    fun `missing current row falls back to incoming values`() {
        // 行不在（远端尚未合并等异常场景）：以 incoming 为准，行为与旧实现一致
        val row = SectionUpsertPlan.editRow(incoming(), null, "sync-new", now = 5_000L)
        assertEquals(0, row.sortOrder)
        assertEquals(1L, row.versionSeq) // incoming.versionSeq(0) + 1
        assertEquals("sync-new", row.syncId)
        assertEquals(5_000L, row.updatedAt)
    }
}
