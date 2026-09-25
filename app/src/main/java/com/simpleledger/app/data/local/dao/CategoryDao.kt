package com.simpleledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.simpleledger.app.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    /** 存量导出（RoomInitialExporter）：全部分类行 */
    @Query("SELECT * FROM categories ORDER BY id")
    suspend fun getAll(): List<CategoryEntity>

    /** 不分归属，按类型列出全部（跨分区检索 / 明细页筛选用） */
    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder, id")
    fun observeByType(type: Int): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder, id")
    suspend fun getAllByType(type: Int): List<CategoryEntity>

    /**
     * 候选分类（**唯一真源**）：给定「分区 + 类型」→ 同类型**全局** + 同类型**本分区专属**，
     * 排序「专属在前 → sortOrder → id」。UI 层不得再自行拼接 / 去重（同名不合并，Q-02）。
     */
    @Query(
        """
        SELECT * FROM categories
        WHERE type = :type
          AND (sectionId IS NULL OR sectionId = :sectionId)
        ORDER BY CASE WHEN sectionId IS NULL THEN 1 ELSE 0 END, sortOrder, id
        """
    )
    fun observeCandidates(sectionId: Long, type: Int): Flow<List<CategoryEntity>>

    /** 全局分类（sectionId IS NULL），供「全局分类管理」页 */
    @Query("SELECT * FROM categories WHERE type = :type AND sectionId IS NULL ORDER BY sortOrder, id")
    fun observeGlobalByType(type: Int): Flow<List<CategoryEntity>>

    /** 某分区的专属分类，供「分区管理」页 */
    @Query("SELECT * FROM categories WHERE type = :type AND sectionId = :sectionId ORDER BY sortOrder, id")
    fun observeSectionByType(sectionId: Long, type: Int): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    /** v5 同步身份查找（远端操作回放按 syncId 定位逻辑行） */
    @Query("SELECT * FROM categories WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): CategoryEntity?

    /** 某分区的全部专属分类（删除分区的逐行埋点用，T-3） */
    @Query("SELECT * FROM categories WHERE sectionId = :sectionId ORDER BY sortOrder, id")
    suspend fun listBySection(sectionId: Long): List<CategoryEntity>

    /**
     * 远端回放 upsert：按 syncId 有则更新、无则插入（理由同 [SectionDao.upsertRemote]）。
     * 原子性由外层 OpApplier 单事务保证。
     */
    suspend fun upsertRemote(row: CategoryEntity): Long {
        val existing = getBySyncId(row.syncId)
        return if (existing == null) {
            insert(row)
        } else {
            update(row.copy(id = existing.id))
            existing.id
        }
    }

    /** 远端回放删除（行整体死亡） */
    @Query("DELETE FROM categories WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    @Query("SELECT * FROM categories WHERE name = :name AND type = :type LIMIT 1")
    suspend fun findByName(name: String, type: Int): CategoryEntity?

    /** 该分区拥有的专属分类数量（供删除分区的影响描述） */
    @Query("SELECT COUNT(*) FROM categories WHERE sectionId = :sectionId")
    suspend fun countBySection(sectionId: Long): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE entries SET categoryId = :toCategoryId WHERE categoryId = :fromCategoryId")
    suspend fun moveEntries(fromCategoryId: Long, toCategoryId: Long)

    /**
     * 删除分区时把该分区专属分类降级为全局（Q-04）。返回受影响行数。
     * `categories.sectionId` 无 FK，若不显式降级会留下悬空 sectionId。
     */
    @Query("UPDATE categories SET sectionId = NULL WHERE sectionId = :sectionId")
    suspend fun detachFromSection(sectionId: Long): Int

    /**
     * 排序作用域收窄到 (类型, 归属)：全局与某分区的排序互相独立（EC-01）。
     * `sectionId` 为 null 表示全局。
     */
    @Query(
        """
        SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM categories
        WHERE type = :type
          AND ((:sectionId IS NULL AND sectionId IS NULL) OR sectionId = :sectionId)
        """
    )
    suspend fun nextSortOrder(type: Int, sectionId: Long?): Int
}
