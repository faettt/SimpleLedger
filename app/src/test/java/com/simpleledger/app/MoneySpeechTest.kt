package com.simpleledger.app

import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.logic.CategoryShare
import com.simpleledger.app.ui.amountSpeech
import com.simpleledger.app.ui.components.barChartSpeech
import com.simpleledger.app.ui.components.pieChartSpeech
import com.simpleledger.app.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读屏中文金额与图表摘要的纯函数单测。
 *
 * 重点锁「零」的处理：连续零只读一个「零」、组末尾零一律省略、组间空缺补一个「零」——
 * 这三条是最容易写错、也最难靠肉眼在实机上发现的地方（读屏念错一个字用户未必立刻察觉）。
 */
class MoneySpeechTest {

    // ---------------------------------------------------------------- toChineseSpeech：整数元

    @Test
    fun `whole yuan reads yuan-zheng`() {
        assertEquals("两千元整", Money.toChineseSpeech(200_000L))
        assertEquals("一百元整", Money.toChineseSpeech(100_00L))
        assertEquals("一万二千元整", Money.toChineseSpeech(1_200_000L))
    }

    @Test
    fun `ten to nineteen drops leading yi`() {
        // 口语是「十五」而非「一十五」
        assertEquals("十五元整", Money.toChineseSpeech(1_500L))
        assertEquals("十元整", Money.toChineseSpeech(1_000L))
    }

    @Test
    fun `leading two before qian-wan reads liang`() {
        // 2000 → 两千（不是二千）；但 2000 万、2000 亿同理
        assertEquals("两千元整", Money.toChineseSpeech(200_000L))
        assertEquals("二十元整", Money.toChineseSpeech(2_000L)) // 20 仍读「二十」
    }

    // ---------------------------------------------------------------- 「零」的三种情况

    @Test
    fun `trailing zero inside group is dropped`() {
        // 1050 → 一千零五十（不是「一千零五十零」）
        assertEquals("一千零五十元整", Money.toChineseSpeech(105_000L))
        // 900 → 九百（没有尾零）
        assertEquals("九百元整", Money.toChineseSpeech(90_000L))
    }

    @Test
    fun `gap between groups inserts single zero`() {
        // 10005 → 一万零五（万组后是个位组，缺 4 位 → 补一个「零」）
        assertEquals("一万零五元整", Money.toChineseSpeech(1_000_500L))
        // 100000005 → 一亿零五（万组整组为空 → 仍只补一个「零」，不出现「零零」）
        assertEquals("一亿零五元整", Money.toChineseSpeech(10_000_000_500L))
    }

    @Test
    fun `consecutive zeros collapse to one`() {
        // 10008 → 一万零八（三个连续 0 只读一个「零」）
        assertEquals("一万零八元整", Money.toChineseSpeech(1_000_800L))
    }

    // ---------------------------------------------------------------- 角 / 分

    @Test
    fun `jiao without fen`() {
        assertEquals("一百元五角", Money.toChineseSpeech(100_50L))
        assertEquals("五角", Money.toChineseSpeech(50L))
    }

    @Test
    fun `yuan zero and fen only`() {
        assertEquals("五分", Money.toChineseSpeech(5L))
    }

    @Test
    fun `yuan and jiao and fen`() {
        assertEquals("三角五分", Money.toChineseSpeech(35L))
        assertEquals("一百元五角五分", Money.toChineseSpeech(100_55L))
    }

    @Test
    fun `yuan with empty jiao but fen inserts zero`() {
        // 20.05 → 二十元零五分：角为 0 而分非 0，中间必须补「零」
        assertEquals("二十元零五分", Money.toChineseSpeech(2_005L))
    }

    // ---------------------------------------------------------------- 边界

    @Test
    fun `zero reads zero yuan zheng`() {
        assertEquals("零元整", Money.toChineseSpeech(0L))
    }

    @Test
    fun `supports yi scale`() {
        // parseToCents 上限 99_999_999_999 分；取整亿值验证读法到「亿」
        assertEquals("一亿元整", Money.toChineseSpeech(10_000_000_000L))
    }

    // ---------------------------------------------------------------- 方向 + 隐私（关键）

    @Test
    fun `amount speech carries direction`() {
        assertEquals("支出两千元整", amountSpeech(isIncome = false, cents = 200_000L, hidden = false))
        assertEquals("收入一万二千元整", amountSpeech(isIncome = true, cents = 1_200_000L, hidden = false))
    }

    @Test
    fun `hidden amounts never leak the real value`() {
        // 隐私模式：打码只挡住眼睛，读屏也绝不能念出真值
        val expense = amountSpeech(isIncome = false, cents = 200_000L, hidden = true)
        assertEquals("支出，金额已隐藏", expense)
        assertFalse(expense.contains("两千"))
        assertFalse(expense.contains("2"))

        val income = amountSpeech(isIncome = true, cents = 1_200_000L, hidden = true)
        assertEquals("收入，金额已隐藏", income)
        assertFalse(income.contains("一万"))
    }

    // ---------------------------------------------------------------- 图表摘要

    private fun share(name: String, cents: Long, fraction: Double) =
        CategoryShare(
            CategoryTotal(
                categoryId = 1L,
                name = name,
                emoji = "🍚",
                sectionId = null,
                sectionName = null,
                sectionEmoji = null,
                total = cents,
                count = 1,
            ),
            fraction,
        )

    @Test
    fun `pie chart speech lists items with percent and chinese amount`() {
        val shares = listOf(
            share("餐饮", 200_000L, 0.45),
            share("交通", 90_000L, 0.20),
        )
        assertEquals(
            "分类占比：共 2 类，总支出 四千四百五十元整。餐饮 45%，两千元整；交通 20%，九百元整",
            pieChartSpeech(shares, totalCents = 445_000L, hidden = false),
        )
    }

    @Test
    fun `pie chart speech folds the tail into a count`() {
        val shares = (1..7).map { share("类$it", 10_000L, 1.0 / 7) }
        val speech = pieChartSpeech(shares, totalCents = 70_000L, hidden = false, maxItems = 5)
        assertTrue(speech.contains("共 7 类"))
        assertTrue(speech.contains("等 2 类"))
    }

    @Test
    fun `pie chart speech hides amounts in privacy mode`() {
        val shares = listOf(share("餐饮", 200_000L, 1.0))
        val speech = pieChartSpeech(shares, totalCents = 200_000L, hidden = true)
        assertEquals("分类占比：共 1 类，金额已隐藏", speech)
        assertFalse(speech.contains("两千"))
    }

    @Test
    fun `empty pie chart speech`() {
        assertEquals("分类占比：暂无数据", pieChartSpeech(emptyList(), totalCents = 0L, hidden = false))
    }

    @Test
    fun `bar chart speech reports peak and total`() {
        val daily = listOf(8 to 40_000L, 12 to 120_000L)
        assertEquals(
            "每日支出：本月 30 天，最高 12 日 一千二百元整，合计 一千六百元整",
            barChartSpeech(daily, daysInMonth = 30, hidden = false),
        )
    }

    @Test
    fun `bar chart speech hides amounts in privacy mode`() {
        val daily = listOf(12 to 120_000L)
        val speech = barChartSpeech(daily, daysInMonth = 30, hidden = true)
        assertEquals("每日支出：本月 30 天，金额已隐藏", speech)
        assertFalse(speech.contains("一千二百"))
    }

    @Test
    fun `empty bar chart speech`() {
        assertEquals("每日支出：本月暂无支出", barChartSpeech(emptyList(), daysInMonth = 30, hidden = false))
    }
}