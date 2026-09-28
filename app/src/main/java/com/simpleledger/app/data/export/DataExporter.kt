package com.simpleledger.app.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import com.simpleledger.app.data.local.AppDatabase
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.util.DateTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
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
    /** AU-9：一致性快照用（VACUUM INTO）；null = 退回旧三文件拷贝口径（JVM 场景不传） */
    private val db: AppDatabase? = null,
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
            // AU-9：备份是用户的最后防线。Room 默认 WAL 模式下，对**打开中**的库按
            // db/-wal/-shm 三文件顺序直接拷贝没有任何原子性——拷贝窗口内恰有并发写 /
            // 自动 checkpoint（后台同步轮随时写库）就会得到主库与 WAL 错位的撕裂备份，
            // 恢复后丢最近事务甚至库损坏。首选 `VACUUM INTO`：它在自己的只读事务里
            // 生成一个自洽的单文件快照（与并发写入方互不干扰）；仅当 SQLite 过老
            // （< 3.27，无 VACUUM INTO，minSdk 26 覆盖到的低端机）时退回旧三文件口径。
            val snapshot = vacuumIntoSnapshot(dir)
            try {
                ZipOutputStream(file.outputStream().buffered()).use { zip ->
                    if (snapshot != null) {
                        // 一致性快照：单文件完整，无需（也不存在）wal/shm
                        zip.putNextEntry(ZipEntry("database/simple_ledger.db"))
                        snapshot.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    } else {
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
            } finally {
                snapshot?.delete()
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
            val dbDir = context.getDatabasePath("simple_ledger.db").parentFile
                ?: error("找不到数据库目录")
            val imageDir = File(context.filesDir, "images").apply { mkdirs() }
            context.contentResolver.openInputStream(uri)?.use { input ->
                restoreZipInto(ZipInputStream(input.buffered()), dbDir, imageDir)
            } ?: error("无法读取所选备份文件")
            true
        }.onFailure { Log.e(TAG, "恢复备份失败", it) }.getOrElse { false }
    }

    /** 清理旧的导出文件，避免缓存堆积 */
    suspend fun cleanOldExports() = withContext(Dispatchers.IO) {
        runCatching { File(context.cacheDir, "exports").listFiles()?.forEach { it.delete() } }
        Unit
    }

    /**
     * AU-9：`VACUUM INTO` 生成一致性快照（调用方负责用后删除）。
     * 目标文件必须不存在（SQLite 要求）；任何失败（含 SQLite < 3.27 不支持该语句）
     * 返回 null → [createBackup] 退回旧三文件拷贝口径。
     */
    private fun vacuumIntoSnapshot(dir: File): File? {
        val database = db ?: return null
        // VACUUM INTO 要求目标不存在——用随机名而不是 createTempFile（后者会先建空文件）
        val snapshot = File(dir, "snapshot-${UUID.randomUUID()}.db")
        return runCatching {
            database.openHelper.writableDatabase.execSQL(
                "VACUUM INTO ?",
                arrayOf(snapshot.absolutePath),
            )
            check(snapshot.exists() && snapshot.length() > 0) { "VACUUM INTO 未产出快照" }
            snapshot
        }.onFailure {
            snapshot.delete()
            Log.i(TAG, "VACUUM INTO 快照不可用，退回三文件拷贝口径（旧 SQLite 或 IO 失败）", it)
        }.getOrNull()
    }

    private fun timestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))

    private companion object {
        const val TAG = "SimpleLedger.Export"
    }
}

/**
 * 解压恢复的**纯 JVM 内核**（AU-1/AU-2 拆出，供单测直测；不依赖 Android 类）：
 *
 * 1. 当前数据先留一份 .bak（含旧 wal/shm——回退需要完整三件套），恢复失败可回退；
 * 2. AU-1（Zip Slip 防护）：恶意备份包的条目名可含 `../`（如
 *    `database/../shared_prefs/simple_ledger_settings.xml`），逐条目落盘前必须经
 *    [safeEntryFile] 校验归一化路径仍落在目标目录内，逃逸条目跳过不入盘——
 *    否则用户在 SAF 选择器里选中的任意 zip 都能拿到沙箱内任意文件写原语
 *    （覆盖应用锁 prefs、改写同步指向的服务器）；
 * 3. AU-2：旧库残留的 -wal/-shm 在**写入第一个 database/ 条目之前**清掉（此前的实现
 *    是解压完再无条件删除——把刚从备份包解压出来的 wal/shm 也一并误删，备份时还躺在
 *    WAL 里未 checkpoint 的事务随「恢复成功」静默丢失，与打包侧「wal/shm 必须一并
 *    打包才能完整还原」的契约自相矛盾）。清理只由 database/ 条目触发：zip 里没有
 *    数据库条目（误选了别的 zip）时不动本地库。zip 自带的 wal/shm 原样落位，
 *    重启后由 SQLite recovery 回放。
 */
internal fun restoreZipInto(zip: ZipInputStream, dbDir: File, imageDir: File) {
    // 1) 当前数据先留一份 .bak
    listOf("", "-wal", "-shm").forEach { suffix ->
        val current = File(dbDir, "simple_ledger.db$suffix")
        if (current.exists()) {
            current.copyTo(File(dbDir, "simple_ledger.db$suffix.bak"), overwrite = true)
        }
    }

    // 2) 逐条目解压覆盖
    var staleLogsCleared = false
    zip.use { stream ->
        var entry: ZipEntry? = stream.nextEntry
        while (entry != null) {
            val name = entry.name
            when {
                name.startsWith("database/") -> {
                    if (!staleLogsCleared) {
                        // AU-2：清掉**旧库**残留日志——只清一次、只在确有新库要落位时清，
                        // 且必须在主库文件落位之前（新主库 + 旧日志 = 撕裂库）
                        listOf("-wal", "-shm").forEach { suffix ->
                            File(dbDir, "simple_ledger.db$suffix").delete()
                        }
                        staleLogsCleared = true
                    }
                    safeEntryFile(dbDir, name.removePrefix("database/"))?.let { target ->
                        target.outputStream().use { stream.copyTo(it) }
                    }
                }

                name.startsWith("images/") -> {
                    safeEntryFile(imageDir, name.removePrefix("images/"))?.let { target ->
                        target.outputStream().use { stream.copyTo(it) }
                    }
                }
            }
            stream.closeEntry()
            entry = stream.nextEntry
        }
    }
}

/**
 * AU-1：把 zip 条目名解析到 [destDir] 内的安全落点。
 * 归一化（canonicalPath）后必须仍在目标目录**之内**——拒绝 `..` 逃逸、目录自身、
 * 空名与其它越界形态（Zip Slip 标准防护）；逃逸返回 null，调用方跳过该条目。
 */
internal fun safeEntryFile(destDir: File, entryName: String): File? {
    if (entryName.isBlank()) return null
    val target = File(destDir, entryName)
    val destRoot = destDir.canonicalPath + File.separator
    return if (target.canonicalPath.startsWith(destRoot)) {
        target.parentFile?.mkdirs() // 路径已校验在目录内，建父目录是安全的
        target
    } else {
        null
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
