package com.simpleledger.app.sync

import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.TrashKind
import com.simpleledger.app.data.local.entity.TrashResolved
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpType
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.op.SyncOp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * OpMerge / OpRecorder / OpApplier 联合测试（T-3 收敛矩阵，11 用例零回归）。
 *
 * 定案口径：
 *  -「U_live = {u | ¬∃d: d.baseSeq ≥ u.seq}」——被更晚 OVERWRITE/DELETE 的 baseSeq 覆盖的早期 UPSERT 不复活。
 *  - LWW 全序 RowVersion(seq, actorId) + opId 破平；分层应用序 SECTION→CATEGORY→ENTRY→IMAGE→MEMBER→SETTING→TRASH。
 *  - 引用三分判定：正常；被引 rowSyncId 曾出现但已死 → 0 占位（CATEGORY 降级全局）；真未到 → 挂起 deferred。
 *  - TOMBSTONE 双写（墓碑行 + TRASH 观察行）使删除可见可还原（restoreTrashEntry / purgeTrashEntry）。
 *
 * 共用夹具（FakeTx/FakeSyncDao/FakeRowStore/FaultyRowStore/SyncTestDevice）见 SyncTestFakes.kt。
 */
private typealias Device = SyncTestDevice

class OpMergeTest {

    /** 口径：两机并发无因果 → 并集（ADD-WINS）。 */
    @Test
    fun concurrent_adds_both_survive() = runBlocking {
        val a = Device("A")
        val b = Device("B")
        a.localUpsert(RowKind.SECTION, "sec-1", sectionPayload("生活"), baseSeq = null)
        b.localUpsert(RowKind.SECTION, "sec-2", sectionPayload("工作"), baseSeq = null)
        a.receiveFrom(b)
        b.receiveFrom(a)
        assertEquals(2, a.store.rows.count { it.key.first == RowKind.SECTION })
        assertEquals(2, b.store.rows.count { it.key.first == RowKind.SECTION })
        assertEquals(a.state(), b.state())
    }

    /** 口径：有因果（delete.baseSeq ≥ u.seq）→ 被观察过的早期 UPSERT 不复活（Tombstone-wins 局部）。 */
    @Test
    fun delete_beats_prior_upsert_with_causal_base() = runBlocking {
        val a = Device("A")
        val b = Device("B")
        seedBoth(a, b)
        val snap = entryPayload(amount = 100)
        a.localUpsert(RowKind.ENTRY, "e-1", snap, baseSeq = null) // u.seq=1
        b.receiveFrom(a)
        b.localDelete(RowKind.ENTRY, "e-1", baseSeq = 1, snapshot = snap) // d.baseSeq=1 ≥ 1 → 观察过
        a.receiveFrom(b)
        assertTrue(a.store.rows.keys.none { it.second == "e-1" })
        assertTrue(b.store.rows.keys.none { it.second == "e-1" })
        assertEquals(a.state(), b.state())
    }

    /** 口径：A 删 ∥ B 改（互不覆盖）→ 双留：删除墓碑（conflict=true）+ 修改存活，两机一致。 */
    @Test
    fun delete_vs_edit_concurrent_double_survive() = runBlocking {
        val a = Device("A")
        val b = Device("B")
        seedBoth(a, b)
        val snap1 = entryPayload(amount = 100, note = "原")
        val snap2 = entryPayload(amount = 100, note = "改")
        a.localUpsert(RowKind.ENTRY, "e-1", snap1, baseSeq = null) // u1.seq=1
        b.receiveFrom(a)
        a.localDelete(RowKind.ENTRY, "e-1", baseSeq = 1, snapshot = snap1) // d 不观察 u2
        b.localUpsert(RowKind.ENTRY, "e-1", snap2, baseSeq = 1) // u2.seq=2 并发
        a.receiveFrom(b)
        b.receiveFrom(a)

        val rowA = a.store.rows[RowKind.ENTRY to "e-1"]
        val rowB = b.store.rows[RowKind.ENTRY to "e-1"]
        assertEquals("改", rowA?.payload?.optString("note"))
        assertEquals("改", rowB?.payload?.optString("note"))
        val trash = a.dao.trashTable.values.single { it.rowSyncId == "e-1" }
        assertEquals(TrashKind.DELETE, trash.kind)
        assertTrue("删改冲突双留：行存活 ⇒ conflict=true", trash.conflict)
        assertEquals(1, b.dao.trashTable.size)
        assertEquals(a.state(), b.state())
    }

