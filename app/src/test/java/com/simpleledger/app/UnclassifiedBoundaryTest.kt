package com.simpleledger.app

import com.simpleledger.app.data.local.SeedIds
import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.RowKindValue
import com.simpleledger.app.data.local.entity.TrashKind
import com.simpleledger.app.logic.CategoryDeletePlan
import com.simpleledger.app.logic.PhotoRetention
import com.simpleledger.app.logic.StatsCalculator
import com.simpleledger.app.logic.TrashAggregation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 交叉验证补充的边界/反例测试（P1-1，2026-09-25）。
 *
 * 覆盖既有 28 用例之外的边界：
 * 1. B3 统计桶：去向选「未分类」后，哨兵作为**普通分类行**进统计桶，占比数学正确、无特判；
 * 2. B4 反例：任意输入顺序下，去向候选**永不包含目标自身**；
 * 3. B1 边界：全量种子 syncId 集合在场时，幂等补种只补哨兵、不误伤既有种子行；
 * 4. A2 防御：PhotoRetention 对负数引用按零处理（`<=0` 语义锁定，误宽不误漏）；
 * 5. A1 边界：**0 笔账目的分区整包**（真机走查 05 帧实际出现的形态）→ 仍成包、entryCount=0；
 * 6. A1 边界：同分区两个整包并发在场（旧包 0 笔 + 新包 1 笔）→ 互不吸纳、各按包头 rowSyncId 归属。
 */
class UnclassifiedBoundaryTest {

    // -------- 1) B3：去向=未分类 → 统计桶自然出现（哨兵是真实分类，无硬编码特判） --------

    @Test
    fun `unclassified destination forms a normal stats bucket`() {
        // 删除「餐饮」去向选了未分类哨兵（本地 id = 9）后，DAO 聚合出的
        // CategoryTotal 与其他分类同构：哨兵桶按普通分类参与占比计算。
        val buckets = listOf(
            CategoryTotal(
                categoryId = 9L, name = SectionFirstSeed.Unclassified.NAME, iconId = SectionFirstSeed.Unclassified.ICON_ID,
                sectionId = null, sectionName = null, sectionIconId = null, sectionColorIndex = null, total = 3_000L, count = 3,
            ),
            CategoryTotal(
                categoryId = 2L, name = "交通", iconId = 8,
                sectionId = null, sectionName = null, sectionIconId = null, sectionColorIndex = null, total = 1_000L, count = 1,
            ),
        )
        val shares = StatsCalculator.categoryShares(buckets)
        assertEquals(2, shares.size)
        val unclassified = shares.first { it.total.categoryId == 9L }
        assertEquals("未分类", unclassified.total.name)
        assertEquals(0.75, unclassified.fraction, 1e-9)
        assertEquals("75%", StatsCalculator.percentLabel(unclassified.fraction))
    }

    @Test
    fun `unclassified-only bucket still renders with full share`() {
        // 极端：全部账目都落哨兵（用户从不选分类）→ 统计页只有「未分类」一个桶、占满全环
        val buckets = listOf(
            CategoryTotal(
                categoryId = 9L, name = SectionFirstSeed.Unclassified.NAME, iconId = SectionFirstSeed.Unclassified.ICON_ID,
                sectionId = null, sectionName = null, sectionIconId = null, sectionColorIndex = null, total = 4_200L, count = 1,
            ),
        )
        val shares = StatsCalculator.categoryShares(buckets)
        assertEquals(1, shares.size)
        assertEquals(1.0, shares.single().fraction, 1e-9)
        assertEquals("100%", StatsCalculator.percentLabel(1.0))
    }

    // -------- 2) B4 反例：候选永不包含目标自身（乱序输入下亦然） --------

    @Test
    fun `destinations never contain the target regardless of input order`() {
        val sameType = listOf(
            SectionFirstSeed.Unclassified.categoryRow(EntryType.EXPENSE).copy(id = 9L),
            com.simpleledger.app.data.local.entity.CategoryEntity(
                id = 3L, name = "交通", iconId = 8, type = EntryType.EXPENSE, sortOrder = 1,
            ),
            com.simpleledger.app.data.local.entity.CategoryEntity(
                id = 1L, name = "餐饮", iconId = 2, type = EntryType.EXPENSE, sortOrder = 0,
            ),
        )
        for (target in sameType.map { it.id }) {
            val plan = CategoryDeletePlan.plan(sameType.shuffled(), targetId = target)
            assertFalse(
                "目标自身 $target 不得出现在去向候选",
                plan.destinations.any { it.id == target },
            )
        }
    }

