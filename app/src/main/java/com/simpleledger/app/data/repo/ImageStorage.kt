package com.simpleledger.app.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * 贴图存储：把系统相册选中的图片拷贝进应用私有目录并压缩，
 * 保证记账数据完全离线、可随备份迁移。
 */
class ImageStorage(private val context: Context) {

    private val imageDir: File
        get() = File(context.filesDir, "images").apply { mkdirs() }

    private val maxDimension = 1600

    /** 导入一张图片，返回存储后的绝对路径 */
    suspend fun import(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            } ?: return@runCatching null

            var sampleSize = 1
            var width = max(bounds.outWidth, 1)
            var height = max(bounds.outHeight, 1)
            while (width / 2 >= maxDimension && height / 2 >= maxDimension) {
                sampleSize *= 2
                width /= 2
                height /= 2
            }

            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return@runCatching null

            val target = File(imageDir, "${UUID.randomUUID()}.jpg")
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            bitmap.recycle()
            target.absolutePath
        }.getOrNull()
    }

    /** 删除本地图片文件（忽略不存在的情况） */
    suspend fun deleteFiles(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { path -> runCatching { File(path).delete() } }
        Unit
    }
}
