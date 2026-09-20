package com.simpleledger.app.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.util.DateTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据导出与备份。
 * 「简账」是本地优先的产品，用户必须能随时把数据带走——这是隐私承诺的一部分。
 */
class DataExporter(
    private val context: Context,
    private val repository: LedgerRepository,
) {

    /**
     * 导出全部账目为 CSV（UTF-8 带 BOM，Excel 直接打开中文不乱码）。
     * @return 导出的文件，失败返回 null
     */
    suspend fun exportCsv(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val rows = repository.allEntriesForExport()
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "简账-明细-${timestamp()}.csv")
            file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("\uFEFF")
                writer.write("日期,时间,类型,金额(元),分类,分区,分区备注,账目备注,贴图数\n")
                rows.forEach { row ->
                    writer.write(
                        listOf(
                            row.date,
                            row.time,
                            row.typeLabel,
                            row.amountYuan,
                            row.category,
                            row.section,
                            row.sectionNote,
                            row.note,
                            row.imageCount.toString(),
                        ).joinToString(",") { CsvFormat.escape(it) }
                    )
                    writer.write("\n")
                }
            }
            file
        }.onFailure { Log.e(TAG, "导出 CSV 失败", it) }.getOrNull()
    }

    /** 生成完整备份（数据库 + 全部贴图）为 zip，可分享到网盘或存到本机 */
    suspend fun createBackup(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "简账-备份-${timestamp()}.zip")
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                // 数据库（WAL 模式需要一并打包 wal/shm 才能完整还原）
                val dbDir = context.getDatabasePath("simple_ledger.db").parentFile
                listOf("", "-wal", "-shm").forEach { suffix ->
                    val dbFile = File(dbDir, "simple_ledger.db$suffix")
                    if (dbFile.exists()) {
                        zip.putNextEntry(ZipEntry("database/simple_ledger.db$suffix"))
                        dbFile.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                // 贴图
                val imageDir = File(context.filesDir, "images")
                imageDir.listFiles()?.forEach { image ->
                    zip.putNextEntry(ZipEntry("images/${image.name}"))
                    image.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("backup-info.txt"))
                zip.write(
                    "简账备份\n生成时间：${LocalDateTime.now()}\n包含：数据库 + 全部贴图\n".toByteArray()
                )
                zip.closeEntry()
            }
            file
        }.onFailure { Log.e(TAG, "生成备份失败", it) }.getOrNull()
    }

    /**
     * 从备份 zip 恢复：先备份当前数据到 .bak，再解压覆盖。
     * 恢复后需要重启应用（Room 持有旧连接），由调用方负责。
     */
    suspend fun restoreBackup(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dbFile = context.getDatabasePath("simple_ledger.db")
            val dbDir = dbFile.parentFile ?: error("找不到数据库目录")
            val imageDir = File(context.filesDir, "images").apply { mkdirs() }

            // 1) 当前数据先留一份 .bak，恢复失败可回退
            listOf("", "-wal", "-shm").forEach { suffix ->
                val current = File(dbDir, "simple_ledger.db$suffix")
                if (current.exists()) current.copyTo(File(dbDir, "simple_ledger.db$suffix.bak"), overwrite = true)
            }

            // 2) 解压覆盖
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name.startsWith("database/") -> {
                                val target = File(dbDir, name.removePrefix("database/"))
                                target.outputStream().use { zip.copyTo(it) }
                            }

                            name.startsWith("images/") -> {
                                val target = File(imageDir, name.removePrefix("images/"))
                                if (target.name.isNotBlank()) target.outputStream().use { zip.copyTo(it) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("无法读取所选备份文件")

            // 3) 清掉 -wal/-shm，避免用旧日志覆盖新库
            listOf("-wal", "-shm").forEach { suffix ->
                File(dbDir, "simple_ledger.db$suffix").delete()
            }
            true
        }.onFailure { Log.e(TAG, "恢复备份失败", it) }.getOrElse { false }
    }

    /** 清理旧的导出文件，避免缓存堆积 */
    suspend fun cleanOldExports() = withContext(Dispatchers.IO) {
        runCatching { File(context.cacheDir, "exports").listFiles()?.forEach { it.delete() } }
        Unit
    }

    private fun timestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))

    private companion object {
        const val TAG = "SimpleLedger.Export"
    }
}

/** 导出一行账目的扁平结构 */
data class ExportRow(
    val date: String,
    val time: String,
    val typeLabel: String,
    val amountYuan: String,
    val category: String,
    val section: String,
    val sectionNote: String,
    val note: String,
    val imageCount: Int,
)

/** 供导出使用的时间格式化工具 */
internal fun exportDate(millis: Long): String = DateTimes.toLocalDate(millis).toString()

internal fun exportTime(millis: Long): String = DateTimes.timeLabel(DateTimes.toLocalTime(millis))
