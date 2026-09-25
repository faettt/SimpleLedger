package com.simpleledger.app.sync.crypto

import java.security.SecureRandom

/**
 * 云端文件命名（§7-6，S4「云端永不出现任何明文名」）：
 * - 照片 = 确定性假名 `nym("photo:" + sha256hex)`——跨设备同照片同名，天然内容寻址去重（R-16）；
 * - meta = 确定性假名 `nym("meta")`——各设备算出同一入口；
 * - 操作分片 = 随机 128bit hex + ".op"——每设备只写自己的分片，零写冲突，无需确定性。
 *
 * 假名 = `base32lower(HMAC-SHA256(nameKey, "v1:" + canonical))` 截 26 字符：
 * PRF 性质保证同输入稳定同名、异输入不可关联、不可逆；nameKey 与 encKey 经 HKDF
 * 分域派生（[SyncCrypto.deriveKeys]），泄其一不影响另一。
 */
object FilenameNym {

    private const val PREFIX = "v1:"
    private const val NYM_LEN = 26
    private val random = SecureRandom()

    /** 照片文件假名（64hex 小写内容哈希入参） */
    fun photoName(nameKey: ByteArray, contentHashHex: String): String =
        nym(nameKey, "photo:$contentHashHex")

    /** 元文件假名（全库唯一入口） */
    fun metaName(nameKey: ByteArray): String = nym(nameKey, "meta")

    /** 操作分片随机名：32 字符小写 hex + ".op" */
    fun chunkName(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) } + ".op"
    }

    /** 确定性假名核心：HMAC 前缀域分隔，截 26 字符小写 base32（130 bit 空间） */
    internal fun nym(nameKey: ByteArray, canonical: String): String {
        val mac = hmacSha256(nameKey, (PREFIX + canonical).toByteArray(Charsets.UTF_8))
        return base32Lower(mac).take(NYM_LEN)
    }

    /** RFC 4648 Base32（无填充），输出统一小写 */
    private fun base32Lower(input: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val out = StringBuilder((input.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in input) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) {
            out.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
        }
        return out.toString()
    }
}
