package com.simpleledger.app.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * 贴图存储，分两阶段：
 *
 * 1. [importToPending]：用户选好图片后**立即**调用（此时系统授予的读权限一定有效），
 *    把图片压缩写入 cache 待入库目录。Photo Picker 返回的 content Uri 授权是短时效的，
 *    拖到点「保存」时再读会失败，所以必须前置。
 * 2. [promoteToStorage]：保存账目时把待入库文件移入应用私有正式目录并返回最终路径。
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

    /** 保存账目时调用：把待入库图片移入正式目录，返回最终路径 */
    suspend fun promoteToStorage(pendingPath: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val source = File(pendingPath)
            if (!source.exists()) error("待入库图片已不存在")
            val target = File(storageDir, source.name)
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            target.absolutePath
        }.onFailure { throwable ->
            Log.e(TAG, "贴图入库失败: $pendingPath", throwable)
        }.getOrNull()
    }

    /** 删除图片文件（正式目录与待入库目录通用，忽略不存在的情况） */
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
    }
}
