package com.simpleledger.app.sync

import com.simpleledger.app.sync.crypto.CryptoFormat
import com.simpleledger.app.sync.crypto.FilenameNym
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.dav.DavErrors
import com.simpleledger.app.sync.dav.DavException
import com.simpleledger.app.sync.dav.MetaBody
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.dav.fileName
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.security.MessageDigest

/**
 * WebDAV 集成链路实测（T-2 验收③）：对**真实 HTTP 往返**跑通
 * PUT→HEAD→GET→PROPFIND→If-Match 冲突 412→If-None-Match:* 防重→DELETE 全链路。
 *
 * 服务端 = 本文件内嵌的最小 DAV 测试服务（`MiniDavServer`，JDK HttpServer 承载，
 * 支持 MKCOL/PROPFIND/PUT/HEAD/GET[Range]/DELETE + ETag 条件写语义），
 * 客户端 = 生产代码 `MiniWebDavClient` / `WebDavRemote` 原样（仅注入 kxml2 解析器，
 * 避开 JVM 单测里的 android.util.Xml 桩）。坚果云真实账号实测留给用户人工验收（U-4）。
 */
class MiniWebDavClientTest {

    private lateinit var server: MiniDavServer
    private lateinit var workDir: File

    private val crypto = SyncCrypto()
    private val kdf = KdfParams(mKiB = 32, t = 1, p = 1, salt = ByteArray(16) { 3 })

