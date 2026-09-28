package com.simpleledger.app.sync.op

import androidx.room.withTransaction
import com.simpleledger.app.data.local.AppDatabase
import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.ConflictTrashEntity
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryImageEntity
import com.simpleledger.app.data.local.entity.MemberEntity
import com.simpleledger.app.data.local.entity.OpOrigin
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.TrashKind
import com.simpleledger.app.data.local.entity.TrashResolved
import com.simpleledger.app.data.repo.ImageStorage
import com.simpleledger.app.logic.PhotoRetention
import com.simpleledger.app.logic.TrashAggregation
import org.json.JSONObject

/**
 * CRDT 合并纯核（§3.5 八条契约的 1/2/8 条）——**输入操作集 → 输出状态** 的纯函数：
 * 禁止读系统时间、禁止网络、禁止 IO（§7-8）。
 *
 * 1. **存活判定（observed-remove）**：`U_live = { u | u 为 UPSERT 且 ¬∃ d: DELETE ∧ d.baseSeq ≥ u.seq }`
 *    ——一条更新只有被某次删除**观察过**才死；并发/更晚的更新杀不死。
 * 2. **行状态**：`max(U_live)` 按 `RowVersion(seq, actorId)` 全序 LWW（平局按 opId 再破，全确定）。
 * 8. **并发编辑双留（U-3）**：`U_live` 中存在并发且 LWW 落败的版本 ⇒ 进回收站
 *    `TrashKind.OVERWRITE`；串行覆盖（`u_j.baseSeq ≥ u_lo.seq`）的历史**不留底**。
 *
 * 已知边界（baseSeq 数字模型的固有极限，≥3 分支历史）：同 seq 多分支时
 * 「观察过」按数字比较，极端三向竞争下留底判定从宽（宁多留不丢，D5）。
 */
object OpMerge {

    /** 一行操作全集的收敛结果 */
    data class RowResolution(
        /** `max(U_live)`；null = 行死（或从未有过更新） */
        val winner: SyncOp?,
        /** 行是否存活 */
        val alive: Boolean,
        /** 全部 DELETE 操作——每条各留一个 `TrashKind.DELETE` 留底 */
        val deletes: List<SyncOp>,
        /** `U_live` 中并发且 LWW 落败的 UPSERT——各留一个 `TrashKind.OVERWRITE` 留底 */
        val overwriteLosers: List<SyncOp>,
    )

    /** 全序比较器：(seq, actorId) 全序 + opId 终极破平（同设备同 seq 也全确定） */
    private val VERSION_ORDER = compareBy<SyncOp>({ it.seq }, { it.actorId }, { it.opId })

    /** observed-remove 判据：删除方的 baseSeq 是否「观察过」seq 为 [seq] 的更新 */
    fun observed(baseSeq: Long?, seq: Long): Boolean = (baseSeq ?: -1L) >= seq

    /** 互不观察（并发）：双方都没观察到对方 */
    private fun mutuallyUnobserved(a: SyncOp, b: SyncOp): Boolean =
        !observed(a.baseSeq, b.seq) && !observed(b.baseSeq, a.seq)

    /** §3.5 规则 1/2/8 的完整求解 */
    fun resolve(ops: List<SyncOp>): RowResolution {
        val upserts = ops.filter { it.opType == OpType.UPSERT }
        val deletes = ops.filter { it.opType == OpType.DELETE }
        val live = upserts.filter { u -> deletes.none { d -> observed(d.baseSeq, u.seq) } }
        val winner = live.maxWithOrNull(VERSION_ORDER)
        val overwriteLosers = live.filter { u ->
            u.opId != winner?.opId && live.any { j ->
                j.opId != u.opId &&
                    VERSION_ORDER.compare(j, u) > 0 &&
                    mutuallyUnobserved(j, u)
            }
        }
        return RowResolution(
            winner = winner,
            alive = winner != null,
            deletes = deletes.sortedWith(compareBy({ it.createdAt }, { it.opId })),
            overwriteLosers = overwriteLosers.sortedWith(VERSION_ORDER),
        )
    }
}

