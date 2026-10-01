package com.simpleledger.app.sync

import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.SyncOpEntity
import com.simpleledger.app.sync.account.WebDavCred
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.op.TrashAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-1「写后触发同步」验收：`OpRecorder` 三个写入口成功收尾 → 注入回调 →
 * `SyncManager.requestSync(AFTER_WRITE)`。
 *
 * 覆盖三件事：
 * 1. 三个写入口各自触发一次，且**先落账后触发**；写失败（事务将回滚）不触发；
 *    回调缺省 / 回调抛错都不得影响本地写（事务内旁路，S6 口径）。
 * 2. AFTER_WRITE 走独立合并窗口：连续多笔写只排一轮、窗口内不起跑；
 * 3. 两个窗口互不干扰：写后触发不受 60s 自动去抖压制，也不消耗自动窗口。
 *
 * 合并窗口用**生产常量** [SyncManager.WRITE_DEBOUNCE_MILLIS] 真实等待（不做测试专用注入口，
 * 免得测的是另一个值）。三处等待共约 9s，断言用轮询 + 超时上限，不做裸 sleep 定时。
 */
class WriteTriggeredSyncTest {

    // ------------------------------------------------------------ 写入口触发（OpRecorder 侧）

    /** 三个写入口：每个成功收尾都触发一次，且触发发生在「操作确已落账」之后 */
    @Test
    fun eachWriteEntryTriggersOnceAfterTheOperationLands() = runBlocking {
        val dao = FakeSyncDao()
        val hits = AtomicInteger()
        val recorder = OpRecorder(dao, { "dev-test" }, { "member-1" }) { hits.incrementAndGet() }

        val entrySnapshot = OpCodec.entrySnapshot(
            type = 0, amountCents = 1200, categorySyncId = "cat-1", sectionSyncId = "sec-1",
            entryTime = 1_000L, note = "午饭", reconciled = false, reimburseState = 0,
            createdAt = 1_000L, updatedAt = 1_000L, memberSyncId = null,
        )

        // ① 记账（新建）
        val upOpId = recorder.onUpsert(
            rowKind = RowKind.ENTRY, rowSyncId = "e-1", seq = 1L, baseSeq = null, snapshot = entrySnapshot,
        )
        assertEquals("onUpsert 成功即触发一次", 1, hits.get())
        assertNotNull("先落账后触发：操作确实在库", dao.getOp(upOpId))

        // ② 删除（observed-remove）
        val delOpId = recorder.onDelete(
            rowKind = RowKind.ENTRY, rowSyncId = "e-1", baseSeq = 1L,
            snapshot = OpCodec.withDeletedAt(entrySnapshot, 2_000L),
        )
        assertEquals("onDelete 成功即触发一次", 2, hits.get())
        assertNotNull(dao.getOp(delOpId))
        assertNotNull("删除推导出留底（收尾触发不早于留底推导）", dao.getTrash(delOpId))

        // ③ 回收站动作（彻底删除）
        val actOpId = recorder.onTrashAct(delOpId, TrashAction.PURGE, restoreSnapshot = null)
        assertEquals("onTrashAct 成功即触发一次", 3, hits.get())
        assertNotNull(dao.getOp(actOpId))
    }

