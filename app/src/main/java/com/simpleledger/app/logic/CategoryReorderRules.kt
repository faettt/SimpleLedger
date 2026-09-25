package com.simpleledger.app.logic

import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.entity.CategoryEntity

/**
 * 分类手动排序的「未分类」哨兵规则（与 [CategoryDeletePlan] 的
 * 「未分类恒排末尾（兜底语义）」同一口径，F-3，2026-09-25）。
 *
 * 「未分类」哨兵是系统兜底行，**不参与用户手动排序**：
 * - 排序重写时哨兵行整行忽略——位次不动、不 bump versionSeq、不记 UPSERT 埋点
 *   （否则同步端会把哨兵当作用户改动行，且与恒排末尾语义自相矛盾）；
 * - 普通行的位次 = 它在传入序列中的下标（哨兵占位不挤压普通行）。
 */
object CategoryReorderRules {

    /**
     * 计算排序重写计划（纯函数，JVM 可测）：传入 UI 侧的完整序列（可能含哨兵），
     * 返回「(分类 id → 新 sortOrder)」对——哨兵行被剔除，普通行保持传入相对顺序。
     */
    fun rewritePlan(ordered: List<CategoryEntity>): List<Pair<Long, Int>> =
        ordered.mapIndexedNotNull { index, category ->
            if (isReorderable(category)) category.id to index else null
        }

    /** 该分类是否允许参与手动排序：哨兵恒 false（UI 排序按钮 enable 与仓库拦截共用此口径） */
    fun isReorderable(category: CategoryEntity): Boolean =
        !SectionFirstSeed.Unclassified.isUnclassified(category)
}