    // -------- 3) B1 边界：全量种子在场时幂等补种不误伤 --------

    @Test
    fun `seeding against full seed catalog only adds the two sentinels`() {
        // 新装 seed() 已写 19 个普通分类；升级库启动时 ensureUnclassified 的输入
        // = 全部既有 syncId（含种子 + 用户自建）。此时只应补 2 条哨兵，零误伤。
        val existing = SectionFirstSeed.categories
            .map { SeedIds.category(it.type, it.sectionName, it.name) }
            .toSet()
        val missing = SectionFirstSeed.Unclassified.missingCategories(existing)
        assertEquals(
            listOf(
                SectionFirstSeed.Unclassified.syncId(EntryType.EXPENSE),
                SectionFirstSeed.Unclassified.syncId(EntryType.INCOME),
            ),
            missing.map { it.syncId },
        )
        // 且补种集与既有种子集零交集（不重插任何既有行）
        assertTrue(missing.map { it.syncId }.none { it in existing })
    }

    // -------- 4) A2 防御：负数引用按零处理 --------

    @Test
    fun `negative ref counts are treated as zero`() {
        // DAO COUNT 不会返回负数；这里锁定 <=0 判据对异常输入的防御语义
        assertTrue(PhotoRetention.shouldDeleteFile(liveRefCount = -1, suspendedTrashRefCount = 0))
        assertTrue(PhotoRetention.shouldDeleteFile(liveRefCount = 0, suspendedTrashRefCount = -3))
    }

    // -------- 5) A1 边界：0 笔账目的分区整包 --------

    private fun trash(
        deleteOpId: String,
        rowKind: String,
        rowSyncId: String,
        snapshot: JSONObject,
        deletedAt: Long = 1_000L,
        kind: String = TrashKind.DELETE,
    ) = com.simpleledger.app.data.local.entity.ConflictTrashEntity(
        deleteOpId = deleteOpId,
        rowKind = rowKind,
        rowSyncId = rowSyncId,
        kind = kind,
        snapshot = snapshot.toString(),
        deletedAt = deletedAt,
        deletedByMemberId = null,
    )

    @Test
    fun `empty section still forms a package with zero entries`() {
        // 走查 05 帧实况：旧幽灵进程留下「分区『装修』及 0 笔账目」的包。
        // 边界语义：包头独立成包（不因无成员被丢弃），entryCount=0。
        val rows = listOf(
            trash("d-sec", RowKindValue.SECTION, "sec-1", JSONObject().put("name", "装修"), deletedAt = 20_080_000L),
        )
        val groups = TrashAggregation.group(rows)
        assertEquals(1, groups.size)
        val pkg = groups.single()
        assertEquals(TrashAggregation.GROUP_SECTION, pkg.kind)
        assertEquals(0, pkg.entryCount)
        assertEquals(0, pkg.imageCount)
        assertEquals(listOf(RowKindValue.SECTION), pkg.rows.map { it.rowKind })
    }

    @Test
    fun `two packages of the same section do not cross-absorb`() {
        // 同一分区 syncId 的两个删除留底（旧包 0 笔 + 新包 1 笔）：
        // 新包的成员 ENTRY 按 sectionSyncId 归属到**每一个**同名包头吗？——
        // 定案口径：成员被「所有指向该分区的包头」各自吸纳一次即重复；实现按
        // forEach(head) 独立拉取，此处锁定实际行为 = 各包头独立成包且成员不复制成两份散条。
        val rows = listOf(
            trash("d-sec-old", RowKindValue.SECTION, "sec-1", JSONObject().put("name", "装修"), deletedAt = 1_000L),
            trash("d-sec-new", RowKindValue.SECTION, "sec-1", JSONObject().put("name", "装修"), deletedAt = 2_000L),
            trash("d-e-new", RowKindValue.ENTRY, "e1", JSONObject().put("sectionSyncId", "sec-1"), deletedAt = 1_900L),
        )
        val groups = TrashAggregation.group(rows)
        // 散条 = 0：成员 ENTRY 被整包吸纳，绝不作为散条重复出现（规则 3 的反面锁定）
        assertEquals(0, groups.count { it.kind == TrashAggregation.GROUP_SOLO })
        assertEquals(2, groups.size)
        val newPkg = groups.first { it.head.deleteOpId == "d-sec-new" }
        val oldPkg = groups.first { it.head.deleteOpId == "d-sec-old" }
        // 成员挂在（每次包头拉取时都在池里→）两个包各自吸纳：总吸纳次数恰好 1 次/包，且无散条泄漏
        assertEquals(1, newPkg.entryCount + oldPkg.entryCount)
    }
}
