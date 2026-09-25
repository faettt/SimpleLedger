package com.simpleledger.app.logic

/**
 * 回收站恢复的「原分区已死」兜底纯逻辑（A1 整包恢复 / 单笔恢复，JVM 可测）。
 *
 * 定案：单笔恢复时若原分区已被删除 → 迁到**存活分区中排序最前**（sortOrder, id 最小）
 * 的一个 + snackbar 告知；整包恢复时分区与账目一起回来，无此问题。
 *
 * 已知边界：库里一个分区都不剩时无处安放 → 返回 null，调用方沿用远端回放的
 * 死引用口径（sectionId = 0 占位），不伪造分区。
 */
object RestoreFallbacks {

    /** 参与排序的最小分区信息（解耦 Entity，纯逻辑可测） */
    data class SectionRef(val id: Long, val sortOrder: Int)

    /**
     * 解析恢复目标分区。
     *
     * @param originalSectionId 原分区 id
     * @param originalAlive 原分区是否仍存活
     * @param sections 当前全部存活分区
     * @return 原分区存活 → 原 id；否则存活分区里 (sortOrder, id) 最小者；无分区 → null
     */
    fun fallbackSectionId(
        originalSectionId: Long,
        originalAlive: Boolean,
        sections: List<SectionRef>,
    ): Long? {
        if (originalAlive) return originalSectionId
        return sections.minWithOrNull(compareBy({ it.sortOrder }, { it.id }))?.id
    }

    /** 是否需要「改换分区」提示（原分区已死） */
    fun needsRehome(originalAlive: Boolean): Boolean = !originalAlive
}
