package com.simpleledger.app.logic

/**
 * U-12 `sync_ops` 操作日志裁剪规则（纯函数，JVM 可测；无 Android / data 层依赖）。
 *
 * 编号说明：任务建议的 U-9 经 grep 确认**已被占用**（`docs/sync-feature-2026-09-24/
 * 02-架构设计.md` §8 裁决表 U-9 = 坚果云 OAuth 深适配；且 LedgerRepository 拆分在
 * ExportRows/LedgerDeleteRestore 有两处 U-9 散标）——按「先 grep 未占用才可用」
 * 的规则顺延取 U-12。
 *
 * sync_ops 是 CRDT 事实源，裁剪必须保住三个正确性机制（论证对应 [OpMerge] 语义）：
 *
 * 1. **observed-remove 杀死判据**（`DELETE.baseSeq ≥ UPSERT.seq` 才杀死更新）——
 *    DELETE 操作**永不裁**：少了一条 DELETE，迟到的新操作会在裁过的机器上复活、
 *    在没裁的机器上仍死透 → 分歧；
 * 2. **行胜者全序**（max(U_live) 按 `(seq, actorId, opId)`）——被裁的操作都严格小于
 *    每行保留的「全序最大 UPSERT」，故未来任何新操作 W 满足
 *    `max(保留集 ∪ {W}) ≡ max(全量集 ∪ {W})`，收敛不变；
 *    （若保留的最大 UPSERT 已被杀死，则杀死它的 DELETE.baseSeq ≥ 其 seq ≥ 其余
 *    UPSERT 的 seq ⇒ 全行皆死，胜者只能由新操作决定，两侧仍一致。）
 * 3. **引用三分判定的「认识它」**（`countOpsOfRow > 0` 即占位、否则挂起）——
 *    每行至少保留胜者一条，判据永不失效。
 *
 * 可裁剪 = **同时**满足：非 outbox（LOCAL 且未上传的操作绝不裁；REMOTE 操作的
 * uploaded 恒为 false——它只是 LOCAL outbox 簿记）、已应用（applied）、
 * createdAt 早于 cutoff、opType = UPSERT（DELETE / TRASH_ACT 不裁）、非本行保留胜者。
 * 挂起集（applied = 0）由 DAO 查询天然排除，本函数再做一次防御性复检——
 * 误裁的代价是丢操作，宁多留不丢。
 */
object OpTrimRules {

    /** 参与裁剪判定的单条操作摘要（调用方从 DAO 行投影而来） */
    data class OpRow(
        val opId: String,
        val rowKind: String,
        val rowSyncId: String,
        val opType: String,
        val seq: Long,
        val actorId: String,
        /** 取值见 data 层 `OpOrigin`（"LOCAL" / "REMOTE"） */
        val origin: String,
        val uploaded: Boolean,
        val applied: Boolean,
        val createdAt: Long,
    )

    /**
     * 每行保留胜者（**全量口径**：含未满保留期的新操作——只看候选集会误保「子集内最大」）。
     * [winnerKey] 为复合排序键，构造见 [winnerKey]。
     */
    data class RowWinner(val rowKind: String, val rowSyncId: String, val winnerKey: String)

    /**
     * 复合排序键：`%020d|%s|%s`（seq, actorId, opId）——seq 零填充 20 位保证
     * 字典序 = 数值序，actorId / opId 均为 32hex 无竖线，故复合串取 MAX 即
     * `(seq, actorId, opId)` 全序最大（与 OpMerge.VERSION_ORDER 同序）。
     * 生产 SQL 侧用 `max(printf(...))` 聚合构造（SyncDao.latestUpsertPerRow，SQL 文本
     * 常量化共享；「取最大」语义由 SyncDaoLatestUpsertSqlTest 用真实 SQLite 钉死），
     * 两侧格式必须一致。
     */
    fun winnerKey(seq: Long, actorId: String, opId: String): String =
        "%020d|%s|%s".format(seq, actorId, opId)

    /** 从复合键解析胜者 opId（第二个 `|` 之后） */
    fun winnerOpId(winnerKey: String): String =
        winnerKey.substringAfter('|').substringAfter('|')

    /**
     * 求可裁剪 opId 集合。[candidates] 为 DAO 预筛（老 + 已传 + 已应用）的候选，
     * [winners] 为每行保留胜者；本函数是完整规范（对候选再做防御性复检）。
     */
    fun trimmableOpIds(
        candidates: List<OpRow>,
        winners: List<RowWinner>,
        cutoffMillis: Long,
    ): Set<String> {
        val protected = winners.map { winnerOpId(it.winnerKey) }.toSet()
        return candidates.asSequence()
            .filter { it.applied } // 挂起集绝不裁（防御复检）
            .filter { !(it.origin == ORIGIN_LOCAL && !it.uploaded) } // outbox 绝不裁（防御复检）
            .filter { it.createdAt < cutoffMillis } // 保留期内不动
            .filter { it.opType == OP_TYPE_UPSERT } // DELETE / TRASH_ACT 永不裁
            .filter { it.rowKind != ROW_KIND_TRASH } // 回收站动作行不裁（量小，保守）
            .filter { it.opId !in protected } // 每行胜者保底（引用判定 + 未来合并基线）
            .map { it.opId }
            .toSet()
    }

    /** opType 字面量（与 data 层 `OpTypeValue.UPSERT` 同源；logic 层不反向依赖 data） */
    private const val OP_TYPE_UPSERT = "UPSERT"

    /** rowKind 字面量（与 data 层 `RowKindValue.TRASH` 同源） */
    private const val ROW_KIND_TRASH = "TRASH"

    /** origin 字面量（与 data 层 `OpOrigin.LOCAL` 同源） */
    private const val ORIGIN_LOCAL = "LOCAL"
}
