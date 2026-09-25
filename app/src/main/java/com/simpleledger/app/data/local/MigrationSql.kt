package com.simpleledger.app.data.local

/**
 * 数据库迁移语句常量。
 *
 * 把 SQL 文本单独抽成**无 Android 依赖**的常量，是为了能在本机用普通 JVM 单测
 * （`testDebugUnitTest`）把它钉死——真正的 Room 迁移测试（[androidx.room.testing.MigrationTestHelper]）
 * 需要真机/模拟器跑 instrumented test，本机环境无法执行。
 *
 * 这样至少能防止「有人手滑改了迁移 SQL 却没人发现」这类最危险的回归：
 * 单测会断言语句与预期完全一致，改了就红。
 */
object MigrationSql {

    /** v1 → v2：分区表新增月度预算字段，默认 0 表示未设预算 */
    const val ADD_SECTION_BUDGET_CENTS =
        "ALTER TABLE sections ADD COLUMN budgetCents INTEGER NOT NULL DEFAULT 0"

    /**
     * v2 → v3 ①：分类表新增「归属」列。
     *
     * 可空；`NULL` = 全局（所有分区可见），非空 = 该分区专属。**刻意不给它声明 FK**
     * （SQLite 无法为已有表补 FK；一旦声明就得重建 `categories`，而 `entries` 的
     * `ForeignKey.RESTRICT` 会让重建失败）。完整性改由 Repository 保证。
     *
     * 关键副作用：`ADD COLUMN` 后既有 12 行的 `sectionId` 天然为 `NULL` = 显式全局，
     * 即「12 个既有分类归位为全局」这一步**零 UPDATE** 完成（PRD D13 / 裁定 C-2）。
     */
    const val ADD_CATEGORY_SECTION_ID =
        "ALTER TABLE categories ADD COLUMN sectionId INTEGER"

    /** v2 → v3 ②：为归属列建索引。索引名必须与 Room 生成名一致，否则 schema 校验失败。 */
    const val CREATE_CATEGORY_SECTION_INDEX =
        "CREATE INDEX IF NOT EXISTS index_categories_sectionId ON categories (sectionId)"

    /**
     * v2 → v3 ③：补分类——为「装修」写入装修专属分类（支出 5：主材/人工/家具/家电/设计费；
     * 收入 2：报销/退款）。单条 `INSERT ... SELECT ... UNION ALL` 语句（不含分号）。
     *
     * 以 `name = '装修'` 定位该分区；若用户已改名导致定位失败，子查询返回 `NULL`，
     * 这批分类落为「全局」——不丢数据、不报错（D13：测试数据、无需保守迁移）。
     */
    val INSERT_SECTION_FIRST_CATEGORIES: String = buildString {
        // 以装修分区 id 子查询为 sectionId；支出 5 条 sortOrder 0..4，收入 2 条 sortOrder 0..1
        val zhuangxiuId = "(SELECT id FROM sections WHERE name = '装修' ORDER BY id LIMIT 1)"
        append("INSERT INTO categories (name, emoji, type, sectionId, sortOrder) ")
        append("SELECT '主材', '\uD83E\uDDF1', 0, $zhuangxiuId, 0")
        append(" UNION ALL SELECT '人工', '\uD83D\uDC77', 0, $zhuangxiuId, 1")
        append(" UNION ALL SELECT '家具', '\uD83D\uDECB\uFE0F', 0, $zhuangxiuId, 2")
        append(" UNION ALL SELECT '家电', '\uD83D\uDCFA', 0, $zhuangxiuId, 3")
        append(" UNION ALL SELECT '设计费', '\uD83D\uDCD0', 0, $zhuangxiuId, 4")
        append(" UNION ALL SELECT '报销', '\uD83D\uDCB5', 1, $zhuangxiuId, 0")
        append(" UNION ALL SELECT '退款', '\u21A9\uFE0F', 1, $zhuangxiuId, 1")
    }

