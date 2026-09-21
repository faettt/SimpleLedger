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

    /** 和纸胶带色板容量：环上一张图最多几个真实分类 */
    const val TAPE_PALETTE_SIZE = 8

    /** 环上最小可视角度 3° 对应的占比 */
    const val MIN_SLICE_FRACTION = 3.0 / 360.0

    /**
     * 占比文案（图例 / 环上标注 / 读屏串共用一份）。
     *
     * 两条口径：
     *  - **四舍五入**而不是截断：截断会把 0.7% 显示成 0%，让「31% / 22% / 20% / 10% /
     *    9% / 2% / 0% / 0% …」这种列表看起来像少了几类。
     *  - 非零但不足 0.5% 的写 **「<1%」**：一个旁边标着「¥680.00」的扇区写着「0%」
     *    是自相矛盾的，读者会以为数据错了。
     */
    fun percentLabel(fraction: Double): String {
        if (fraction <= 0.0) return "0%"
        val percent = kotlin.math.round(fraction * 100).toInt()
        return if (percent <= 0) "<1%" else "$percent%"
    }

    /**
     * 读图兜底①（规范 §2.5）：把「看不清」的扇区并成一个「其他」桶。
     *
     * 两条合并规则**都必须做**，它们解决的是两个不同的问题：
     *
     * ① **最小可视角度**：占比 < 3° 的扇区在环上只剩一条发丝。它不只是
     *    「自己看不见」——它把两侧扇区的角度也挤歪了，让人误判相邻两项的比例。
     * ② **色板容量**：和纸胶带色板只有 8 色。真实分类多于 8 个时，第 9 个起
     *    只能回头复用色板 → 环上出现两块同色扇区。而颜色是这张图**唯一**的
     *    「类别 → 图形」映射（另外两条通道是图例文字和读屏串），同色即等于没映射。
     *
     * 输入须已按金额降序（[categoryShares] 保持 DAO 的排序），
     * 这样「保留前面的、合并后面的」天然等价于「保大弃小」。
     *
     * 合并桶的名字是 **「其他 N 类」**，不是规范原文的「其他」——这是刻意的：
     * 支出分类里本来就有一个叫「其他支出」的，两者在同一个图例里并排时，
     * 「其他」会被误读成它。加个数量后缀既消除歧义，又补上了「合并掉了几个」这个信息。
     *
     * @return 合并后的列表；无项可并时**原样返回**（不构造多余的空桶）。
     */
    fun mergeSmallShares(
        shares: List<CategoryShare>,
        minFraction: Double = MIN_SLICE_FRACTION,
        maxSlices: Int = TAPE_PALETTE_SIZE,
    ): List<CategoryShare> {
        if (shares.size <= 1) return shares

        val keep = ArrayList<CategoryShare>(shares.size)
        val merged = ArrayList<CategoryShare>(4)
        for (share in shares) {
            if (share.fraction >= minFraction && keep.size < maxSlices) keep += share else merged += share
        }
        if (merged.isEmpty()) return shares
        if (keep.isEmpty()) return shares  // 极端数据（全是极小项）时不做无意义的合并

        val mergedTotal = merged.sumOf { it.total.total }
        val mergedCount = merged.sumOf { it.total.count }
        val grand = keep.sumOf { it.total.total } + mergedTotal
        val bucket = CategoryTotal(
            // 哨兵 id：负数不可能与真实分类冲突，点击也定位不到任何分类
            categoryId = -1L,
            name = "其他 ${merged.size} 类",
            // 兜底 43 = tag，与数据库迁移的兜底图标一致
            iconId = 43,
            // 跨多个分区聚合 → 没有单一归属，故分区字段全为 null
            sectionId = null,
            sectionName = null,
            sectionIconId = null,
            sectionColorIndex = null,
            total = mergedTotal,
            count = mergedCount,
        )
        return keep + CategoryShare(bucket, mergedTotal.toDouble() / grand, isMerged = true)
    }

    fun toLocalDate(epochMillis: Long, zone: ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}

data class CategoryShare(
    val total: CategoryTotal,
    /** 0.0 ~ 1.0 */
    val fraction: Double,
    /**
     * 是否为「其他 N 类」合并桶（见 [StatsCalculator.mergeSmallShares]）。
     *
     * 图表与图例据此分配颜色 —— 合并桶固定中性墨灰，不参与色板轮转。
     * 默认 false，使既有构造点与单测无需改动。
     */
    val isMerged: Boolean = false,
)