/**
 * 行投影写入端口（OpApplier 的出站依赖）。生产实现 [RoomRowStore] 走 Room DAO，
 * 单测用内存实现。`putWinner` 返回 false = **必需引用从未到过**（操作全集里也没有它，
 * 真正的乱序窗口）→ 操作挂起 `applied = 0` 下轮重试（§3.5 规则 6「引用未到」）。
 *
 * 引用判定三分（§3.5-6 的细化裁量，保 §3.5-7 纯函数收敛）：
 * - 解析到行 → 正常引用；
 * - 行不在但操作全集**认识**它（到过且已死）→ 占位落库：ENTRY 挂 0（UI 显示
 *   「未分类/未分区」）、CATEGORY 降级全局（与 deleteSection.detach 语义一致）——
 *   若按「未到」挂起，删∥增引用会在两机留下分歧投影（一边有行一边永久挂起），违背收敛；
 * - 操作全集也不认识 → 真·未到 → 挂起重试。
 */
interface RowStore {
    suspend fun putWinner(rowKind: RowKind, rowSyncId: String, winner: SyncOp): Boolean
    suspend fun removeRow(rowKind: RowKind, rowSyncId: String)
}

/** 事务执行端口：生产 = `db.withTransaction`（单事务整体回滚，R-12）；单测 = 快照式假事务 */
interface TxRunner {
    suspend fun <T> runInTransaction(block: suspend () -> T): T
}

class RoomTxRunner(private val db: AppDatabase) : TxRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T = db.withTransaction { block() }
}

/**
 * Room 版行投影写入：payload（syncId 引用）→ 本地 Long id 映射后落业务表。
 *
 * ⚠️ `entries` 的 `memberId` 存的就是成员 **syncId**（松耦合无 FK，Entities.kt 注释），
 * 无需映射；`categorySyncId` / `sectionSyncId` / `entrySyncId` 必须解析到本地行，
 * 解析不到即挂起（防孤儿引用，R-12）。
 *
 * ⚠️ 运行期 SQLite 外键确认**关闭**（room-runtime / sqlite-framework 字节码无
 * `foreign_keys` 触点 + 生成代码无 pragma，2026-09-24 实证）——`ON DELETE CASCADE`
 * 不生效，删除 ENTRY 时必须显式清 `entry_images` 行（此处已显式处理）。
 */
