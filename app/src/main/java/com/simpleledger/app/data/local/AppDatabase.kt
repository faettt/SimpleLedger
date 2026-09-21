package com.simpleledger.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.simpleledger.app.data.local.dao.CategoryDao
import com.simpleledger.app.data.local.dao.EntryDao
import com.simpleledger.app.data.local.dao.SectionDao
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryEntity
import com.simpleledger.app.data.local.entity.EntryImageEntity
import com.simpleledger.app.data.local.entity.SectionEntity

@Database(
    entities = [
        SectionEntity::class,
        CategoryEntity::class,
        EntryEntity::class,
        EntryImageEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sectionDao(): SectionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun entryDao(): EntryDao

    companion object {
        private const val DB_NAME = "simple_ledger.db"

        /** v1 → v2：分区增加月度预算字段（默认 0 = 未设预算） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MigrationSql.ADD_SECTION_BUDGET_CENTS)
            }
        }

        /**
         * v2 → v3：分类新增「归属」维度，并补入装修专属分类。
         *
         * **只做增量 DDL**（`ALTER TABLE ADD COLUMN` + `CREATE INDEX`）+ 一条补分类 INSERT：
         * 不 DROP / 不 CREATE TABLE（D-1），`entries` 表零改动。
         *
         * 按主理人裁定 **C-2**：**不执行**任何「装修账目分类改写」的 `UPDATE`——
         * 12 个既有分类因 `ADD COLUMN` 后天然为 NULL 而全部归位为全局（零 UPDATE），
         * 装修专属分类作为**新增**补入。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MigrationSql.ADD_CATEGORY_SECTION_ID)
                db.execSQL(MigrationSql.CREATE_CATEGORY_SECTION_INDEX)
                db.execSQL(MigrationSql.INSERT_SECTION_FIRST_CATEGORIES)
            }
        }

        /**
         * v3 → v4：图标从 emoji 字符串改为 `iconId`，并引入分区胶带色与账目双维度状态。
         *
         * 五件事：
         * 1. `categories` **重建**：`emoji` → `iconId`（按 [IconMapping] 的 50 条映射翻译）
         * 2. `sections` **重建**：`emoji` → `iconId`，并新增 `colorIndex`
         * 3. 3 个初始分区的 `colorIndex` 对齐 [SectionFirstSeed]
         * 4. `entries` 新增 `reconciled`（核对维度）
         * 5. `entries` 新增 `reimburseState`（报销维度，与核对正交）
         *
         * 为什么是「重建」而不是 `DROP COLUMN`、以及为什么 `DROP TABLE` 不会撞上
         * `entries` 的 `ForeignKey.RESTRICT`——完整论证见 [MigrationSql] 的 v3→v4 段注释。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                requireForeignKeyDisabled(db)

                // —— categories：重建以去掉 emoji 列并换成 iconId ——
                db.execSQL(MigrationSql.CREATE_CATEGORIES_V4)
                db.execSQL(MigrationSql.COPY_CATEGORIES_V4)
                db.execSQL(MigrationSql.DROP_CATEGORIES_OLD)
                db.execSQL(MigrationSql.RENAME_CATEGORIES_V4)
                db.execSQL(MigrationSql.RECREATE_CATEGORY_TYPE_INDEX)
                db.execSQL(MigrationSql.RECREATE_CATEGORY_SECTION_INDEX)

                // —— sections：重建（emoji → iconId）并新增 colorIndex ——
                db.execSQL(MigrationSql.CREATE_SECTIONS_V4)
                db.execSQL(MigrationSql.COPY_SECTIONS_V4)
                db.execSQL(MigrationSql.DROP_SECTIONS_OLD)
                db.execSQL(MigrationSql.RENAME_SECTIONS_V4)
                db.execSQL(MigrationSql.ALIGN_SECTION_COLOR_INDEX)

                // —— entries：双维度状态 ——
                db.execSQL(MigrationSql.ADD_ENTRY_RECONCILED)
                db.execSQL(MigrationSql.ADD_ENTRY_REIMBURSE_STATE)
            }
        }

        /**
         * v3 → v4 的前置守卫：本迁移**要求外键处于关闭状态**。
         *
         * 为什么这不是多余的检查（完整论证见 [MigrationSql] 的 v3→v4 段）：
         * `categories` / `sections` 必须「重建」才能去掉 `emoji` 列（`DROP COLUMN`
         * 需要 SQLite 3.35+，本项目 minSdk 26），而重建绕不开 `DROP TABLE`；
         * 偏偏 `entries` 对这两个表有 `ON DELETE RESTRICT` 外键。
         *
         * ⚠️ 已离线实测：**RESTRICT 是立即检查的，无法延迟**。`PRAGMA defer_foreign_keys`
         * 与 `PRAGMA legacy_alter_table` 两种绕法在外键开启时**同样失败**
         * （见 `tools/verify_migration_v4.py` 的 foreign_keys=ON 用例）。
         * 也就是说：外键一旦开启，SQL 层面无解。
         *
         * 当前状态是安全的，有三重证据：Room 2.8.5 从不设置该 pragma（已反汇编确认）、
         * 本项目未调用 `setForeignKeyConstraintsEnabled`、SQLite 默认值为 OFF。
         * 这道守卫的作用是把这层**隐式依赖变成显式检查**——将来若有人打开外键，
         * 升级时会看到这条可执行的报错，而不是一句难查的 `FOREIGN KEY constraint failed`。
         */
        private fun requireForeignKeyDisabled(db: SupportSQLiteDatabase) {
            val enabled = db.query("PRAGMA foreign_keys").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            check(enabled == 0) {
                "v3→v4 迁移要求外键关闭，当前为开启。原因：categories / sections 必须重建才能" +
                    "去掉 emoji 列，重建需要 DROP TABLE，而 entries 对它们有 ON DELETE RESTRICT " +
                    "外键（立即检查、无法延迟，实测外键开启时无解）。" +
                    "请勿调用 RoomDatabase.Builder 或 SQLiteOpenHelper 的外键开启方法。"
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        seed(db)
                    }
                })
                .build()

        /**
         * 首次启动时按 [SectionFirstSeed] 的**理想结构**预置数据（用户可随意增删改）。
         *
         * 与 `MIGRATION_2_3` / `MIGRATION_3_4` 同源：先插分区、再插全局分类（sectionId = NULL）、
         * 最后按分区名回查 id 插分区专属分类——保证「新装」与「升级」初始态一致。
         *
         * v4 起图标写 `iconId`（不再是 emoji），分区额外写 `colorIndex`。
         */
        private fun seed(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()

            // 1) 分区（含图标与胶带色）
            SectionFirstSeed.sections.forEachIndexed { index, section ->
                db.execSQL(
                    "INSERT INTO sections (name, iconId, note, budgetCents, colorIndex, sortOrder, createdAt) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        section.name, section.iconId, section.note,
                        section.budgetCents, section.colorIndex, index, now,
                    ),
                )
            }

            // 2) 全局分类（sectionId = NULL）；sortOrder 按 (type, 全局) 作用域递增
            val counters = HashMap<String, Int>()
            SectionFirstSeed.globalCategories.forEach { category ->
                val order = nextOrder(counters, category.type, null)
                db.execSQL(
                    "INSERT INTO categories (name, iconId, type, sectionId, sortOrder) VALUES (?, ?, ?, NULL, ?)",
                    arrayOf<Any?>(category.name, category.iconId, category.type, order),
                )
            }

            // 3) 分区专属分类；按 sectionName 回查分区 id
            SectionFirstSeed.sectionCategories.forEach { category ->
                val sectionId = db.query(
                    "SELECT id FROM sections WHERE name = ? ORDER BY id LIMIT 1",
                    arrayOf(category.sectionName),
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
                val order = nextOrder(counters, category.type, sectionId)
                db.execSQL(
                    "INSERT INTO categories (name, iconId, type, sectionId, sortOrder) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(category.name, category.iconId, category.type, sectionId, order),
                )
            }
        }

        /** 在 (type, 归属) 作用域内取下一个 sortOrder（与 `CategoryDao.nextSortOrder` 口径一致） */
        private fun nextOrder(counters: MutableMap<String, Int>, type: Int, sectionId: Long?): Int {
            val key = "$type:${sectionId ?: "global"}"
            val next = counters[key] ?: 0
            counters[key] = next + 1
            return next
        }
    }
}
