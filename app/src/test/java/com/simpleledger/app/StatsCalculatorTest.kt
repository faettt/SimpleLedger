package com.simpleledger.app

import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.logic.CategoryShare
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

    /* ------------------------------------- 分区占比环图 + 双图联动（§2.5 v2.1） */

    private fun section(id: Long, name: String, expense: Long, colorIndex: Int = 0) =
        com.simpleledger.app.data.local.entity.SectionTotal(
            sectionId = id, name = name, iconId = 1, colorIndex = colorIndex,
            note = "", budgetCents = 0, expense = expense, income = 0, count = 1,
        )

    private fun cat(id: Long, name: String, total: Long, sectionId: Long?) =
        CategoryShare(
            total = CategoryTotal(
                categoryId = id, name = name, iconId = 2,
                sectionId = sectionId, sectionName = "分区$sectionId", sectionIconId = 1,
                sectionColorIndex = 0, total = total, count = 1,
            ),
            fraction = 0.0,
        )

    @Test
    fun `section shares skip zero expense sections and keep DAO order`() {
        val sections = listOf(
            section(1, "日常开支", 4578_50L),
            section(2, "装修", 92068_00L),
            section(3, "旅行", 0L),          // 本月一分没花 → 不进环
        )
        val shares = StatsCalculator.sectionShares(sections)

        assertEquals(2, shares.size)
        // 保持 DAO 的分区排序（sortOrder），环上按用户自己的排布出现，不被金额打乱
        assertEquals(listOf("日常开支", "装修"), shares.map { it.section.name })
        val grand = (4578_50L + 92068_00L).toDouble()
        assertEquals(4578_50L / grand, shares[0].fraction, 1e-9)
        assertEquals(92068_00L / grand, shares[1].fraction, 1e-9)
    }

    @Test
    fun `section shares return empty when nothing was spent`() {
        val allZero = listOf(section(1, "日常开支", 0L), section(2, "装修", 0L))
        assertEquals(0, StatsCalculator.sectionShares(allZero).size)
        assertEquals(0, StatsCalculator.sectionShares(emptyList()).size)
    }

    @Test
    fun `filter by section keeps only that section's categories`() {
        // 同名分类可以跨分区存在（PRD q-02），所以按「分类自身归属」过滤：
        // 与条形图的着色规则（颜色＝分类所属分区的胶带色）是同一条口径。
        val shares = listOf(
            cat(13, "主材", 30180L, sectionId = 2L),   // 装修·主材
            cat(1, "餐饮", 291L, sectionId = 1L),      // 日常开支·餐饮
            cat(14, "人工", 22100L, sectionId = 2L),   // 装修·人工
        )
        val onlyDecor = StatsCalculator.filterBySection(shares, sectionId = 2L)

        assertEquals(listOf("主材", "人工"), onlyDecor.map { it.total.name })
        // null = 不筛选，原样返回（不复制、不重排）
        assertEquals(shares, StatsCalculator.filterBySection(shares, sectionId = null))
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

    /* ------------------------------------- 按人维度（U-7/R-18，T-5 扩展） */

    /** 构造带成员关系的账目（EntryFull 的 member 关系 = R-20 改名即时生效的数据源） */
    private fun fullEntry(
        type: Int,
        cents: Long,
        memberId: String?,
        memberName: String?,
    ) = com.simpleledger.app.data.local.entity.EntryFull(
        entry = entry(type, cents, 0L).copy(memberId = memberId),
        category = null,
        section = null,
        images = emptyList(),
        member = memberName?.let { name ->
            com.simpleledger.app.data.local.entity.MemberEntity(
                syncId = memberId ?: "",
                name = name,
                createdAt = 0L,
                updatedAt = 0L,
            )
        },
    )

    @Test
    fun `member shares sum equals total expense and ignore income`() {
        val entries = listOf(
            fullEntry(EntryType.EXPENSE, 3000L, "m1", "阿明"),
            fullEntry(EntryType.EXPENSE, 1000L, "m1", "阿明"),
            fullEntry(EntryType.EXPENSE, 2000L, "m2", "小林"),
            fullEntry(EntryType.EXPENSE, 500L, null, null),    // 记账人不可考 → 未知成员桶
            fullEntry(EntryType.INCOME, 9999L, "m1", "阿明"),   // 收入不计入（口径同分区占比）
        )
        val shares = StatsCalculator.memberShares(entries)

        // 验收断言：各成员合计 = 全账支出合计（不含收入）
        assertEquals(6500L, shares.sumOf { it.amountCents })
        // 金额降序；成员名经 EntryFull.member 关系取（R-20 改名即时生效）
        assertEquals(listOf("阿明", "小林", "未知成员"), shares.map { it.name })
        assertEquals(listOf(4000L, 2000L, 500L), shares.map { it.amountCents })
        assertEquals(listOf(2, 1, 1), shares.map { it.count })
        assertEquals("m1", shares[0].memberSyncId)
        assertEquals(null, shares[2].memberSyncId)
        // fraction 合计 = 1.0
        assertEquals(1.0, shares.sumOf { it.fraction }, 1e-9)
    }

    @Test
    fun `member shares return empty when nothing was spent`() {
        assertEquals(0, StatsCalculator.memberShares(emptyList()).size)
        // 只有收入 → 支出合计 0 → 空
        assertEquals(
            0,
            StatsCalculator.memberShares(listOf(fullEntry(EntryType.INCOME, 100L, "m1", "阿明"))).size,
        )
    }

    @Test
    fun `member shares unknown label is customizable`() {
        val shares = StatsCalculator.memberShares(
            listOf(fullEntry(EntryType.EXPENSE, 100L, null, null)),
            unknownLabel = "未知",
        )
        assertEquals(listOf("未知"), shares.map { it.name })
    }
}
