package com.simpleledger.app.util

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 金额格式化与解析（存储单位：分） */
object Money {

    private val format = DecimalFormatCache.get()

    fun formatCents(cents: Long): String = format.format(cents / 100.0)

    fun formatWithSymbol(cents: Long): String = "¥" + formatCents(cents)

    /** "12.5" / "12" / "0.99" -> 1250 / 1200 / 99，非法输入返回 null */
    fun parseToCents(input: String): Long? {
        val cleaned = input.trim().replace(",", "")
        if (cleaned.isEmpty()) return null
        return runCatching {
            val value = BigDecimal(cleaned)
            val cents = value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
            if (cents in 1..99_999_999_999L) cents else null
        }.getOrNull()
    }
}

private object DecimalFormatCache {
    fun get(): java.text.DecimalFormat =
        (java.text.DecimalFormat.getNumberInstance(Locale.US) as java.text.DecimalFormat).apply {
            applyPattern("#,##0.00")
        }
}

/** 时间展示与月份区间换算（时间管理） */
object DateTimes {

    private val weekDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun dayLabel(date: LocalDate): String =
        "${date.monthValue}月${date.dayOfMonth}日 ${weekDays[date.dayOfWeek.value - 1]}"

    fun monthLabel(month: YearMonth): String = "${month.year}年${month.monthValue}月"

    fun timeLabel(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    fun dateLabel(date: LocalDate): String =
        "%04d年%02d月%02d日".format(date.year, date.monthValue, date.dayOfMonth)

    fun toLocalDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun toLocalTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()

    /** 某个月的起止区间：[start, end) */
    fun monthRange(month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }
}
