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
}
