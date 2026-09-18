package com.simpleledger.app.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.simpleledger.app.data.local.AppDatabase
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryImageEntity
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.SectionTotal
import com.simpleledger.app.data.local.entity.TypeTotal
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
    /** 新增图片的来源 Uri */
    val newImageUris: List<Uri> = emptyList(),
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

    /** 新建 / 编辑并保存（含贴图同步），返回条目 id */
    suspend fun saveEntry(draft: EntryDraft): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val entry = EntryEntity(
            id = draft.id ?: 0L,
            type = draft.type,
            amountCents = draft.amountCents,
            categoryId = draft.categoryId,
            sectionId = draft.sectionId,
            entryTime = draft.entryTime,
            note = draft.note.trim(),
            createdAt = if (draft.id == null) now else entryDao.getEntryFull(draft.id)?.entry?.createdAt ?: now,
            updatedAt = now,
        )
        val entryId = if (draft.id == null) {
            entryDao.insertEntry(entry)
        } else {
            entryDao.updateEntry(entry)
            draft.id
        }

        // 同步贴图：新图入库，取消选择的旧图删除（文件 + 记录）
        val importedPaths = draft.newImageUris.mapNotNull { imageStorage.import(it) }
        val currentImages = entryDao.imagesOf(entryId)
        val keptSet = (draft.keptImagePaths + importedPaths).toSet()
        val removedPaths = currentImages.map { it.filePath }.filter { it !in keptSet }
        entryDao.removeImagesNotIn(entryId, keptSet.toList())
        imageStorage.deleteFiles(removedPaths)

        val keptCount = draft.keptImagePaths.size
        entryDao.insertImages(
            importedPaths.mapIndexed { index, path ->
                EntryImageEntity(entryId = entryId, filePath = path, sortOrder = keptCount + index)
            }
        )
        entryId
    }

    suspend fun deleteEntry(id: Long) {
        val paths = entryDao.imagesOf(id).map { it.filePath }
        entryDao.deleteEntry(id) // 图片记录级联删除
        imageStorage.deleteFiles(paths)
    }
}
