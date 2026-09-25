package com.simpleledger.app

import com.simpleledger.app.logic.RestoreFallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收站恢复「原分区已死」兜底单测（A1）。
 *
 * 定案：原分区存活 → 原地；已死 → 存活分区中 (sortOrder, id) 最小的一个；
 * 一个分区都不剩 → null（调用方沿用死引用 0 占位口径，不伪造分区）。
 */
class RestoreFallbacksTest {

    private fun ref(id: Long, sortOrder: Int) = RestoreFallbacks.SectionRef(id, sortOrder)

    @Test
    fun `alive original keeps its section`() {
        val sections = listOf(ref(2, 0), ref(1, 1))
        assertEquals(
            7L,
            RestoreFallbacks.fallbackSectionId(
                originalSectionId = 7L,
                originalAlive = true,
                sections = sections,
            ),
        )
        // 原分区存活即使列表为空也原地不动
        assertEquals(
            7L,
            RestoreFallbacks.fallbackSectionId(originalSectionId = 7L, originalAlive = true, sections = emptyList()),
        )
    }

    @Test
    fun `dead original falls to first alive by sortOrder then id`() {
        val sections = listOf(
            ref(5, 2),
            ref(3, 1),
            ref(4, 1), // 与 3 同 sortOrder → id 小者胜
            ref(9, 0), // sortOrder 最小
        )
        assertEquals(
            9L,
            RestoreFallbacks.fallbackSectionId(originalSectionId = 7L, originalAlive = false, sections = sections),
        )
    }

    @Test
    fun `no alive section returns null`() {
        assertNull(
            RestoreFallbacks.fallbackSectionId(originalSectionId = 7L, originalAlive = false, sections = emptyList()),
        )
    }

    @Test
    fun `rehome flag follows aliveness`() {
        assertTrue(RestoreFallbacks.needsRehome(originalAlive = false))
        assertFalse(RestoreFallbacks.needsRehome(originalAlive = true))
    }
}