    /** 写失败（DAO 抛错 ⇒ 调用方事务整体回滚）**不得**触发同步，否则会同步一笔不存在的操作 */
    @Test
    fun failedWriteDoesNotTrigger() = runBlocking {
        val hits = AtomicInteger()
        val recorder = OpRecorder(FailingInsertDao(FakeSyncDao()), { "dev-test" }, { null }) { hits.incrementAndGet() }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                recorder.onUpsert(
                    rowKind = RowKind.ENTRY, rowSyncId = "e-1", seq = 1L, baseSeq = null,
                    snapshot = OpCodec.entrySnapshot(
                        type = 0, amountCents = 100, categorySyncId = "c", sectionSyncId = "s",
                        entryTime = 1_000L, note = "", reconciled = false, reimburseState = 0,
                        createdAt = 1_000L, updatedAt = 1_000L, memberSyncId = null,
                    ),
                )
            }
        }
        assertEquals("写失败路径不得触发同步", 0, hits.get())
    }

    /**
     * 回调抛错必须被吞掉：它在业务事务内执行，异常穿回 `db.withTransaction` 会把用户
     * 刚写好的账整体回滚（比不同步严重得多）。断言「不抛 + 操作照常落账」。
     */
    @Test
    fun afterWriteCallbackFailureNeverBreaksTheLocalWrite() = runBlocking {
        val dao = FakeSyncDao()
        val recorder = OpRecorder(dao, { "dev-test" }, { null }) { error("回调注入故障") }
        val opId = recorder.onUpsert(
            rowKind = RowKind.SECTION, rowSyncId = "sec-1", seq = 1L, baseSeq = null,
            snapshot = OpCodec.sectionSnapshot("生活", 1, "", 0, 0, 0, 1_000L),
        )
        assertNotNull("回调异常被吞：本地写照常返回且落账", dao.getOp(opId))
    }

    /** 未接线（缺省 no-op）也必须安全降级：JVM 单测与任何未接回调的装配路径共用构造器 */
    @Test
    fun recorderWithoutCallbackDegradesToNoop() = runBlocking {
        val dao = FakeSyncDao()
        val recorder = OpRecorder(dao, { "dev-test" }, { null })
        val opId = recorder.onUpsert(
            rowKind = RowKind.SECTION, rowSyncId = "sec-1", seq = 1L, baseSeq = null,
            snapshot = OpCodec.sectionSnapshot("生活", 1, "", 0, 0, 0, 1_000L),
        )
        assertNotNull("缺省回调：照常记操作，不触发同步也不抛", dao.getOp(opId))
    }

    // ------------------------------------------------------------ AFTER_WRITE 合并窗口

    /** 连续多笔写只排一轮；窗口（3s）内不起跑（写路径在事务内，过早同步读不到刚记的操作） */
    @Test
    fun afterWriteBurstCoalescesIntoOneRound() {
        val fx = ManagerFixture().also { it.configure() }
        try {
            assertEquals("合并窗口 = 3s（生产常量）", 3_000L, SyncManager.WRITE_DEBOUNCE_MILLIS)

            fx.manager.requestSync(SyncTrigger.AFTER_WRITE)
            fx.manager.requestSync(SyncTrigger.AFTER_WRITE)
            fx.manager.requestSync(SyncTrigger.AFTER_WRITE)

            assertEquals("窗口未到不得起跑（业务事务尚未提交）", 0, fx.engine.calls)
            awaitCalls(fx.engine, 1)
            Thread.sleep(200) // 若实现「每笔写各排一轮」，多出来的轮次会在此显形
            assertEquals("三连写只合并成一轮", 1, fx.engine.calls)
            assertEquals(listOf(SyncTrigger.AFTER_WRITE), fx.engine.triggers)
        } finally {
            fx.scope.cancel()
        }
    }

    /** 60s 自动去抖窗口开着时，写后触发照跑（两者各用各的计时，互不压制） */
    @Test
    fun afterWriteIsNotSuppressedByTheAutoDebounceWindow() {
        val fx = ManagerFixture().also { it.configure() }
        try {
            fx.manager.requestSync(SyncTrigger.FOREGROUND)
            fx.manager.requestSync(SyncTrigger.COLD_START)
            assertEquals("自动触发 60s 去抖：回前台后冷启动被压制", 1, fx.engine.calls)

            fx.manager.requestSync(SyncTrigger.AFTER_WRITE) // 自动窗口还开着，但写后触发独立
            awaitCalls(fx.engine, 2)
            assertEquals(listOf(SyncTrigger.FOREGROUND, SyncTrigger.AFTER_WRITE), fx.engine.triggers)
        } finally {
            fx.scope.cancel()
        }
    }

    /** 反向：写后触发排程不占用自动窗口——紧接着的自动触发该立即跑就跑（不被饿到 60s 后） */
    @Test
    fun afterWriteWindowDoesNotConsumeTheAutoDebounceWindow() {
        val fx = ManagerFixture().also { it.configure() }
        try {
            fx.manager.requestSync(SyncTrigger.AFTER_WRITE) // 只排程，不动自动计时
            fx.manager.requestSync(SyncTrigger.PERIODIC) // 自动窗口仍是空的 → 立即起跑
            assertEquals("写后触发不得占用自动去抖窗口", 1, fx.engine.calls)
            assertEquals(listOf(SyncTrigger.PERIODIC), fx.engine.triggers)
        } finally {
            fx.scope.cancel()
        }
    }

    /** 未配置同步：写后触发同样静默（S6），等满一个窗口也不排程 */
    @Test
    fun afterWriteIsSilentWhenSyncIsNotConfigured() {
        val fx = ManagerFixture() // 无凭证 / 无派生密钥
        try {
            fx.manager.requestSync(SyncTrigger.AFTER_WRITE)
            Thread.sleep(SyncManager.WRITE_DEBOUNCE_MILLIS + 300L)
            assertEquals("未配置：不排程、不跑引擎", 0, fx.engine.calls)
        } finally {
            fx.scope.cancel()
        }
    }

    // ------------------------------------------------------------ skipReason 在门面侧不被吞

    /** 引擎回传的跳过成因必须原样透出（UI 的分因措辞依赖这一层不丢字段） */
    @Test
    fun syncNowPassesSkipReasonThrough() = runBlocking {
        val fx = ManagerFixture().also { it.configure() }
        try {
            fx.engine.nextOutcome = SyncOutcome.skip(SkipReason.IN_FLIGHT)
            val outcome = fx.manager.syncNow(SyncTrigger.MANUAL)
            assertEquals(SkipReason.IN_FLIGHT, outcome.skipReason)
            assertTrue("门面侧 skipped 语义不变", outcome.skipped)
        } finally {
            fx.scope.cancel()
        }
    }

    // ------------------------------------------------------------ 夹具

    /**
     * [FakeSyncRunner] + 触发序列记录（组合而非改共用件 `SyncTestFakes.kt`：
     * 多人并行时少一个共同编辑点）。
     */
    private class RecordingRunner : SyncRunner {
        private val fake = FakeSyncRunner()

        /** 每次真正进入引擎的触发（顺序即调用顺序） */
        val triggers: MutableList<SyncTrigger> = Collections.synchronizedList(mutableListOf<SyncTrigger>())

        val calls: Int get() = fake.calls

        var nextOutcome: SyncOutcome
            get() = fake.nextOutcome
            set(value) {
                fake.nextOutcome = value
            }

        override val state: StateFlow<SyncState> get() = fake.state

        override suspend fun syncOnce(trigger: SyncTrigger): SyncOutcome {
            triggers.add(trigger)
            return fake.syncOnce(trigger)
        }

        override fun clearState() = fake.clearState()
    }

    private class ManagerFixture(engineWrapper: (RecordingRunner) -> SyncRunner = { it }) {
        val store = FakeSyncStore()
        val account = FakeAccountStore()
        val dao = FakeSyncDao()
        val engine = RecordingRunner()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = SyncManager(
            engine = engineWrapper(engine),
            store = store,
            account = account,
            crypto = SyncCrypto(),
            syncDao = dao,
            recorder = OpRecorder(dao, { "dev-test" }, { store.selfMemberId }),
            exporter = FakeInitialExporter(),
            tx = FakeTx(listOf(dao)),
            // 去抖/写后触发用例不触达 DAV：触达即测试写错，直接炸出来
            davFactory = { error("本用例不应触达 DAV") },
            remoteFactory = { _, _, _ -> error("本用例不应触达远端工厂") },
            scope = scope,
        )

        /** 已接入同步（凭证 + 派生密钥齐备）；密钥内容不参与 requestSync 路径 */
        fun configure() {
            account.save(WebDavCred(baseUrl = "https://dav.example.com/dav/", username = "u", appPassword = "p"))
            store.saveDerived(
                SyncKeys(encKey = ByteArray(32), nameKey = ByteArray(32), kcv = ByteArray(16)),
                KdfParams(mKiB = 32, t = 1, p = 1, salt = ByteArray(16)),
            )
        }
    }

    /** 注入「insertOp 必抛」：模拟磁盘/IO 错导致的写失败（事务回滚前不得触发同步） */
    private class FailingInsertDao(private val delegate: FakeSyncDao) : SyncDao by delegate {
        override suspend fun insertOp(op: SyncOpEntity): Long = throw IllegalStateException("注入：写操作失败")
    }

    /** 首轮按预设的 nextOutcome 返回（IN_FLIGHT），其后自动放行 success */
    private class InFlightThenOkRunner(private val wrapped: RecordingRunner) : SyncRunner by wrapped {
        override suspend fun syncOnce(trigger: SyncTrigger): SyncOutcome {
            val outcome = wrapped.syncOnce(trigger)
            if (outcome.skipReason == SkipReason.IN_FLIGHT) {
                wrapped.nextOutcome = SyncOutcome(success = true)
            }
            return outcome
        }
    }

    /**
     * 全面审查 P2：写后窗口到点时恰有别的轮在跑 → skip(IN_FLIGHT)。
     * 该轮开始时读不到本窗口的写，就此作罢这批写要等下个触发才补传——
     * 必须重新排一个窗口（本轮验证：IN_FLIGHT 之后仍会发起第二轮）。
     */
    @Test
    fun afterWriteSkippedInFlightReArmsAnotherWindow() {
        val f = ManagerFixture(engineWrapper = { InFlightThenOkRunner(it) })
        f.configure()
        f.engine.nextOutcome = SyncOutcome.skip(SkipReason.IN_FLIGHT)
        f.manager.requestSync(SyncTrigger.AFTER_WRITE)
        // 第一轮 IN_FLIGHT → 重排 → 第二轮放行（两个 3s 窗口，真时间等待）
        awaitCalls(f.engine, 2)
        assertTrue(f.engine.nextOutcome.success)
    }

    /** 轮询等引擎跑够 [expected] 轮（真时间窗口；超时上限防挂死，失败时报实际轮数） */
    private fun awaitCalls(engine: RecordingRunner, expected: Int, timeoutMillis: Long = 8_000L) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (engine.calls < expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("等待 AFTER_WRITE 轮次超时（${timeoutMillis}ms）", expected, engine.calls)
    }
}
