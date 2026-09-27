package com.simpleledger.app.sync.dav

import com.simpleledger.app.sync.crypto.BlobHeader
import com.simpleledger.app.sync.crypto.CryptoFormat
import com.simpleledger.app.sync.crypto.FilenameNym
import com.simpleledger.app.sync.crypto.KdfParams
import com.simpleledger.app.sync.crypto.SyncCrypto
import com.simpleledger.app.sync.crypto.SyncCryptoException
import com.simpleledger.app.sync.crypto.SyncKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 云端布局读写（§1.7）：
 *
 * ```
 * <dav-root>/simpleledger-v1/
 *     <nym(meta)>            # 元文件：明文头(KDF 参数/salt/KCV) + 密文体(bookId 等)
 *     <random>.op …          # 操作分片（每设备自写、追加式、If-None-Match: *）
 *     <nym(photo:hash)> …    # 照片（内容寻址，If-None-Match: * 防重复）
 * ```
 *
 * 全部内容经 [SyncCrypto.seal] 加密（密文格式 v1，明文内部 gzip），文件名为 HMAC 假名
 * 或随机名（S4：云端永不出现明文名）。
 *
 * 与 §3.7 签名的差异（缺口补齐，调用形态不变）：
 * - 增补 [kdf]：seal 需要把派生参数写进密文头（§7），仅持 [SyncKeys] 无法构造容器；
 * - 增补 [workDir]：照片断点续传（R-23）需要落盘半成品的目录（App 侧传 `cacheDir/photo_partial`）。
 */
