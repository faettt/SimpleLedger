package com.simpleledger.app.sync

import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncKeys
import com.simpleledger.app.sync.dav.DavException
import com.simpleledger.app.sync.dav.MiniWebDavClient
import com.simpleledger.app.sync.dav.WebDavRemote
import com.simpleledger.app.sync.photo.FilePhotoStore
import com.simpleledger.app.sync.photo.PhotoStore
import com.simpleledger.app.sync.photo.PhotoSyncReport
import com.simpleledger.app.sync.photo.PhotoTransfer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.util.Random

/**
 * 照片管线（R-16 去重 / R-17 Wi-Fi 门控 / R-19 月流量记账 / R-23 断点续传）JVM 实测。
 *
 * 口径映射：
 * - Wi-Fi 门控矩阵（wifiOnlyPhotos × Wi-Fi/蜂窝/离线 六格）：R-17/Q-5；
 * - 二次同步零请求（台账去重）：S3「照片二次传输流量≈0」+ R-16；
 * - 对端下行 + sha256 校验、坏内容抛 Corrupted：R-12 数据完整性；
 * - 配额顶格仅照片挂起：R-19/Q-5（坚果云 1GiB 上 / 3GiB 下）；
 * - photoPartialLength + 半成品续传收满：R-23（不产生重复云端文件——内容寻址单名）。
 *
 * 传输底座 = 生产 WebDavRemote 原样对 MiniDavServer 真实 HTTP（与 MiniWebDavClientTest 同法）。
 */
class PhotoTransferTest {

    private lateinit var server: MiniDavServer
    private lateinit var workRoot: File
    private val crypto = SyncCrypto()
    private val kdf = KdfParams(mKiB = 32, t = 1, p = 1, salt = ByteArray(16) { 9 })
    private lateinit var keys: SyncKeys

    @Before
    fun setUp() {
        server = MiniDavServer().also { it.start() }
        workRoot = File(System.getProperty("java.io.tmpdir"), "sl-photo-test-${System.nanoTime()}")
        workRoot.mkdirs()
        keys = crypto.deriveKeys("正确口令".toCharArray(), kdf)
    }

    @After
    fun tearDown() {
        server.stop()
        workRoot.deleteRecursively()
    }

    // ------------------------------------------------------------ R-17：Wi-Fi 门控矩阵

    @Test
    fun wifiGateMatrixSixCells() = runBlocking {
        data class Cell(
            val wifiOnly: Boolean,
            val online: Boolean,
            val wifi: Boolean,
            val expectUp: Int,
            val expectPaused: Boolean,
        )
        val cells = listOf(
            Cell(wifiOnly = true, online = true, wifi = true, expectUp = 1, expectPaused = false),   // 仅WiFi×Wi-Fi → 传
            Cell(wifiOnly = true, online = true, wifi = false, expectUp = 0, expectPaused = true),   // 仅WiFi×蜂窝 → 挂起
            Cell(wifiOnly = true, online = false, wifi = false, expectUp = 0, expectPaused = true),  // 仅WiFi×离线 → 挂起
            Cell(wifiOnly = false, online = true, wifi = true, expectUp = 1, expectPaused = false),  // 不限×Wi-Fi → 传
            Cell(wifiOnly = false, online = true, wifi = false, expectUp = 1, expectPaused = false), // 不限×蜂窝 → 传（R-17 反向）
            Cell(wifiOnly = false, online = false, wifi = true, expectUp = 0, expectPaused = true),  // 不限×离线 → 挂起
        )
        for ((index, cell) in cells.withIndex()) {
            val hash = PhotoTransfer.sha256Hex("照片-$index".toByteArray())
            val store = FakeSyncStore().apply { wifiOnlyPhotos = cell.wifiOnly }
            val photos = FakePhotoStore().apply { files[hash] = "照片-$index".toByteArray() }
            val network = FakeNetworkStatus(online = cell.online, wifi = cell.wifi)
            val transfer = PhotoTransfer(FakeSyncDao(), photos, FakePhotoRefs(listOf(hash)), network, store)
            val remote = remote(File(workRoot, "cell-$index"))
            val logBefore = server.requestLog.size

            val report = transfer.syncPendingPhotos(remote)
            assertEquals("[$index] 上行张数", cell.expectUp, report.up)
            assertEquals("[$index] paused", cell.expectPaused, report.paused)
            if (cell.expectUp == 0) {
                assertEquals("[$index] 门控拦下应零请求（S6/R-17）", logBefore, server.requestLog.size)
                assertEquals("[$index] isPhotoAllowedNow", !cell.online || (cell.wifiOnly && !cell.wifi), !transfer.isPhotoAllowedNow())
            } else {
                assertTrue("[$index] 真实上行有流量", report.bytesUp > 0)
                assertEquals("[$index] 月流量已记账", report.bytesUp, store.photoUp)
            }
        }
    }

