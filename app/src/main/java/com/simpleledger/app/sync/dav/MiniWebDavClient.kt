package com.simpleledger.app.sync.dav

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 自研最小 WebDAV 客户端（架构 V2 选型：OkHttp 5，Sardine 系否决）。
 *
 * 覆盖需求面（R-01/R-03/R-23）：MKCOL（405/301 幂等视作已存在）、PROPFIND `Depth: 1`
 * （XmlPullParser 解析，不引 JAXB）、HEAD、GET（可 `Range` 续传）、PUT
 * （`If-Match` / `If-None-Match: *` 条件写）、DELETE。
 *
 * 出错口径：非 2xx 抛 [DavException.Http]、IO 抛 [DavException.Network]；
 * 「已存在 / 已删除」等幂等语义在方法内部消化，不冒泡。
 *
 * @param baseUrl WebDAV 根地址（自动补尾部斜杠，保证相对路径解析正确）
 * @param newParser PROPFIND 解析器工厂（生产默认 `android.util.Xml.newPullParser()`；
 *                  JVM 单测注入 kxml2 实现，避免依赖 android.jar 桩）
 */
class MiniWebDavClient(
    baseUrl: HttpUrl,
    private val username: String,
    private val appPassword: String,
    private val client: OkHttpClient = defaultClient(),
    private val newParser: () -> XmlPullParser = { Xml.newPullParser() },
) {

    private val baseUrl: HttpUrl = ensureTrailingSlash(baseUrl)

    /** PROPFIND Depth:1：列出目录自身 + 直接子资源（子资源含文件与子目录） */
    suspend fun propfindDepth1(dir: String): List<DavResource> {
        val request = baseRequest(resolve(dir))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_TYPE))
            .header("Depth", "1")
            .build()
        return execute(request) { response ->
            if (response.code != 207 && !response.isSuccessful) {
                throw DavException.Http(response.code, "PROPFIND", dir)
            }
            parseMultistatus(response.body.string())
        }
    }

    /** MKCOL 建目录：405/301 视为已存在（幂等，多设备首建竞争安全） */
    suspend fun mkcol(dir: String) {
        val request = baseRequest(resolve(dir)).method("MKCOL", null).build()
        execute(request) { response ->
            when {
                response.isSuccessful -> Unit
                response.code == 405 || response.code == 301 -> Unit // 已存在
                else -> throw DavException.Http(response.code, "MKCOL", dir)
            }
        }
    }

    /** HEAD 探存在（照片去重 R-16）：404 → null */
    suspend fun head(path: String): DavHead? {
        val request = baseRequest(resolve(path)).head().build()
        return execute(request) { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> DavHead(
                    etag = response.header("ETag"),
                    size = response.header("Content-Length")?.toLongOrNull(),
                )
                else -> throw DavException.Http(response.code, "HEAD", path)
            }
        }
    }

    /**
     * PUT 写入（条件写）：
     * - [ifMatch] 非空 → `If-Match: <etag>`（覆盖写防并发，失败 412 → [DavException.Http]）；
     * - [ifNoneMatchStar] → `If-None-Match: *`（首写防重，撞已存在 412 → [DavException.Http]）。
     */
    suspend fun put(
        path: String,
        body: RequestBody,
        ifMatch: String? = null,
        ifNoneMatchStar: Boolean = false,
    ): PutResult {
        val builder = baseRequest(resolve(path)).put(body)
        if (ifMatch != null) builder.header("If-Match", ifMatch)
        if (ifNoneMatchStar) builder.header("If-None-Match", "*")
        return execute(builder.build()) { response ->
            if (!response.isSuccessful) {
                throw DavException.Http(response.code, "PUT", path)
            }
            PutResult(etag = response.header("ETag"), size = body.contentLength())
        }
    }

    /**
     * GET 下载（[rangeStart] 非空 → `Range: bytes=<n>-` 或 `bytes=<n>-<m>` 断点续传/头部探测）。
     * 返回的 [Response] 由调用方负责 close（流式读取大照片）。
     */
    suspend fun get(path: String, rangeStart: Long? = null, rangeEnd: Long? = null): Response {
        val builder = baseRequest(resolve(path)).get()
        if (rangeStart != null) {
            val range = if (rangeEnd != null) "bytes=$rangeStart-$rangeEnd" else "bytes=$rangeStart-"
            builder.header("Range", range)
        }
        val request = builder.build()
        val response = withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw DavException.Network(e)
            }
        }
        if (!response.isSuccessful && response.code != 206) {
            val code = response.code
            response.close()
            throw DavException.Http(code, "GET", path)
        }
        return response
    }

    /** DELETE：404 视为已删除（幂等） */
    suspend fun delete(path: String) {
        val request = baseRequest(resolve(path)).delete().build()
        execute(request) { response ->
            when {
                response.isSuccessful || response.code == 204 || response.code == 404 -> Unit
                else -> throw DavException.Http(response.code, "DELETE", path)
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun resolve(rel: String): HttpUrl =
        baseUrl.resolve(rel.trimStart('/')) ?: error("无法解析 WebDAV 路径: $rel")

    private fun baseRequest(url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", basicCredentials(username, appPassword))
            .header("User-Agent", USER_AGENT)

    private suspend fun <T> execute(request: Request, interpret: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw DavException.Network(e)
            }
            response.use { interpret(it) }
        }

    /** 207 multistatus 解析（namespace 无关：剥前缀按 local name 匹配） */
    private fun parseMultistatus(xml: String): List<DavResource> {
        val parser = newParser()
        parser.setInput(StringReader(xml))
        val resources = mutableListOf<DavResource>()
        var inResponse = false
        var href: String? = null
        var etag: String? = null
        var size: Long? = null
        var isDir = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "response" -> {
                        inResponse = true
                        href = null
                        etag = null
                        size = null
                        isDir = false
                    }
                    "href" -> if (inResponse) href = parser.nextText()
                    "getetag" -> if (inResponse) etag = parser.nextText()
                    "getcontentlength" -> if (inResponse) size = parser.nextText().trim().toLongOrNull()
                    "collection" -> if (inResponse) isDir = true
                }
                event == XmlPullParser.END_TAG && localName(parser.name) == "response" -> {
                    href?.let { resources += DavResource(it, isDir, etag, size) }
                    inResponse = false
                }
            }
            event = parser.next()
        }
        return resources
    }

    private fun localName(qName: String): String = qName.substringAfter(':')

    companion object {
        private const val USER_AGENT = "SimpleLedger-Sync/1"

        private val XML_TYPE = "application/xml; charset=utf-8".toMediaType()

        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>
               <D:propfind xmlns:D="DAV:">
                 <D:prop>
                   <D:resourcetype/>
                   <D:getetag/>
                   <D:getcontentlength/>
                 </D:prop>
               </D:propfind>"""

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        fun basicCredentials(username: String, appPassword: String): String {
            val token = java.util.Base64.getEncoder()
                .encodeToString("$username:$appPassword".toByteArray(Charsets.UTF_8))
            return "Basic $token"
        }

        private fun ensureTrailingSlash(url: HttpUrl): HttpUrl =
            if (url.encodedPath.endsWith("/")) url
            else url.newBuilder().encodedPath(url.encodedPath + "/").build()
    }
}

/** PROPFIND 子资源（href 为服务端原文；文件名取 [fileName]） */
data class DavResource(
    val href: String,
    val isDir: Boolean,
    val etag: String?,
    val size: Long?,
)

/** HEAD 结果 */
data class DavHead(val etag: String?, val size: Long?)

/** PUT 结果（etag 供后续 If-Match 覆盖写） */
data class PutResult(val etag: String?, val size: Long)

/** 从 href 提取文件名（百分号解码，取末段）——云端名本身是假名/随机名，无可读信息 */
fun DavResource.fileName(): String =
    href.substringBefore('?').substringAfterLast('/').percentDecode()

private fun String.percentDecode(): String =
    URLDecoder.decode(this.replace("+", "%2B"), Charsets.UTF_8.name())
