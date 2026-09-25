package com.simpleledger.app.sync

import com.simpleledger.app.sync.crypto.FilenameNym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件名假名性质（T-2 验收②）：同输入同名（稳定性）/ 异输入异名（不可关联性）/
 * 形态 = 26 字符小写 base32；分片名 = 32hex + ".op" 且互不重复。
 */
class FilenameNymTest {

    private val nameKeyA = ByteArray(32) { 1 }
    private val nameKeyB = ByteArray(32) { 2 }
    private val hash1 = "a".repeat(64)
    private val hash2 = "b".repeat(64)

    @Test
    fun sameInputSameName() {
        assertEquals(
            FilenameNym.photoName(nameKeyA, hash1),
            FilenameNym.photoName(nameKeyA.copyOf(), hash1),
        )
        assertEquals(FilenameNym.metaName(nameKeyA), FilenameNym.metaName(nameKeyA.copyOf()))
    }

    @Test
    fun differentInputDifferentName() {
        assertNotEquals(FilenameNym.photoName(nameKeyA, hash1), FilenameNym.photoName(nameKeyA, hash2))
        assertNotEquals(FilenameNym.metaName(nameKeyA), FilenameNym.photoName(nameKeyA, hash1))
        // 换 nameKey ⇒ 全部假名换脸（密钥分域的意义）
        assertNotEquals(FilenameNym.photoName(nameKeyA, hash1), FilenameNym.photoName(nameKeyB, hash1))
    }

    @Test
    fun nymShapeIsLowercaseBase32Of26Chars() {
        val names = listOf(
            FilenameNym.photoName(nameKeyA, hash1),
            FilenameNym.metaName(nameKeyA),
            FilenameNym.photoName(nameKeyB, hash2),
        )
        for (name in names) {
            assertEquals(26, name.length)
            assertTrue("非法假名字符: $name", name.all { it in 'a'..'z' || it in '2'..'7' })
            assertFalse("云端不得出现明文信息", name.contains("photo") || name.contains("meta"))
        }
    }

    @Test
    fun chunkNameIsRandomHexWithOpSuffix() {
        val a = FilenameNym.chunkName()
        val b = FilenameNym.chunkName()
        assertTrue(a.endsWith(".op"))
        assertEquals(32, a.removeSuffix(".op").length)
        assertTrue(a.removeSuffix(".op").all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a, b) // 128bit 随机，碰撞概率可忽略
    }
}
