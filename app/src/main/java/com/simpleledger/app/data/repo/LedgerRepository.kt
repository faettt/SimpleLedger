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

    fun observeSectionTotals(start: Long, end: Long): Flow<List<SectionTotal>> =
        entryDao.observeSectionTotals(start, end)

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

    suspend fun saveSection(section: SectionEntity): Long {
        return if (section.id == 0L) {
            sectionDao.insert(section.copy(sortOrder = sectionDao.nextSortOrder()))
        } else {
            sectionDao.update(section)
            section.id
        }
    }

    /**
     * 删除分区：其中账目自动移入排序最靠前的其余分区，
     * 若删除后不存在任何分区则拒绝（至少保留一个）。
     */
    suspend fun deleteSection(sectionId: Long): Result<Unit> = deleteMutex.withLock {
        db.withTransaction {
            val all = sectionDao.getAll()
            if (all.size <= 1) {
                Result.failure(IllegalStateException("至少保留一个分区"))
            } else {
                val fallback = all.first { it.id != sectionId }
                if (entryDao.countBySection(sectionId) > 0) {
                    sectionDao.moveEntries(sectionId, fallback.id)
                }
                sectionDao.delete(sectionId)
                Result.success(Unit)
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

    fun observeCategories(type: Int): Flow<List<CategoryEntity>> = categoryDao.observeByType(type)

    suspend fun saveCategory(category: CategoryEntity): Long {
        return if (category.id == 0L) {
            categoryDao.insert(category.copy(sortOrder = categoryDao.nextSortOrder(category.type)))
        } else {
            categoryDao.update(category)
            category.id
        }
    }

    /** 删除分类：相关账目自动移入同类型下的其余分类 */
    suspend fun deleteCategory(categoryId: Long): Result<Unit> = deleteMutex.withLock {
        db.withTransaction {
            val target = categoryDao.getById(categoryId)
            if (target == null) {
                Result.failure(IllegalStateException("分类不存在"))
            } else {
                val fallback = categoryDao.getAllByType(target.type).firstOrNull { it.id != categoryId }
                if (fallback == null) {
                    Result.failure(IllegalStateException("至少保留一个分类"))
                } else {
                    if (entryDao.countByCategory(categoryId) > 0) {
                        categoryDao.moveEntries(categoryId, fallback.id)
                    }
                    categoryDao.delete(categoryId)
                    Result.success(Unit)
                }
            }
        }
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
