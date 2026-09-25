package com.simpleledger.app.sync

import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.photo.PhotoTransfer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * T-4 验收核心：**双实例全链集成测试**——两个 [SyncEngine] 对 MiniDavServer（真实 HTTP 往返 +
 * 生产加密管道）跑离线并发账目，证 §4.1 一轮同步编排（PUSH_OPS → PULL_OPS → MERGE → PHOTOS）
 * 的 S1 全链收敛。
 *
 * 口径映射：
 * - S1：两机各离线 ≥20 笔（增/删/改含 A删∥B改双留）→ 三轮同步后并集逐笔一致、总额相等、
 *   孤儿引用清零（收敛断言 = SyncTestDevice.state() 全状态文本比对）；
 * - S6：断网（死端口）全程无异常外抛，`SyncOutcome.error = NETWORK` + 角标 Failed；
 * - 单飞：PHOTOS 阶段挂起期间的第二轮触发直接 SKIPPED（引擎 Mutex tryLock）；
 * - 断点/幂等：已应用分片不再回拉（台账差集），二次轮 pulledChunks = 0。
 *
 * 双机共享同一 WebDAV 账号与派生密钥（同口令同 KDF），文件名假名一致 ⇒ 与真双机同构。
 */
class SyncEngineIntegrationTest {

    private lateinit var server: MiniDavServer
    private lateinit var workRoot: File
    private val crypto = SyncCrypto()

    /** 轻量 KDF（m=32KiB, t=1）只为提速；派生链/密文格式与生产逐字节同实现 */
    private val kdf = KdfParams(mKiB = 32, t = 1, p = 1, salt = ByteArray(16) { 7 })
    private lateinit var keys: SyncKeys

    @Before
    fun setUp() {
        server = MiniDavServer().also { it.start() }
        workRoot = File(System.getProperty("java.io.tmpdir"), "sl-engine-test-${System.nanoTime()}")
        workRoot.mkdirs()
        keys = crypto.deriveKeys("正确口令".toCharArray(), kdf)
    }

    @After
    fun tearDown() {
        server.stop()
        workRoot.deleteRecursively()
    }

    // ------------------------------------------------------------ S1：双实例离线并发收敛

