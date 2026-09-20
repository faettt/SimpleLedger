package com.simpleledger.app

import com.simpleledger.app.data.export.CsvFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CSV 字段转义单测（RFC 4180）。
 *
 * 这里锁死的是一个真实 bug 的修复：原 `escape()` 只检查 `,` / `"` / `\n`，
 * **漏了 `\r`**。而账目备注、分区备注都是多行输入框（`minLines = 2`），
 * 用户粘贴含 CR 或 CRLF 的文本时，单个 `\r` 会冲出字段边界 → Excel 拆行错列。
 */
class CsvFormatTest {

    @Test
    fun `plain field is unchanged`() {
        assertEquals("餐饮", CsvFormat.escape("餐饮"))
        assertEquals("123.45", CsvFormat.escape("123.45"))
        assertEquals("", CsvFormat.escape(""))
    }

    @Test
    fun `field with comma is quoted`() {
        // 备注里带逗号——账目备注常见「午饭,加饮料」
        assertEquals("\"午饭,加饮料\"", CsvFormat.escape("午饭,加饮料"))
    }

    @Test
    fun `field with newline is quoted`() {
        assertEquals("\"第一行\n第二行\"", CsvFormat.escape("第一行\n第二行"))
    }

    @Test
    fun `field with lone carriage return is quoted - the fixed gap`() {
        // 这是修复点：单个 CR（\r）过去不被识别，会逃出字段边界
        assertEquals("\"第一行\r第二行\"", CsvFormat.escape("第一行\r第二行"))
    }

    @Test
    fun `field with CRLF is quoted`() {
        // Windows 记事本粘贴进来的换行是 \r\n
        assertEquals("\"行一\r\n行二\"", CsvFormat.escape("行一\r\n行二"))
    }

    @Test
    fun `internal double quote is doubled and wrapped`() {
        // 字段本身含双引号：整体包裹 + 内部引号加倍
        assertEquals("\"他说\"\"你好\"\"\"", CsvFormat.escape("他说\"你好\""))
    }

    @Test
    fun `quote plus comma both handled`() {
        assertEquals("\"a,\"\"b\"", CsvFormat.escape("a,\"b"))
    }

    @Test
    fun `lone carriage return without other specials still quoted`() {
        // 精确锁定门控条件里包含 '\r'
        assertEquals("\"\r\"", CsvFormat.escape("\r"))
    }
}