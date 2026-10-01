package com.simpleledger.app.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.simpleledger.app.data.local.IconMapping

/** 账目类型：支出 / 收入 */
object EntryType {
    const val EXPENSE = 0
    const val INCOME = 1
}

/**
 * 报销状态（v4 引入，D4 裁定：与「核对」是两个独立维度，可叠加）。
 *
 * 与 `EntryEntity.reconciled` 正交：一笔装修支出可以同时「已核对」且「待报销」。
 * 账目行会用两个独立的 14dp 符号槽分别渲染这两个维度。
 */
object ReimburseState {
    /** 不适用（默认）——槽位留空 */
    const val NONE = 0

    /** 待报销——空心圆，朱砂色 */
    const val PENDING = 1

    /** 已报销——实心圆，松烟墨绿色 */
    const val CLEARED = 2
}

/**
 * 分区：账目的一级分组（如「日常开支」「装修」「旅行」）。
 * 每个分区可以有自己的备注（分区备注）、月度预算与专属胶带色。
 *
 * ⚠️ v4 起 `emoji` 已替换为 [iconId]（Int，1–50）。历史 emoji 的翻译见 `IconMapping`。
 */
@Entity(tableName = "sections", indices = [Index(value = ["syncId"], unique = true)])
data class SectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 图标：1–50，指向 50 枚手绘图标（见 docs/design/icons/manifest.json） */
    val iconId: Int = IconMapping.DEFAULT_SECTION_ICON_ID,
    val note: String = "",
    /** 月度预算，单位：分；0 表示未设置预算 */
    val budgetCents: Long = 0,
    /**
     * 胶带色索引 0–7（见设计令牌 `tape.palette`）。
     * 该色会沿用到：分区卡片色边 → 账目行左侧 3dp 色条 → 按分区的图表配色。
     */
    val colorIndex: Int = 0,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 全局同步 ID（32 字符小写，v5 多端同步引入）。
     * 跨设备引用**只用它**，本地自增 [id] 永不出现在操作载荷里：
     * 新行 = `UUID` 32hex；迁移存量 = 随机 32hex；种子行 = [SeedIds] 确定性派生（防两机默认分区撞车）。
     */
    val syncId: String = "",
    /** 行级 Lamport 因果计数（CRDT 版本）；每次写入 = 旧值 + 1 */
    val versionSeq: Long = 0,
    /** 最近一次语义修改时间（epoch millis）；迁移存量回填 = createdAt */
    val updatedAt: Long = createdAt,
)

/**
 * 分类：用户自定义的支出 / 收入类型（如「餐饮」「交通」），挂在某条账目上。
 *
 * 「分区优先」重构后引入**归属维度** `sectionId`：
 * - `sectionId == null` ⇒ **全局**（所有分区可见，一次定义、到处可用）；
 * - `sectionId != null` ⇒ **该分区专属**。
 *
 * ⚠️ **刻意不给 `sectionId` 声明 FK**（见设计 D-1）：SQLite 无法为已有表补 FK，
 * 一旦声明就得重建 `categories`。完整性改由 `LedgerRepository` 保证
 * （删分区时显式 `detachFromSection` 降级为全局）。
 * ⚠️ **严禁用 `0L` 表示全局**——`null` 才是全局语义。
 *
 * ⚠️ v4 起 `emoji` 已替换为 [iconId]（Int，1–50）。历史 emoji 的翻译见 `IconMapping`。
 */