    /*
     * 说明（裁定 C-2，勿误删此注）：设计文档 §4.3 曾给出一组「装修账目语义归位」的 `UPDATE` 语句，
     * 但主理人裁定 **不执行账目重定向**——保守、不改写历史账目语义、结果可预测。
     * 因此本对象**不提供**该 UPDATE 常量，`MIGRATION_2_3` 也不执行任何 `UPDATE entries`。
     */

    // ==================================================================================
    // v3 → v4：图标从 emoji 字符串改为 iconId，并引入分区胶带色与账目双维度状态
    //
    // ⚠️ 上面 MIGRATION_2_3 用到的 [INSERT_SECTION_FIRST_CATEGORIES] **必须保持冻结**：
    //    它执行在 v3 schema 上，那时 `categories.emoji` 还存在。改它会让升级路径崩。
    //
    // ⚠️ 为什么 categories / sections 要「重建」而不是 `DROP COLUMN`：
    //    SQLite 的 `ALTER TABLE ... DROP COLUMN` 需要 3.35+（Android 14 / API 34 起），
    //    本项目 minSdk 26，在旧设备上会直接抛错。所以走
    //    「建新表 → 拷数据（顺带翻译）→ DROP 旧表 → RENAME」这个跨版本安全的模式。
    //
    // ⚠️ 为什么 DROP TABLE 不会撞上 `entries` 的 `ForeignKey.RESTRICT`：
    //    Room 2.8.5 **从不设置 `PRAGMA foreign_keys`**（已反汇编 `RoomOpenHelper` 确认，
    //    其常量池中无任何外键 / PRAGMA 字符串），本项目也未调用
    //    `setForeignKeyConstraintsEnabled` → 取 SQLite 默认值 **OFF** → RESTRICT 不生效。
    //
    // ⚠️ 这是本迁移唯一的**隐式前提**，所以做了两件事把它显式化：
    //    ① `AppDatabase.MIGRATION_3_4` 开头有 `requireForeignKeyDisabled(db)` 运行时守卫；
    //    ② `tools/verify_migration_v4.py` 会在 foreign_keys=ON / OFF 两种模式下各跑一遍。
    //
    // ⚠️ 已实测：**外键开启时 SQL 层面无解**，不要试图「修好」它。RESTRICT 是立即检查的，
    //    以下两种常见绕法都试过且都失败（报 `FOREIGN KEY constraint failed`）：
    //      · 建新表 → 拷数据 → DROP 旧表 → RENAME（就是下面这套）
    //      · `PRAGMA legacy_alter_table=ON` + 先把旧表改名 → 建正式名新表 → 拷数据 → DROP 旧名
    //    `PRAGMA defer_foreign_keys` 同样无效（RESTRICT 不参与延迟）。
    // ==================================================================================

    /** v3 → v4 ①：建 categories 新表（`emoji` → `iconId`） */
    const val CREATE_CATEGORIES_V4 =
        "CREATE TABLE IF NOT EXISTS `categories_new` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`iconId` INTEGER NOT NULL, " +
            "`type` INTEGER NOT NULL, " +
            "`sectionId` INTEGER, " +
            "`sortOrder` INTEGER NOT NULL)"

    /**
     * v3 → v4 ②：拷数据并**翻译** emoji → iconId。
     *
     * 左侧先 `REPLACE(emoji, char(65039), '')` 剥掉变体选择符（U+FE0F）：
     * 有 6 个 emoji 在旧源码里带 VS16，而数据库中的实际字形可能不带，剥掉后两种写法都能命中。
     * 映射表本体见 [IconMapping]（50 条，与设计图标集一一对应）。
     */
    val COPY_CATEGORIES_V4: String =
        "INSERT INTO `categories_new` (`id`, `name`, `iconId`, `type`, `sectionId`, `sortOrder`) " +
            "SELECT `id`, `name`, " +
            "CASE REPLACE(`emoji`, char(65039), '') ${IconMapping.sqlCaseWhen()} " +
            "ELSE ${IconMapping.DEFAULT_CATEGORY_ICON_ID} END, " +
            "`type`, `sectionId`, `sortOrder` FROM `categories`"

