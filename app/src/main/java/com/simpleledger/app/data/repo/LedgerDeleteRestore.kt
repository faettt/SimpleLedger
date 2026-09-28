package com.simpleledger.app.data.repo

import androidx.room.withTransaction
import com.simpleledger.app.data.local.AppDatabase
import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.dao.CategoryDao
import com.simpleledger.app.data.local.dao.EntryDao
import com.simpleledger.app.data.local.dao.SectionDao
import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryImageEntity
import com.simpleledger.app.logic.CategoryDeletePlan
import com.simpleledger.app.logic.PhotoRetention
import com.simpleledger.app.logic.RestoreFallbacks
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.op.TrashAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 删除 / 恢复语义协作者（U-11 拆分自 LedgerRepository，行为零变化）：
 *
 * - **A1 整包删分区**：账目逐行连贴图删除 + 专属分类降级全局 + 分区 DELETE，
 *   可整包恢复（留底由 [OpRecorder.onDelete] 经 TrashDeriver 推导）；
 * - **B4 分类去向迁移**：账目改挂去向分类逐行埋点 + 分类 DELETE；
 * - **§3.8 删除撤销双路径**：快照删除（留 syncId/versionSeq/deleteOpId）→
 *   未上传走 `removePendingOps` 原版本复原 / 已上传走复活 UPSERT + TRASH_ACT(PURGE)；
 * - **恢复兜底**：原分区已死迁移到存活分区（[RestoreFallbacks]）。
 *
 * 埋点铁律（§3.7/§7-9）不变：本类的每个业务写与 [ops] 追加都处在**同一个**
 * `db.withTransaction` 事务内；文件 IO 一律在事务外（照片实体文件按 A2 照片挂起
 * 口径不物理删除，交 [PhotoRetention] 判据）。
 */
