package com.simpleledger.app.logic

import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType

/**
 * 「明细页长按 → 移动到其它分区」时，判断**当前分类**在目标分区是否仍合法。
 *
 * 背景（QA P1-1 / PRD EC-06）：账目的「分区 = X、分类只属于 Y」是非法组合。
 * 移动分区时若当前分类是**其它分区**的专属分类，必须为目标分区重选一个同类型分类，
 * 否则会静默制造出跨分区的非法归属。全局分类（`sectionId == null`）在任何分区都可见，
 * 可以随账目一起平移。
 *
 * 本对象是**纯函数**（无 Android 依赖），可在 JVM 单测里锁住「直接允许 / 需要重选 /
 * 无候选」三种判定，避免把这类规则写进 Composable 而无法回归。
 */
object SectionMoveRules {

    /** 兜底分类名，与初始示例数据、删除兜底口径保持一致 */
    const val FALLBACK_EXPENSE_NAME = "其他支出"
    const val FALLBACK_INCOME_NAME = "其他收入"

    /**
     * 当前分类能否**直接随**账目移入目标分区：
     * - 无分类实体（`null`，历史 / 悬空）：不阻断，原样保留；
     * - 全局分类（`sectionId == null`）：任何分区都可见 → 允许；
     * - 专属分类：仅当它本就属于目标分区时才允许。
     */
    fun canKeepCategory(current: CategoryEntity?, targetSectionId: Long): Boolean {
        if (current == null) return true
        if (current.sectionId == null) return true
        return current.sectionId == targetSectionId
    }

    /** 是否需要在对话框内追加一步「为目标分区重选分类」 */
    fun needsReselect(current: CategoryEntity?, targetSectionId: Long): Boolean =
        !canKeepCategory(current, targetSectionId)

    /**
     * 目标分区的预选兜底分类：优先「其他支出」/「其他收入」，
     * 找不到取同类型候选的第一项，候选为空返回 `null`（此时应禁止确认）。
     *
     * [candidates] 传入目标分区的候选集合（`observeCandidates(目标分区, type)` 的结果）；
     * 本函数内部按 [type] 再过滤一次，故传入「全类型」集合也安全。
     */
    fun pickFallback(candidates: List<CategoryEntity>, type: Int): CategoryEntity? {
        val sameType = candidates.filter { it.type == type }
        val preferred = if (type == EntryType.INCOME) FALLBACK_INCOME_NAME else FALLBACK_EXPENSE_NAME
        return sameType.firstOrNull { it.name == preferred } ?: sameType.firstOrNull()
    }
}
