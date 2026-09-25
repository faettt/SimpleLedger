package com.simpleledger.app

import com.simpleledger.app.logic.PhotoRetention
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 照片引用挂起判据单测（A2）。
 *
 * 定案：实体文件删除当且仅当「业务引用为零 **且** 未消化留底引用为零」。
 * 误宽不误漏：任何一面仍有引用都必须保留文件——漏算会丢用户照片，绝不允许。
 */
class PhotoRetentionTest {

    @Test
    fun `file deleted only when both ref counts are zero`() {
        assertTrue(PhotoRetention.shouldDeleteFile(liveRefCount = 0, suspendedTrashRefCount = 0))
    }

    @Test
    fun `live reference keeps file`() {
        assertFalse(PhotoRetention.shouldDeleteFile(liveRefCount = 1, suspendedTrashRefCount = 0))
        assertFalse(PhotoRetention.shouldDeleteFile(liveRefCount = 3, suspendedTrashRefCount = 0))
    }

    @Test
    fun `visible trash reference keeps file`() {
        assertFalse(PhotoRetention.shouldDeleteFile(liveRefCount = 0, suspendedTrashRefCount = 1))
        assertFalse(PhotoRetention.shouldDeleteFile(liveRefCount = 0, suspendedTrashRefCount = 7))
    }

    @Test
    fun `both referencing keeps file`() {
        assertFalse(PhotoRetention.shouldDeleteFile(liveRefCount = 2, suspendedTrashRefCount = 2))
    }
}