class RoomRowStore(
    private val db: AppDatabase,
    private val pathForHash: (String) -> String,
    private val settingSink: (String, String) -> Unit,
) : RowStore {

    private val sectionDao = db.sectionDao()
    private val categoryDao = db.categoryDao()
    private val entryDao = db.entryDao()
    private val syncDao = db.syncDao()

    override suspend fun putWinner(rowKind: RowKind, rowSyncId: String, winner: SyncOp): Boolean =
        when (rowKind) {
            RowKind.SECTION -> {
                val p = winner.payload
                val row = SectionEntity(
                    id = sectionDao.getBySyncId(rowSyncId)?.id ?: 0L,
                    name = p.getString("name"),
                    iconId = p.getInt("iconId"),
                    note = p.optString("note", ""),
                    budgetCents = p.optLong("budgetCents", 0L),
                    colorIndex = p.optInt("colorIndex", 0),
                    sortOrder = p.optInt("sortOrder", 0),
                    createdAt = p.optLong("createdAt", winner.createdAt),
                    syncId = rowSyncId,
                    versionSeq = winner.seq,
                    updatedAt = winner.createdAt,
                )
                sectionDao.upsertRemote(row)
                true
            }

            RowKind.CATEGORY -> {
                val p = winner.payload
                val sectionSyncId = if (p.has("sectionSyncId")) p.getString("sectionSyncId") else null
                val rawSectionId = if (sectionSyncId == null) {
                    null
                } else {
                    refOrNull(sectionDao.getBySyncId(sectionSyncId)?.id, sectionSyncId, RowKind.SECTION)
                        ?: return false // 引用从未到 → 挂起
                }
                // 已死占位（0）→ 降级全局：与 deleteSection 的 detachFromSection 语义一致
                val sectionId = if (rawSectionId == 0L) null else rawSectionId
                val row = CategoryEntity(
                    id = categoryDao.getBySyncId(rowSyncId)?.id ?: 0L,
                    name = p.getString("name"),
                    iconId = p.getInt("iconId"),
                    type = p.getInt("type"),
                    sectionId = sectionId,
                    sortOrder = p.optInt("sortOrder", 0),
                    syncId = rowSyncId,
                    versionSeq = winner.seq,
                    updatedAt = winner.createdAt,
                )
                categoryDao.upsertRemote(row)
                true
            }

            RowKind.ENTRY -> {
                val p = winner.payload
                val categorySyncId = p.getString("categorySyncId")
                val sectionSyncId = p.getString("sectionSyncId")
                val categoryId = refOrNull(
                    categoryDao.getBySyncId(categorySyncId)?.id, categorySyncId, RowKind.CATEGORY,
                ) ?: return false
                val sectionId = refOrNull(
                    sectionDao.getBySyncId(sectionSyncId)?.id, sectionSyncId, RowKind.SECTION,
                ) ?: return false
                val row = EntryEntity(
                    id = entryDao.getBySyncId(rowSyncId)?.id ?: 0L,
                    type = p.getInt("type"),
                    amountCents = p.getLong("amountCents"),
                    categoryId = categoryId,
                    sectionId = sectionId,
                    entryTime = p.getLong("entryTime"),
                    note = p.optString("note", ""),
                    reconciled = p.optBoolean("reconciled", false),
                    reimburseState = p.optInt("reimburseState", 0),
                    createdAt = p.optLong("createdAt", winner.createdAt),
                    updatedAt = p.optLong("updatedAt", winner.createdAt),
                    syncId = rowSyncId,
                    versionSeq = winner.seq,
                    memberId = if (p.has("memberSyncId")) p.getString("memberSyncId") else null,
                )
                entryDao.upsertRemote(row)
                true
            }

            RowKind.IMAGE -> {
                val p = winner.payload
                val entrySyncId = p.getString("entrySyncId")
                val entryId = refOrNull(
                    entryDao.getBySyncId(entrySyncId)?.id, entrySyncId, RowKind.ENTRY,
                ) ?: return false
                val contentHash = p.optString("contentHash", "")
                val row = EntryImageEntity(
                    id = entryDao.getImageBySyncId(rowSyncId)?.id ?: 0L,
                    entryId = entryId,
                    filePath = if (contentHash.isEmpty()) "" else pathForHash(contentHash),
                    sortOrder = p.optInt("sortOrder", 0),
                    syncId = rowSyncId,
                    versionSeq = winner.seq,
                    updatedAt = winner.createdAt,
                    contentHash = contentHash,
                )
                entryDao.upsertRemoteImage(row)
                true
            }

            RowKind.MEMBER -> {
                val p = winner.payload
                syncDao.upsertMember(
                    MemberEntity(
                        syncId = rowSyncId,
                        name = p.getString("name"),
                        hidden = p.optBoolean("hidden", false),
                        createdAt = p.optLong("createdAt", winner.createdAt),
                        updatedAt = winner.createdAt,
                        versionSeq = winner.seq,
                    )
                )
                true
            }

            RowKind.SETTING -> {
                val p = winner.payload
                settingSink(p.getString("key"), p.optString("value", ""))
                true
            }

            RowKind.TRASH -> true // 回收站条目由 TrashDeriver 物化，不走行投影
        }

    /**
     * 引用三分判定（口径见 [RowStore] 注释）：
     * 解析到 → 本地 id；到过且已死 → 0 占位（收敛优先）；从未到 → null（挂起重试）。
     */
    private suspend fun refOrNull(knownId: Long?, syncId: String, kind: RowKind): Long? = when {
        knownId != null -> knownId
        syncDao.countOpsOfRow(kind.value, syncId) > 0 -> 0L
        else -> null
    }

    override suspend fun removeRow(rowKind: RowKind, rowSyncId: String) {
        when (rowKind) {
            RowKind.SECTION -> sectionDao.deleteBySyncId(rowSyncId)
            RowKind.CATEGORY -> categoryDao.deleteBySyncId(rowSyncId)
            RowKind.ENTRY -> {
                val entry = entryDao.getBySyncId(rowSyncId) ?: return
                if (entry.id != 0L) {
                    entryDao.deleteImagesOf(entry.id) // 外键关、级联不生效 → 显式清，防孤儿行
                }
                entryDao.deleteBySyncId(rowSyncId)
            }
            RowKind.IMAGE -> entryDao.deleteImageBySyncId(rowSyncId)
            // 成员可隐藏不可删（产品拍板）：DELETE MEMBER 不产生，防御性忽略
            RowKind.MEMBER -> Unit
            // 设置无行投影表；TRASH 条目由 TrashDeriver 管理
            RowKind.SETTING -> Unit
            RowKind.TRASH -> Unit
        }
    }
}