    @Test
    fun s1_offlineConcurrentLedgersConvergeToUnion() = runBlocking {
        val a = EngineDevice("A", server.baseUrl, keys, kdf, File(workRoot, "A"))
        val b = EngineDevice("B", server.baseUrl, keys, kdf, File(workRoot, "B"))
        seedBoth(a, b)

        // ---- 公共基线 shared-1..8（A 记账 → 双向收敛到 v1）
        for (i in 1..8) {
            a.core.localUpsert(RowKind.ENTRY, "shared-$i", sharedPayload(i, "shared-$i"), baseSeq = null)
        }
        assertTrue(a.engine.syncOnce(SyncTrigger.MANUAL).success)
        assertTrue(b.engine.syncOnce(SyncTrigger.MANUAL).success)
        assertTrue(a.engine.syncOnce(SyncTrigger.MANUAL).success)
        assertEquals(a.core.state(), b.core.state())

        // ---- 离线并发：A 20 操作（14 增 + 3 改 + 3 删）、B 20 操作（14 增 + 4 改 + 2 删）
        for (i in 1..14) {
            a.core.localUpsert(RowKind.ENTRY, "a-new-$i", entryPayload(i * 10L, "a-new-$i"), baseSeq = null)
        }
        a.core.localUpsert(RowKind.ENTRY, "shared-1", entryPayload(111L, "A改1"), baseSeq = 1) // 改∥改
        a.core.localUpsert(RowKind.ENTRY, "shared-2", entryPayload(222L, "A改2"), baseSeq = 1) // 串行改
        a.core.localUpsert(RowKind.ENTRY, "shared-5", entryPayload(555L, "A改5"), baseSeq = 1) // 串行改
        a.core.localDelete(RowKind.ENTRY, "shared-3", baseSeq = 1, snapshot = sharedPayload(3, "shared-3")) // 删∥改
        a.core.localDelete(RowKind.ENTRY, "shared-4", baseSeq = 1, snapshot = sharedPayload(4, "shared-4")) // 删∥改
        a.core.localDelete(RowKind.ENTRY, "shared-6", baseSeq = 1, snapshot = sharedPayload(6, "shared-6")) // 删∥改

        for (i in 1..14) {
            b.core.localUpsert(RowKind.ENTRY, "b-new-$i", entryPayload(i * 10L + 1, "b-new-$i"), baseSeq = null)
        }
        b.core.localUpsert(RowKind.ENTRY, "shared-1", entryPayload(112L, "B改1"), baseSeq = 1) // 改∥改
        b.core.localUpsert(RowKind.ENTRY, "shared-3", entryPayload(303L, "B改3"), baseSeq = 1) // 删∥改双留
        b.core.localUpsert(RowKind.ENTRY, "shared-4", entryPayload(404L, "B改4"), baseSeq = 1) // 删∥改双留
        b.core.localUpsert(RowKind.ENTRY, "shared-6", entryPayload(606L, "B改6"), baseSeq = 1) // 删∥改双留
        b.core.localDelete(RowKind.ENTRY, "shared-7", baseSeq = 1, snapshot = sharedPayload(7, "shared-7")) // 纯删除
        b.core.localDelete(RowKind.ENTRY, "shared-8", baseSeq = 1, snapshot = sharedPayload(8, "shared-8")) // 纯删除

        // ---- 三轮全链收敛：a.push → b.push+pull+merge → a.pull+merge
        val outcomeA1 = a.engine.syncOnce(SyncTrigger.MANUAL)
        val outcomeB = b.engine.syncOnce(SyncTrigger.MANUAL)
        val outcomeA2 = a.engine.syncOnce(SyncTrigger.MANUAL)
        assertTrue("A 首轮应成功", outcomeA1.success)
        assertTrue("B 全链应成功", outcomeB.success)
        assertTrue("A 收敛轮应成功", outcomeA2.success)

        // ---- 并集逐笔一致（全状态文本比对：行投影 + 回收站留底 + 设置）
        assertEquals(a.core.state(), b.core.state())
        val stateB = b.core.state()
        for (i in 1..14) {
            assertTrue("并集应收下 a-new-$i", stateB.contains("a-new-$i"))
            assertTrue("并集应收下 b-new-$i", a.core.state().contains("b-new-$i"))
        }

        // ---- 冲突裁决逐条验证
        assertEquals("改∥改 LWW：actorId B 胜", "B改1", a.core.store.rows[RowKind.ENTRY to "shared-1"]?.payload?.optString("note"))
        assertEquals("A 串行改保留", "A改2", a.core.store.rows[RowKind.ENTRY to "shared-2"]?.payload?.optString("note"))
        assertEquals("A 串行改保留", "A改5", a.core.store.rows[RowKind.ENTRY to "shared-5"]?.payload?.optString("note"))
        for (i in listOf(3, 4, 6)) {
            assertEquals("删∥改双留：修改存活（shared-$i）", "B改$i", a.core.store.rows[RowKind.ENTRY to "shared-$i"]?.payload?.optString("note"))
        }
        assertTrue("纯删除行死（shared-7）", a.core.store.rows.keys.none { it.second == "shared-7" })
        assertTrue("纯删除行死（shared-8）", a.core.store.rows.keys.none { it.second == "shared-8" })

        // ---- 账目总额相等 + 孤儿引用清零 + 无挂起
        assertEquals("并集总额两机相等", totalCents(a), totalCents(b))
        assertTrue(a.core.store.orphanReferences().isEmpty())
        assertTrue(b.core.store.orphanReferences().isEmpty())
        assertEquals("收敛轮零挂起", 0, outcomeA2.deferredOps)
    }

    // ------------------------------------------------------------ S6：静默失败

    @Test
    fun s6_networkFailureIsSilentAndClassified() = runBlocking {
        val dead = MiniDavServer().also { it.start() }
        val deadUrl = dead.baseUrl
        dead.stop() // 死端口：连接拒绝 → NETWORK

        val d = EngineDevice("A", deadUrl, keys, kdf, File(workRoot, "dead"))
        d.core.localUpsert(RowKind.ENTRY, "e-1", entryPayload(100L, "离线记账"), baseSeq = null)

        // S6：永不外抛——syncOnce 正常返回失败结果（断言不抛即证明）
        val outcome = d.engine.syncOnce(SyncTrigger.MANUAL)
        assertFalse(outcome.success)
        assertEquals(SyncError.NETWORK, outcome.error)
        assertTrue(d.engine.state.value is SyncState.Failed)
        assertEquals("NETWORK", d.syncStore.lastError)

        // 断点不丢：操作仍在 outbox，网络恢复后下轮续传
        assertEquals(1, d.core.dao.countOutbox())
    }

    @Test
    fun unconfiguredRoundIsSkipped() = runBlocking {
        val d = EngineDevice("A", server.baseUrl, keys, kdf, File(workRoot, "noconf"), remoteOverride = { null })
        val outcome = d.engine.syncOnce(SyncTrigger.MANUAL)
        assertTrue(outcome.skipped)
        assertTrue(outcome.success)
    }