internal class LedgerDeleteRestore(
    private val db: AppDatabase,
    private val entryDao: EntryDao,
    private val sectionDao: SectionDao,
    private val categoryDao: CategoryDao,
    private val syncDao: SyncDao,
    private val recording: LedgerOpRecording,
    private val imageStorage: ImageStorage,
) {
    private val ops: OpRecorder = recording.ops

    /** 删除分区 / 分类时的互斥锁，避免并发迁移兜底分类 */
    private val deleteMutex = Mutex()

    /**
     * 删除分区前的影响描述（供确认框展示「连删留底 + 分类去向」，A1 新口径）。
     * [SectionDeleteImpact.blockedReason] 非空表示「当前不可删」及原因（仅剩
     * 「分区不存在」——「最后一个分区有账目不给删」的旧规已废止）。
     */
    suspend fun sectionDeleteImpact(sectionId: Long): SectionDeleteImpact {
        val target = sectionDao.getById(sectionId)
            ?: return SectionDeleteImpact("", 0, 0, "分区不存在")
        return SectionDeleteImpact(
            sectionName = target.name,
            entryCount = entryDao.countBySection(sectionId),
            exclusiveCategoryCount = categoryDao.countBySection(sectionId),
            blockedReason = null,
        )
    }

    /**
     * 删除单笔账目并逐行埋点（ENTRY + 各 IMAGE 各一条 DELETE，载荷含 `_deletedAt`）。
     * ⚠️ 必须在调用方事务内执行（[deleteSection] 的连删步骤）。
     * A2 照片挂起：贴图行删除但实体文件**不物理删除**（留底恢复后按 contentHash 回链）。
     */
    private suspend fun deleteEntryRowsInTx(entryId: Long, now: Long) {
        val full = entryDao.getEntryFull(entryId) ?: return
        val entry = full.entry
        val entrySyncId = entry.syncId.ifEmpty { newSyncId() }
        // AU-8：引用解析统一走 LedgerOpRecording（挂 0 / 悬空分类 → 「未分类」哨兵）
        val categorySyncId = recording.entryCategorySyncId(entry.categoryId, entry.type)
        val sectionSyncId = recording.entrySectionSyncId(entry.sectionId)

        full.images.sortedBy { it.sortOrder }.forEach { img ->
            ops.onDelete(
                RowKind.IMAGE,
                img.syncId.ifEmpty { newSyncId() },
                img.versionSeq, // U-13：baseSeq 携真实 versionSeq（v0 行可为 0，不得抬成 1）
                OpCodec.withDeletedAt(
                    OpCodec.imageSnapshot(entrySyncId, img.contentHash, img.sortOrder),
                    now,
                ),
            )
        }
        ops.onDelete(
            RowKind.ENTRY,
            entrySyncId,
            entry.versionSeq, // U-13：baseSeq 携真实 versionSeq（v0 行可为 0，不得抬成 1）
            OpCodec.withDeletedAt(
                OpCodec.entrySnapshot(
                    type = entry.type,
                    amountCents = entry.amountCents,
                    categorySyncId = categorySyncId,
                    sectionSyncId = sectionSyncId,
                    entryTime = entry.entryTime,
                    note = entry.note,
                    reconciled = entry.reconciled,
                    reimburseState = entry.reimburseState,
                    createdAt = entry.createdAt,
                    updatedAt = now,
                    memberSyncId = entry.memberId,
                ),
                now,
            ),
        )

        // 运行期外键关闭、级联不生效（见 AppDatabase 注释）→ 显式清贴图行
        entryDao.deleteImagesOf(entryId)
        entryDao.deleteEntry(entryId)
    }

    /**
     * 删除分区（A1 新口径：**删除分区 = 连删账目并留底**，可整包恢复）。
     *
     * 语义（在事务内，任一失败整体回滚）：
     * 1. 该分区下**每笔账目**逐行删除：ENTRY + 各 IMAGE 记 DELETE 操作，
     *    [OpRecorder.onDelete] 自动推导 `sync_trash` 留底（TrashDeriver）；
     *    照片实体文件**不销毁**（引用挂起，恢复后完整回链，见 [PhotoRetention]）；
     * 2. 专属分类逐行**降级为全局**（`sectionId := NULL`，同事务逐行记 UPSERT）；
     * 3. 分区本身记 DELETE（留底同推导）并删除行。
     *
     * 旧规「最后一个分区有账目不给删」**废止**——账目随分区一并进回收站，可整包恢复。
     */
    suspend fun deleteSection(sectionId: Long): Result<Unit> = deleteMutex.withLock {
        runCatching {
            db.withTransaction {
                val now = System.currentTimeMillis()
                val target = sectionDao.getById(sectionId) ?: error("分区不存在")

                // 1) 账目逐笔连贴图删除（DELETE 埋点 + 留底，同事务）
                entryDao.listBySection(sectionId).forEach { entry ->
                    deleteEntryRowsInTx(entry.id, now)
                }

                // 2) 专属分类逐行降级为全局（不丢数据），同步侧逐行埋点
                categoryDao.listBySection(sectionId).forEach { cat ->
                    val updated = cat.copy(sectionId = null, versionSeq = cat.versionSeq + 1, updatedAt = now)
                    categoryDao.update(updated)
                    recording.recordCategoryUpsert(updated, baseSeq = cat.versionSeq)
                }

                // 3) 分区本身
                recording.recordSectionDelete(target, now)
                sectionDao.delete(sectionId)
            }
        }
    }

    /**
     * 删除分类（B4 新口径：**去向单选迁移**，FR-41）。
     *
     * 该分类下的账目迁移到 [destinationId] 指定的同类型分类（null → 默认「未分类」哨兵），
     * 然后删除分类。取代旧的「同类型 sort-first 兜底链」。
     * 「未分类」哨兵不可删（[CategoryDeleteImpact] 的 blockedReason 与 UI 共守）。
     *
     * v5 埋点：账目改挂 destination 逐行记 UPSERT；分类本身记 DELETE。
     */
    suspend fun deleteCategory(categoryId: Long, destinationId: Long? = null): Result<Unit> =
        deleteMutex.withLock {
            runCatching {
                db.withTransaction {
                    val now = System.currentTimeMillis()
                    val target = categoryDao.getById(categoryId) ?: error("分类不存在")
                    if (SectionFirstSeed.Unclassified.isUnclassified(target)) {
                        error("「未分类」不可删除")
                    }
                    // AU-3：去向候选按受影响账目所在分区收窄（分区 = X、分类只属于 Y
                    // 是非法组合，SectionMoveRules/EC-06 口径）——迁移后不允许出现
                    // 「账目留在原分区、却挂上了别分区专属分类」的越界组合
                    val affectedSections = entryDao.sectionIdsOfCategory(categoryId).toSet()
                    val sameType = categoryDao.getAllByType(target.type)
                    val plan = CategoryDeletePlan.plan(
                        sameType,
                        target.id,
                        affectedSections.takeIf { it.isNotEmpty() },
                    )
                    val destId = CategoryDeletePlan.resolveDestinationId(plan.destinations, destinationId)
                    val referencing = entryDao.countByCategory(categoryId)

                    if (referencing > 0) {
                        val dest = destId?.let { categoryDao.getById(it) }
                            ?: error("没有同类型分类可承接；请先新建一个同类型分类")
                        entryDao.listByCategory(categoryId).forEach { e ->
                            val updated = e.copy(
                                categoryId = dest.id,
                                versionSeq = e.versionSeq + 1,
                                updatedAt = now,
                            )
                            entryDao.updateEntry(updated)
                            recording.recordEntryUpsert(updated, baseSeq = e.versionSeq)
                        }
                    }
                    recording.recordCategoryDelete(target, now)
                    categoryDao.delete(categoryId)
                }
            }
        }

    /**
     * 删除分类前的影响描述（FR-41 新口径：给出**去向候选**与默认预选，
     * 确认框升级为单选迁移；哨兵恒在候选末位，因此只要有同类型哨兵就永不可阻塞）。
     * AU-3：候选已按受影响账目所在分区收窄（全局 + 受影响账目全部所在分区的专属项），
     * UI 单选列表与迁移执行共用同一份 [CategoryDeletePlan] 口径。
     */
    suspend fun categoryDeleteImpact(categoryId: Long): CategoryDeleteImpact {
        val target = categoryDao.getById(categoryId)
            ?: return CategoryDeleteImpact(0, emptyList(), null, "分类不存在")
        if (SectionFirstSeed.Unclassified.isUnclassified(target)) {
            return CategoryDeleteImpact(0, emptyList(), null, "「未分类」不可删除")
        }
        val affectedSections = entryDao.sectionIdsOfCategory(categoryId).toSet()
        val sameType = categoryDao.getAllByType(target.type)
        val plan = CategoryDeletePlan.plan(
            sameType,
            target.id,
            affectedSections.takeIf { it.isNotEmpty() },
        )
        val referencing = entryDao.countByCategory(categoryId)
        val blocked = if (plan.destinations.isEmpty() && referencing > 0) {
            "该分类下还有 $referencing 笔账目，且没有同类型分类可承接；请先新建一个同类型分类"
        } else {
            null
        }
        return CategoryDeleteImpact(
            entryCount = referencing,
            destinations = plan.destinations,
            defaultDestinationId = plan.defaultDestinationId,
            blockedReason = blocked,
        )
    }

    /** 无快照删除（文件直接销毁）；撤销语义走 [deleteEntryWithSnapshot] */
    suspend fun deleteEntry(id: Long) {
        val snapshot = deleteEntryWithSnapshot(id) ?: return
        val parkedPaths = snapshot.images.mapNotNull { it.parkedPath.ifEmpty { null } }
        if (parkedPaths.isNotEmpty()) {
            imageStorage.deleteFiles(parkedPaths)
        }
    }

    /**
     * 删除账目并返回可恢复的快照（贴图文件先移入暂存区，不直接销毁）。
     * 长按删除按设计规格是「不弹确认 + 4 秒撤销」，所以必须留得住这份数据。
     *
     * v5：删除在事务内逐行埋点（ENTRY + IMAGE 各一条 DELETE，载荷含 `_deletedAt`）；
     * 快照携带 syncId / versionSeq / deleteOpId 供恢复的双路径使用。
     * 共享内容文件（`contentHash` 引用计数 > 1）**不暂存**——其余账目还指着它。
     */
    suspend fun deleteEntryWithSnapshot(id: Long): DeletedEntrySnapshot? {
        val prepared = db.withTransaction {
            // AU-7：行读取移入事务内——此前在事务外普通读，读与事务开始之间若恰有
            // 远端合并推进该行 versionSeq（OpApplier 的 MERGE 事务随时可能提交），
            // DELETE 会拿着陈旧的 versionSeq 作 baseSeq，按 observed-remove 判据
            // （baseSeq >= seq 才算观察到）杀不死并发新版本，而本行仍被无条件删除
            // ——本机死、他机活的跨设备分歧。与 deleteEntryRowsInTx（调用方事务内
            // 重读）、saveEntry（事务内读 existing）对齐同一口径。
            val full = entryDao.getEntryFull(id) ?: return@withTransaction null
            val now = System.currentTimeMillis()
            val entry = full.entry
            val entrySyncId = entry.syncId.ifEmpty { newSyncId() }
            // AU-8：引用解析统一走 LedgerOpRecording（挂 0 / 悬空分类 → 「未分类」哨兵）
            val categorySyncId = recording.entryCategorySyncId(entry.categoryId, entry.type)
            val sectionSyncId = recording.entrySectionSyncId(entry.sectionId)
            val images = full.images.sortedBy { it.sortOrder }

            val imageInfos = images.map { img ->
                val shared = img.contentHash.isNotEmpty() && entryDao.countByContentHash(img.contentHash) > 1
                val imgSyncId = img.syncId.ifEmpty { newSyncId() }
                // U-13：baseSeq / 快照 versionSeq 都携真实值（v0 行可为 0）——恢复路径
                // 优先读 DELETE op 的 baseSeq（:384/:409），两处同源才不产生伪造版本
                val imgBase = img.versionSeq
                val deleteOpId = ops.onDelete(
                    RowKind.IMAGE,
                    imgSyncId,
                    imgBase,
                    OpCodec.withDeletedAt(
                        OpCodec.imageSnapshot(entrySyncId, img.contentHash, img.sortOrder),
                        now,
                    ),
                )
                DeletedImageSnapshot(
                    syncId = imgSyncId,
                    versionSeq = imgBase,
                    contentHash = img.contentHash,
                    sortOrder = img.sortOrder,
                    filePath = img.filePath,
                    parkedPath = "",
                    deleteOpId = deleteOpId,
                    sharedFile = shared,
                )
            }

            val entryBase = entry.versionSeq // U-13：真实 versionSeq，可为 0
            val entryDeleteOpId = ops.onDelete(
                RowKind.ENTRY,
                entrySyncId,
                entryBase,
                OpCodec.withDeletedAt(
                    OpCodec.entrySnapshot(
                        type = entry.type,
                        amountCents = entry.amountCents,
                        categorySyncId = categorySyncId,
                        sectionSyncId = sectionSyncId,
                        entryTime = entry.entryTime,
                        note = entry.note,
                        reconciled = entry.reconciled,
                        reimburseState = entry.reimburseState,
                        createdAt = entry.createdAt,
                        updatedAt = now,
                        memberSyncId = entry.memberId,
                    ),
                    now,
                ),
            )

            // 运行期外键关闭、级联不生效（见 AppDatabase 注释）→ 显式清贴图行
            entryDao.deleteImagesOf(id)
            entryDao.deleteEntry(id)

            DeletedEntrySnapshot(
                type = entry.type,
                amountCents = entry.amountCents,
                categoryId = entry.categoryId,
                sectionId = entry.sectionId,
                entryTime = entry.entryTime,
                note = entry.note,
                parkedImagePaths = emptyList(),
                syncId = entrySyncId,
                versionSeq = entryBase,
                memberId = entry.memberId,
                createdAt = entry.createdAt,
                reconciled = entry.reconciled,
                reimburseState = entry.reimburseState,
                images = imageInfos,
                entryDeleteOpId = entryDeleteOpId,
            )
        }

        // A2 照片挂起：贴图实体文件**留在原位**（内容寻址 `filesDir/images/<sha256>.jpg`），
        // 不再暂存挪窝——留底期内文件不物理删除（[PhotoRetention]，回收站恢复 /
        // 撤销删除后按 contentHash 原路回链，无需文件迁移）。4 秒撤销窗口的恢复
        // 直接复用原路径（parkedPath 恒空，[restoreEntry] 的归位循环自然跳过）。
        return prepared
    }

    /**
     * 撤销删除（§3.8 双路径）：
     * 1. **首选**：删除操作全部**未上传** → `removePendingOps` 连操作一起摘除、清留底，
     *    行按**原 syncId / 原 versionSeq** 复原（云端不会留下矛盾事实）；
     * 2. **备选**：删除操作已上传 → 复活 UPSERT（`baseSeq = d.baseSeq`、`seq = baseSeq + 1`，
     *    不会被同一删除杀死）+ `TRASH_ACT(PURGE)` 撤下留底（§3.8 撤销语义）。
     *
     * AU-6：路径判定收进**同一个事务**、且以 `removePendingOps` 的返回计数为准——
     * 此前判据（逐 op 普通读 `uploaded` 标志）在事务外计算、事务内执行，与 PUSH 的
     * `markUploaded`（SyncEngine 上传成功即置位）之间无互斥：判「净」后、事务前恰有一轮
     * 同步上传成功时，带 `uploaded = 0` 守卫的摘除会静默删 0 行，却仍按原版本复原且不留
     * 复活 UPSERT——本机账目复活、其余设备永远看到它已删，直到该行下次被编辑才自愈。
     * 摘除计数是带守卫的**事后事实**：摘满 ⟺ 全部未上传；摘不满 ⟺ 至少一条已上传/非本机
     * （或已不在日志，如 resetSync 清空后）→ 一律走备选路径，宁可多记一条复活操作，
     * 也不留下静默分歧。
     *
     * 暂存贴图文件在事务提交后归位（文件 IO 在事务外）。
     */
    suspend fun restoreEntry(snapshot: DeletedEntrySnapshot): Long? = runCatching {
        val opIds = (listOf(snapshot.entryDeleteOpId) + snapshot.images.map { it.deleteOpId })
            .filter { it.isNotEmpty() }
        val entryId = db.withTransaction {
            val removed = if (opIds.isEmpty()) 0 else syncDao.removePendingOps(opIds)
            if (removed == opIds.size) {
                // 首选路径：删除操作确认全部未上传（守卫 DELETE 同事务返回摘满）
                opIds.forEach { syncDao.deleteTrash(it) }
                insertRestoredRows(
                    snapshot,
                    entryVersionSeq = snapshot.versionSeq,
                    imageVersionSeqs = snapshot.images.map { it.versionSeq },
                )
            } else {
                // 备选路径：至少一条删除操作已上传（或已不在日志）——复活 UPSERT + PURGE
                val entryBase = syncDao.getOp(snapshot.entryDeleteOpId)?.baseSeq ?: snapshot.versionSeq
                if (snapshot.entryDeleteOpId.isNotEmpty()) {
                    ops.onTrashAct(snapshot.entryDeleteOpId, TrashAction.PURGE, null)
                }
                ops.onUpsert(
                    RowKind.ENTRY,
                    snapshot.syncId,
                    seq = entryBase + 1,
                    baseSeq = entryBase,
                    snapshot = OpCodec.entrySnapshot(
                        type = snapshot.type,
                        amountCents = snapshot.amountCents,
                        // AU-8：引用解析统一走 LedgerOpRecording（挂 0 / 悬空分类 → 哨兵）
                        categorySyncId = recording.entryCategorySyncId(snapshot.categoryId, snapshot.type),
                        sectionSyncId = recording.entrySectionSyncId(snapshot.sectionId),
                        entryTime = snapshot.entryTime,
                        note = snapshot.note,
                        reconciled = snapshot.reconciled,
                        reimburseState = snapshot.reimburseState,
                        createdAt = snapshot.createdAt,
                        updatedAt = System.currentTimeMillis(),
                        memberSyncId = snapshot.memberId,
                    ),
                )
                val imageSeqs = snapshot.images.map { img ->
                    val base = syncDao.getOp(img.deleteOpId)?.baseSeq ?: img.versionSeq
                    if (img.deleteOpId.isNotEmpty()) {
                        ops.onTrashAct(img.deleteOpId, TrashAction.PURGE, null)
                    }
                    ops.onUpsert(
                        RowKind.IMAGE,
                        img.syncId,
                        seq = base + 1,
                        baseSeq = base,
                        snapshot = OpCodec.imageSnapshot(snapshot.syncId, img.contentHash, img.sortOrder),
                    )
                    base + 1
                }
                insertRestoredRows(snapshot, entryVersionSeq = entryBase + 1, imageVersionSeqs = imageSeqs)
            }
        }

        // 事务外：暂存文件归位（共享文件本就未暂存，parkedPath 为空直接跳过）
        snapshot.images.forEach { img ->
            if (img.parkedPath.isNotEmpty()) {
                imageStorage.unparkFile(img.parkedPath, img.filePath)
            }
        }
        entryId
    }.getOrNull()

    /**
     * 回收站恢复后的「原分区已死」兜底（A1 单笔恢复）：恢复出来的账目若挂在 0 占位分区
     * （原分区已删且未随整包恢复），迁移到**存活分区中排序最前**的一个（[RestoreFallbacks]）
     * 并按 UPSERT 埋点（改挂随同步带到其余设备）。
     *
     * @return 改换后的分区 id；原分区仍在、行不存在或已无存活分区（无处安放，
     *         沿用死引用 0 占位口径）时返回 null
     */
    suspend fun rehomeRestoredEntry(entrySyncId: String): Long? {
        val entry = entryDao.getBySyncId(entrySyncId) ?: return null
        val originalAlive = entry.sectionId != 0L && sectionDao.getById(entry.sectionId) != null
        if (originalAlive) return null
        val sections = sectionDao.observeAll().first()
            .map { RestoreFallbacks.SectionRef(it.id, it.sortOrder) }
        val targetId = RestoreFallbacks.fallbackSectionId(entry.sectionId, false, sections)
            ?: return null
        val now = System.currentTimeMillis()
        db.withTransaction {
            val updated = entry.copy(
                sectionId = targetId,
                versionSeq = entry.versionSeq + 1,
                updatedAt = now,
            )
            entryDao.updateEntry(updated)
            recording.recordEntryUpsert(updated, baseSeq = entry.versionSeq)
        }
        return targetId
    }

    /** 恢复时把快照写回业务表（必须在调用方事务内执行），返回条目 id */
    private suspend fun insertRestoredRows(
        snapshot: DeletedEntrySnapshot,
        entryVersionSeq: Long,
        imageVersionSeqs: List<Long>,
    ): Long {
        val now = System.currentTimeMillis()
        val entryId = entryDao.upsertRemote(
            EntryEntity(
                id = 0,
                type = snapshot.type,
                amountCents = snapshot.amountCents,
                categoryId = snapshot.categoryId,
                sectionId = snapshot.sectionId,
                entryTime = snapshot.entryTime,
                note = snapshot.note,
                reconciled = snapshot.reconciled,
                reimburseState = snapshot.reimburseState,
                createdAt = snapshot.createdAt,
                updatedAt = now,
                syncId = snapshot.syncId,
                versionSeq = entryVersionSeq,
                memberId = snapshot.memberId,
            )
        )
        snapshot.images.forEachIndexed { index, img ->
            entryDao.upsertRemoteImage(
                EntryImageEntity(
                    id = 0,
                    entryId = entryId,
                    filePath = img.filePath,
                    sortOrder = img.sortOrder,
                    syncId = img.syncId,
                    versionSeq = imageVersionSeqs.getOrElse(index) { img.versionSeq },
                    updatedAt = now,
                    contentHash = img.contentHash,
                )
            )
        }
        return entryId
    }
}