    /** 口径：同 seq 两机并发编辑 → LWW 全序（seq, actorId, opId）破平，败者进 OVERWRITE 留底（U-3）。 */
    @Test
    fun lww_field_level_tiebreak_total_order() = runBlocking {
        val a = Device("A")
        val b = Device("B")
        seedBoth(a, b)
        val snap = entryPayload(amount = 100)
        a.localUpsert(RowKind.ENTRY, "e-1", snap, baseSeq = null) // u1.seq=1
        b.receiveFrom(a)
        val editA = a.localUpsert(RowKind.ENTRY, "e-1", entryPayload(amount = 200), baseSeq = 1) // seq=2
        val editB = b.localUpsert(RowKind.ENTRY, "e-1", entryPayload(amount = 300), baseSeq = 1) // seq=2
        a.receiveFrom(b)
        b.receiveFrom(a)

        // actorId "B" > "A" ⇒ B 胜；A 的并发败版进 OVERWRITE 留底（串行覆盖的 u1 不留底）
        assertEquals(300L, a.store.rows[RowKind.ENTRY to "e-1"]?.payload?.optLong("amountCents"))
        assertEquals(
            canonicalJson(entryPayload(amount = 300)),
            canonicalJson(b.store.rows[RowKind.ENTRY to "e-1"]!!.payload),
        )
        assertTrue(a.dao.trashTable.values.any { it.kind == TrashKind.OVERWRITE && it.deleteOpId == editA.opId })
        assertTrue(a.dao.trashTable.values.none { it.deleteOpId == editB.opId })
        assertTrue(a.dao.trashTable.values.none { it.kind == TrashKind.OVERWRITE && it.deleteOpId != editA.opId })
        assertEquals(a.state(), b.state())
    }

    /** 口径：存量导出确定性 opId——同内容两机导出同 id 折叠幂等，异内容异 id 不冲突。 */
    @Test
    fun deterministic_opid_idempotent_across_devices() = runBlocking {
        val src = FakeExportSource(sections = listOf(exportSection("sec-1", "生活")))
        val daoA = FakeSyncDao()
        val expA = RoomInitialExporter(src, daoA, FakeTx(listOf(daoA)), { "A" }, { null })
        assertEquals(1, expA.export())
        assertEquals("重复导出零副作用", 0, expA.export())

        val daoB = FakeSyncDao()
        val expB = RoomInitialExporter(src, daoB, FakeTx(listOf(daoB)), { "B" }, { null })
        assertEquals(1, expB.export())
        val idA = daoA.opLog.keys.single()
        val idB = daoB.opLog.keys.single()
        assertEquals("同内容跨设备同 opId → INSERT OR IGNORE 折叠", idA, idB)
        assertTrue(idA.startsWith("export-sec-1-"))

        // 异内容（U-5 迁移改名行）→ 异 opId，LWW 双留不丢
        val daoC = FakeSyncDao()
        val srcC = FakeExportSource(sections = listOf(exportSection("sec-1", "改过")))
        val expC = RoomInitialExporter(srcC, daoC, FakeTx(listOf(daoC)), { "C" }, { null })
        assertEquals(1, expC.export())
        assertNotEquals(idA, daoC.opLog.keys.single())
    }

    /** 口径：被引 entry 未到 → deferred 挂起，entry 到达后 retryDeferred 回补。 */
    @Test
    fun deferred_image_until_entry_arrives() = runBlocking {
        val a = Device("A")
        val b = Device("B")
        seedBoth(a, b)
        a.localUpsert(RowKind.ENTRY, "e-1", entryPayload(amount = 100), baseSeq = null)
        a.localUpsert(RowKind.IMAGE, "img-1", OpCodec.imageSnapshot("e-1", "h", 0), baseSeq = null)

        // B 先只收 image（entry 未到）→ 整行挂起
        val imageResult = b.applier.applyRemote(a.allOps().filter { it.rowSyncId == "img-1" })
        assertEquals(1, imageResult.deferred)
        assertEquals(1, b.dao.countDeferredOps())
        assertTrue("挂起不物化行投影（种子行之外无 IMAGE）", b.store.rows.keys.none { it.first == RowKind.IMAGE })

        // entry 到达 → retryDeferred 回补
        b.applier.applyRemote(a.allOps().filter { it.rowSyncId == "e-1" })
        val retry = b.applier.retryDeferred()
        assertEquals(1, retry.applied)
        assertEquals(0, retry.deferred)
        assertEquals(1, b.store.rows.count { it.key.first == RowKind.IMAGE })
        assertEquals(a.state(), b.state())
    }