    // ------------------------------------------------------------ 单飞

    @Test
    fun singleFlight_secondConcurrentRoundIsSkipped() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val d = EngineDevice("A", server.baseUrl, keys, kdf, File(workRoot, "gate"), gate = gate)
        var first: SyncOutcome? = null
        val job = launch { first = d.engine.syncOnce(SyncTrigger.MANUAL) }

        // 等首轮推进到 PHOTOS（被 gate 挂起），此刻引擎 Mutex 被占用
        var spins = 0
        while (d.engine.state.value != SyncState.Syncing(SyncPhase.PHOTOS)) {
            yield()
            check(++spins < 100_000) { "首轮未推进到 PHOTOS：${d.engine.state.value}" }
        }
        val second = d.engine.syncOnce(SyncTrigger.MANUAL)
        assertTrue("占用中的触发应直接 SKIPPED", second.skipped)

        gate.complete(Unit)
        job.join()
        assertTrue(first!!.success)
    }

    // ------------------------------------------------------------ 断点 / 幂等

    @Test
    fun appliedChunksAreNotRepulled_secondRoundZeroFetch() = runBlocking {
        val a = EngineDevice("A", server.baseUrl, keys, kdf, File(workRoot, "A2"))
        val b = EngineDevice("B", server.baseUrl, keys, kdf, File(workRoot, "B2"))
        seedBoth(a, b)
        a.core.localUpsert(RowKind.ENTRY, "e-1", entryPayload(100L, "只增一次"), baseSeq = null)

        assertTrue(a.engine.syncOnce(SyncTrigger.MANUAL).success)
        assertTrue(b.engine.syncOnce(SyncTrigger.MANUAL).success)
        assertEquals(a.core.state(), b.core.state())

        // 幂等轮：台账差集挡掉已应用分片，零回拉（R-07 断点口径）
        val again = a.engine.syncOnce(SyncTrigger.MANUAL)
        assertTrue(again.success)
        assertEquals(0, again.pulledChunks)
        assertEquals(0, again.pushedOps)
        assertEquals(a.core.state(), b.core.state())
    }

    // ------------------------------------------------------------ 夹具

    /** 一台「设备」= SyncTestDevice（合并语义全生产）+ SyncEngine 全链（真实 HTTP + 加密管道） */
    private class EngineDevice(
        val name: String,
        baseUrl: String,
        keys: SyncKeys,
        kdf: KdfParams,
        workDir: File,
        gate: CompletableDeferred<Unit>? = null,
        remoteOverride: (() -> WebDavRemote?)? = null,
    ) {
        val core = SyncTestDevice(name)
        val syncStore = FakeSyncStore()
        val photoStore = FakePhotoStore()
        val photoRefs = FakePhotoRefs(hashes = emptyList(), gate = gate)
        val network = FakeNetworkStatus(online = true, wifi = true)
        val remote: WebDavRemote = WebDavRemote(
            MiniWebDavClient(baseUrl.toHttpUrl(), "user", "app-pass", newParser = { KXmlParser() }),
            keys,
            kdf,
            workDir,
        )
        val photo = PhotoTransfer(core.dao, photoStore, photoRefs, network, syncStore)
        val engine = SyncEngine(core.dao, core.applier, photo, syncStore, remoteOverride ?: { remote })
    }

    private fun seedBoth(a: EngineDevice, b: EngineDevice) {
        for (d in listOf(a, b)) {
            d.core.seedRow(RowKind.SECTION, "sec-1", OpCodec.sectionSnapshot("生活", 1, "", 0, 0, 0, 1_000L))
            d.core.seedRow(RowKind.CATEGORY, "cat-1", OpCodec.categorySnapshot("吃", 2, 0, null, 0))
        }
    }

    private fun sharedPayload(i: Int, note: String) = entryPayload(i * 100L, note)

    private fun entryPayload(amount: Long, note: String) = OpCodec.entrySnapshot(
        type = 0, amountCents = amount, categorySyncId = "cat-1", sectionSyncId = "sec-1",
        entryTime = 1_000L, note = note, reconciled = false, reimburseState = 0,
        createdAt = 1_000L, updatedAt = 1_000L, memberSyncId = null,
    )

    /** 账目总额（分）——S1「总额相等」断言 */
    private fun totalCents(d: EngineDevice): Long =
        d.core.store.rows.values
            .filter { it.kind == RowKind.ENTRY }
            .sumOf { it.payload.optLong("amountCents") }
}