    @Before
    fun setUp() {
        server = MiniDavServer().also { it.start() }
        workDir = File(System.getProperty("java.io.tmpdir"), "sl-webdav-test-${System.nanoTime()}")
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

    // ---------- T-2 验收③：全链路 ----------

    @Test
    fun fullChainPutHeadGetPropfindConditionalWritesDelete() = runBlocking {
        val dav = client()
        dav.mkcol("simpleledger-v1/")
        dav.mkcol("simpleledger-v1/") // 幂等：二次建目录不抛

        // PUT 首写（If-None-Match: *）
        val put = dav.put("simpleledger-v1/a.op", "hello".toRequestBody(null), ifNoneMatchStar = true)
        assertNotNull(put.etag)

        // HEAD
        val head = dav.head("simpleledger-v1/a.op")
        assertEquals(5L, head?.size)
        assertEquals(put.etag, head?.etag)
        assertNull(dav.head("simpleledger-v1/absent.op")) // 404 → null

        // GET
        val got = dav.get("simpleledger-v1/a.op").use { it.body.string() }
        assertEquals("hello", got)

        // PROPFIND Depth:1
        val listing = dav.propfindDepth1("simpleledger-v1/")
        assertTrue(listing.any { it.fileName() == "a.op" && !it.isDir })
        assertTrue(listing.any { it.isDir }) // 目录自身也在

        // If-None-Match: * 防重 → 412
        try {
            dav.put("simpleledger-v1/a.op", "x".toRequestBody(null), ifNoneMatchStar = true)
            fail("重复首写应 412")
        } catch (e: DavException.Http) {
            assertEquals(412, e.code)
        }

        // If-Match 覆盖写：etag 对 → 成功；陈旧 etag → 412
        val overwrite = dav.put("simpleledger-v1/a.op", "hello2".toRequestBody(null), ifMatch = put.etag)
        assertNotNull(overwrite.etag)
        try {
            dav.put("simpleledger-v1/a.op", "x".toRequestBody(null), ifMatch = "\"stale-etag\"")
            fail("陈旧 etag 覆盖写应 412")
        } catch (e: DavException.Http) {
            assertEquals(412, e.code)
        }
        assertEquals("hello2", dav.get("simpleledger-v1/a.op").use { it.body.string() })

        // DELETE + 404 幂等
        dav.delete("simpleledger-v1/a.op")
        assertNull(dav.head("simpleledger-v1/a.op"))
        dav.delete("simpleledger-v1/a.op")
    }

    @Test
    fun getSupportsRangeResume() = runBlocking {
        val dav = client()
        dav.mkcol("simpleledger-v1/")
        dav.put("simpleledger-v1/big.bin", ByteArray(100) { it.toByte() }.toRequestBody(null), ifNoneMatchStar = true)
        val tail = dav.get("simpleledger-v1/big.bin", rangeStart = 40).use { it.body.bytes() }
        assertEquals(60, tail.size)
        assertEquals(40.toByte(), tail[0])
        val window = dav.get("simpleledger-v1/big.bin", rangeStart = 0, rangeEnd = 55).use { it.body.bytes() }
        assertEquals(56, window.size)
    }

    // ---------- WebDavRemote 云端布局 / 密文管道 ----------

    @Test
    fun remoteChunkAndPhotoRoundTripWithDedupe() = runBlocking {
        val keys = crypto.deriveKeys("正确口令".toCharArray(), kdf)
        val remote = WebDavRemote(client(), keys, kdf, workDir)
        remote.ensureLayout()

        // 操作分片：gzip+seal 上传 → 下载解密逐字节一致；随机名互不重复
        val plain = "[{\"opId\":\"x\"}]".toByteArray()
        val name = remote.uploadChunk(plain)
        assertTrue(name.endsWith(".op"))
        assertArrayEquals(plain, remote.downloadChunk(name))
        assertNotEquals(name, remote.uploadChunk(plain))

        // 照片内容寻址：同 hash 只占一份，二次上传 = 去重命中（If-None-Match:* 撞 412 被吞）
        val photo = ByteArray(100 * 1024) { (it % 251).toByte() }
        val hash = sha256Hex(photo)
        assertFalse(remote.photoHas(hash))
        remote.uploadPhoto(hash, photo) {}
        assertTrue(remote.photoHas(hash))
        remote.uploadPhoto(hash, photo) {}

        // listRemote 只见密文与随机名（S4）
        val names = remote.listRemote().map { it.fileName() }
        assertTrue(names.contains(name))
        assertTrue(names.none { it.contains("meta") || it.contains("photo") })
    }

    @Test
    fun metaWriteReadAndKcvRejectsWrongPassword() = runBlocking {
        val keys = crypto.deriveKeys("正确口令".toCharArray(), kdf)
        val remote = WebDavRemote(client(), keys, kdf, workDir)
        remote.ensureLayout()
        remote.writeMetaOnce(MetaBody(bookId = "book-1", createdAt = 42L))
        assertEquals(MetaBody("book-1", 42L), remote.readMeta())

        // 首写竞争：再写一次走 412 → 重读校验，口令一致视作成功
        remote.writeMetaOnce(MetaBody(bookId = "book-另一台", createdAt = 43L))
        assertEquals(MetaBody("book-1", 42L), remote.readMeta()) // 先写者赢

        // 无口令发现（首次接入 setup 流程的关键步骤）
        val header = remote.peekMetaHeader()
        assertEquals(CryptoFormat.PURPOSE_META, header?.purpose)
        assertArrayEquals(keys.kcv, header?.kcv)

        // R-04：口令不一致 → BadPassword（此时只探过 56B 头，未下载任何数据）
        val wrongKeys = crypto.deriveKeys("错误口令".toCharArray(), kdf)
        val wrongRemote = WebDavRemote(client(), wrongKeys, kdf, workDir)
        try {
            wrongRemote.readMeta()
            fail("口令不一致应抛 DavException.BadPassword")
        } catch (e: DavException.BadPassword) {
            // 预期
        }
    }

    @Test
    fun photoDownloadResumesFromPartialFile() = runBlocking {
        val keys = crypto.deriveKeys("正确口令".toCharArray(), kdf)
        val remote = WebDavRemote(client(), keys, kdf, workDir)
        remote.ensureLayout()

        // 随机字节（不可压缩）⇒ 密文长度 ≈ 明文 + 头 + tag，断点演练可预知偏移
        val photo = ByteArray(100 * 1024).also { java.util.Random(7).nextBytes(it) }
        val hash = sha256Hex(photo)
        remote.uploadPhoto(hash, photo) {}

        // 模拟中断：半成品密文只落了前 40000 字节
        val cipherKey = "simpleledger-v1/${FilenameNym.photoName(keys.nameKey, hash)}"
        val fullCipher = server.files[cipherKey]!!
        val part = File(workDir, "${FilenameNym.photoName(keys.nameKey, hash)}.part")
        part.writeBytes(fullCipher.copyOfRange(0, 40_000))

        val progress = mutableListOf<Long>()
        val result = remote.downloadPhoto(hash, resumeFrom = 40_000L) { progress.add(it) }
        assertArrayEquals(photo, result)
        assertFalse("收满后半成品应清理", part.exists())
        assertEquals(fullCipher.size.toLong(), progress.last())
    }

    // ---------- HTTP → SyncError 映射（U-4/T4 定稿：401 才是凭证码，403 独立成档） ----------

    @Test
    fun davErrorsMapToSyncErrorNames() {
        assertEquals("AUTH", DavErrors.httpToSyncError(401))
        assertEquals("AUTH", DavErrors.httpToSyncError(407))
        assertEquals(
            "ACCESS_DENIED",
            DavErrors.httpToSyncError(403), // U-4/T4 定稿：≥4 种语义，不可并进 AUTH / QUOTA
        )
        assertEquals("CONFLICT_WRITE", DavErrors.httpToSyncError(412))
        assertEquals("QUOTA", DavErrors.httpToSyncError(413))
        assertEquals("QUOTA", DavErrors.httpToSyncError(507))
        assertEquals("QUOTA", DavErrors.httpToSyncError(509))
        assertEquals("NETWORK", DavErrors.httpToSyncError(423)) // DAV 锁占用 = 瞬态
        assertEquals("NETWORK", DavErrors.httpToSyncError(503)) // 坚果云频控实测走 503（调研 §3）
        assertEquals("NETWORK", DavErrors.httpToSyncError(429)) // 限流（防御性分支）
        assertEquals("UNKNOWN", DavErrors.httpToSyncError(404))

        assertEquals("BAD_PASSWORD", DavErrors.toSyncErrorName(DavException.BadPassword()))
        assertEquals("CORRUPTED", DavErrors.toSyncErrorName(DavException.Corrupted("坏")))
        assertEquals(
            "NETWORK",
            DavErrors.toSyncErrorName(DavException.Network(java.io.IOException("断网"))),
        )
    }

    private companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
