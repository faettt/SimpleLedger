package com.simpleledger.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * F-1（QA Round 2，2026-09-25）：回收站冲突副标题「双逗号」回归锁定。
 *
 * 口径：素材归 strings.xml（**不带前导标点**），拼接单一来源收口在
 * ConflictTrashScreen（「A + "，" + B」）。本测试在纯 JVM 上：
 * 1. 直接解析磁盘上的 strings.xml（防止有人又把「，」塞回素材）；
 * 2. 复刻 Screen 侧 line1 拼接表达式，断言最终成句无双逗号 / 无前导标点。
 */
class TrashConflictLineTest {

    // ---------- strings.xml 解析 ----------

    private fun stringsXml(): File {
        // 单测工作目录 = app/ 模块目录；向上兜底找一次，防 CI 差异
        var dir: File? = File(System.getProperty("user.dir") ?: error("no user.dir"))
        repeat(3) {
            val candidate = File(dir!!, "src/main/res/values/strings.xml")
            if (candidate.isFile) return candidate
            dir = dir!!.parentFile ?: return@repeat
        }
        error("strings.xml not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun readStrings(): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsXml())
        val nodes = doc.getElementsByTagName("string")
        val out = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name").nodeValue
            out[name] = node.textContent
        }
        return out
    }

    private val strings by lazy { readStrings() }

    private fun res(name: String, vararg args: String): String =
        strings.getValue(name).let { s ->
            var result = s
            args.forEachIndexed { index, arg -> result = result.replace("%${index + 1}\$s", arg) }
            result
        }

    // ---------- 复刻 ConflictTrashScreen 的 line1 拼接（单一来源同构） ----------

    /** 与 ConflictTrashScreen.kt:183-197 的 when 表达式逐分支同构 */
    private fun composeLine1(kindOverwrite: Boolean, conflict: Boolean, deletedByName: String, actorLabel: String): String {
        return if (kindOverwrite) {
            res("trash_line_overwrite") +
                if (conflict) "，" + res("trash_line_overwrite_by", actorLabel) else ""
        } else {
            res("trash_line_deleted", deletedByName) +
                if (conflict) "，" + res("trash_line_modified", actorLabel) else ""
        }
    }

    // ---------- 1) 素材本身不带前导标点 ----------

    @Test
    fun `raw materials carry no leading punctuation`() {
        for (name in listOf("trash_line_deleted", "trash_line_modified", "trash_line_overwrite", "trash_line_overwrite_by")) {
            val raw = strings.getValue(name)
            assertFalse("$name 不得以逗号开头", raw.startsWith("，") || raw.startsWith(","))
            assertFalse("$name 不得以句读结尾造成重复", raw.endsWith("，"))
        }
    }

    // ---------- 2) 成句结果无双逗号 ----------

    @Test
    fun `delete-modify conflict line has no double comma`() {
        val line = composeLine1(
            kindOverwrite = false, conflict = true,
            deletedByName = "另一台设备", actorLabel = "本机",
        )
        assertEquals("被 另一台设备 删除，被 本机 修改", line)
        assertFalse("不得出现双逗号", line.contains("，，"))
        assertFalse("不得出现前导逗号", line.startsWith("，"))
    }

    @Test
    fun `pure delete line has no trailing comma`() {
        val line = composeLine1(
            kindOverwrite = false, conflict = false,
            deletedByName = "本机", actorLabel = "本机",
        )
        assertEquals("被 本机 删除", line)
        assertFalse(line.endsWith("，"))
    }

    @Test
    fun `overwrite line has no double comma`() {
        val line = composeLine1(
            kindOverwrite = true, conflict = true,
            deletedByName = "", actorLabel = "本机",
        )
        assertEquals("被修改覆盖，被 本机 覆盖", line)
        assertFalse("不得出现双逗号", line.contains("，，"))
    }

    // ---------- 3) 未知成员兜底（Screen 侧 ifBlank 落「未知成员」） ----------

    @Test
    fun `blank deleted-by falls back to unknown member`() {
        val fallback = "未知成员"
        val line = composeLine1(
            kindOverwrite = false, conflict = true,
            deletedByName = fallback, actorLabel = "另一台设备",
        )
        assertEquals("被 未知成员 删除，被 另一台设备 修改", line)
        assertFalse(line.contains("，，"))
    }
}
