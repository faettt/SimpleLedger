package com.simpleledger.app

import com.simpleledger.app.data.local.MigrationSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移 SQL 常量回归单测。
 *
 * 说明（可行性结论）：真正的 Room 迁移测试需 `androidx.room:room-testing` 的
 * [androidx.room.testing.MigrationTestHelper]，它依赖 Android Instrumentation，
 * 只能在真机/模拟器上跑（`connectedAndroidTest`），**本机 `testDebugUnitTest` 跑不了**，
 * 且本地 Gradle 缓存中无 room-testing 依赖、项目也无 androidTest 源集，无法在此环境落地。
 *
 * 退而求其次：把迁移 SQL 抽成无 Android 依赖的常量 [MigrationSql]，用普通 JVM 单测钉死其文本。
 * 这能防住最危险的一类回归——有人改了迁移语句却没人发现（改了就会红）。
 */
class MigrationSqlTest {

    @Test
    fun `v1 to v2 migration adds budget column with default zero`() {
        assertEquals(
            "ALTER TABLE sections ADD COLUMN budgetCents INTEGER NOT NULL DEFAULT 0",
            MigrationSql.ADD_SECTION_BUDGET_CENTS,
        )
    }

    @Test
    fun `migration sql is a single ALTER TABLE statement`() {
        // 防呆：不能是空串，也不能混入多条语句（execSQL 一次只执行一条）
        assertTrue(MigrationSql.ADD_SECTION_BUDGET_CENTS.startsWith("ALTER TABLE sections"))
        assertEquals(
            0,
            MigrationSql.ADD_SECTION_BUDGET_CENTS.count { it == ';' },
        )
    }

    @Test
    fun `budget column is not null with zero default - matches entity`() {
        // 字段名 / NOT NULL / DEFAULT 三者必须与 SectionEntity.budgetCents 声明一致
        assertTrue(MigrationSql.ADD_SECTION_BUDGET_CENTS.contains("budgetCents INTEGER NOT NULL DEFAULT 0"))
    }
}