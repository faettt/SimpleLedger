package com.simpleledger.app.ui.entry

/**
 * 编辑页 / 编辑面板删除确认框的文案（U-14）。两处对话框（全屏路由 [EntryEditScreen]
 * 与就地宿主 [EntryEditHost]）共用同一常量，保证大屏与手机口径永远一致。
 *
 * 缺陷背景（旧文案「删除后可用明细页提示条里的「撤销」恢复，贴图也会一并删除。」两处失实）：
 *  1. 该路径走 `repo.deleteEntry`（LedgerDeleteRestore.kt:225 无快照删除，快照用完即弃），
 *     且完成后 `savedEntryId` 为 null → RESULT_SAVED_ENTRY_ID 置 -1（AppRoot.kt:468），
 *     被明细页 / 分区详情的 `entryId > 0` 过滤（LedgerScreen.kt:124、SectionDetailScreen.kt:120）
 *     ——承诺的「提示条撤销」根本不会出现；
 *  2. A2 照片挂起口径下贴图文件不物理删除（parkedPath 恒空，LedgerDeleteRestore.kt:270、:322-326），
 *     「贴图也会一并删除」同样不实。
 *
 * 据实改写：此路径删除后账目进同步留底（ops.onDelete → 回收站留底），留底期内可在
 * 「我的 → 冲突回收站」找回（与 strings.xml trash_auto_note「留底 90 天后自动清理」同口径，
 * 90 天对应 sync/SyncManager.TRASH_RETENTION_MILLIS）；贴图随账目一并留底，
 * 回收站恢复时按 contentHash 原路回链。
 *
 * 注意：若将来把该路径改为快照删除 + 返回撤销提示条（与列表长按删除同一撤销语义），
 * 必须同步改回本文案，并更新 EntryDeleteConfirmTest 的断言。
 */
internal const val ENTRY_DELETE_CONFIRM_TITLE = "删除这笔账目？"

internal const val ENTRY_DELETE_CONFIRM_BODY =
    "删除后账目将留底 90 天，可在「我的 → 冲突回收站」找回，贴图一并留底；" +
        "明细页提示条的「撤销」不适用于这里。"
