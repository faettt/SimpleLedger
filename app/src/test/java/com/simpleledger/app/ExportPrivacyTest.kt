package com.simpleledger.app

import com.simpleledger.app.data.export.ExportRow
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryImageEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.repo.entryToExportRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死一条**不可动摇的隐私边界**：CSV 导出必须始终写真实金额。
 *
 * 「隐藏金额」是显示层偏好，只影响屏幕；导出文件是用户的备份，必须完整。
 * 若后人让导出跟随 `hideAmounts`，用户会静默拿到一份缺金额的备份——数据丢失级事故。
 * 这些用例通过 `entryToExportRow`（纯映射函数，不碰文件 IO）直接断言。
 */
class ExportPrivacyTest {

    private fun entryFull(
        amountCents: Long,
        type: Int = EntryType.EXPENSE,
        note: String = "",
        category: CategoryEntity? = CategoryEntity(id = 1, name = "餐饮", emoji = "🍜", type = type),
        section: SectionEntity? = SectionEntity(id = 1, name = "日常开支", emoji = "📌"),
        images: List<EntryImageEntity> = emptyList(),
    ): EntryFull {
        val entry = EntryEntity(
            id = 1,
            type = type,
            amountCents = amountCents,
            categoryId = category?.id ?: 0,
            sectionId = section?.id ?: 0,
            entryTime = 0L,
            note = note,
        )
        return EntryFull(entry = entry, category = category, section = section, images = images)
    }

    @Test
    fun `export row always writes the real amount`() {
        val row = entryToExportRow(entryFull(amountCents = 200_000L))
        assertEquals("2000.00", row.amountYuan)
    }

    @Test
    fun `export row keeps cents and drops grouping separators`() {
        val row = entryToExportRow(entryFull(amountCents = 1_234_567L))
        // 真实值原样保留，仅去掉千分位逗号（CSV 字段分隔符是逗号，逗号会破坏列结构）
        assertEquals("12345.67", row.amountYuan)
        assertTrue("不应含千分位逗号", !row.amountYuan.contains(","))
    }

    @Test
    fun `export row never masks the amount`() {
        // 显式排除任何「打码」形态：不允许出现 •••• / 金额已隐藏 / 星号占位
        val row = entryToExportRow(entryFull(amountCents = 500_00L))
        assertEquals("500.00", row.amountYuan)
        assertTrue(!row.amountYuan.contains("••••"))
        assertTrue(!row.amountYuan.contains("金额已隐藏"))
        assertTrue(!row.amountYuan.contains("*"))
    }

    @Test
    fun `export row maps type label and relations`() {
        val expense = entryToExportRow(entryFull(amountCents = 100L, type = EntryType.EXPENSE))
        val income = entryToExportRow(
            entryFull(amountCents = 100L, type = EntryType.INCOME),
        )
        assertEquals("支出", expense.typeLabel)
        assertEquals("收入", income.typeLabel)
        assertEquals("餐饮", expense.category)
        assertEquals("日常开支", expense.section)
    }

    @Test
    fun `export row falls back for missing category and section`() {
        val row = entryToExportRow(
            entryFull(amountCents = 100L, category = null, section = null),
        )
        assertEquals("未分类", row.category)
        assertEquals("未分区", row.section)
        assertEquals("1.00", row.amountYuan)
    }

    @Test
    fun `export keeps exclusive category name regardless of visibility - EC09`() {
        // 分类「归属可见性」只影响候选集合，不影响导出（同步点 #4）：
        // 分区专属分类照样原样写出分类名与分区名，两列语义不变
        val row = entryToExportRow(
            entryFull(
                amountCents = 100L,
                category = CategoryEntity(
                    id = 9,
                    name = "主材",
                    emoji = "🧱",
                    type = EntryType.EXPENSE,
                    sectionId = 1L,
                ),
                section = SectionEntity(id = 1, name = "装修", emoji = "🔨"),
            ),
        )
        assertEquals("主材", row.category)
        assertEquals("装修", row.section)
    }

    @Test
    fun `export row counts images and carries note`() {
        val row = entryToExportRow(
            entryFull(
                amountCents = 100L,
                note = "午饭",
                images = listOf(
                    EntryImageEntity(id = 1, entryId = 1, filePath = "/a.jpg"),
                    EntryImageEntity(id = 2, entryId = 1, filePath = "/b.jpg"),
                ),
            ),
        )
        assertEquals(2, row.imageCount)
        assertEquals("午饭", row.note)
    }

    @Test
    fun `export row shape is intact`() {
        // 防御性：确认映射出的就是 ExportRow 且字段名未漂移
        val row: ExportRow = entryToExportRow(entryFull(amountCents = 42L))
        assertEquals("0.42", row.amountYuan)
    }
}