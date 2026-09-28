package com.simpleledger.app.data.repo

import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.dao.CategoryDao
import com.simpleledger.app.data.local.dao.SectionDao
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.RowKind
import java.util.UUID

/**
 * v5 埋点辅助（U-11 拆分自 LedgerRepository，行为零变化）：快照拼装 + [OpRecorder] 追加。
 *
 * 埋点铁律（§3.7/§7-9）不变：这些函数**必须**在调用方的 `db.withTransaction` 事务内
 * 执行——行写与操作追加同事务的契约由调用方（LedgerRepository / [LedgerDeleteRestore]）
 * 保证，本类不做任何事务、不做任何表写。AppContainer 不感知本类（由 LedgerRepository 自行构造）。
 */
internal class LedgerOpRecording(
    private val sectionDao: SectionDao,
    private val categoryDao: CategoryDao,
    val ops: OpRecorder,
) {

    suspend fun recordSectionUpsert(row: SectionEntity, baseSeq: Long?) {
        ops.onUpsert(
            RowKind.SECTION,
            row.syncId,
            seq = row.versionSeq,
            baseSeq = baseSeq,
            snapshot = OpCodec.sectionSnapshot(
                name = row.name,
                iconId = row.iconId,
                note = row.note,
                budgetCents = row.budgetCents,
                colorIndex = row.colorIndex,
                sortOrder = row.sortOrder,
                createdAt = row.createdAt,
            ),
        )
    }

    suspend fun recordSectionDelete(row: SectionEntity, now: Long) {
        ops.onDelete(
            RowKind.SECTION,
            row.syncId.ifEmpty { newSyncId() },
            // U-13：DELETE baseSeq = 真实 versionSeq（v4 迁移/种子行可为 0），不得伪造抬成 1
            row.versionSeq,
            OpCodec.withDeletedAt(
                OpCodec.sectionSnapshot(
                    name = row.name,
                    iconId = row.iconId,
                    note = row.note,
                    budgetCents = row.budgetCents,
                    colorIndex = row.colorIndex,
                    sortOrder = row.sortOrder,
                    createdAt = row.createdAt,
                ),
                now,
            ),
        )
    }

    suspend fun recordCategoryUpsert(row: CategoryEntity, baseSeq: Long?) {
        ops.onUpsert(
            RowKind.CATEGORY,
            row.syncId,
            seq = row.versionSeq,
            baseSeq = baseSeq,
            snapshot = OpCodec.categorySnapshot(
                name = row.name,
                iconId = row.iconId,
                type = row.type,
                sectionSyncId = row.sectionId?.let { sectionSyncIdOf(it) },
                sortOrder = row.sortOrder,
            ),
        )
    }

    suspend fun recordCategoryDelete(row: CategoryEntity, now: Long) {
        ops.onDelete(
            RowKind.CATEGORY,
            row.syncId.ifEmpty { newSyncId() },
            // U-13：DELETE baseSeq = 真实 versionSeq（v4 迁移/种子行可为 0），不得伪造抬成 1
            row.versionSeq,
            OpCodec.withDeletedAt(
                OpCodec.categorySnapshot(
                    name = row.name,
                    iconId = row.iconId,
                    type = row.type,
                    sectionSyncId = row.sectionId?.let { sectionSyncIdOf(it) },
                    sortOrder = row.sortOrder,
                ),
                now,
            ),
        )
    }

    suspend fun recordEntryUpsert(row: EntryEntity, baseSeq: Long?) {
        ops.onUpsert(
            RowKind.ENTRY,
            row.syncId,
            seq = row.versionSeq,
            baseSeq = baseSeq,
            snapshot = OpCodec.entrySnapshot(
                type = row.type,
                amountCents = row.amountCents,
                categorySyncId = entryCategorySyncId(row.categoryId, row.type),
                sectionSyncId = entrySectionSyncId(row.sectionId),
                entryTime = row.entryTime,
                note = row.note,
                reconciled = row.reconciled,
                reimburseState = row.reimburseState,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                memberSyncId = row.memberId,
            ),
        )
    }

    /**
     * AU-8：账目快照的**分类引用**解析。
     *
     * 缺陷背景：此前 `categorySyncIdOf(0) ?: ""` 把 0 占位（未分类口径，UI 与
     * OpApplier「ENTRY 挂 0」共同承认的合法状态）序列化成**空串**，而对端
     * RoomRowStore 的引用三分判定把空串判为「从未到过」→ 整行挂起、每轮重试永不落地
     * ——编辑一笔挂 0 分类/悬空分类的账目后，其余设备静默不同步（真实触发入口：
     * moveEntryToSection 保留 0 分类、duplicateEntry 复制 0 分类）。
     *
     * 修法：解析不到分类行（含 0 占位）→ 改挂「未分类」哨兵。哨兵是**确定性 syncId**
     * 的全局分类行（SeedIds 派生，两设备补种同一逻辑行），任何设备都可解析；且语义
     * 精确——挂 0 的账目 UI 显示的就是「未分类」，与 deleteCategory 迁去哨兵的既有
     * 编码同口径。
     */
    suspend fun entryCategorySyncId(categoryId: Long, type: Int): String =
        categoryDao.getById(categoryId)?.syncId ?: SectionFirstSeed.Unclassified.syncId(type)

    /**
     * AU-8：账目快照的**分区引用**解析。
     *
     * 分区维没有哨兵（「未分区」不是分区行），0 占位/悬空分区只能继续以空串落载荷；
     * 对端 refOrNull 会把空串判「从未到过」而挂起该行——彻底修复需 OpApplier 把空串
     * 引用按「到过且已死 → 0 占位」降级（与 CATEGORY 死引用降级同口径，sync/ 范围），
     * 数据层已保证除该残留外不再产生空串引用。
     */
    suspend fun entrySectionSyncId(sectionId: Long): String =
        sectionDao.getById(sectionId)?.syncId ?: ""

    /** 分区 syncId 读取（快照引用拼装用）；行不存在返回 null */
    private suspend fun sectionSyncIdOf(id: Long): String? = sectionDao.getById(id)?.syncId

    /** 分类 syncId 读取（快照引用拼装用）；行不存在返回 null */
    private suspend fun categorySyncIdOf(id: Long): String? = categoryDao.getById(id)?.syncId
}

/** 新行 syncId：UUID 32 字符小写（跨设备身份，永不变） */
internal fun newSyncId(): String = UUID.randomUUID().toString().replace("-", "")
