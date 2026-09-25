package com.simpleledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.simpleledger.app.data.local.entity.SectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SectionDao {

    @Query("SELECT * FROM sections ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<SectionEntity>>

    @Query("SELECT * FROM sections ORDER BY sortOrder, id")
    suspend fun getAll(): List<SectionEntity>

    @Query("SELECT * FROM sections WHERE id = :id")
    suspend fun getById(id: Long): SectionEntity?

    /** v5 同步身份查找（远端操作回放按 syncId 定位逻辑行） */
    @Query("SELECT * FROM sections WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): SectionEntity?

    @Insert
    suspend fun insert(section: SectionEntity): Long

    @Update
    suspend fun update(section: SectionEntity)

    /**
     * 远端回放 upsert：按 syncId 有则更新（保留本地 Long id）、无则插入。
     * 刻意不用 `@Insert(REPLACE)`——autoGenerate id=0 会每次造新行，破坏按 syncId 幂等。
     * 原子性由外层 OpApplier 单事务保证（本方法不做嵌套事务）。
     */
    suspend fun upsertRemote(row: SectionEntity): Long {
        val existing = getBySyncId(row.syncId)
        return if (existing == null) {
            insert(row)
        } else {
            update(row.copy(id = existing.id))
            existing.id
        }
    }

    @Query("DELETE FROM sections WHERE id = :id")
    suspend fun delete(id: Long)

    /** 远端回放删除（行整体死亡） */
    @Query("DELETE FROM sections WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    @Query("UPDATE entries SET sectionId = :toSectionId WHERE sectionId = :fromSectionId")
    suspend fun moveEntries(fromSectionId: Long, toSectionId: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM sections")
    suspend fun nextSortOrder(): Int
}
