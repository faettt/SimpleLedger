package com.simpleledger.app.sync

import com.simpleledger.app.logic.OpTrimRules
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.RowKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-12 裁剪**收敛性**验收（核心正确性证明）：对一台设备按 OpTrimRules 裁掉历史操作后，
 * 与未裁设备在三类对抗场景下仍逐字节收敛（[SyncTestDevice.state] 全状态比对）——
 *
 * 1. **迟到并发 UPSERT**：离线设备带着 `seq ≤ 已删行 baseSeq` 的旧更新晚到，
 *    两机都必须被同一条 DELETE 杀死（裁剪绝不能让死行复活）；
 * 2. **引用三分判定**：裁剪后 `countOpsOfRow ≥ 1`（每行胜者 + DELETE 保底），
 *    引用死行的新操作照常走「到过已死 → 占位」而非永久挂起；
 * 3. **存活行继续编辑**：胜者保留 = 未来合并的基线还在，普通编辑照常收敛。
 */
class OpTrimConvergenceTest {

    /** 按生产规则对一台测试设备执行裁剪（cutoff 取未来 = 测试里的操作都算「老」） */
    private suspend fun trimDevice(d: SyncTestDevice) {
        val cutoff = System.currentTimeMillis() + 24 * 60 * 60 * 1000
        // 裁剪前提 = 本轮 PUSH 已完成：本地操作全部标记已上传（模拟 syncOnce 的 PUSH 收尾）
        d.dao.markUploaded(d.dao.opLog.keys.filter { d.dao.opLog[it]?.origin == "LOCAL" }, "chunk-test")
        val doomed = OpTrimRules.trimmableOpIds(
            candidates = d.dao.trimCandidates(cutoff).map {
                OpTrimRules.OpRow(
                    it.opId, it.rowKind, it.rowSyncId, it.opType,
                    it.seq, it.actorId, it.origin, it.uploaded, it.applied, it.createdAt,
                )
            },
            winners = d.dao.latestUpsertPerRow().map {
                OpTrimRules.RowWinner(it.rowKind, it.rowSyncId, it.winnerKey)
            },
            cutoffMillis = cutoff,
        )
        d.dao.deleteOpsByIds(doomed.toList())
    }

    private fun entryPayload(amount: Long, note: String, categorySyncId: String = "C", sectionSyncId: String = "S") =
        OpCodec.entrySnapshot(
            type = 0, amountCents = amount, categorySyncId = categorySyncId, sectionSyncId = sectionSyncId,
            entryTime = 1_000L, note = note, reconciled = false, reimburseState = 0,
            createdAt = 1_000L, updatedAt = 1_000L, memberSyncId = null,
        )

    private fun seedRows(d: SyncTestDevice) {
        d.seedRow(RowKind.SECTION, "S", OpCodec.sectionSnapshot("生活", 1, "", 0, 0, 0, 1_000L))
        d.seedRow(RowKind.CATEGORY, "C", OpCodec.categorySnapshot("吃", 2, 0, null, 0))
    }

