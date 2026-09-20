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
}