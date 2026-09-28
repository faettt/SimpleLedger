package com.simpleledger.app.sync

import com.simpleledger.app.data.local.entity.SyncOpEntity
import com.simpleledger.app.sync.account.WebDavCred
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.op.OpRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-12 裁剪挂钩实测（[SyncManager.syncNow] 成功收尾）：只裁「非 outbox + 已应用 +
 * 超保留期」的非胜者 UPSERT；失败 / 跳过轮不裁。引擎注假件（不触网），
 * DAO 走内存实现，裁剪口径与生产 SQL 逐条对齐。
 */
class OpTrimTest {

    @Test
    fun syncNowTrimsOnlyOldNonWinnerUpserts() = runBlocking {
        val dao = FakeSyncDao()
        val store = FakeSyncStore()
        // 行 X：v1（老，非胜者）、v2（老，胜者）、d1（DELETE）；行 Y：v3（保留期内的新操作）
        dao.insertOp(op("v1", seq = 1, createdAt = 1_000L))
        dao.insertOp(op("v2", seq = 2, createdAt = 2_000L))
        dao.insertOp(op("d1", opType = "DELETE", seq = 0, baseSeq = 2L, createdAt = 3_000L))
        dao.insertOp(op("v3", rowSyncId = "Y", seq = 1, createdAt = System.currentTimeMillis()))
        // 保底三件：outbox（LOCAL 未上传）、挂起集（未应用）、TRASH_ACT
        dao.insertOp(op("out-1", uploaded = false, createdAt = 1_500L))
        dao.insertOp(op("def-1", applied = false, uploaded = true, createdAt = 1_600L))
        dao.insertOp(op("act-1", opType = "TRASH_ACT", rowSyncId = "t-1", seq = 0, createdAt = 1_700L))

        val outcome = fixture(dao, store).syncNow(SyncTrigger.MANUAL)

        assertTrue(outcome.success)
        val tracked = listOf("v1", "v2", "d1", "v3", "out-1", "def-1", "act-1")
        val missing = tracked.filter { dao.getOp(it) == null }
        assertEquals("只裁掉 v1 一条（实缺：$missing）", 1, missing.size)
        assertNull("老非胜者 UPSERT 应被裁", dao.getOp("v1"))
        assertNotNull("每行胜者保底", dao.getOp("v2"))
        assertNotNull("DELETE 永不裁", dao.getOp("d1"))
        assertNotNull("保留期内的新操作不裁", dao.getOp("v3"))
        assertNotNull("outbox 绝不裁", dao.getOp("out-1"))
        assertNotNull("挂起集绝不裁", dao.getOp("def-1"))
        assertNotNull("TRASH_ACT 永不裁", dao.getOp("act-1"))

        // 幂等：第二轮无可裁
        fixture(dao, store).syncNow(SyncTrigger.MANUAL)
        assertNull(dao.getOp("v1"))
        assertNotNull(dao.getOp("v2"))
    }

    @Test
    fun syncNowDoesNotTrimOnFailedOrSkippedRound() = runBlocking {
        val dao = FakeSyncDao()
        val store = FakeSyncStore()
        dao.insertOp(op("v1", seq = 1, createdAt = 1_000L))
        dao.insertOp(op("v2", seq = 2, createdAt = 2_000L))

        // 失败轮不裁（U-12 只建立在「本轮账已对齐」的前提上）
        manager(dao, store, SyncOutcome(success = false, error = SyncError.NETWORK))
            .syncNow(SyncTrigger.MANUAL)
        assertNotNull("失败轮不裁", dao.getOp("v1"))

        // 跳过轮（未配置 / 单飞占用）不裁
        manager(dao, store, SyncOutcome.SKIPPED).syncNow(SyncTrigger.MANUAL)
        assertNotNull("跳过轮不裁", dao.getOp("v1"))
    }

    // ------------------------------------------------------------ 夹具

    private suspend fun countMissing(dao: FakeSyncDao, opIds: List<String>): Int =
        opIds.count { dao.getOp(it) == null }

    private fun fixture(dao: FakeSyncDao, store: FakeSyncStore): SyncManager =
        manager(dao, store, SyncOutcome(success = true))

    private fun manager(dao: FakeSyncDao, store: FakeSyncStore, outcome: SyncOutcome): SyncManager {
        val engine = FakeSyncRunner().apply { nextOutcome = outcome }
        return SyncManager(
            engine = engine,
            store = store,
            account = FakeAccountStore(WebDavCred("https://dav.example.com/", "u", "p")),
            crypto = SyncCrypto(),
            syncDao = dao,
            recorder = OpRecorder(dao, { "dev-test" }, { store.selfMemberId }),
            exporter = FakeInitialExporter(0),
            tx = FakeTx(listOf(dao)),
            davFactory = { error("裁剪测试不应触网") },
            remoteFactory = { _, _, _ -> error("裁剪测试不应触网") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun op(
        opId: String,
        rowSyncId: String = "X",
        opType: String = "UPSERT",
        seq: Long = 1L,
        baseSeq: Long? = null,
        createdAt: Long,
        uploaded: Boolean = true,
        applied: Boolean = true,
    ) = SyncOpEntity(
        opId = opId,
        rowKind = "ENTRY",
        rowSyncId = rowSyncId,
        opType = opType,
        actorId = "A",
        memberId = null,
        seq = seq,
        baseSeq = baseSeq,
        payload = "{}",
        origin = "LOCAL",
        applied = applied,
        uploaded = uploaded,
        chunkName = null,
        createdAt = createdAt,
    )
}
