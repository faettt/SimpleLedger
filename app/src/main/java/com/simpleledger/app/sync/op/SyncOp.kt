package com.simpleledger.app.sync.op

import com.simpleledger.app.data.local.entity.OpOrigin
import com.simpleledger.app.data.local.entity.OpTypeValue
import com.simpleledger.app.data.local.entity.RowKindValue
import com.simpleledger.app.data.local.entity.SyncOpEntity
import org.json.JSONObject
import java.util.UUID

/**
 * 行类型（与 `SyncEntities.RowKindValue` 的字符串值一一对应，落库后不复用）。
 *
 * [layerIndex] 是 §3.5 规则 5 的**分层应用序**：尊重 RESTRICT 引用序（R-12），
 * SECTION→CATEGORY→ENTRY→IMAGE→MEMBER→SETTING→TRASH，层序错乱会制造孤儿引用。
 */
enum class RowKind(val value: String) {
    SECTION(RowKindValue.SECTION),
    CATEGORY(RowKindValue.CATEGORY),
    ENTRY(RowKindValue.ENTRY),
    IMAGE(RowKindValue.IMAGE),
    MEMBER(RowKindValue.MEMBER),
    SETTING(RowKindValue.SETTING),
    TRASH(RowKindValue.TRASH),
    ;

    val layerIndex: Int
        get() = when (this) {
            SECTION -> 0
            CATEGORY -> 1
            ENTRY -> 2
            IMAGE -> 3
            MEMBER -> 4
            SETTING -> 5
            TRASH -> 6
        }

    companion object {
        fun fromValue(value: String): RowKind =
            entries.firstOrNull { it.value == value }
                ?: error("未知 rowKind: $value")
    }
}

/** 操作类型（与 `OpTypeValue` 一一对应） */
enum class OpType(val value: String) {
    UPSERT(OpTypeValue.UPSERT),
    DELETE(OpTypeValue.DELETE),
    TRASH_ACT(OpTypeValue.TRASH_ACT),
    ;

    companion object {
        fun fromValue(value: String): OpType =
            entries.firstOrNull { it.value == value }
                ?: error("未知 opType: $value")
    }
}

/** 回收站动作（TRASH_ACT 载荷的 `action` 字段） */
object TrashAction {
    /** 恢复到账本：以原 syncId、seq = maxSeq + 1 串行回插一条 UPSERT（§3.5-8-4） */
    const val RESTORE = "RESTORE"

    /** 彻底删除：留底条目推进 resolved = PURGED（保留至 90 天清理，R-21） */
    const val PURGE = "PURGE"
}

/**
 * 行级因果版本：`seq` = 行 Lamport 计数，`actorId` = 平局破除（设备 UUID，时钟偏移免疫）。
 * 全序确定 ⇒ LWW 合并任意设备/任意顺序收敛到同一版本。
 */
data class RowVersion(val seq: Long, val actorId: String) : Comparable<RowVersion> {
    override fun compareTo(other: RowVersion): Int =
        compareValuesBy(this, other, { it.seq }, { it.actorId })
}

/**
 * 一条不可变同步操作（CRDT 事实源）。
 *
 * **[baseSeq] 语义（U-3 裁定，写操作必须遵守）**：
 * - `UPSERT`：编辑时观察到的行 `versionSeq`（**新建 = null**），同时 `seq = baseSeq + 1`；
 *   并发编辑（互不观察）按 [RowVersion] LWW，落败版以 `TrashKind.OVERWRITE` 进回收站；
 *   串行覆盖（观察到了对方）历史**不留底**。
 * - `DELETE`：被删行当时的 `versionSeq`，不允许为 null——observed-remove 判据：
 *   `delete.baseSeq ≥ upsert.seq` 才杀死该更新。
 * - `TRASH_ACT`：恒为 null（`seq = 0`）。
 */
data class SyncOp(
    val opId: String,
    val rowKind: RowKind,
    val rowSyncId: String,
    val opType: OpType,
    val actorId: String,
    val memberId: String?,
    val seq: Long,
    val baseSeq: Long?,
    val payload: JSONObject,
    val createdAt: Long,
)

/** 生成操作 UUID（opId 幂等键） */
fun newOpId(): String = UUID.randomUUID().toString()

/**
 * 操作 → 落库实体。
 *
 * [applied]：REMOTE 操作先 false 落库、物化成功后 markApplied（挂起重试靠它，§3.5-6）；
 * LOCAL 操作恒 true（业务写本身就是物化）。
 */
fun SyncOp.toEntity(
    origin: String,
    applied: Boolean,
    uploaded: Boolean,
    chunkName: String? = null,
): SyncOpEntity = SyncOpEntity(
    opId = opId,
    rowKind = rowKind.value,
    rowSyncId = rowSyncId,
    opType = opType.value,
    actorId = actorId,
    memberId = memberId,
    seq = seq,
    baseSeq = baseSeq,
    payload = payload.toString(),
    origin = origin,
    applied = applied,
    uploaded = uploaded,
    chunkName = chunkName,
    createdAt = createdAt,
)

/** 落库实体 → 操作模型（payload JSON 原样带回） */
fun SyncOpEntity.toModel(): SyncOp = SyncOp(
    opId = opId,
    rowKind = RowKind.fromValue(rowKind),
    rowSyncId = rowSyncId,
    opType = OpType.fromValue(opType),
    actorId = actorId,
    memberId = memberId,
    seq = seq,
    baseSeq = baseSeq,
    payload = JSONObject(payload),
    createdAt = createdAt,
)
