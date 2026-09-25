package com.simpleledger.app.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.simpleledger.app.data.export.ExportRow
import com.simpleledger.app.data.local.AppDatabase
import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryImageEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.OpOrigin
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.SectionTotal
import com.simpleledger.app.data.local.entity.TypeTotal
import com.simpleledger.app.logic.PhotoRetention
import com.simpleledger.app.logic.RestoreFallbacks
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.op.TrashAction
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 一笔记账的完整草稿（新建 / 编辑共用） */
data class EntryDraft(
    val id: Long? = null,
    val type: Int,
    val amountCents: Long,
    val categoryId: Long,
    val sectionId: Long,
    val entryTime: Long,
    val note: String,
    /** 编辑后仍然保留的既有图片路径（顺序即展示顺序） */
    val keptImagePaths: List<String> = emptyList(),
    /** 已读入 cache、等待入库的新图片路径 */
    val pendingImagePaths: List<String> = emptyList(),
)

/**
 * 账本仓库：业务写路径 + **v5 同步埋点**（T-3）。
 *
 * 埋点铁律（§3.7/§7-9）：每次增删改与 [OpRecorder] 的操作追加在**同一个**数据库事务内
 * ——失败同回滚，绝不出现「行写了操作没记 / 操作记了行没写」；文件 IO 一律在事务外
 * （沿用 saveEntry 三段式）。行版本口径（U-3）：
 * - 新行：`syncId = UUID 32hex`、`versionSeq = 1`、UPSERT `baseSeq = null`；
 * - 编辑：`versionSeq = 旧值 + 1`、UPSERT `baseSeq = 旧值`；
 * - 删除：DELETE `baseSeq = 被删行 versionSeq`，载荷 = 行快照 + `_deletedAt`。
 */
