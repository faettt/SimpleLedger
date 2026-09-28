package com.simpleledger.app

import com.simpleledger.app.logic.SectionDetailFilterPlan
import com.simpleledger.app.logic.SectionDetailFilterPlan.SectionDetailBranch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 分区详情「默认全部时间 + 月份筛选」纯判定单测。
 *
 * 覆盖五组规则（与 [SectionDetailFilterPlan] 一一对应）：
 * 1. 空态三分支——分区无账目优先于筛选月空；全部模式永不报「筛选月无账目」；
 * 2. 「按月筛选」落点——最新账目月优先，无账目回退当月；
 * 3. 月份步进的跨年环绕；
 * 4. 全部模式月份分隔头的月/年边界（首组、同月、跨月、跨年）；
 * 5. **landTarget 三分支（裁定核心）**——全部模式不跳 / 同月空操作 / 异月跟月。
 */
class SectionDetailFilterPlanTest {

    // -------- 1. 空态三分支 --------

    @Test
    fun `no-entry-ever wins even with a month filter active`() {
        // 分区从未记过账：无论筛什么月，正确引导都是「记一笔」，不是「看全部时间」
        assertEquals(
            SectionDetailBranch.NO_ENTRY_EVER,
            SectionDetailFilterPlan.emptyBranch(
                hasAnyEntry = false,
                windowEmpty = true,
                monthFilter = YearMonth.of(2026, 9),
            ),
        )
    }

    @Test
    fun `filtered month empty when section has entries elsewhere`() {
        assertEquals(
            SectionDetailBranch.MONTH_EMPTY,
            SectionDetailFilterPlan.emptyBranch(
                hasAnyEntry = true,
                windowEmpty = true,
                monthFilter = YearMonth.of(2026, 9),
            ),
        )
    }

    @Test
    fun `content when window has entries`() {
        assertEquals(
            SectionDetailBranch.CONTENT,
            SectionDetailFilterPlan.emptyBranch(
                hasAnyEntry = true,
                windowEmpty = false,
                monthFilter = null,
            ),
        )
    }

    @Test
    fun `all mode never reports month empty`() {
        // 全部模式下「窗口空」要么是从未记账（hasAnyEntry 亦 false），
        // 要么是换流瞬态里 hasAnyEntry 尚未跟上——都不该报「筛选月无账目」
        assertEquals(
            SectionDetailBranch.CONTENT,
            SectionDetailFilterPlan.emptyBranch(
                hasAnyEntry = true,
                windowEmpty = true,
                monthFilter = null,
            ),
        )
    }

    // -------- 2. 「按月筛选」落点 / 回退 --------

    @Test
    fun `enterMonthFilter lands on the latest entry month`() {
        // 列表按时间倒序，首组 = 最新账目：2025-12-31 → 2025年12月
        assertEquals(
            YearMonth.of(2025, 12),
            SectionDetailFilterPlan.enterMonthFilter(
                latestGroupDate = LocalDate.of(2025, 12, 31),
                today = LocalDate.of(2026, 9, 28),
            ),
        )
    }

    @Test
    fun `enterMonthFilter falls back to today when there is no entry`() {
        // 首帧前点击 / 空分区：回退当月（可能落无账月，由空态「看全部时间」自愈）
        assertEquals(
            YearMonth.of(2026, 9),
            SectionDetailFilterPlan.enterMonthFilter(
                latestGroupDate = null,
                today = LocalDate.of(2026, 9, 28),
            ),
        )
    }

    // -------- 3. 月份步进跨年环绕 --------

    @Test
    fun `stepMonth wraps backwards across a year`() {
        assertEquals(
            YearMonth.of(2025, 12),
            SectionDetailFilterPlan.stepMonth(YearMonth.of(2026, 1), -1),
        )
    }

    @Test
    fun `stepMonth wraps forwards across a year`() {
        assertEquals(
            YearMonth.of(2026, 1),
            SectionDetailFilterPlan.stepMonth(YearMonth.of(2025, 12), 1),
        )
    }

    @Test
    fun `stepMonth moves within a year`() {
        assertEquals(
            YearMonth.of(2026, 10),
            SectionDetailFilterPlan.stepMonth(YearMonth.of(2026, 9), 1),
        )
        assertEquals(
            YearMonth.of(2026, 8),
            SectionDetailFilterPlan.stepMonth(YearMonth.of(2026, 9), -1),
        )
    }

    // -------- 4. 月份分隔头边界（仅全部模式） --------

    @Test
    fun `first group always gets a month header`() {
        assertEquals(
            YearMonth.of(2026, 9),
            SectionDetailFilterPlan.monthBoundary(prev = null, next = YearMonth.of(2026, 9)),
        )
    }

    @Test
    fun `same month as previous group has no header`() {
        assertNull(
            SectionDetailFilterPlan.monthBoundary(
                prev = YearMonth.of(2026, 9),
                next = YearMonth.of(2026, 9),
            ),
        )
    }

    @Test
    fun `crossing into a new month gets a header`() {
        assertEquals(
            YearMonth.of(2026, 9),
            SectionDetailFilterPlan.monthBoundary(
                prev = YearMonth.of(2026, 8),
                next = YearMonth.of(2026, 9),
            ),
        )
    }

    @Test
    fun `crossing into a new year gets a header`() {
        assertEquals(
            YearMonth.of(2026, 1),
            SectionDetailFilterPlan.monthBoundary(
                prev = YearMonth.of(2025, 12),
                next = YearMonth.of(2026, 1),
            ),
        )
    }

    // -------- 5. landTarget 三分支（裁定核心：仅月筛选生效） --------

    @Test
    fun `landTarget never jumps in all-time mode`() {
        // 全部模式（current = null）不跳：否则每次记账 / 复制 / 保存都会把视图
        // 从「全部」收窄进单月，最高频操作反复被打断（第四轮 mustFix 裁定）
        assertNull(
            SectionDetailFilterPlan.landTarget(
                current = null,
                entryMonth = YearMonth.of(2026, 9),
            ),
        )
    }

    @Test
    fun `landTarget is a no-op when entry stays in the filtered month`() {
        // 编辑同月保存：不折腾列表位置
        assertNull(
            SectionDetailFilterPlan.landTarget(
                current = YearMonth.of(2026, 9),
                entryMonth = YearMonth.of(2026, 9),
            ),
        )
    }

    @Test
    fun `landTarget follows the entry into a different month`() {
        // 编辑改期 / 复制落当月：月模式下账目落哪月就跟去哪月，新账目必然可见
        assertEquals(
            YearMonth.of(2026, 10),
            SectionDetailFilterPlan.landTarget(
                current = YearMonth.of(2026, 9),
                entryMonth = YearMonth.of(2026, 10),
            ),
        )
    }
}