/**
 * 回收站留底推导（§3.5 规则 3 + 8）：由操作全集幂等物化 `sync_trash`。
 *
 * - 每个 DELETE ⇒ `TrashKind.DELETE` 留底（纯删除 / 删改冲突都留，R-08），
 *   `conflict = (U_live ≠ ∅)`、`conflictActorId = argmax 方 actorId`；
 * - `U_live` 中并发 LWW 落败者 ⇒ `TrashKind.OVERWRITE` 留底（U-3），键 = 落败 UPSERT 的 opId；
 * - 已存在的条目**保留 resolved 用户态**（只刷新派生字段）；
 * - 新条目若已有 TRASH_ACT 等着（动作先于删除到达的乱序极端）立即补推进 resolved。
 */
internal class TrashDeriver(private val syncDao: SyncDao) {

    /** 重算一行的留底，返回本次新增/更新的条目数 */
    suspend fun derive(rowKind: RowKind, rowSyncId: String): Int {
        val ops = syncDao.opsOfRow(rowKind.value, rowSyncId).map { it.toModel() }
        val resolution = OpMerge.resolve(ops)
        var writes = 0
        for (d in resolution.deletes) {
            writes += writeEntry(
                source = d,
                targetKind = rowKind,
                rowSyncId = rowSyncId,
                kind = TrashKind.DELETE,
                conflict = resolution.alive,
                conflictActorId = resolution.winner?.actorId,
            )
        }
        for (loser in resolution.overwriteLosers) {
            writes += writeEntry(
                source = loser,
                targetKind = rowKind,
                rowSyncId = rowSyncId,
                kind = TrashKind.OVERWRITE,
                conflict = true,
                conflictActorId = resolution.winner?.actorId,
            )
        }
        return writes
    }

    /** TRASH_ACT 条目推进（远端动作 / 动作先到的乱序补推） */
    suspend fun syncTrashEntry(deleteOpId: String): Int {
        val existing = syncDao.getTrash(deleteOpId) ?: return 0
        return applyPendingAct(deleteOpId, existing)
    }

    private suspend fun writeEntry(
        source: SyncOp,
        targetKind: RowKind,
        rowSyncId: String,
        kind: String,
        conflict: Boolean,
        conflictActorId: String?,
    ): Int {
        val entity = ConflictTrashEntity(
            deleteOpId = source.opId,
            rowKind = targetKind.value,
            rowSyncId = rowSyncId,
            kind = kind,
            snapshot = source.payload.toString(),
            deletedAt = source.createdAt,
            deletedByMemberId = source.memberId,
            conflict = conflict,
            conflictActorId = conflictActorId,
            resolved = TrashResolved.VISIBLE,
        )
        val existing = syncDao.getTrash(entity.deleteOpId)
        return if (existing == null) {
            syncDao.insertTrash(entity)
            applyPendingAct(entity.deleteOpId, entity)
        } else {
            // 派生字段可随新操作变化（如删改冲突后补记 conflict），resolved 用户态不动
            syncDao.updateTrash(
                existing.copy(
                    snapshot = entity.snapshot,
                    deletedAt = entity.deletedAt,
                    deletedByMemberId = entity.deletedByMemberId,
                    conflict = entity.conflict,
                    conflictActorId = entity.conflictActorId,
                )
            )
            1
        }
    }

    /** 若该留底已有 TRASH_ACT（乱序先到），按 (seq, actorId, opId) 最后一条推进 resolved */
    private suspend fun applyPendingAct(deleteOpId: String, entry: ConflictTrashEntity): Int {
        if (entry.resolved != TrashResolved.VISIBLE) return 0
        val acts = syncDao.opsOfRow(RowKind.TRASH.value, deleteOpId)
            .map { it.toModel() }
            .filter { it.opType == OpType.TRASH_ACT }
        if (acts.isEmpty()) return 0
        val latest = acts.maxWith(compareBy({ it.seq }, { it.actorId }, { it.opId }))
        val action = latest.payload.optString("action", "")
        val resolved = when (action) {
            TrashAction.RESTORE -> TrashResolved.RESTORED
            TrashAction.PURGE -> TrashResolved.PURGED
            else -> return 0
        }
        syncDao.resolveTrash(deleteOpId, resolved)
        return 1
    }
}

