package com.simpleledger.app.sync

import com.simpleledger.app.sync.crypto.BlobHeader
import com.simpleledger.app.sync.crypto.CryptoFormat
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpType
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.op.SyncOp
import com.simpleledger.app.sync.photo.PhotoTransfer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * U-7 损坏分片隔离验收：真实 HTTP（MiniDavServer）+ 生产加密管道下——
 *
 * - 单个损坏分片（密文解不开 / 明文非合法 JSON）不再拖死整轮：其余分片照常应用，
 *   台账**绝不**把损坏分片记为已下载（内容不丢，只是暂缺）；
 * - 连续失败达 [SyncEngine.CHUNK_QUARANTINE_THRESHOLD] 才进隔离名单，自动轮跳过回拉——
 *   「不再请求」直接用 [MiniDavServer.requestLog] 的 GET 请求计数断言（坏分片是确定性损坏，
 *   「被请求必计数」与「请求日志零增长」互为印证）；
 * - 成功解码即清零计数：修复后的分片**不需要**手动同步，低于阈值时下一轮自动轮即恢复；
 * - 手动「立即同步」重试一次作逃生口：仍损坏 → 当场重新隔离；内容修复 → 正常应用并解除；
 * - 隔离状态随 SyncStore.clearAll()（resetSync，R-05）清空。
 *
 * 损坏注入方式：直写垃圾字节（密文头都认不出）；恢复注入：用同口令派生密钥
 * `SyncCrypto.seal` 出合法密文覆盖同名文件。
 */
class ChunkQuarantineTest {

    private lateinit var server: MiniDavServer
    private lateinit var workRoot: File
    private val crypto = SyncCrypto()

    /** 轻量 KDF（m=32KiB, t=1）只为提速；派生链/密文格式与生产逐字节同实现 */
    private val kdf = KdfParams(mKiB = 32, t = 1, p = 1, salt = ByteArray(16) { 7 })
    private lateinit var keys: SyncKeys
    private lateinit var dav: MiniWebDavClient

    @Before
    fun setUp() = runBlocking {
        server = MiniDavServer().also { it.start() }
        workRoot = File(System.getProperty("java.io.tmpdir"), "sl-quarantine-test-${System.nanoTime()}")
        workRoot.mkdirs()
        keys = crypto.deriveKeys("正确口令".toCharArray(), kdf)
        dav = MiniWebDavClient(server.baseUrl.toHttpUrl(), "user", "app-pass", newParser = { KXmlParser() })
        WebDavRemote(dav, keys, kdf, File(workRoot, "plant")).ensureLayout()
    }

    @After
    fun tearDown() {
        server.stop()
        workRoot.deleteRecursively()
    }

    // ------------------------------------------------------------ 用例

    @Test
    fun corruptChunkDoesNotStallRound_othersStillApplied_ledgerUntouched() = runBlocking {
        val d = device("A")
        plantGoodChunk(GOOD_CHUNK_NAME)
        plantGoodChunk(
            GOOD_CHUNK_NAME_2,
            opId = "b-op-2", rowSyncId = "e-2", amount = 200L, note = "第二个好分片",
        )
        plantGarbageChunk(CORRUPT_CHUNK_NAME)

        val outcome = d.engine.syncOnce(SyncTrigger.COLD_START)

        // U-7 核心：坏分片不拖死整轮，好分片照常收敛
        assertTrue("含坏分片的轮次仍应成功", outcome.success)
        assertTrue("好分片 1 应已应用", d.core.store.exists(RowKind.ENTRY, "e-1"))
        assertTrue("好分片 2 应已应用", d.core.store.exists(RowKind.ENTRY, "e-2"))
        assertEquals("pulledChunks 只数成功解码的分片", 2, outcome.pulledChunks)
        // 台账对照：好分片照常入账，坏分片绝不入账（内容暂缺 ≠ 内容丢失，修复后仍可拉取）
        assertNotNull("好分片应进台账", d.core.dao.getRemoteFile(GOOD_CHUNK_NAME))
        assertNull("坏分片不得进台账", d.core.dao.getRemoteFile(CORRUPT_CHUNK_NAME))
        // 首败只计数不隔离（给截断下载等偶发损坏留重试余地）
        assertEquals(1, d.syncStore.chunkFailureCount(CORRUPT_CHUNK_NAME))
        assertTrue(d.syncStore.quarantinedChunks().isEmpty())
        assertEquals(0, outcome.quarantinedChunks)
    }