    /** 口径：引用三分——被引 rowSyncId 曾出现但已死 → 0 占位（不挂起、不丢、无孤儿）。 */
    @Test
    fun dead_entry_reference_resolves_to_zero_placeholder() = runBlocking {
        val a = Device("A")
        val b = Device("B")
        seedBoth(a, b)
        val snap = entryPayload(amount = 100)
        a.localUpsert(RowKind.ENTRY, "e-1", snap, baseSeq = null)
        a.localDelete(RowKind.ENTRY, "e-1", baseSeq = 1, snapshot = snap) // e-1 已死（曾在场）
        a.localUpsert(RowKind.IMAGE, "img-1", OpCodec.imageSnapshot("e-1", "h", 0), baseSeq = null)
        b.receiveFrom(a)

        assertEquals("0", b.store.rows[RowKind.IMAGE to "img-1"]?.payload?.getString("entrySyncId"))
        assertEquals("0", a.store.rows[RowKind.IMAGE to "img-1"]?.payload?.getString("entrySyncId"))
        assertTrue(b.store.orphanReferences().isEmpty())
        assertTrue(a.store.orphanReferences().isEmpty())
        assertEquals(a.state(), b.state())
    }

    /** 口径：真未引用（从未出现过的 rowSyncId）→ 挂起，不占位。 */
    @Test
    fun never_seen_reference_stays_deferred() = runBlocking {
        val b = Device("B")
        val op = SyncOp(
            opId = "op-1",
            rowKind = RowKind.IMAGE,
            rowSyncId = "img-1",
            opType = OpType.UPSERT,
            actorId = "A",
            memberId = null,
            seq = 3,
            baseSeq = null,
            payload = OpCodec.imageSnapshot("nope", "h", 0),
            createdAt = 1L,
        )
        val result = b.applier.applyRemote(listOf(op))
        assertEquals(1, result.deferred)
        assertTrue(b.store.rows.isEmpty()) // 挂起不物化
        assertEquals(1, b.dao.countDeferredOps()) // 操作落账（applied=0）供下轮重试
    }

    /** TOMBSTONE 双写：删除可见可还原——restore 复活（串行回插），purge 推进 PURGED 留底。 */
    @Test
    fun tombstone_double_write_restore_and_purge() = runBlocking {
        val a = Device("A")
        seedBoth(a, a)
        val snap = entryPayload(amount = 100)
        a.localUpsert(RowKind.ENTRY, "e-1", snap, baseSeq = null)
        val del1 = a.localDelete(RowKind.ENTRY, "e-1", baseSeq = 1, snapshot = snap)
        assertEquals(1, a.dao.trashTable.size)

        // 恢复：TRASH_ACT(RESTORE) + 串行回插 UPSERT（baseSeq = maxSeqOf）→ 行复活、留底推进 RESTORED
        assertTrue(a.applier.restoreTrashEntry(a.recorder, del1.opId))
        assertEquals(1, a.store.rows.count { it.key.second == "e-1" })
        assertEquals(TrashResolved.RESTORED, a.dao.trashTable[del1.opId]?.resolved)

        // 彻底删除：另一行删除后 purge → 留底推进 PURGED、行保持死亡
        a.localUpsert(RowKind.ENTRY, "e-2", snap, baseSeq = null)
        val del2 = a.localDelete(RowKind.ENTRY, "e-2", baseSeq = 1, snapshot = snap)
        assertTrue(a.applier.purgeTrashEntry(a.recorder, del2.opId))
        assertTrue(a.store.rows.keys.none { it.second == "e-2" })
        assertEquals(TrashResolved.PURGED, a.dao.trashTable[del2.opId]?.resolved)
    }

    /**
     * U-13 回归：迁移/种子行（versionSeq = 0）被删后恢复——回插不得落进伪造 baseSeq 的观察范围。
     *
     * 链条：v4 迁移/种子行 versionSeq = 0，存量导出以 seq = 0 声明存在；写路径对 DELETE
     * baseSeq `coerceAtLeast(1L)` ⇒ 该行的 DELETE.baseSeq = 1 而行内 MAX(seq) = 0。
     * 旧实现回插 seq = maxSeqOf + 1 = 1 ⇒ observed(1, 1) = true ⇒ live = ∅ → removeRow，
     * 而留底已先翻 RESTORED——恢复静默失败且内容处处不可见。修复后回插
     * seq = max(MAX(seq), DELETE.baseSeq 最大值) + 1 = 2，恒晚于删除声称观察到的版本。
     */
    @Test
    fun restore_of_migrated_v0_row_survives_faked_delete_baseSeq() = runBlocking {
        val a = Device("A")
        // 真实 v0 存量库形态：引用的分区/分类种子行也在（entryPayload 引用 sec-1/cat-1）
        a.seedRow(RowKind.SECTION, "sec-1", sectionPayload("生活"), versionSeq = 0L)
        a.seedRow(RowKind.CATEGORY, "cat-1", categoryPayload("吃"), versionSeq = 0L)
        a.seedRow(RowKind.ENTRY, "e-0", entryPayload(amount = 77), versionSeq = 0L)
        val snap = entryPayload(amount = 77)
        // 写路径口径：v0 行删除时 baseSeq 被 coerce 抬为 1（行内 MAX(seq) 仍 = 0）
        val del = a.localDelete(RowKind.ENTRY, "e-0", baseSeq = 1, snapshot = snap)
        assertEquals(0L, a.dao.maxSeqOf(RowKind.ENTRY.value, "e-0"))

        assertTrue(a.applier.restoreTrashEntry(a.recorder, del.opId))
        assertTrue("恢复后行必须复活（旧实现被 observed(1,1) 判死静默丢失）", a.store.exists(RowKind.ENTRY, "e-0"))
        assertEquals(
            "回插 seq 必须严格晚于删除声称观察到的 baseSeq",
            2L,
            a.dao.maxSeqOf(RowKind.ENTRY.value, "e-0"),
        )
        assertEquals(TrashResolved.RESTORED, a.dao.trashTable[del.opId]?.resolved)
        assertEquals("恢复内容 = 删除时快照", "77", a.store.rows[RowKind.ENTRY to "e-0"]?.payload?.optString("amountCents"))
    }

