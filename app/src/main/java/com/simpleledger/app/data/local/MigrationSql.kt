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
        append("SELECT '主材', '🧱', 0, $zhuangxiuId, 0")
        append(" UNION ALL SELECT '人工', '👷', 0, $zhuangxiuId, 1")
        append(" UNION ALL SELECT '家具', '🛋️', 0, $zhuangxiuId, 2")
        append(" UNION ALL SELECT '家电', '📺', 0, $zhuangxiuId, 3")
        append(" UNION ALL SELECT '设计费', '📐', 0, $zhuangxiuId, 4")
        append(" UNION ALL SELECT '报销', '💵', 1, $zhuangxiuId, 0")
        append(" UNION ALL SELECT '退款', '↩️', 1, $zhuangxiuId, 1")
    }

    /*
     * 说明（裁定 C-2，勿误删此注）：设计文档 §4.3 曾给出一组「装修账目语义归位」的 `UPDATE` 语句，
     * 但主理人裁定 **不执行账目重定向**——保守、不改写历史账目语义、结果可预测。
     * 因此本对象**不提供**该 UPDATE 常量，`MIGRATION_2_3` 也不执行任何 `UPDATE entries`。
     */
}
