package com.simpleledger.app.sync

import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.SyncOpEntity
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.op.OpApplier
import com.simpleledger.app.sync.photo.PhotoTransfer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * P1-3「skipped 成因区分」验收：`SyncOutcome.skipped` 的一个 bool 原先混了两种成因，
 * 现在两条产出路径各带 `skipReason`：
 *
 * - `SyncEngine.syncOnce` 单飞锁 `tryLock()` 失败 → [SkipReason.IN_FLIGHT]（已有轮在跑）；
 * - `remoteProvider()` 返回 null（未配置同步）→ [SkipReason.NOT_CONFIGURED]。
 *
 * 同时钉住反向不变量：跳过轮必有成因、且**只有**跳过轮才有成因（成功 / 失败轮恒为 null）——
 * UI 靠这个字段分措辞，字段若在非跳过轮冒出来就会误导。
 *
 * 单飞用例直接用真 [SyncEngine] + MiniDavServer：在 `outbox` 上挂闸门把首轮卡在 PUSH_OPS
 * （锁已持有、且不触达网络），比用假引擎更接近生产路径。
 */
class SkipReasonTest {

    private lateinit var server: MiniDavServer
    private lateinit var workRoot: File

    /** 轻量 KDF 参数与固定密钥：本用例只验跳过成因，不验派生链（派生链由 SyncManagerTest 钉） */
    private val keys = SyncKeys(encKey = ByteArray(32) { 1 }, nameKey = ByteArray(32) { 2 }, kcv = ByteArray(16) { 3 })
    private val kdf = KdfParams(mKiB = 32, t = 1, p = 1, salt = ByteArray(16) { 4 })

    @Before
    fun setUp() {
        server = MiniDavServer().also { it.start() }
        workRoot = File(System.getProperty("java.io.tmpdir"), "sl-skipreason-test-${System.nanoTime()}")
        workRoot.mkdirs()
    }

    @After
    fun tearDown() {
        server.stop()
        workRoot.deleteRecursively()
    }

    // ------------------------------------------------------------ 两种成因

    @Test
    fun notConfiguredRoundCarriesNotConfiguredReason() = runBlocking {
        val fx = fixture(dirName = "noconf", configured = false)

        val outcome = fx.engine.syncOnce(SyncTrigger.MANUAL)

        assertTrue("未配置：本轮未执行", outcome.skipped)
        assertEquals("未配置成因", SkipReason.NOT_CONFIGURED, outcome.skipReason)
        assertNull("跳过轮不带错误码（S6：跳过不是失败）", outcome.error)
        assertTrue("跳过轮 success = true（不打扰用户）", outcome.success)
    }

    @Test
    fun singleFlightRoundCarriesInFlightReason() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fx = fixture(dirName = "inflight", outboxGate = gate)

        var first: SyncOutcome? = null
        val job = launch { first = fx.engine.syncOnce(SyncTrigger.MANUAL) }
        // 首轮进入 PUSH_OPS = 单飞锁已被持有（随后卡在闸门上的 outbox）
        var spins = 0
        while (fx.engine.state.value != SyncState.Syncing(SyncPhase.PUSH_OPS)) {
            yield()
            check(++spins < 100_000) { "首轮未进入 PUSH_OPS：${fx.engine.state.value}" }
        }

        val second = fx.engine.syncOnce(SyncTrigger.MANUAL)

        assertTrue("占用中的触发应直接跳过", second.skipped)
        assertEquals("单飞占用成因", SkipReason.IN_FLIGHT, second.skipReason)

        gate.complete(Unit)
        job.join()
        assertNotNull("被占用的那一轮照常跑完", first)
        assertNull("正常收尾的那轮不该带跳过成因", first!!.skipReason)
    }

    // ------------------------------------------------------------ 反向不变量：只有跳过轮才有成因

    @Test
    fun successfulRoundCarriesNoSkipReason() = runBlocking {
        val fx = fixture(dirName = "ok")

        val outcome = fx.engine.syncOnce(SyncTrigger.MANUAL)

        assertFalse("正常轮不是跳过轮", outcome.skipped)
        assertNull("非跳过轮 skipReason 恒为 null", outcome.skipReason)
    }

    @Test
    fun failedRoundCarriesNoSkipReason() = runBlocking {
        val dead = MiniDavServer().also { it.start() }
        val deadUrl = dead.baseUrl
        dead.stop() // 死端口：连接拒绝 → NETWORK（S6 静默失败）

        val fx = fixture(dirName = "dead", remoteUrl = deadUrl)
        val outcome = fx.engine.syncOnce(SyncTrigger.MANUAL)

        assertFalse("网络失败 ≠ 跳过", outcome.skipped)
        assertEquals(SyncError.NETWORK, outcome.error)
        assertNull("失败轮的成因字段必须为空（否则 UI 会按「跳过」措辞）", outcome.skipReason)
    }

    // ------------------------------------------------------------ 常量形状（既有调用方兼容）

    @Test
    fun legacySkippedConstantKeepsShapeAndCarriesReason() {
        assertTrue(SyncOutcome.SKIPPED.success)
        assertTrue(SyncOutcome.SKIPPED.skipped)
        assertEquals(
            "旧常量语义收窄为「未配置」（既有断言只看 skipped，不看成因）",
            SkipReason.NOT_CONFIGURED,
            SyncOutcome.SKIPPED.skipReason,
        )
        assertEquals(SkipReason.IN_FLIGHT, SyncOutcome.skip(SkipReason.IN_FLIGHT).skipReason)
        assertEquals(SkipReason.NOT_CONFIGURED, SyncOutcome.skip(SkipReason.NOT_CONFIGURED).skipReason)
    }

    // ------------------------------------------------------------ 夹具

    private class Fixture(val rawDao: FakeSyncDao, val store: FakeSyncStore, val engine: SyncEngine)

    private fun fixture(
        dirName: String,
        configured: Boolean = true,
        remoteUrl: String = server.baseUrl,
        outboxGate: CompletableDeferred<Unit>? = null,
    ): Fixture {
        val raw = FakeSyncDao()
        val dao: SyncDao = if (outboxGate == null) raw else GatedOutboxDao(raw, outboxGate)
        val rows = FakeRowStore(raw)
        val tx = FakeTx(listOf(raw, rows))
        val store = FakeSyncStore()
        val photo = PhotoTransfer(dao, FakePhotoStore(), FakePhotoRefs(), FakeNetworkStatus(), store)
        val remote = WebDavRemote(
            dav = MiniWebDavClient(remoteUrl.toHttpUrl(), "user", "app-pass", newParser = { KXmlParser() }),
            keys = keys,
            kdf = kdf,
            workDir = File(workRoot, dirName),
        )
        val provider: () -> WebDavRemote? = if (configured) ({ remote }) else ({ null })
        val engine = SyncEngine(
            syncDao = dao,
            applier = OpApplier(dao, rows, tx),
            photo = photo,
            store = store,
            remoteProvider = provider,
        )
        return Fixture(raw, store, engine)
    }

    /** `outbox` 挂闸门：首轮卡在 PUSH_OPS（不触达网络），供单飞用例稳定制造「占用中」 */
    private class GatedOutboxDao(
        private val delegate: FakeSyncDao,
        private val gate: CompletableDeferred<Unit>,
    ) : SyncDao by delegate {
        override suspend fun outbox(limit: Int): List<SyncOpEntity> {
            gate.await()
            return delegate.outbox(limit)
        }
    }
}
