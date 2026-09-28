package com.simpleledger.app.ui.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * U-13 回归：saved 终态必须「一次性消费」。
 *
 * 缺陷回顾：保存 / 删除成功把 [EntryEditUiState.saved] 置 true 后原先永不复位，而就地
 * 编辑宿主按 `viewModel(key = "entry_$entryId")` 在同一返回栈条目内缓存 VM——面板关闭
 * 后再点开同一笔账，LaunchedEffect(state.saved) 读到残留 true 立即再回调 onSaved，
 * 编辑面板闪现即自动关闭并重复弹「已记入」，大屏上无法二次编辑同一笔账。
 *
 * 说明（可行性口径，同 MigrationSqlTest）：EntryEditViewModel 依赖真实 LedgerRepository
 * （Room / Android 依赖），本机 `testDebugUnitTest` 无法构造；故把「消费」语义抽成纯函数
 * [takeSavedOutcome]，用 JVM 单测钉死「消费一次后不得再次触发」的口径。宿主侧
 * 「先取局部变量、先消费、再回调」的接线为组合层行为，待真机走查（见修复汇报 caveats）。
 */
class EntryEditSavedResetTest {

    @Test
    fun `saved terminal state is consumed exactly once`() {
        val saved = EntryEditUiState(saved = true, savedEntryId = 42L)

        val (reset, consumedId) = saved.takeSavedOutcome()!!

        // 消费时交出本次保存的账目 id（宿主用它发 onSaved 回调）
        assertEquals(42L, consumedId)
        // 状态复位：saved 归 false、savedEntryId 清空——缓存的 VM 下次进组合
        // 读不到残留 true，面板不会闪现即关
        assertFalse(reset.saved)
        assertNull(reset.savedEntryId)
        // 复位只动这两个字段，表单其余状态必须原样保留
        assertEquals(saved.isEdit, reset.isEdit)
        assertEquals(saved.sectionId, reset.sectionId)
        assertEquals(saved.amountText, reset.amountText)
        assertEquals(saved.note, reset.note)
        assertEquals(saved.images, reset.images)
        assertEquals(saved.selectedCategoryId, reset.selectedCategoryId)

        // 第二次消费：无事件。这正是缓存 VM 跨开合复用时旧行为的病灶所在
        assertNull(reset.takeSavedOutcome())
    }

    @Test
    fun `unsaved state has nothing to consume`() {
        val idle = EntryEditUiState(saved = false, savedEntryId = 99L)
        assertNull(idle.takeSavedOutcome())
    }

    @Test
    fun `delete completion also flows through saved and is one-shot`() {
        // 删除成功（EntryEditViewModel.deleteEntry）同样只置 saved=true、savedEntryId=null
        val deleted = EntryEditUiState(saved = true, savedEntryId = null)

        val (reset, consumedId) = deleted.takeSavedOutcome()!!

        assertNull(consumedId)
        assertFalse(reset.saved)
        assertNull(reset.savedEntryId)
        assertNull(reset.takeSavedOutcome())
    }
}
