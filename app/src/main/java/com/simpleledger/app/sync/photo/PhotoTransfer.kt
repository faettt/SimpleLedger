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
import kotlin.coroutines.cancellation.CancellationException
import com.simpleledger.app.util.SlLog

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
     * 异常口径：网络 / DAV 异常**照抛**（由 `SyncEngine` 统一映射 `SyncError`，
     * S6 静默在引擎层收口）；单张照片的**确定性损坏**（下载/解密/哈希校验失败）按
     * U-7 照片侧对称口径只计这一张——连续失败达 [PHOTO_QUARANTINE_THRESHOLD] 隔离，
     * 其余照片照常同步、整轮不再被拖成永久 Failed。[retryQuarantined] = true
     * （手动「立即同步」）是被隔离照片的逃生口：先解除隔离重试，仍损坏按计数当场重新隔离。
     * 门控与续传未收满不算失败，走报告 `paused/skipped`。
     */
    suspend fun syncPendingPhotos(
        remote: WebDavRemote,
        retryQuarantined: Boolean = false,
    ): PhotoSyncReport {
        if (!network.isOnline()) return PhotoSyncReport.EMPTY.copy(paused = true)
        var up = 0
        var down = 0
        var skipped = 0
        var bytesUp = 0L
        var bytesDown = 0L
        var paused = false

        // U-7 照片侧对称（口径照抄 SyncEngine 分片隔离）：自动轮跳过被隔离照片；
        // MANUAL「立即同步」不跳——先解除隔离重试一次（逃生口）
        val quarantined = store.quarantinedPhotos()
        val skipSet = if (retryQuarantined) emptySet() else quarantined

        for (hash in refs.contentHashes().filter { it.isNotEmpty() }.distinct()) {
            val name = remote.photoRemoteName(hash)
            var local = photos.readBytes(hash)
            if (local != null && sha256Hex(local) != hash) {
                // U-16：本地字节与内容寻址哈希不符 = 进程中途死亡留下的截断坏文件
                // （写路径已原子化，此处兼容存量坏文件）。坏文件**不照传不记台账**——
                // 照传会让对端每轮下载必抛 Corrupted、整轮同步永久失败无自愈。
                // 视同缺失走下行分支：云端有权威副本即原子覆写自愈；云端也没有则跳过
                // （字节本地已损坏，失败轮循环只会拖死全部同步）。
                local = null
            }
            if (local != null) {
                // ---- 上行：本地有文件 → 确保云端有 ----
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
                if (name in skipSet) {
                    skipped++ // U-7：隔离照片本轮跳过（缺失经状态详情透出，非静默）
                    continue
                }
                if (name in quarantined) store.clearQuarantinedPhoto(name) // 手动重试：先解除再试
                if (!remote.photoHas(hash)) {
                    skipped++ // 云端还没有（对端未传完），不算失败
                    continue
                }
                var received = remote.photoPartialLength(hash)
                val resumeFrom = received
                val downloaded = try {
                    val dl = remote.downloadPhoto(hash, resumeFrom) { received = it }
                    val plain = dl?.plain
                    if (plain != null) {
                        val actual = PhotoTransfer.sha256Hex(plain)
                        if (actual != hash) {
                            // 哈希不符是**确定性坏**（非瞬态）：同样计数 → 隔离，而非无限重试
                            throw DavException.Corrupted("照片内容哈希不符：期望 $hash 实际 $actual")
                        }
                    }
                    dl
                } catch (e: CancellationException) {
                    throw e // 协程取消不是照片坏：吞掉会把整轮取消误记成所有照片连续失败
                } catch (t: Throwable) {
                    // U-7 照片侧对称，计数口径对齐分片侧（2026-10-01 拍板）：只把**确定性
                    // 损坏**计入隔离名单；瞬态网络错/HTTP 错本轮跳过、下轮重试，不让坏运气
                    // 把好照片关进名单。其余照片照常同步。
                    remote.deletePhotoPartial(hash)
                    if (t.isPhotoCorruption()) {
                        onPhotoFailure(name, t)
                    } else {
                        SlLog.d(
                            "PhotoTransfer",
                            "photo $name 瞬态失败本轮跳过（不计数）: ${t.message ?: t.javaClass.name}",
                        )
                    }
                    continue
                }
                // 任何轮次都按「本次实收」记账（2026-10-01 审查补漏：多段续传的中间段
                // 此前只在完成轮计尾段，中间段流量全部漏计）
                if (downloaded != null && downloaded.wireBytes > 0) {
                    store.addPhotoTraffic(0, downloaded.wireBytes)
                    bytesDown += downloaded.wireBytes
                }
                val plain = downloaded?.plain
                if (plain == null) {
                    // 未收满：半成品保留，下轮从断点续（R-23）；本轮流量已按实收记账
                    paused = true
                    skipped++
                    continue
                }
                store.clearPhotoFailure(name) // U-7：成功即清零（截断下载等偶发损坏不积累）
                photos.writeBytes(hash, plain)
                recordPhotoRemote(name, received)
                down++
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

    /**
     * 口径对齐分片侧 U-7（2026-10-01 拍板）：只有确定性损坏计入隔离。
     * 照片路径的确定性坏统一是 [DavException.Corrupted]——哈希不符在本地抛、
     * 解密失败在 WebDavRemote.open 抛时已包装为同类；网络/HTTP 错一律视为瞬态。
     */
    private fun Throwable.isPhotoCorruption(): Boolean = this is DavException.Corrupted

    /**
     * U-7 照片侧对称：失败计数 +1，达 [PHOTO_QUARANTINE_THRESHOLD] 进隔离名单。
     * 坏照片只是暂缺的贴图——隔离的代价是该图暂不显示且状态详情可见（非静默），
     * 收益是其余照片照常同步、整轮不再被同一张坏照片拖成永久 Failed
     * （坏一张曾使角标永久 Failed、后续照片全阻塞，且每轮都重试同一张）。
     */
    private fun onPhotoFailure(name: String, t: Throwable) {
        val failures = store.recordPhotoFailure(name)
        SlLog.d("PhotoTransfer", "photo $name failed (consecutive=$failures): ${t.message ?: t.javaClass.name}")
        if (failures >= PHOTO_QUARANTINE_THRESHOLD) store.quarantinePhoto(name)
    }

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

        /**
         * 照片隔离阈值：连续失败次数达此值才隔离（与
         * `SyncEngine.CHUNK_QUARANTINE_THRESHOLD` 同值同因——下载截断等偶发损坏与
         * 真损坏同形，给重试留余地；哈希不符虽是确定性坏，也走同一计数通道）。
         */
        const val PHOTO_QUARANTINE_THRESHOLD = 3

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
        // U-16：先写临时文件再 rename 原子替换。直写在中途死亡（Worker 后台被杀是常态）
        // 会留下内容截断的坏哈希文件，而本类上行路径此前不校验本地哈希，坏文件照传记台账
        // 后对端每轮下载必抛 Corrupted——temp+rename 保证 filesDir 里永远只有完整文件。
        // rename 失败（异常文件系统）直接抛错走整轮失败重试，与写入失败同语义，不退回直写。
        val tmp = File(file.parentFile, "${file.name}.tmp${System.nanoTime()}")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                throw java.io.IOException("照片原子替换失败：${file.name}")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    override fun pathForHash(contentHash: String): String = pathForHash.invoke(contentHash)

    private fun fileOf(contentHash: String): File = File(pathForHash.invoke(contentHash))
}
