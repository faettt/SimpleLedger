package com.simpleledger.app.data.local

import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import java.util.UUID

/**
 * 「分区优先」重构后的**理想初始态**（纯 Kotlin 数据结构，无 Android 依赖）。
 *
 * 首次安装的 `seed()` 与升级路径的 `MIGRATION_2_3` / `MIGRATION_3_4` **同源**：
 * 都用这份结构保证「新装」与「升级」两条路径的初始态一致（见设计文档 §4.2 / §4.3）。
 *
 * 结构要点：
 * - 3 个分区：日常开支（5000 元 / 青绿）/ 装修（**26 万元** / 赭黄）/ 旅行（未设预算 / 灰蓝）
 * - 12 个**全局**分类（`sectionName == null` ⇒ 全局），沿用既有分类
 * - 7 个**装修专属**分类（支出 5 + 收入 2，`sectionName == "装修"`）
 *
 * ⚠️ v4 起图标不再是 emoji 字符串，而是 `iconId`（Int，1–50，指向 `docs/design/icons`
 * 的 50 枚手绘图标）。旧数据 emoji 与新 iconId 的对应关系见 [IconMapping]。
 */
object SectionFirstSeed {

    /** 分区示例数据 */
    data class SeedSection(
        val name: String,
        /** 1–50，见 docs/design/icons/manifest.json */
        val iconId: Int,
        val note: String,
        val budgetCents: Long,
        /** 0–7，索引到和纸胶带色板；会沿用到账目行色条与按分区的图表配色 */
        val colorIndex: Int,
    )

    /** 分类示例数据；`sectionName == null` ⇒ 全局，非空 ⇒ 该分区专属 */
    data class SeedCategory(
        val name: String,
        /** 1–50，见 docs/design/icons/manifest.json */
        val iconId: Int,
        val type: Int,
        val sectionName: String?,
    )

    /** 装修分区名（迁移与种子共用，避免两处写死） */
    const val ZHUANGXIU_SECTION_NAME = "装修"

    /** 装修分区预算：26 万元（分） */
    const val ZHUANGXIU_BUDGET_CENTS = 26_000_000L

    /** 分区胶带色索引（与 tokens-journal.json 的 tape.palette 顺序一一对应） */
    object TapeColor {
        const val QING_LV = 0
        const val HUI_LAN = 1
        const val ZHE_HUANG = 2
        const val TAO_TU = 3
        const val TENG_ZI = 4
        const val TAI_LV = 5
        const val OU_FEN = 6
        const val HUI_HE = 7
    }

    val sections: List<SeedSection> = listOf(
        // 1 = pin · 青绿
        SeedSection("日常开支", 1, "日常生活开销", 500_000L, TapeColor.QING_LV),
        // 17 = hammer · 赭黄
        SeedSection(ZHUANGXIU_SECTION_NAME, 17, "主材与人工，控制在 26 万内", ZHUANGXIU_BUDGET_CENTS, TapeColor.ZHE_HUANG),
        // 13 = plane · 灰蓝
        SeedSection("旅行", 13, "出发前把大头订完", 0L, TapeColor.HUI_LAN),
    )

    /** 全局分类：沿用既有 12 个（支出 8 + 收入 4） */
    val globalCategories: List<SeedCategory> = listOf(
        SeedCategory("餐饮", 2, EntryType.EXPENSE, null),      // rice-bowl
        SeedCategory("交通", 8, EntryType.EXPENSE, null),      // bus
        SeedCategory("购物", 15, EntryType.EXPENSE, null),     // bag
        SeedCategory("居住", 16, EntryType.EXPENSE, null),     // house
        SeedCategory("医疗", 20, EntryType.EXPENSE, null),     // pill
        SeedCategory("娱乐", 22, EntryType.EXPENSE, null),     // gamepad
        SeedCategory("学习", 26, EntryType.EXPENSE, null),     // book
        SeedCategory("其他支出", 42, EntryType.EXPENSE, null),  // box
        SeedCategory("工资", 36, EntryType.INCOME, null),      // money-bag
        SeedCategory("理财", 37, EntryType.INCOME, null),      // invest
        SeedCategory("红包", 32, EntryType.INCOME, null),      // red-envelope
        SeedCategory("其他收入", 41, EntryType.INCOME, null),   // sparkle
    )

    /** 分区专属分类：装修专属（支出 5 + 收入 2） */
    val sectionCategories: List<SeedCategory> = listOf(
        SeedCategory("主材", 44, EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),   // brick
        SeedCategory("人工", 45, EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),   // helmet
        SeedCategory("家具", 46, EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),   // sofa
        SeedCategory("家电", 47, EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME),   // tv
        SeedCategory("设计费", 48, EntryType.EXPENSE, ZHUANGXIU_SECTION_NAME), // ruler
        SeedCategory("报销", 49, EntryType.INCOME, ZHUANGXIU_SECTION_NAME),    // banknote
        SeedCategory("退款", 50, EntryType.INCOME, ZHUANGXIU_SECTION_NAME),    // refund
    )

