package com.simpleledger.app.logic

import com.simpleledger.app.data.local.entity.SectionEntity

/**
 * 分区「编辑保存」的行合并纯逻辑（AU-5，无 Android / Room 依赖，JVM 可测）。
 *
 * 缺陷背景：编辑分区的调用方（SectionHomeViewModel / SectionManageViewModel）构造
 * [SectionEntity] 时只填表单内字段，`sortOrder` 恒为默认值 0（对照：`colorIndex`
 * 两处都特意回填 `existing?.colorIndex`，说明「表单外字段保现值」是有意设计，
 * 唯 `sortOrder` 漏了）。仓库编辑分支走 `sectionDao.update`（Room `@Update` 全列
 * 覆写），于是**每一次改名 / 改预算都会把该分区的排序静默归 0**：
 * - 分区首屏按 `ORDER BY sortOrder, id` 排序，被编辑的分区直接跳到最前；
 * - 该 UPSERT 载荷携带 `sortOrder = 0` 经操作日志同步（LWW 应用），把错误排序
 *   传染给其余全部设备。
 *
 * 修法：以库内现值为基合并，`sortOrder` / `createdAt` 一律保现值（与 `saveCategory`
 * 编辑分支「保序」同款处理）；`versionSeq = baseSeq + 1`、`updatedAt = now` 交由
 * 调用方（LedgerRepository.saveSection）在同事务内落库与埋点。
 */
object SectionUpsertPlan {

    /**
     * 编辑分支的行合并。[cur] 为库内现值（null = 行不存在，如远端尚未合并——
     * 全部字段以 incoming 为准，行为与旧实现一致）。
     */
    fun editRow(incoming: SectionEntity, cur: SectionEntity?, syncId: String, now: Long): SectionEntity =
        incoming.copy(
            syncId = syncId,
            versionSeq = (cur?.versionSeq ?: incoming.versionSeq) + 1,
            // AU-5：保序核心行——排序是用户手动排好的展示状态，不是表单字段
            sortOrder = cur?.sortOrder ?: incoming.sortOrder,
            createdAt = cur?.createdAt ?: incoming.createdAt,
            updatedAt = now,
        )
}
