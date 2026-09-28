package com.simpleledger.app.data.repo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.simpleledger.app.logic.ImageExportPlan
import com.simpleledger.app.logic.ImageFileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 贴图存储，分两阶段：
 *
 * 1. [importToPending]：用户选好图片后**立即**调用（此时系统授予的读权限一定有效），
 *    把图片**原字节**写入 cache 待入库目录（「贴图不压缩」：不再解码/缩采样/重压缩，
 *    改由魔数嗅探 + 本机解码门槛双检把关）。Photo Picker 返回的 content Uri 授权是
 *    短时效的，拖到点「保存」时再读会失败，所以必须前置。
 * 2. [promoteToStorage]：保存账目时把待入库文件移入应用私有正式目录，返回最终路径与
 *    **明文原字节的 SHA-256**（v5 内容寻址，R-16；哈希口径不变——恒为「落盘字节的
 *    SHA-256」，原字节方案只是被哈希的内容从重压缩 JPEG 换成了原始字节）。
 *
 * v5 起正式目录的文件名 = `<sha256>.jpg`（[pathForHash]）：
 * - 同一张照片（同字节）只占一份磁盘、云端只有一个假名文件（内容寻址去重）；
 * - 跨设备同步来的照片按 hash 直接对上路径，无需额外映射表；
 * - 删除文件前**必须**按 `entry_images.contentHash` 引用计数判定（>1 不删，
 *   防共享照片误删）——计数查询在 `EntryDao.countByContentHash`，由调用方（仓库）执行。
 *
 * ⚠️ 跨升级去重例外（除虫评审 #4 留档）：哈希口径从「重压缩 JPEG 字节」换为「原始
 * 字节」——升级前入库的照片（压缩副本）与升级后重新导入的同图（原字节）hash 不同，
 * 不再去重，磁盘与云端各存一份。历史副本无法回溯为原图（原字节已不存在），接受此残余。
 *
 * 「贴图不压缩」后 `.jpg` 是历史假名：真实格式（PNG/GIF/HEIC…）只活在字节里，由
 * [com.simpleledger.app.logic.ImageExportPlan.detectFormat] 魔数嗅探给出——存量贴图
 * 零迁移、op 载荷与备份 zip 零改动，跨设备按 hash 对路径的口径不变。
 *
 * 两阶段都失败时记录日志并返回 null，由调用方决定如何提示用户。
 */
class ImageStorage(private val context: Context) {

    private val storageDir: File
        get() = File(context.filesDir, "images").apply { mkdirs() }

    private val pendingDir: File
        get() = File(context.cacheDir, "pending_images").apply { mkdirs() }

