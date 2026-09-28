package com.simpleledger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.simpleledger.app.data.local.entity.ConflictTrashEntity
import com.simpleledger.app.data.local.entity.MemberEntity
import com.simpleledger.app.data.local.entity.RemoteFileEntity
import com.simpleledger.app.data.local.entity.SyncOpEntity
import kotlinx.coroutines.flow.Flow

/**
 * 同步子系统专用查询（v5）：操作 outbox / 云端文件游标 / 冲突回收站 / 远程台账 / 成员。
 *
 * 分工约定（避免 T-3/T-4 越层）：
 * - 业务行（sections/categories/entries/entry_images）的 syncId 查找与远端回放在
 *   `EntryDao` / `SectionDao` / `CategoryDao`（T-3 增补 getBySyncId / upsertRemote）；
 * - 本 DAO 只管同步支撑表 + 行级版本查询（`maxSeqOf` 等，供 OpRecorder 取 Lamport 计数）。
 *
 * 幂等口径：一切写入以 `opId` / `deleteOpId` / `remoteName` 主键幂等（INSERT OR IGNORE /
 * OR REPLACE），重复投递无副作用（R-07）。
 */
@Dao
interface SyncDao {

    /* ================================================================ 成员 */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: MemberEntity)

    @Query("SELECT * FROM members WHERE syncId = :syncId")
    suspend fun getMember(syncId: String): MemberEntity?

    /** 成员认领 / 撞名检查：全书唯一靠应用层校验（撞名双留 + 提示改名，U-10） */
    @Query("SELECT * FROM members WHERE name = :name LIMIT 1")
    suspend fun findMemberByName(name: String): MemberEntity?

    @Query("SELECT * FROM members ORDER BY createdAt, syncId")
    suspend fun listMembers(): List<MemberEntity>

    /** 成员管理页（U-2）：全部成员实时列表（隐藏的也在，UI 标注「已隐藏」） */
    @Query("SELECT * FROM members ORDER BY createdAt, syncId")
    fun observeMembers(): Flow<List<MemberEntity>>

    @Query("SELECT COUNT(*) FROM members")
    suspend fun countMembers(): Int

    /* ================================================================ 操作日志（outbox / 回放） */

    /** 幂等落操作（opId 主键冲突即忽略——远端重复投递 / 本地重复埋点都安全） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOp(op: SyncOpEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOps(ops: List<SyncOpEntity>)

    @Query("SELECT * FROM sync_ops WHERE opId = :opId")
    suspend fun getOp(opId: String): SyncOpEntity?

    @Query("SELECT COUNT(*) FROM sync_ops WHERE opId = :opId")
    suspend fun countOp(opId: String): Int

    /**
     * 待上传 outbox（PUSH 阶段的数据源）：本机产生（`origin = 'LOCAL'`，见 [OpOrigin]）
     * 且未上传的操作，按产生顺序出队。`limit` = 单个云端分片的操作数上限（128，见 §7）。
     */
    @Query(
        """
        SELECT * FROM sync_ops
        WHERE uploaded = 0 AND origin = 'LOCAL'
        ORDER BY createdAt, opId
        LIMIT :limit
        """
    )
    suspend fun outbox(limit: Int): List<SyncOpEntity>

    @Query("SELECT COUNT(*) FROM sync_ops WHERE uploaded = 0 AND origin = 'LOCAL'")
    suspend fun countOutbox(): Int

    /** 打包上传成功后标记（chunkName = 云端分片假名，用于断点审计） */
    @Query("UPDATE sync_ops SET uploaded = 1, chunkName = :chunkName WHERE opId IN (:opIds)")
    suspend fun markUploaded(opIds: List<String>, chunkName: String)

    /**
     * 挂起集（§3.5 规则 6）：引用未到（如 ENTRY 先于其 CATEGORY 到达）的远端操作，
     * 每轮应用末尾 + 下轮开始重试；上限告警阈值 50。
     */
    @Query("SELECT * FROM sync_ops WHERE applied = 0 ORDER BY createdAt, opId")
    suspend fun deferredOps(): List<SyncOpEntity>

    @Query("SELECT COUNT(*) FROM sync_ops WHERE applied = 0")
    suspend fun countDeferredOps(): Int

    @Query("UPDATE sync_ops SET applied = 1 WHERE opId IN (:opIds)")
    suspend fun markApplied(opIds: List<String>)

    /**
     * 某行的操作全集（CRDT 合并的输入），**按 §3.5 分层内全序排序**：
     * `(seq, actorId, opId)` —— OpApplier 的确定性收敛依赖此顺序。
     */
    @Query(
        """
        SELECT * FROM sync_ops
        WHERE rowKind = :rowKind AND rowSyncId = :rowSyncId
        ORDER BY seq, actorId, opId
        """
    )
    suspend fun opsOfRow(rowKind: String, rowSyncId: String): List<SyncOpEntity>

    @Query(
        """
        SELECT COUNT(*) FROM sync_ops
        WHERE rowKind = :rowKind AND rowSyncId = :rowSyncId
        """
    )
    suspend fun countOpsOfRow(rowKind: String, rowSyncId: String): Int

    /**
     * 行级当前最大 Lamport 计数（新 UPSERT 的 `seq = maxSeqOf + 1`；
     * RESTORE 回插的 `baseSeq` 也取它——U-3：回插按串行覆盖记账）。
     * 无操作时返回 null（种子行 / 从未同步的行）。
     */
    @Query(
        """
        SELECT MAX(seq) FROM sync_ops
        WHERE rowKind = :rowKind AND rowSyncId = :rowSyncId
        """
    )
    suspend fun maxSeqOf(rowKind: String, rowSyncId: String): Long?

    /**
     * 撤销摘除（首选路径，§3.8）：删除操作**尚未上传**时可连操作一起摘除，
     * 云端不会留下矛盾事实。返回受影响行数（= 0 说明已上传，只能走 TRASH_ACT 兜底）。
     */
    @Query("DELETE FROM sync_ops WHERE opId IN (:opIds) AND uploaded = 0 AND origin = 'LOCAL'")
    suspend fun removePendingOps(opIds: List<String>): Int

    /* ================================================================ 操作日志裁剪（U-12） */

    /**
     * 裁剪候选（U-12）：非 outbox（已上传，或 origin = REMOTE——远端操作的 uploaded
     * 恒为 false，它只是 LOCAL outbox 簿记）+ 已应用 + 早于保留期（对齐 R-21 的 90 天）的
     * 业务行 UPSERT。DELETE / TRASH_ACT / TRASH 行**永不进候选**（observed-remove
     * 判据依赖，正确性论证见 `logic/OpTrimRules`）；挂起集（applied = 0）天然不在结果里。
     */
    @Query(
        """
        SELECT * FROM sync_ops
        WHERE (uploaded = 1 OR origin = 'REMOTE')
          AND applied = 1
          AND createdAt < :cutoffMillis
          AND opType = 'UPSERT'
          AND rowKind != 'TRASH'
        ORDER BY createdAt, opId
        """
    )
    suspend fun trimCandidates(cutoffMillis: Long): List<SyncOpEntity>

    companion object {
        /**
         * [latestUpsertPerRow] 的 SQL（常量化：测试直接引用同一份文本，零漂移）。
         *
         * 胜者必须是 `max(printf(...))` **聚合**——SQLite 的 GROUP BY 对裸列表达式
         * 只取组内任意一行（sqlite3 3.50.6 实测为组内第一行），不带 `max` 时返回的
         * 不是全序最大 UPSERT，而是往往最老的那条：裁剪会把真 LWW 胜者当可裁删除，
         * 一条 baseSeq 恰在其间的 DELETE 就会造成跨设备永久分歧（评审 R1-high）。
         * 该「取最大」语义由 `SyncDaoLatestUpsertSqlTest` 对同一建表语句
         * （`MigrationSql.CREATE_TABLE_SYNC_OPS`）跑真实 SQLite 钉死。
         */
        const val LATEST_UPSERT_PER_ROW_SQL: String =
            """
            SELECT rowKind, rowSyncId,
                   max(printf('%020d|%s|%s', seq, actorId, opId)) AS winnerKey
            FROM sync_ops
            WHERE opType = 'UPSERT'
            GROUP BY rowKind, rowSyncId
            """
    }

    /**
     * 每行保留胜者（U-12，**全量口径**含保留期内的新操作）：按 `(seq, actorId, opId)`
     * 全序取最大 UPSERT（SQL 见 [LATEST_UPSERT_PER_ROW_SQL]，`max(printf(...))` 聚合）——
     * 复合键构造契约见 `logic/OpTrimRules.winnerKey`
     * （SQL `printf` 与 Kotlin `%020d|%s|%s` 两侧必须一致，opId = 复合键第二个 `|` 之后）。
     */
    @Query(LATEST_UPSERT_PER_ROW_SQL)
    suspend fun latestUpsertPerRow(): List<RowWinnerProjection>

    /** 按 opId 批量删除（U-12 裁剪执行；只允许传入 `OpTrimRules.trimmableOpIds` 的结果） */
    @Query("DELETE FROM sync_ops WHERE opId IN (:opIds)")
    suspend fun deleteOpsByIds(opIds: List<String>): Int

    /* ================================================================ 冲突回收站 */

    /** 幂等物化留底（键 = deleteOpId，重复推导无副作用） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrash(trash: ConflictTrashEntity): Long

    /** 重新物化时更新派生字段（conflict / conflictActorId）；不动 resolved 用户态 */
    @Update
    suspend fun updateTrash(trash: ConflictTrashEntity)

    @Query("SELECT * FROM sync_trash WHERE deleteOpId = :deleteOpId")
    suspend fun getTrash(deleteOpId: String): ConflictTrashEntity?

    /** 回收站页（U-3）：可见条目实时列表（新→旧） */
    @Query("SELECT * FROM sync_trash WHERE resolved = 0 ORDER BY deletedAt DESC, deleteOpId")
    fun observeVisibleTrash(): Flow<List<ConflictTrashEntity>>

    @Query("SELECT * FROM sync_trash WHERE resolved = 0 ORDER BY deletedAt DESC, deleteOpId")
    suspend fun listVisibleTrash(): List<ConflictTrashEntity>

    /** 「我的」入口角标计数（U-3：有留底时提示数量） */
    @Query("SELECT COUNT(*) FROM sync_trash WHERE resolved = 0")
    fun observeVisibleTrashCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_trash WHERE resolved = 0")
    suspend fun countVisibleTrash(): Int

    /** 恢复 / 彻底删除后推进状态（值见 TrashResolved） */
    @Query("UPDATE sync_trash SET resolved = :resolved WHERE deleteOpId = :deleteOpId")
    suspend fun resolveTrash(deleteOpId: String, resolved: Int)

    /** 彻底删除后连条目一起清掉（不留残骸） */
    @Query("DELETE FROM sync_trash WHERE deleteOpId = :deleteOpId")
    suspend fun deleteTrash(deleteOpId: String)

    /** R-21 自动清理：90 天前的留底全部清除（无论 resolved 状态），不影响正常账目 */
    @Query("DELETE FROM sync_trash WHERE deletedAt < :cutoffMillis")
    suspend fun purgeTrashBefore(cutoffMillis: Long): Int

    /** 照片引用挂起审计：列出待清理窗口内的留底行（调用方从中提取 contentHash 释放实体文件） */
    @Query("SELECT * FROM sync_trash WHERE deletedAt < :cutoffMillis")
    suspend fun listTrashBefore(cutoffMillis: Long): List<ConflictTrashEntity>

    /** 手动清空前取全量留底（调用方提取 contentHash 做照片物理释放） */
    @Query("SELECT * FROM sync_trash")
    suspend fun listAllTrash(): List<ConflictTrashEntity>

    /**
     * 照片引用挂起计数（B 系·照片挂起）：留底期内 entry_images 与实体文件**不物理删除**，
     * 只要还有未消化的留底快照引用该 contentHash，业务侧的照片清理就必须跳过该文件。
     * 用快照 LIKE 匹配（快照 JSON 含 contentHash 字段），误宽不误漏——多留一会儿无害。
     */
    @Query(
        """
        SELECT COUNT(*) FROM sync_trash
        WHERE resolved = 0 AND snapshot LIKE '%' || :contentHash || '%'
        """
    )
    suspend fun countVisibleTrashRefs(contentHash: String): Int

    /** R-21 手动清空 */
    @Query("DELETE FROM sync_trash")
    suspend fun clearTrash()

    /* ================================================================ 云端文件台账（增量游标） */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRemoteFile(file: RemoteFileEntity)

    @Query("SELECT * FROM sync_remote_files WHERE remoteName = :remoteName")
    suspend fun getRemoteFile(remoteName: String): RemoteFileEntity?

    /** PULL 差集：PROPFIND 列出的文件 ⊖ 台账已记录的文件 = 需要 GET 的新分片 */
    @Query("SELECT * FROM sync_remote_files ORDER BY remoteName")
    suspend fun remoteFilesAll(): List<RemoteFileEntity>

    /** 按台账类型（见 [RemoteFileKind]）列出 */
    @Query("SELECT * FROM sync_remote_files WHERE kind = :kind ORDER BY remoteName")
    suspend fun remoteFilesByKind(kind: String): List<RemoteFileEntity>

    /** 未下载完的操作分片（断点：下载失败的分片 downloadedAt 保持 0，下轮续） */
    @Query(
        """
        SELECT * FROM sync_remote_files
        WHERE kind = 'OP_CHUNK' AND downloadedAt = 0
        ORDER BY remoteName
        """
    )
    suspend fun undownloadedChunks(): List<RemoteFileEntity>

    /** 分片 GET + 解密 + 回放成功后标记 */
    @Query("UPDATE sync_remote_files SET downloadedAt = :at WHERE remoteName = :remoteName")
    suspend fun markDownloaded(remoteName: String, at: Long)

    /** PUT 成功后回填 etag / size（后续覆盖写走 If-Match） */
    @Query(
        """
        UPDATE sync_remote_files
        SET etag = :etag, size = :size, uploadedAt = :at
        WHERE remoteName = :remoteName
        """
    )
    suspend fun markFileUploaded(remoteName: String, etag: String?, size: Long, at: Long)

    @Query("DELETE FROM sync_remote_files WHERE remoteName = :remoteName")
    suspend fun deleteRemoteFile(remoteName: String)

    /** 重置同步（R-05）：作废云端布局时清空本地游标 */
    @Query("DELETE FROM sync_remote_files")
    suspend fun clearRemoteFiles()

    /** 重置同步（R-05）：清空操作日志（本地账本零触碰，只作废同步侧事实源） */
    @Query("DELETE FROM sync_ops")
    suspend fun clearOps()

    /* ================================================================ 行级 syncId 查找（合并去重辅助） */

    /**
     * 全库 syncId 冲突探针（合并前体检 / 单测用）：四张业务表里任一存在即返回 1。
     * 正常库恒为 0——迁移两阶段回填（先随机后覆盖）+ 唯一索引保证。
     */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM sections WHERE syncId = :syncId)
             + (SELECT COUNT(*) FROM categories WHERE syncId = :syncId)
             + (SELECT COUNT(*) FROM entries WHERE syncId = :syncId)
             + (SELECT COUNT(*) FROM entry_images WHERE syncId = :syncId)
        """
    )
    suspend fun countRowsWithSyncId(syncId: String): Int
}

/** U-12 裁剪用投影：每行保留胜者的复合键（SQL 列与构造器参数一一对应） */
data class RowWinnerProjection(
    val rowKind: String,
    val rowSyncId: String,
    val winnerKey: String,
)
