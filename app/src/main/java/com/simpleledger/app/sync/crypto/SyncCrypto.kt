package com.simpleledger.app.sync.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 口令派生参数（架构 §1.3 定案：m = 64 MiB、t = 2、p = 1、salt = 16B 随机）。
 *
 * 参数随密文头明文存储（§7），可调参不破兼容；同口令设备经相同参数派生同一把主密钥。
 */
data class KdfParams(
    val mKiB: Int = 65_536,
    val t: Int = 2,
    val p: Int = 1,
    val salt: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is KdfParams && other.mKiB == mKiB && other.t == t && other.p == p && other.salt.contentEquals(salt)

    override fun hashCode(): Int {
        var result = mKiB
        result = 31 * result + t
        result = 31 * result + p
        result = 31 * result + salt.contentHashCode()
        return result
    }
}

/**
 * 派生出的三把密钥（架构 §1.6 密钥派生链）：
 * - [encKey]：内容 AES-256-GCM 密钥（HKDF info = "SimpleLedger/v1/enc"）；
 * - [nameKey]：文件名 HMAC 假名密钥（info = "SimpleLedger/v1/name"）；
 * - [kcv]：口令校验值 = HMAC-SHA256(encKey, "SimpleLedger/v1/check")[0..16)。
 */
data class SyncKeys(
    val encKey: ByteArray,
    val nameKey: ByteArray,
    val kcv: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SyncKeys &&
            other.encKey.contentEquals(encKey) &&
            other.nameKey.contentEquals(nameKey) &&
            other.kcv.contentEquals(kcv)

    override fun hashCode(): Int {
        var result = encKey.contentHashCode()
        result = 31 * result + nameKey.contentHashCode()
        result = 31 * result + kcv.contentHashCode()
        return result
    }
}

/** 解密/解析失败的语义分类（`DavErrors` 映射到 `SyncError.CORRUPTED` / 报错面） */
sealed class SyncCryptoException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** 密文格式不符（magic / 版本 / 长度非法），对应 `SyncError.CORRUPTED` */
    class BadFormat(message: String) : SyncCryptoException(message)

    /** GCM 认证失败（密文/头被篡改或口令错误），对应 `SyncError.CORRUPTED` */
    class Tampered(message: String, cause: Throwable? = null) : SyncCryptoException(message, cause)
}

/**
 * 同步加密原语封装：Argon2id 口令派生 + HKDF-SHA256 子密钥 + AES-256-GCM 容器加解密。
 *
 * 实测依据（架构 V1/V4）：
 * - Argon2id 走 BouncyCastle `Argon2BytesGenerator` **纯 Java 直调**，
 *   ⚠️ 绝不 `Security.addProvider`——避开 Android 内置旧版 BC Provider 类冲突；
 * - AES-GCM 用系统 `javax.crypto`（API 19+ 稳定）；每次 seal 随机 12B IV，
 *   固定 IV 是密码学禁忌，禁止复用；
 * - 明文体先 gzip 再进 GCM（§7 密文格式 v1 的一部分，对 ops/photo/meta 一律生效），
 *   使 [open] 的输出即原始明文，任何调用方都不可能忘记解压。
 *
 * 口令口径：[deriveKeys] 接收 [CharArray]，内部统一按 **UTF-8 字节**喂给 Argon2id——
 * 保证不同设备/不同语言环境派生结果逐字节一致。
 */
class SyncCrypto(private val random: SecureRandom = SecureRandom()) {

