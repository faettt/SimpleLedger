package com.simpleledger.app

import com.simpleledger.app.data.local.entity.ConflictTrashEntity
import com.simpleledger.app.data.local.entity.RowKindValue
import com.simpleledger.app.data.local.entity.TrashKind
import com.simpleledger.app.logic.TrashAggregation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收站「整包聚合」纯逻辑单测（A1/A2）。
 *
 * 定案锚点：
 * 1. 分区 DELETE 留底 = 包头，吸纳「快照 sectionSyncId 指向它」的 ENTRY 删底，
 *    以及成员账目名下的 IMAGE 删底；
 * 2. 无主 ENTRY 自成包并吸纳自己的 IMAGE；OVERWRITE 落败版 / 孤立 IMAGE / CATEGORY 散条独立；
 * 3. 包内 rows 按 SECTION → ENTRY → IMAGE 分层序；包间按最新 deletedAt 新→旧。
 */
class TrashAggregationTest {

    private fun trash(
        deleteOpId: String,
        rowKind: String,
        rowSyncId: String,
        snapshot: JSONObject,
        deletedAt: Long = 1_000L,
        kind: String = TrashKind.DELETE,
    ) = ConflictTrashEntity(
        deleteOpId = deleteOpId,
        rowKind = rowKind,
        rowSyncId = rowSyncId,
        kind = kind,
        snapshot = snapshot.toString(),
        deletedAt = deletedAt,
        deletedByMemberId = null,
    )

    private fun sectionSnapshot(syncId: String, name: String = "装修") =
        JSONObject().put("name", name).put("colorIndex", 2)

    private fun entrySnapshot(sectionSyncId: String, note: String = "便当") =
        JSONObject().put("sectionSyncId", sectionSyncId).put("note", note).put("amountCents", 1200L)

    private fun imageSnapshot(entrySyncId: String, contentHash: String) =
        JSONObject().put("entrySyncId", entrySyncId).put("contentHash", contentHash).put("sortOrder", 0)

    // -------- 分区整包 --------

    @Test
    fun `section delete groups entries and images into one package`() {
        val rows = listOf(
            trash("d-entry-2", RowKindValue.ENTRY, "e2", entrySnapshot("sec-1"), deletedAt = 900L),
            trash("d-sec", RowKindValue.SECTION, "sec-1", sectionSnapshot("sec-1"), deletedAt = 1_000L),
            trash("d-entry-1", RowKindValue.ENTRY, "e1", entrySnapshot("sec-1"), deletedAt = 900L),
            trash("d-img-1", RowKindValue.IMAGE, "i1", imageSnapshot("e1", "hash-a"), deletedAt = 800L),
            trash("d-img-2", RowKindValue.IMAGE, "i2", imageSnapshot("e2", "hash-b"), deletedAt = 800L),
        )
        val groups = TrashAggregation.group(rows)
        assertEquals(1, groups.size)
        val pkg = groups.single()
        assertEquals(TrashAggregation.GROUP_SECTION, pkg.kind)
        assertEquals("d-sec", pkg.head.deleteOpId)
        // 分层序：SECTION → ENTRY（2 笔）→ IMAGE（2 张）
        assertEquals(
            listOf(RowKindValue.SECTION, RowKindValue.ENTRY, RowKindValue.ENTRY, RowKindValue.IMAGE, RowKindValue.IMAGE),
            pkg.rows.map { it.rowKind },
        )
        assertEquals(2, pkg.entryCount)
        assertEquals(2, pkg.imageCount)
        assertEquals(setOf("e1", "e2"), pkg.entrySyncIds)
    }

    @Test
    fun `image pointing to foreign entry is not absorbed by section package`() {
        val rows = listOf(
            trash("d-sec", RowKindValue.SECTION, "sec-1", sectionSnapshot("sec-1")),
            trash("d-entry-1", RowKindValue.ENTRY, "e1", entrySnapshot("sec-1")),
            trash("d-img-x", RowKindValue.IMAGE, "ix", imageSnapshot("e-other", "hash-c")),
        )
        val groups = TrashAggregation.group(rows)
        assertEquals(2, groups.size)
        val solo = groups.first { it.kind == TrashAggregation.GROUP_SOLO }
        assertEquals("d-img-x", solo.head.deleteOpId)
    }

