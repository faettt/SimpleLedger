package com.simpleledger.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sectionDao(): SectionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun entryDao(): EntryDao

    companion object {
        private const val DB_NAME = "simple_ledger.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
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
            db.execSQL(
                "INSERT INTO sections (name, emoji, note, sortOrder, createdAt) VALUES " +
                    "('日常开支', '📌', '日常生活开销', 0, $now), " +
                    "('装修', '🔨', '预算 26 万，红线 30 万', 1, $now), " +
                    "('旅行', '✈️', '出发前把大头订完', 2, $now)"
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