    /**
     * Argon2id + HKDF 派生三把密钥（§1.6 派生链）。
     *
     * 与 RFC 9106 §5.3 测试向量共享同一个 [argon2id] 实现（向量含 secret/additional，
     * 生产路径两者为空——见 `SyncCryptoTest` 逐字节断言）。
     */
    fun deriveKeys(password: CharArray, params: KdfParams): SyncKeys {
        val master = argon2id(
            password = String(password).toByteArray(Charsets.UTF_8),
            salt = params.salt,
            secret = null,
            additional = null,
            mKiB = params.mKiB,
            t = params.t,
            p = params.p,
            outLen = 32,
        )
        val encKey = hkdfSha256(master, salt = null, info = INFO_ENC.toByteArray(Charsets.UTF_8), len = 32)
        val nameKey = hkdfSha256(master, salt = null, info = INFO_NAME.toByteArray(Charsets.UTF_8), len = 32)
        val kcv = hmacSha256(encKey, INFO_CHECK.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
        return SyncKeys(encKey = encKey, nameKey = nameKey, kcv = kcv)
    }

    /**
     * 加密为 §7 密文格式 v1 容器：56B 明文头 + gzip 明文的 AES-256-GCM 密文（tag 16B 附尾）。
     *
     * @param key     32B AES 密钥（[SyncKeys.encKey]）
     * @param purpose 用途域：[CryptoFormat.PURPOSE_OPS] / PURPOSE_PHOTO / PURPOSE_META
     * @param kdf     写入明文头的派生参数（解密方从头里取参数重派生口令校验）
     * @param kcv     仅 purpose = META 传非空（口令校验值写进头，供「口令不一致」判据）
     */
    fun seal(
        key: ByteArray,
        purpose: Int,
        plaintext: ByteArray,
        kdf: KdfParams,
        kcv: ByteArray? = null,
    ): ByteArray {
        require(key.size == 32) { "encKey 必须 32 字节" }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val header = CryptoFormat.encodeHeader(
            BlobHeader(purpose = purpose, version = CryptoFormat.FORMAT_VERSION, kdf = kdf, iv = iv, kcv = kcv)
        )
        val cipher = Cipher.getInstance(GCM_TRANSFORM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        val ciphertext = cipher.doFinal(gzip(plaintext))
        return header + ciphertext
    }

    /**
     * 解开 [seal] 产出的容器，返回原始明文（自动 gunzip）。
     *
     * @throws SyncCryptoException.BadFormat 头非法（magic/版本/长度）
     * @throws SyncCryptoException.Tampered  GCM 认证失败（篡改或密钥不符）
     */
    fun open(key: ByteArray, blob: ByteArray): ByteArray {
        require(key.size == 32) { "encKey 必须 32 字节" }
        if (blob.size < CryptoFormat.HEADER_SIZE + GCM_TAG_BYTES) {
            throw SyncCryptoException.BadFormat("密文长度 ${blob.size} 不足一个 GCM 容器")
        }
        val header = CryptoFormat.decodeHeader(blob) // 头非法 → BadFormat
        val cipher = Cipher.getInstance(GCM_TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, header.iv),
        )
        val compressed = try {
            cipher.doFinal(blob, CryptoFormat.HEADER_SIZE, blob.size - CryptoFormat.HEADER_SIZE)
        } catch (e: AEADBadTagException) {
            throw SyncCryptoException.Tampered("GCM 认证失败（密文被篡改或口令不一致）", e)
        } catch (e: GeneralSecurityException) {
            throw SyncCryptoException.Tampered("GCM 解密失败: ${e.message}", e)
        }
        return gunzip(compressed)
    }

    /** 「口令不一致」判据（R-04）：比对派生 KCV 与 meta 头里的 KCV（常数时间） */
    fun checkPassword(keys: SyncKeys, expectKcv: ByteArray): Boolean =
        MessageDigest.isEqual(keys.kcv, expectKcv)

    /**
     * Argon2id 裸调用（RFC 9106 口径，v = 0x13）。生产路径 [deriveKeys] 不带 secret/additional；
     * 保留两个参数是为了让 `SyncCryptoTest` 直接复现 **RFC 9106 §5.3 官方向量**（该向量带 K/X）。
     */
    internal fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        secret: ByteArray?,
        additional: ByteArray?,
        mKiB: Int,
        t: Int,
        p: Int,
        outLen: Int,
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(mKiB)
            .withIterations(t)
            .withParallelism(p)
            .withSalt(salt)
        if (secret != null) builder.withSecret(secret)
        if (additional != null) builder.withAdditional(additional)
        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        val out = ByteArray(outLen)
        generator.generateBytes(password, out)
        return out
    }

    /**
     * HKDF-SHA256（RFC 5869：extract-then-expand）。
     * [salt] 为 null 时按 RFC 以 HashLen 个 0x00 填充。金标见 `SyncCryptoTest`（A.1/A.3 向量）。
     */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, len: Int): ByteArray {
        // 空盐 / 缺省盐 → HashLen 个 0x00（RFC 5869 extract 口径）。
        // HMAC 的密钥零填充语义下「空密钥」与「32 字节零密钥」逐字节等价（A.3 金标钉死），
        // 这里显式换 32 零字节还绕开 JCE 对空密钥的 IllegalArgumentException。
        val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(effectiveSalt, ikm)
        val hashLen = 32
        require(len <= 255 * hashLen) { "HKDF 输出长度越界" }
        val result = ByteArray(len)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < len) {
            val out = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(hashLen, len - offset)
            System.arraycopy(out, 0, result, offset, n)
            t = out
            offset += n
            counter++
        }
        return result
    }

    private companion object {
        /** 派生链域分隔（§1.6）——三者互不派生自对方，改名即换域 */
        const val INFO_ENC = "SimpleLedger/v1/enc"
        const val INFO_NAME = "SimpleLedger/v1/name"
        const val INFO_CHECK = "SimpleLedger/v1/check"

        const val GCM_TRANSFORM = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = 16
        const val IV_BYTES = 12

        fun gzip(input: ByteArray): ByteArray =
            ByteArrayOutputStream().use { bos ->
                GZIPOutputStream(bos).use { it.write(input) }
                bos.toByteArray()
            }

        fun gunzip(input: ByteArray): ByteArray =
            GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
    }
}

/** HMAC-SHA256 裸函数（KCV、文件名假名、HKDF 共用；RFC 5869 口径） */
internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(message)
}
