package com.simpleledger.app.ui.entry

import com.simpleledger.app.sync.SyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-14 回归：编辑页 / 编辑面板删除确认框的文案契约。
 *
 * 缺陷回顾：旧文案「删除后可用明细页提示条里的「撤销」恢复，贴图也会一并删除。」
 * 两处失实——该路径走无快照删除（LedgerDeleteRestore.kt:225）且回传 id 为 -1 被
 * `entryId > 0` 过滤（LedgerScreen.kt:124），提示条撤销根本不会出现；贴图文件按 A2
 * 留底策略保留在内容寻址目录（LedgerDeleteRestore.kt:270、:322-326），并未「一并删除」。
 *
 * 文案已据实改写为单一真源常量（EntryDeleteConfirm.kt），本测试钉死：
 *  1. 不得再承诺「提示条撤销」这条不存在的恢复路径；
 *  2. 不得再宣称「贴图也会一并删除」，须如实说明留底口径；
 *  3. 必须指向真实存在的恢复入口「我的 → 冲突回收站」（MineScreen → Routes.CONFLICT_TRASH）；
 *  4. 文案写的「留底 90 天」必须与 sync 侧真实保留期一致，防止改保留期时文案失实。
 */
class EntryDeleteConfirmTest {

    @Test
    fun `body must not promise the nonexistent snackbar undo path`() {
        // 旧文案的失实承诺：任何变体都不得回归
        assertFalse(ENTRY_DELETE_CONFIRM_BODY.contains("可用明细页提示条里的「撤销」恢复"))
        assertFalse(ENTRY_DELETE_CONFIRM_BODY.contains("撤销恢复"))
    }

    @Test
    fun `body must not claim images are physically deleted`() {
        // A2 口径：贴图文件按留底策略保留（恢复时按 contentHash 原路回链），并非物理删除
        assertFalse(ENTRY_DELETE_CONFIRM_BODY.contains("贴图也会一并删除"))
        assertTrue(ENTRY_DELETE_CONFIRM_BODY.contains("贴图一并留底"))
    }

    @Test
    fun `body must point to the real recovery entry`() {
        // 与「我的」页入口文案（R.string.mine_sync_trash = 冲突回收站）一致的真实路径
        assertTrue(ENTRY_DELETE_CONFIRM_BODY.contains("「我的 → 冲突回收站」"))
    }

    @Test
    fun `retention days in copy must match sync trash retention`() {
        // 文案写死「留底 90 天」，真源是 SyncManager.TRASH_RETENTION_MILLIS——
        // 若同步侧调整保留期，此断言会红，提示同步更新文案
        assertEquals(90L * 24 * 60 * 60 * 1000, SyncManager.TRASH_RETENTION_MILLIS)
        assertTrue(ENTRY_DELETE_CONFIRM_BODY.contains("留底 90 天"))
    }

    @Test
    fun `title stays the entry-delete question`() {
        assertEquals("删除这笔账目？", ENTRY_DELETE_CONFIRM_TITLE)
    }
}