    /** v3 → v4 ③：删旧表并改名。索引随旧表一起被删，需在后面重建。 */
    const val DROP_CATEGORIES_OLD = "DROP TABLE `categories`"

    const val RENAME_CATEGORIES_V4 =
        "ALTER TABLE `categories_new` RENAME TO `categories`"

    /** v3 → v4 ④：重建索引。索引名必须与 Room 生成名一致，否则 schema 校验失败。 */
    const val RECREATE_CATEGORY_TYPE_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_categories_type` ON `categories` (`type`)"

    const val RECREATE_CATEGORY_SECTION_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_categories_sectionId` ON `categories` (`sectionId`)"

    /** v3 → v4 ⑤：sections 新表（`emoji` → `iconId`，并新增 `colorIndex` 胶带色） */
    const val CREATE_SECTIONS_V4 =
        "CREATE TABLE IF NOT EXISTS `sections_new` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`iconId` INTEGER NOT NULL, " +
            "`note` TEXT NOT NULL, " +
            "`budgetCents` INTEGER NOT NULL, " +
            "`colorIndex` INTEGER NOT NULL, " +
            "`sortOrder` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL)"

    /** v3 → v4 ⑥：拷分区并翻译图标；`colorIndex` 先统一给 0（青绿） */
    val COPY_SECTIONS_V4: String =
        "INSERT INTO `sections_new` " +
            "(`id`, `name`, `iconId`, `note`, `budgetCents`, `colorIndex`, `sortOrder`, `createdAt`) " +
            "SELECT `id`, `name`, " +
            "CASE REPLACE(`emoji`, char(65039), '') ${IconMapping.sqlCaseWhen()} " +
            "ELSE ${IconMapping.DEFAULT_SECTION_ICON_ID} END, " +
            "`note`, `budgetCents`, 0, `sortOrder`, `createdAt` FROM `sections`"

    const val DROP_SECTIONS_OLD = "DROP TABLE `sections`"

    const val RENAME_SECTIONS_V4 =
        "ALTER TABLE `sections_new` RENAME TO `sections`"

    /**
     * v3 → v4 ⑦：把 3 个初始分区的胶带色对齐到 [SectionFirstSeed] 的定义
     * （日常开支=青绿 0 / 装修=赭黄 2 / 旅行=灰蓝 1）。
     *
     * 目的与 `MIGRATION_2_3` 补装修分类一致：**让「升级」与「全新安装」两条路径的初始态一致**。
     * 用户自建的分区不在 CASE 内 → 落 ELSE 0，不猜测、不覆盖。
     */
    const val ALIGN_SECTION_COLOR_INDEX =
        "UPDATE sections SET colorIndex = CASE name " +
            "WHEN '装修' THEN ${SectionFirstSeed.TapeColor.ZHE_HUANG} " +
            "WHEN '旅行' THEN ${SectionFirstSeed.TapeColor.HUI_LAN} " +
            "WHEN '日常开支' THEN ${SectionFirstSeed.TapeColor.QING_LV} " +
            "ELSE 0 END"

    /** v3 → v4 ⑧：账目新增「核对」维度（双维度之一，见 D4 裁定） */
    const val ADD_ENTRY_RECONCILED =
        "ALTER TABLE entries ADD COLUMN reconciled INTEGER NOT NULL DEFAULT 0"

    /** v3 → v4 ⑨：账目新增「报销」维度（与核对正交，可叠加） */
    const val ADD_ENTRY_REIMBURSE_STATE =
        "ALTER TABLE entries ADD COLUMN reimburseState INTEGER NOT NULL DEFAULT 0"

