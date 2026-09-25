package com.simpleledger.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 多端同步（v5）的四张支撑表：成员 / 操作日志 / 冲突回收站 / 云端文件台账。
 *
 * 与 [SectionEntity] 等业务表的分工：业务表存**行投影**（用户看到的当前状态），
 * 这里的 `sync_ops` 是**事实源**（不可变操作日志，CRDT），`sync_trash` 是由 DELETE /
 * OVERWRITE 操作推导出的留底物化（键 = 操作 opId ⇒ 幂等），`sync_remote_files` 是增量
 * 发现用的云端文件游标。
 *
 * ⚠️ 全部行标识用 `syncId`（32 字符小写），本地自增 Long id 永不出现在操作载荷里。
 */

/** 回收站留底来源（U-3 裁定）：删除留底与「被修改覆盖」留底语义不同，必须可区分 */
object TrashKind {
    /** 删除留底：由 DELETE 操作推导（R-08 纯删除 / 删改冲突都留底） */
    const val DELETE = "DELETE"

    /**
     * 被修改覆盖留底：并发编辑（互不观察）时按 (seq, actorId) LWW 的**落败版**快照
     * （U-3 裁定）。串行覆盖历史**不留底**——只有并发竞争的败方才进回收站。
     */
    const val OVERWRITE = "OVERWRITE"
}

/** 回收站条目状态（`sync_trash.resolved`），由 TRASH_ACT 操作推进 */
object TrashResolved {
    /** 可见（默认）——列表里可「恢复」或「彻底删除」 */
    const val VISIBLE = 0

    /** 已恢复到账本（TRASH_ACT(RESTORE)） */
    const val RESTORED = 1

    /** 已彻底删除（TRASH_ACT(PURGE)）；条目保留至 90 天自动清理（R-21） */
    const val PURGED = 2
}

/** `sync_ops.rowKind` / `sync_trash.rowKind` 取值（与 T-3 `sync/op/SyncOp.kt` 的 RowKind 枚举一一对应） */
object RowKindValue {
    const val SECTION = "SECTION"
    const val CATEGORY = "CATEGORY"
    const val ENTRY = "ENTRY"
    const val IMAGE = "IMAGE"
    const val MEMBER = "MEMBER"
    const val SETTING = "SETTING"
    const val TRASH = "TRASH"
}

/** `sync_ops.opType` 取值（与 T-3 的 OpType 枚举一一对应） */
object OpTypeValue {
    /** 行快照全量写入（幂等回放的「生」） */
    const val UPSERT = "UPSERT"

    /** 观察者删除（observed-remove，携带 baseSeq）；payload = 被删版本快照 */
    const val DELETE = "DELETE"

    /** 回收站动作：`{action: RESTORE | PURGE}`，`rowSyncId` = 目标留底的 deleteOpId */
    const val TRASH_ACT = "TRASH_ACT"
}

/** `sync_ops.origin` 取值 */
object OpOrigin {
    /** 本机产生（待上传 outbox） */
    const val LOCAL = "LOCAL"

    /** 云端拉回（待/已回放） */
    const val REMOTE = "REMOTE"
}

/** `sync_remote_files.kind` 取值 */
object RemoteFileKind {
    /** 操作分片（随机名 + ".op"） */
    const val OP_CHUNK = "OP_CHUNK"

    /** 照片（内容寻址确定性假名） */
    const val PHOTO = "PHOTO"

    /** 元文件（确定性假名，含 KDF 参数 / KCV） */
    const val META = "META"
}

/**
 * 成员（家人共记一本账，全员全权；可隐藏不可删——主理人拍板）。
 *
 * `syncId` 即成员 UUID（认领时生成），也是账目 `EntryEntity.memberId` 的指向目标。
 * 成员名全书唯一；并发撞名时双留 + UI 提示改名（U-10），不在 DB 层加唯一约束。
 */
@Entity(tableName = "members")
data class MemberEntity(
    /** 成员 UUID（32 字符小写），认领时生成 */
    @PrimaryKey val syncId: String,
    /** 显示名（全书唯一；改名对历史账目展示即时生效，R-20） */
    val name: String,
    /** 隐藏标记：成员可隐藏不可删（隐藏后记账选择器不展示，历史标签保留） */
    val hidden: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** 行级 Lamport 因果计数（v5），见 [SectionEntity.versionSeq] */
    val versionSeq: Long = 0,
)

/**
 * 操作日志（CRDT 事实源；不可变，`applied` / `uploaded` 为本地簿记）。
 *
 * **U-3 裁定后的 [baseSeq] 语义**（写操作时必须遵守）：
 * - `UPSERT`：编辑时观察到的行 `versionSeq`（**新建 = null**）；同时 `seq = baseSeq + 1`。
 *   并发编辑（互不观察）按 `(seq, actorId)` LWW，落败版以 `TrashKind.OVERWRITE` 进回收站；
 *   串行覆盖（观察到了对方）历史不留底。
 * - `DELETE`：被删行当时的 `versionSeq`（observed-remove 判据：`delete.baseSeq ≥ upsert.seq`
 *   才杀死该更新），不允许为 null。
 * - `TRASH_ACT`：恒为 null（`seq = 0`）。
 *
 * RESTORE 回插按**串行覆盖**记账（`baseSeq = 当前 max seq`），不会与既有更新冲突双留。
 */