    @Test
    fun threeConsecutiveFailuresQuarantine_autoRoundsSkipAfterwards() = runBlocking {
        val d = device("A")
        plantGarbageChunk(CORRUPT_CHUNK_NAME)

        repeat(SyncEngine.CHUNK_QUARANTINE_THRESHOLD - 1) {
            val outcome = d.engine.syncOnce(SyncTrigger.FOREGROUND)
            assertTrue(outcome.success)
        }
        assertTrue("未达阈值不隔离", d.syncStore.quarantinedChunks().isEmpty())

        // 第 3 次失败：进隔离
        val third = d.engine.syncOnce(SyncTrigger.FOREGROUND)
        assertTrue(third.success)
        assertTrue(
            "连续失败达阈值应隔离",
            d.syncStore.quarantinedChunks().contains(CORRUPT_CHUNK_NAME),
        )
        assertEquals(1, third.quarantinedChunks)

        // 隔离后的自动轮：跳过回拉。「不再请求」用 MiniDavServer 请求日志直接计数——
        // 该路径此前每轮被 GET 一次（3 次失败 = 3 条 GET），本轮必须零增长
        val corruptGets = "GET /dav/${WebDavRemote.DIR}/$CORRUPT_CHUNK_NAME"
        val getsBefore = server.requestLog.count { it.startsWith(corruptGets) }
        assertEquals("前 3 轮每轮拉取一次坏分片", SyncEngine.CHUNK_QUARANTINE_THRESHOLD, getsBefore)
        val skipped = d.engine.syncOnce(SyncTrigger.PERIODIC)
        assertTrue(skipped.success)
        assertEquals("隔离分片不再发起 GET（请求计数断言）", getsBefore, server.requestLog.count { it.startsWith(corruptGets) })
        assertEquals("隔离分片不计入 pulledChunks", 0, skipped.pulledChunks)
        assertEquals("隔离分片不应再计数", 3, d.syncStore.chunkFailureCount(CORRUPT_CHUNK_NAME))
        assertEquals(1, skipped.quarantinedChunks)
    }

    @Test
    fun successDecodeResetsCount_subThresholdRecoversOnAutoRound() = runBlocking {
        val d = device("A")
        plantGarbageChunk(CORRUPT_CHUNK_NAME)
        val first = d.engine.syncOnce(SyncTrigger.COLD_START)
        assertTrue(first.success)
        assertEquals(1, d.syncStore.chunkFailureCount(CORRUPT_CHUNK_NAME))

        // 云端内容被修复（同口令合法密文覆盖同名文件）；未达阈值从未隔离，
        // 不需要手动同步——下一轮自动轮即拉到并清零计数（U-7：成功解码即清零）
        plantGoodChunk(CORRUPT_CHUNK_NAME, opId = "b-op-1", rowSyncId = "e-1", amount = 100L, note = "修复后自动拉到")
        val second = d.engine.syncOnce(SyncTrigger.FOREGROUND)
        assertTrue(second.success)
        assertTrue("修复后的分片应正常应用", d.core.store.exists(RowKind.ENTRY, "e-1"))
        assertEquals("成功解码清零计数", 0, d.syncStore.chunkFailureCount(CORRUPT_CHUNK_NAME))
        assertTrue(d.syncStore.quarantinedChunks().isEmpty())
        assertEquals(0, second.quarantinedChunks)
        assertNotNull("修复后的分片应进台账", d.core.dao.getRemoteFile(CORRUPT_CHUNK_NAME))
    }

    @Test
    fun manualSyncRetriesQuarantinedChunk_stillCorruptRequarantines() = runBlocking {
        val d = device("A")
        plantGarbageChunk(CORRUPT_CHUNK_NAME)
        repeat(SyncEngine.CHUNK_QUARANTINE_THRESHOLD) { d.engine.syncOnce(SyncTrigger.COLD_START) }
        assertTrue(d.syncStore.quarantinedChunks().contains(CORRUPT_CHUNK_NAME))

        // 手动「立即同步」= 逃生口：重试一次，仍损坏 → 当场重新隔离（计数 4）
        val retry = d.engine.syncOnce(SyncTrigger.MANUAL)
        assertTrue(retry.success)
        assertTrue("仍损坏应重新隔离", d.syncStore.quarantinedChunks().contains(CORRUPT_CHUNK_NAME))
        assertEquals(4, d.syncStore.chunkFailureCount(CORRUPT_CHUNK_NAME))
        assertNull(d.core.dao.getRemoteFile(CORRUPT_CHUNK_NAME))
    }

