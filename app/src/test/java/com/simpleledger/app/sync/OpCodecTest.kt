package com.simpleledger.app.sync

import com.simpleledger.app.sync.op.OpCodec
import com.simpleledger.app.sync.op.OpType
import com.simpleledger.app.sync.op.RowKind
import com.simpleledger.app.sync.op.SyncOp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操作 JSON 往返（T-2/T-3 配套）：字段名稳定、null 缺省口径（严禁 0L）、分片往返。
 */
class OpCodecTest {

    @Test
    fun upsertRoundTripWithAllFields() {
        val op = SyncOp(
            opId = "op-1",
            rowKind = RowKind.ENTRY,
            rowSyncId = "f".repeat(32),
            opType = OpType.UPSERT,
            actorId = "actor-a",
            memberId = "member-a",
            seq = 3,
            baseSeq = 2,
            payload = OpCodec.entrySnapshot(
                type = 0, amountCents = 12345, categorySyncId = "c".repeat(32),
                sectionSyncId = "s".repeat(32), entryTime = 1_700_000_000_000,
                note = "拿铁", reconciled = true, reimburseState = 1,
                createdAt = 1_600_000_000_000, updatedAt = 1_650_000_000_000,
                memberSyncId = "member-a",
            ),
            createdAt = 1_650_000_000_001,
        )
        val decoded = OpCodec.fromJson(OpCodec.toJson(op))
        assertEquals(op.opId, decoded.opId)
        assertEquals(op.rowKind, decoded.rowKind)
        assertEquals(op.rowSyncId, decoded.rowSyncId)
        assertEquals(op.opType, decoded.opType)
        assertEquals(op.actorId, decoded.actorId)
        assertEquals(op.memberId, decoded.memberId)
        assertEquals(op.seq, decoded.seq)
        assertEquals(op.baseSeq, decoded.baseSeq)
        assertEquals(op.createdAt, decoded.createdAt)
        assertEquals(op.payload.toString(), decoded.payload.toString())
    }

    @Test
    fun nullFieldsAreOmittedNotZero() {
        val op = SyncOp(
            opId = "op-2",
            rowKind = RowKind.CATEGORY,
            rowSyncId = "c".repeat(32),
            opType = OpType.UPSERT,
            actorId = "actor-a",
            memberId = null,
            seq = 1,
            baseSeq = null,
            payload = OpCodec.categorySnapshot(
                name = "餐饮", iconId = 3, type = 0, sectionSyncId = null, sortOrder = 0,
            ),
            createdAt = 1L,
        )
        val json = OpCodec.toJson(op)
        // null 落 JSON 为**缺省**，严禁 0L 冒充（§7-3：全局分类归属就是「没有这个键」）
        assertFalse(json.has("memberId"))
        assertFalse(json.has("baseSeq"))
        assertFalse(json.getJSONObject("payload").has("sectionSyncId"))

        val decoded = OpCodec.fromJson(json)
        assertEquals(null, decoded.memberId)
        assertEquals(null, decoded.baseSeq)
        assertFalse(decoded.payload.has("sectionSyncId"))
    }

    @Test
    fun deletePayloadCarriesDeletedAtMarker() {
        val snapshot = OpCodec.imageSnapshot(entrySyncId = "e".repeat(32), contentHash = "h".repeat(64), sortOrder = 1)
        val withMarker = OpCodec.withDeletedAt(snapshot, 123456L)
        assertEquals(123456L, withMarker.getLong("_deletedAt"))
        assertFalse(snapshot.has("_deletedAt")) // 原快照不被污染
        assertFalse(OpCodec.withoutDeletedAt(withMarker).has("_deletedAt"))
    }

    @Test
    fun chunkRoundTrip() {
        val ops = listOf(
            SyncOp(
                "op-a", RowKind.SECTION, "s".repeat(32), OpType.UPSERT, "actor-a", "m", 1, null,
                OpCodec.sectionSnapshot("日常开支", 1, "", 0, 0, 0, 100L), 100L,
            ),
            SyncOp(
                "op-b", RowKind.TRASH, "op-x", OpType.TRASH_ACT, "actor-b", null, 0, null,
                OpCodec.trashActSnapshot("RESTORE"), 101L,
            ),
            SyncOp(
                "op-c", RowKind.ENTRY, "e".repeat(32), OpType.DELETE, "actor-a", "m", 0, 5,
                OpCodec.withDeletedAt(
                    OpCodec.entrySnapshot(
                        0, 500, "c".repeat(32), "s".repeat(32), 200L, "", false, 0, 90L, 95L, null,
                    ),
                    102L,
                ),
                102L,
            ),
        )
        val decoded = OpCodec.decodeChunk(OpCodec.encodeChunk(ops))
        assertEquals(ops.map { it.opId }, decoded.map { it.opId })
        assertEquals(ops.map { it.opType }, decoded.map { it.opType })
        assertEquals(ops.map { it.payload.toString() }, decoded.map { it.payload.toString() })
    }

    @Test
    fun stableFieldNamesNeverDrift() {
        val json = OpCodec.toJson(
            SyncOp("op", RowKind.SETTING, "k", OpType.UPSERT, "a", null, 1, null,
                OpCodec.settingSnapshot("hide_amounts", "true"), 1L),
        )
        assertEquals(
            setOf("opId", "rowKind", "rowSyncId", "opType", "actorId", "seq", "createdAt", "payload"),
            keyNames(json),
        )
        val payload = json.getJSONObject("payload")
        assertEquals(setOf("key", "value"), keyNames(payload))
        assertTrue(OpCodec.settingSnapshot("hide_amounts", "true").getString("value").isNotEmpty())
    }

    /** android.jar 的 org.json 无 keySet()——用 keys() 迭代器列出键名 */
    private fun keyNames(json: JSONObject): Set<String> {
        val names = mutableSetOf<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) names.add(iterator.next())
        return names
    }
}
