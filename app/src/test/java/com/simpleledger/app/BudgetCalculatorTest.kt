package com.simpleledger.app

import com.simpleledger.app.logic.BudgetCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分区预算纯函数单测。
 *
 * 重点把边界钉死：尤其是「未设预算」的除零保护，以及「超支比例夹到 1.0」。
 * 这些数值过去散落在 Compose 组合函数里，肉眼很难判断对错。
 */
class BudgetCalculatorTest {

    private val eps = 1e-6f

    // ---------- budgetRatio ----------

    @Test
    fun `budget ratio with budget zero returns 0 - div by zero guard`() {
        // 未设预算（budget <= 0）时绝不允许除零，返回 0f
        assertEquals(0f, BudgetCalculator.budgetRatio(expenseCents = 12345L, budgetCents = 0L), eps)
        assertEquals(0f, BudgetCalculator.budgetRatio(expenseCents = 12345L, budgetCents = -1L), eps)
        assertEquals(0f, BudgetCalculator.budgetRatio(expenseCents = 0L, budgetCents = 0L), eps)
    }

    @Test
    fun `budget ratio with zero expense returns 0`() {
        assertEquals(0f, BudgetCalculator.budgetRatio(expenseCents = 0L, budgetCents = 500000L), eps)
    }

    @Test
    fun `budget ratio normal fraction`() {
        // 花掉 2500 元 / 预算 5000 元 = 0.5
        assertEquals(0.5f, BudgetCalculator.budgetRatio(expenseCents = 250000L, budgetCents = 500000L), eps)
    }

    @Test
    fun `budget ratio exact full is 1`() {
        // 正好用满：比例应为 1.0，而不是超支
        assertEquals(1f, BudgetCalculator.budgetRatio(expenseCents = 500000L, budgetCents = 500000L), eps)
    }

    @Test
    fun `budget ratio overspent is clamped to 1`() {
        // 超支：比例夹到 1.0（进度条不应溢出）
        assertEquals(1f, BudgetCalculator.budgetRatio(expenseCents = 600000L, budgetCents = 500000L), eps)
        assertEquals(1f, BudgetCalculator.budgetRatio(expenseCents = 9_999_999L, budgetCents = 500000L), eps)
    }

    // ---------- isOverspent ----------

    @Test
    fun `is overspent only when strictly above budget`() {
        assertFalse(BudgetCalculator.isOverspent(expenseCents = 400000L, budgetCents = 500000L))
        // 正好用满不算超支
        assertFalse(BudgetCalculator.isOverspent(expenseCents = 500000L, budgetCents = 500000L))
        assertTrue(BudgetCalculator.isOverspent(expenseCents = 500001L, budgetCents = 500000L))
    }

    @Test
    fun `is overspent false when no budget`() {
        // 未设预算时无论花多少都不算超支
        assertFalse(BudgetCalculator.isOverspent(expenseCents = 999999L, budgetCents = 0L))
        assertFalse(BudgetCalculator.isOverspent(expenseCents = 999999L, budgetCents = -5L))
    }

    // ---------- isNearLimit ----------

    @Test
    fun `is near limit at or above 90 percent`() {
        // 90% 整：算接近上限
        assertTrue(BudgetCalculator.isNearLimit(expenseCents = 450000L, budgetCents = 500000L))
        // 89%：不算
        assertFalse(BudgetCalculator.isNearLimit(expenseCents = 445000L, budgetCents = 500000L))
        // 95%：算
        assertTrue(BudgetCalculator.isNearLimit(expenseCents = 475000L, budgetCents = 500000L))
    }

    @Test
    fun `is near limit false when overspent`() {
        // 已经超支时不再报「接近上限」（两个状态互斥，避免同时点亮）
        assertFalse(BudgetCalculator.isNearLimit(expenseCents = 600000L, budgetCents = 500000L))
        // 正好用满：未超支且比例=1.0 >= 0.9，算接近上限
        assertTrue(BudgetCalculator.isNearLimit(expenseCents = 500000L, budgetCents = 500000L))
    }

    @Test
    fun `is near limit false when no budget`() {
        assertFalse(BudgetCalculator.isNearLimit(expenseCents = 999999L, budgetCents = 0L))
    }
}