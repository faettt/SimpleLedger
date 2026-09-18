package com.simpleledger.app.logic

import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryType
import java.time.LocalDate
import java.time.ZoneId

/** 纯 Kotlin 统计逻辑，便于单元测试 */
object StatsCalculator {

    /** 每日支出（用于柱状图），按日期升序 */
    fun dailyExpense(
        entries: List<EntryEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<LocalDate, Long>> =
        entries.asSequence()
            .filter { it.type == EntryType.EXPENSE }
            .groupBy { StatsCalculator.toLocalDate(it.entryTime, zone) }
            .map { (date, list) -> date to list.sumOf { it.amountCents } }
            .sortedBy { it.first }

    fun balance(incomeCents: Long, expenseCents: Long): Long = incomeCents - expenseCents

    /** 分类占比（用于饼图），总额为 0 时返回空 */
    fun categoryShares(totals: List<CategoryTotal>): List<CategoryShare> {
        val grand = totals.sumOf { it.total }
        if (grand <= 0) return emptyList()
        return totals.map { CategoryShare(it, it.total.toDouble() / grand) }
    }

    fun toLocalDate(epochMillis: Long, zone: ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}

data class CategoryShare(
    val total: CategoryTotal,
    /** 0.0 ~ 1.0 */
    val fraction: Double,
)