    /**
     * v4 全部语句，**按 [AppDatabase.MIGRATION_3_4] 的执行顺序排列**。
     *
     * 单独列出来是因为顺序本身就是契约：比如必须在 DROP 旧表**之前**先建新表、
     * 索引必须等 RENAME 之后才能建（建早了会被 DROP 一起带走）。
     * 单测按这份清单断言，顺序一旦被改动测试就会红。
     */
    val V4_STATEMENTS: List<Pair<String, String>> = listOf(
        "CREATE_CATEGORIES_V4" to CREATE_CATEGORIES_V4,
        "COPY_CATEGORIES_V4" to COPY_CATEGORIES_V4,
        "DROP_CATEGORIES_OLD" to DROP_CATEGORIES_OLD,
        "RENAME_CATEGORIES_V4" to RENAME_CATEGORIES_V4,
        "RECREATE_CATEGORY_TYPE_INDEX" to RECREATE_CATEGORY_TYPE_INDEX,
        "RECREATE_CATEGORY_SECTION_INDEX" to RECREATE_CATEGORY_SECTION_INDEX,
        "CREATE_SECTIONS_V4" to CREATE_SECTIONS_V4,
        "COPY_SECTIONS_V4" to COPY_SECTIONS_V4,
        "DROP_SECTIONS_OLD" to DROP_SECTIONS_OLD,
        "RENAME_SECTIONS_V4" to RENAME_SECTIONS_V4,
        "ALIGN_SECTION_COLOR_INDEX" to ALIGN_SECTION_COLOR_INDEX,
        "ADD_ENTRY_RECONCILED" to ADD_ENTRY_RECONCILED,
        "ADD_ENTRY_REIMBURSE_STATE" to ADD_ENTRY_REIMBURSE_STATE,
    )

    // ==================================================================================
    // v4 → v5：多端同步身份（syncId / versionSeq）+ 四张同步支撑表
    //
    // ⚠️ 全程**零 DROP、零重建表**：只 ALTER ADD / UPDATE / CREATE TABLE(新表) / CREATE INDEX。
    //    引用关系（entries → categories / sections 的本地 Long id）原值不动 ⇒ 迁移前后逐笔无损（R-11）。
    //
    // 回填口径（两阶段，保证唯一索引可建）：
    //    ① 全部存量行先落**随机** syncId（`lower(hex(randomblob(16)))` = 32hex）；
    //    ② 种子行再**覆盖**为确定性 syncId（[SeedIds]，与 `AppDatabase.seed()` 同一函数——
    //       新装 / 升级 / 多设备的默认分区分类天然是同一逻辑行，合并不重复，H7）。
    //    ③ 覆盖语句一律带 `rowid = (SELECT MIN(rowid) ...)` 守卫：用户克隆出的同名同行
    //       只有最早一行拿到确定性 id，绝不产生重复 syncId 撞唯一索引。
    //    未改名判定 = 同名 + 同 iconId（分类另要求同 type 与同归属）；用户改过名的种子行
    //    保持随机 id（极端情况合并出重复行，用户删一条即可）——已知边界，见设计 §8 U-5。
    // ==================================================================================

    /** v5 ①：成员表（家人共记，可隐藏不可删） */
    const val CREATE_TABLE_MEMBERS =
        "CREATE TABLE IF NOT EXISTS members (" +
            "syncId TEXT PRIMARY KEY NOT NULL, " +
            "name TEXT NOT NULL, " +
            "hidden INTEGER NOT NULL DEFAULT 0, " +
            "createdAt INTEGER NOT NULL, " +
            "updatedAt INTEGER NOT NULL, " +
            "versionSeq INTEGER NOT NULL DEFAULT 0)"

    /**
     * v5 ②：操作日志（CRDT 事实源，不可变）。
     * `baseSeq` 语义（U-3 裁定）：UPSERT = 编辑时观察到的行 versionSeq（新建 = null）；
     * DELETE = 被删行当时的 versionSeq（observed-remove 判据）。
     */
    const val CREATE_TABLE_SYNC_OPS =
        "CREATE TABLE IF NOT EXISTS sync_ops (" +
            "opId TEXT PRIMARY KEY NOT NULL, " +
            "rowKind TEXT NOT NULL, " +
            "rowSyncId TEXT NOT NULL, " +
            "opType TEXT NOT NULL, " +
            "actorId TEXT NOT NULL, " +
            "memberId TEXT, " +
            "seq INTEGER NOT NULL, " +
            "baseSeq INTEGER, " +
            "payload TEXT NOT NULL, " +
            "origin TEXT NOT NULL, " +
            "applied INTEGER NOT NULL DEFAULT 1, " +
            "uploaded INTEGER NOT NULL DEFAULT 0, " +
            "chunkName TEXT, " +
            "createdAt INTEGER NOT NULL)"

