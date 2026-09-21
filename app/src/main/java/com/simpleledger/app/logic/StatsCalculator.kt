package com.simpleledger.app.logic

import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionTotal
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

    /** 分类金额（用于横向条形图），总额为 0 时返回空 */
    fun categoryShares(totals: List<CategoryTotal>): List<CategoryShare> {
        val grand = totals.sumOf { it.total }
        if (grand <= 0) return emptyList()
        return totals.map { CategoryShare(it, it.total.toDouble() / grand) }
    }

    /**
     * 分区占比（用于分区占比环图，规范 §2.5 v2.1）。
     *
     * 两条口径：
     * - **只按支出聚合**：这张环图回答的是「钱花在哪个分区」，收入不计入；
     * - **支出为 0 的分区不进环**：一个 0 元的分区是几何上不存在的扇区，
     *   画出来只会得到一条发丝。它仍会出现在图例里吗？也不会——图例与环图
     *   必须是同一份数据（否则「图例里有个分区、环上却找不到」）。
     *   「这个月一分没花的分区」这个事实由分区首屏的卡片承载，不在统计页。
     *
     * 输入保持 DAO 的分区排序（`sortOrder`），环上扇区按用户自己的排布出现，
     * 而不是按金额大小重排 —— 分区顺序是用户亲手定的，不该被金额悄悄打乱。
     *
     * @return 支出合计为 0 时返回空列表（环图画成整圈灰环，由调用方判空态）。
     */
    fun sectionShares(sections: List<SectionTotal>): List<SectionShare> {
        val grand = sections.sumOf { it.expense }
        if (grand <= 0) return emptyList()
        return sections
            .filter { it.expense > 0 }
            .map { SectionShare(it, it.expense.toDouble() / grand) }
    }

    /**
     * 双图联动（规范 §2.5 G4）：点环图某分区后，条形图只保留该分区内的分类。
     *
     * 分类是否属于某分区，看的是**分类自身的归属**（`CategoryTotal.sectionId`），
     * 而不是账目的分区 —— 因为这张图的每个分类金额是**按分类 id 跨账目聚合**的，
     * 同一个全局分类（如「餐饮」）的账目可能散在多个分区里。按「分类归属」过滤
     * 与条形图的着色规则（颜色＝分类所属分区的胶带色）是同一条口径，不会出现
     * 「条的颜色是 A 分区、却被归进了 B 分区」的自相矛盾。
     *
     * @param sectionId null = 不筛选（全部分类）。
     */
    fun filterBySection(
        shares: List<CategoryShare>,
        sectionId: Long?,
    ): List<CategoryShare> {
        if (sectionId == null) return shares
        return shares.filter { it.total.sectionId == sectionId }
    }

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

    fun toLocalDate(epochMillis: Long, zone: ZoneId): LocalDate =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}

data class CategoryShare(
    val total: CategoryTotal,
    /** 0.0 ~ 1.0 */
    val fraction: Double,
)

/**
 * 分区占比环图的一个扇区。
 *
 * 与 [CategoryShare] 分开成两个类型，是因为两者的「身份维度」不同：
 * [CategoryShare] 的身份是**分类**（可多于色板容量、需要消歧），
 * [SectionShare] 的身份是**分区**（用户建的一级对象，数量天然 ≤ 色板容量）。
 * 合成一个泛型类型会让调用方各自写一堆 if 来区分。
 */
data class SectionShare(
    val section: SectionTotal,
    /** 0.0 ~ 1.0（分母 = 全部分区的支出合计） */
    val fraction: Double,
)