/** 删除账目时留下的快照，用于撤销窗口内完整恢复（含 v5 同步身份与操作关联） */
data class DeletedEntrySnapshot(
    val type: Int,
    val amountCents: Long,
    val categoryId: Long,
    val sectionId: Long,
    val entryTime: Long,
    val note: String,
    /** 暂存后的贴图文件路径（保留兼容 UI 撤销逻辑；与 [images] 的 parkedPath 同源） */
    val parkedImagePaths: List<String>,
    /** 原行 syncId（恢复复用，跨设备身份不变） */
    val syncId: String = "",
    /** 原行 versionSeq（未上传 DELETE 摘除后按原版本复原） */
    val versionSeq: Long = 0,
    /** 记账成员 syncId；null = 未知成员 */
    val memberId: String? = null,
    val createdAt: Long = 0,
    val reconciled: Boolean = false,
    val reimburseState: Int = 0,
    /** 贴图逐张快照（同步恢复需要逐张身份与操作关联） */
    val images: List<DeletedImageSnapshot> = emptyList(),
    /** ENTRY 的 DELETE 操作 opId（restoreEntry 双路径判据与摘除键） */
    val entryDeleteOpId: String = "",
)

/** 删除账目时逐张贴图留下的快照 */
data class DeletedImageSnapshot(
    val syncId: String,
    val versionSeq: Long,
    val contentHash: String,
    val sortOrder: Int,
    val filePath: String,
    /** 暂存后的新路径；空 = 共享文件未暂存（或暂存失败，文件仍在 filePath） */
    val parkedPath: String,
    /** 该贴图的 DELETE 操作 opId */
    val deleteOpId: String,
    /** true = 内容被其他贴图行共享（引用计数 > 1），删除/撤销都不动文件 */
    val sharedFile: Boolean = false,
)

