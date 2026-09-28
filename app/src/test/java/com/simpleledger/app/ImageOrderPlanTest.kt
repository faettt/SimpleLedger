package com.simpleledger.app

import com.simpleledger.app.logic.ImageOrderPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 贴图顺序重排纯逻辑的回归钉子（AU-4）。
 *
 * 缺陷复现（裁决实例）：原 A(0) B(1) C(2) D(3)，编辑页删 A、B 保留 C、D 并新增 E
 * → 旧实现保留行不重写 sortOrder、新图 E 拿 sortOrder = keptCount = 2，与 C 撞序，
 * `ORDER BY sortOrder, id` 下按 id 破平 → 展示 C,E,D，而编辑页顺序是 C,D,E——
 * 保存后重开即见顺序被打乱。修复后保留行按编辑器顺序重写 0..k-1、新图从 k 起。
 */
class ImageOrderPlanTest {

    @Test
    fun `kept rows renumber to zero-based editor order`() {
        // 删 A(0)、B(1)，保留 C(2)、D(3) → C→0、D→1
        val plan = ImageOrderPlan.renumberPlan(
            keptPaths = listOf("/c.jpg", "/d.jpg"),
            currentSortOrder = mapOf("/a.jpg" to 0, "/b.jpg" to 1, "/c.jpg" to 2, "/d.jpg" to 3),
        )
        assertEquals(mapOf("/c.jpg" to 0, "/d.jpg" to 1), plan)
    }

    @Test
    fun `unchanged rows produce no writes`() {
        // 顺序未动（删的是尾部）→ 零写放大，普通编辑不产生噪音操作
        val plan = ImageOrderPlan.renumberPlan(
            keptPaths = listOf("/a.jpg", "/b.jpg"),
            currentSortOrder = mapOf("/a.jpg" to 0, "/b.jpg" to 1, "/c.jpg" to 2),
        )
        assertEquals(emptyMap<String, Int>(), plan)
    }

    @Test
    fun `append after removals starts new images right after kept rows`() {
        val kept = listOf("/c.jpg", "/d.jpg")
        val renumber = ImageOrderPlan.renumberPlan(
            kept,
            mapOf("/a.jpg" to 0, "/b.jpg" to 1, "/c.jpg" to 2, "/d.jpg" to 3),
        )
        // 修复后编辑页顺序 C,D,E 落库序号 = 0,1,2：重排 C→0、D→1，新图 E 从 k=2 起
        // （旧实现 E 拿 keptCount=2 之外的旧撞序形态已被本组断言钉死）
        assertEquals(mapOf("/c.jpg" to 0, "/d.jpg" to 1), renumber)
        assertEquals(2, ImageOrderPlan.nextOrderForImported(kept))
    }

    @Test
    fun `kept paths missing from database are ignored`() {
        // keptImagePaths 里混进未落库路径（异常场景）不应让重排崩溃
        val plan = ImageOrderPlan.renumberPlan(
            keptPaths = listOf("/missing.jpg", "/a.jpg"),
            currentSortOrder = mapOf("/a.jpg" to 5),
        )
        assertEquals(mapOf("/a.jpg" to 1), plan)
    }

    @Test
    fun `empty kept list yields empty plan and zero start`() {
        assertEquals(emptyMap<String, Int>(), ImageOrderPlan.renumberPlan(emptyList(), mapOf("/a.jpg" to 0)))
        assertEquals(0, ImageOrderPlan.nextOrderForImported(emptyList()))
    }
}
