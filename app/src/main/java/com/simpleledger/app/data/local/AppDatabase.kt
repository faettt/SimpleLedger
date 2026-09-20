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
    version = 2,
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        seed(db)
                    }
                })
                .build()

        /** 首次启动时预置常用分区与分类，用户可随意增删改 */
        private fun seed(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            // 预算示例：日常开支 5000 元 / 装修 26 万元 / 旅行未设预算
            db.execSQL(
                "INSERT INTO sections (name, emoji, note, budgetCents, sortOrder, createdAt) VALUES " +
                    "('日常开支', '📌', '日常生活开销', 500000, 0, $now), " +
                    "('装修', '🔨', '主材与人工，控制在 26 万内', 26000000, 1, $now), " +
                    "('旅行', '✈️', '出发前把大头订完', 0, 2, $now)"
            )
            db.execSQL(
                "INSERT INTO categories (name, emoji, type, sortOrder) VALUES " +
                    "('餐饮', '🍚', 0, 0), " +
                    "('交通', '🚌', 0, 1), " +
                    "('购物', '🛍️', 0, 2), " +
                    "('居住', '🏠', 0, 3), " +
                    "('医疗', '💊', 0, 4), " +
                    "('娱乐', '🎮', 0, 5), " +
                    "('学习', '📚', 0, 6), " +
                    "('其他支出', '📦', 0, 7)"
            )
            db.execSQL(
                "INSERT INTO categories (name, emoji, type, sortOrder) VALUES " +
                    "('工资', '💰', 1, 0), " +
                    "('理财', '📈', 1, 1), " +
                    "('红包', '🧧', 1, 2), " +
                    "('其他收入', '✨', 1, 3)"
            )
        }
    }
}
