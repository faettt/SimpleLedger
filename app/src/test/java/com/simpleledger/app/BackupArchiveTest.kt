package com.simpleledger.app

import com.simpleledger.app.data.export.RestoreVerdict
import com.simpleledger.app.data.export.restoreZipInto
import com.simpleledger.app.data.export.safeEntryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份包解压恢复内核的回归钉子（AU-1 / AU-2 / P0 三段式，`restoreZipInto` /
 * `safeEntryFile` 为 DataExporter 拆出的纯 JVM 内核，不依赖 Android 运行时）。
 *
 * AU-1（Zip Slip，high）：restoreBackup 接受 SAF 里任选的 zip，条目名含 `../`
 * 时可写到目标目录之外（如 `database/../shared_prefs/…` 覆盖应用锁 prefs、
 * 改写同步指向的服务器）——归一化路径必须仍在暂存目录内，逃逸条目跳过。
 *
 * AU-2（mustFix）：旧实现会在解压后无条件删除 `-wal/-shm`，把备份包自带的
 * wal/shm 一并误删（备份时未 checkpoint 的事务随「恢复成功」静默丢失）。
 *
 * P0 三段式（2026-09 全面审查）：暂存 → 校验（SQLite 头 + user_version ≤ 当前）
 * → 替换（失败用 .bak 回滚）。任何校验不通过都**不触碰本地数据**。
 */
class BackupArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /* ---------- 共用件：构造合法 SQLite 头的最小库文件字节 ---------- */

    /** 100 字节最小 SQLite 文件头：magic + 填充 + user_version（偏移 60，大端 u32）。 */
    private fun fakeDbBytes(userVersion: Int): ByteArray = ByteArray(100).also {
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
        it[60] = (userVersion ushr 24).toByte()
        it[61] = (userVersion ushr 16).toByte()
        it[62] = (userVersion ushr 8).toByte()
        it[63] = userVersion.toByte()
        // 主库内容可辨识标记放在头部之后（断言用）
        "BODY-$userVersion".toByteArray().copyInto(it, 64)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return f
    }

    private fun restore(file: File, dbDir: File, imageDir: File, current: Int = 5): RestoreVerdict =
        restoreZipInto(
            ZipInputStream(FileInputStream(file).buffered()),
            dbDir, imageDir, current, tmp.newFolder().apply { mkdirs() },
        )

    /* ---------- AU-1：safeEntryFile 归一化校验 ---------- */

    @Test
    fun `entry name with dotdot escapes are rejected`() {
        val dir = tmp.newFolder("dest")
        assertNull("../escape.txt".let { safeEntryFile(dir, it) })
        assertNull(safeEntryFile(dir, "sub/../../escape.txt"))
        assertNull(safeEntryFile(dir, ".."))
        assertNull(safeEntryFile(dir, "images/.."))
    }

    @Test
    fun `directory itself and blank names are rejected`() {
        val dir = tmp.newFolder("dest2")
        // 条目 "database/" 与 "images/" 去掉前缀后为空 → 目标 = 目录自身，不是文件
        assertNull(safeEntryFile(dir, ""))
        assertNull(safeEntryFile(dir, "   "))
        assertNull(safeEntryFile(dir, "/"))
    }

    @Test
    fun `legitimate nested names are kept inside the destination`() {
        val dir = tmp.newFolder("dest3")
        val ok = safeEntryFile(dir, "simple_ledger.db-wal")
        assertNotNull(ok)
        assertEquals(dir.canonicalPath + File.separator + "simple_ledger.db-wal", ok!!.canonicalPath)
        val nested = safeEntryFile(dir, "sub/inner.jpg")
        assertNotNull(nested)
        assertTrue(nested!!.canonicalPath.startsWith(dir.canonicalPath + File.separator))
    }

    /* ---------- AU-1：restoreZipInto 对恶意条目的整体防线 ---------- */

    @Test
    fun `malicious zip cannot write outside staging directories`() {
        val dbDir = tmp.newFolder("databases")
        val imageDir = tmp.newFolder("images")
        val sharedPrefs = tmp.newFolder("shared_prefs")
        val escaped = File(imageDir, "../../../evil-root.txt").canonicalFile

        val zipFile = zip(
            // 沙箱内越界写：覆盖 shared_prefs（应用锁 / 同步凭证所在）——暂存阶段即被丢弃
            "database/../shared_prefs/simple_ledger_settings.xml" to "hacked".toByteArray(),
            // 越出 images 目录
            "images/../../../evil-root.txt" to "hacked".toByteArray(),
            // 合法条目照常落位（恶意包通常混入合法条目掩盖痕迹）
            "database/simple_ledger.db" to fakeDbBytes(5),
            "images/real.jpg" to "JPEG".toByteArray(),
        )

        val verdict = restore(zipFile, dbDir, imageDir)

        assertEquals(RestoreVerdict.Ok, verdict)
        // 越界目标不存在
        assertFalse(File(sharedPrefs, "simple_ledger_settings.xml").exists())
        assertFalse("逃逸条目不得落盘：$escaped", escaped.exists())
        // 合法条目正常落位
        assertTrue(File(dbDir, "simple_ledger.db").readBytes().contentEquals(fakeDbBytes(5)))
        assertEquals("JPEG", File(imageDir, "real.jpg").readText())
    }

    /* ---------- P0 第二段：校验不通过 = 本地零触碰 ---------- */

    @Test
    fun `zip without database entry is not a backup and writes nothing`() {
        val dbDir = tmp.newFolder("databases3")
        val imageDir = tmp.newFolder("images3")
        File(dbDir, "simple_ledger.db-wal").writeText("LOCAL-WAL-INTACT")

        // 误选了不含数据库的 zip：本地库不动（wal 里的未 checkpoint 事务不能丢），
        // 贴图也不落盘——不是简账备份就不该往用户数据区写任何东西
        val verdict = restore(
            zip("images/pic.jpg" to "PIC".toByteArray()),
            dbDir, imageDir,
        )

        assertEquals(RestoreVerdict.NotABackup, verdict)
        assertEquals("LOCAL-WAL-INTACT", File(dbDir, "simple_ledger.db-wal").readText())
        assertFalse(File(imageDir, "pic.jpg").exists())
    }

    @Test
    fun `non-sqlite database entry is rejected as not a backup`() {
        val dbDir = tmp.newFolder("databases-nonsqlite")
        val imageDir = tmp.newFolder("images-nonsqlite")
        File(dbDir, "simple_ledger.db").writeText("PRECIOUS-LOCAL-DATA")

        val verdict = restore(
            zip("database/simple_ledger.db" to "just some text, not sqlite".toByteArray()),
            dbDir, imageDir,
        )

        assertEquals(RestoreVerdict.NotABackup, verdict)
        // 本地主库原样
        assertEquals("PRECIOUS-LOCAL-DATA", File(dbDir, "simple_ledger.db").readText())
    }

    @Test
    fun `backup from newer schema is rejected before touching local data`() {
        val dbDir = tmp.newFolder("databases-newer")
        val imageDir = tmp.newFolder("images-newer")
        File(dbDir, "simple_ledger.db").writeText("LOCAL-V5")

        // 备份 user_version=9，当前应用 schema=5：恢复会让 Room 打开即抛异常（启动永久崩溃），
        // 必须在触碰本地数据之前拒绝
        val verdict = restore(
            zip(
                "database/simple_ledger.db" to fakeDbBytes(9),
                "images/pic.jpg" to "PIC".toByteArray(),
            ),
            dbDir, imageDir, current = 5,
        )

        assertEquals(RestoreVerdict.NewerSchema(9), verdict)
        assertEquals("LOCAL-V5", File(dbDir, "simple_ledger.db").readText())
        assertFalse(File(imageDir, "pic.jpg").exists())
        // 不允许留下任何 .bak 残留
        assertFalse(File(dbDir, "simple_ledger.db.bak").exists())
    }

    /* ---------- P0 第三段：成功换库 + 失败回滚 ---------- */

    @Test
    fun `successful restore swaps db trio keeps backup wal and cleans bak`() {
        val dbDir = tmp.newFolder("databases2")
        val imageDir = tmp.newFolder("images2")

        // 本地旧库三件套：主库 + 带「未 checkpoint 事务」的旧 wal
        File(dbDir, "simple_ledger.db").writeText("OLD-MAIN")
        File(dbDir, "simple_ledger.db-wal").writeText("OLD-WAL-STALE-TX")
        File(dbDir, "simple_ledger.db-shm").writeText("OLD-SHM")

        val verdict = restore(
            zip(
                "database/simple_ledger.db" to fakeDbBytes(5),
                // 备份包自带 wal：里面有备份时未 checkpoint 的最后几笔账目——必须保留（AU-2）
                "database/simple_ledger.db-wal" to "BACKUP-WAL-PENDING-TX".toByteArray(),
                "images/pic.jpg" to "PIC".toByteArray(),
            ),
            dbDir, imageDir,
        )

        assertEquals(RestoreVerdict.Ok, verdict)
        assertTrue(File(dbDir, "simple_ledger.db").readBytes().contentEquals(fakeDbBytes(5)))
        // AU-2 修复点：备份包的 wal 原样落位
        assertEquals("BACKUP-WAL-PENDING-TX", File(dbDir, "simple_ledger.db-wal").readText())
        // zip 未带 shm → 旧库的 shm 残留应已被清掉（在换库之前）
        assertFalse(File(dbDir, "simple_ledger.db-shm").exists())
        // P0 修复点：成功后 .bak 清理（旧实现永不清理，长期残留）
        assertFalse(File(dbDir, "simple_ledger.db.bak").exists())
        assertFalse(File(dbDir, "simple_ledger.db-wal.bak").exists())
        assertEquals("PIC", File(imageDir, "pic.jpg").readText())
    }

    @Test
    fun `mid-phase failure rolls back database from bak`() {
        val dbDir = tmp.newFolder("databases-rollback")
        val imageDir = tmp.newFolder("images-rollback")
        File(dbDir, "simple_ledger.db").writeText("CURRENT-MAIN")
        File(dbDir, "simple_ledger.db-wal").writeText("CURRENT-WAL")

        // 制造确定性中途失败：imageDir 里预建一个**非空**目录、与暂存贴图同名——
        // copyTo(overwrite=true) 会先尝试删除目标，非空目录删不掉即抛
        // FileAlreadyExistsException（空目录会被静默替换，所以必须非空）。
        // 此刻库已被换过 → 必须走 .bak 回滚
        File(imageDir, "collide.jpg").apply {
            mkdirs()
            File(this, "inner.txt").writeText("x")
        }

        val zipFile = zip(
            "database/simple_ledger.db" to fakeDbBytes(5),
            "images/collide.jpg" to "PIC".toByteArray(),
        )

        val thrown = runCatching { restore(zipFile, dbDir, imageDir) }.exceptionOrNull()

        assertNotNull("贴图阶段应抛出 IO 异常", thrown)
        // 回滚后：三件套回到原样（字节级），.bak 已清理——「已保留原有数据」为真
        assertEquals("CURRENT-MAIN", File(dbDir, "simple_ledger.db").readText())
        assertEquals("CURRENT-WAL", File(dbDir, "simple_ledger.db-wal").readText())
        assertFalse(File(dbDir, "simple_ledger.db.bak").exists())
        assertFalse(File(dbDir, "simple_ledger.db-wal.bak").exists())
    }
}
