package com.simpleledger.app.sync

import com.simpleledger.app.sync.crypto.CryptoFormat
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncCryptoException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 加密原语金标测试（T-2 验收①）：
 * - Argon2id 复现 **RFC 9106 §5.3 官方向量**（逐字节）；
 * - HKDF-SHA256 复现 **RFC 5869 附录 A.1 / A.3 官方向量**（逐字节）；
 * - KCV 用测试侧独立 HMAC 实现交叉验证；
 * - AES-256-GCM roundtrip + 篡改拒绝。
 */
class SyncCryptoTest {

    private val crypto = SyncCrypto()

    private val testKdf = KdfParams(mKiB = 32, t = 1, p = 1, salt = ByteArray(16) { 7 })

    // ---------- RFC 9106 §5.3（Argon2id 官方测试向量） ----------

    @Test
    fun argon2idMatchesRfc9106Section53Vector() {
        // P = 0x01×32, S = 0x02×16, K = 0x03×8, X = 0x04×12, v = 0x13, m = 32 KiB, t = 3, p = 4, T = 32
        val tag = crypto.argon2id(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            secret = ByteArray(8) { 0x03 },
            additional = ByteArray(12) { 0x04 },
            mKiB = 32,
            t = 3,
            p = 4,
            outLen = 32,
        )
        assertEquals(
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            toHex(tag),
        )
    }

    // ---------- RFC 5869 附录 A（HKDF-SHA256 官方测试向量） ----------

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm = crypto.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            len = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            toHex(okm),
        )
    }

    @Test
    fun hkdfMatchesRfc5869TestCase3EmptySaltInfo() {
        val okm = crypto.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(0),
            info = ByteArray(0),
            len = 42,
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            toHex(okm),
        )
    }

    // ---------- 派生链（§1.6） ----------

    @Test
    fun deriveKeysIsDeterministicAndDomainSeparated() {
        val a = crypto.deriveKeys("口令-测试".toCharArray(), testKdf)
        val b = crypto.deriveKeys("口令-测试".toCharArray(), testKdf)
        assertArrayEquals(a.encKey, b.encKey)
        assertArrayEquals(a.nameKey, b.nameKey)
        // enc / name 分域：两把子密钥必须不同
        assertFalse(a.encKey.contentEquals(a.nameKey))
        val other = crypto.deriveKeys("别的口令".toCharArray(), testKdf)
        assertFalse(a.encKey.contentEquals(other.encKey))
    }

    @Test
    fun kcvIsHmacPrefixOfEncKey() {
        val keys = crypto.deriveKeys("口令-测试".toCharArray(), testKdf)
        // 测试侧独立实现（javax.crypto.Mac）交叉验证 KCV = HMAC-SHA256(encKey, label)[0..16)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keys.encKey, "HmacSHA256"))
        val full = mac.doFinal("SimpleLedger/v1/check".toByteArray(Charsets.UTF_8))
        assertArrayEquals(full.copyOfRange(0, 16), keys.kcv)

        assertTrue(crypto.checkPassword(keys, keys.kcv))
        assertFalse(crypto.checkPassword(keys, ByteArray(16) { 1 }))
    }

    // ---------- AES-256-GCM 容器（§7 密文格式 v1） ----------

    @Test
    fun sealOpenRoundtripAllPurposes() {
        val keys = crypto.deriveKeys("口令-测试".toCharArray(), testKdf)
        val plaintext = "账目明文 amountCents=12345；重复重复重复重复重复（触发 gzip）".repeat(20)
        for (purpose in intArrayOf(
            CryptoFormat.PURPOSE_OPS,
            CryptoFormat.PURPOSE_PHOTO,
            CryptoFormat.PURPOSE_META,
        )) {
            val withKcv = purpose == CryptoFormat.PURPOSE_META
            val blob = crypto.seal(
                keys.encKey, purpose, plaintext.toByteArray(Charsets.UTF_8), testKdf,
                kcv = if (withKcv) keys.kcv else null,
            )
            val header = CryptoFormat.decodeHeader(blob)
            assertEquals(purpose, header.purpose)
            if (withKcv) {
                assertArrayEquals(keys.kcv, header.kcv)
            } else {
                assertEquals(null, header.kcv)
            }
            val opened = crypto.open(keys.encKey, blob)
            assertEquals(plaintext, String(opened, Charsets.UTF_8))
        }
    }

    @Test
    fun gzipIsAppliedInsideContainer() {
        val keys = crypto.deriveKeys("口令-测试".toCharArray(), testKdf)
        val highlyCompressible = ByteArray(64 * 1024) // 全零，gzip 后极小
        val blob = crypto.seal(keys.encKey, CryptoFormat.PURPOSE_OPS, highlyCompressible, testKdf)
        // 密文段远小于明文 ⇒ 明文确实在进 GCM 前被压缩（§7）
        assertTrue(blob.size - CryptoFormat.HEADER_SIZE < highlyCompressible.size / 4)
        assertArrayEquals(highlyCompressible, crypto.open(keys.encKey, blob))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val keys = crypto.deriveKeys("口令-测试".toCharArray(), testKdf)
        val blob = crypto.seal(keys.encKey, CryptoFormat.PURPOSE_OPS, "hello".toByteArray(), testKdf)

        val flippedCipher = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        expectTampered { crypto.open(keys.encKey, flippedCipher) }

        val flippedIv = blob.copyOf().also { it[30] = (it[30] + 1).toByte() } // IV 位于 28..39
        expectTampered { crypto.open(keys.encKey, flippedIv) }

        // 换口令 ⇒ 换密钥 ⇒ GCM 认证失败
        val wrongKeys = crypto.deriveKeys("错口令".toCharArray(), testKdf)
        expectTampered { crypto.open(wrongKeys.encKey, blob) }
    }

    @Test
    fun brokenHeaderIsRejectedAsBadFormat() {
        val keys = crypto.deriveKeys("口令-测试".toCharArray(), testKdf)
        val blob = crypto.seal(keys.encKey, CryptoFormat.PURPOSE_OPS, "hello".toByteArray(), testKdf)

        val wrongMagic = blob.copyOf().also { it[0] = 'X'.code.toByte() }
        expectBadFormat { crypto.open(keys.encKey, wrongMagic) }

        val wrongVersion = blob.copyOf().also { it[5] = 9 }
        expectBadFormat { crypto.open(keys.encKey, wrongVersion) }

        expectBadFormat { crypto.open(keys.encKey, blob.copyOfRange(0, 20)) }
    }

    private fun expectTampered(block: () -> Unit) {
        try {
            block()
            fail("应抛 SyncCryptoException.Tampered")
        } catch (e: SyncCryptoException.Tampered) {
            // 预期
        }
    }

    private fun expectBadFormat(block: () -> Unit) {
        try {
            block()
            fail("应抛 SyncCryptoException.BadFormat")
        } catch (e: SyncCryptoException.BadFormat) {
            // 预期
        }
    }

    private companion object {
        fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        fun hex(text: String): ByteArray {
            val clean = text.replace(" ", "")
            return ByteArray(clean.length / 2) {
                clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
