package com.simpleledger.app.data.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.WorkManager
import com.simpleledger.app.data.local.AppDatabase
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.sync.SyncWorker
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

/** 恢复结果：失败细分成三档，让 UI 能给出「为什么失败」的诚实文案而不是一律「恢复失败」。 */
sealed class RestoreResult {
    data object Success : RestoreResult()
    data object NotABackup : RestoreResult()
    data class NewerSchema(val backupVersion: Int, val appVersion: Int) : RestoreResult()
    data object Failed : RestoreResult()
}

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
                        // 全面审查 P2 回退口径加固：旧 SQLite（无 VACUUM INTO）时，
                        // 原先「db/-wal/-shm 三文件顺序拷贝」窗口内恰有并发写 / 自动
                        // checkpoint 就会得到主库与 WAL 错位的撕裂备份。现先
                        // `wal_checkpoint(TRUNCATE)` 把 WAL 全量合并回主库（主库文件自此
                        // 自洽），**只拷主库单文件**；checkpoint 被并发读者挡住（busy）时
                        // 才退回旧三文件口径。
                        val dbDir = context.getDatabasePath(DB_NAME).parentFile
                        val checkpointed = db?.let { database ->
                            runCatching {
                                database.openHelper.writableDatabase
                                    .query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                                        cursor.moveToFirst() && cursor.getInt(0) == 0 // 0 = 非 busy
                                    }
                            }.getOrElse { false }
                        } ?: false
                        if (checkpointed) {
                            val main = File(dbDir, DB_NAME)
                            check(main.exists() && main.length() > 0) { "checkpoint 后主库文件缺失" }
                            zip.putNextEntry(ZipEntry("database/$DB_NAME"))
                            main.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        } else {
                            // 数据库（WAL 模式需要一并打包 wal/shm 才能完整还原）
                            listOf("", "-wal", "-shm").forEach { suffix ->
                                val dbFile = File(dbDir, "$DB_NAME$suffix")
                                if (dbFile.exists()) {
                                    zip.putNextEntry(ZipEntry("database/$DB_NAME$suffix"))
                                    dbFile.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
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
     * 从备份 zip 恢复（P0 重做：先暂存校验、再原子替换、失败自动回滚）。
     *
     * 顺序（对应 2026-09 全面审查 P0-2/P0-3/P1-2）：
     * 1. **取消后台同步**——换库窗口内绝不能有第二个写者（旧 WAL 连接的 checkpoint
     *    会把旧页写回新库，损坏刚恢复的数据）；
     * 2. 暂存解压 + 校验（是 SQLite？schema 版本 ≤ 当前？）——**任何不通过都在触碰
     *    本地数据之前失败**，本地库一个字节都不动；
     * 3. 备 .bak → 清旧日志 → 换库 → 贴图；中途任何失败**用 .bak 回滚**，
     *    「已保留原有数据」这句话从此为真；
     * 4. 成功后由调用方（MineViewModel）**立即重启进程**——Room 旧连接持有旧 WAL fd，
     *    只有进程消失才能彻底杜绝写回。
     *
     * 失败（非 Success）时本地库已回滚为原样，且此处会重新注册周期同步
     * （第 1 步取消过，进程还活着，不能让定时任务就此消失）。
     */
    suspend fun restoreBackup(uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        // 1) 停掉后台同步写手。cancelUniqueWork 对「正在运行」的 Worker 不强杀，
        //    但同步引擎单飞 Mutex 保证至多一轮；本轮结束后不再有新写者。
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME) }
        // 当前 schema 版本（读取活库的 user_version，单一事实源，避免与 @Database 注解漂移）
        val currentVersion = db?.openHelper?.readableDatabase?.use { it.version } ?: 0

        val dbDir = context.getDatabasePath(DB_NAME).parentFile
        val imageDir = File(context.filesDir, "images")
        val stage = File(context.cacheDir, "restore-staging-${UUID.randomUUID()}")

        val outcome = runCatching {
            if (dbDir == null) error("找不到数据库目录")
            imageDir.mkdirs()
            stage.mkdirs()
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    restoreZipInto(
                        ZipInputStream(input.buffered()),
                        dbDir, imageDir, currentVersion, stage,
                    )
                } ?: error("无法读取所选备份文件")
            } finally {
                stage.deleteRecursively()
            }
        }

        when {
            outcome.isSuccess && outcome.getOrNull() == RestoreVerdict.Ok -> RestoreResult.Success
            outcome.getOrNull() == RestoreVerdict.NotABackup -> RestoreResult.NotABackup
            outcome.getOrNull() is RestoreVerdict.NewerSchema -> {
                val v = outcome.getOrNull() as RestoreVerdict.NewerSchema
                RestoreResult.NewerSchema(v.backupVersion, currentVersion)
            }
            else -> {
                // 失败路径：本地库已被内核回滚为原样；恢复被取消的周期同步
                runCatching { SyncWorker.enqueue(context) }
                    .onFailure { Log.e(TAG, "恢复失败后重注册周期同步失败", it) }
                RestoreResult.Failed
            }
        }
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
        const val DB_NAME = "simple_ledger.db"
    }
}

