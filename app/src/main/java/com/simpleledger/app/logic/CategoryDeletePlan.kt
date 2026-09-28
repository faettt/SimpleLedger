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
 * AU-3 分区合法性收窄：账目的「分区 = X、分类只属于 Y」是非法组合（QA P1-1 /
 * EC-06，[SectionMoveRules] 只在移分区路径强制执行，删除迁移路径此前漏了）。
 * [affectedSectionIds] 传入受影响账目所在分区的全集后，候选只保留
 * **全局分类**（任何分区都可见）与「受影响账目**全部**落在其归属分区」的专属分类
 * ——一个去向要服务所有受影响账目，只要有一笔账目不在其归属分区就是非法迁移。
 * null（该分类下无账目，迁移不会发生）= 不过滤，保持原全量口径。
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
     * @param affectedSectionIds 受影响账目所在分区的全集；null = 无受影响账目
     *   （迁移不会发生），不过滤（AU-3，见类注释）
     */
    fun plan(
        sameTypeAll: List<CategoryEntity>,
        targetId: Long,
        affectedSectionIds: Set<Long>? = null,
    ): Plan {
        // AU-3 分区合法性收窄：全局恒合法；专属分类必须让**每一笔**受影响账目
        // 都落在它的归属分区（emptySet 按不过滤处理，双保险）。
        val scoped = if (affectedSectionIds.isNullOrEmpty()) {
            sameTypeAll
        } else {
            sameTypeAll.filter { candidate ->
                candidate.sectionId == null || affectedSectionIds.all { it == candidate.sectionId }
            }
        }
        val others = scoped
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
