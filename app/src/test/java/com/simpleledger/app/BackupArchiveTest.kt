package com.simpleledger.app

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
 * 备份包解压恢复内核的回归钉子（AU-1 / AU-2，`restoreZipInto` / `safeEntryFile`
 * 为 DataExporter 拆出的纯 JVM 内核，不依赖 Android 运行时）。
 *
 * AU-1（Zip Slip，high）：restoreBackup 接受 SAF 里任选的 zip，条目名含 `../`
 * 时可写到目标目录之外（如 `database/../shared_prefs/…` 覆盖应用锁 prefs、
 * 改写同步指向的服务器）——归一化路径必须仍在目标目录内，逃逸条目跳过。
 *
 * AU-2（mustFix）：旧实现在解压完之后无条件删除 `-wal/-shm`，把**刚从备份包
 * 解压出来的** wal/shm 一并误删——备份时还躺在 WAL 里未 checkpoint 的事务随
 * 「恢复成功」静默丢失。修复后旧库残留日志在第一个 database/ 条目落位**之前**
 * 清掉，zip 自带的 wal/shm 原样保留。
 */
class BackupArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

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
    fun `malicious zip cannot write outside target directories`() {
        val dbDir = tmp.newFolder("databases")
        val imageDir = tmp.newFolder("images")
        val sharedPrefs = tmp.newFolder("shared_prefs")
        val zipFile = tmp.newFile("evil.zip")
        // 精确计算恶意条目的落点（修复前会真的写到这里）
        val escaped = File(imageDir, "../../../evil-root.txt").canonicalFile

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            // 沙箱内越界写：覆盖 shared_prefs（应用锁 / 同步凭证所在）
            zip.putNextEntry(ZipEntry("database/../shared_prefs/simple_ledger_settings.xml"))
            zip.write("hacked".toByteArray())
            zip.closeEntry()
            // 越出 images 目录
            zip.putNextEntry(ZipEntry("images/../../../evil-root.txt"))
            zip.write("hacked".toByteArray())
            zip.closeEntry()
            // 合法条目照常落位（恶意包通常混入合法条目掩盖痕迹）
            zip.putNextEntry(ZipEntry("database/simple_ledger.db"))
            zip.write("MAIN-DB".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("images/real.jpg"))
            zip.write("JPEG".toByteArray())
            zip.closeEntry()
        }

        restoreZipInto(ZipInputStream(FileInputStream(zipFile)), dbDir, imageDir)

        // 越界目标全部不存在
        assertFalse(File(sharedPrefs, "simple_ledger_settings.xml").exists())
        assertFalse("逃逸条目不得落盘：$escaped", escaped.exists())
        // 合法条目正常落位
        assertEquals("MAIN-DB", File(dbDir, "simple_ledger.db").readText())
        assertEquals("JPEG", File(imageDir, "real.jpg").readText())
    }

    /* ---------- AU-2：旧库残留日志先清、zip 自带 wal/shm 保留 ---------- */

    @Test
    fun `stale wal-shm cleared before extraction and backup wal-shm kept`() {
        val dbDir = tmp.newFolder("databases2")
        val imageDir = tmp.newFolder("images2")

        // 本地旧库三件套：主库 + 带「未 checkpoint 事务」的旧 wal
        File(dbDir, "simple_ledger.db").writeText("OLD-MAIN")
        File(dbDir, "simple_ledger.db-wal").writeText("OLD-WAL-STALE-TX")
        File(dbDir, "simple_ledger.db-shm").writeText("OLD-SHM")

        val zipFile = tmp.newFile("good.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("database/simple_ledger.db"))
            zip.write("NEW-MAIN".toByteArray())
            zip.closeEntry()
            // 备份包自带 wal：里面有备份时未 checkpoint 的最后几笔账目——必须保留
            zip.putNextEntry(ZipEntry("database/simple_ledger.db-wal"))
            zip.write("BACKUP-WAL-PENDING-TX".toByteArray())
            zip.closeEntry()
        }

        restoreZipInto(ZipInputStream(FileInputStream(zipFile)), dbDir, imageDir)

        assertEquals("NEW-MAIN", File(dbDir, "simple_ledger.db").readText())
        // 修复点：备份包的 wal 原样落位（旧实现会在解压后把它无条件删掉）
        assertEquals("BACKUP-WAL-PENDING-TX", File(dbDir, "simple_ledger.db-wal").readText())
        // zip 未带 shm → 旧库的 shm 残留应已被清掉（在第一个 database/ 条目落位前）
        assertFalse(File(dbDir, "simple_ledger.db-shm").exists())
    }

    @Test
    fun `zip without database entries leaves local database untouched`() {
        val dbDir = tmp.newFolder("databases3")
        val imageDir = tmp.newFolder("images3")
        File(dbDir, "simple_ledger.db-wal").writeText("LOCAL-WAL-INTACT")

        val zipFile = tmp.newFile("images-only.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("images/pic.jpg"))
            zip.write("PIC".toByteArray())
            zip.closeEntry()
        }

        restoreZipInto(ZipInputStream(FileInputStream(zipFile)), dbDir, imageDir)

        // 误选了不含数据库的 zip：不该清掉本地 wal（否则未 checkpoint 事务凭空丢失）
        assertEquals("LOCAL-WAL-INTACT", File(dbDir, "simple_ledger.db-wal").readText())
        assertEquals("PIC", File(imageDir, "pic.jpg").readText())
    }

    @Test
    fun `current data is backed up to bak files before overwrite`() {
        val dbDir = tmp.newFolder("databases4")
        val imageDir = tmp.newFolder("images4")
        File(dbDir, "simple_ledger.db").writeText("CURRENT-MAIN")
        File(dbDir, "simple_ledger.db-wal").writeText("CURRENT-WAL")

        val zipFile = tmp.newFile("restore.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("database/simple_ledger.db"))
            zip.write("RESTORED".toByteArray())
            zip.closeEntry()
        }

        restoreZipInto(ZipInputStream(FileInputStream(zipFile)), dbDir, imageDir)

        assertEquals("CURRENT-MAIN", File(dbDir, "simple_ledger.db.bak").readText())
        assertEquals("CURRENT-WAL", File(dbDir, "simple_ledger.db-wal.bak").readText())
        assertEquals("RESTORED", File(dbDir, "simple_ledger.db").readText())
    }
}
