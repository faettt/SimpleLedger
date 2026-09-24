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

    /**
     * 带 ¥ 金额：**符号在货币符之前**（「−¥18,545.00」），与账目行的「−¥ / +¥」语序统一。
     *
     * 负号用真减号 U+2212 —— 楷体子集 v2 已收录（见 Fonts.kt）；
     * 旧子集缺字才用 ASCII hyphen 的做法随子集重建作废。
     */
    fun formatWithSymbol(cents: Long): String =
        if (cents < 0) "−¥" + formatCents(-cents) else "¥" + formatCents(cents)

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

    /* ------------------------------------------------------------------
       读屏用中文金额
       ------------------------------------------------------------------ */

    private val cnDigits = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")

    /** 组内单位：千 / 百 / 十 / 个（个位无单位字） */
    private val cnGroupUnits = arrayOf("千", "百", "十", "")

    /** 组间单位：个 / 万 / 亿 */
    private val cnScaleUnits = arrayOf("", "万", "亿")

    /**
     * 把「分」转成读屏友好的中文读法（**不含**支出/收入方向词，方向由 UI 层拼接）。
     *
     * 规格（与设计文档 §09 一致）：
     * - 整元 → 「…元整」，如 200000 → "两千元整"
     * - 有角无分 → 「…元…角」，如 10050 → "一百元五角"
     * - 有元有分且角为 0 → 「…元零…分」，如 2005 → "二十元零五分"
     * - 元为 0、只有角分 → 「…角…分」，如 35 → "三角五分"
     * - 不足 1 角、只有分 → 「…分」，如 5 → "五分"
     * - 0 → "零元整"
     *
     * 之所以交系统 TTS 读 `¥2,000.00` 会有两种坏结果：念成「二千点零零」，或夹英文数字，
     * 且不受控；中文用户听到的是噪音。故此处自行成词。
     *
     * @param cents 金额（分），支持到「亿」（`parseToCents` 上限 99_999_999_999 分 ≈ 10 亿）
     */
    fun toChineseSpeech(cents: Long): String {
        val negative = cents < 0
        val abs = kotlin.math.abs(cents)
        val yuan = abs / 100
        val jiao = (abs % 100) / 10
        val fen = abs % 10

        val sb = StringBuilder()
        if (negative) sb.append("负")
        if (yuan > 0L) {
            sb.append(chineseInteger(yuan)).append("元")
            when {
                jiao == 0L && fen == 0L -> sb.append("整")
                // 角为 0 但分非 0：中间必须补「零」，否则「二十元五分」会被听成「二十元五分」连读的怪词
                jiao == 0L -> sb.append("零").append(cnDigits[fen.toInt()]).append("分")
                fen == 0L -> sb.append(cnDigits[jiao.toInt()]).append("角")
                else -> sb.append(cnDigits[jiao.toInt()]).append("角")
                    .append(cnDigits[fen.toInt()]).append("分")
            }
        } else {
            when {
                jiao == 0L && fen == 0L -> sb.append("零元整")
                jiao == 0L -> sb.append(cnDigits[fen.toInt()]).append("分")
                fen == 0L -> sb.append(cnDigits[jiao.toInt()]).append("角")
                else -> sb.append(cnDigits[jiao.toInt()]).append("角")
                    .append(cnDigits[fen.toInt()]).append("分")
            }
        }
        return sb.toString()
    }

    /**
     * 非负整数的中文读法（万进），支持到「亿」。
     *
     * 「零」的处理是本函数最容易错的部分，分两层：
     * 1. **组内**（见 [readGroup]）：连续多个 0 只出一个「零」，且**组末尾的 0 一律省略**
     *    （1050 读「一千零五十」，不是「一千零五十零」）；
     * 2. **组间**：高位组与低位组之间若低位组不足 4 位（有前导 0），补一个「零」
     *    （10005 读「一万零五」）；若中间整组为 0 也补「零」（100000005 →「一亿零五」）。
     */
    private fun chineseInteger(n: Long): String {
        if (n == 0L) return cnDigits[0]

        // 按 4 位一组拆分：groups[0]=个组, groups[1]=万组, groups[2]=亿组
        val groups = ArrayList<Int>()
        var x = n
        while (x > 0L) {
            groups.add((x % 10000L).toInt())
            x /= 10000L
        }

        val sb = StringBuilder()
        var pendingZero = false
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) {
                // 整组为 0：先记下需要补「零」，等后面真出现非 0 组时再补，避免出现「一万零零五」
                if (sb.isNotEmpty()) pendingZero = true
                continue
            }
            // 组间补「零」：要么中间隔了空组，要么本组不足 4 位（有前导 0）
            if (sb.isNotEmpty() && (pendingZero || g < 1000)) sb.append(cnDigits[0])
            pendingZero = false
            sb.append(readGroup(g)).append(cnScaleUnits[i])
        }

        var s = sb.toString()
        // 最前端的「一十」口语化为「十」：10→十、15→十五、100000→十万、1000000→一百万 不变。
        // 只处理**首部**：110 应读「一百一十」，其中「一十」在末尾，不能动。
        if (s.startsWith("一十")) s = s.substring(1)
        // 首位的「二」在百/千/万/亿之前读「两」：两千 / 两万 / 两亿；但 20 仍读「二十」
        s = when {
            s.startsWith("二百") || s.startsWith("二千") ||
                s.startsWith("二万") || s.startsWith("二亿") -> "两" + s.substring(1)

            else -> s
        }
        return s
    }

    /** 读一个 4 位以内的组（0..9999），忽略组末尾的 0，连续 0 只出一个「零」 */
    private fun readGroup(g: Int): String {
        val ds = intArrayOf(g / 1000, (g / 100) % 10, (g / 10) % 10, g % 10)
        val sb = StringBuilder()
        var zero = false
        for (i in 0..3) {
            val d = ds[i]
            if (d == 0) {
                zero = true
                continue
            }
            if (zero && sb.isNotEmpty()) sb.append(cnDigits[0])
            zero = false
            sb.append(cnDigits[d]).append(cnGroupUnits[i])
        }
        return sb.toString()
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