/**
 * 删除分区前给确认框用的影响描述（A1 新口径：分区 + 账目**一并删除并留底**，
 * 回收站整包恢复；专属分类降级为全局）。
 *
 * @param sectionName            被删分区名（确认框标题用）
 * @param entryCount            该分区下的账目数（将随分区一并进回收站）
 * @param exclusiveCategoryCount 该分区的专属分类数（删除时将降级为全局）
 * @param blockedReason         非 null 表示「当前不可删」及可读原因
 *                              （旧规「最后一个分区有账目不给删」已废止，
 *                              仅剩「分区不存在」）
 */
data class SectionDeleteImpact(
    val sectionName: String,
    val entryCount: Int,
    val exclusiveCategoryCount: Int,
    val blockedReason: String?,
)

/**
 * 删除分类前给确认框用的影响描述（B4/FR-41 新口径：确认框升级为**去向单选**）。
 *
 * @param entryCount           该分类下的账目数
 * @param destinations         去向候选（同类型其余分类 + 「未分类」哨兵，哨兵恒排末尾；
 *                             AU-3：已按受影响账目所在分区收窄，跨分区专属项不出现）
 * @param defaultDestinationId 默认预选 = 「未分类」哨兵；候选为空时 null
 * @param blockedReason        非 null 表示「当前不可删」及可读原因
 */
data class CategoryDeleteImpact(
    val entryCount: Int,
    val destinations: List<CategoryEntity>,
    val defaultDestinationId: Long?,
    val blockedReason: String?,
)