    const val CREATE_INDEX_SYNC_OPS_OUTBOX =
        "CREATE INDEX IF NOT EXISTS index_sync_ops_outbox ON sync_ops (uploaded, createdAt)"

    const val CREATE_INDEX_SYNC_OPS_ROW =
        "CREATE INDEX IF NOT EXISTS index_sync_ops_row ON sync_ops (rowKind, rowSyncId)"

    /**
     * v5 ③：冲突回收站（持久层留底，R-08/R-09）。
     * `kind`（U-3 裁定）：'DELETE' = 删除留底；'OVERWRITE' = 并发编辑 LWW 落败版留底。
     */
    const val CREATE_TABLE_SYNC_TRASH =
        "CREATE TABLE IF NOT EXISTS sync_trash (" +
            "deleteOpId TEXT PRIMARY KEY NOT NULL, " +
            "rowKind TEXT NOT NULL, " +
            "rowSyncId TEXT NOT NULL, " +
            "kind TEXT NOT NULL DEFAULT 'DELETE', " +
            "snapshot TEXT NOT NULL, " +
            "deletedAt INTEGER NOT NULL, " +
            "deletedByMemberId TEXT, " +
            "conflict INTEGER NOT NULL DEFAULT 0, " +
            "conflictActorId TEXT, " +
            "resolved INTEGER NOT NULL DEFAULT 0)"

    /** v5 ④：云端文件台账（增量发现游标；remoteName 即假名，无可读信息） */
    const val CREATE_TABLE_SYNC_REMOTE_FILES =
        "CREATE TABLE IF NOT EXISTS sync_remote_files (" +
            "remoteName TEXT PRIMARY KEY NOT NULL, " +
            "kind TEXT NOT NULL, " +
            "etag TEXT, " +
            "size INTEGER NOT NULL DEFAULT 0, " +
            "downloadedAt INTEGER NOT NULL DEFAULT 0, " +
            "uploadedAt INTEGER NOT NULL DEFAULT 0)"

    // —— v5 ⑤：四表增列（syncId / versionSeq / updatedAt / memberId / contentHash）——

    const val ADD_SECTION_SYNC_ID =
        "ALTER TABLE sections ADD COLUMN syncId TEXT NOT NULL DEFAULT ''"

    const val ADD_SECTION_VERSION_SEQ =
        "ALTER TABLE sections ADD COLUMN versionSeq INTEGER NOT NULL DEFAULT 0"

    const val ADD_SECTION_UPDATED_AT =
        "ALTER TABLE sections ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"

    const val ADD_CATEGORY_SYNC_ID =
        "ALTER TABLE categories ADD COLUMN syncId TEXT NOT NULL DEFAULT ''"

    const val ADD_CATEGORY_VERSION_SEQ =
        "ALTER TABLE categories ADD COLUMN versionSeq INTEGER NOT NULL DEFAULT 0"

    const val ADD_CATEGORY_UPDATED_AT =
        "ALTER TABLE categories ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"

    const val ADD_ENTRY_SYNC_ID =
        "ALTER TABLE entries ADD COLUMN syncId TEXT NOT NULL DEFAULT ''"

    const val ADD_ENTRY_VERSION_SEQ =
        "ALTER TABLE entries ADD COLUMN versionSeq INTEGER NOT NULL DEFAULT 0"

    /** 记账成员同步 ID；可空（存量 = 未知成员，memberId 保持 NULL） */
    const val ADD_ENTRY_MEMBER_ID =
        "ALTER TABLE entries ADD COLUMN memberId TEXT"

