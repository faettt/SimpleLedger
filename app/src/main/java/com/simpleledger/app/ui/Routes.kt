package com.simpleledger.app.ui

/**
 * 路由契约（**唯一真源**）。
 *
 * 「分区优先」重构后导航从 5 槽收敛为 4 槽（分区 · 明细 · 统计 · 我的），且分区升为首屏。
 * 各页面**不得**硬编码路由字符串，一律引用本文件里的常量 / 构造器——这样 T02/T03/T04
 * 可以各自独立推进而只依赖本文件。
 *
 * - [NEW_ENTRY_ID] = -1：新建账目（编辑态传真实账目 id）
 * - [NEW_SECTION] = -1：编辑态「分区由账目读取」，不做分区上下文覆盖
 */
object Routes {

    /** 一级导航（顺序即 UI 顺序）：分区 · 明细 · 统计 · 我的 */
    const val SECTIONS = "sections"
    const val LEDGER = "ledger"
    const val STATS = "stats"
    const val MINE = "mine"

    /** 「我的 → 记账 → 全局分类」入口页面 */
    const val GLOBAL_CATEGORIES = "global-categories"

    /** 分区详情：section/{sectionId} */
    const val SECTION_DETAIL = "section/{sectionId}"

    /** 分区管理：section/{sectionId}/manage（编辑分区信息 + 管理该分区专属分类） */
    const val SECTION_MANAGE = "section/{sectionId}/manage"

    /** 记一笔 / 编辑账目：entry/{entryId}?sectionId={sectionId} */
    const val ENTRY_EDIT = "entry/{entryId}?sectionId={sectionId}"

    // 路由参数名（避免各处硬编码字符串）
    const val ARG_ENTRY_ID = "entryId"
    const val ARG_SECTION_ID = "sectionId"

    // 哨兵值
    const val NEW_ENTRY_ID = -1L
    const val NEW_SECTION = -1L

    fun sectionDetail(sectionId: Long): String = "section/$sectionId"

    fun sectionManage(sectionId: Long): String = "section/$sectionId/manage"

    /**
     * 新建：entryId = [NEW_ENTRY_ID]、必须带 sectionId；
     * 编辑：带真实 entryId，sectionId 传 [NEW_SECTION]（由 VM 从账目读取，分区只读）。
     */
    fun entryEdit(entryId: Long, sectionId: Long = NEW_SECTION): String =
        "entry/$entryId?$ARG_SECTION_ID=$sectionId"

    /** 一级导航（4 槽，顺序即 UI 顺序） */
    val topLevel = listOf(SECTIONS, LEDGER, STATS, MINE)
}
