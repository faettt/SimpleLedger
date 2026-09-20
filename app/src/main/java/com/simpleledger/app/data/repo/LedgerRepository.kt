package com.simpleledger.app.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.simpleledger.app.data.export.ExportRow
import com.simpleledger.app.data.local.AppDatabase
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryImageEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.SectionTotal
import com.simpleledger.app.data.local.entity.TypeTotal
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

class LedgerRepository(
    private val db: AppDatabase,
    private val imageStorage: ImageStorage,
) {
    private val entryDao = db.entryDao()
    private val sectionDao = db.sectionDao()
    private val categoryDao = db.categoryDao()

    /** 删除分区 / 分类时的互斥锁，避免并发迁移兜底分类 */
    private val deleteMutex = Mutex()

    // ---------- 明细 / 统计查询 ----------

    fun observeEntries(
        start: Long,
        end: Long,
        sectionId: Long? = null,
        categoryId: Long? = null,
        type: Int? = null,
    ): Flow<List<EntryFull>> =
        entryDao.observeEntries(start, end, sectionId, categoryId, type)

    fun observeTypeTotals(
        start: Long,
        end: Long,
        sectionId: Long? = null,
        categoryId: Long? = null,
    ): Flow<List<TypeTotal>> = entryDao.observeTypeTotals(start, end, sectionId, categoryId)

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

    suspend fun saveSection(section: SectionEntity): Long {
        return if (section.id == 0L) {
            sectionDao.insert(section.copy(sortOrder = sectionDao.nextSortOrder()))
        } else {
            sectionDao.update(section)
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

    /** 分区排序：整表重写 sortOrder */
    suspend fun reorderSections(ordered: List<SectionEntity>) {
        db.withTransaction {
            ordered.forEachIndexed { index, section ->
                sectionDao.update(section.copy(sortOrder = index))
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
    suspend fun saveCategory(category: CategoryEntity): Long {
        return if (category.id == 0L) {
            categoryDao.insert(
                category.copy(sortOrder = categoryDao.nextSortOrder(category.type, category.sectionId))
            )
        } else {
            categoryDao.update(category)
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

    suspend fun reorderCategories(ordered: List<CategoryEntity>) {
        db.withTransaction {
            ordered.forEachIndexed { index, category ->
                categoryDao.update(category.copy(sortOrder = index))
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

    /** 新建 / 编辑并保存（含贴图同步），返回条目 id */
    suspend fun saveEntry(draft: EntryDraft): Long {
        // 1) 事务外先把待入库图片移入正式目录（文件操作，不占用数据库事务）
        val promoted = draft.pendingImagePaths.map { path -> path to imageStorage.promoteToStorage(path) }
        val importedPaths = promoted.mapNotNull { it.second }
        val failedPendingPaths = promoted.filter { it.second == null }.map { it.first }
        val keptSet = (draft.keptImagePaths + importedPaths).toSet()

        return try {
            // 2) 事务内写库，保证账目与贴图记录一致
            val (entryId, removedPaths) = db.withTransaction {
                val now = System.currentTimeMillis()
                val entry = EntryEntity(
                    id = draft.id ?: 0L,
                    type = draft.type,
                    amountCents = draft.amountCents,
                    categoryId = draft.categoryId,
                    sectionId = draft.sectionId,
                    entryTime = draft.entryTime,
                    note = draft.note.trim(),
                    createdAt = if (draft.id == null) {
                        now
                    } else {
                        entryDao.getEntryFull(draft.id)?.entry?.createdAt ?: now
                    },
                    updatedAt = now,
                )
                val savedId = if (draft.id == null) {
                    entryDao.insertEntry(entry)
                } else {
                    entryDao.updateEntry(entry)
                    draft.id
                }

                // 同步贴图：保留用户留下的 + 新入库的，其余记录删除
                val removed = entryDao.imagesOf(savedId)
                    .map { it.filePath }
                    .filter { it !in keptSet }
                if (keptSet.isEmpty()) {
                    // NOT IN () 在 SQLite 中是非法语法，空集合必须走单独分支
                    entryDao.deleteImagesOf(savedId)
                } else {
                    entryDao.removeImagesNotIn(savedId, keptSet.toList())
                }

                val keptCount = draft.keptImagePaths.size
                entryDao.insertImages(
                    importedPaths.mapIndexed { index, path ->
                        EntryImageEntity(entryId = savedId, filePath = path, sortOrder = keptCount + index)
                    }
                )
                savedId to removed
            }

            // 3) 事务提交后再做文件清理，保证回滚时不会提前丢文件
            if (removedPaths.isNotEmpty()) {
                imageStorage.deleteFiles(removedPaths)
            }
            if (failedPendingPaths.isNotEmpty()) {
                imageStorage.deleteFiles(failedPendingPaths)
            }
            entryId
        } catch (throwable: Throwable) {
            // 事务回滚时回收已入库的图片文件，避免留下孤儿文件
            if (importedPaths.isNotEmpty()) {
                imageStorage.deleteFiles(importedPaths)
            }
            throw throwable
        }
    }

    suspend fun deleteEntry(id: Long) {
        val paths = entryDao.imagesOf(id).map { it.filePath }
        entryDao.deleteEntry(id) // 图片记录级联删除
        imageStorage.deleteFiles(paths)
    }

    /**
     * 删除账目并返回可恢复的快照（贴图文件先移入暂存区，不直接销毁）。
     * 长按删除按设计规格是「不弹确认 + 4 秒撤销」，所以必须留得住这份数据。
     */
    suspend fun deleteEntryWithSnapshot(id: Long): DeletedEntrySnapshot? {
        val full = entryDao.getEntryFull(id) ?: return null
        val parked = imageStorage.parkFiles(full.images.map { it.filePath })
        entryDao.deleteEntry(id)
        return DeletedEntrySnapshot(
            type = full.entry.type,
            amountCents = full.entry.amountCents,
            categoryId = full.entry.categoryId,
            sectionId = full.entry.sectionId,
            entryTime = full.entry.entryTime,
            note = full.entry.note,
            parkedImagePaths = parked,
        )
    }

    /** 撤销删除：用暂存的贴图重新入库，账目 id 会变，其余字段保持不变 */
    suspend fun restoreEntry(snapshot: DeletedEntrySnapshot): Long? = runCatching {
        saveEntry(
            EntryDraft(
                id = null,
                type = snapshot.type,
                amountCents = snapshot.amountCents,
                categoryId = snapshot.categoryId,
                sectionId = snapshot.sectionId,
                entryTime = snapshot.entryTime,
                note = snapshot.note,
                pendingImagePaths = snapshot.parkedImagePaths,
            )
        )
    }.getOrNull()

    /** 撤销窗口过期后清理暂存文件 */
    suspend fun discardParkedImages() = imageStorage.cleanParkedFiles()
}

/** 删除账目时留下的快照，用于撤销窗口内完整恢复 */
data class DeletedEntrySnapshot(
    val type: Int,
    val amountCents: Long,
    val categoryId: Long,
    val sectionId: Long,
    val entryTime: Long,
    val note: String,
    val parkedImagePaths: List<String>,
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