    /** 全部分类（全局 + 分区专属），便于单测整体断言 */
    val categories: List<SeedCategory> = globalCategories + sectionCategories

    /**
     * B1「未分类」哨兵：categories 表内置的**系统分类**，每种类型（支出/收入）各一行。
     *
     * 定案要点：
     * - **零 schema 变更**：就是普通分类行（plain category rows），同步协议零改动，
     *   旧客户端把它当普通分类展示；
     * - **不可删 / 不可改名**：管理列表中可见，但编辑 / 删除 / 排序操作全部禁用；
     * - **确定性 syncId**（`SeedIds.unclassified(type)`）：两台设备各自补种出来的是同一逻辑行，
     *   合并天然对齐（与 SectionFirstSeed 的 H7 同一模式）；
     * - **幂等补种**：新装走 `AppDatabase.seed()`，升级 / 崩溃恢复走启动时
     *   `LedgerRepository.ensureUnclassified()`——两条路径共用 [missingCategories]，
     *   重复执行零副作用。
     */
    object Unclassified {

        /** 展示名（明细 / 统计 / 导出里与用户所见一致） */
        const val NAME = "未分类"

        /** 中性图标 43 = tag（与分类默认图标一致，不携带语义） */
        const val ICON_ID = 43

        /** 哨兵的确定性 syncId（支出 / 收入各一个） */
        fun syncId(type: Int): String = SeedIds.unclassified(type)

        /** 按行身份（syncId）判定是否哨兵——用户自建的同名分类不算 */
        fun isUnclassified(syncId: String): Boolean =
            syncId == syncId(EntryType.EXPENSE) || syncId == syncId(EntryType.INCOME)

        fun isUnclassified(category: CategoryEntity): Boolean = isUnclassified(category.syncId)

        /** 哨兵行模板（sortOrder / updatedAt 由落库方按作用域补齐） */
        fun categoryRow(type: Int): CategoryEntity = CategoryEntity(
            name = NAME,
            iconId = ICON_ID,
            type = type,
            sectionId = null,
            sortOrder = 0,
            syncId = syncId(type),
            versionSeq = 0,
            updatedAt = 0,
        )

        /**
         * 幂等补种计划（纯函数）：给定库中既有分类的 syncId 集合，返回**缺失**的哨兵行。
         * 第二次调用（哨兵已在）返回空列表——这就是幂等性的测试锚点。
         */
        fun missingCategories(existingSyncIds: Set<String>): List<CategoryEntity> =
            listOf(EntryType.EXPENSE, EntryType.INCOME)
                .map { type -> categoryRow(type) }
                .filter { it.syncId !in existingSyncIds }

        /**
         * B2 保存口径：记账不选分类 = 记到「未分类」哨兵（不再硬拦「请选择分类」）。
         * [unclassifiedId] 由调用方解析（`LedgerRepository.unclassifiedCategoryId(type)`）。
         */
        fun saveCategoryId(selectedCategoryId: Long?, unclassifiedId: Long): Long =
            selectedCategoryId ?: unclassifiedId
    }
}

/**
 * 种子行的**确定性 syncId** 派生（v5 多端同步，架构设计 §3.6 / H7）。
 *
 * 两台设备各自新装后，默认分区 / 分类必须是**同一逻辑行**，否则合并会复制出两套
 * 「日常开支 / 装修 / 旅行」。解法：种子行的 syncId 不用随机 UUID，而是由「种子 key」
 * 确定性派生——所有设备对同一种子行算出同一个 syncId，合并天然对齐。
 *
 * **单一真源**：`AppDatabase.seed()`（新装写入）与 `MigrationSql`（升级回填覆盖）
 * 都必须调用本对象的同一组函数，禁止各自手写公式。
 *
 * 形态：`"sd" + nameUUID(key).hex 截 30` = 32 字符小写（`sd` 前缀可与随机 32hex 区分）。
 * 用户改过名 / 换过图标的种子行**无法**按 key 识别（迁移期）→ 取随机 syncId，
 * 极端情况合并出重复行（用户删一条即可，走标准删除兜底链）——已知边界，见设计 §8 U-5。
 */
object SeedIds {

    /** 分区种子 key = `section:<分区名>` */
    fun section(name: String): String = seed("section:$name")

    /**
     * 分类种子 key = `category:<type><:sectionName>:<分类名>`
     * （全局分类 `sectionName` 传 null，key 中为空串）。
     */
    fun category(type: Int, sectionName: String?, name: String): String =
        seed("category:$type:${sectionName ?: ""}:$name")

    /** 「未分类」哨兵种子 key = `unclassified:<type>`（B1，两设备补种同一逻辑行） */
    fun unclassified(type: Int): String = seed("unclassified:$type")

    /** `UUID.nameUUIDFromBytes`（MD5 名字型 UUID v3）取 hex32 前 30 位，加 `sd` 前缀 */
    private fun seed(key: String): String =
        "sd" + UUID.nameUUIDFromBytes(key.toByteArray()).toString().replace("-", "").take(30)
}