    // ------------------------------------------------------------ S3 / R-16：二次同步零流量

    @Test
    fun secondRoundUploadIsZeroTraffic() = runBlocking {
        val hash = PhotoTransfer.sha256Hex("去重上行".toByteArray())
        val photos = FakePhotoStore().apply { files[hash] = "去重上行".toByteArray() }
        val store = FakeSyncStore()
        val transfer = PhotoTransfer(FakeSyncDao(), photos, FakePhotoRefs(listOf(hash)), FakeNetworkStatus(), store)
        val remote = remote(File(workRoot, "dedupe-up"))

        val first = transfer.syncPendingPhotos(remote)
        assertEquals(1, first.up)
        val logAfterFirst = server.requestLog.size

        val second = transfer.syncPendingPhotos(remote)
        assertEquals("二次同步零上行（S3）", 0, second.up)
        assertEquals(1, second.skipped) // 台账去重命中
        assertFalse(second.paused)
        assertEquals("二次同步零请求（S3/R-16）", logAfterFirst, server.requestLog.size)

        // 对端再传同内容：HEAD 去重命中一次后记台账，后续零流量
        val photosPeer = FakePhotoStore().apply { files[hash] = "去重上行".toByteArray() }
        val peerStore = FakeSyncStore()
        val peer = PhotoTransfer(FakeSyncDao(), photosPeer, FakePhotoRefs(listOf(hash)), FakeNetworkStatus(), peerStore)
        val firstPeer = peer.syncPendingPhotos(remote(File(workRoot, "dedupe-peer")))
        assertEquals("云端已有同 hash → 去重不重传", 0, firstPeer.up)
        assertEquals(1, firstPeer.skipped)
        assertEquals("去重命中零上行流量", 0L, peerStore.photoUp)
    }

    @Test
    fun secondRoundDownloadIsZeroTraffic() = runBlocking {
        val bytes = ByteArray(64 * 1024).also { Random(11).nextBytes(it) }
        val hash = PhotoTransfer.sha256Hex(bytes)
        val photosA = FakePhotoStore().apply { files[hash] = bytes }
        val transferA = PhotoTransfer(FakeSyncDao(), photosA, FakePhotoRefs(listOf(hash)), FakeNetworkStatus(), FakeSyncStore())
        assertEquals(1, transferA.syncPendingPhotos(remote(File(workRoot, "dl-a"))).up)

        val photosB = FakePhotoStore()
        val storeB = FakeSyncStore()
        val transferB = PhotoTransfer(FakeSyncDao(), photosB, FakePhotoRefs(listOf(hash)), FakeNetworkStatus(), storeB)
        val remoteB = remote(File(workRoot, "dl-b"))

        val first = transferB.syncPendingPhotos(remoteB)
        assertEquals(1, first.down)
        assertTrue(first.bytesDown > 0)
        assertArrayEquals(bytes, photosB.files[hash]) // sha256 校验通过才落盘
        assertEquals(first.bytesDown, storeB.photoDown) // R-19 记账
        val logAfterFirst = server.requestLog.size

        val second = transferB.syncPendingPhotos(remoteB)
        assertEquals("二次同步零下行（S3）", 0, second.down)
        assertEquals(1, second.skipped)
        assertEquals("二次同步零请求", logAfterFirst, server.requestLog.size)
    }

    // ------------------------------------------------------------ 数据完整性

