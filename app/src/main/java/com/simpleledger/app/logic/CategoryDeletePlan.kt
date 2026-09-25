package com.simpleledger.app.logic

import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.entity.CategoryEntity

/**
 * 删除分类的「去向迁移」纯逻辑（B4，无 Android / Room 依赖，JVM 可测）。
 *
 * 定案（FR-41）：删除分类的确认弹窗升级为**去向单选**——候选 = 同类型其余分类 +
 * 「未分类」哨兵，默认预选「未分类」；该分类下的账目迁移到所选去向后分类才删除。
 * 取代旧的「同类型 sort-first 兜底链」。
 *
 * 展示序：其余分类按 (sortOrder, id) 升序，「未分类」恒排**末尾**（兜底语义，
 * 不与用户自建分类抢视线）。
 */
object CategoryDeletePlan {

    /** 去向候选 + 默认预选 */
    data class Plan(
        val destinations: List<CategoryEntity>,
        /** 默认去向 = 「未分类」哨兵（若在候选中），否则首个候选 */
        val defaultDestinationId: Long?,
    )

    /**
     * 计算删除 [targetId] 的去向候选。
     *
     * @param sameTypeAll 同类型全部分类（含目标自身与「未分类」哨兵），任意顺序
     * @param targetId 被删分类 id
     */
    fun plan(sameTypeAll: List<CategoryEntity>, targetId: Long): Plan {
        val others = sameTypeAll
            .filter { it.id != targetId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val (sentinel, normal) = others.partition { SectionFirstSeed.Unclassified.isUnclassified(it) }
        val destinations = normal + sentinel
        val defaultId = sentinel.firstOrNull()?.id ?: destinations.firstOrNull()?.id
        return Plan(destinations = destinations, defaultDestinationId = defaultId)
    }

    /**
     * 解析用户选择的去向：null / 无效 id → 默认去向；必须落在 [destinations] 内
     * （防越权迁移到目标自身或跨类型分类）。全部无效返回 null = 不可执行删除。
     */
    fun resolveDestinationId(destinations: List<CategoryEntity>, requestedId: Long?): Long? {
        if (requestedId != null && destinations.any { it.id == requestedId }) return requestedId
        val sentinel = destinations.firstOrNull { SectionFirstSeed.Unclassified.isUnclassified(it) }
        return sentinel?.id ?: destinations.firstOrNull()?.id
    }
}