    const val ADD_IMAGE_SYNC_ID =
        "ALTER TABLE entry_images ADD COLUMN syncId TEXT NOT NULL DEFAULT ''"

    const val ADD_IMAGE_VERSION_SEQ =
        "ALTER TABLE entry_images ADD COLUMN versionSeq INTEGER NOT NULL DEFAULT 0"

    const val ADD_IMAGE_UPDATED_AT =
        "ALTER TABLE entry_images ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"

    /** 明文 JPEG SHA-256（64hex）；存量为空串，由 LedgerApp 启动后台补算（U-6） */
    const val ADD_IMAGE_CONTENT_HASH =
        "ALTER TABLE entry_images ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''"

    // —— v5 ⑥：存量回填（先全体随机 syncId，再由 ⑦ 覆盖种子行）——

    /** 分区：随机 syncId + updatedAt 回填为 createdAt（分区没有独立更新时间，取建档时刻） */
    const val BACKFILL_SECTION_SYNC_ID =
        "UPDATE sections SET syncId = lower(hex(randomblob(16))), updatedAt = createdAt"

    const val BACKFILL_CATEGORY_SYNC_ID =
        "UPDATE categories SET syncId = lower(hex(randomblob(16))), updatedAt = 0"

    /** 账目：只补 syncId；memberId 保持 NULL（未知成员），既有 updatedAt 原值不动 */
    const val BACKFILL_ENTRY_SYNC_ID =
        "UPDATE entries SET syncId = lower(hex(randomblob(16)))"

    const val BACKFILL_IMAGE_SYNC_ID =
        "UPDATE entry_images SET syncId = lower(hex(randomblob(16))), updatedAt = 0"

    // —— v5 ⑧：索引（名字必须与 Room 生成名一致，否则 schema 校验失败；见 MigrationSqlTest）——

    const val CREATE_SECTION_SYNC_ID_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS index_sections_syncId ON sections (syncId)"

    const val CREATE_CATEGORY_SYNC_ID_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS index_categories_syncId ON categories (syncId)"

    const val CREATE_ENTRY_SYNC_ID_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS index_entries_syncId ON entries (syncId)"

    const val CREATE_IMAGE_SYNC_ID_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS index_entry_images_syncId ON entry_images (syncId)"

    const val CREATE_ENTRY_MEMBER_ID_INDEX =
        "CREATE INDEX IF NOT EXISTS index_entries_memberId ON entries (memberId)"

    const val CREATE_IMAGE_CONTENT_HASH_INDEX =
        "CREATE INDEX IF NOT EXISTS index_entry_images_contentHash ON entry_images (contentHash)"

