package com.simpleledger.app

import com.simpleledger.app.data.local.MigrationSql
import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.data.local.SeedIds
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

    /* ---------------------------------------------------------------- v4 → v5 */

    /**
     * v5 语句顺序契约（51 条）：新表 → ADD COLUMN → 随机回填 → 种子覆盖 → 索引。
     * 顺序敏感点：覆盖必须晚于回填（两阶段保唯一索引可建），索引必须最后建。
     */
    private fun expectedV5Names(): List<String> = listOf(
        "CREATE_TABLE_MEMBERS",
        "CREATE_TABLE_SYNC_OPS",
        "CREATE_INDEX_SYNC_OPS_OUTBOX",
        "CREATE_INDEX_SYNC_OPS_ROW",
        "CREATE_TABLE_SYNC_TRASH",
        "CREATE_TABLE_SYNC_REMOTE_FILES",
        "ADD_SECTION_SYNC_ID", "ADD_SECTION_VERSION_SEQ", "ADD_SECTION_UPDATED_AT",
        "ADD_CATEGORY_SYNC_ID", "ADD_CATEGORY_VERSION_SEQ", "ADD_CATEGORY_UPDATED_AT",
        "ADD_ENTRY_SYNC_ID", "ADD_ENTRY_VERSION_SEQ", "ADD_ENTRY_MEMBER_ID",
        "ADD_IMAGE_SYNC_ID", "ADD_IMAGE_VERSION_SEQ", "ADD_IMAGE_UPDATED_AT", "ADD_IMAGE_CONTENT_HASH",
        "BACKFILL_SECTION_SYNC_ID", "BACKFILL_CATEGORY_SYNC_ID",
        "BACKFILL_ENTRY_SYNC_ID", "BACKFILL_IMAGE_SYNC_ID",
    ) + SectionFirstSeed.sections.map { "SEED_SECTION_SYNC_ID_${it.name}" } +
        SectionFirstSeed.globalCategories.map { "SEED_CATEGORY_SYNC_ID_${it.name}" } +
        SectionFirstSeed.sectionCategories.map { "SEED_CATEGORY_SYNC_ID_${it.name}" } +
        listOf(
            "CREATE_SECTION_SYNC_ID_INDEX", "CREATE_CATEGORY_SYNC_ID_INDEX",
            "CREATE_ENTRY_SYNC_ID_INDEX", "CREATE_IMAGE_SYNC_ID_INDEX",
            "CREATE_ENTRY_MEMBER_ID_INDEX", "CREATE_IMAGE_CONTENT_HASH_INDEX",
        )

    @Test
    fun `v5 statement list is complete and order is pinned`() {
        // 3 分区 + 19 分类 = 22 条种子覆盖；合计 6 + 13 + 4 + 22 + 6 = 51 条
        assertEquals(51, MigrationSql.V5_STATEMENTS.size)
        assertEquals(expectedV5Names(), MigrationSql.V5_STATEMENTS.map { it.first })
    }

    @Test
    fun `v5 statements are all single-statement`() {
        assertEquals(
            "v5 语句条数应与 MIGRATION_4_5 一致",
            51,
            MigrationSql.V5_STATEMENTS.size,
        )
        val names = MigrationSql.V5_STATEMENTS.map { it.first }
        assertEquals("语句名不得重复", names.size, names.toSet().size)
        MigrationSql.V5_STATEMENTS.forEach { (name, sql) ->
            assertTrue("$name 不应为空", sql.isNotBlank())
            assertEquals("$name 混入了分号", 0, sql.count { it == ';' })
        }
    }

    /** R-11 铁律：零 DROP、零重建表——四表只增列，本地 Long 引用原值不动 */
    @Test
    fun `v5 never drops or rebuilds tables`() {
        MigrationSql.V5_STATEMENTS.forEach { (name, sql) ->
            assertTrue("$name 不得含 DROP", !sql.contains("DROP"))
            assertTrue("$name 不得含 RENAME", !sql.contains("RENAME"))
            assertTrue("$name 不得走临时表重建", !sql.contains("_new"))
        }
    }

    @Test
    fun `v5 adds sync identity columns exactly`() {
        // 与 §3.1 DDL 逐字一致：syncId 32hex / versionSeq Lamport / updatedAt
        assertEquals(
            "ALTER TABLE sections ADD COLUMN syncId TEXT NOT NULL DEFAULT ''",
            MigrationSql.ADD_SECTION_SYNC_ID,
        )
        assertEquals(
            "ALTER TABLE sections ADD COLUMN versionSeq INTEGER NOT NULL DEFAULT 0",
            MigrationSql.ADD_SECTION_VERSION_SEQ,
        )
        assertEquals(
            "ALTER TABLE sections ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
            MigrationSql.ADD_SECTION_UPDATED_AT,
        )
        assertEquals(
            "ALTER TABLE categories ADD COLUMN syncId TEXT NOT NULL DEFAULT ''",
            MigrationSql.ADD_CATEGORY_SYNC_ID,
        )
        assertEquals(
            "ALTER TABLE categories ADD COLUMN versionSeq INTEGER NOT NULL DEFAULT 0",
            MigrationSql.ADD_CATEGORY_VERSION_SEQ,
        )
        assertEquals(
            "ALTER TABLE categories ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
            MigrationSql.ADD_CATEGORY_UPDATED_AT,
        )
        assertEquals(
            "ALTER TABLE entries ADD COLUMN syncId TEXT NOT NULL DEFAULT ''",
            MigrationSql.ADD_ENTRY_SYNC_ID,
        )
        assertEquals(
            "ALTER TABLE entries ADD COLUMN versionSeq INTEGER NOT NULL DEFAULT 0",
            MigrationSql.ADD_ENTRY_VERSION_SEQ,
        )
        // memberId 必须**可空**（存量 = 未知成员）；误写 NOT NULL 会把老账目塞进假成员
        assertEquals("ALTER TABLE entries ADD COLUMN memberId TEXT", MigrationSql.ADD_ENTRY_MEMBER_ID)
        assertTrue(!MigrationSql.ADD_ENTRY_MEMBER_ID.contains("NOT NULL"))
        assertEquals(
            "ALTER TABLE entry_images ADD COLUMN syncId TEXT NOT NULL DEFAULT ''",
            MigrationSql.ADD_IMAGE_SYNC_ID,
        )
        assertEquals(
            "ALTER TABLE entry_images ADD COLUMN versionSeq INTEGER NOT NULL DEFAULT 0",
            MigrationSql.ADD_IMAGE_VERSION_SEQ,
        )
        assertEquals(
            "ALTER TABLE entry_images ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
            MigrationSql.ADD_IMAGE_UPDATED_AT,
        )
        assertEquals(
            "ALTER TABLE entry_images ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''",
            MigrationSql.ADD_IMAGE_CONTENT_HASH,
        )
    }

    @Test
    fun `v5 backfills random sync ids for all legacy rows`() {
        // 第一阶段：全体随机（lower(hex(randomblob(16))) = 32hex）；第二阶段才覆盖种子行
        assertEquals(
            "UPDATE sections SET syncId = lower(hex(randomblob(16))), updatedAt = createdAt",
            MigrationSql.BACKFILL_SECTION_SYNC_ID,
        )
        assertEquals(
            "UPDATE categories SET syncId = lower(hex(randomblob(16))), updatedAt = 0",
            MigrationSql.BACKFILL_CATEGORY_SYNC_ID,
        )
        assertEquals(
            "UPDATE entries SET syncId = lower(hex(randomblob(16)))",
            MigrationSql.BACKFILL_ENTRY_SYNC_ID,
        )
        assertEquals(
            "UPDATE entry_images SET syncId = lower(hex(randomblob(16))), updatedAt = 0",
            MigrationSql.BACKFILL_IMAGE_SYNC_ID,
        )
    }

    @Test
    fun `v5 seed sync ids are deterministic golden vectors`() {
        // RFC 向量式金标：钉死 UUID.nameUUIDFromBytes 派生公式（Python 校验脚本镜像同一组金标）。
        // 一旦有人改公式，多设备默认数据会分裂成两套逻辑行——必须红。
        val goldens: Map<String, String> = mapOf(
            SeedIds.section("日常开支") to "sd05948a76a5233ccebe001dad42a63e",
            SeedIds.section("装修") to "sd1056d1fadccb3c758c73dfdd5f72c0",
            SeedIds.section("旅行") to "sdbe6ff5dc48ef304aaf3d3bc0e123b0",
            SeedIds.category(0, null, "餐饮") to "sdef2a17de26533d868d4b39f02722ad",
            SeedIds.category(0, null, "交通") to "sd71f609c0b79334f995719dbb87fa34",
            SeedIds.category(0, null, "购物") to "sd07e72de901ef3592a8637e74d7456f",
            SeedIds.category(0, null, "居住") to "sd09ba2516708d3ec183d459318f6674",
            SeedIds.category(0, null, "医疗") to "sd15384270ffc63b8cba5f74050c9738",
            SeedIds.category(0, null, "娱乐") to "sd093b209f1f8d33eebbcf2e76e9a6a1",
            SeedIds.category(0, null, "学习") to "sdfdb7acf1ad0138b69dd79f7de8f35a",
            SeedIds.category(0, null, "其他支出") to "sd452344bf97583dbb86b06cb474f20d",
            SeedIds.category(1, null, "工资") to "sd8803f75cfb4a3a519ae3ef83c49a28",
            SeedIds.category(1, null, "理财") to "sd42ec544700943cffb8fc870e5935fb",
            SeedIds.category(1, null, "红包") to "sd97bda608be5b35d39c79a6c24273aa",
            SeedIds.category(1, null, "其他收入") to "sd3923bb02822733a7894cd5a09e1736",
            SeedIds.category(0, "装修", "主材") to "sd262aeca685e239958765bf34ecbd2d",
            SeedIds.category(0, "装修", "人工") to "sd97c75c38ab003937adbdc493c2d00d",
            SeedIds.category(0, "装修", "家具") to "sd9a6ab9f33a303e9bb37f4423cc3717",
            SeedIds.category(0, "装修", "家电") to "sd7b1a5f8b41f135e39ddeb5ad761bd5",
            SeedIds.category(0, "装修", "设计费") to "sd60a22752e2a232119fff97056701d4",
            SeedIds.category(1, "装修", "报销") to "sd1b4aa01a333139e498a6726e08c2a3",
            SeedIds.category(1, "装修", "退款") to "sd0fcfc90aee313b4eae6004278cf089",
        )
        goldens.forEach { (actual, expected) -> assertEquals(expected, actual) }
        // 形态：'sd' 前缀 + 30 hex（可与随机 32hex 区分），全集无重复
        assertEquals(22, goldens.size)
        goldens.keys.forEach { id ->
            assertTrue("种子 syncId 形态不对：$id", id.matches(Regex("sd[0-9a-f]{30}")))
        }
    }

    @Test
    fun `v5 seed fixes cover every seed row exactly once with uniqueness guard`() {
        val fixes = MigrationSql.V5_STATEMENTS.filter { it.first.startsWith("SEED_") }
        assertEquals(SectionFirstSeed.sections.size + SectionFirstSeed.categories.size, fixes.size)

        // 分区：同名 + 同 iconId 命中；MIN(rowid) 保证同名克隆行只有一行拿确定性 id
        SectionFirstSeed.sections.forEach { section ->
            val expected = "UPDATE sections SET syncId = '" + SeedIds.section(section.name) +
                "' WHERE rowid = (SELECT MIN(rowid) FROM sections WHERE name = '${section.name}' " +
                "AND iconId = ${section.iconId})"
            assertEquals(expected, fixes.first { it.first == "SEED_SECTION_SYNC_ID_${section.name}" }.second)
        }

        // 分类：同名 + 同 type + 同 iconId + 同归属；专属分类还要求分区行已拿到确定性 id
        SectionFirstSeed.categories.forEach { category ->
            val id = SeedIds.category(category.type, category.sectionName, category.name)
            val scope = if (category.sectionName == null) {
                "sectionId IS NULL"
            } else {
                "sectionId IN (SELECT id FROM sections WHERE syncId = '" +
                    SeedIds.section(category.sectionName) + "')"
            }
            val expected = "UPDATE categories SET syncId = '" + id +
                "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '${category.name}' " +
                "AND type = ${category.type} AND iconId = ${category.iconId} AND $scope)"
            assertEquals(expected, fixes.first { it.first == "SEED_CATEGORY_SYNC_ID_${category.name}" }.second)
        }
    }

    @Test
    fun `v5 creates the four sync tables with u3 semantics`() {
        val members = MigrationSql.CREATE_TABLE_MEMBERS
        assertTrue(members.startsWith("CREATE TABLE IF NOT EXISTS members"))
        listOf("syncId TEXT PRIMARY KEY NOT NULL", "name TEXT NOT NULL", "hidden INTEGER NOT NULL")
            .forEach { assertTrue("members 缺少：$it", members.contains(it)) }

        val ops = MigrationSql.CREATE_TABLE_SYNC_OPS
        assertTrue(ops.startsWith("CREATE TABLE IF NOT EXISTS sync_ops"))
        // U-3：UPSERT 也携带 baseSeq（编辑观察版本；新建 = null）→ 列必须可空
        assertTrue(ops.contains("baseSeq INTEGER,"))
        listOf("opId TEXT PRIMARY KEY NOT NULL", "rowKind TEXT NOT NULL", "rowSyncId TEXT NOT NULL",
            "opType TEXT NOT NULL", "actorId TEXT NOT NULL", "seq INTEGER NOT NULL",
            "payload TEXT NOT NULL", "origin TEXT NOT NULL")
            .forEach { assertTrue("sync_ops 缺少：$it", ops.contains(it)) }

        val trash = MigrationSql.CREATE_TABLE_SYNC_TRASH
        assertTrue(trash.startsWith("CREATE TABLE IF NOT EXISTS sync_trash"))
        // U-3：kind 来源字段（DELETE = 删除留底 / OVERWRITE = 并发编辑落败版留底）
        assertTrue(trash.contains("kind TEXT NOT NULL DEFAULT 'DELETE'"))
        assertTrue(trash.contains("deleteOpId TEXT PRIMARY KEY NOT NULL"))

        val files = MigrationSql.CREATE_TABLE_SYNC_REMOTE_FILES
        assertTrue(files.startsWith("CREATE TABLE IF NOT EXISTS sync_remote_files"))
        assertTrue(files.contains("remoteName TEXT PRIMARY KEY NOT NULL"))
    }

    @Test
    fun `v5 index names follow room naming or explicit contract`() {
        // 业务表四个 syncId 索引：对齐 Room 生成名（index_<表>_<列>），否则 schema 校验崩
        assertEquals(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_sections_syncId ON sections (syncId)",
            MigrationSql.CREATE_SECTION_SYNC_ID_INDEX,
        )
        assertEquals(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_categories_syncId ON categories (syncId)",
            MigrationSql.CREATE_CATEGORY_SYNC_ID_INDEX,
        )
        assertEquals(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_entries_syncId ON entries (syncId)",
            MigrationSql.CREATE_ENTRY_SYNC_ID_INDEX,
        )
        assertEquals(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_entry_images_syncId ON entry_images (syncId)",
            MigrationSql.CREATE_IMAGE_SYNC_ID_INDEX,
        )
        assertEquals(
            "CREATE INDEX IF NOT EXISTS index_entries_memberId ON entries (memberId)",
            MigrationSql.CREATE_ENTRY_MEMBER_ID_INDEX,
        )
        assertEquals(
            "CREATE INDEX IF NOT EXISTS index_entry_images_contentHash ON entry_images (contentHash)",
            MigrationSql.CREATE_IMAGE_CONTENT_HASH_INDEX,
        )
        // sync_ops 两个索引：显式契约名（SyncDao / SyncOpEntity 声明同名）
        assertEquals(
            "CREATE INDEX IF NOT EXISTS index_sync_ops_outbox ON sync_ops (uploaded, createdAt)",
            MigrationSql.CREATE_INDEX_SYNC_OPS_OUTBOX,
        )
        assertEquals(
            "CREATE INDEX IF NOT EXISTS index_sync_ops_row ON sync_ops (rowKind, rowSyncId)",
            MigrationSql.CREATE_INDEX_SYNC_OPS_ROW,
        )
    }
}