class LedgerRepository(
    private val db: AppDatabase,
    private val imageStorage: ImageStorage,
    private val ops: OpRecorder,
) {
    private val entryDao = db.entryDao()
    private val sectionDao = db.sectionDao()
    private val categoryDao = db.categoryDao()
    private val syncDao = db.syncDao()

    /** 删除分区 / 分类时的互斥锁，避免并发迁移兜底分类 */
    private val deleteMutex = Mutex()

    // ---------- 明细 / 统计查询 ----------

    fun observeEntries(
        start: Long,
        end: Long,
        sectionId: Long? = null,
        categoryId: Long? = null,
        type: Int? = null,
        reconciled: Boolean? = null,
        reimburseState: Int? = null,
    ): Flow<List<EntryFull>> =
        entryDao.observeEntries(start, end, sectionId, categoryId, type, reconciled, reimburseState)

    fun observeTypeTotals(
        start: Long,
        end: Long,
        sectionId: Long? = null,
        categoryId: Long? = null,
        reconciled: Boolean? = null,
        reimburseState: Int? = null,
    ): Flow<List<TypeTotal>> =
        entryDao.observeTypeTotals(start, end, sectionId, categoryId, reconciled, reimburseState)

    fun observeCategoryTotals(
        type: Int,
        start: Long,
        end: Long,
        sectionId: Long? = null,
    ): Flow<List<CategoryTotal>> = entryDao.observeCategoryTotals(type, start, end, sectionId)

    /** 分区首屏数据源：**含空分区**的本月汇总（LEFT JOIN 版，见 Q-06/EC-04） */
    fun observeSectionHome(start: Long, end: Long): Flow<List<SectionTotal>> =
        entryDao.observeSectionOverview(start, end)

    /** 全局搜索（跨全部时间，四类匹配）；调用方负责转义 LIKE 通配符 */
    fun observeSearch(keyword: String, type: Int?): Flow<List<EntryFull>> {
        val escaped = keyword
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return entryDao.observeSearch("%$escaped%", type)
    }

    // ---------- 分区 ----------

    fun observeSections(): Flow<List<SectionEntity>> = sectionDao.observeAll()

    suspend fun getSection(sectionId: Long): SectionEntity? = sectionDao.getById(sectionId)

    /** 新建 / 编辑分区（同事务埋点 UPSERT） */
    suspend fun saveSection(section: SectionEntity): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        if (section.id == 0L) {
            val syncId = section.syncId.ifEmpty { newSyncId() }
            val row = section.copy(
                sortOrder = sectionDao.nextSortOrder(),
                syncId = syncId,
                versionSeq = 1,
                updatedAt = now,
            )
            val id = sectionDao.insert(row)
            recordSectionUpsert(row, baseSeq = null)
            id
        } else {
            val cur = sectionDao.getById(section.id)
            val baseSeq = cur?.versionSeq ?: section.versionSeq
            val syncId = (cur?.syncId ?: section.syncId).ifEmpty { newSyncId() }
            val row = section.copy(
                syncId = syncId,
                versionSeq = baseSeq + 1,
                createdAt = cur?.createdAt ?: section.createdAt,
                updatedAt = now,
            )
            sectionDao.update(row)
            recordSectionUpsert(row, baseSeq = baseSeq)
            section.id
        }
    }

    /**
     * 删除分区前的影响描述（供确认框展示「账目去向 + 分类去向」，FR-40）。
     * [blockedReason] 非空表示「当前不可删」及原因。
     */
    suspend fun sectionDeleteImpact(sectionId: Long): SectionDeleteImpact {
        val all = sectionDao.getAll()
        val target = all.firstOrNull { it.id == sectionId }
            ?: return SectionDeleteImpact(0, 0, null, "分区不存在")
        val others = all.filter { it.id != sectionId }
        val entryCount = entryDao.countBySection(sectionId)
        val exclusiveCount = categoryDao.countBySection(sectionId)
        val fallback = others.firstOrNull()
        val blocked = if (others.isEmpty() && entryCount > 0) {
            "该分区还有 $entryCount 笔账目，删除后将无处归属；请先删除这些账目"
        } else {
            null
        }
        return SectionDeleteImpact(
            entryCount = entryCount,
            exclusiveCategoryCount = exclusiveCount,
            fallback = fallback,
            blockedReason = blocked,
        )
    }

    /**
     * 删除分区（Q-04 / Q-06 / D-3）。
     *
     * 语义（在事务内，任一失败整体回滚）：
     * 1. 该分区的专属分类一律**降级为全局**（`sectionId := NULL`），绝不硬删（Q-04）；
     * 2. 若它是**最后一个分区**：
     *    - 有账目 → 阻塞删除并给出可读原因（保护账目不丢、不重建表）；
     *    - 无账目 → 允许删到 0（Q-06）；
     * 3. 否则把账目迁到「排序最靠前的其余分区」，再删分区。
     *
     * 注意：账目迁入 fallback 前，专属分类已降级为全局 → 全局对 fallback 可见 →
     * 不会出现「分区=旅行、分类只属于装修」的非法组合。
     */
    suspend fun deleteSection(sectionId: Long): Result<Unit> = deleteMutex.withLock {
        runCatching {
            db.withTransaction {
                val all = sectionDao.getAll()
                val target = all.firstOrNull { it.id == sectionId }
                    ?: error("分区不存在")
                val others = all.filter { it.id != sectionId }
                val entryCount = entryDao.countBySection(sectionId)

                // Q-04：专属分类降级为全局（不丢数据、不报错）
                categoryDao.detachFromSection(sectionId)

                if (others.isEmpty()) {
                    if (entryCount > 0) {
                        // 抛出以触发事务回滚（含上面的 detachFromSection）
                        error("该分区还有 $entryCount 笔账目，删除后将无处归属；请先删除这些账目")
                    }
                    sectionDao.delete(sectionId) // 允许删到 0（Q-06）
                } else {
                    val fallback = others.first() // 已按 sortOrder 排序，取最靠前者
                    if (entryCount > 0) sectionDao.moveEntries(sectionId, fallback.id)
                    sectionDao.delete(sectionId)
                }
            }
        }
    }

    /** 分区排序：整表重写 sortOrder（每次改动逐行埋点） */
    suspend fun reorderSections(ordered: List<SectionEntity>) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            ordered.forEachIndexed { index, section ->
                // 只动 sortOrder，其余字段以库内现值为准（防止拖拽排序顺手覆盖并发编辑）
                val cur = sectionDao.getById(section.id) ?: return@forEachIndexed
                val updated = cur.copy(sortOrder = index, versionSeq = cur.versionSeq + 1, updatedAt = now)
                sectionDao.update(updated)
                recordSectionUpsert(updated, baseSeq = cur.versionSeq)
            }
        }
    }

    // ---------- 分类 ----------

    /** 不分归属，按类型列出全部（明细页筛选等跨分区检索用） */
    fun observeCategories(type: Int): Flow<List<CategoryEntity>> = categoryDao.observeByType(type)

    /** 候选口径唯一真源：给定「分区 + 类型」→ 同类型全局 + 同类型本分区专属（专属在前） */
    fun observeCandidates(sectionId: Long, type: Int): Flow<List<CategoryEntity>> =
        categoryDao.observeCandidates(sectionId, type)

    /** 全局分类（sectionId IS NULL） */
    fun observeGlobalCategories(type: Int): Flow<List<CategoryEntity>> =
        categoryDao.observeGlobalByType(type)

    /** 某分区的专属分类 */
    fun observeSectionCategories(sectionId: Long, type: Int): Flow<List<CategoryEntity>> =
        categoryDao.observeSectionByType(sectionId, type)

    suspend fun getCategory(categoryId: Long): CategoryEntity? = categoryDao.getById(categoryId)

    /** 新建时 `sortOrder` 落在 (类型, 归属) 作用域内；编辑时保持原值（Q-11：不做改归属） */
    suspend fun saveCategory(category: CategoryEntity): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        if (category.id == 0L) {
            val syncId = category.syncId.ifEmpty { newSyncId() }
            val row = category.copy(
                sortOrder = categoryDao.nextSortOrder(category.type, category.sectionId),
                syncId = syncId,
                versionSeq = 1,
                updatedAt = now,
            )
            val id = categoryDao.insert(row)
            recordCategoryUpsert(row, baseSeq = null)
            id
        } else {
            val cur = categoryDao.getById(category.id)
            if (cur != null && SectionFirstSeed.Unclassified.isUnclassified(cur)) {
                error("「未分类」不可编辑")
            }
            val baseSeq = cur?.versionSeq ?: category.versionSeq
            val syncId = (cur?.syncId ?: category.syncId).ifEmpty { newSyncId() }
            val row = category.copy(syncId = syncId, versionSeq = baseSeq + 1, updatedAt = now)
            categoryDao.update(row)
            recordCategoryUpsert(row, baseSeq = baseSeq)
            category.id
        }
    }

    /**
     * 删除分类（EC-03 / Q-05 / D-4）。兜底链（**同类型**）：
     * ① 同类型 + 同分区其余专属（仅当 target 为专属）→ ② 同类型全局其余 → ③ 同类型任意其余。
     * 三级都空且**有账目**时才拒绝并给可读原因；无账目则允许删除（允许 0 个全局分类，Q-05）。
     */
    suspend fun deleteCategory(categoryId: Long): Result<Unit> = deleteMutex.withLock {
        runCatching {
            db.withTransaction {
                val target = categoryDao.getById(categoryId) ?: error("分类不存在")
                val sameType = categoryDao.getAllByType(target.type)
                val fallback = pickFallback(target, sameType)
                val referencing = entryDao.countByCategory(categoryId)

                if (fallback == null) {
                    if (referencing > 0) {
                        error("该分类下还有 $referencing 笔账目，且没有同类型分类可承接；请先新建一个同类型分类")
                    }
                    categoryDao.delete(categoryId)
                } else {
                    if (referencing > 0) categoryDao.moveEntries(categoryId, fallback.id)
                    categoryDao.delete(categoryId)
                }
            }
        }
    }

    /** 兜底链：① 同分区其余专属 → ② 全局其余 → ③ 任意其余（跨分区允许，历史显示不受可见性影响） */
    private fun pickFallback(
        target: CategoryEntity,
        sameType: List<CategoryEntity>,
    ): CategoryEntity? {
        val others = sameType.filter { it.id != target.id }
        if (target.sectionId != null) {
            others.firstOrNull { it.sectionId == target.sectionId }?.let { return it }
        }
        others.firstOrNull { it.sectionId == null }?.let { return it }
        return others.firstOrNull()
    }

    /** 删除分类前的影响描述（供确认框展示「账目去向」，FR-41） */
    suspend fun categoryDeleteImpact(categoryId: Long): CategoryDeleteImpact {
        val target = categoryDao.getById(categoryId)
            ?: return CategoryDeleteImpact(0, null, "分类不存在")
        val sameType = categoryDao.getAllByType(target.type)
        val fallback = pickFallback(target, sameType)
        val referencing = entryDao.countByCategory(categoryId)
        val blocked = if (fallback == null && referencing > 0) {
            "该分类下还有 $referencing 笔账目，且没有同类型分类可承接；请先新建一个同类型分类"
        } else {
            null
        }
        return CategoryDeleteImpact(referencing, fallback, blocked)
    }

    /** 分类排序：作用域 (类型, 归属) 内整表重写（每次改动逐行埋点） */
    suspend fun reorderCategories(ordered: List<CategoryEntity>) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            ordered.forEachIndexed { index, category ->
                val cur = categoryDao.getById(category.id) ?: return@forEachIndexed
                val updated = cur.copy(sortOrder = index, versionSeq = cur.versionSeq + 1, updatedAt = now)
                categoryDao.update(updated)
                recordCategoryUpsert(updated, baseSeq = cur.versionSeq)
            }
        }
    }

    // ---------- 账目 ----------

    suspend fun getEntryFull(id: Long): EntryFull? = entryDao.getEntryFull(id)

    /** 大屏详情面板用：观察单条账目（编辑后自动刷新） */
    fun observeEntryFull(id: Long): Flow<EntryFull?> = entryDao.observeEntryFull(id)

    /** 「我的」页概览：账目 / 分区 / 分类 数量 */
    suspend fun counts(): Triple<Int, Int, Int> = Triple(
        entryDao.countAllEntries(),
        entryDao.countAllSections(),
        entryDao.countAllCategories(),
    )

    /** 导出用：把全部账目拍平成可写入 CSV 的行 */
    suspend fun allEntriesForExport(): List<ExportRow> =
        entryDao.allEntriesFull().map { full -> entryToExportRow(full) }

    /**
     * 选图回调时立即调用：把系统相册返回的 Uri 读入缓存待入库目录。
     * 必须在这一刻读取——Photo Picker 授予的 Uri 读权限是短时效的。
     */
    suspend fun importPendingImage(uri: Uri): String? = imageStorage.importToPending(uri)

    /** 新建 / 编辑并保存（含贴图同步 + 同事务埋点），返回条目 id */
    suspend fun saveEntry(draft: EntryDraft): Long {
        // 1) 事务外先把待入库图片移入正式目录（内容寻址 <sha256>.jpg；文件操作不占事务）
        val promoted = draft.pendingImagePaths.map { path -> path to imageStorage.promoteToStorage(path) }
        val imported = promoted.mapNotNull { it.second }
        val importedPaths = imported.map { it.path }
        val failedPendingPaths = promoted.filter { it.second == null }.map { it.first }
        val keptSet = (draft.keptImagePaths + importedPaths).toSet()

        return try {
            // 2) 事务内写库 + 埋点，保证账目 / 贴图记录 / 操作三者一致
            val (entryId, removedFiles) = db.withTransaction {
                val now = System.currentTimeMillis()
                val existing = draft.id?.let { entryDao.getEntryFull(it)?.entry }
                val entrySyncId = existing?.syncId?.ifEmpty { newSyncId() } ?: newSyncId()
                val baseSeq = existing?.versionSeq
                val seq = (baseSeq ?: 0L) + 1
                val entry = EntryEntity(
                    id = draft.id ?: 0L,
                    type = draft.type,
                    amountCents = draft.amountCents,
                    categoryId = draft.categoryId,
                    sectionId = draft.sectionId,
                    entryTime = draft.entryTime,
                    note = draft.note.trim(),
                    // 编辑保留核对 / 报销 / 成员标签（v5：同步快照必须保真，不得随保存清空）
                    reconciled = existing?.reconciled ?: false,
                    reimburseState = existing?.reimburseState ?: 0,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    syncId = entrySyncId,
                    versionSeq = seq,
                    memberId = existing?.memberId ?: ops.currentMemberId(),
                )
                val savedId = if (draft.id == null) {
                    entryDao.insertEntry(entry)
                } else {
                    entryDao.updateEntry(entry)
                    draft.id
                }
                recordEntryUpsert(entry, baseSeq = baseSeq)

                // 同步贴图：保留用户留下的 + 新入库的，其余记录删除（逐张埋点 DELETE）
                val current = entryDao.imagesOf(savedId)
                val removed = current.filter { it.filePath !in keptSet }
                removed.forEach { img ->
                    val imgBase = img.versionSeq.coerceAtLeast(1L)
                    ops.onDelete(
                        RowKind.IMAGE,
                        img.syncId.ifEmpty { newSyncId() },
                        imgBase,
                        OpCodec.withDeletedAt(
                            OpCodec.imageSnapshot(entrySyncId, img.contentHash, img.sortOrder),
                            now,
                        ),
                    )
                }
                if (keptSet.isEmpty()) {
                    // NOT IN () 在 SQLite 中是非法语法，空集合必须走单独分支
                    entryDao.deleteImagesOf(savedId)
                } else {
                    entryDao.removeImagesNotIn(savedId, keptSet.toList())
                }

                val keptCount = draft.keptImagePaths.size
                imported.forEachIndexed { index, img ->
                    val sortOrder = keptCount + index
                    val imgSyncId = newSyncId()
                    entryDao.insertImages(
                        listOf(
                            EntryImageEntity(
                                entryId = savedId,
                                filePath = img.path,
                                sortOrder = sortOrder,
                                syncId = imgSyncId,
                                versionSeq = 1,
                                updatedAt = now,
                                contentHash = img.contentHash,
                            )
                        )
                    )
                    ops.onUpsert(
                        RowKind.IMAGE,
                        imgSyncId,
                        seq = 1,
                        baseSeq = null,
                        snapshot = OpCodec.imageSnapshot(entrySyncId, img.contentHash, sortOrder),
                    )
                }
                savedId to removed.map { it.filePath to it.contentHash }
            }

            // 3) 事务提交后再做文件清理（文件 IO 在事务外）。A2 照片挂起：
            //    内容寻址文件只有在「业务引用为零 **且** 未消化留底引用也为零」时
            //    才物理删除（PhotoRetention：误宽不误漏——多留一会儿无害，漏判丢照片）
            removedFiles.forEach { (path, hash) ->
                if (hash.isEmpty() || PhotoRetention.shouldDeleteFile(
                        entryDao.countByContentHash(hash),
                        syncDao.countVisibleTrashRefs(hash),
                    )
                ) {
                    imageStorage.deleteFiles(listOf(path))
                }
            }
            if (failedPendingPaths.isNotEmpty()) {
                imageStorage.deleteFiles(failedPendingPaths)
            }
            entryId
        } catch (throwable: Throwable) {
            // 事务回滚时回收本次新入库的图片文件；A2 照片挂起：双重引用归零才物理删
            imported.forEach { img ->
                if (img.contentHash.isEmpty() || PhotoRetention.shouldDeleteFile(
                        entryDao.countByContentHash(img.contentHash),
                        syncDao.countVisibleTrashRefs(img.contentHash),
                    )
                ) {
                    imageStorage.deleteFiles(listOf(img.path))
                }
            }
            throw throwable
        }
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
     * 长按菜单：切换**核对**维度（同事务埋点；行 versionSeq + 1 由定向 SQL 完成）。
     *
     * 与 [setEntryReimburseState] 是两个独立事务，互不牵连 —— 这正是 v4 把「核对 / 报销」
     * 拆成两个正交字段的目的（一笔装修支出完全可以「已核对」且「待报销」）。
     */
    suspend fun setEntryReconciled(id: Long, value: Boolean) {
        db.withTransaction {
            val entry = entryDao.getEntryFull(id)?.entry ?: return@withTransaction
            val now = System.currentTimeMillis()
            entryDao.updateReconciled(id, value, now) // SQL 内 versionSeq = versionSeq + 1
            recordEntryUpsert(
                entry.copy(reconciled = value, updatedAt = now, versionSeq = entry.versionSeq + 1),
                baseSeq = entry.versionSeq,
            )
        }
    }

    /** 长按菜单：设置**报销**维度（[ReimburseState.NONE] / PENDING / CLEARED），埋点口径同上 */
    suspend fun setEntryReimburseState(id: Long, value: Int) {
        db.withTransaction {
            val entry = entryDao.getEntryFull(id)?.entry ?: return@withTransaction
            val now = System.currentTimeMillis()
            entryDao.updateReimburseState(id, value, now)
            recordEntryUpsert(
                entry.copy(reimburseState = value, updatedAt = now, versionSeq = entry.versionSeq + 1),
                baseSeq = entry.versionSeq,
            )
        }
    }

    /**
     * 删除账目并返回可恢复的快照（贴图文件先移入暂存区，不直接销毁）。
     * 长按删除按设计规格是「不弹确认 + 4 秒撤销」，所以必须留得住这份数据。
     *
     * v5：删除在事务内逐行埋点（ENTRY + IMAGE 各一条 DELETE，载荷含 `_deletedAt`）；
     * 快照携带 syncId / versionSeq / deleteOpId 供 [restoreEntry] 双路径恢复。
     * 共享内容文件（`contentHash` 引用计数 > 1）**不暂存**——其余账目还指着它。
     */
    suspend fun deleteEntryWithSnapshot(id: Long): DeletedEntrySnapshot? {
        val full = entryDao.getEntryFull(id) ?: return null
        val prepared = db.withTransaction {
            val now = System.currentTimeMillis()
            val entry = full.entry
            val entrySyncId = entry.syncId.ifEmpty { newSyncId() }
            val categorySyncId = full.category?.syncId ?: categoryDao.getById(entry.categoryId)?.syncId ?: ""
            val sectionSyncId = full.section?.syncId ?: sectionDao.getById(entry.sectionId)?.syncId ?: ""
            val images = full.images.sortedBy { it.sortOrder }

            val imageInfos = images.map { img ->
                val shared = img.contentHash.isNotEmpty() && entryDao.countByContentHash(img.contentHash) > 1
                val imgSyncId = img.syncId.ifEmpty { newSyncId() }
                val imgBase = img.versionSeq.coerceAtLeast(1L)
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

            val entryBase = entry.versionSeq.coerceAtLeast(1L)
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
     * 暂存贴图文件在事务提交后归位（文件 IO 在事务外）。
     */
    suspend fun restoreEntry(snapshot: DeletedEntrySnapshot): Long? = runCatching {
        val opIds = (listOf(snapshot.entryDeleteOpId) + snapshot.images.map { it.deleteOpId })
            .filter { it.isNotEmpty() }
        val cleanUndo = opIds.all { opId ->
            syncDao.getOp(opId)?.let { it.origin == OpOrigin.LOCAL && !it.uploaded } ?: true
        }

        val entryId = if (cleanUndo) {
            db.withTransaction {
                if (opIds.isNotEmpty()) {
                    syncDao.removePendingOps(opIds)
                    opIds.forEach { syncDao.deleteTrash(it) }
                }
                insertRestoredRows(
                    snapshot,
                    entryVersionSeq = snapshot.versionSeq,
                    imageVersionSeqs = snapshot.images.map { it.versionSeq },
                )
            }
        } else {
            db.withTransaction {
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
                        categorySyncId = categoryDao.getById(snapshot.categoryId)?.syncId ?: "",
                        sectionSyncId = sectionDao.getById(snapshot.sectionId)?.syncId ?: "",
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

    /** 撤销窗口过期后清理暂存文件 */
    suspend fun discardParkedImages() = imageStorage.cleanParkedFiles()

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
            recordEntryUpsert(updated, baseSeq = entry.versionSeq)
        }
        return targetId
    }

    // ---------- U-6 存量照片 contentHash 补算（T-4，启动后台一次） ----------

    /**
     * v4 存量贴图的 contentHash 补算（U-6）：读文件 → sha256 → 落内容寻址路径
     * （`filesDir/images/<sha256>.jpg`）→ 回填 `contentHash` 并**同事务**记一条
     * IMAGE UPSERT（hash 是贴图行的语义字段，补算结果必须能同步到其余设备，
     * 否则对端永远拿不到照片内容哈希）。
     *
     * 口径：
     * - 只处理 `contentHash` 为空且文件存在的行；重复调用零副作用（幂等）；
   * - 文件 IO 全部在事务**外**（§7-9）：先复制到内容寻址路径，逐行小事务回填，
     *   最后统一清理「不再被任何行引用」的旧路径——同文件被多行共享（v4 允许）
     *   时复制式迁移保证不互踩；
     * - 静默进度（不打扰用户）；返回补算行数。
     */
    suspend fun backfillImageHashes(): Int {
        val pending = entryDao.imagesWithoutHash()
        if (pending.isEmpty()) return 0
        val oldPaths = mutableSetOf<String>()
        var count = 0
        for (img in pending) {
            if (img.filePath.isEmpty()) continue
            val file = File(img.filePath)
            if (!file.exists()) continue
            val bytes = file.readBytes()
            val hash = sha256HexOf(bytes)
            val target = imageStorage.pathForHash(hash)
            if (target != img.filePath) {
                val targetFile = File(target)
                targetFile.parentFile?.mkdirs()
                if (!targetFile.exists()) targetFile.writeBytes(bytes) // 复制式迁移，旧文件最后统一清
                oldPaths += img.filePath
            }
            val entrySyncId = entryDao.getEntryFull(img.entryId)?.entry?.syncId ?: continue
            val baseSeq = img.versionSeq
            db.withTransaction {
                entryDao.updateImageBackfill(img.id, hash, target)
                ops.onUpsert(
                    RowKind.IMAGE,
                    img.syncId,
                    seq = baseSeq + 1,
                    baseSeq = baseSeq,
                    snapshot = OpCodec.imageSnapshot(entrySyncId, hash, img.sortOrder),
                )
            }
            count++
        }
        if (oldPaths.isNotEmpty()) {
            val remaining = entryDao.allImages().map { it.filePath }.toSet()
            oldPaths
                .filter { it !in remaining }
                .forEach { path -> runCatching { File(path).delete() } }
        }
        return count
    }

    // ---------- 成员（U-2/R-20，T-5：改名 / 隐藏走同一套「事务 + 埋点」范式） ----------

    /**
     * 成员改名（R-20：历史账目经 `EntryFull.member` 关系取名，改完即时生效，无需回写账目）。
     * 全书唯一名：撞名 / 空名拒绝（返回 false，UI 提示换名）；同事务记 MEMBER UPSERT
     * （seq = 旧值 + 1、baseSeq = 旧值，随操作日志同步到其余设备）。
     */
    suspend fun renameMember(syncId: String, newName: String): Boolean = db.withTransaction {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@withTransaction false
        val cur = syncDao.getMember(syncId) ?: return@withTransaction false
        if (trimmed != cur.name && syncDao.findMemberByName(trimmed) != null) {
            return@withTransaction false // 撞名拒绝（U-2 全书唯一）
        }
        val updated = cur.copy(
            name = trimmed,
            versionSeq = cur.versionSeq + 1,
            updatedAt = System.currentTimeMillis(),
        )
        syncDao.upsertMember(updated)
        ops.onUpsert(
            rowKind = RowKind.MEMBER,
            rowSyncId = updated.syncId,
            seq = updated.versionSeq,
            baseSeq = cur.versionSeq,
            snapshot = OpCodec.memberSnapshot(updated.name, updated.hidden, updated.createdAt),
        )
        true
    }

    /**
     * 成员隐藏 / 取消隐藏（成员**可隐藏不可删**——主理人拍板；隐藏后记账选择器不展示，
     * 历史标签保留）。同事务记 MEMBER UPSERT，口径同 [renameMember]。
     */
    suspend fun setMemberHidden(syncId: String, hidden: Boolean): Boolean = db.withTransaction {
        val cur = syncDao.getMember(syncId) ?: return@withTransaction false
        val updated = cur.copy(
            hidden = hidden,
            versionSeq = cur.versionSeq + 1,
            updatedAt = System.currentTimeMillis(),
        )
        syncDao.upsertMember(updated)
        ops.onUpsert(
            rowKind = RowKind.MEMBER,
            rowSyncId = updated.syncId,
            seq = updated.versionSeq,
            baseSeq = cur.versionSeq,
            snapshot = OpCodec.memberSnapshot(updated.name, updated.hidden, updated.createdAt),
        )
        true
    }

    // ---------- v5 埋点辅助（必须在调用方事务内执行） ----------

    /** 新行 syncId：UUID 32 字符小写（跨设备身份，永不变） */
    private fun newSyncId(): String = UUID.randomUUID().toString().replace("-", "")

    private suspend fun recordSectionUpsert(row: SectionEntity, baseSeq: Long?) {
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

    private suspend fun recordSectionDelete(row: SectionEntity, now: Long) {
        ops.onDelete(
            RowKind.SECTION,
            row.syncId.ifEmpty { newSyncId() },
            row.versionSeq.coerceAtLeast(1L),
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

    private suspend fun recordCategoryUpsert(row: CategoryEntity, baseSeq: Long?) {
        ops.onUpsert(
            RowKind.CATEGORY,
            row.syncId,
            seq = row.versionSeq,
            baseSeq = baseSeq,
            snapshot = OpCodec.categorySnapshot(
                name = row.name,
                iconId = row.iconId,
                type = row.type,
                sectionSyncId = row.sectionId?.let { sectionDao.getById(it)?.syncId },
                sortOrder = row.sortOrder,
            ),
        )
    }

    private suspend fun recordCategoryDelete(row: CategoryEntity, now: Long) {
        ops.onDelete(
            RowKind.CATEGORY,
            row.syncId.ifEmpty { newSyncId() },
            row.versionSeq.coerceAtLeast(1L),
            OpCodec.withDeletedAt(
                OpCodec.categorySnapshot(
                    name = row.name,
                    iconId = row.iconId,
                    type = row.type,
                    sectionSyncId = row.sectionId?.let { sectionDao.getById(it)?.syncId },
                    sortOrder = row.sortOrder,
                ),
                now,
            ),
        )
    }

    private suspend fun recordEntryUpsert(row: EntryEntity, baseSeq: Long?) {
        ops.onUpsert(
            RowKind.ENTRY,
            row.syncId,
            seq = row.versionSeq,
            baseSeq = baseSeq,
            snapshot = OpCodec.entrySnapshot(
                type = row.type,
                amountCents = row.amountCents,
                categorySyncId = categoryDao.getById(row.categoryId)?.syncId ?: "",
                sectionSyncId = sectionDao.getById(row.sectionId)?.syncId ?: "",
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
 * 删除分区前给确认框用的影响描述（FR-40：同时说明「账目去向 + 分类去向」）。
 *
 * @param entryCount            该分区下的账目数
 * @param exclusiveCategoryCount 该分区的专属分类数（删除时将降级为全局）
 * @param fallback              账目将移入的分区；无其余分区时为 null
 * @param blockedReason         非 null 表示「当前不可删」及可读原因
 */
data class SectionDeleteImpact(
    val entryCount: Int,
    val exclusiveCategoryCount: Int,
    val fallback: SectionEntity?,
    val blockedReason: String?,
)

/**
 * 删除分类前给确认框用的影响描述（FR-41：说明「账目去向」，或给出可读阻塞原因）。
 *
 * @param entryCount    该分类下的账目数
 * @param fallback      账目将改挂的分类；无同类型分类可承接时为 null
 * @param blockedReason 非 null 表示「当前不可删」及可读原因
 */
data class CategoryDeleteImpact(
    val entryCount: Int,
    val fallback: CategoryEntity?,
    val blockedReason: String?,
)

/**
 * 把一条账目映射成 CSV 导出的一行（**纯函数**，便于单测这条不可动摇的隐私边界）。
 *
 * ⚠️ 硬性约束：`amountYuan` 必须**始终写真实金额**。
 * 「隐藏金额」只是显示层偏好（`LocalHideAmounts` / `AppSettings.hideAmounts`），
 * 让用户自己看不清数字，而**不**改变数据本身；导出的文件是用户的备份，必须完整。
 * 若让导出跟随隐私开关，用户会在毫不知情下得到一份缺金额的备份——这是数据丢失级事故。
 * 本函数刻意不接收任何 hidden 参数，从签名上就杜绝后人「顺手」让它跟随隐藏开关。
 * 对应的回归断言见 `ExportPrivacyTest`。
 */
/** SHA-256 小写 hex（U-6 补算的内容寻址口径，与 ImageStorage.promoteToStorage 一致） */
private fun sha256HexOf(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

internal fun entryToExportRow(full: EntryFull): ExportRow = ExportRow(
    date = DateTimes.toLocalDate(full.entry.entryTime).toString(),
    time = DateTimes.timeLabel(DateTimes.toLocalTime(full.entry.entryTime)),
    typeLabel = if (full.entry.type == EntryType.EXPENSE) "支出" else "收入",
    amountYuan = Money.formatCents(full.entry.amountCents).replace(",", ""),
    category = full.category?.name ?: "未分类",
    section = full.section?.name ?: "未分区",
    sectionNote = full.section?.note ?: "",
    note = full.entry.note,
    imageCount = full.images.size,
)
