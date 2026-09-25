package com.simpleledger.app.sync

import com.simpleledger.app.sync.crypto.BlobHeader
import com.simpleledger.app.sync.crypto.CryptoFormat
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCryptoException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * 密文头 v1 编解码（T-2 验收①配套）：字节级布局钉死 + 版本不符报错（§7）。
 */
class CryptoFormatTest {

    private val kdf = KdfParams(mKiB = 65_536, t = 2, p = 1, salt = ByteArray(16) { it.toByte() })
    private val iv = ByteArray(12) { (it + 40).toByte() }
    private val kcv = ByteArray(16) { (it + 80).toByte() }

    @Test
    fun headerLayoutMatchesSpec() {
        val bytes = CryptoFormat.encodeHeader(
            BlobHeader(CryptoFormat.PURPOSE_META, CryptoFormat.FORMAT_VERSION, kdf, iv, kcv),
        )
        assertEquals(56, bytes.size)
        assertEquals("SLC1", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(CryptoFormat.PURPOSE_META, bytes[4].toInt())
        assertEquals(1, bytes[5].toInt())
        // kdf.mKiB = 65536 = 0x00010000（u32 大端）
        assertEquals(0x00, bytes[6].toInt())
        assertEquals(0x01, bytes[7].toInt())
        assertEquals(0x00, bytes[8].toInt())
        assertEquals(0x00, bytes[9].toInt())
        assertEquals(2, bytes[10].toInt()) // t
        assertEquals(1, bytes[11].toInt()) // p
        kdf.salt.forEachIndexed { i, b -> assertEquals(b, bytes[12 + i]) }
        iv.forEachIndexed { i, b -> assertEquals(b, bytes[28 + i]) }
        kcv.forEachIndexed { i, b -> assertEquals(b, bytes[40 + i]) }
    }

    @Test
    fun roundtripWithKcv() {
        val header = BlobHeader(CryptoFormat.PURPOSE_META, CryptoFormat.FORMAT_VERSION, kdf, iv, kcv)
        val decoded = CryptoFormat.decodeHeader(CryptoFormat.encodeHeader(header))
        assertEquals(header, decoded)
        assertArrayEquals(kcv, decoded.kcv)
    }

    @Test
    fun kcvIsNullWhenAbsentOrAllZero() {
        val withoutKcv = BlobHeader(
            CryptoFormat.PURPOSE_OPS, CryptoFormat.FORMAT_VERSION, kdf, iv, null,
        )
        val bytes = CryptoFormat.encodeHeader(withoutKcv)
        // 非 meta 用途：40..55 必须全 0（§7）
        (40 until 56).forEach { assertEquals(0, bytes[it].toInt()) }
        assertNull(CryptoFormat.decodeHeader(bytes).kcv)
    }

    @Test
    fun badMagicOrVersionThrowsBadFormat() {
        val bytes = CryptoFormat.encodeHeader(
            BlobHeader(CryptoFormat.PURPOSE_META, CryptoFormat.FORMAT_VERSION, kdf, iv, kcv),
        )
        try {
            CryptoFormat.decodeHeader(bytes.copyOf().also { it[2] = 'X'.code.toByte() })
            fail("magic 不符应抛 BadFormat")
        } catch (e: SyncCryptoException.BadFormat) {
            // 预期
        }
        try {
            CryptoFormat.decodeHeader(bytes.copyOf().also { it[5] = 2 })
            fail("版本不符应抛 BadFormat")
        } catch (e: SyncCryptoException.BadFormat) {
            // 预期
        }
        try {
            CryptoFormat.decodeHeader(ByteArray(10))
            fail("长度不足应抛 BadFormat")
        } catch (e: SyncCryptoException.BadFormat) {
            // 预期
        }
    }
}
