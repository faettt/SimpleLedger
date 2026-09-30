package com.simpleledger.app.sync

import com.simpleledger.app.sync.crypto.CryptoFormat
import com.simpleledger.app.sync.crypto.FilenameNym
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.photo.PhotoTransfer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * U-7 照片侧隔离验收（与 ChunkQuarantineTest 分片侧同构）：真实 HTTP（MiniDavServer）
 * + 生产加密管道 + SyncEngine 全链下——
 *
 * - 单张坏照片（云端内容哈希不符）不再拖死整轮：同一轮内其余照片照常下载、
 *   整轮照常成功（旧实现在此抛 Corrupted → 角标永久 Failed → 每轮重试同一张的死循环）；
 * - 连续失败达 [PhotoTransfer.PHOTO_QUARANTINE_THRESHOLD] 才进隔离名单，自动轮跳过
 *   下载——「不再请求」直接用 [MiniDavServer.requestLog] 的 GET 计数断言（坏照片是
 *   确定性坏，「被请求必计数」与「请求日志零增长」互为印证）；
 * - 成功下载（哈希校验通过）即清零计数：低于阈值时下一轮自动轮即恢复；
 * - 手动「立即同步」重试一次作逃生口：仍损坏 → 当场重新隔离；内容修复 → 正常下载并解除；
 * - 隔离状态随 SyncStore.clearAll()（resetSync，R-05）清空。
 *
 * 注入方式：按受害者 hash 的假名直写「名实不符」密文（sha256 校验必不符）；
 * 修复注入：同假名覆写正确密文（不带 If-None-Match；uploadPhoto 的条件写会 412 吞掉）。
 */
class PhotoQuarantineTest {

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
        workRoot = File(System.getProperty("java.io.tmpdir"), "sl-photo-quarantine-${System.nanoTime()}")
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
    fun corruptPhotoDoesNotStallRound_othersStillDownloaded() = runBlocking {
        val d = device("A")
        plantPhoto(BAD_HASH, wrongBytes)
        plantPhoto(GOOD_HASH, goodBytes)

        // 坏照片排在前面：同轮内不得阻塞后面的好照片（旧实现在这里整轮抛 Corrupted）
        d.refs.hashes = listOf(BAD_HASH, GOOD_HASH)
        val outcome = d.engine.syncOnce(SyncTrigger.COLD_START)

        // U-7 照片侧核心：坏照片不拖死整轮，好照片照常落地
        assertTrue("含坏照片的轮次仍应成功", outcome.success)
        assertArrayEquals("好照片应照常下载落盘", goodBytes, d.photos.files[GOOD_HASH])
        assertEquals("down 只数成功校验的照片", 1, outcome.photo.down)
        // 首败只计数不隔离（给下载截断等偶发损坏留重试余地）
        assertEquals(1, d.syncStore.photoFailureCount(badName(d)))
        assertTrue(d.syncStore.quarantinedPhotos().isEmpty())
        assertEquals(0, outcome.quarantinedPhotos)
        assertNull("坏照片不得记台账", d.core.dao.getRemoteFile(badName(d)))
        assertNull("坏照片内容不得落盘", d.photos.files[BAD_HASH])
    }