    @Test
    fun repairedChunkRecoversOnManualSync_quarantineAndCountCleared() = runBlocking {
        val d = device("A")
        plantGarbageChunk(CORRUPT_CHUNK_NAME)
        repeat(SyncEngine.CHUNK_QUARANTINE_THRESHOLD) { d.engine.syncOnce(SyncTrigger.COLD_START) }
        assertTrue(d.syncStore.quarantinedChunks().contains(CORRUPT_CHUNK_NAME))

        // 云端内容被修复（同口令合法密文覆盖同名文件）→ 手动同步重试成功
        plantGoodChunk(CORRUPT_CHUNK_NAME, opId = "b-op-2", rowSyncId = "e-2", amount = 200L, note = "修复后拉到")
        val recovered = d.engine.syncOnce(SyncTrigger.MANUAL)
        assertTrue(recovered.success)
        assertTrue("修复后的分片应正常应用", d.core.store.exists(RowKind.ENTRY, "e-2"))
        assertTrue("解除隔离", d.syncStore.quarantinedChunks().isEmpty())
        assertEquals("成功解码清零计数", 0, d.syncStore.chunkFailureCount(CORRUPT_CHUNK_NAME))
        assertEquals(0, recovered.quarantinedChunks)
    }

    @Test
    fun resetClearsQuarantineState() = runBlocking {
        val d = Device("A")
        plantGarbageChunk(CORRUPT_CHUNK_NAME)
        repeat(SyncEngine.CHUNK_QUARANTINE_THRESHOLD) { d.engine.syncOnce(SyncTrigger.COLD_START) }
        assertTrue(d.syncStore.quarantinedChunks().isNotEmpty())

        // resetSync（R-05）经 SyncStore.clearAll() 清空全部隔离状态
        d.syncStore.clearAll()
        assertTrue(d.syncStore.quarantinedChunks().isEmpty())
        assertEquals(0, d.syncStore.chunkFailureCount(CORRUPT_CHUNK_NAME))
    }

    // ------------------------------------------------------------ U-7 判据补全（本轮）

    /**
     * U-7 判据补全回归：密文合法解开、JSON 也合法，但 rowKind 是本版本不认识的枚举值
     * （版本偏斜：新版 App 按 §7-5 先写了新枚举）——确定性损坏，必须计数/隔离而非
     * 整轮失败。旧判据只认 Corrupted/SyncCryptoException/JSONException，此处裸抛的
     * IllegalStateException（UnknownOpEnumException）穿透判据 ⇒ syncOnce 永久失败、
     * 手动「立即同步」同样失败、永不隔离。
     */
    @Test
    fun unknownEnumValueQuarantinesInsteadOfFailingRound() = runBlocking {
        val d = device("A")
        plantGoodChunk(GOOD_CHUNK_NAME)
        plantUnknownEnumChunk(CORRUPT_CHUNK_NAME)

        repeat(SyncEngine.CHUNK_QUARANTINE_THRESHOLD) {
            val outcome = d.engine.syncOnce(SyncTrigger.COLD_START)
            assertTrue("未知枚举属确定性损坏，不得拖死整轮", outcome.success)
            assertTrue("好分片照常应用", d.core.store.exists(RowKind.ENTRY, "e-1"))
            assertNull("坏分片不得进台账", d.core.dao.getRemoteFile(CORRUPT_CHUNK_NAME))
        }
        assertEquals(SyncEngine.CHUNK_QUARANTINE_THRESHOLD, d.syncStore.chunkFailureCount(CORRUPT_CHUNK_NAME))
        assertTrue("连续失败达阈值应隔离", d.syncStore.quarantinedChunks().contains(CORRUPT_CHUNK_NAME))
    }

    /**
     * U-7 判据补全回归：GCM 认证通过但明文不是合法 gzip 流（编码器 bug / 版本偏斜
     * 产出的分片）。gunzip 原先在 SyncCrypto.open 的 try 块之外，ZipException 裸穿透
     * WebDavRemote 的 `catch (SyncCryptoException)` 损坏包装，同样不进隔离判据 ⇒
     * 整轮永久失败。现归为 BadFormat → Corrupted → 正常计数。
     */
    @Test
    fun nonGzipPlaintextQuarantinesInsteadOfFailingRound() = runBlocking {
        val d = device("A")
        plantSealedNonGzipChunk(CORRUPT_CHUNK_NAME)

        val outcome = d.engine.syncOnce(SyncTrigger.COLD_START)
        assertTrue("解压失败应按确定性损坏计数，不得整轮失败", outcome.success)
        assertEquals(1, d.syncStore.chunkFailureCount(CORRUPT_CHUNK_NAME))
        assertNull(d.core.dao.getRemoteFile(CORRUPT_CHUNK_NAME))
        assertEquals(0, outcome.quarantinedChunks) // 首败只计数不隔离
    }

    // ------------------------------------------------------------ 夹具

