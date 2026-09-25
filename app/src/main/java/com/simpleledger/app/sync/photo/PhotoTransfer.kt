package com.simpleledger.app.sync.photo

import com.simpleledger.app.data.local.dao.EntryDao
import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.RemoteFileEntity
import com.simpleledger.app.data.local.entity.RemoteFileKind
import com.simpleledger.app.sync.SyncStore
import com.simpleledger.app.sync.dav.DavException
import com.simpleledger.app.sync.dav.WebDavRemote
import java.io.File
import java.security.MessageDigest

/**
 * 照片文件存取端口（生产 = `FilePhotoStore` 包 `ImageStorage.pathForHash`；JVM 测试 = 内存 Map）。
 */
interface PhotoStore {
    fun exists(contentHash: String): Boolean
    fun readBytes(contentHash: String): ByteArray?
    fun writeBytes(contentHash: String, bytes: ByteArray)
    fun pathForHash(contentHash: String): String
}

/**
 * 贴图内容哈希清单端口（生产 = `RoomPhotoRefs` 查 `entry_images.contentHash`）。
 * 返回全部**非空**内容哈希（去重在 PhotoTransfer 内做）。
 */
interface PhotoRefs {
    suspend fun contentHashes(): List<String>
}

/** 网络状态端口（生产 = `SyncTriggers.AndroidNetworkStatus`；JVM 测试 = 可拨杆假件） */
interface NetworkStatus {
    fun isOnline(): Boolean

    /** 是否 Wi-Fi（含以太网——同为不计蜂窝流量的链路，R-17） */
    fun isWifi(): Boolean
}

/**
 * 照片管线报告（R-16/17/19/23 的可观测面，T-5 状态详情页展示）。
 *
 * @param up       本次真实上行的照片张数
 * @param down     本次真实下行的照片张数
 * @param skipped  跳过张数（去重命中 / 门控拦下 / 云端还没有 / 续传未收满）
 * @param bytesUp  本次上行字节数（密文实量，R-19 记账口径）
 * @param bytesDown 本次下行字节数（不含续传前已收部分）
 * @param paused   true = 有照片被 Wi-Fi 门控 / 额度拦下，待条件恢复补传（R-17）
 */
data class PhotoSyncReport(
    val up: Int,
    val down: Int,
    val skipped: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val paused: Boolean,
) {
    companion object {
        val EMPTY = PhotoSyncReport(up = 0, down = 0, skipped = 0, bytesUp = 0, bytesDown = 0, paused = false)
    }
}

/**
 * 照片管线（R-16 内容寻址去重 / R-17 Wi-Fi 门控 / R-19 月流量记账 / R-23 断点续传）。
 *
 * 与 §3.7 签名的差异（缺口补齐，T-2/3 先例，调用形态不变）：
 * - `imageStorage/prefs` 端口化为 [PhotoStore]/[PhotoRefs]/[NetworkStatus]/[SyncStore]——
 *   JVM 单测可构造（无 Android Context）；生产装配见 `AppContainer`；
 * - `remote` 从构造参数改为 `syncPendingPhotos(remote)` 入参——账号切换 / setup 后
 *   重建远端不必重建本对象。
 *
 * 门控口径（R-17/Q-5）：`wifiOnlyPhotos`（设备本地偏好）为 true 时仅 Wi-Fi 传照片，
 * 否则在线即传；坚果云免费额度（月上行 1GB / 下行 3GB）顶格后该方向挂起，
 * **账目操作照常同步**。门控拦下 = 报告 `paused = true`，回 Wi-Fi / 跨月自动补传。
 *
 * 去重口径（R-16，S3「二次传输流量≈0」）：内容寻址 + `sync_remote_files` 台账——
 * 已确认在云端的照片（自传 / 去重命中 / 已下载）后续轮次**零请求**；
 * 不确定时才 HEAD 一次（确定性假名，`If-None-Match` 防重复上传）。
 */
