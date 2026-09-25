package com.simpleledger.app.sync.op

import com.simpleledger.app.data.local.entity.OpTypeValue
import com.simpleledger.app.data.local.entity.RowKindValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * 操作 ↔ JSON 编解码（org.json，Android 内置，§1.2）+ §3.7 载荷快照构造。
 *
 * 分片文件格式 = JSON **数组**（元素为单操作对象）；字段名稳定不复用
 * （§7-5：改字段 = 加新字段 + 弃旧字段）。
 *
 * ⚠️ null 口径：`memberId` / `baseSeq` / `sectionSyncId` 为 null 时 JSON **缺省该键**
 * （严禁写 0L/空串冒充——全局分类的归属就是「没有这个键」）。
 */
object OpCodec {

    /** 单操作 → JSON 对象 */
    fun toJson(op: SyncOp): JSONObject {
        val json = JSONObject()
            .put("opId", op.opId)
            .put("rowKind", op.rowKind.value)
            .put("rowSyncId", op.rowSyncId)
            .put("opType", op.opType.value)
            .put("actorId", op.actorId)
            .put("seq", op.seq)
            .put("createdAt", op.createdAt)
        if (op.memberId != null) json.put("memberId", op.memberId)
        if (op.baseSeq != null) json.put("baseSeq", op.baseSeq)
        json.put("payload", op.payload)
        return json
    }

    /** JSON 对象 → 单操作 */
    fun fromJson(json: JSONObject): SyncOp = SyncOp(
        opId = json.getString("opId"),
        rowKind = RowKind.fromValue(json.getString("rowKind")),
        rowSyncId = json.getString("rowSyncId"),
        opType = OpType.fromValue(json.getString("opType")),
        actorId = json.getString("actorId"),
        memberId = if (json.has("memberId")) json.getString("memberId") else null,
        seq = json.getLong("seq"),
        baseSeq = if (json.has("baseSeq")) json.getLong("baseSeq") else null,
        payload = json.getJSONObject("payload"),
        createdAt = json.getLong("createdAt"),
    )

    /** 一批操作 → 分片明文（JSON 数组） */
    fun encodeChunk(ops: List<SyncOp>): String {
        val array = JSONArray()
        ops.forEach { array.put(toJson(it)) }
        return array.toString()
    }

    /** 分片明文 → 一批操作 */
    fun decodeChunk(text: String): List<SyncOp> {
        val array = JSONArray(text)
        return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
    }

    // ------------------------------------------------------------ 载荷快照（§3.7）

    /** SECTION：`{name, iconId, note, budgetCents, colorIndex, sortOrder, createdAt}` */
    fun sectionSnapshot(
        name: String,
        iconId: Int,
        note: String,
        budgetCents: Long,
        colorIndex: Int,
        sortOrder: Int,
        createdAt: Long,
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("iconId", iconId)
        .put("note", note)
        .put("budgetCents", budgetCents)
        .put("colorIndex", colorIndex)
        .put("sortOrder", sortOrder)
        .put("createdAt", createdAt)

    /** CATEGORY：全局分类 `sectionSyncId = null` → **缺省键**（严禁 0L） */
    fun categorySnapshot(
        name: String,
        iconId: Int,
        type: Int,
        sectionSyncId: String?,
        sortOrder: Int,
    ): JSONObject {
        val json = JSONObject()
            .put("name", name)
            .put("iconId", iconId)
            .put("type", type)
            .put("sortOrder", sortOrder)
        if (sectionSyncId != null) json.put("sectionSyncId", sectionSyncId)
        return json
    }

    /** ENTRY：`memberSyncId` 为 null（未知成员）→ 缺省键 */
    fun entrySnapshot(
        type: Int,
        amountCents: Long,
        categorySyncId: String,
        sectionSyncId: String,
        entryTime: Long,
        note: String,
        reconciled: Boolean,
        reimburseState: Int,
        createdAt: Long,
        updatedAt: Long,
        memberSyncId: String?,
    ): JSONObject {
        val json = JSONObject()
            .put("type", type)
            .put("amountCents", amountCents)
            .put("categorySyncId", categorySyncId)
            .put("sectionSyncId", sectionSyncId)
            .put("entryTime", entryTime)
            .put("note", note)
            .put("reconciled", reconciled)
            .put("reimburseState", reimburseState)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
        if (memberSyncId != null) json.put("memberSyncId", memberSyncId)
        return json
    }

    /** IMAGE：`{entrySyncId, contentHash, sortOrder}` */
    fun imageSnapshot(
        entrySyncId: String,
        contentHash: String,
        sortOrder: Int,
    ): JSONObject = JSONObject()
        .put("entrySyncId", entrySyncId)
        .put("contentHash", contentHash)
        .put("sortOrder", sortOrder)

    /** MEMBER：`{name, hidden, createdAt}` */
    fun memberSnapshot(name: String, hidden: Boolean, createdAt: Long): JSONObject = JSONObject()
        .put("name", name)
        .put("hidden", hidden)
        .put("createdAt", createdAt)

    /** SETTING：`{key, value}`（key ∈ AppSettings 六项） */
    fun settingSnapshot(key: String, value: String): JSONObject = JSONObject()
        .put("key", key)
        .put("value", value)

    /** TRASH_ACT：`{action: RESTORE | PURGE}` */
    fun trashActSnapshot(action: String): JSONObject = JSONObject().put("action", action)

    /** DELETE 载荷 = 被删版本快照 + `_deletedAt`（回收站展示用） */
    fun withDeletedAt(snapshot: JSONObject, deletedAt: Long): JSONObject =
        JSONObject(snapshot.toString()).put("_deletedAt", deletedAt)

    /** 回插/撤销复活用：剥掉 `_deletedAt` 还原 UPSERT 形态的快照 */
    fun withoutDeletedAt(snapshot: JSONObject): JSONObject =
        JSONObject(snapshot.toString()).apply { remove("_deletedAt") }

    /** 常量表（与 `SyncEntities` 对齐；JSON 文本值与枚举 value 同源） */
    object Fields {
        const val OP_ID = "opId"
        const val ROW_KIND = "rowKind"
        const val ROW_SYNC_ID = "rowSyncId"
        const val OP_TYPE = "opType"
        const val ACTOR_ID = "actorId"
        const val MEMBER_ID = "memberId"
        const val SEQ = "seq"
        const val BASE_SEQ = "baseSeq"
        const val PAYLOAD = "payload"
        const val CREATED_AT = "createdAt"

        const val ROW_KIND_TRASH = RowKindValue.TRASH
        const val OP_TYPE_UPSERT = OpTypeValue.UPSERT
        const val OP_TYPE_DELETE = OpTypeValue.DELETE
        const val OP_TYPE_TRASH_ACT = OpTypeValue.TRASH_ACT
    }
}