    /**
     * v5 全部语句，**按 [AppDatabase.MIGRATION_4_5] 的执行顺序**：
     * 新表（含其索引）→ ADD COLUMN → 随机回填 → 种子行确定性覆盖（[SeedIds]，
     * 与 `AppDatabase.seed()` 共用同一函数）→ 业务表索引。
     *
     * 顺序即契约：覆盖必须在随机回填**之后**（两阶段保唯一索引可建），
     * 唯一索引必须在回填**之后**建（建早了随机撞名会炸）。
     * 单测按这份清单逐名钉死，`tools/verify_migration_v5.py` 原样解析执行。
     */
    val V5_STATEMENTS: List<Pair<String, String>> = listOf(
        "CREATE_TABLE_MEMBERS" to CREATE_TABLE_MEMBERS,
        "CREATE_TABLE_SYNC_OPS" to CREATE_TABLE_SYNC_OPS,
        "CREATE_INDEX_SYNC_OPS_OUTBOX" to CREATE_INDEX_SYNC_OPS_OUTBOX,
        "CREATE_INDEX_SYNC_OPS_ROW" to CREATE_INDEX_SYNC_OPS_ROW,
        "CREATE_TABLE_SYNC_TRASH" to CREATE_TABLE_SYNC_TRASH,
        "CREATE_TABLE_SYNC_REMOTE_FILES" to CREATE_TABLE_SYNC_REMOTE_FILES,
        "ADD_SECTION_SYNC_ID" to ADD_SECTION_SYNC_ID,
        "ADD_SECTION_VERSION_SEQ" to ADD_SECTION_VERSION_SEQ,
        "ADD_SECTION_UPDATED_AT" to ADD_SECTION_UPDATED_AT,
        "ADD_CATEGORY_SYNC_ID" to ADD_CATEGORY_SYNC_ID,
        "ADD_CATEGORY_VERSION_SEQ" to ADD_CATEGORY_VERSION_SEQ,
        "ADD_CATEGORY_UPDATED_AT" to ADD_CATEGORY_UPDATED_AT,
        "ADD_ENTRY_SYNC_ID" to ADD_ENTRY_SYNC_ID,
        "ADD_ENTRY_VERSION_SEQ" to ADD_ENTRY_VERSION_SEQ,
        "ADD_ENTRY_MEMBER_ID" to ADD_ENTRY_MEMBER_ID,
        "ADD_IMAGE_SYNC_ID" to ADD_IMAGE_SYNC_ID,
        "ADD_IMAGE_VERSION_SEQ" to ADD_IMAGE_VERSION_SEQ,
        "ADD_IMAGE_UPDATED_AT" to ADD_IMAGE_UPDATED_AT,
        "ADD_IMAGE_CONTENT_HASH" to ADD_IMAGE_CONTENT_HASH,
        "BACKFILL_SECTION_SYNC_ID" to BACKFILL_SECTION_SYNC_ID,
        "BACKFILL_CATEGORY_SYNC_ID" to BACKFILL_CATEGORY_SYNC_ID,
        "BACKFILL_ENTRY_SYNC_ID" to BACKFILL_ENTRY_SYNC_ID,
        "BACKFILL_IMAGE_SYNC_ID" to BACKFILL_IMAGE_SYNC_ID,
        // —— ⑦ 种子行确定性 syncId 覆盖（22 条：3 分区 + 12 全局分类 + 7 装修专属分类）——
        //    每条一行（便于 tools/verify_migration_v5.py 原样解析）；
        //    未改名判定 = 同名 + 同 iconId（分类再加同 type + 同归属）；
        //    MIN(rowid) 守卫：同名克隆行只有最早一行拿确定性 id。
        "SEED_SECTION_SYNC_ID_日常开支" to "UPDATE sections SET syncId = '" + SeedIds.section("日常开支") + "' WHERE rowid = (SELECT MIN(rowid) FROM sections WHERE name = '日常开支' AND iconId = 1)",
        "SEED_SECTION_SYNC_ID_装修" to "UPDATE sections SET syncId = '" + SeedIds.section("装修") + "' WHERE rowid = (SELECT MIN(rowid) FROM sections WHERE name = '装修' AND iconId = 17)",
        "SEED_SECTION_SYNC_ID_旅行" to "UPDATE sections SET syncId = '" + SeedIds.section("旅行") + "' WHERE rowid = (SELECT MIN(rowid) FROM sections WHERE name = '旅行' AND iconId = 13)",
        "SEED_CATEGORY_SYNC_ID_餐饮" to "UPDATE categories SET syncId = '" + SeedIds.category(0, null, "餐饮") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '餐饮' AND type = 0 AND iconId = 2 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_交通" to "UPDATE categories SET syncId = '" + SeedIds.category(0, null, "交通") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '交通' AND type = 0 AND iconId = 8 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_购物" to "UPDATE categories SET syncId = '" + SeedIds.category(0, null, "购物") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '购物' AND type = 0 AND iconId = 15 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_居住" to "UPDATE categories SET syncId = '" + SeedIds.category(0, null, "居住") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '居住' AND type = 0 AND iconId = 16 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_医疗" to "UPDATE categories SET syncId = '" + SeedIds.category(0, null, "医疗") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '医疗' AND type = 0 AND iconId = 20 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_娱乐" to "UPDATE categories SET syncId = '" + SeedIds.category(0, null, "娱乐") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '娱乐' AND type = 0 AND iconId = 22 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_学习" to "UPDATE categories SET syncId = '" + SeedIds.category(0, null, "学习") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '学习' AND type = 0 AND iconId = 26 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_其他支出" to "UPDATE categories SET syncId = '" + SeedIds.category(0, null, "其他支出") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '其他支出' AND type = 0 AND iconId = 42 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_工资" to "UPDATE categories SET syncId = '" + SeedIds.category(1, null, "工资") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '工资' AND type = 1 AND iconId = 36 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_理财" to "UPDATE categories SET syncId = '" + SeedIds.category(1, null, "理财") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '理财' AND type = 1 AND iconId = 37 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_红包" to "UPDATE categories SET syncId = '" + SeedIds.category(1, null, "红包") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '红包' AND type = 1 AND iconId = 32 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_其他收入" to "UPDATE categories SET syncId = '" + SeedIds.category(1, null, "其他收入") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '其他收入' AND type = 1 AND iconId = 41 AND sectionId IS NULL)",
        "SEED_CATEGORY_SYNC_ID_主材" to "UPDATE categories SET syncId = '" + SeedIds.category(0, "装修", "主材") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '主材' AND type = 0 AND iconId = 44 AND sectionId IN (SELECT id FROM sections WHERE syncId = '" + SeedIds.section("装修") + "'))",
        "SEED_CATEGORY_SYNC_ID_人工" to "UPDATE categories SET syncId = '" + SeedIds.category(0, "装修", "人工") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '人工' AND type = 0 AND iconId = 45 AND sectionId IN (SELECT id FROM sections WHERE syncId = '" + SeedIds.section("装修") + "'))",
        "SEED_CATEGORY_SYNC_ID_家具" to "UPDATE categories SET syncId = '" + SeedIds.category(0, "装修", "家具") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '家具' AND type = 0 AND iconId = 46 AND sectionId IN (SELECT id FROM sections WHERE syncId = '" + SeedIds.section("装修") + "'))",
        "SEED_CATEGORY_SYNC_ID_家电" to "UPDATE categories SET syncId = '" + SeedIds.category(0, "装修", "家电") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '家电' AND type = 0 AND iconId = 47 AND sectionId IN (SELECT id FROM sections WHERE syncId = '" + SeedIds.section("装修") + "'))",
        "SEED_CATEGORY_SYNC_ID_设计费" to "UPDATE categories SET syncId = '" + SeedIds.category(0, "装修", "设计费") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '设计费' AND type = 0 AND iconId = 48 AND sectionId IN (SELECT id FROM sections WHERE syncId = '" + SeedIds.section("装修") + "'))",
        "SEED_CATEGORY_SYNC_ID_报销" to "UPDATE categories SET syncId = '" + SeedIds.category(1, "装修", "报销") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '报销' AND type = 1 AND iconId = 49 AND sectionId IN (SELECT id FROM sections WHERE syncId = '" + SeedIds.section("装修") + "'))",
        "SEED_CATEGORY_SYNC_ID_退款" to "UPDATE categories SET syncId = '" + SeedIds.category(1, "装修", "退款") + "' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '退款' AND type = 1 AND iconId = 50 AND sectionId IN (SELECT id FROM sections WHERE syncId = '" + SeedIds.section("装修") + "'))",
        "CREATE_SECTION_SYNC_ID_INDEX" to CREATE_SECTION_SYNC_ID_INDEX,
        "CREATE_CATEGORY_SYNC_ID_INDEX" to CREATE_CATEGORY_SYNC_ID_INDEX,
        "CREATE_ENTRY_SYNC_ID_INDEX" to CREATE_ENTRY_SYNC_ID_INDEX,
        "CREATE_IMAGE_SYNC_ID_INDEX" to CREATE_IMAGE_SYNC_ID_INDEX,
        "CREATE_ENTRY_MEMBER_ID_INDEX" to CREATE_ENTRY_MEMBER_ID_INDEX,
        "CREATE_IMAGE_CONTENT_HASH_INDEX" to CREATE_IMAGE_CONTENT_HASH_INDEX,
    )
}
