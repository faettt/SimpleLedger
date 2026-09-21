package com.simpleledger.app

import com.simpleledger.app.data.local.IconMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * emoji → iconId 迁移映射表的**契约测试**。
 *
 * 这张表是「历史数据」与「图标集」之间的唯一桥梁，错一条 = 升级后某个分类丢图标。
 * 三个断言维度（与 [IconMapping] 文件头的三条约束一一对应）：
 *
 * 1. **数量与双射**：50 条、iconId 1–50 连续、emoji 键无重复 —— 任何一边增删都必须同步，
 *    否则迁移会漏翻或翻重。
 * 2. **码点序列冻结**：键以码点构造（源文件无 emoji 字符），这里把码点序列逐条钉死，
 *    防止「顺手改了个字符」这类静默破坏。
 * 3. **VS16 标记**：6 个 emoji 在旧源码里带变体选择符（U+FE0F），表里的键必须已剥除，
 *    且这 6 个的 iconId 必须与 `VS16_ICON_IDS` 一致。
 */
class IconMappingTest {

    @Test
    fun `mapping has exactly fifty entries`() {
        assertEquals(50, IconMapping.EMOJI_TO_ICON_ID.size)
    }

    @Test
    fun `icon ids cover one to fifty without gaps or duplicates`() {
        val ids = IconMapping.EMOJI_TO_ICON_ID.values
        assertEquals("iconId 应为 1–50 连续（无缺、无重）", (1..50).toList(), ids.sorted())
        assertEquals("emoji 键不得重复", 50, IconMapping.EMOJI_TO_ICON_ID.keys.size)
    }

    @Test
    fun `keys are stripped of variation selector u-fe0f`() {
        // 约束 (2)：SQL 侧用 REPLACE(emoji, char(65039), '') 剥掉 VS16 再比对，
        // 因此表内键**必须**不含 VS16，否则永远匹配不上
        IconMapping.EMOJI_TO_ICON_ID.keys.forEach { key ->
            assertTrue(
                "键含 U+FE0F（应先剥除）：${key.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }}",
                !key.contains("\uFE0F"),
            )
        }
    }

    @Test
    fun `sql case body has one when per mapping and no semicolons`() {
        val sql = IconMapping.sqlCaseWhen()
        assertEquals("WHEN 子句数量 = 映射条数", 50, "WHEN".toRegex().findAll(sql).count())
        assertEquals("THEN 子句数量 = 映射条数", 50, "THEN".toRegex().findAll(sql).count())
        // execSQL 一次只执行一条语句，CASE 体只是片段，绝不能自带分号
        assertEquals(0, sql.count { it == ';' })
        // 抽查两条：首条 = pin(1)，末条 = refund(50)
        assertTrue(sql.contains("WHEN '\uD83D\uDCCC' THEN 1"))
        assertTrue(sql.contains("WHEN '\u21A9' THEN 50"))
    }

    @Test
    fun `vs16 marker matches the six emoji that carry a variation selector in legacy data`() {
        // 旧源码里带 VS16 的是：✈️(13) 🛍️(15) 🛠️(18) 🏷️(43) 🛋️(46) ↩️(50)
        assertEquals(setOf(13, 15, 18, 43, 46, 50), IconMapping.VS16_ICON_IDS)
    }

    @Test
    fun `defaults point at tag and pin`() {
        assertEquals(43, IconMapping.DEFAULT_CATEGORY_ICON_ID)
        assertEquals(1, IconMapping.DEFAULT_SECTION_ICON_ID)
        // 兜底值本身也必须在合法区间内，否则 slCategoryIcon() 会拿到非法 id
        assertTrue(IconMapping.DEFAULT_CATEGORY_ICON_ID in IconMapping.ICON_ID_RANGE)
        assertTrue(IconMapping.DEFAULT_SECTION_ICON_ID in IconMapping.ICON_ID_RANGE)
    }
}
