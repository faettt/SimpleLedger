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
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.SectionTotal
import com.simpleledger.app.data.local.entity.TypeTotal
import com.simpleledger.app.logic.CategoryReorderRules
import com.simpleledger.app.logic.ImageOrderPlan
import com.simpleledger.app.logic.PhotoRetention
import com.simpleledger.app.logic.SectionUpsertPlan
import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpRecorder
import com.simpleledger.app.sync.op.RowKind
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.security.MessageDigest

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
 * 账本仓库：业务写路径 + **v5 同步埋点**（T-3）。对外门面（U-11 拆分后公开签名零变化）。
 *
 * 埋点铁律（§3.7/§7-9）：每次增删改与 [OpRecorder] 的操作追加在**同一个**数据库事务内
 * ——失败同回滚，绝不出现「行写了操作没记 / 操作记了行没写」；文件 IO 一律在事务外
 * （沿用 saveEntry 三段式）。行版本口径（U-3）：
 * - 新行：`syncId = UUID 32hex`、`versionSeq = 1`、UPSERT `baseSeq = null`；
 * - 编辑：`versionSeq = 旧值 + 1`、UPSERT `baseSeq = 旧值`；
 * - 删除：DELETE `baseSeq = 被删行 versionSeq`，载荷 = 行快照 + `_deletedAt`。
 *
 * 内部分工（U-11 拆分，行为零变化）：
 * - 本类：查询 / 保存写路径 / 排序 / 成员 / 照片哈希补算（写 + 埋点同事务由本类保证）；
 * - [LedgerDeleteRestore]：A1 整包删分区 / B4 分类去向迁移 / §3.8 删除撤销双路径 /
 *   恢复兜底（deleteMutex 随之迁移）；两者共用 [LedgerOpRecording] 的埋点辅助；
 * - [entryToExportRow]：导出纯函数（隐私边界，ExportPrivacyTest 钉死）独立成文。
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

    /** v5 埋点辅助（U-11 拆分；快照拼装 + 操作追加，必须在本仓库的事务内被调用） */
    private val recording = LedgerOpRecording(sectionDao, categoryDao, ops)

    /** 删除 / 恢复语义协作者（U-11 拆分；AppContainer 不感知） */
    private val deleteRestore = LedgerDeleteRestore(
        db = db,
        entryDao = entryDao,
        sectionDao = sectionDao,
        categoryDao = categoryDao,
        syncDao = syncDao,
        recording = recording,
        imageStorage = imageStorage,
    )

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
            recording.recordSectionUpsert(row, baseSeq = null)
            id
        } else {
            val cur = sectionDao.getById(section.id)
            val baseSeq = cur?.versionSeq ?: section.versionSeq
            val syncId = (cur?.syncId ?: section.syncId).ifEmpty { newSyncId() }
            // AU-5：行合并下沉到 SectionUpsertPlan（JVM 可测）——编辑分支必须回填
            // cur.sortOrder，否则 @Update 全列覆写把分区排序归 0 并经同步传染全部设备
            val row = SectionUpsertPlan.editRow(section, cur, syncId, now)
            sectionDao.update(row)
            recording.recordSectionUpsert(row, baseSeq = baseSeq)
            section.id
        }
    }

    /** 删除分区（A1 整包连删留底，语义见 [LedgerDeleteRestore.deleteSection]） */
    suspend fun deleteSection(sectionId: Long): Result<Unit> = deleteRestore.deleteSection(sectionId)

    /** 删除分区前的影响描述（A1 新口径，见 [LedgerDeleteRestore.sectionDeleteImpact]） */
    suspend fun sectionDeleteImpact(sectionId: Long): SectionDeleteImpact =
        deleteRestore.sectionDeleteImpact(sectionId)

    /** 分区排序：整表重写 sortOrder（每次改动逐行埋点） */
    suspend fun reorderSections(ordered: List<SectionEntity>) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            ordered.forEachIndexed { index, section ->
                // 只动 sortOrder，其余字段以库内现值为准（防止拖拽排序顺手覆盖并发编辑）
                val cur = sectionDao.getById(section.id) ?: return@forEachIndexed
                val updated = cur.copy(sortOrder = index, versionSeq = cur.versionSeq + 1, updatedAt = now)
                sectionDao.update(updated)
                recording.recordSectionUpsert(updated, baseSeq = cur.versionSeq)
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

    // ---------- B1「未分类」哨兵 ----------

    /**
     * 幂等补种「未分类」哨兵（启动时调用；新装已由 `AppDatabase.seed()` 写入）。
     * 补种行**不记操作**（与种子行同生命周期：初始导出 / 首次编辑才产生操作），
     * 避免两台设备各自补种出 seq=1 的 LWW 冲突留底。
     */
    suspend fun ensureUnclassified() {
        val existing = listOf(EntryType.EXPENSE, EntryType.INCOME)
            .flatMap { type -> categoryDao.getAllByType(type) }
            .map { it.syncId }
            .toSet()
        val missing = SectionFirstSeed.Unclassified.missingCategories(existing)
        if (missing.isEmpty()) return
        db.withTransaction {
            missing.forEach { row ->
                categoryDao.insert(
                    row.copy(sortOrder = categoryDao.nextSortOrder(row.type, null)),
                )
            }
        }
    }

    /** 解析「未分类」哨兵的本地 id（缺则现场补种；B2 记账不选分类的落点） */
    suspend fun unclassifiedCategoryId(type: Int): Long {
        val syncId = SectionFirstSeed.Unclassified.syncId(type)
        categoryDao.getBySyncId(syncId)?.let { return it.id }
        ensureUnclassified()
        return categoryDao.getBySyncId(syncId)?.id
            ?: error("「未分类」哨兵补种失败")
    }

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
            recording.recordCategoryUpsert(row, baseSeq = null)
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
            recording.recordCategoryUpsert(row, baseSeq = baseSeq)
            category.id
        }
    }

    /** 删除分类（B4 去向单选迁移，语义见 [LedgerDeleteRestore.deleteCategory]） */
    suspend fun deleteCategory(categoryId: Long, destinationId: Long? = null): Result<Unit> =
        deleteRestore.deleteCategory(categoryId, destinationId)

    /** 删除分类前的影响描述（B4/FR-41 去向单选，见 [LedgerDeleteRestore.categoryDeleteImpact]） */
    suspend fun categoryDeleteImpact(categoryId: Long): CategoryDeleteImpact =
        deleteRestore.categoryDeleteImpact(categoryId)

    /**
     * 分类排序：作用域 (类型, 归属) 内整表重写（每次改动逐行埋点）。
     *
     * F-3：「未分类」哨兵不参与用户排序——哨兵行整行忽略（位次不动、不 bump
     * versionSeq、不记 UPSERT），与 [CategoryDeletePlan]「未分类恒排末尾」同口径。
     */
    suspend fun reorderCategories(ordered: List<CategoryEntity>) {
        val plan = CategoryReorderRules.rewritePlan(ordered)
        db.withTransaction {
            val now = System.currentTimeMillis()
            plan.forEach { (id, sortOrder) ->
                val cur = categoryDao.getById(id) ?: return@forEach
                val updated = cur.copy(sortOrder = sortOrder, versionSeq = cur.versionSeq + 1, updatedAt = now)
                categoryDao.update(updated)
                recording.recordCategoryUpsert(updated, baseSeq = cur.versionSeq)
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

    /**
     * 「保存到相册」主入口：API 29+ 直写 MediaStore 返回 [GalleryExportOutcome.Saved]；
     * API 26–28 返回 NeedsSaf（建议名 + MIME），UI 拉起 CreateDocument 拿到目标 Uri 后
     * 调 [writeExportImageToSafTarget] 落盘。批次 B（贴图 UI）消费。
     */
    suspend fun exportImageToGallery(imagePath: String): GalleryExportOutcome =
        imageStorage.exportImageToGallery(imagePath)

    /** 「保存到相册」SAF 续篇：把贴图原字节写入 CreateDocument 选中的目标 Uri，写失败返回 false */
    suspend fun writeExportImageToSafTarget(target: Uri, imagePath: String): Boolean =
        imageStorage.writeExportImageToSafTarget(target, imagePath)

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
                recording.recordEntryUpsert(entry, baseSeq = baseSeq)

                // 同步贴图：保留用户留下的 + 新入库的，其余记录删除（逐张埋点 DELETE）
                val current = entryDao.imagesOf(savedId)
                val removed = current.filter { it.filePath !in keptSet }
                removed.forEach { img ->
                    val imgBase = img.versionSeq // U-13：真实 versionSeq，可为 0
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

                // AU-4：保留行按编辑器顺序（draft.keptImagePaths）重写 sortOrder = 0..k-1。
                // 旧实现保留行沿用旧序号、新图从 keptCount 起编号——删靠前旧图再追加时
                // 新图序号撞进保留图中间，保存后展示顺序被打乱（违背 keptImagePaths
                // 「顺序即展示顺序」契约）。sortOrder 是贴图行语义字段，改写的行逐条
                // 记 UPSERT（seq = 旧值 + 1、baseSeq = 旧值）同步到其余设备。
                val keptRowByPath = entryDao.imagesOf(savedId).associateBy { it.filePath }
                val renumber = ImageOrderPlan.renumberPlan(
                    draft.keptImagePaths,
                    keptRowByPath.mapValues { it.value.sortOrder },
                )
                renumber.forEach { (path, newOrder) ->
                    val row = keptRowByPath.getValue(path)
                    val imgSyncId = row.syncId.ifEmpty { newSyncId() }
                    val updated = row.copy(
                        sortOrder = newOrder,
                        syncId = imgSyncId,
                        versionSeq = row.versionSeq + 1,
                        updatedAt = now,
                    )
                    entryDao.updateImage(updated)
                    ops.onUpsert(
                        RowKind.IMAGE,
                        imgSyncId,
                        seq = updated.versionSeq,
                        baseSeq = row.versionSeq,
                        snapshot = OpCodec.imageSnapshot(entrySyncId, row.contentHash, newOrder),
                    )
                }

                val keptCount = ImageOrderPlan.nextOrderForImported(draft.keptImagePaths)
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
    suspend fun deleteEntry(id: Long) = deleteRestore.deleteEntry(id)

    /** 删除账目并返回可恢复快照（语义见 [LedgerDeleteRestore.deleteEntryWithSnapshot]） */
    suspend fun deleteEntryWithSnapshot(id: Long): DeletedEntrySnapshot? =
        deleteRestore.deleteEntryWithSnapshot(id)

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
            recording.recordEntryUpsert(
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
            recording.recordEntryUpsert(
                entry.copy(reimburseState = value, updatedAt = now, versionSeq = entry.versionSeq + 1),
                baseSeq = entry.versionSeq,
            )
        }
    }

    /** 撤销删除（§3.8 双路径，语义见 [LedgerDeleteRestore.restoreEntry]） */
    suspend fun restoreEntry(snapshot: DeletedEntrySnapshot): Long? = deleteRestore.restoreEntry(snapshot)

    /** 撤销窗口过期后清理暂存文件 */
    suspend fun discardParkedImages() = imageStorage.cleanParkedFiles()

    /** 恢复后的「原分区已死」兜底（语义见 [LedgerDeleteRestore.rehomeRestoredEntry]） */
    suspend fun rehomeRestoredEntry(entrySyncId: String): Long? =
        deleteRestore.rehomeRestoredEntry(entrySyncId)

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
}

/** SHA-256 小写 hex（U-6 补算的内容寻址口径，与 ImageStorage.promoteToStorage 一致） */
private fun sha256HexOf(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
