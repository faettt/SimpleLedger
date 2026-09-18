package com.simpleledger.app.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** 账目类型：支出 / 收入 */
object EntryType {
    const val EXPENSE = 0
    const val INCOME = 1
}

/**
 * 分区：账目的一级分组（如「日常开支」「装修」「旅行」）。
 * 每个分区可以有自己的备注（分区备注），会展示在管理与统计页面。
 */
@Entity(tableName = "sections")
data class SectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "📌",
    val note: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 分类：用户自定义的支出 / 收入类型（如「餐饮」「交通」），挂在某条账目上。
 */
@Entity(tableName = "categories", indices = [Index("type")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "🏷️",
    /** 0 = 支出分类，1 = 收入分类 */
    val type: Int,
    val sortOrder: Int = 0,
)

/**
 * 账目条目：一笔记账。
 * 金额以「分」为单位存储（Long），避免浮点误差。
 */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("entryTime"), Index("sectionId"), Index("categoryId")],
)
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 0 = 支出，1 = 收入 */
    val type: Int,
    /** 金额，单位：分 */
    val amountCents: Long,
    val categoryId: Long,
    val sectionId: Long,
    /** 记账时间（epoch millis）——时间管理核心字段 */
    val entryTime: Long,
    /** 单独备注：仅属于这一笔的说明 */
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 贴图：一条账目可以有多张图片，文件保存在应用私有目录 */
@Entity(
    tableName = "entry_images",
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entryId")],
)
data class EntryImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    /** 图片文件的绝对路径 */
    val filePath: String,
    val sortOrder: Int = 0,
)

/** 账目 + 分类 + 分区 + 图片 的聚合视图 */
data class EntryFull(
    @Embedded val entry: EntryEntity,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: CategoryEntity?,
    @Relation(parentColumn = "sectionId", entityColumn = "id")
    val section: SectionEntity?,
    @Relation(parentColumn = "id", entityColumn = "entryId")
    val images: List<EntryImageEntity>,
)

/** 按类型汇总（SUM 结果行） */
data class TypeTotal(
    val type: Int,
    val total: Long,
)

/** 按分类汇总 */
data class CategoryTotal(
    val categoryId: Long,
    val name: String,
    val emoji: String,
    val total: Long,
    val count: Int,
)

/** 按分区汇总（含分区备注，供统计页展示） */
data class SectionTotal(
    val sectionId: Long,
    val name: String,
    val emoji: String,
    val note: String,
    val expense: Long,
    val income: Long,
    val count: Int,
)
