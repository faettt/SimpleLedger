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

    /**
     * 时间范围内的账目（可按分区 / 分类 / 类型 / **双维状态**过滤），带关联信息。
     *
     * `reconciled` / `reimburseState` 传 null 表示该维不筛。两个参数**互相独立**
     * （A2/D4：核对与报销是两个正交字段），所以「待核对 + 待报销」这种组合天然可用。
     *
     * `(:x IS NULL OR col = :x)` 这个写法是为了让「不筛」与「筛值 0」可区分：
     * 若用 `col = COALESCE(:x, col)`，筛 `reimburseState = 0`（不适用）会退化成不筛。
     */
    @Transaction
    @Query(
        """
        SELECT * FROM entries
        WHERE entryTime >= :start AND entryTime < :end
          AND (:sectionId IS NULL OR sectionId = :sectionId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:type IS NULL OR type = :type)
          AND (:reconciled IS NULL OR reconciled = :reconciled)
          AND (:reimburseState IS NULL OR reimburseState = :reimburseState)
        ORDER BY entryTime DESC, id DESC
        """
    )
    fun observeEntries(
        start: Long,
        end: Long,
        sectionId: Long?,
        categoryId: Long?,
        type: Int?,
        reconciled: Boolean?,
        reimburseState: Int?,
    ): Flow<List<EntryFull>>

    /**
     * 按类型汇总（不含类型过滤，两种一起返回）。
     *
     * 条件与 [observeEntries] **逐项一致**：概览卡的三个数必须等于列表里那些账目
     * 加起来的结果。若这里少一个条件，用户筛「待报销」后就会看到列表 3 笔、
     * 概览却是整月的钱，两个数字当场互相打脸。
     */
    @Query(
        """
        SELECT type, COALESCE(SUM(amountCents), 0) AS total
        FROM entries
        WHERE entryTime >= :start AND entryTime < :end
          AND (:sectionId IS NULL OR sectionId = :sectionId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:reconciled IS NULL OR reconciled = :reconciled)
          AND (:reimburseState IS NULL OR reimburseState = :reimburseState)
        GROUP BY type
        """
    )
    fun observeTypeTotals(
        start: Long,
        end: Long,
        sectionId: Long?,
        categoryId: Long?,
        reconciled: Boolean?,
        reimburseState: Int?,
    ): Flow<List<TypeTotal>>

    /**
     * 分类占比：**按分类 id 聚合** + `LEFT JOIN sections` 带出归属字段（Q-09 消歧）。
     * 专属分类会带出所属分区名 / 图标 / 胶带色；全局分类三列为 NULL。
     *
     * v4：图标与颜色都是索引（`iconId` 1–50 / `colorIndex` 0–7），不再是 emoji 字符串。
     */
    @Query(
        """
        SELECT e.categoryId AS categoryId, c.name AS name, c.iconId AS iconId,
               c.sectionId AS sectionId, s.name AS sectionName,
               s.iconId AS sectionIconId, s.colorIndex AS sectionColorIndex,
               COALESCE(SUM(e.amountCents), 0) AS total, COUNT(*) AS count
        FROM entries e
        JOIN categories c ON c.id = e.categoryId
        LEFT JOIN sections s ON s.id = c.sectionId
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

    /**
     * 分区首屏「含空分区」汇总（本月支出 / 收入 / 笔数）。
     *
     * **必须用 LEFT JOIN sections**：空分区（本月无账目，甚至从未记账）也要出现在首屏，
     * 否则用户看不到自己新建的分区。用 `COUNT(e.id)` 而非 `COUNT(*)`——LEFT JOIN 未命中时
     * `e.*` 全为 NULL，`COUNT(*)` 会得到 1，导致空分区笔数错报为 1。
     */
    @Query(
        """
        SELECT s.id AS sectionId, s.name AS name, s.iconId AS iconId, s.colorIndex AS colorIndex,
               s.note AS note, s.budgetCents AS budgetCents,
               COALESCE(SUM(CASE WHEN e.type = 0 THEN e.amountCents ELSE 0 END), 0) AS expense,
               COALESCE(SUM(CASE WHEN e.type = 1 THEN e.amountCents ELSE 0 END), 0) AS income,
               COUNT(e.id) AS count
        FROM sections s
        LEFT JOIN entries e
          ON e.sectionId = s.id AND e.entryTime >= :start AND e.entryTime < :end
        GROUP BY s.id
        ORDER BY s.sortOrder, s.id
        """
    )
    fun observeSectionOverview(start: Long, end: Long): Flow<List<SectionTotal>>

    /**
     * 全局搜索：跨全部时间，四类匹配 —— 账目备注、分类名、分区名 / 分区备注、金额数字。
     * 金额用「分的字符串包含关键词」实现（搜 2000 既命中 ¥2,000.00 也命中 ¥20.00 的分值，
     * 符合"我记过一笔 2000 的"这类模糊回忆）。上限 200 条防止极端关键词拖慢 UI。
     *
     * ⚠️ `LIMIT` 与 `LedgerViewModel.SEARCH_RESULT_LIMIT` 必须保持一致：UI 依赖该上限
     * 判断结果是否被截断，不一致会让「仅显示前 N 条」的提示漏报或误报。
     */
    @Transaction
    @Query(
        """
        SELECT * FROM entries e
        WHERE (:type IS NULL OR e.type = :type)
          AND (
            e.note LIKE :like ESCAPE '\'
            OR EXISTS (SELECT 1 FROM categories c WHERE c.id = e.categoryId AND c.name LIKE :like ESCAPE '\')
            OR EXISTS (SELECT 1 FROM sections s WHERE s.id = e.sectionId
                       AND (s.name LIKE :like ESCAPE '\' OR s.note LIKE :like ESCAPE '\'))
            OR CAST(e.amountCents AS TEXT) LIKE :like ESCAPE '\'
          )
        ORDER BY e.entryTime DESC, e.id DESC
        LIMIT 200
        """
    )
    fun observeSearch(like: String, type: Int?): Flow<List<EntryFull>>

    @Transaction
    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun getEntryFull(id: Long): EntryFull?

    // ---------- v5 同步身份（远端回放 / 内容寻址，T-3） ----------

    /** 按同步身份定位逻辑行（远端操作回放） */
    @Query("SELECT * FROM entries WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): EntryEntity?

    /** 按同步身份定位贴图行 */
    @Query("SELECT * FROM entry_images WHERE syncId = :syncId")
    suspend fun getImageBySyncId(syncId: String): EntryImageEntity?

    /** 删除分区/分类时逐行迁移 + 埋点用 */
    @Query("SELECT * FROM entries WHERE sectionId = :sectionId ORDER BY entryTime, id")
    suspend fun listBySection(sectionId: Long): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE categoryId = :categoryId ORDER BY entryTime, id")
    suspend fun listByCategory(categoryId: Long): List<EntryEntity>

    /**
     * 某分类下账目所在分区的全集（AU-3 删除分类去向候选的分区合法性收窄用；
     * 含 0 占位——挂死分区的账目只能迁去全局分类）。
     */
    @Query("SELECT DISTINCT sectionId FROM entries WHERE categoryId = :categoryId")
    suspend fun sectionIdsOfCategory(categoryId: Long): List<Long>

    /**
     * 内容哈希引用计数（R-16 共享文件保护）：删除文件前查，
     * > 1 说明其余账目/贴图还在用同一文件，不得删/不得暂存。
     */
    @Query("SELECT COUNT(*) FROM entry_images WHERE contentHash = :contentHash")
    suspend fun countByContentHash(contentHash: String): Int

    /**
     * 远端回放 upsert：按 syncId 有则更新、无则插入（理由同 [SectionDao.upsertRemote]）。
     * 原子性由外层 OpApplier 单事务保证。
     */
    suspend fun upsertRemote(row: EntryEntity): Long {
        val existing = getBySyncId(row.syncId)
        return if (existing == null) {
            insertEntry(row)
        } else {
            updateEntry(row.copy(id = existing.id))
            existing.id
        }
    }

    /** 贴图行的远端回放 upsert（口径同 [upsertRemote]） */
    suspend fun upsertRemoteImage(row: EntryImageEntity): Long {
        val existing = getImageBySyncId(row.syncId)
        return if (existing == null) {
            insertImages(listOf(row))
            0L
        } else {
            updateImage(row.copy(id = existing.id))
            existing.id
        }
    }

    /** 远端回放删除（行整体死亡；贴图行由调用方显式清——运行期外键关闭，级联不生效） */
    @Query("DELETE FROM entries WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    @Query("DELETE FROM entry_images WHERE syncId = :syncId")
    suspend fun deleteImageBySyncId(syncId: String)

    @Update
    suspend fun updateImage(image: EntryImageEntity)

    // ---------- T-4 存量导出 / 照片管线 / U-6 哈希补算 ----------

    /** 存量导出（RoomInitialExporter）：全部账目行（不带关联，逐行取快照用） */
    @Query("SELECT * FROM entries ORDER BY id")
    suspend fun allEntries(): List<EntryEntity>

    /** 存量导出 / 照片管线：全部贴图行 */
    @Query("SELECT * FROM entry_images ORDER BY id")
    suspend fun allImages(): List<EntryImageEntity>

    /** 照片管线（R-16 内容寻址）：全部非空内容哈希（去重前原样） */
    @Query("SELECT DISTINCT contentHash FROM entry_images WHERE contentHash IS NOT NULL AND contentHash != ''")
    suspend fun allContentHashes(): List<String>

    /** U-6 哈希补算：contentHash 为空的贴图行（v4 存量照片） */
    @Query("SELECT * FROM entry_images WHERE contentHash IS NULL OR contentHash = '' ORDER BY id")
    suspend fun imagesWithoutHash(): List<EntryImageEntity>

    /** U-6 哈希补算回填：只改 contentHash / filePath 两列（不动版本字段——技术迁移非语义变更） */
    @Query("UPDATE entry_images SET contentHash = :contentHash, filePath = :filePath WHERE id = :id")
    suspend fun updateImageBackfill(id: Long, contentHash: String, filePath: String)

    /** 大屏列表–详情：选中账目的实时数据（编辑后自动刷新） */
    @Transaction
    @Query("SELECT * FROM entries WHERE id = :id")
    fun observeEntryFull(id: Long): Flow<EntryFull?>

    /** 导出用：全部账目（按时间正序），带分类 / 分区 / 贴图 */
    @Transaction
    @Query("SELECT * FROM entries ORDER BY entryTime ASC, id ASC")
    suspend fun allEntriesFull(): List<EntryFull>

    @Query("SELECT * FROM entry_images WHERE entryId = :entryId ORDER BY sortOrder, id")
    suspend fun imagesOf(entryId: Long): List<EntryImageEntity>

    @Insert
    suspend fun insertEntry(entry: EntryEntity): Long

    @Update
    suspend fun updateEntry(entry: EntryEntity)

    /**
     * 只改**核对**维度（v4 A2：两个维度各自独立，一次只动一个，绝不互相牵连）。
     *
     * 刻意用定向 `@Query` 而不是「读出 EntryEntity → copy → updateEntry」：
     * 后者会把行内其它列一起写回，若此刻另有写入（如表单保存）就会互相覆盖。
     * 定向 UPDATE 只碰 reconciled 这一列，天然无竞态。
     */
    @Query(
        "UPDATE entries SET reconciled = :value, updatedAt = :now, versionSeq = versionSeq + 1 " +
            "WHERE id = :id"
    )
    suspend fun updateReconciled(id: Long, value: Boolean, now: Long)

    /**
     * 只改**报销**维度，理由同上。
     * v5 起两处定向 UPDATE 都顺带 `versionSeq + 1`（行级 Lamport 与 UPSERT 操作 seq 同步推进，U-3）。
     */
    @Query(
        "UPDATE entries SET reimburseState = :value, updatedAt = :now, versionSeq = versionSeq + 1 " +
            "WHERE id = :id"
    )
    suspend fun updateReimburseState(id: Long, value: Int, now: Long)

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

    // 「我的」页概览统计
    @Query("SELECT COUNT(*) FROM entries")
    suspend fun countAllEntries(): Int

    @Query("SELECT COUNT(*) FROM sections")
    suspend fun countAllSections(): Int

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun countAllCategories(): Int
}