    @Test
    fun threeConsecutiveFailuresQuarantinePhoto_autoRoundsSkipAfterwards() = runBlocking {
        val d = device("A")
        plantPhoto(BAD_HASH, wrongBytes)
        d.refs.hashes = listOf(BAD_HASH)

        repeat(PhotoTransfer.PHOTO_QUARANTINE_THRESHOLD - 1) {
            val outcome = d.engine.syncOnce(SyncTrigger.FOREGROUND)
            assertTrue(outcome.success)
        }
        assertTrue("未达阈值不隔离", d.syncStore.quarantinedPhotos().isEmpty())

        // 第 3 次失败：进隔离
        val third = d.engine.syncOnce(SyncTrigger.FOREGROUND)
        assertTrue(third.success)
        assertTrue(
            "连续失败达阈值应隔离",
            d.syncStore.quarantinedPhotos().contains(badName(d)),
        )
        assertEquals(1, third.quarantinedPhotos)

        // 隔离后的自动轮：跳过下载。「不再请求」用 MiniDavServer 请求日志直接计数——
        // 该路径此前每轮被 GET 一次（3 次失败 = 3 条 GET），本轮必须零增长
        val corruptGets = "GET /dav/${WebDavRemote.DIR}/${badName(d)}"
        val getsBefore = server.requestLog.count { it.startsWith(corruptGets) }
        assertEquals("前 3 轮每轮下载一次坏照片", PhotoTransfer.PHOTO_QUARANTINE_THRESHOLD, getsBefore)
        val skipped = d.engine.syncOnce(SyncTrigger.PERIODIC)
        assertTrue(skipped.success)
        assertEquals(
            "隔离照片不再发起 GET（请求计数断言）",
            getsBefore,
            server.requestLog.count { it.startsWith(corruptGets) },
        )
        assertEquals("隔离照片跳过计入 skipped", 1, skipped.photo.skipped)
        assertEquals("隔离照片不应再计数", 3, d.syncStore.photoFailureCount(badName(d)))
        assertEquals(1, skipped.quarantinedPhotos)
    }

    @Test
    fun successDownloadResetsCount_subThresholdRecoversOnAutoRound() = runBlocking {
        val d = device("A")
        plantPhoto(BAD_HASH, wrongBytes)
        d.refs.hashes = listOf(BAD_HASH)
        val first = d.engine.syncOnce(SyncTrigger.COLD_START)
        assertTrue(first.success)
        assertEquals(1, d.syncStore.photoFailureCount(badName(d)))

        // 云端内容被修复（同假名覆写受害照片的真实内容）；未达阈值从未隔离，
        // 不需要手动同步——下一轮自动轮即拉到并清零计数（U-7：成功下载即清零）
        plantPhoto(BAD_HASH, victimBytes)
        val second = d.engine.syncOnce(SyncTrigger.FOREGROUND)
        assertTrue(second.success)
        assertArrayEquals("修复后的照片应正常落盘", victimBytes, d.photos.files[BAD_HASH])
        assertEquals("成功下载清零计数", 0, d.syncStore.photoFailureCount(badName(d)))
        assertTrue(d.syncStore.quarantinedPhotos().isEmpty())
        assertEquals(0, second.quarantinedPhotos)
        assertEquals("成功下载不计 skipped", 0, second.photo.skipped)
    }

    @Test
    fun manualSyncRetriesQuarantinedPhoto_stillCorruptRequarantines() = runBlocking {
        val d = device("A")
        plantPhoto(BAD_HASH, wrongBytes)
        d.refs.hashes = listOf(BAD_HASH)
        repeat(PhotoTransfer.PHOTO_QUARANTINE_THRESHOLD) { d.engine.syncOnce(SyncTrigger.COLD_START) }
        assertTrue(d.syncStore.quarantinedPhotos().contains(badName(d)))

        // 手动「立即同步」= 逃生口：重试一次，仍损坏 → 当场重新隔离（计数 4）
        val retry = d.engine.syncOnce(SyncTrigger.MANUAL)
        assertTrue(retry.success)
        assertTrue("仍损坏应重新隔离", d.syncStore.quarantinedPhotos().contains(badName(d)))
        assertEquals(4, d.syncStore.photoFailureCount(badName(d)))
        assertNull("仍损坏不得记台账", d.core.dao.getRemoteFile(badName(d)))
    }

