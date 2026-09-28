package com.simpleledger.app.logic

import java.time.LocalDate
import java.time.YearMonth

/**
 * 分区详情「默认全部时间 + 月份筛选」的**纯判定**集合（无 Android 依赖，JVM 可测）。
 *
 * 模式语义（任务书裁定原文）：
 * - **默认 = 全部时间**（`monthFilter == null`）：进入分区详情先看该分区自始至今的全部账目，
 *   与首屏「本月」卡片的口径差由汇总行的「累计支出 / 累计收入」标签区分；
 * - 月筛选（`monthFilter != null`）：只看该自然月。
 *
 * 时间窗换算（null → 全量窗口 / 月 → monthRange）留在
 * `SectionDetailViewModel`（依赖 DateTimes 与仓库签名），此处只收不涉 IO 的判定。
 */
object SectionDetailFilterPlan {

    /** 列表区按哪个分支渲染（空态两分支 + 正常内容，共三分支） */
    enum class SectionDetailBranch {
        /** 分区从无账目：引导「记一笔」 */
        NO_ENTRY_EVER,

        /** 分区有账目、当前时间窗为空（只在月筛选下出现）：引导「看全部时间」清筛选 */
        MONTH_EMPTY,

        /** 有内容：渲染分组列表 */
        CONTENT,
    }

    /**
     * 空态分支判定。`!hasAnyEntry` 必须最先判——分区从未记过账时，无论筛什么月，
     * 正确引导都是「记一笔」而非「看全部时间」。
     *
     * [monthFilter] 传 `null`（全部模式）时永不返回 [SectionDetailBranch.MONTH_EMPTY]：
     * 全部模式下「窗口空」要么意味着从未记账（hasAnyEntry 亦为 false），
     * 要么是换流瞬态里 hasAnyEntry 尚未跟上（此时返回 CONTENT 短暂空白，
     * 由下一次流发射纠正，好过误报「筛选月无账目」把用户引去清一个不存在的筛选）。
     */
    fun emptyBranch(
        hasAnyEntry: Boolean,
        windowEmpty: Boolean,
        monthFilter: YearMonth?,
    ): SectionDetailBranch = when {
        !hasAnyEntry -> SectionDetailBranch.NO_ENTRY_EVER
        windowEmpty && monthFilter != null -> SectionDetailBranch.MONTH_EMPTY
        else -> SectionDetailBranch.CONTENT
    }

    /**
     * 「按月筛选」的落点月份：优先**最新账目所在月**（列表已按时间倒序，首组即最新），
     * 让用户点进去看到的是「最近在记的那个月」而不是一个空白月；
     * 尚无账目（首帧前点击 / 空分区）回退 [today] 当月——可能落无账月，
     * 由空态「看全部时间」一键自愈兜底。
     */
    fun enterMonthFilter(latestGroupDate: LocalDate?, today: LocalDate): YearMonth =
        latestGroupDate?.let { YearMonth.from(it) } ?: YearMonth.from(today)

    /**
     * 月份步进：[delta] = +1 下月 / -1 上月。`YearMonth.plusMonths` 天然跨年环绕
     * （2026年1月 ‹ → 2025年12月，2025年12月 › → 2026年1月），无需手工借位。
     */
    fun stepMonth(month: YearMonth, delta: Int): YearMonth = month.plusMonths(delta.toLong())

    /**
     * 全部模式的**月份分隔头**判定：本组与上一组不同月（或本组是首组）时，
     * 在本组上方插一个月头，返回该月；同月返回 `null`（不插头，避免每月重复刷屏）。
     *
     * 仅全部模式调用——月模式整表同月，筛选条上的月份标签即锚点，不再插头。
     * [prev] 传上一组的月份，首组传 `null`。
     */
    fun monthBoundary(prev: YearMonth?, next: YearMonth): YearMonth? =
        if (prev != next) next else null

    /**
     * 保存 / 复制 / 编辑成功后的跳月判定（「账目落哪月筛哪月」）。
     *
     * **裁定（第四轮 mustFix）：仅月筛选生效。**
     * - `current == null`（全部模式）→ `null`，**不跳**：默认「全部」是任务书裁定原文，
     *     无条件跳月会让每次记账 / 复制 / 保存都把视图从全部收窄进单月，最高频操作
     *     反复被打断，并与「离开页面复位全部」相抵。全部模式下常规记账（时间=此刻）
     *     按时间倒序天然置顶可见；补记往月落全量列表的历史正确位置（不隐藏不丢账）。
     * - `current == entryMonth` → `null`，空操作：同月保存不折腾列表位置。
     * - 异月 → [entryMonth]：月模式下账目落到哪个月就跟去哪个月
     *     （编辑改期跟去新月、复制落此刻当月），新账目必然可见。
     *
     * 跳月本身无新文案，snackbar 提示照旧。
     */
    fun landTarget(current: YearMonth?, entryMonth: YearMonth): YearMonth? = when {
        current == null -> null
        current == entryMonth -> null
        else -> entryMonth
    }
}
