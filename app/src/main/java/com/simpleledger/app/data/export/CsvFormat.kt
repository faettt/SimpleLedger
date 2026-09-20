package com.simpleledger.app.data.export

/**
 * CSV 字段转义（RFC 4180），纯 Kotlin、无 Android 依赖，便于 JVM 单测。
 *
 * 规则：字段中含有**逗号**、**双引号**或**换行符**（含 LF `\n` 与 CR `\r`）时，
 * 用双引号包裹整个字段，字段内部的双引号加倍（`"` → `""`）。
 *
 * 为什么必须处理 `\r`：账目备注、分区备注都是多行输入框（`minLines = 2`），
 * 用户可能粘贴含 CR/CRLF 的文本；若只认 `\n`，单个 `\r` 会逃逸出字段边界，
 * 导致 Excel 等解析器把一行拆成两行、列错位。
 */
object CsvFormat {

    /** 需要触发引号包裹的字符集合（逗号 / 双引号 / LF / CR） */
    private fun needsQuoting(value: String): Boolean =
        value.contains(',') ||
            value.contains('"') ||
            value.contains('\n') ||
            value.contains('\r')

    /**
     * 按 RFC 4180 转义单个 CSV 字段。
     *
     * @param value 原始字段值
     * @return 可安全写入 CSV 的字段文本
     */
    fun escape(value: String): String =
        if (needsQuoting(value)) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}