/** 暂存校验结论（内核 [restoreZipInto] 的失败细分，供 [RestoreResult] 映射）。 */
internal sealed class RestoreVerdict {
    data object Ok : RestoreVerdict()
    data object NotABackup : RestoreVerdict()
    data class NewerSchema(val backupVersion: Int) : RestoreVerdict()
}

/**
 * 解压恢复的**纯 JVM 内核**（AU-1/AU-2 拆出供单测直测；不依赖 Android 类）。
 *
 * P0 重做后的三段式（2026-09 全面审查）：
 *
 * **第一段·暂存**：所有条目先解压到 [stageDir]（database/ 与 images/ 两个子目录），
 * 本地数据一个字节不碰。这样坏 zip / 磁盘满等任何解压失败都发生在破坏之前。
 * AU-1（Zip Slip 防护）在暂存阶段生效：条目名归一化后必须仍在暂存目录内，
 * 逃逸条目（如 `database/../shared_prefs/…` 覆盖应用锁 prefs）跳过不入盘。
 *
 * **第二段·校验**：暂存库里必须存在 `simple_ledger.db`、文件头是合法 SQLite
 * （magic `SQLite format 3\0`）、且 `user_version`（偏移 60，大端 u32）不高于
 * [currentDbVersion]——高版本备份恢复进旧 App 会在 Room 打开时抛异常且**启动永久崩溃**，
 * 必须在触碰本地数据之前拒绝。任一不满足即返回 [RestoreVerdict.NotABackup] /
 * [RestoreVerdict.NewerSchema]，本地保持原样。
 *
 * **第三段·替换 + 回滚**：.bak 三件套（含旧 wal/shm，回退需要完整三件套）→
 * 清旧库残留 -wal/-shm（AU-2：必须在主库落位之前，且只在确有新库时清）→
 * 换库 → 贴图（内容寻址命名，按名覆盖/新增，additive）。**任何失败用 .bak 整套回滚**，
 * 保证「恢复失败已保留原有数据」为真；成功则清掉 .bak（旧实现永不清理）。
 *
 * AU-2 细则：zip 自带的 wal/shm 原样落位（备份时未 checkpoint 的事务随 WAL 恢复，
 * 重启后由 SQLite recovery 回放）；zip 里没有数据库条目（误选了别的 zip）时
 * 不触碰本地库，直接 NotABackup。
 */
