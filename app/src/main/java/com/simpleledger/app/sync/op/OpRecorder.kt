package com.simpleledger.app.sync.op

import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.OpOrigin
import com.simpleledger.app.data.local.entity.TrashResolved
import com.simpleledger.app.sync.account.SyncPrefs
import org.json.JSONObject

/**
 * 本地写路径埋点（§3.7/§3.8）：把每次增删改**在业务写同一个数据库事务内**追加为不可变操作。
 *
 * 事务契约（§7-9）：调用方（`LedgerRepository`）必须把本类方法与表写包在**同一个**
 * `db.withTransaction {}` 里——失败同回滚，绝不出现「行写了操作没记 / 操作记了行没写」。
 * 文件 IO 一律在事务外（沿用 `saveEntry` 三段式）。
 *
 * 落操作后即时推导回收站留底（[TrashDeriver]，幂等纯函数物化）：纯删除也留底（R-08）。
 *
 * 身份注入用 lambda 而非直接持有 [SyncPrefs]：JVM 单测可构造（测试无 Android Context），
 * 生产走次构造 `OpRecorder(syncDao, prefs)`，取 `deviceId` / `selfMemberId`。
 */
class OpRecorder(
    private val syncDao: SyncDao,
    private val actorIdOf: () -> String,
    private val memberIdOf: () -> String?,
) {

    constructor(syncDao: SyncDao, prefs: SyncPrefs) :
        this(syncDao, { prefs.deviceId }, { prefs.selfMemberId })

    private val deriver = TrashDeriver(syncDao)

    /** 本机认领成员（记账成员标签用；null = 未认领） */
    fun currentMemberId(): String? = memberIdOf()

    /** 本机设备身份（操作 actorId） */
    fun currentActorId(): String = actorIdOf()

    /**
     * 记一条 LOCAL UPSERT（新建/编辑/回插共用）。返回操作 opId。
     *
     * @param seq     新行 versionSeq（= baseSeq + 1，新建 = 1）
     * @param baseSeq 编辑时观察到的行 versionSeq（**新建 = null**，U-3 裁定）
     */
    suspend fun onUpsert(
        rowKind: RowKind,
        rowSyncId: String,
        seq: Long,
        baseSeq: Long?,
        snapshot: JSONObject,
    ): String {
        val op = SyncOp(
            opId = newOpId(),
            rowKind = rowKind,
            rowSyncId = rowSyncId,
            opType = OpType.UPSERT,
            actorId = actorIdOf(),
            memberId = memberIdOf(),
            seq = seq,
            baseSeq = baseSeq,
            payload = snapshot,
            createdAt = System.currentTimeMillis(),
        )
        syncDao.insertOp(op.toEntity(OpOrigin.LOCAL, applied = true, uploaded = false))
        // 并发编辑 LWW 落败留底在本地也可能触发（对方高 seq 操作已到、未轮到本行物化）
        deriver.derive(rowKind, rowSyncId)
        return op.opId
    }

    /**
     * 记一条 LOCAL DELETE（observed-remove，携带 baseSeq）。返回操作 opId。
     *
     * 快照 = 删除方观察到的行快照（同 UPSERT 字段 + `_deletedAt`），
     * 落 `sync_trash` 供「冲突回收站」恢复（R-08/R-09）。
     *
     * @param baseSeq 被删行当时的 versionSeq（不允许 null——判据 `d.baseSeq ≥ u.seq`）
     */
    suspend fun onDelete(
        rowKind: RowKind,
        rowSyncId: String,
        baseSeq: Long,
        snapshot: JSONObject,
    ): String {
        val op = SyncOp(
            opId = newOpId(),
            rowKind = rowKind,
            rowSyncId = rowSyncId,
            opType = OpType.DELETE,
            actorId = actorIdOf(),
            memberId = memberIdOf(),
            seq = 0,
            baseSeq = baseSeq,
            payload = snapshot,
            createdAt = System.currentTimeMillis(),
        )
        syncDao.insertOp(op.toEntity(OpOrigin.LOCAL, applied = true, uploaded = false))
        deriver.derive(rowKind, rowSyncId)
        return op.opId
    }

    /**
     * 记回收站动作（TRASH_ACT）。返回 TRASH_ACT 操作 opId。
     *
     * `action = RESTORE` 且带 [restoreSnapshot] 时，**同事务**补记回插 UPSERT
     * （原 syncId、`seq = max(MAX(seq), 该行全部 DELETE.baseSeq 的最大值) + 1`、
     * `baseSeq = maxSeq`——串行回插，§3.5-8-4 + U-13，被覆盖的现版本不留底）；
     * 行投影由调用方随后 `OpApplier.materializeRow` 重算。
     *
     * `action = PURGE` 用于彻底删除与「4 秒撤销」撤下刚产生的留底（§3.8）。
     * 条目 `resolved` 状态同步推进（RESTORE→RESTORED / PURGE→PURGED）。
     */
    suspend fun onTrashAct(
        deleteOpId: String,
        action: String,
        restoreSnapshot: JSONObject?,
    ): String {
        val now = System.currentTimeMillis()
        val trash = syncDao.getTrash(deleteOpId)
        val actOp = SyncOp(
            opId = newOpId(),
            rowKind = RowKind.TRASH,
            rowSyncId = deleteOpId,
            opType = OpType.TRASH_ACT,
            actorId = actorIdOf(),
            memberId = memberIdOf(),
            seq = 0,
            baseSeq = null,
            payload = OpCodec.trashActSnapshot(action),
            createdAt = now,
        )
        syncDao.insertOp(op = actOp.toEntity(OpOrigin.LOCAL, applied = true, uploaded = false))

        if (action == TrashAction.RESTORE && restoreSnapshot != null && trash != null) {
            val targetKind = RowKind.fromValue(trash.rowKind)
            // U-13：回插 seq 不能只看 MAX(seq)。v4 迁移/种子行的 versionSeq = 0（存量导出
            // 即以 seq = 0 声明存在）。缺陷史：其 DELETE 的 baseSeq 曾被写路径
            // `coerceAtLeast(1L)` 伪抬成 1（该 coerce 已在 data/repo 收口时移除，DELETE
            // 现携真实 versionSeq），行内 MAX(seq) 却仍 = 0——若回插 seq = 1，则
            // observed(1, 1) = true，回插 UPSERT 落在该 DELETE 的观察范围内被 OpMerge
            // 判死（live = ∅ → removeRow），而此时留底已翻 RESTORED：恢复静默失败且
            // 内容处处不可见。
            // 口径：seq = max(MAX(seq), 该行全部 DELETE.baseSeq 的最大值) + 1——回插必须
            // 严格晚于删除所「声称观察到」的最高版本，保证 observed(baseSeq, seq) 恒 false；
            // 正常路径 DELETE.baseSeq ≤ MAX(seq)，此 max 不改变既有行为。
            val ops = syncDao.opsOfRow(trash.rowKind, trash.rowSyncId)
            val maxSeq = maxOf(
                ops.maxOfOrNull { it.seq } ?: 0L,
                ops.filter { it.opType == OpType.DELETE.value }
                    .mapNotNull { it.baseSeq }
                    .maxOrNull()
                    ?: 0L,
            )
            val restoreOp = SyncOp(
                opId = newOpId(),
                rowKind = targetKind,
                rowSyncId = trash.rowSyncId,
                opType = OpType.UPSERT,
                actorId = actorIdOf(),
                memberId = memberIdOf(),
                seq = maxSeq + 1,
                baseSeq = maxSeq, // 串行覆盖：观察到当前 max 版本 ⇒ 不产生 OVERWRITE 留底
                payload = restoreSnapshot,
                createdAt = now,
            )
            syncDao.insertOp(op = restoreOp.toEntity(OpOrigin.LOCAL, applied = true, uploaded = false))
            deriver.derive(targetKind, trash.rowSyncId)
        }

        if (trash != null) {
            val resolved = if (action == TrashAction.RESTORE) {
                TrashResolved.RESTORED
            } else {
                TrashResolved.PURGED
            }
            syncDao.resolveTrash(deleteOpId, resolved)
        }
        return actOp.opId
    }
}
