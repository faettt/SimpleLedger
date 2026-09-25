package com.simpleledger.app.sync.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * §7 密文格式 v1 的 56B 明文头编解码（一切云端 blob 通用）。
 *
 * ```
 * 偏移   长度   内容
 * 0      4     MAGIC "SLC1"（明文）
 * 4      1     purpose（0=ops, 1=photo, 2=meta）
 * 5      1     formatVersion = 1
 * 6      4     kdf.mKiB (u32 BE)
 * 10     1     kdf.t
 * 11     1     kdf.p
 * 12     16    kdf.salt
 * 28     12    GCM IV（随机）
 * 40     16    kcv（仅 purpose=meta 非空，其余全 0）
 * 56     n     AES-256-GCM 密文（明文体先 gzip；tag 16B 附密文尾）
 * ```
 *
 * 头本身**明文**存储：盐 / 派生参数 / KCV 无泄露问题（PRD 认可），解密方靠它取参数
 * 校验口令与重派生密钥；版本不符即 `SyncCryptoException.BadFormat` → `SyncError.CORRUPTED`。
 */
object CryptoFormat {

    /** 容器魔数（4B，ASCII "SLC1"） */
    const val MAGIC = "SLC1"

    /** 密文格式版本；破坏性变更必须递增并保留旧解码路径 */
    const val FORMAT_VERSION = 1

    /** purpose：操作分片 */
    const val PURPOSE_OPS = 0

    /** purpose：照片 */
    const val PURPOSE_PHOTO = 1

    /** purpose：元文件（唯一携带 KCV 的用途域） */
    const val PURPOSE_META = 2

    /** 明文头固定长度 */
    const val HEADER_SIZE = 56

    /** GCM 认证标签长度（附密文尾） */
    const val GCM_TAG_BYTES = 16

    private const val SALT_OFFSET = 12
    private const val SALT_LEN = 16
    private const val IV_OFFSET = 28
    private const val IV_LEN = 12
    private const val KCV_OFFSET = 40
    private const val KCV_LEN = 16

    /** 编码 56B 头（大端；kcv 为 null 时写全 0） */
    fun encodeHeader(h: BlobHeader): ByteArray {
        require(h.kdf.salt.size == SALT_LEN) { "KDF 盐必须 $SALT_LEN 字节" }
        require(h.iv.size == IV_LEN) { "GCM IV 必须 $IV_LEN 字节" }
        require(h.kcv == null || h.kcv.size == KCV_LEN) { "KCV 必须 $KCV_LEN 字节" }
        val bytes = ByteArray(HEADER_SIZE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buffer.put(h.purpose.toByte())
        buffer.put(h.version.toByte())
        buffer.putInt(h.kdf.mKiB)
        buffer.put(h.kdf.t.toByte())
        buffer.put(h.kdf.p.toByte())
        buffer.put(h.kdf.salt, 0, SALT_LEN)
        buffer.put(h.iv, 0, IV_LEN)
        if (h.kcv != null) {
            buffer.put(h.kcv, 0, KCV_LEN)
        }
        return bytes
    }

    /**
     * 解码头部。
     *
     * @throws SyncCryptoException.BadFormat 长度不足 / magic 不符 / 版本不符 / 参数越界
     */
    fun decodeHeader(bytes: ByteArray): BlobHeader {
        if (bytes.size < HEADER_SIZE) {
            throw SyncCryptoException.BadFormat("头部长度不足：${bytes.size} < $HEADER_SIZE")
        }
        if (!bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII).equals(MAGIC)) {
            throw SyncCryptoException.BadFormat("MAGIC 不符，不是本格式密文")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val purpose = buffer.get().toInt() and 0xFF
        val version = buffer.get().toInt() and 0xFF
        if (version != FORMAT_VERSION) {
            throw SyncCryptoException.BadFormat("密文格式版本不符：$version ≠ $FORMAT_VERSION")
        }
        val mKiB = buffer.int
        val t = buffer.get().toInt() and 0xFF
        val p = buffer.get().toInt() and 0xFF
        if (mKiB <= 0 || t <= 0 || p <= 0) {
            throw SyncCryptoException.BadFormat("KDF 参数越界：m=$mKiB t=$t p=$p")
        }
        val salt = ByteArray(SALT_LEN).also { buffer.get(it) }
        val iv = ByteArray(IV_LEN).also { buffer.get(it) }
        val kcvRaw = ByteArray(KCV_LEN).also { buffer.get(it) }
        val kcv = if (kcvRaw.all { it == 0.toByte() }) null else kcvRaw
        return BlobHeader(
            purpose = purpose,
            version = version,
            kdf = KdfParams(mKiB = mKiB, t = t, p = p, salt = salt),
            iv = iv,
            kcv = kcv,
        )
    }
}

/**
 * 明文头字段（[CryptoFormat] 的编解码载体）。
 *
 * @param purpose 用途域：PURPOSE_OPS / PURPOSE_PHOTO / PURPOSE_META
 * @param kcv     仅 meta 非空（口令校验值）；其余用途恒为 null
 */
data class BlobHeader(
    val purpose: Int,
    val version: Int,
    val kdf: KdfParams,
    val iv: ByteArray,
    val kcv: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is BlobHeader &&
            other.purpose == purpose &&
            other.version == version &&
            other.kdf == kdf &&
            other.iv.contentEquals(iv) &&
            (other.kcv == null && kcv == null || other.kcv != null && kcv != null && other.kcv.contentEquals(kcv))

    override fun hashCode(): Int {
        var result = purpose
        result = 31 * result + version
        result = 31 * result + kdf.hashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + (kcv?.contentHashCode() ?: 0)
        return result
    }
}