/** 一轮应用的结果统计（applied = 已物化操作数；deferred = 挂起数；trashUpserts = 留底写入数） */
data class ApplyResult(val applied: Int, val deferred: Int, val trashUpserts: Int)

/**
 * 远端操作幂等回放 + observed-remove 合并 + 回收站推导（CRDT 核心，§3.5 八条契约）。
 *
 * 契约要点：
 * - **单事务**：一轮全部操作在一个事务内应用，任一失败整体回滚、下轮重试（R-12）；
 * - **分层序**：SECTION→CATEGORY→ENTRY→IMAGE→MEMBER→SETTING→TRASH（尊重引用序），
 *   层内按行 syncId 确定序（行内操作序由 `opsOfRow` 的 (seq, actorId, opId) 保证）；
 * - **幂等**：opId `INSERT OR IGNORE`，重复/乱序/断点投递重放收敛一致（R-07）；
 * - **挂起**：引用未到 → `applied = 0`，每轮末尾 + 下轮开始重试（规则 6）。
 */
class OpApplier(
    private val syncDao: SyncDao,
    private val rows: RowStore,
    private val tx: TxRunner,
    /**
     * A2 照片挂起的物理释放端口：给定一组 contentHash，按 [PhotoRetention] 判据
     * （业务引用 + 剩余可见留底引用**双双归零**）删除实体文件；生产构造注入，
     * 单测默认空实现（纯逻辑判据另测，不在此重复）。
     */
    private val photoRelease: suspend (Set<String>) -> Unit = {},
) {

    /**
     * 生产构造（§3.7 签名 + 两个缺口补齐参数）：
     * [imageStorage] 提供内容寻址路径（IMAGE 行的 filePath）；
     * [settingSink] 把 SETTING 胜者写入 AppSettings（T-5 接线，默认空实现）。
     */
    constructor(
        db: AppDatabase,
        syncDao: SyncDao,
        imageStorage: ImageStorage,
        settingSink: (String, String) -> Unit = { _, _ -> },
    ) : this(
        syncDao = syncDao,
        rows = RoomRowStore(db, imageStorage::pathForHash, settingSink),
        tx = RoomTxRunner(db),
        photoRelease = { hashes ->
            // PhotoRetention 判据（业务引用 + 剩余可见留底引用双归零）→ 物理删文件
            val entryDao = db.entryDao()
            val deletable = hashes.filter { hash ->
                PhotoRetention.shouldDeleteFile(
                    entryDao.countByContentHash(hash),
                    syncDao.countVisibleTrashRefs(hash),
                )
            }
            if (deletable.isNotEmpty()) {
                imageStorage.deleteFiles(deletable.map { imageStorage.pathForHash(it) })
            }
        },
    )

    private val deriver = TrashDeriver(syncDao)

    /** 应用一批远端操作（单事务、幂等） */
    suspend fun applyRemote(ops: List<SyncOp>): ApplyResult {
        if (ops.isEmpty()) return ApplyResult(0, 0, 0)
        return tx.runInTransaction {
            syncDao.insertOps(ops.map { it.toEntity(OpOrigin.REMOTE, applied = false, uploaded = false) })
            runPass(ops.map { it.rowKind to it.rowSyncId })
        }
    }

    /** 重试挂起集（§3.5 规则 6：每轮应用末尾 + 下轮开始调用） */
    suspend fun retryDeferred(): ApplyResult {
        val pending = syncDao.deferredOps().map { it.toModel() }
        if (pending.isEmpty()) return ApplyResult(0, 0, 0)
        return tx.runInTransaction {
            runPass(pending.map { it.rowKind to it.rowSyncId })
        }
    }

    /** 重算单行投影 + 回收站推导（独立事务，RESTORE 等场景的收尾物化） */
    suspend fun materializeRow(rowKind: RowKind, rowSyncId: String) {
        tx.runInTransaction { materializeWithinTx(rowKind, rowSyncId) }
    }

    /**
     * 回收站「恢复」（T-5 入口）：同事务记 TRASH_ACT(RESTORE) + 回插 UPSERT（OpRecorder 内）
     * 并重算行投影。回插按串行覆盖记账（baseSeq = maxSeqOf），被覆盖的现版本不留底（U-3）。
     */
    suspend fun restoreTrashEntry(recorder: OpRecorder, deleteOpId: String): Boolean {
        val trash = syncDao.getTrash(deleteOpId) ?: return false
        if (trash.resolved != TrashResolved.VISIBLE) return false
        tx.runInTransaction {
            recorder.onTrashAct(deleteOpId, TrashAction.RESTORE, OpCodec.withoutDeletedAt(JSONObject(trash.snapshot)))
            materializeWithinTx(RowKind.fromValue(trash.rowKind), trash.rowSyncId)
        }
        return true
    }

    /** 回收站「彻底删除」（T-5 入口）：条目推进 resolved = PURGED，保留至 90 天清理（R-21） */
    suspend fun purgeTrashEntry(recorder: OpRecorder, deleteOpId: String): Boolean {
        val trash = syncDao.getTrash(deleteOpId) ?: return false
        if (trash.resolved != TrashResolved.VISIBLE) return false
        tx.runInTransaction {
            recorder.onTrashAct(deleteOpId, TrashAction.PURGE, null)
        }
        // A2 照片挂起：该留底被彻底删除后不再保护其引用的照片文件——
        // 业务引用与剩余可见留底引用都归零时物理释放（误宽不误漏）
        releaseRetainedPhotos(listOf(trash))
        return true
    }

    /**
     * 批量照片释放（手动清空 / 90 天到期清理后调用）：从留底行提取 IMAGE 快照的
     * contentHash（[TrashAggregation.imageHashesOf]），交由 [photoRelease] 按判据物理删除。
     * 幂等：判据不满足的 hash 多次传入无副作用。
     */
    suspend fun releaseRetainedPhotos(rows: List<ConflictTrashEntity>) {
        releasePhotoHashes(TrashAggregation.imageHashesOf(rows))
    }

    /**
     * U-17：按 contentHash 的释放端口转发。AppContainer 给 [com.simpleledger.app.sync.SyncManager]
     * 的 R-21 到期清理接线复用本入口，与生产判据（[photoRelease]）单一真源；
     * 幂等同 [releaseRetainedPhotos]：判据不满足的 hash 多次传入无副作用。
     */
    suspend fun releasePhotoHashes(hashes: Set<String>) {
        if (hashes.isNotEmpty()) photoRelease(hashes)
    }

    // ------------------------------------------------------------------ 内部

    /** 分层序遍历受影响行并逐行物化（调用方保证已在事务内） */
    private suspend fun runPass(affected: List<Pair<RowKind, String>>): ApplyResult {
        var applied = 0
        var deferred = 0
        var trashUpserts = 0
        val ordered = affected
            .distinct()
            .sortedWith(compareBy({ it.first.layerIndex }, { it.second }))
        for ((rowKind, rowSyncId) in ordered) {
            val outcome = materializeWithinTx(rowKind, rowSyncId)
            applied += outcome.applied
            deferred += outcome.deferred
            trashUpserts += outcome.trashUpserts
        }
        return ApplyResult(applied, deferred, trashUpserts)
    }

    /**
     * 单行物化：留底推导 → LWW 胜者投影 / 行删除 → applied 簿记。
     * 引用未到返回 deferred（整行挂起，含其全部操作）。
     */
    private suspend fun materializeWithinTx(rowKind: RowKind, rowSyncId: String): ApplyResult {
        val ops = syncDao.opsOfRow(rowKind.value, rowSyncId).map { it.toModel() }
        var trashUpserts = 0

        if (rowKind == RowKind.TRASH) {
            trashUpserts += deriver.syncTrashEntry(rowSyncId)
            syncDao.markApplied(ops.map { it.opId })
            return ApplyResult(ops.size, 0, trashUpserts)
        }

        val resolution = OpMerge.resolve(ops)
        trashUpserts += deriver.derive(rowKind, rowSyncId)

        val winner = resolution.winner
        val ok = if (winner == null) {
            rows.removeRow(rowKind, rowSyncId)
            true
        } else {
            rows.putWinner(rowKind, rowSyncId, winner)
        }
        return if (ok) {
            syncDao.markApplied(ops.map { it.opId })
            ApplyResult(ops.size, 0, trashUpserts)
        } else {
            // 引用未到：保持 applied = 0（新插入的 REMOTE 操作即此状态），下轮重试
            ApplyResult(0, ops.size, trashUpserts)
        }
    }
}