    // -------- 单笔账目包 --------

    @Test
    fun `orphan entry forms its own group with its images`() {
        val rows = listOf(
            trash("d-entry-1", RowKindValue.ENTRY, "e1", entrySnapshot("sec-dead"), deletedAt = 2_000L),
            trash("d-img-1", RowKindValue.IMAGE, "i1", imageSnapshot("e1", "hash-a"), deletedAt = 2_000L),
        )
        val groups = TrashAggregation.group(rows)
        assertEquals(1, groups.size)
        val single = groups.single()
        assertEquals(TrashAggregation.GROUP_ENTRY, single.kind)
        assertEquals(1, single.entryCount)
        assertEquals(1, single.imageCount)
    }

    // -------- 散条 --------

    @Test
    fun `overwrite losers categories and orphan images stay solo`() {
        val rows = listOf(
            trash("d-cat", RowKindValue.CATEGORY, "c1", JSONObject().put("name", "餐饮")),
            trash("d-ov", RowKindValue.ENTRY, "e1", entrySnapshot("s"), kind = TrashKind.OVERWRITE),
            trash("d-img", RowKindValue.IMAGE, "i1", imageSnapshot("e-none", "hash-z")),
        )
        val groups = TrashAggregation.group(rows)
        assertEquals(3, groups.size)
        assertTrue(groups.all { it.kind == TrashAggregation.GROUP_SOLO })
        // 落败版 ENTRY 散条的 entryCount = 1（行级计数按 rowKind，不区分包语义）
        assertEquals(1, groups.sumOf { it.entryCount })
    }

    // -------- 排序 --------

    @Test
    fun `groups sorted by newest deletedAt then head opId`() {
        val rows = listOf(
            // 旧包（整包，deletedAt 取包内最新 = 1_000）
            trash("d-sec-old", RowKindValue.SECTION, "sec-1", sectionSnapshot("sec-1"), deletedAt = 1_000L),
            trash("d-e-old", RowKindValue.ENTRY, "e1", entrySnapshot("sec-1"), deletedAt = 900L),
            // 新单笔
            trash("d-e-new", RowKindValue.ENTRY, "e2", entrySnapshot("sec-2"), deletedAt = 3_000L),
            // 同刻两包 → 按 head opId 稳定序
            trash("d-sec-b", RowKindValue.SECTION, "sec-b", sectionSnapshot("sec-b"), deletedAt = 2_000L),
            trash("d-sec-a", RowKindValue.SECTION, "sec-a", sectionSnapshot("sec-a"), deletedAt = 2_000L),
        )
        val groups = TrashAggregation.group(rows)
        assertEquals(
            listOf("d-e-new", "d-sec-a", "d-sec-b", "d-sec-old"),
            groups.map { it.head.deleteOpId },
        )
    }

    // -------- 照片 hash 提取（A2 释放判据的输入） --------

    @Test
    fun `imageHashesOf only reads image rows`() {
        val rows = listOf(
            trash("d-img-1", RowKindValue.IMAGE, "i1", imageSnapshot("e1", "hash-a")),
            trash("d-img-2", RowKindValue.IMAGE, "i2", imageSnapshot("e2", "hash-b")),
            // ENTRY 快照不携带照片语义，即使误带 contentHash 也不算
            trash("d-e", RowKindValue.ENTRY, "e1", JSONObject().put("contentHash", "hash-a")),
            // IMAGE 行但快照缺 contentHash 键 → 提取安全跳过
            trash("d-img-nohash", RowKindValue.IMAGE, "i3", JSONObject().put("sortOrder", 0)),
        )
        assertEquals(setOf("hash-a", "hash-b"), TrashAggregation.imageHashesOf(rows))
    }
}