@Entity(
    tableName = "categories",
    indices = [Index("type"), Index("sectionId"), Index(value = ["syncId"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 图标：1–50，指向 50 枚手绘图标（见 docs/design/icons/manifest.json） */
    val iconId: Int = IconMapping.DEFAULT_CATEGORY_ICON_ID,
    /** 0 = 支出分类，1 = 收入分类 */
    val type: Int,
    /** 归属：null = 全局；非空 = 该分区专属（不加 FK，见类注释） */
    val sectionId: Long? = null,
    val sortOrder: Int = 0,
    /** 全局同步 ID（v5），见 [SectionEntity.syncId] */
    val syncId: String = "",
    /** 行级 Lamport 因果计数（v5），见 [SectionEntity.versionSeq] */
    val versionSeq: Long = 0,
    /** 最近一次语义修改时间（v5）；迁移存量回填 0 */
    val updatedAt: Long = 0,
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
    indices = [
        Index("entryTime"),
        Index("sectionId"),
        Index("categoryId"),
        Index(value = ["syncId"], unique = true),
        Index("memberId"),
    ],
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
    /**
     * 核对状态：false = 未核对（默认），true = 已核对（账目行显示 ✓）。
     * 与 [reimburseState] 正交，可同时成立。
     */
    val reconciled: Boolean = false,
    /** 报销状态，见 [ReimburseState] */
    val reimburseState: Int = ReimburseState.NONE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 全局同步 ID（v5），见 [SectionEntity.syncId] */
    val syncId: String = "",
    /** 行级 Lamport 因果计数（v5），见 [SectionEntity.versionSeq] */
    val versionSeq: Long = 0,
    /**
     * 记账成员的同步 ID（v5，指向 [MemberEntity.syncId]）。
     * `null` = 未知成员（存量账目 / 未认领设备）；刻意不加 FK（跨设备合并以 syncId 松耦合）。
     */
    val memberId: String? = null,
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
    indices = [Index("entryId"), Index(value = ["syncId"], unique = true), Index("contentHash")],
)
data class EntryImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    /** 图片文件的绝对路径 */
    val filePath: String,
    val sortOrder: Int = 0,
    /** 全局同步 ID（v5），见 [SectionEntity.syncId] */
    val syncId: String = "",
    /** 行级 Lamport 因果计数（v5），见 [SectionEntity.versionSeq] */
    val versionSeq: Long = 0,
    /** 最近一次语义修改时间（v5）；迁移存量回填 0 */
    val updatedAt: Long = 0,
    /**
     * 明文 JPEG 的 SHA-256（64hex 小写，v5 照片内容寻址）。
     * 空串 = 尚未补算（存量照片由启动后台一次性补算）；补算完成前该照片不参与同步。
     */
    val contentHash: String = "",
)

/**
 * 账目 + 分类 + 分区 + 图片 + 成员 的聚合视图。
 *
 * v5 增挂 [member]（记账成员，按 `memberId → syncId` 关联）：存量账目 `memberId = null`
 * 时成员为 null，UI 显示「未知成员」占位（不挤占 62dp 行高，见 U-6）。
 */
data class EntryFull(
    @Embedded val entry: EntryEntity,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: CategoryEntity?,
    @Relation(parentColumn = "sectionId", entityColumn = "id")
    val section: SectionEntity?,
    @Relation(parentColumn = "id", entityColumn = "entryId")
    val images: List<EntryImageEntity>,
    @Relation(parentColumn = "memberId", entityColumn = "syncId")
    val member: MemberEntity? = null,
)

/** 按类型汇总（SUM 结果行） */
data class TypeTotal(
    val type: Int,
    val total: Long,
)

/**
 * 按分类汇总。
 *
 * 「分区优先」重构后增加**归属字段**，供 Q-09「分类占比按 id 聚合 + 分区名消歧」使用：
 * 专属分类会带出所属分区的名称、图标与胶带色，供图表与消歧标签使用；全局分类则为 null。
 *
 * ⚠️ v4 起图标与颜色都是索引（`iconId` 1–50 / `colorIndex` 0–7），不再是 emoji 字符串。
 */
data class CategoryTotal(
    val categoryId: Long,
    val name: String,
    /** 分类图标 1–50 */
    val iconId: Int,
    /** null = 全局 */
    val sectionId: Long?,
    /** 专属分类所属分区名；全局为 null */
    val sectionName: String?,
    /** 专属分类所属分区图标 1–50；全局为 null */
    val sectionIconId: Int?,
    /** 专属分类所属分区胶带色 0–7；全局为 null */
    val sectionColorIndex: Int?,
    val total: Long,
    val count: Int,
)

/** 按分区汇总（含分区备注、月度预算与胶带色，供统计页展示） */
data class SectionTotal(
    val sectionId: Long,
    val name: String,
    /** 分区图标 1–50 */
    val iconId: Int,
    /** 分区胶带色 0–7 */
    val colorIndex: Int,
    val note: String,
    val budgetCents: Long = 0,
    /** 窗口内支出（:start–:end）。统计页按选中月取数；首屏卡片传当月，供月度预算行用 */
    val expense: Long,
    val income: Long,
    val count: Int,
    /** **全部时间**累计支出（不受窗口限制）——首屏卡片的主金额口径（2026-10-01 拍板：
     *  卡片直接展示分区总额，月初不清零；月度数字由预算行「本月已用」单独表达） */
    val totalExpense: Long = 0,
)
