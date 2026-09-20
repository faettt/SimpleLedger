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

    /* ---------------------------------------------------------------- v3 → v4 */

    /** execSQL 一次一条 —— 13 条语句任何一条都不能混入分号 */
    @Test
    fun `v3 to v4 statements are all single-statement`() {
        assertEquals(
            "v4 语句条数应与 MIGRATION_3_4 一致",
            13,
            MigrationSql.V4_STATEMENTS.size,
        )
        MigrationSql.V4_STATEMENTS.forEach { (name, sql) ->
            assertTrue("$name 不应为空", sql.isNotBlank())
            assertEquals("$name 混入了分号", 0, sql.count { it == ';' })
        }
    }

    @Test
    fun `v3 to v4 rebuilds categories with iconId and without emoji`() {
        val sql = MigrationSql.CREATE_CATEGORIES_V4
        assertTrue(sql.startsWith("CREATE TABLE IF NOT EXISTS `categories_new`"))
        // 列集合必须与 Room 导出的 4.json 完全一致（缺列/多列都会让启动时 schema 校验崩）
        listOf("`id`", "`name`", "`iconId` INTEGER NOT NULL", "`type`", "`sectionId` INTEGER", "`sortOrder`")
            .forEach { assertTrue("categories_new 缺少：$it", sql.contains(it)) }
        assertTrue("categories_new 不得再含 emoji 列", !sql.contains("emoji"))
        assertTrue("主键必须是 AUTOINCREMENT", sql.contains("PRIMARY KEY AUTOINCREMENT"))
    }

    @Test
    fun `v3 to v4 copy translates every legacy emoji with vs16 stripping`() {
        val sql = MigrationSql.COPY_CATEGORIES_V4
        // 左侧剥掉变体选择符：6 个旧 emoji 带 U+FE0F，不同输入法写入时可能不带
        assertTrue(sql.contains("REPLACE(`emoji`, char(65039), '')"))
        // 50 个 WHEN 一个都不能少 —— 漏一个 = 该分类升级后丢图标
        assertEquals(50, "WHEN".toRegex().findAll(sql).count())
        // 兜底必须是 43 = tag，与 IconMapping.DEFAULT_CATEGORY_ICON_ID 一致
        assertTrue(sql.contains("ELSE 43 END"))
        // 新表列与旧表列一一对应，保证 id / sortOrder 等不漂移
        assertTrue(sql.contains("SELECT `id`, `name`,"))
        assertTrue(sql.contains("FROM `categories`"))
    }

    @Test
    fun `v3 to v4 drops and renames categories in the safe order`() {
        assertEquals("DROP TABLE `categories`", MigrationSql.DROP_CATEGORIES_OLD)
        assertEquals("ALTER TABLE `categories_new` RENAME TO `categories`", MigrationSql.RENAME_CATEGORIES_V4)
        // 索引随旧表被删，RENAME 后必须重建，且名字与 Room 生成的一致。
        // ⚠️ 反引号也必须一致：Room 的 createSql 里标识符带反引号（见 3.json），
        //    少写反引号虽然 SQLite 也认，但会让「与 Room 逐字一致」这条契约名存实亡。
        assertEquals(
            "CREATE INDEX IF NOT EXISTS `index_categories_type` ON `categories` (`type`)",
            MigrationSql.RECREATE_CATEGORY_TYPE_INDEX,
        )
        assertEquals(
            "CREATE INDEX IF NOT EXISTS `index_categories_sectionId` ON `categories` (`sectionId`)",
            MigrationSql.RECREATE_CATEGORY_SECTION_INDEX,
        )
    }

    @Test
    fun `v3 to v4 rebuilds sections with iconId and tape color`() {
        val create = MigrationSql.CREATE_SECTIONS_V4
        assertTrue(create.startsWith("CREATE TABLE IF NOT EXISTS `sections_new`"))
        assertTrue(create.contains("`iconId` INTEGER NOT NULL"))
        assertTrue(create.contains("`colorIndex` INTEGER NOT NULL"))
        assertTrue(!create.contains("emoji"))
        val copy = MigrationSql.COPY_SECTIONS_V4
        assertTrue(copy.contains("REPLACE(`emoji`, char(65039), '')"))
        // 分区兜底 1 = pin（与分类的 43 不同，别抄错）
        assertTrue(copy.contains("ELSE 1 END"))
        assertEquals("ALTER TABLE `sections_new` RENAME TO `sections`", MigrationSql.RENAME_SECTIONS_V4)
    }

    @Test
    fun `v3 to v4 aligns seed section tape colors`() {
        val sql = MigrationSql.ALIGN_SECTION_COLOR_INDEX
        // 与 SectionFirstSeed 的三个初始分区一致：装修=赭黄 2 / 旅行=灰蓝 1 / 日常开支=青绿 0
        assertTrue(sql.contains("WHEN '装修' THEN 2"))
        assertTrue(sql.contains("WHEN '旅行' THEN 1"))
        assertTrue(sql.contains("WHEN '日常开支' THEN 0"))
        assertTrue(sql.contains("ELSE 0 END"))
    }

    @Test
    fun `v3 to v4 adds the two orthogonal entry status columns`() {
        assertEquals(
            "ALTER TABLE entries ADD COLUMN reconciled INTEGER NOT NULL DEFAULT 0",
            MigrationSql.ADD_ENTRY_RECONCILED,
        )
        assertEquals(
            "ALTER TABLE entries ADD COLUMN reimburseState INTEGER NOT NULL DEFAULT 0",
            MigrationSql.ADD_ENTRY_REIMBURSE_STATE,
        )
    }
}
