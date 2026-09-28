package com.simpleledger.app

import com.simpleledger.app.data.repo.newSyncId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新行 syncId 契约单测（U-11 拆分后钉住共享辅助的口径；版本身份本身是 U-3 裁定）。
 *
 * 定案锚点：syncId = UUID 去连字符后的 **32 字符小写 hex**，跨设备身份永不变；
 * 每次生成互不相同（两行撞 syncId 即同步合并事故）。
 */
class NewSyncIdTest {

    @Test
    fun `syncId is 32 lowercase hex chars without dashes`() {
        val id = newSyncId()
        assertEquals(32, id.length)
        assertTrue("应为 32 字符小写 hex，实际：$id", id.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun `syncId never contains dashes`() {
        repeat(50) { assertTrue(newSyncId().none { it == '-' }) }
    }

    @Test
    fun `syncId is unique per call`() {
        val ids = (1..100).map { newSyncId() }
        assertEquals(100, ids.toSet().size)
        assertNotEquals(ids[0], ids[1])
    }
}
