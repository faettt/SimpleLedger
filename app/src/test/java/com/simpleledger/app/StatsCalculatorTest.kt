package com.simpleledger.app

import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.logic.CategoryShare
import com.simpleledger.app.logic.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsCalculatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun entry(type: Int, cents: Long, timeMillis: Long) = EntryEntity(
        id = 0,
        type = type,
        amountCents = cents,
        categoryId = 1,
        sectionId = 1,
        entryTime = timeMillis,
    )

    @Test
    fun `daily expense groups by local date`() {
        // 2026-09-18 10:00 与 22:00（上海时间），以及 9 月 17 日一笔
        val sep18_10am = java.time.ZonedDateTime.of(2026, 9, 18, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sep18_10pm = java.time.ZonedDateTime.of(2026, 9, 18, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sep17_9am = java.time.ZonedDateTime.of(2026, 9, 17, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

        val entries = listOf(
            entry(EntryType.EXPENSE, 1000L, sep18_10am),
            entry(EntryType.EXPENSE, 2500L, sep18_10pm),
            entry(EntryType.INCOME, 9000L, sep18_10am), // 收入不参与每日支出
            entry(EntryType.EXPENSE, 500L, sep17_9am),
        )

        val daily = StatsCalculator.dailyExpense(entries, zone)

        assertEquals(
            listOf(LocalDate.of(2026, 9, 17) to 500L, LocalDate.of(2026, 9, 18) to 3500L),
            daily,
        )
    }

    @Test
    fun `balance is income minus expense`() {
        assertEquals(4000L, StatsCalculator.balance(9000L, 5000L))
        assertEquals(-1000L, StatsCalculator.balance(0L, 1000L))
    }

    @Test
    fun `category shares sum to 1 and handle empty`() {
        val totals = listOf(
            com.simpleledger.app.data.local.entity.CategoryTotal(
                categoryId = 1, name = "餐饮", iconId = 2,
                sectionId = null, sectionName = null, sectionIconId = null, sectionColorIndex = null,
                total = 3000L, count = 3,
            ),
            com.simpleledger.app.data.local.entity.CategoryTotal(
                categoryId = 2, name = "交通", iconId = 8,
                sectionId = null, sectionName = null, sectionIconId = null, sectionColorIndex = null,
                total = 1000L, count = 1,
            ),
        )
        val shares = StatsCalculator.categoryShares(totals)
        assertEquals(2, shares.size)
        assertEquals(0.75, shares[0].fraction, 1e-9)
        assertEquals(0.25, shares[1].fraction, 1e-9)

        assertEquals(0, StatsCalculator.categoryShares(emptyList()).size)
    }

    /* ---------------------------------------------- 读图兜底①：小扇区合并 */

    private fun share(id: Long, name: String, total: Long, fraction: Double) =
        CategoryShare(
            total = CategoryTotal(
                categoryId = id, name = name, iconId = 2,
                sectionId = null, sectionName = null, sectionIconId = null,
                sectionColorIndex = null, total = total, count = 1,
            ),
            fraction = fraction,
        )

    @Test
    fun `tiny slices are folded into one other bucket`() {
        // 3° = 0.8333%。医疗 / 购物 都在线下，必须合并；人工 9.9% 必须留下。
        val shares = listOf(
            share(1, "主材", 9000L, 0.9000),
            share(2, "人工", 990L, 0.0990),
            share(3, "医疗", 6L, 0.0006),
            share(4, "购物", 4L, 0.0004),
        )
        val merged = StatsCalculator.mergeSmallShares(shares)

        assertEquals(3, merged.size)
        assertEquals(listOf("主材", "人工", "其他 2 类"), merged.map { it.total.name })
        // isMerged 只对合并桶为真 —— 它决定取色走中性墨灰而非色板轮转
        assertEquals(listOf(false, false, true), merged.map { it.isMerged })

        val bucket = merged.last().total
        assertEquals(10L, bucket.total)   // 6 + 4
        assertEquals(2, bucket.count)
        assertEquals(-1L, bucket.categoryId)  // 哨兵：点不到任何真实分类
        assertNull(bucket.sectionId)          // 跨分区聚合，无单一归属
    }

    @Test
    fun `slices are capped at the tape palette size to avoid duplicate colors`() {
        // 12 类各 8.33%（都远大于 3°）→ 小扇区规则拦不住，必须靠色板容量拦。
        // 否则第 9 项起回头复用 8 色板，环上会出现两块同色扇区。
        val shares = (1..12).map { share(it.toLong(), "类$it", 100L, 1.0 / 12) }
        val merged = StatsCalculator.mergeSmallShares(shares)

        assertEquals(StatsCalculator.TAPE_PALETTE_SIZE + 1, merged.size)  // 8 + 其他
        assertEquals("其他 4 类", merged.last().total.name)
        assertEquals(400L, merged.last().total.total)
        // 合并后占比仍然收敛到 1，图例百分比不会加起来不等于 100%
        assertEquals(1.0, merged.sumOf { it.fraction }, 1e-9)
    }

    @Test
    fun `nothing to merge returns the very same list`() {
        val shares = listOf(
            share(1, "主材", 6000L, 0.60),
            share(2, "人工", 4000L, 0.40),
        )
        val merged = StatsCalculator.mergeSmallShares(shares)
        assertEquals(2, merged.size)
        assertEquals(shares, merged)
        assertEquals(0, StatsCalculator.mergeSmallShares(emptyList()).size)
        assertEquals(1, StatsCalculator.mergeSmallShares(listOf(shares[0])).size)
    }

    /* ------------------------------------------------- 占比文案 */

    @Test
    fun `percent label rounds and never shows zero for a real amount`() {
        assertEquals("31%", StatsCalculator.percentLabel(0.3114))
        assertEquals("100%", StatsCalculator.percentLabel(1.0))
        // 四舍五入而非截断：0.7% 要显示 1%，截断会变成 0%
        assertEquals("1%", StatsCalculator.percentLabel(0.007))
        // 非零但不足 0.5% → 写「<1%」。旁边标着金额却写 0% 是自相矛盾的
        assertEquals("<1%", StatsCalculator.percentLabel(0.003))
        assertEquals("0%", StatsCalculator.percentLabel(0.0))
    }
}
