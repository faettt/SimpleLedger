package com.simpleledger.app.data.local.dao

import com.simpleledger.app.data.local.MigrationSql
import com.simpleledger.app.logic.OpTrimRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * `SyncDao.latestUpsertPerRow` 的**真实 SQLite** 语义钉子（评审 R1-high 修复配套）。
 *
 * 修的是什么：`GROUP BY` 下裸列表达式（不带聚合）SQLite 只取组内任意一行
 * （sqlite3 3.50.6 实测为组内第一行）——旧 SQL `printf(...) AS winnerKey` 返回的不是
 * 全序最大 UPSERT 而是最老那条，裁剪会把真 LWW 胜者当可裁删除，一条 baseSeq 恰在其间
 * 的 DELETE 即可造成跨设备永久分歧。修复后必须 `max(printf(...))` 聚合。
 *
 * 为什么不是 Room in-memory：JVM 单测没有 Android 运行时（项目未引入 Robolectric），
 * 而 Room 对 `@Query` 是**原样透传 SQL 文本**给 SQLite——语义由 SQLite 引擎决定，
 * 对「同一建表语句 + 同一条 SQL」跑真实 SQLite 即可钉死语义。零漂移保证：
 * - SQL：测试直接引用生产注解用的同一份常量 [SyncDao.LATEST_UPSERT_PER_ROW_SQL]；
 * - 建表：[MigrationSql.CREATE_TABLE_SYNC_OPS]（v5 迁移冻结语句，与 Room 实体 schema
 *   迁移校验等价）。
 *
 * 覆盖场景（期望值一律经 [OpTrimRules.winnerKey] 构造——顺带钉死 SQL `printf` 与
 * Kotlin `%020d|%s|%s` 的格式一致契约）：
 * 1. 组内 seq=5 先插、seq=9 后插 → 取 seq=9（评审员复现口径；旧 SQL 错取 seq=5）；
 * 2. 倒序插入（seq=9 先插）→ 仍取 seq=9（与插入顺序无关）；
 * 3. seq 平局 → actorId 字典序大者胜；
 * 4. seq + actorId 双平局 → opId 字典序大者胜；
 * 5. 组内混入全序更大的 DELETE → WHERE opType='UPSERT' 必须排除它。
 *
 * 引擎：`sqlite3` CLI——macOS 与 GitHub CI（ubuntu-latest）均预装；缺失时按 JUnit
 * 假设**跳过**（测试报告可见 skip，不是静默通过）。
 */
class SyncDaoLatestUpsertSqlTest {

    /** 种子操作（opId, rowKind, rowSyncId, opType, actorId, seq），数组顺序 = 插入顺序 */
    private data class SeedOp(
        val opId: String,
        val rowKind: String,
        val rowSyncId: String,
        val opType: String,
        val actorId: String,
        val seq: Long,
    )

    private val seeds = listOf(
        // row-1：场景 1（seq=5 先插 → seq=9 后插，胜者 op-2；旧 SQL 错取 op-1）
        SeedOp("op-1", "ENTRY", "row-1", "UPSERT", "device-A", 5L),
        SeedOp("op-2", "ENTRY", "row-1", "UPSERT", "device-A", 9L),
        // row-2：场景 2（seq=9 先插 → seq=5 后插，胜者 op-3；证明与插入顺序无关）
        SeedOp("op-3", "ENTRY", "row-2", "UPSERT", "device-A", 9L),
        SeedOp("op-4", "ENTRY", "row-2", "UPSERT", "device-A", 5L),
        // row-3：场景 3（seq 双 7 平局，device-Z > device-B，胜者 op-6）
        SeedOp("op-5", "CATEGORY", "row-3", "UPSERT", "device-B", 7L),
        SeedOp("op-6", "CATEGORY", "row-3", "UPSERT", "device-Z", 7L),
        // row-4：场景 4（seq + actorId 双平局，"op-8" > "op-7"，胜者 op-8）
        SeedOp("op-7", "CATEGORY", "row-4", "UPSERT", "device-Z", 7L),
        SeedOp("op-8", "CATEGORY", "row-4", "UPSERT", "device-Z", 7L),
        // row-5：场景 5（DELETE op-10 全序最大但必须被 WHERE 排除；
        //          两条 UPSERT seq+actor 双平局，"op-99" > "op-90"，胜者 op-99——最后插入）
        SeedOp("op-90", "ENTRY", "row-5", "UPSERT", "device-A", 7L),
        SeedOp("op-10", "ENTRY", "row-5", "DELETE", "device-X", 9L),
        SeedOp("op-99", "ENTRY", "row-5", "UPSERT", "device-A", 7L),
    )

    /** 期望胜者：key = "rowKind|rowSyncId"，value = (seq, actorId, opId) 全序最大 UPSERT */
    private val expectedWinners = mapOf(
        "ENTRY|row-1" to Triple(9L, "device-A", "op-2"),
        "ENTRY|row-2" to Triple(9L, "device-A", "op-3"),
        "CATEGORY|row-3" to Triple(7L, "device-Z", "op-6"),
        "CATEGORY|row-4" to Triple(7L, "device-Z", "op-8"),
        "ENTRY|row-5" to Triple(7L, "device-A", "op-99"),
    )

