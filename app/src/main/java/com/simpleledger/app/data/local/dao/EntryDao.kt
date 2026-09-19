package com.simpleledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryImageEntity
import com.simpleledger.app.data.local.entity.SectionTotal
import com.simpleledger.app.data.local.entity.TypeTotal
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {

    /** 时间范围内的账目（可按分区 / 分类 / 类型过滤），带关联信息 */
    @Transaction
    @Query(
        """
        SELECT * FROM entries
        WHERE entryTime >= :start AND entryTime < :end
          AND (:sectionId IS NULL OR sectionId = :sectionId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:type IS NULL OR type = :type)
        ORDER BY entryTime DESC, id DESC
        """
    )
    fun observeEntries(
        start: Long,
        end: Long,
        sectionId: Long?,
        categoryId: Long?,
        type: Int?,
    ): Flow<List<EntryFull>>

    /** 按类型汇总（不含类型过滤，两种一起返回） */
    @Query(
        """
        SELECT type, COALESCE(SUM(amountCents), 0) AS total
        FROM entries
        WHERE entryTime >= :start AND entryTime < :end
          AND (:sectionId IS NULL OR sectionId = :sectionId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        GROUP BY type
        """
    )
    fun observeTypeTotals(
        start: Long,
        end: Long,
        sectionId: Long?,
        categoryId: Long?,
    ): Flow<List<TypeTotal>>

    /** 按分类汇总 */
    @Query(
        """
        SELECT e.categoryId AS categoryId, c.name AS name, c.emoji AS emoji,
               COALESCE(SUM(e.amountCents), 0) AS total, COUNT(*) AS count
        FROM entries e JOIN categories c ON c.id = e.categoryId
        WHERE e.entryTime >= :start AND e.entryTime < :end
          AND e.type = :type
          AND (:sectionId IS NULL OR e.sectionId = :sectionId)
        GROUP BY e.categoryId ORDER BY total DESC
        """
    )
    fun observeCategoryTotals(
        type: Int,
        start: Long,
        end: Long,
        sectionId: Long?,
    ): Flow<List<CategoryTotal>>

    /** 按分区汇总（含分区备注） */
    @Query(
        """
        SELECT e.sectionId AS sectionId, s.name AS name, s.emoji AS emoji, s.note AS note,
               COALESCE(SUM(CASE WHEN e.type = 0 THEN e.amountCents ELSE 0 END), 0) AS expense,
               COALESCE(SUM(CASE WHEN e.type = 1 THEN e.amountCents ELSE 0 END), 0) AS income,
               COUNT(*) AS count
        FROM entries e JOIN sections s ON s.id = e.sectionId
        WHERE e.entryTime >= :start AND e.entryTime < :end
        GROUP BY e.sectionId ORDER BY expense DESC
        """
    )
    fun observeSectionTotals(start: Long, end: Long): Flow<List<SectionTotal>>

    @Transaction
    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun getEntryFull(id: Long): EntryFull?

    @Query("SELECT * FROM entry_images WHERE entryId = :entryId ORDER BY sortOrder, id")
    suspend fun imagesOf(entryId: Long): List<EntryImageEntity>

    @Insert
    suspend fun insertEntry(entry: EntryEntity): Long

    @Update
    suspend fun updateEntry(entry: EntryEntity)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Insert
    suspend fun insertImages(images: List<EntryImageEntity>)

    /** 编辑时同步图片：删掉不在保留列表里的记录 */
    @Query(
        "DELETE FROM entry_images WHERE entryId = :entryId AND filePath NOT IN (:keptPaths)"
    )
    suspend fun removeImagesNotIn(entryId: Long, keptPaths: List<String>)

    /** 保留列表为空时使用，等价于清空该账目的全部贴图记录 */
    @Query("DELETE FROM entry_images WHERE entryId = :entryId")
    suspend fun deleteImagesOf(entryId: Long)

    @Query("SELECT COUNT(*) FROM entries WHERE categoryId = :categoryId")
    suspend fun countByCategory(categoryId: Long): Int

    @Query("SELECT COUNT(*) FROM entries WHERE sectionId = :sectionId")
    suspend fun countBySection(sectionId: Long): Int
}