class WebDavRemote(
    private val dav: MiniWebDavClient,
    private val keys: SyncKeys,
    private val kdf: KdfParams,
    private val workDir: File,
    private val crypto: SyncCrypto = SyncCrypto(),
) {

    /** 建协议目录（幂等，多设备首建竞争安全） */
    suspend fun ensureLayout() {
        dav.mkcol("$DIR/")
    }

    /**
     * 无口令发现元文件明文头（首次接入，`sequence-diagram-setup` 步骤 32–38）。
     *
     * 发现**不依赖口令**：`nym(meta)` 要 nameKey，nameKey 要云端 salt——先有鸡蛋问题。
     * 路径：① 先试本机 nameKey 算出的 `nym(meta)`（已建连设备直接命中）；② 兜底探测：
     * 列目录后对非 `.op` 候选取 56B 头（`Range: bytes=0-55`），认 `purpose = META` 的那个。
     * 头明文无泄露问题（§7）；「口令不一致」由调用方拿头里 KCV 与派生结果比对
     * （`SyncCrypto.checkPassword`）——在下载任何数据前失败（R-04）。
     */
    suspend fun peekMetaHeader(): BlobHeader? {
        val blob = fetchMetaBlob() ?: return null
        return try {
            CryptoFormat.decodeHeader(blob)
        } catch (e: SyncCryptoException) {
            throw DavException.Corrupted("meta 头解析失败: ${e.message}", e)
        }
    }

    /**
     * 读元文件；不存在返回 null。
     *
     * 头里 KCV 与本机派生不符 → [DavException.BadPassword]（「口令不一致」，
     * R-04 要求在下载任何数据前失败）。
     */
    suspend fun readMeta(): MetaBody? {
        val blob = fetchMetaBlob() ?: return null
        val header = try {
            CryptoFormat.decodeHeader(blob)
        } catch (e: SyncCryptoException) {
            throw DavException.Corrupted("meta 头解析失败: ${e.message}", e)
        }
        val expectKcv = header.kcv ?: throw DavException.Corrupted("meta 头缺少 KCV")
        if (!crypto.checkPassword(keys, expectKcv)) {
            throw DavException.BadPassword()
        }
        val plain = try {
            crypto.open(keys.encKey, blob)
        } catch (e: SyncCryptoException) {
            throw DavException.Corrupted("meta 解密失败: ${e.message}", e)
        }
        return MetaBody.fromJson(String(plain, Charsets.UTF_8))
    }

    /**
     * 首写元文件（`If-None-Match: *`）。与他机竞争失败（412）时改走「有 meta」路径：
     * 重读 meta 完成 KCV 校验（口令不符自然抛 [DavException.BadPassword]）。
     *
     * 条件写降级（v1.4.2）：不少 WebDAV 实现不支持 `If-None-Match` 条件头，如实回
     * 400/405/409/501（quirk 矩阵实测 reject400/501 复现「同步出错」）。降级策略：
     * 回读已有 meta（他机赢了）→ KCV 校验收敛；确无 meta → 去条件普通 PUT 重试一次。
     * 竞态注记：两机同刻首开且服务器不支持条件写时可能双写 meta（后写覆盖），
     * 窗口仅限首次接入同刻并发，可接受（条件写正常的服务器不受影响）。
     */
    suspend fun writeMetaOnce(body: MetaBody) {
        val name = FilenameNym.metaName(keys.nameKey)
        val blob = seal(CryptoFormat.PURPOSE_META, body.toJson().toByteArray(Charsets.UTF_8), withKcv = true)
        try {
            dav.put("$DIR/$name", blob.toRequestBody(BINARY_TYPE), ifNoneMatchStar = true)
        } catch (e: DavException.Http) {
            when {
                e.code == 412 ->
                    // 首写竞争：他机已写入。校验口令一致即视为成功。
                    readMeta() ?: throw e
                e.code == 400 || e.code == 405 || e.code == 409 || e.code == 501 -> {
                    if (readMeta() != null) return // 他机已建账：KCV 校验通过即收敛
                    dav.put("$DIR/$name", blob.toRequestBody(BINARY_TYPE), ifNoneMatchStar = false)
                }
                else -> throw e
            }
        }
    }

    /** 根下全部文件（分片 + 照片；目录自身与子目录已滤掉） */
    suspend fun listRemote(): List<DavResource> =
        listHomeOrEmpty().filter { !it.isDir }

    /** 打包上传操作分片：gzip+seal 后 `If-None-Match: *` PUT，返回随机假名 */
    suspend fun uploadChunk(opsPlain: ByteArray): String {
        val name = FilenameNym.chunkName()
        val blob = seal(CryptoFormat.PURPOSE_OPS, opsPlain, withKcv = false)
        dav.put("$DIR/$name", blob.toRequestBody(BINARY_TYPE), ifNoneMatchStar = true)
        return name
    }

    /** 下载并解开操作分片，返回明文操作 JSON */
    suspend fun downloadChunk(remoteName: String): ByteArray {
        val blob = dav.get("$DIR/$remoteName").use { it.body.bytes() }
        return open(blob)
    }

    /** 照片是否已存在云端（HEAD 确定性假名，R-16 传过不重传） */
    suspend fun photoHas(contentHashHex: String): Boolean =
        dav.head("$DIR/${FilenameNym.photoName(keys.nameKey, contentHashHex)}") != null

    /**
     * 照片云端假名（PhotoTransfer 台账键用；与 [photoHas]/[uploadPhoto]/[downloadPhoto] 同源）。
     */
    fun photoRemoteName(contentHashHex: String): String =
        FilenameNym.photoName(keys.nameKey, contentHashHex)

    /**
     * R-23 断点续传偏移：本地半成品密文（`workDir/<假名>.part`）已收长度；无半成品 = 0。
     */
    fun photoPartialLength(contentHashHex: String): Long {
        val part = File(workDir, "${photoRemoteName(contentHashHex)}.part")
        return if (part.exists()) part.length() else 0L
    }

    /**
     * 上传照片（`If-None-Match: *`）。撞已存在（412）视为成功——内容寻址去重，
     * 同 hash 云端只留一份（R-16）。[onProgress] 报告已写出字节数。
     */
    suspend fun uploadPhoto(contentHashHex: String, bytes: ByteArray, onProgress: (Long) -> Unit) {
        val name = FilenameNym.photoName(keys.nameKey, contentHashHex)
        val blob = seal(CryptoFormat.PURPOSE_PHOTO, bytes, withKcv = false)
        val body = CountingRequestBody(blob, onProgress)
        try {
            dav.put("$DIR/$name", body, ifNoneMatchStar = true)
        } catch (e: DavException.Http) {
            if (e.code != 412) throw e // 已存在 = 去重命中，成功
        }
    }

    /**
     * 下载照片（支持断点续传，R-23）。
     *
     * 半成品密文落 `workDir/<假名>.part`：[resumeFrom] 与半成品长度一致时从该偏移续传；
     * 服务端不支持 Range（返回 200）则自动重头整传，不产生重复云端文件（内容寻址）。
     * 收满即解密返回明文字节并清理半成品；**未收满返回 null**（半成品保留，下轮从断点续）。
     */
    suspend fun downloadPhoto(
        contentHashHex: String,
        resumeFrom: Long,
        onProgress: (Long) -> Unit,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val name = FilenameNym.photoName(keys.nameKey, contentHashHex)
        val part = File(workDir, "$name.part")
        workDir.mkdirs()
        val skip = if (part.exists() && part.length() == resumeFrom) resumeFrom else 0L
        if (skip == 0L && part.exists()) part.delete()
        val response = try {
            dav.get("$DIR/$name", rangeStart = skip.takeIf { it > 0 })
        } catch (e: DavException.Http) {
            if (e.code == 404) return@withContext null else throw e
        }
        response.use { r ->
            // 206 = 允许续传追加；200 = 服务端整传（半成品作废重来）
            val append = r.code == 206 && skip > 0
            if (!append && part.exists()) part.delete()
            val totalFromHeader: Long? = if (r.code == 206) {
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            } else {
                r.header("Content-Length")?.toLongOrNull()?.let { skip + it }
            }
            var received = skip
            val sink = FileOutputStream(part, append) // append=true 续传追加，false 重写
            sink.use { out ->
                val input = r.body.byteStream()
                val buffer = ByteArray(DOWNLOAD_CHUNK)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    received += n
                    onProgress(received)
                }
            }
            val complete = totalFromHeader == null || received >= totalFromHeader
            if (!complete) return@withContext null // 断点：保留 .part，下轮续传
            val blob = part.readBytes()
            part.delete()
            return@withContext open(blob)
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 列家目录；**家目录尚不存在（PROPFIND 404）→ 空表**。
     * RFC 4918 对不存在的 collection 的 PROPFIND 如实返回 404（坚果云同）——
     * 全新账本首次开启同步时家目录还没建，语义是「家里无账」而非错误
     * （T-5 走查发现：原先 404 裸抛，setupAccount / PULL 在真实 WebDAV 上必失败）。
     */
    private suspend fun listHomeOrEmpty(): List<DavResource> = try {
        dav.propfindDepth1("$DIR/")
    } catch (e: DavException.Http) {
        if (e.code == 404) emptyList()
        else throw e
    }

    /**
     * meta 密文获取：先按本机假名直取，缺失走 56B 头探测（口令不一致也能发现「家里已有账」）。
     *
     * 缺失容错 404/409/400 三档（v1.4.2）：RFC 4918 缺文件应 404，但真实服务器分叉大——
     * 409 Conflict（父目录不存在时 GET，坚果云类常见）、400 Bad Request（部分实现对
     * 不存在路径的 GET 如此回）语义都是「没有这个文件」。误判安全性：即便云端其实有 meta，
     * 下游 writeMetaOnce 的 `If-None-Match:*` 会 412 → 内部重读校验口令，一致性收敛（§4.2）。
     */
    private suspend fun fetchMetaBlob(): ByteArray? {
        val name = FilenameNym.metaName(keys.nameKey)
        try {
            return dav.get("$DIR/$name").use { it.body.bytes() }
        } catch (e: DavException.Http) {
            if (e.code != 404 && e.code != 409 && e.code != 400) throw e
        }
        // 兜底 56B 头探测。家目录尚不存在（= 全新账本，「家里无账」）时 [listHomeOrEmpty]
        // 回空 → null，交 setupAccount 走本机建账分支
        for (resource in listHomeOrEmpty()) {
            if (resource.isDir) continue
            val fileName = resource.fileName()
            if (fileName.endsWith(".op") || fileName == name) continue
            val head = try {
                dav.get("$DIR/$fileName", rangeStart = 0, rangeEnd = HEADER_PROBE_LAST)
                    .use { it.body.bytes() }
            } catch (e: DavException.Http) {
                continue
            }
            if (head.size < CryptoFormat.HEADER_SIZE) continue
            val header = try {
                CryptoFormat.decodeHeader(head)
            } catch (e: SyncCryptoException) {
                continue
            }
            if (header.purpose == CryptoFormat.PURPOSE_META) {
                return dav.get("$DIR/$fileName").use { it.body.bytes() }
            }
        }
        return null
    }

    private fun seal(purpose: Int, plaintext: ByteArray, withKcv: Boolean): ByteArray =
        crypto.seal(
            key = keys.encKey,
            purpose = purpose,
            plaintext = plaintext,
            kdf = kdf,
            kcv = if (withKcv) keys.kcv else null,
        )

    private fun open(blob: ByteArray): ByteArray = try {
        crypto.open(keys.encKey, blob)
    } catch (e: SyncCryptoException) {
        throw DavException.Corrupted("云端密文解析失败: ${e.message}", e)
    }

    /** 边写边报进度的请求体（[onProgress] 每块回调累计字节数） */
    private class CountingRequestBody(
        private val bytes: ByteArray,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = BINARY_TYPE
        override fun contentLength(): Long = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            while (sent < bytes.size) {
                val count = minOf(PROGRESS_STEP, bytes.size - sent.toInt())
                sink.write(bytes, sent.toInt(), count)
                sent += count
                onProgress(sent)
            }
        }
    }

    companion object {
        /** 协议版本目录（破坏性升级换目录并存，§7-5） */
        const val DIR = "simpleledger-v1"

        private val BINARY_TYPE = "application/octet-stream".toMediaType()
        private const val PROGRESS_STEP = 64 * 1024
        private const val DOWNLOAD_CHUNK = 64 * 1024
        private const val HEADER_PROBE_LAST = CryptoFormat.HEADER_SIZE.toLong() - 1
    }
}

/**
 * meta 明文体（§7：`{bookId, createdAt}`）。
 * bookId 首次接入时生成，用于识别「同一本账」；不含任何用户数据。
 */
data class MetaBody(val bookId: String, val createdAt: Long) {

    fun toJson(): String = JSONObject()
        .put("bookId", bookId)
        .put("createdAt", createdAt)
        .toString()

    companion object {
        fun fromJson(text: String): MetaBody {
            val json = JSONObject(text)
            return MetaBody(
                bookId = json.getString("bookId"),
                createdAt = json.getLong("createdAt"),
            )
        }
    }
}