@Entity(
    tableName = "sync_ops",
    indices = [
        // 索引名是显式契约（迁移 SQL 用同名创建，Room schema 校验对名字敏感）
        Index(value = ["uploaded", "createdAt"], name = "index_sync_ops_outbox"),
        Index(value = ["rowKind", "rowSyncId"], name = "index_sync_ops_row"),
    ],
)
data class SyncOpEntity(
    /** 操作 UUID；`INSERT OR IGNORE` 幂等 */
    @PrimaryKey val opId: String,
    /** 目标行类型，取值见 [RowKindValue] */
    val rowKind: String,
    /** 目标行 syncId（`TRASH_ACT` 时 = 目标留底的 deleteOpId） */
    val rowSyncId: String,
    /** 取值见 [OpTypeValue] */
    val opType: String,
    /** 产生设备 UUID（SyncPrefs.deviceId）；LWW 平局按它字典序破 */
    val actorId: String,
    /** 操作人成员 syncId（展示「谁记的 / 谁删的」）；null = 未知成员 */
    val memberId: String?,
    /** 行级 Lamport：UPSERT = baseSeq + 1；DELETE = 0；TRASH_ACT = 0 */
    val seq: Long,
    /** 语义见类注释（U-3 裁定）；新建行的 UPSERT 与 TRASH_ACT 为 null */
    val baseSeq: Long?,
    /** JSON：UPSERT = 行快照；DELETE = 被删版本快照 + `_deletedAt`；TRASH_ACT = `{action}` */
    val payload: String,
    /** 取值见 [OpOrigin] */
    val origin: String,
    /** REMOTE 挂起 = false（引用未到，下轮重试，§3.5 规则 6） */
    val applied: Boolean = true,
    /** LOCAL outbox 簿记：false = 待传 */
    val uploaded: Boolean = false,
    /** 已上传到的云端分片文件假名 */
    val chunkName: String? = null,
    val createdAt: Long,
)

/**
 * 冲突回收站（持久层留底，R-08/R-09；由 DELETE / OVERWRITE 操作推导物化）。
 *
 * 与本地 4 秒撤销（`cache/parked_images`）分层并存：撤销窗口 = 体验层，这里 = 持久层，
 * 卸载重装 / 跨设备仍可恢复（R-09）。
 *
 * [kind]（U-3 裁定）：`DELETE` = 删除留底；`OVERWRITE` = 并发编辑 LWW 落败版留底。
 * [deleteOpId] 为幂等键 = `sync_ops.opId`：
 * - `kind = DELETE` → 该 DELETE 操作的 opId；
 * - `kind = OVERWRITE` → 落败的那次 UPSERT 操作的 opId。
 */
@Entity(tableName = "sync_trash")
data class ConflictTrashEntity(
    /** 幂等键，语义见类注释 */
    @PrimaryKey val deleteOpId: String,
    /** 取值见 [RowKindValue] */
    val rowKind: String,
    /** 被留底行的 syncId */
    val rowSyncId: String,
    /** 留底来源，取值见 [TrashKind] */
    val kind: String = TrashKind.DELETE,
    /** 被留底版本快照 JSON（成员 / 时间 / 金额 / 照片引用…） */
    val snapshot: String,
    val deletedAt: Long,
    /** 删除方成员 syncId（`kind = OVERWRITE` 时为落败编辑的作者）；null = 未知成员 */
    val deletedByMemberId: String?,
    /** true = 删/改冲突（行仍存活，R-08 双留），false = 行已死 */
    val conflict: Boolean = false,
    /** 冲突对方设备 actorId（展示「被 X 删除，被 Y 修改」） */
    val conflictActorId: String? = null,
    /** 推进状态，取值见 [TrashResolved] */
    val resolved: Int = TrashResolved.VISIBLE,
)

/**
 * 云端文件台账（增量发现用的游标；`remoteName` 本身是假名/随机名，无可读信息，S4）。
 */
@Entity(tableName = "sync_remote_files")
data class RemoteFileEntity(
    /** 云端文件名（HMAC 假名或随机名，永不出现明文名） */
    @PrimaryKey val remoteName: String,
    /** 取值见 [RemoteFileKind] */
    val kind: String,
    /** PROPFIND/HEAD 返回的 etag（条件写 If-Match 用） */
    val etag: String?,
    val size: Long = 0,
    /** >0 = 已下载到本地（分片）；epoch millis */
    val downloadedAt: Long = 0,
    /** >0 = 本机上传（照片 / 分片）；epoch millis */
    val uploadedAt: Long = 0,
)
