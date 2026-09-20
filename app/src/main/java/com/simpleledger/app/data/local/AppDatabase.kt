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
    version = 3,
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
         * 与 `MIGRATION_2_3` 同源：先插分区、再插全局分类（sectionId = NULL）、
         * 最后按分区名回查 id 插分区专属分类——保证「新装」与「升级」初始态一致。
         */
        private fun seed(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()

            // 1) 分区
            SectionFirstSeed.sections.forEachIndexed { index, section ->
                db.execSQL(
                    "INSERT INTO sections (name, emoji, note, budgetCents, sortOrder, createdAt) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(section.name, section.emoji, section.note, section.budgetCents, index, now),
                )
            }

            // 2) 全局分类（sectionId = NULL）；sortOrder 按 (type, 全局) 作用域递增
            val counters = HashMap<String, Int>()
            SectionFirstSeed.globalCategories.forEach { category ->
                val order = nextOrder(counters, category.type, null)
                db.execSQL(
                    "INSERT INTO categories (name, emoji, type, sectionId, sortOrder) VALUES (?, ?, ?, NULL, ?)",
                    arrayOf<Any?>(category.name, category.emoji, category.type, order),
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
                    "INSERT INTO categories (name, emoji, type, sectionId, sortOrder) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(category.name, category.emoji, category.type, sectionId, order),
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