internal fun restoreZipInto(
    zip: ZipInputStream,
    dbDir: File,
    imageDir: File,
    currentDbVersion: Int,
    stageDir: File,
): RestoreVerdict {
    val stageDb = File(stageDir, "database").apply { mkdirs() }
    val stageImg = File(stageDir, "images").apply { mkdirs() }

    // ---------- 第一段：全部条目暂存（本地零触碰） ----------
    zip.use { stream ->
        var entry: ZipEntry? = stream.nextEntry
        while (entry != null) {
            when {
                entry.name.startsWith("database/") ->
                    safeEntryFile(stageDb, entry.name.removePrefix("database/"))
                entry.name.startsWith("images/") ->
                    safeEntryFile(stageImg, entry.name.removePrefix("images/"))
                else -> null // backup-info.txt 等非数据条目：忽略
            }?.let { target -> target.outputStream().use { stream.copyTo(it) } }
            stream.closeEntry()
            entry = stream.nextEntry
        }
    }

    // ---------- 第二段：校验（不通过 = 本地零改动） ----------
    val stagedMain = File(stageDb, DB_MAIN)
    if (!stagedMain.exists()) return RestoreVerdict.NotABackup
    val backupVersion = readSqliteUserVersion(stagedMain)
        ?: return RestoreVerdict.NotABackup // 不是合法 SQLite 文件
    if (backupVersion > currentDbVersion) return RestoreVerdict.NewerSchema(backupVersion)

    // ---------- 第三段：替换（失败自动回滚） ----------
    runCatching {
        // 1) 当前三件套留 .bak
        DB_SUFFIXES.forEach { suffix ->
            val current = File(dbDir, "$DB_MAIN$suffix")
            if (current.exists()) current.copyTo(File(dbDir, "$DB_MAIN$suffix.bak"), overwrite = true)
        }
        // 2) 清旧库残留日志——必须在主库落位之前（新主库 + 旧日志 = 撕裂库）
        DB_SUFFIXES.filter { it.isNotEmpty() }.forEach { suffix ->
            File(dbDir, "$DB_MAIN$suffix").delete()
        }
        // 3) 换库：暂存的 db 三件套里存在谁就落谁
        DB_SUFFIXES.forEach { suffix ->
            val staged = File(stageDb, "$DB_MAIN$suffix")
            if (staged.exists()) staged.copyTo(File(dbDir, "$DB_MAIN$suffix"), overwrite = true)
        }
        // 4) 贴图（additive：内容寻址命名，同名即同内容，旧图保留供旧库引用）
        stageImg.listFiles()?.forEach { img ->
            img.copyTo(File(imageDir, img.name), overwrite = true)
        }
        // 5) 成功：清 .bak
        DB_SUFFIXES.forEach { suffix -> File(dbDir, "$DB_MAIN$suffix.bak").delete() }
    }.onFailure { t ->
        // 回滚：删掉半写的三件套，.bak 里有谁就恢复谁；回滚后 .bak 一并清理
        DB_SUFFIXES.forEach { suffix ->
            File(dbDir, "$DB_MAIN$suffix").delete()
            val bak = File(dbDir, "$DB_MAIN$suffix.bak")
            if (bak.exists()) {
                bak.copyTo(File(dbDir, "$DB_MAIN$suffix"), overwrite = true)
                bak.delete()
            }
        }
        throw t
    }
    return RestoreVerdict.Ok
}

/** SQLite 文件头解析：合法则返回 user_version（偏移 60，大端 u32），非法返回 null。 */
private fun readSqliteUserVersion(dbFile: File): Int? = runCatching {
    val head = ByteArray(100)
    var read = 0
    dbFile.inputStream().use { input ->
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
    }
    // magic："SQLite format 3\0" 共 16 字节；文件太短读不满头部也不合法
    check(read >= 64) { "文件过短" }
    val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    check(head.copyOfRange(0, magic.size).contentEquals(magic)) { "非 SQLite 文件" }
    ((head[60].toInt() and 0xFF) shl 24) or
        ((head[61].toInt() and 0xFF) shl 16) or
        ((head[62].toInt() and 0xFF) shl 8) or
        (head[63].toInt() and 0xFF)
}.getOrNull()

/** 内核与 DataExporter 共用的库文件名（保持与 [com.simpleledger.app.data.local.AppDatabase] 的 DB_NAME 一致） */
private const val DB_MAIN = "simple_ledger.db"
private val DB_SUFFIXES = listOf("", "-wal", "-shm")

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