    /**
     * U-16 回归：本地坏哈希文件（进程写入中途死亡留下的截断内容）**不照传不记台账**——
     * 旧实现上行路径不校验本地字节哈希，坏文件照传后对端每轮下载必抛 Corrupted、
     * 整轮同步永久失败无自愈。现判定为缺失转下行：云端权威副本原子覆写自愈。
     */
    @Test
    fun corruptLocalBytesAreNotUploaded_healedFromCloud() = runBlocking {
        val bytes = ByteArray(48 * 1024).also { Random(7).nextBytes(it) }
        val hash = PhotoTransfer.sha256Hex(bytes)
        // 云端先有权威副本（对端正常上传）
        transferOf(FakePhotoStore().apply { files[hash] = bytes }, listOf(hash))
            .syncPendingPhotos(remote(File(workRoot, "u16-src")))

        // 本机：同名 hash 下是被截断/篡改过的坏文件
        val photos = FakePhotoStore().apply { files[hash] = bytes.copyOfRange(0, bytes.size / 2) }
        val store = FakeSyncStore()
        val transfer = PhotoTransfer(FakeSyncDao(), photos, FakePhotoRefs(listOf(hash)), FakeNetworkStatus(), store)

        val report = transfer.syncPendingPhotos(remote(File(workRoot, "u16-victim")))

        assertEquals("坏文件不得上行", 0, report.up)
        assertEquals("转下行自愈", 1, report.down)
        assertArrayEquals("本地被云端权威副本覆写", bytes, photos.files[hash])
        // 自愈后归零流量：台账去重命中，零请求零传输（S3）
        val healed = transfer.syncPendingPhotos(remote(File(workRoot, "u16-victim")))
        assertEquals(1, healed.skipped)
        assertEquals(0, healed.up)
        assertEquals(0, healed.down)
    }

    /**
     * U-16 回归：本地坏哈希且云端也没有 → 不上传（防对端永久 CORRUPTED）、不记台账、
     * 轮次仍成功收场（失败轮循环只会拖死全部同步；字节本地已损坏，无从恢复）。
     */
    @Test
    fun corruptLocalBytesWithNoCloudCopySkipQuietly() = runBlocking {
        val hash = PhotoTransfer.sha256Hex("原件早已不在".toByteArray())
        val photos = FakePhotoStore().apply { files[hash] = "截断残片".toByteArray() }
        val transfer = PhotoTransfer(FakeSyncDao(), photos, FakePhotoRefs(listOf(hash)), FakeNetworkStatus(), FakeSyncStore())

        val report = transfer.syncPendingPhotos(remote(File(workRoot, "u16-lost")))

        assertEquals("坏文件不得上行", 0, report.up)
        assertEquals("云端无副本只能跳过", 1, report.skipped)
        assertFalse(report.paused)
    }

    /**
     * U-16 回归：`FilePhotoStore.writeBytes` 先写临时文件再 rename 原子替换——
     * 落盘内容完整、目录里不留 `.tmp` 残骸、同名覆写生效。
     */
    @Test
    fun filePhotoStoreWritesAtomicallyWithoutTmpLeftovers() {
        val dir = File(workRoot, "u16-store")
        val store = FilePhotoStore { hash -> File(dir, "$hash.jpg").absolutePath }
        val bytes = ByteArray(200 * 1024).also { Random(9).nextBytes(it) }
        val hash = PhotoTransfer.sha256Hex(bytes)

        store.writeBytes(hash, bytes)
        assertArrayEquals(bytes, store.readBytes(hash))
        assertEquals("目录只应有内容寻址这一个文件", 1, dir.listFiles()!!.size)
        assertTrue("不得遗留临时文件", dir.listFiles()!!.none { it.name.contains(".tmp") })

        // 覆写（rename 语义 = 原子替换既有文件）与坏字节修复路径
        val fixed = ByteArray(64 * 1024).also { Random(10).nextBytes(it) }
        val fixedHash = PhotoTransfer.sha256Hex(fixed)
        store.writeBytes(fixedHash, fixed)
        assertArrayEquals(fixed, store.readBytes(fixedHash))
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun corruptPhotoContentThrowsCorrupted() = runBlocking {
        val expect = "应被保护的内容".toByteArray()
        val hash = PhotoTransfer.sha256Hex(expect)
        // 云端被塞入「名实不符」内容：假名按 expect 的 hash，密体是别的字节
        val evil = remote(File(workRoot, "evil"))
        evil.uploadPhoto(hash, "狸猫换太子".toByteArray()) {}

        val transfer = PhotoTransfer(FakeSyncDao(), FakePhotoStore(), FakePhotoRefs(listOf(hash)), FakeNetworkStatus(), FakeSyncStore())
        try {
            transfer.syncPendingPhotos(remote(File(workRoot, "victim")))
            fail("sha256 不符应抛 DavException.Corrupted")
        } catch (e: DavException.Corrupted) {
            // 预期：坏内容不落盘
        }
    }

    // ------------------------------------------------------------ R-19 / Q-5：配额顶格只挂起照片

    @Test
    fun quotaTopPausesPhotosOnly() = runBlocking {
        val hash = PhotoTransfer.sha256Hex("超额照片".toByteArray())
        val photos = FakePhotoStore().apply { files[hash] = "超额照片".toByteArray() }
        val store = FakeSyncStore()
        store.addPhotoTraffic(PhotoTransfer.QUOTA_UPLOAD_BYTES, 0) // 上行顶格
        val transfer = PhotoTransfer(FakeSyncDao(), photos, FakePhotoRefs(listOf(hash)), FakeNetworkStatus(), store)

        val report = transfer.syncPendingPhotos(remote(File(workRoot, "quota")))
        assertEquals(0, report.up)
        assertTrue(report.paused) // 仅照片挂起，账目照常（引擎层不受影响）
        assertEquals(1, report.skipped)
        assertTrue("下行仍有余量 ⇒ 总开关不关", transfer.isPhotoAllowedNow())

        store.addPhotoTraffic(0, PhotoTransfer.QUOTA_DOWNLOAD_BYTES) // 双向顶格
        assertFalse(transfer.isPhotoAllowedNow())
    }

    // ------------------------------------------------------------ R-23：断点续传

    @Test
    fun photoPartialLengthReportsPartSize() {
        val remote = remote(File(workRoot, "parts"))
        val hash = "ab".repeat(32)
        assertEquals("无半成品 = 0", 0L, remote.photoPartialLength(hash))
        val part = File(workRoot, "parts/${remote.photoRemoteName(hash)}.part")
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(123))
        assertEquals("半成品长度即断点偏移", 123L, remote.photoPartialLength(hash))
    }

