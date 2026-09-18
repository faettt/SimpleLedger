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

    @Insert
    suspend fun insert(section: SectionEntity): Long

    @Update
    suspend fun update(section: SectionEntity)

    @Query("DELETE FROM sections WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE entries SET sectionId = :toSectionId WHERE sectionId = :fromSectionId")
    suspend fun moveEntries(fromSectionId: Long, toSectionId: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM sections")
    suspend fun nextSortOrder(): Int
}
