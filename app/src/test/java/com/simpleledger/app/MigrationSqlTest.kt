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
 *
 * v2 → v3 新增 3 条断言：新增归属列文本 / 索引常量 / 装修专属分类 INSERT 常量。
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

    /* ---------------------------------------------------------------- v2 → v3 */

    @Test
    fun `v2 to v3 adds nullable category section column`() {
        assertEquals(
            "ALTER TABLE categories ADD COLUMN sectionId INTEGER",
            MigrationSql.ADD_CATEGORY_SECTION_ID,
        )
        // 必须**可空**（NULL = 全局）；若误写成 NOT NULL DEFAULT 0 会让既有分类被当成「分区 0 专属」
        assertTrue(MigrationSql.ADD_CATEGORY_SECTION_ID.contains("sectionId INTEGER"))
        assertTrue(!MigrationSql.ADD_CATEGORY_SECTION_ID.contains("NOT NULL"))
        // 单语句、无分号
        assertEquals(0, MigrationSql.ADD_CATEGORY_SECTION_ID.count { it == ';' })
        assertTrue(MigrationSql.ADD_CATEGORY_SECTION_ID.startsWith("ALTER TABLE categories"))
    }

    @Test
    fun `v2 to v3 creates section index with room-consistent name`() {
        assertEquals(
            "CREATE INDEX IF NOT EXISTS index_categories_sectionId ON categories (sectionId)",
            MigrationSql.CREATE_CATEGORY_SECTION_INDEX,
        )
        // 索引名必须与 Room 由 Index("sectionId") 生成的名字一致，否则 schema 校验失败
        assertTrue(MigrationSql.CREATE_CATEGORY_SECTION_INDEX.contains("index_categories_sectionId"))
        assertEquals(0, MigrationSql.CREATE_CATEGORY_SECTION_INDEX.count { it == ';' })
    }

    @Test
    fun `v2 to v3 inserts the seven decoration-section categories`() {
        val sql = MigrationSql.INSERT_SECTION_FIRST_CATEGORIES
        // 单条语句：不能混入分号（execSQL 一次只执行一条）
        assertEquals(0, sql.count { it == ';' })
        assertTrue(sql.startsWith("INSERT INTO categories"))
        assertTrue(sql.contains("UNION ALL"))
        // 7 个装修专属分类名一个都不能少
        listOf("主材", "人工", "家具", "家电", "设计费", "报销", "退款").forEach { name ->
            assertTrue("缺少装修专属分类：$name", sql.contains(name))
        }
        // 通过名字子查询定位装修分区（改名时子查询返回 NULL → 落为全局，不丢数据、不报错）
        assertTrue(sql.contains("SELECT id FROM sections WHERE name = '装修'"))
    }
}
