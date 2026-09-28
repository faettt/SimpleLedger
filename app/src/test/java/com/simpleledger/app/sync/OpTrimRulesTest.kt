package com.simpleledger.app.sync

import com.simpleledger.app.logic.OpTrimRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-12 裁剪规则纯函数矩阵（[OpTrimRules]）：
 * 老且已传且已应用的 UPSERT 才可裁；DELETE / TRASH_ACT / TRASH 行 / outbox /
 * 挂起集 / 每行全序胜者一律保底（正确性论证见 OpTrimRules KDoc）。
 */
class OpTrimRulesTest {

    private val cutoff = 10_000L

    private fun op(
        opId: String,
        rowKind: String = "ENTRY",
        rowSyncId: String = "row-1",
        opType: String = "UPSERT",
        seq: Long = 1L,
        actorId: String = "A",
        origin: String = "LOCAL",
        uploaded: Boolean = true,
        applied: Boolean = true,
        createdAt: Long = 5_000L,
    ) = OpTrimRules.OpRow(opId, rowKind, rowSyncId, opType, seq, actorId, origin, uploaded, applied, createdAt)

    private fun winner(seq: Long, actorId: String, opId: String, rowKind: String = "ENTRY", rowSyncId: String = "row-1") =
        OpTrimRules.RowWinner(rowKind, rowSyncId, OpTrimRules.winnerKey(seq, actorId, opId))

    @Test
    fun oldUploadedAppliedUpsertsAreTrimmable() {
        val doomed = OpTrimRules.trimmableOpIds(
            candidates = listOf(op("op-1"), op("op-2", seq = 2L, actorId = "A")),
            winners = listOf(winner(2L, "A", "op-2")),
            cutoffMillis = cutoff,
        )
        assertEquals(setOf("op-1"), doomed)
    }

    @Test
    fun rowWinnerIsProtected_evenWhenOld() {
        val doomed = OpTrimRules.trimmableOpIds(
            candidates = listOf(op("winner-1", seq = 5L), op("loser-1", seq = 2L)),
            winners = listOf(winner(5L, "A", "winner-1")),
            cutoffMillis = cutoff,
        )
        assertEquals("每行全序最大 UPSERT 必须保底", setOf("loser-1"), doomed)
    }

    @Test
    fun deletesAndTrashActsNeverTrim_evenIfCandidateSlipsIn() {
        val doomed = OpTrimRules.trimmableOpIds(
            candidates = listOf(
                op("del-1", opType = "DELETE", seq = 0L),
                op("act-1", opType = "TRASH_ACT", seq = 0L),
                op("trash-row", rowKind = "TRASH"),
            ),
            winners = emptyList(),
            cutoffMillis = cutoff,
        )
        assertTrue("非 UPSERT / TRASH 行一律不裁", doomed.isEmpty())
    }

    @Test
    fun outboxAndDeferredAreDefensivelyProtected() {
        val doomed = OpTrimRules.trimmableOpIds(
            candidates = listOf(
                op("outbox-1", uploaded = false),
                op("deferred-1", applied = false),
            ),
            winners = emptyList(),
            cutoffMillis = cutoff,
        )
        assertTrue("outbox / 挂起集绝不裁", doomed.isEmpty())
    }

    @Test
    fun remoteOriginOpsAreTrimmableDespiteUploadedFalse() {
        // 远端操作的 uploaded 恒为 false（LOCAL outbox 簿记字段）——靠 origin 判非 outbox
        val doomed = OpTrimRules.trimmableOpIds(
            candidates = listOf(op("remote-1", origin = "REMOTE", uploaded = false)),
            winners = listOf(winner(1L, "A", "remote-1")),
            cutoffMillis = cutoff,
        )
        assertTrue("REMOTE 胜者仍在胜者保底内", doomed.isEmpty())

        val trimmable = OpTrimRules.trimmableOpIds(
            candidates = listOf(
                op("remote-old", origin = "REMOTE", uploaded = false, seq = 1L),
                op("remote-winner", origin = "REMOTE", uploaded = false, seq = 2L),
            ),
            winners = listOf(winner(2L, "A", "remote-winner")),
            cutoffMillis = cutoff,
        )
        assertEquals(setOf("remote-old"), trimmable)
    }

    @Test
    fun mixedRowsKeepEachRowsLatestUpsert() {
        // 混合 rowSyncId：每行各自保留全序最大 UPSERT，两行的非胜者都裁
        val doomed = OpTrimRules.trimmableOpIds(
            candidates = listOf(
                op("r1-loser", rowSyncId = "R1", seq = 1L),
                op("r1-winner", rowSyncId = "R1", seq = 2L),
                op("r2-loser", rowSyncId = "R2", seq = 5L),
                op("r2-winner", rowSyncId = "R2", seq = 9L, actorId = "B"),
            ),
            winners = listOf(
                winner(2L, "A", "r1-winner", rowSyncId = "R1"),
                winner(9L, "B", "r2-winner", rowSyncId = "R2"),
            ),
            cutoffMillis = cutoff,
        )
        assertEquals("每行只裁自己的非胜者", setOf("r1-loser", "r2-loser"), doomed)
    }

    @Test
    fun sameSeqTieBrokenByActorIdPerVersionOrder() {
        // 同 seq 平局按 (actorId, opId) 破平（与 OpMerge.VERSION_ORDER 同序）：
        // 若保底只看 seq，败者 tie-a 会被误保；必须按复合键只保 tie-b
        val doomed = OpTrimRules.trimmableOpIds(
            candidates = listOf(
                op("tie-a", seq = 3L, actorId = "A"),
                op("tie-b", seq = 3L, actorId = "B"),
            ),
            winners = listOf(winner(3L, "B", "tie-b")),
            cutoffMillis = cutoff,
        )
        assertEquals("seq 平局的 LWW 败者可裁", setOf("tie-a"), doomed)
    }

    @Test
    fun cutoffBoundaryIsStrictInequality() {
        val doomed = OpTrimRules.trimmableOpIds(
            candidates = listOf(
                op("after-cutoff", createdAt = cutoff + 1),
                op("at-cutoff", createdAt = cutoff),
                op("before-cutoff", createdAt = cutoff - 1),
            ),
            winners = emptyList(),
            cutoffMillis = cutoff,
        )
        assertEquals(setOf("before-cutoff"), doomed)
    }

    @Test
    fun winnerCompositeKeyRoundTrips() {
        val key = OpTrimRules.winnerKey(42L, "device-aaaa", "op-32hex")
        assertEquals("op-32hex", OpTrimRules.winnerOpId(key))
        // seq 零填充保证字典序 = 数值序（9 位与 10 位比较不翻车）
        val small = OpTrimRules.winnerKey(9L, "A", "x")
        val large = OpTrimRules.winnerKey(10L, "A", "y")
        assertTrue("数值序须与字典序一致", small < large)
    }

    @Test
    fun emptyInputsYieldNothing() {
        assertTrue(OpTrimRules.trimmableOpIds(emptyList(), emptyList(), cutoff).isEmpty())
    }
}
