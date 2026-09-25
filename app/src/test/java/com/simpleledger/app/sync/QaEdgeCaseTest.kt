package com.simpleledger.app.sync

import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.dav.DavException
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.op.OpApplier
import com.simpleledger.app.sync.op.OpCodec
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * QA 交叉验证补网（P0-3 / P2-1 边界与错误路径）：
 *
 * 1. **P0-3 直接钉死**：家目录未建（PROPFIND 404）→「家里无账」语义
 *    （`listRemote` 空表、`peekMetaHeader` null）；非 404 照抛——
 *    与 `SyncManagerTest.setupAccount_createsNewBook` 的全链回归互补，
 *    这里直接锁 `WebDavRemote.listHomeOrEmpty()` 的语义边界（含反例）。
 * 2. 损坏密文（正文/明文头）→ `DavException.Corrupted`，不裸抛底层异常。
 * 3. 空操作分片往返 + 空操作批应用零副作用。
 *
 * 服务端 = `MiniDavServer`（PROPFIND 对不存在 collection 回 404，真实化）。
 */
class QaEdgeCaseTest {

    private lateinit var server: MiniDavServer
    private lateinit var workDir: File

    private val crypto = SyncCrypto()
    private val kdf = KdfParams(mKiB = 32, t = 1, p = 1, salt = ByteArray(16) { 9 })

    @Before
    fun setUp() {
        server = MiniDavServer().also { it.start() }
        workDir = File(System.getProperty("java.io.tmpdir"), "sl-qa-edge-${System.nanoTime()}")
        workDir.mkdirs()
    }

    @After
    fun tearDown() {
        server.stop()
        workDir.deleteRecursively()
    }

    private fun client(): MiniWebDavClient = MiniWebDavClient(
        baseUrl = server.baseUrl.toHttpUrl(),
        username = "user",
        appPassword = "app-pass",
        newParser = { KXmlParser() },
    )

    private fun remote(): WebDavRemote =
        WebDavRemote(client(), crypto.deriveKeys("口令".toCharArray(), kdf), kdf, workDir)

    /** P0-3 ①③：全新账号（家目录未建，PROPFIND 404）= 「家里无账」，不抛 */
    @Test
    fun emptyHomeDirMeansNoLedgerNotError() = runBlocking {
        // 先钉死 fake 真实化（RFC 4918）：不存在的 collection 的 PROPFIND 必须 404。
        // 若 fake 退化回「无条件 207」，本用例会失去区分力（空 207 与 404→空表同结果），
        // 正是当年掩盖裸抛缺口的假绿根因——故显式断言之。
        try {
            client().propfindDepth1("${WebDavRemote.DIR}/")
            fail("fake 失真：不存在的 collection 的 PROPFIND 应 404")
        } catch (e: DavException.Http) {
            assertEquals(404, e.code)
        }
        val r = remote()
        // 未建任何目录/文件：PROPFIND simpleledger-v1/ → 404 → 语义化空表
        assertTrue("家目录未建 → listRemote 空表", r.listRemote().isEmpty())
        assertNull("家目录未建 → 无 meta 可发现", r.peekMetaHeader())
    }

    /** P0-3 ①反例：非 404（如 401）必须照抛，不得吞成「家里无账」 */
    @Test
    fun non404OnHomeListingStillThrows() = runBlocking {
        server.requireAuth = MiniWebDavClient.basicCredentials("user", "other-pass")
        try {
            remote().listRemote()
            fail("PROPFIND 401 应照抛，不得吞成空表")
        } catch (e: DavException.Http) {
            assertEquals(401, e.code)
        }
    }

    /** P2-1：正文被篡改 → GCM 认证失败 → Corrupted（语义分类，不裸抛） */
    @Test
    fun corruptedChunkBodyIsClassifiedCorrupted() = runBlocking {
        val r = remote()
        r.ensureLayout()
        val name = r.uploadChunk("[{\"opId\":\"x\"}]".toByteArray())
        val key = "${WebDavRemote.DIR}/$name"
        val blob = server.files[key]!!
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0xFF).toByte() // 翻密文尾字节
        server.files[key] = blob
        try {
            r.downloadChunk(name)
            fail("篡改密文应抛 DavException.Corrupted")
        } catch (e: DavException.Corrupted) {
            // 预期
        }
    }

    /** P2-1：明文头被破坏（magic 不符）→ BadFormat → 同样归 Corrupted */
    @Test
    fun corruptedChunkHeaderIsClassifiedCorrupted() = runBlocking {
        val r = remote()
        r.ensureLayout()
        val name = r.uploadChunk("[]".toByteArray())
        val key = "${WebDavRemote.DIR}/$name"
        val blob = server.files[key]!!
        blob[0] = 'X'.code.toByte() // 破坏 MAGIC "SLC1"
        server.files[key] = blob
        try {
            r.downloadChunk(name)
            fail("破坏明文头应抛 DavException.Corrupted")
        } catch (e: DavException.Corrupted) {
            // 预期
        }
    }

    /** P2-1：空操作分片——上传/下载往返一致，编解码出空批 */
    @Test
    fun emptyOpsChunkRoundTripsAndDecodesToEmptyBatch() = runBlocking {
        val r = remote()
        r.ensureLayout()
        val name = r.uploadChunk(ByteArray(0))
        assertArrayEquals(ByteArray(0), r.downloadChunk(name))
        assertTrue(OpCodec.decodeChunk(OpCodec.encodeChunk(emptyList())).isEmpty())
    }

    /** P2-1：空操作批应用零副作用（不进事务、不写操作账） */
    @Test
    fun applyRemoteEmptyBatchIsNoOp() = runBlocking {
        val dao = FakeSyncDao()
        val rows = FakeRowStore(dao)
        val tx = FakeTx(listOf(dao, rows))
        val result = OpApplier(dao, rows, tx).applyRemote(emptyList())
        assertEquals(0, result.applied)
        assertEquals(0, result.deferred)
        assertEquals(0, result.trashUpserts)
        assertTrue(dao.opLog.isEmpty())
        assertTrue(rows.rows.isEmpty())
    }
}