    /** 选择图片后立即调用：**原字节**写入待入库目录，返回 cache 中的绝对路径 */
    suspend fun importToPending(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readUriBytes(uri)
            if (bytes.isEmpty()) error("图片内容为空")
            // 导入门控双检：魔数识别 + 本机解码门槛。识别不出 / 本机解不出的格式
            // 「导入即拒」（返回 null），与旧「图片无法解码」走同一条反馈路径——
            // 尽量收窄「入库成功但本机显示链解不出」的不可见数据。（除虫评审 #2
            // 留档残余：魔数+门槛不校验字节完整性，截断文件若仍命中签名前缀仍会
            // 入库——Photo Picker 实际只返回完整图片，且零压缩口径下不做解码探测。）
            val format = ImageExportPlan.detectFormat(bytes)
                ?: error("图片格式无法识别")
            if (Build.VERSION.SDK_INT < ImageExportPlan.minDecodeApi(format)) {
                error("本机系统版本（API ${Build.VERSION.SDK_INT}）不支持该格式（${format.mime}）")
            }
            // 原字节透传落盘：画质无损、GIF 不被静态化成首帧、EXIF 方向随字节保留。
            // 待入库文件名不带扩展名——真实格式活在字节里，入库后统一归入内容寻址假名。
            val target = File(pendingDir, UUID.randomUUID().toString())
            target.writeBytes(bytes)
            target.absolutePath
        }.onFailure { throwable ->
            Log.e(TAG, "读取所选图片失败: $uri", throwable)
        }.getOrNull()
    }

    /**
     * 保存账目时调用：把待入库图片移入正式目录（内容寻址 `<sha256>.jpg`）。
     *
     * 目标已存在（同内容照片）时直接丢弃源文件复用既有文件——天然去重，
     * 两笔账目/两台设备的同一照片共享一个文件（删除走引用计数，见类注释）。
     */
    suspend fun promoteToStorage(pendingPath: String): PromotedImage? = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(pendingPath)
            if (!source.exists()) error("待入库图片已不存在")
            val bytes = source.readBytes()
            val contentHash = sha256Hex(bytes)
            val target = File(storageDir, "$contentHash.jpg")
            if (target.exists()) {
                source.delete()
            } else if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            PromotedImage(path = target.absolutePath, contentHash = contentHash)
        }.onFailure { throwable ->
            Log.e(TAG, "贴图入库失败: $pendingPath", throwable)
        }.getOrNull()
    }

    /** 内容寻址路径：`filesDir/images/<sha256>.jpg`（远端照片按 hash 落同一路径） */
    fun pathForHash(contentHash: String): String =
        File(storageDir, "$contentHash.jpg").absolutePath

    /** 删除图片文件（正式目录与待入库目录通用，忽略不存在的情况）；引用计数判定由调用方做 */
    suspend fun deleteFiles(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { path -> runCatching { File(path).delete() } }
        Unit
    }

    /** 应用启动时清理未被保存的待入库图片，避免占用缓存空间 */
    suspend fun cleanPendingImages() = withContext(Dispatchers.IO) {
        runCatching { pendingDir.listFiles()?.forEach { it.delete() } }
        Unit
    }

    /**
     * 删除账目时把贴图「暂存」而非直接销毁，让 4 秒撤销窗口内可以完整恢复。
     * 返回暂存后的新路径（顺序与入参一致，失败的项被丢弃）。
     *
     * ⚠️ 共享文件（contentHash 引用计数 > 1）**不能**移走——其余账目还指着它，
     * 调用方（LedgerRepository）只把独享文件交给本方法。
     */
    suspend fun parkFiles(paths: List<String>): List<String> = withContext(Dispatchers.IO) {
        paths.mapNotNull { path ->
            runCatching {
                val source = File(path)
                if (!source.exists()) return@runCatching null
                val target = File(parkedDir, "${UUID.randomUUID()}_${source.name}")
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                }
                target.absolutePath
            }.onFailure { Log.e(TAG, "暂存贴图失败: $path", it) }.getOrNull()
        }
    }

    /** 暂存目录：撤销窗口过后（或下次启动时）统一清理 */
    suspend fun cleanParkedFiles() = withContext(Dispatchers.IO) {
        runCatching { parkedDir.listFiles()?.forEach { it.delete() } }
        Unit
    }

    /**
     * 撤销恢复：把暂存文件移回正式路径（[parkFiles] 的逆操作）。
     * 目标已存在（内容寻址共享场景）时直接丢弃暂存副本复用既有文件。
     */
    suspend fun unparkFile(parkedPath: String, targetPath: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(parkedPath)
                if (!source.exists()) return@runCatching false
                val target = File(targetPath)
                target.parentFile?.mkdirs()
                if (target.exists()) {
                    source.delete()
                } else if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                }
                true
            }.onFailure { Log.e(TAG, "暂存归位失败: $parkedPath -> $targetPath", it) }
                .getOrDefault(false)
        }

    private val parkedDir: File
        get() = File(context.cacheDir, "parked_images").apply { mkdirs() }

    // ---------- 导出 API（「保存到相册」，批次 B 的贴图 UI 消费） ----------

    /**
     * 把已入库贴图导出到系统相册，按系统版本分档：
     * - API 29+：直接经 MediaStore 两步写（挂起行 → 写字节 → 提交）入相册集
     *   [GALLERY_RELATIVE_PATH]，返回 [GalleryExportOutcome.Saved]；
     * - API 26–28：旧 MediaStore 没有 IS_PENDING / 相对路径直写权，返回
     *   [GalleryExportOutcome.NeedsSaf]（建议文件名 + 真实 MIME），由 UI 拉起
     *   `ActivityResultContracts.CreateDocument` 再调 [writeExportImageToSafTarget] 落盘。
     *
     * 文件名用真实格式扩展名（.gif 保动画），不用盘上假名 .jpg。
     */
    suspend fun exportImageToGallery(imagePath: String): GalleryExportOutcome =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportToMediaStore(imagePath)
            } else {
                runCatching {
                    val payload = prepareExport(imagePath)
                    GalleryExportOutcome.NeedsSaf(
                        suggestedName = payload.suggestedName,
                        mimeType = payload.format.mime,
                    )
                }.getOrElse { it.toExportFailure() }
            }
        }

    /**
     * API 29+ 分支：MediaStore 挂起项两步写。失败路径（decisions 第 6 条）：
     * 写字节抛错时删除挂起行再返回 Failed，不在相册里留 0 字节幽灵项。
     *
     * 知情接受：「写字节成功后、IS_PENDING=0 提交前」进程被杀会残留一条挂起行，
     * 由系统在数日后自动回收——该 kill 窗口无法用 finally 兜住（进程已死），
     * 不为此引入额外恢复机制。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportToMediaStore(imagePath: String): GalleryExportOutcome {
        return runCatching {
            val payload = prepareExport(imagePath)
            val resolver = context.contentResolver
            // 第一步：落 IS_PENDING 挂起行，扫描器不会立刻收录半成品
            val pending = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, payload.suggestedName)
                put(MediaStore.MediaColumns.MIME_TYPE, payload.format.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
                ?: error("相册写入被系统拒绝（MediaStore.insert 返回空）")
            try {
                // 第二步：写原字节 + 提交（IS_PENDING = 0）
                resolver.openOutputStream(uri)?.use { it.write(payload.bytes) }
                    ?: error("无法打开相册输出流")
                val clear = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, clear, null, null)
                GalleryExportOutcome.Saved(mediaUri = uri.toString())
            } catch (t: Throwable) {
                // 失败清理：删掉挂起行（用 API 1 起的三参重载，one-arg 形式 API 30 才有）
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }.getOrElse { it.toExportFailure() }
    }

    /**
     * SAF 目标落盘（[GalleryExportOutcome.NeedsSaf] 的续篇）：把贴图原字节写入
     * UI 经 CreateDocument 拿到的目标 Uri。不重嗅探不重命名——用户在 SAF 对话框里
     * 改过名是他的自由，这里只管把字节原样写进去。写失败返回 false（文件可能已在
     * 用户操作间隙被清理），由 UI 拼「保存失败」文案。
     */
    suspend fun writeExportImageToSafTarget(target: Uri, imagePath: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(imagePath).takeIf { it.exists() }?.readBytes()
                    ?: error("贴图文件不存在")
                context.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
                    ?: error("无法打开目标输出流")
                true
            }.onFailure { Log.e(TAG, "SAF 导出失败: $target", it) }
                .getOrDefault(false)
        }

    /** 导出前置：读原字节 → 魔数嗅探 → 建议文件名（两条导出分档共用） */
    private fun prepareExport(imagePath: String): ExportPayload {
        val file = File(imagePath)
        if (!file.exists()) error("贴图文件不存在")
        val bytes = file.readBytes()
        if (bytes.isEmpty()) error("贴图内容为空")
        val format = ImageExportPlan.detectFormat(bytes) ?: error("贴图格式无法识别")
        return ExportPayload(
            bytes = bytes,
            format = format,
            suggestedName = ImageExportPlan.suggestedFileName(
                epochMillis = System.currentTimeMillis(),
                contentHashHex = sha256Hex(bytes),
                format = format,
            ),
        )
    }

    /** 导出载荷（[prepareExport] 产物：原字节 + 嗅探格式 + 建议文件名） */
    private class ExportPayload(
        val bytes: ByteArray,
        val format: ImageFileFormat,
        val suggestedName: String,
    )

    private fun Throwable.toExportFailure(): GalleryExportOutcome.Failed =
        GalleryExportOutcome.Failed(reason = message ?: this::class.java.simpleName)

    /**
     * 只打开一次输入流读取全部字节。不能对同一个 content Uri 反复 openInputStream：
     * Photo Picker 授予的临时读权限在首次读取后即被消费，第二次打开会返回 null
     * （表现为「图片无法解码」）。
     */
    private fun readUriBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法打开图片输入流")

    private companion object {
        const val TAG = "SimpleLedger.Image"

        /** 相册子目录（openQuestions ① 默认口径：Pictures/SimpleLedger，可翻转改中文「简账」） */
        const val GALLERY_RELATIVE_PATH = "Pictures/SimpleLedger"

        /** 落盘字节的 SHA-256（64hex 小写）——内容寻址身份 */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

/** 入库结果：正式路径 + 明文内容哈希（`entry_images.contentHash` 同步写入） */
data class PromotedImage(val path: String, val contentHash: String)

/**
 * 「保存到相册」三态结果（批次 B 的 UI 据此分派；reason 为中文短句或异常摘要，
 * 由 UI 拼进 strings.xml 的 save_to_gallery_failed「保存失败：%1$s」）。
 */
sealed interface GalleryExportOutcome {
    /** API 29+：已直接写入系统相册（Pictures/SimpleLedger），mediaUri 为内容 Uri 串 */
    data class Saved(val mediaUri: String) : GalleryExportOutcome

    /** API 26–28：交 SAF——UI 拉起 CreateDocument 后调 [ImageStorage.writeExportImageToSafTarget] */
    data class NeedsSaf(val suggestedName: String, val mimeType: String) : GalleryExportOutcome

    /** 失败：reason 为可直接拼进「保存失败：%1$s」的短句 */
    data class Failed(val reason: String) : GalleryExportOutcome
}
