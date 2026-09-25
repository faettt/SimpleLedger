package com.simpleledger.app.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

/**
 * 贴图存储，分两阶段：
 *
 * 1. [importToPending]：用户选好图片后**立即**调用（此时系统授予的读权限一定有效），
 *    把图片压缩写入 cache 待入库目录。Photo Picker 返回的 content Uri 授权是短时效的，
 *    拖到点「保存」时再读会失败，所以必须前置。
 * 2. [promoteToStorage]：保存账目时把待入库文件移入应用私有正式目录，返回最终路径与
 *    **明文 JPEG 的 SHA-256**（v5 内容寻址，R-16）。
 *
 * v5 起正式目录的文件名 = `<sha256>.jpg`（[pathForHash]）：
 * - 同一张照片（同字节）只占一份磁盘、云端只有一个假名文件（内容寻址去重）；
 * - 跨设备同步来的照片按 hash 直接对上路径，无需额外映射表；
 * - 删除文件前**必须**按 `entry_images.contentHash` 引用计数判定（>1 不删，
 *   防共享照片误删）——计数查询在 `EntryDao.countByContentHash`，由调用方（仓库）执行。
 *
 * 两阶段都失败时记录日志并返回 null，由调用方决定如何提示用户。
 */
class ImageStorage(private val context: Context) {

    private val storageDir: File
        get() = File(context.filesDir, "images").apply { mkdirs() }

    private val pendingDir: File
        get() = File(context.cacheDir, "pending_images").apply { mkdirs() }

    private val maxDimension = 1600

    /** 选择图片后立即调用：压缩并写入待入库目录，返回 cache 中的绝对路径 */
    suspend fun importToPending(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeScaled(uri) ?: error("图片无法解码")
            val target = File(pendingDir, "${UUID.randomUUID()}.jpg")
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            bitmap.recycle()
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

    /**
     * 只打开一次输入流读取全部字节，再从字节数组解码两次（探尺寸 + 实际解码）。
     *
     * 不能对同一个 content Uri 反复 openInputStream：Photo Picker 授予的临时读权限
     * 在首次读取后即被消费，第二次打开会返回 null（表现为"图片无法解码"）。
     */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法打开图片输入流")
        if (bytes.isEmpty()) error("图片内容为空")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        var sampleSize = 1
        var width = max(bounds.outWidth, 1)
        var height = max(bounds.outHeight, 1)
        while (width / 2 >= maxDimension && height / 2 >= maxDimension) {
            sampleSize *= 2
            width /= 2
            height /= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("图片解码失败（尺寸 ${bounds.outWidth}x${bounds.outHeight}）")
    }

    private companion object {
        const val TAG = "SimpleLedger.Image"

        /** 明文 JPEG 的 SHA-256（64hex 小写）——内容寻址身份 */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

/** 入库结果：正式路径 + 明文内容哈希（`entry_images.contentHash` 同步写入） */
data class PromotedImage(val path: String, val contentHash: String)