    /** 口径：每事务原子——第 2 次行写注入故障 → 整轮回滚（操作账/行投影零脏行）。 */
    @Test
    fun transaction_rollback_on_failure() = runBlocking {
        val dao = FakeSyncDao()
        val backing = FakeRowStore(dao)
        val faulty = FaultyRowStore(backing, failAtWrite = 2)
        val tx = FakeTx(listOf(dao, backing))
        val applier = com.simpleledger.app.sync.op.OpApplier(dao, faulty, tx)
        val ops = listOf(
            SyncOp("op-1", RowKind.SECTION, "r1", OpType.UPSERT, "B", null, 5, null, sectionPayload("一"), 1L),
            SyncOp("op-2", RowKind.SECTION, "r2", OpType.UPSERT, "B", null, 5, null, sectionPayload("二"), 2L),
        )
        try {
            applier.applyRemote(ops)
            fail("第 2 次行写应抛出注入故障")
        } catch (e: IllegalStateException) {
            // 预期：注入故障冒泡，事务整体回滚
        }
        assertEquals(1, tx.rollbacks)
        assertTrue("回滚后操作账零脏行", dao.opLog.isEmpty())
        assertTrue("回滚后行投影零脏行", backing.rows.isEmpty())
    }

    /** 口径：删除传播与他机收敛——A 删，B 收敛后行死、TRASH 观察行两机各一且同键。 */
    @Test
    fun delete_propagates_and_tombstone_visible() = runBlocking {
        val a = Device("A")
        val b = Device("B")
        val snap = categoryPayload("吃")
        a.localUpsert(RowKind.CATEGORY, "c-1", snap, baseSeq = null)
        b.receiveFrom(a)
        a.localDelete(RowKind.CATEGORY, "c-1", baseSeq = 1, snapshot = snap)
        b.receiveFrom(a)

        assertTrue(a.store.rows.keys.none { it.second == "c-1" })
        assertTrue(b.store.rows.keys.none { it.second == "c-1" })
        assertEquals(1, a.dao.trashTable.size)
        assertEquals(1, b.dao.trashTable.size)
        assertEquals(a.dao.trashTable.keys, b.dao.trashTable.keys) // 同 deleteOpId 幂等键
        assertEquals(a.state(), b.state())
    }

    // ------------------------------------------------------------------ 夹具

    /** 两机播下同一组种子行（无操作的 v0 行，对齐 v5 种子/存量） */
    private fun seedBoth(a: Device, b: Device) {
        for (d in listOf(a, b)) {
            d.seedRow(RowKind.SECTION, "sec-1", sectionPayload("生活"))
            d.seedRow(RowKind.CATEGORY, "cat-1", categoryPayload("吃"))
        }
    }
}

/* ------------------------------------------------------------ 载荷工厂 */

private fun sectionPayload(name: String) =
    OpCodec.sectionSnapshot(name = name, iconId = 1, note = "", budgetCents = 0, colorIndex = 0, sortOrder = 0, createdAt = 1_000L)

private fun categoryPayload(name: String) =
    OpCodec.categorySnapshot(name = name, iconId = 2, type = 0, sectionSyncId = null, sortOrder = 0)

private fun entryPayload(amount: Long, note: String = "") = OpCodec.entrySnapshot(
    type = 0, amountCents = amount, categorySyncId = "cat-1", sectionSyncId = "sec-1",
    entryTime = 1_000L, note = note, reconciled = false, reimburseState = 0,
    createdAt = 1_000L, updatedAt = 1_000L, memberSyncId = null,
)

private fun exportSection(syncId: String, name: String) = SectionEntity(
    id = 1L,
    name = name,
    iconId = 1,
    note = "",
    budgetCents = 0,
    colorIndex = 0,
    sortOrder = 0,
    createdAt = 1_000L,
    syncId = syncId,
    versionSeq = 0L,
    updatedAt = 1_000L,
)