    @Test
    fun trimmedDeviceConvergesWithUntrimmedOnStaleLateArrivals() = runBlocking {
        val a = SyncTestDevice("A")
        val b = SyncTestDevice("B")
        seedRows(a)
        seedRows(b)

        // 1. A 建行 X（seq1）；2. 双向同步（B 观察到 v1）
        a.localUpsert(RowKind.ENTRY, "X", entryPayload(100, "A-v1"), baseSeq = null)
        b.applier.applyRemote(a.allOps())
        a.applier.applyRemote(b.allOps())

        // 3. B 离线串行改（seq2，观察到 v1）；4. A 也不知情串行改（seq2）——两者并发
        b.localUpsert(RowKind.ENTRY, "X", entryPayload(200, "B-v2"), baseSeq = 1)
        val aV1 = a.dao.opLog.values.first { it.rowSyncId == "X" && it.actorId == "A" && it.seq == 1L }
        val aV2 = a.localUpsert(RowKind.ENTRY, "X", entryPayload(300, "A-v2"), baseSeq = 1)

        // 5. A 删除 X（baseSeq=2，观察到 seq ≤ 2 的 v1/v2；观察不到 B 的 v2'——还没到）
        a.localDelete(RowKind.ENTRY, "X", baseSeq = 2, snapshot = entryPayload(300, "A-v2"))

        // 6. B 仍离线，接着自己的版本继续改（seq3，观察到 B-v2）——DELETE.baseSeq=2 杀不死它
        b.localUpsert(RowKind.ENTRY, "X", entryPayload(400, "B-v3幸存"), baseSeq = 2)

        // 7. 裁剪 A：此刻 A 只见过自己的 v1/v2（B 的操作尚未到达），行胜者 = A-v2 保底，
        //    只有 v1 被裁；DELETE 保留（B 的 v2' 到达后取代 A-v2 成为新胜者，见第 8 步收敛）
        val beforeIds = a.dao.opLog.keys.toSet()
        trimDevice(a)
        val afterIds = a.dao.opLog.keys.toSet()
        assertEquals(
            "裁剪应恰好去掉非胜者 v1 一条",
            setOf(aV1.opId),
            beforeIds - afterIds,
        )
        assertNotNull("行胜者 A-v2 保底", a.dao.getOp(aV2.opId))
        for (row in a.dao.opLog.values.map { it.rowKind to it.rowSyncId }.distinct()) {
            assertTrue(
                "裁剪后每行操作数 ≥ 1（引用判定保底）：$row",
                a.dao.countOpsOfRow(row.first, row.second) > 0,
            )
        }

        // 8. 双向同步：B-v3（seq3 > DELETE.baseSeq=2）在两机都必须幸存
        a.applier.applyRemote(b.allOps())
        b.applier.applyRemote(a.allOps())

        assertEquals("裁剪过的 A 与未裁的 B 必须收敛", a.state(), b.state())
        assertEquals(
            "X 在裁过的 A 上以幸存版本复活",
            "B-v3幸存",
            a.store.rows[RowKind.ENTRY to "X"]?.payload?.optString("note"),
        )
        assertEquals(
            "X 在 B 上同样以幸存版本存活",
            "B-v3幸存",
            b.store.rows[RowKind.ENTRY to "X"]?.payload?.optString("note"),
        )
        assertTrue("无孤儿引用", a.store.orphanReferences().isEmpty())
        assertTrue(b.store.orphanReferences().isEmpty())
    }

    @Test
    fun refsToDeadRowsStillResolveAsPlaceholderAfterTrim() = runBlocking {
        val a = SyncTestDevice("A")
        seedRows(a)
        // 引用死分类的场景要先有一个「到过已死」的分类：建 C2 再删
        a.localUpsert(RowKind.CATEGORY, "C2", OpCodec.categorySnapshot("书籍", 3, 0, null, 0), baseSeq = null)
        a.localDelete(RowKind.CATEGORY, "C2", baseSeq = 1, snapshot = OpCodec.categorySnapshot("书籍", 3, 0, null, 0))
        trimDevice(a)
        // 裁剪后 C2 的操作全集仍有保底（胜者 + DELETE），引用判定必须走「到过已死 → 占位」
        assertTrue("C2 操作全集不得被裁空", a.dao.countOpsOfRow(RowKind.CATEGORY.value, "C2") > 0)

        val entryRefDead = com.simpleledger.app.sync.op.SyncOp(
            "e-ref-dead", RowKind.ENTRY, "E1", com.simpleledger.app.sync.op.OpType.UPSERT, "B", null,
            1, null, entryPayload(500, "引用死分类", categorySyncId = "C2"), 1_000L,
        )
        val result = a.applier.applyRemote(listOf(entryRefDead))
        assertEquals("引用死分类应占位落库而非挂起", 0, result.deferred)
        val row = a.store.rows[RowKind.ENTRY to "E1"]
        assertEquals("死分类引用按 0 占位（未分类口径）", "0", row?.payload?.optString("categorySyncId"))
    }

    @Test
    fun liveRowEditsConvergeNormallyAfterWinnerRetention() = runBlocking {
        val a = SyncTestDevice("A")
        val b = SyncTestDevice("B")
        seedRows(a)
        seedRows(b)
        a.localUpsert(RowKind.ENTRY, "Y", entryPayload(100, "v1"), baseSeq = null)
        b.applier.applyRemote(a.allOps())
        a.applier.applyRemote(b.allOps())
        trimDevice(a)
        // 裁剪后（Y 只剩胜者 v1），B 的普通编辑照常收敛
        b.localUpsert(RowKind.ENTRY, "Y", entryPayload(200, "v2"), baseSeq = 1)
        a.applier.applyRemote(b.allOps())
        b.applier.applyRemote(a.allOps())
        assertEquals(a.state(), b.state())
        assertEquals(
            "Y 胜者应为 v2",
            "v2",
            a.store.rows[RowKind.ENTRY to "Y"]?.payload?.optString("note"),
        )
    }
}