    @Test
    fun resumeFromPartialCompletesDownload() = runBlocking {
        val bytes = ByteArray(100 * 1024).also { Random(5).nextBytes(it) }
        val hash = PhotoTransfer.sha256Hex(bytes)
        val transferA = PhotoTransfer(FakeSyncDao(), FakePhotoStore().apply { files[hash] = bytes }, FakePhotoRefs(listOf(hash)), FakeNetworkStatus(), FakeSyncStore())
        assertEquals(1, transferA.syncPendingPhotos(remote(File(workRoot, "resume-a"))).up)

        // B 侧预置「中断半成品」：密文前 40000 字节已收
        val remoteB = remote(File(workRoot, "resume-b"))
        val cipher = server.files["${WebDavRemote.DIR}/${remoteB.photoRemoteName(hash)}"]!!
        val part = File(workRoot, "resume-b/${remoteB.photoRemoteName(hash)}.part")
        part.parentFile?.mkdirs()
        part.writeBytes(cipher.copyOfRange(0, 40_000))
        assertEquals(40_000L, remoteB.photoPartialLength(hash))

        val photosB = FakePhotoStore()
        val storeB = FakeSyncStore()
        val transferB = PhotoTransfer(FakeSyncDao(), photosB, FakePhotoRefs(listOf(hash)), FakeNetworkStatus(), storeB)
        val report = transferB.syncPendingPhotos(remoteB)

        assertEquals(1, report.down)
        assertArrayEquals(bytes, photosB.files[hash])
        assertEquals("续传只记增量流量", (cipher.size - 40_000).toLong(), report.bytesDown)
        assertEquals(report.bytesDown, storeB.photoDown)
        assertEquals("收满清理半成品", 0L, remoteB.photoPartialLength(hash))
        // 内容寻址单名：断点续传不产生重复云端文件
        assertEquals(1, server.files.keys.count { it.startsWith("${WebDavRemote.DIR}/") && !it.endsWith(".op") })
    }

    // ------------------------------------------------------------ 夹具

    private fun transferOf(photos: PhotoStore, hashes: List<String>, store: FakeSyncStore = FakeSyncStore()): PhotoTransfer =
        PhotoTransfer(FakeSyncDao(), photos, FakePhotoRefs(hashes), FakeNetworkStatus(), store)

    private fun remote(workDir: File): WebDavRemote = WebDavRemote(
        MiniWebDavClient(server.baseUrl.toHttpUrl(), "user", "app-pass", newParser = { KXmlParser() }),
        keys,
        kdf,
        workDir,
    )
}