    /** 一台「设备」= SyncTestDevice（合并语义全生产）+ SyncEngine 全链（真实 HTTP + 加密管道） */
    private fun device(name: String): Device {
        val d = Device(name)
        d.core.seedRow(RowKind.SECTION, "sec-1", OpCodec.sectionSnapshot("生活", 1, "", 0, 0, 0, 1_000L))
        d.core.seedRow(RowKind.CATEGORY, "cat-1", OpCodec.categorySnapshot("吃", 2, 0, null, 0))
        return d
    }

    private inner class Device(name: String) {
        val core = SyncTestDevice(name)
        val syncStore = FakeSyncStore()
        val remote: WebDavRemote = WebDavRemote(
            MiniWebDavClient(server.baseUrl.toHttpUrl(), "user", "app-pass", newParser = { KXmlParser() }),
            keys,
            kdf,
            File(workRoot, name),
        )
        val photo = PhotoTransfer(core.dao, FakePhotoStore(), FakePhotoRefs(), FakeNetworkStatus(), syncStore)
        val engine = SyncEngine(core.dao, core.applier, photo, syncStore, { remote })
    }

    /** 栽赃合法密文分片（seal 后 PUT 到云端，可指定行内容） */
    private fun plantGoodChunk(
        name: String,
        opId: String = "b-op-1",
        rowSyncId: String = "e-1",
        amount: Long = 100L,
        note: String = "B 记账",
    ) {
        val op = SyncOp(
            opId, RowKind.ENTRY, rowSyncId, OpType.UPSERT, "B", null, 1, null,
            entryPayload(amount, note), 1_000L,
        )
        val blob = crypto.seal(
            key = keys.encKey,
            purpose = CryptoFormat.PURPOSE_OPS,
            plaintext = OpCodec.encodeChunk(listOf(op)).toByteArray(Charsets.UTF_8),
            kdf = kdf,
            kcv = null,
        )
        runBlocking { dav.put("${WebDavRemote.DIR}/$name", blob.toRequestBody(BINARY)) }
    }

    /** 栽赃损坏分片：裸垃圾字节——密文头都认不出，downloadChunk 必抛 DavException.Corrupted */
    private fun plantGarbageChunk(name: String) {
        val garbage = "这不是密文——简账损坏分片注入".toByteArray()
        runBlocking { dav.put("${WebDavRemote.DIR}/$name", garbage.toRequestBody(BINARY)) }
    }

    /** 栽赃「未知枚举」分片（U-7 判据补全用例）：同口令合法密文，明文 JSON 的 rowKind 本版本不认识 */
    private fun plantUnknownEnumChunk(name: String) {
        val json =
            """[{"opId":"future-op-1","rowKind":"SECTION_V9","rowSyncId":"s-9",""" +
                """"opType":"UPSERT","actorId":"C","seq":1,"createdAt":1000,"payload":{}}]"""
        val blob = crypto.seal(
            key = keys.encKey,
            purpose = CryptoFormat.PURPOSE_OPS,
            plaintext = json.toByteArray(Charsets.UTF_8),
            kdf = kdf,
            kcv = null,
        )
        runBlocking { dav.put("${WebDavRemote.DIR}/$name", blob.toRequestBody(BINARY)) }
    }

    /** 栽赃「非 gzip 明文」分片（U-7 判据补全用例）：GCM 能解开，但 gunzip 必炸（ZipException） */
    private fun plantSealedNonGzipChunk(name: String) {
        val iv = ByteArray(12) { 3 }
        val header = CryptoFormat.encodeHeader(
            BlobHeader(purpose = CryptoFormat.PURPOSE_OPS, version = CryptoFormat.FORMAT_VERSION, kdf = kdf, iv = iv, kcv = null),
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal("合法 JSON 但不是 gzip 流——[{}]".toByteArray(Charsets.UTF_8))
        runBlocking { dav.put("${WebDavRemote.DIR}/$name", (header + ciphertext).toRequestBody(BINARY)) }
    }

    private fun entryPayload(amount: Long, note: String) = OpCodec.entrySnapshot(
        type = 0, amountCents = amount, categorySyncId = "cat-1", sectionSyncId = "sec-1",
        entryTime = 1_000L, note = note, reconciled = false, reimburseState = 0,
        createdAt = 1_000L, updatedAt = 1_000L, memberSyncId = null,
    )

    companion object {
        /** 32hex 假名形态的云端分片名（§7-6：随机 32hex + ".op"） */
        private const val CORRUPT_CHUNK_NAME = "cafebabe000000000000000000000000.op"
        private const val GOOD_CHUNK_NAME = "feedface000000000000000000000000.op"
        private const val GOOD_CHUNK_NAME_2 = "deadbeef000000000000000000000000.op"

        private val BINARY = "application/octet-stream".toMediaType()
    }
}