    /** 常量级 tripwire：任何环境都跑——生产 SQL 必须带 `max(printf(` 聚合 */
    @Test
    fun productionSql_usesMaxAggregateNotBareColumn() {
        val sql = SyncDao.LATEST_UPSERT_PER_ROW_SQL
        assertTrue(
            "latestUpsertPerRow 必须用 max(printf(...)) 聚合取全序最大 UPSERT" +
                "（GROUP BY 裸列表达式只取组内任意一行，实测 3.50.6 取第一行）",
            Regex("max\\s*\\(\\s*printf\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(sql),
        )
    }

    /** 真实 SQLite 实跑：同一条 SQL + 同一建表语句，断言五组胜者与全序最大一致 */
    @Test
    fun latestUpsertPerRow_returnsFullOrderMaxUpsertPerRow() {
        val binary = findSqlite3()
        assumeTrue(
            "sqlite3 CLI 不可用，跳过（本测试需要真实 SQLite 钉死 SQL 语义；" +
                "macOS 与 GitHub ubuntu runner 均预装）",
            binary != null,
        )

        // 输出（5 条胜者 + 1 条 count）总计 < 1KB，远小于管道缓冲（64KB），
        // 先写完 stdin 再读 stdout 不会死锁。
        val script = buildString {
            appendLine(".bail on") // 任一 SQL 出错即非零退出，杜绝「静默跑空」假绿
            appendLine(".mode tabs")
            appendLine(MigrationSql.CREATE_TABLE_SYNC_OPS + ";")
            seeds.forEachIndexed { index, op -> appendLine(op.insertSql(createdAt = 100L + index)) }
            appendLine(SyncDao.LATEST_UPSERT_PER_ROW_SQL.trimIndent() + ";")
            appendLine("SELECT 'count=' || COUNT(*) FROM (" + SyncDao.LATEST_UPSERT_PER_ROW_SQL.trimIndent() + ");")
        }
        val output = runSqlite3(binary!!, script)
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()

        // 结果集形状：每组恰好一条胜者（count 行 + 5 条胜者行）
        assertEquals("count=5 之外不应有其他前缀行", 1, output.count { it.startsWith("count=") })
        assertEquals("count 行应报告每行组一条胜者", "count=${expectedWinners.size}", output.last())

        val actual = output.filterNot { it.startsWith("count=") }.associate { line ->
            val parts = line.split("\t")
            assertEquals("胜者行应为 rowKind\\trowSyncId\\twinnerKey 三段：$line", 3, parts.size)
            (parts[0] + "|" + parts[1]) to parts[2]
        }

        // 逐行断言：期望值经 OpTrimRules.winnerKey 构造 —— SQL printf 与 Kotlin
        // %020d|%s|%s 两侧格式一致契约在此一并钉死（任何一侧漂移都会在此失败）。
        expectedWinners.forEach { (rowKey, winner) ->
            val (seq, actorId, opId) = winner
            assertEquals(
                "row=$rowKey 的胜者必须为 (seq, actorId, opId) 全序最大 UPSERT",
                OpTrimRules.winnerKey(seq, actorId, opId),
                actual[rowKey],
            )
        }
        assertEquals("结果行数应与组数一致", expectedWinners.size, actual.size)
    }

    /* ---------- sqlite3 CLI 执行辅助 ---------- */

    private fun findSqlite3(): String? =
        listOf("sqlite3", "/usr/bin/sqlite3", "/opt/homebrew/bin/sqlite3", "/usr/local/bin/sqlite3")
            .firstOrNull { candidate ->
                runCatching {
                    val probe = ProcessBuilder(candidate, "--version").redirectErrorStream(true).start()
                    probe.outputStream.close()
                    val finished = probe.waitFor(10, TimeUnit.SECONDS)
                    if (!finished) probe.destroyForcibly()
                    finished && probe.exitValue() == 0
                }.getOrDefault(false)
            }

    private fun runSqlite3(binary: String, script: String): String {
        val process = ProcessBuilder(binary, "-batch").redirectErrorStream(true).start()
        process.outputStream.use { it.write(script.toByteArray(Charsets.UTF_8)) }
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(30, TimeUnit.SECONDS)) { "sqlite3 30 秒未退出（脚本死锁？）" }
        check(process.exitValue() == 0) {
            "sqlite3 退出码 ${process.exitValue()} ≠ 0（.bail on 下即 SQL 出错）。输出：\n$output"
        }
        return output
    }

    private fun SeedOp.insertSql(createdAt: Long): String {
        listOf(opId, rowKind, rowSyncId, opType, actorId).forEach { value ->
            check("'" !in value) { "种子常量含单引号，需先加转义再入库：$value" }
        }
        return "INSERT INTO sync_ops " +
            "(opId, rowKind, rowSyncId, opType, actorId, seq, payload, origin, createdAt) " +
            "VALUES ('$opId', '$rowKind', '$rowSyncId', '$opType', '$actorId', $seq, '{}', 'REMOTE', $createdAt);"
    }
}