class PhotoTransfer(
    private val syncDao: SyncDao,
    private val photos: PhotoStore,
    private val refs: PhotoRefs,
    private val network: NetworkStatus,
    private val store: SyncStore,
) {

    /** 当前是否允许照片传输（连通性 + Wi-Fi 偏好 + 额度，任一方向还有余量） */
    fun isPhotoAllowedNow(): Boolean {
        if (!network.isOnline()) return false
        if (store.wifiOnlyPhotos && !network.isWifi()) return false
        val (up, down) = store.monthlyPhotoUsage()
        return up < QUOTA_UPLOAD_BYTES || down < QUOTA_DOWNLOAD_BYTES
    }

    /** 本月照片流量 (上行, 下行) 字节——R-19 */
    fun monthlyUsage(): Pair<Long, Long> = store.monthlyPhotoUsage()

    /**
     * 一轮照片同步：上行差集（HEAD 去重）→ 下行差集（Range 续传 + 哈希校验）。
     *
     * 异常口径：网络 / DAV / 损坏异常**照抛**（由 `SyncEngine` 统一映射 `SyncError`，
     * S6 静默在引擎层收口）；门控与续传未收满不算失败，走报告 `paused/skipped`。
     */
    suspend fun syncPendingPhotos(remote: WebDavRemote): PhotoSyncReport {
        if (!network.isOnline()) return PhotoSyncReport.EMPTY.copy(paused = true)
        var up = 0
        var down = 0
        var skipped = 0
        var bytesUp = 0L
        var bytesDown = 0L
        var paused = false

        for (hash in refs.contentHashes().filter { it.isNotEmpty() }.distinct()) {
            val local = photos.readBytes(hash)
            if (local != null) {
                // ---- 上行：本地有文件 → 确保云端有 ----
                val name = remote.photoRemoteName(hash)
                if (syncDao.getRemoteFile(name) != null) {
                    // 台账去重命中：已确认在云端，零流量（S3）；计入 skipped（PhotoSyncReport 口径）
                    skipped++
                    continue
                }
                if (!uploadAllowed()) {
                    paused = true
                    skipped++
                    continue
                }
                if (remote.photoHas(hash)) {
                    // R-16 去重命中：HEAD 确认一次并记台账，后续轮次零流量（S3）
                    recordPhotoRemote(name, local.size.toLong())
                    skipped++
                    continue
                }
                var sent = 0L
                remote.uploadPhoto(hash, local) { sent = it }
                recordPhotoRemote(name, sent)
                store.addPhotoTraffic(sent, 0)
                up++
                bytesUp += sent
            } else {
                // ---- 下行：本地缺文件 → 从云端补（含 R-23 断点续传） ----
                if (!downloadAllowed()) {
                    paused = true
                    skipped++
                    continue
                }
                if (!remote.photoHas(hash)) {
                    skipped++ // 云端还没有（对端未传完），不算失败
                    continue
                }
                val resumeFrom = remote.photoPartialLength(hash)
                var received = resumeFrom
                val bytes = remote.downloadPhoto(hash, resumeFrom) { received = it }
                if (bytes == null) {
                    // 未收满：半成品保留，下轮从断点续（R-23）
                    paused = true
                    skipped++
                    continue
                }
                val actual = sha256Hex(bytes)
                if (actual != hash) {
                    throw DavException.Corrupted("照片内容哈希不符：期望 $hash 实际 $actual")
                }
                photos.writeBytes(hash, bytes)
                val delta = (received - resumeFrom).coerceAtLeast(0L)
                recordPhotoRemote(remote.photoRemoteName(hash), received)
                store.addPhotoTraffic(0, delta)
                down++
                bytesDown += delta
            }
        }
        return PhotoSyncReport(up, down, skipped, bytesUp, bytesDown, paused)
    }

    // ------------------------------------------------------------------ 内部

    private fun uploadAllowed(): Boolean =
        (!store.wifiOnlyPhotos || network.isWifi()) &&
            store.monthlyPhotoUsage().first < QUOTA_UPLOAD_BYTES

    private fun downloadAllowed(): Boolean =
        (!store.wifiOnlyPhotos || network.isWifi()) &&
            store.monthlyPhotoUsage().second < QUOTA_DOWNLOAD_BYTES

    /** 照片确认在云端后记台账（后续轮次免 HEAD/免重传的关键） */
    private suspend fun recordPhotoRemote(remoteName: String, size: Long) {
        val now = System.currentTimeMillis()
        syncDao.upsertRemoteFile(
            RemoteFileEntity(
                remoteName = remoteName,
                kind = RemoteFileKind.PHOTO,
                etag = null,
                size = size,
                downloadedAt = now,
                uploadedAt = now,
            )
        )
    }

    companion object {
        /** 坚果云免费档月上行额度（1GB，U-4）；超限仅照片挂起、账目照常（Q-5） */
        const val QUOTA_UPLOAD_BYTES = 1L shl 30

        /** 坚果云免费档月下行额度（3GB） */
        const val QUOTA_DOWNLOAD_BYTES = 3L shl 30

        /** SHA-256 小写 hex（内容寻址口径与 `ImageStorage.promoteToStorage` 一致） */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

/** [PhotoRefs] 的 Room 实现：全部贴图行的非空内容哈希 */
class RoomPhotoRefs(private val entryDao: EntryDao) : PhotoRefs {
    override suspend fun contentHashes(): List<String> = entryDao.allContentHashes()
}

/**
 * [PhotoStore] 的文件系统实现：内容寻址路径由 `ImageStorage.pathForHash` 提供
 * （`filesDir/images/<sha256>.jpg`，T-3 定案）。
 */
class FilePhotoStore(private val pathForHash: (String) -> String) : PhotoStore {
    override fun exists(contentHash: String): Boolean = fileOf(contentHash).exists()

    override fun readBytes(contentHash: String): ByteArray? {
        val file = fileOf(contentHash)
        return if (file.exists()) file.readBytes() else null
    }

    override fun writeBytes(contentHash: String, bytes: ByteArray) {
        val file = fileOf(contentHash)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun pathForHash(contentHash: String): String = pathForHash.invoke(contentHash)

    private fun fileOf(contentHash: String): File = File(pathForHash.invoke(contentHash))
}
