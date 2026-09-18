package com.simpleledger.app

import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.logic.StatsCalculator
import org.junit.Assert.assertEquals
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
            com.simpleledger.app.data.local.entity.CategoryTotal(1, "餐饮", "🍚", 3000L, 3),
            com.simpleledger.app.data.local.entity.CategoryTotal(2, "交通", "🚌", 1000L, 1),
        )
        val shares = StatsCalculator.categoryShares(totals)
        assertEquals(2, shares.size)
        assertEquals(0.75, shares[0].fraction, 1e-9)
        assertEquals(0.25, shares[1].fraction, 1e-9)

        assertEquals(0, StatsCalculator.categoryShares(emptyList()).size)
    }
}