    @Test
    fun repairedPhotoRecoversOnManualSync_quarantineAndCountCleared() = runBlocking {
        val d = device("A")
        plantPhoto(BAD_HASH, wrongBytes)
        d.refs.hashes = listOf(BAD_HASH)
        repeat(PhotoTransfer.PHOTO_QUARANTINE_THRESHOLD) { d.engine.syncOnce(SyncTrigger.COLD_START) }
        assertTrue(d.syncStore.quarantinedPhotos().contains(badName(d)))

        // 云端内容被修复（同假名覆写受害照片的真实内容）→ 手动同步重试成功
        plantPhoto(BAD_HASH, victimBytes)
        val recovered = d.engine.syncOnce(SyncTrigger.MANUAL)
        assertTrue(recovered.success)
        assertArrayEquals("修复后的照片应正常落盘", victimBytes, d.photos.files[BAD_HASH])
        assertTrue("解除隔离", d.syncStore.quarantinedPhotos().isEmpty())
        assertEquals("成功下载清零计数", 0, d.syncStore.photoFailureCount(badName(d)))
        assertEquals(0, recovered.quarantinedPhotos)
    }

    @Test
    fun resetClearsPhotoQuarantineState() {
        val d = device("A")
        d.syncStore.recordPhotoFailure(badName(d))
        d.syncStore.quarantinePhoto(badName(d))

        // resetSync（R-05）经 SyncStore.clearAll() 清空全部隔离状态
        d.syncStore.clearAll()
        assertTrue(d.syncStore.quarantinedPhotos().isEmpty())
        assertEquals(0, d.syncStore.photoFailureCount(badName(d)))
    }

    // ------------------------------------------------------------ 夹具

    /** 一台「设备」= SyncTestDevice（合并语义全生产）+ SyncEngine 全链（真实 HTTP + 加密管道） */
    private fun device(name: String): Device {
        val d = Device(name)
        d.core.seedRow(RowKind.SECTION, "sec-1", OpCodec.sectionSnapshot("生活", 1, "", 0, 0, 0, 1_000L))
        return d
    }

    private inner class Device(name: String) {
        val core = SyncTestDevice(name)
        val syncStore = FakeSyncStore()
        val photos = FakePhotoStore()
        val refs = FakePhotoRefs()
        val remote: WebDavRemote = WebDavRemote(
            MiniWebDavClient(server.baseUrl.toHttpUrl(), "user", "app-pass", newParser = { KXmlParser() }),
            keys,
            kdf,
            File(workRoot, name),
        )
        val photo = PhotoTransfer(core.dao, photos, refs, FakeNetworkStatus(), syncStore)
        val engine = SyncEngine(core.dao, core.applier, photo, syncStore, { remote })
    }

    /** 坏照片的云端假名（受害 hash 的确定性假名，密体名实不符） */
    private fun badName(d: Device): String = d.remote.photoRemoteName(BAD_HASH)

    /** 直写云端照片密文（seal 后 PUT，不带 If-None-Match：可覆写既有假名 = 修复注入） */
    private fun plantPhoto(contentHashHex: String, plain: ByteArray) {
        val blob = crypto.seal(
            key = keys.encKey,
            purpose = CryptoFormat.PURPOSE_PHOTO,
            plaintext = plain,
            kdf = kdf,
            kcv = null,
        )
        val name = FilenameNym.photoName(keys.nameKey, contentHashHex)
        runBlocking { dav.put("${WebDavRemote.DIR}/$name", blob.toRequestBody(BINARY)) }
    }

    companion object {
        private val BINARY = "application/octet-stream".toMediaType()

        /** 受害照片的**真实内容**（修复注入 = 把它种回云端；坏照片注入 = 种别的字节） */
        private val victimBytes = "受害照片原件".toByteArray()

        /** 受害照片的内容哈希（坏照片 = 同假名下种名实不符的字节，sha256 校验必不符） */
        private val BAD_HASH = PhotoTransfer.sha256Hex(victimBytes)

        /** 好照片内容与其哈希（自洽：种什么验什么） */
        private val goodBytes = ByteArray(48 * 1024) { (it % 251).toByte() }
        private val GOOD_HASH = PhotoTransfer.sha256Hex(goodBytes)

        private val wrongBytes = "狸猫换太子——坏照片注入".toByteArray()
    }
}
