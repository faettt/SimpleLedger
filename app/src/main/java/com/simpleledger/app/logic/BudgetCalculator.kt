package com.simpleledger.app.logic

/**
 * 分区预算的纯计算逻辑。
 *
 * 从 [com.simpleledger.app.ui.stats.StatsScreen] 的 `SectionBudgetCard` 中抽出——
 * 这些判断过去散落在 Compose 组合函数里，既没法单测，也容易在改动时悄悄走样。
 * 抽成纯函数后可以用 JVM 单测把边界钉死（尤其是「未设预算」的除零保护）。
 *
 * 约定：
 * - 预算 `budgetCents <= 0` 一律视为「未设预算」，所有比例/状态都按无预算处理，避免除零。
 * - 金额单位统一为「分」(Long)，与数据库保持一致。
 */
object BudgetCalculator {

    /** 接近上限的阈值：用量达到 90% 即视为「接近超支」 */
    private const val NEAR_LIMIT_THRESHOLD = 0.9f

    /**
     * 预算使用比例，夹到 `[0f, 1f]`。
     *
     * @param expenseCents 已支出金额（分）
     * @param budgetCents  预算金额（分）；`<= 0` 表示未设预算
     * @return 使用比例；未设预算时返回 `0f`（并因此天然规避了除零）
     */
    fun budgetRatio(expenseCents: Long, budgetCents: Long): Float =
        if (budgetCents > 0) {
            (expenseCents.toFloat() / budgetCents).coerceIn(0f, 1f)
        } else {
            0f
        }

    /**
     * 是否超支：设了预算且支出严格大于预算。
     *
     * 注意「正好用满」（支出 == 预算）不算超支。
     */
    fun isOverspent(expenseCents: Long, budgetCents: Long): Boolean =
        budgetCents > 0 && expenseCents > budgetCents

    /**
     * 是否接近上限：设了预算、尚未超支，且用量已达 90%。
     *
     * 用比例判定而非重复做除法，保证与 [budgetRatio] 口径一致。
     */
    fun isNearLimit(expenseCents: Long, budgetCents: Long): Boolean =
        budgetCents > 0 && !isOverspent(expenseCents, budgetCents) &&
            budgetRatio(expenseCents, budgetCents) >= NEAR_LIMIT_THRESHOLD
}