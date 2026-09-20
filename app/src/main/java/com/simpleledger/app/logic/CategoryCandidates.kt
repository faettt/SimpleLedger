package com.simpleledger.app.logic

import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.CategoryTotal

/**
 * 分类候选的纯逻辑（**无 Android 依赖**，可在 JVM 单测里锁住查询口径）。
 *
 * 候选口径的唯一真源是 `CategoryDao.observeCandidates(sectionId, type)`：
 * `categories WHERE type = :type AND (sectionId IS NULL OR sectionId = :sectionId)`，
 * 排序「专属在前 → sortOrder → id」。本对象只做「把有序结果拆成两组」「占比标签拼接」
 * 「空态判定」这类纯函数，**禁止**在 UI 组合内自行拼接 / 去重（同名不合并，Q-02）。
 */
object CategoryCandidates {

    /**
     * 已按「专属在前、全局在后」拆分的候选。
     * [all] 为展平后的顺序（专属 + 全局），供「是否在候选内」这类判断使用。
     */
    data class Candidates(
        val exclusive: List<CategoryEntity>,
        val global: List<CategoryEntity>,
    ) {
        val all: List<CategoryEntity> get() = exclusive + global

        val isEmpty: Boolean get() = exclusive.isEmpty() && global.isEmpty()
    }

    /**
     * 输入 [com.simpleledger.app.data.local.dao.CategoryDao.observeCandidates] 的**有序**结果，
     * 按 `sectionId` 是否为空拆成「专属 / 全局」两组，各自保持内部相对顺序不变。
     */
    fun partition(ordered: List<CategoryEntity>): Candidates {
        val exclusive = ArrayList<CategoryEntity>()
        val global = ArrayList<CategoryEntity>()
        ordered.forEach { category ->
            if (category.sectionId == null) global.add(category) else exclusive.add(category)
        }
        return Candidates(exclusive = exclusive, global = global)
    }

    /** 仅按类型过滤（供编辑态判断「当前分类是否属于当前类型」之类的场景）。 */
    fun filterByType(list: List<CategoryEntity>, type: Int): List<CategoryEntity> =
        list.filter { it.type == type }

    /**
     * Q-09：分类占比标签。
     * - 专属 →「🔨 装修 · 材料」（带所属分区 emoji + 名称消歧）
     * - 全局 →「🍚 餐饮」（直接用分类 emoji + 名称）
     */
    fun shareLabel(total: CategoryTotal): String = if (total.sectionId != null) {
        val emoji = total.sectionEmoji ?: ""
        val name = total.sectionName ?: "分区"
        // 与设计示例「🔨 装修 · 材料」一致：分区 emoji 与名称之间保留一个空格
        "${emoji} ${name} · ${total.name}"
    } else {
        "${total.emoji} ${total.name}"
    }

    /**
     * EC-05：当前分区 + 当前类型下候选集合为空 → 走空态引导。
     * 注意：只要**全局分类**存在，就不算空态（EC-05 第 4 条）。
     */
    fun isEmpty(candidates: Candidates): Boolean = candidates.isEmpty
}
