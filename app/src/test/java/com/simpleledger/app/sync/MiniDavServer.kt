package com.simpleledger.app.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 最小 WebDAV 测试服务（仅测试用，JDK HttpServer 承载）：
 * MKCOL / PROPFIND Depth:1 / PUT（If-Match / If-None-Match: *）/ HEAD / GET（Range）/ DELETE + ETag。
 *
 * MiniWebDavClientTest（T-2 传输底座）与 SyncEngineIntegrationTest / PhotoTransferTest /
 * SyncManagerTest（T-4 全链）共用。两个测试专用观测面：
 * - [requestLog]：每个请求记一行 `"METHOD /path [range] -> code"`——
 *   R-04「下载任何数据前失败」、R-16「二次传输零流量」、R-05「resetSync 删云端」等断言用；
 * - [requireAuth]：非空时校验 `Authorization` 头，不匹配回 401——AUTH 归类断言用。
 */
internal class MiniDavServer {

    val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    val etags = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val dirs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var etagSeq = 0
    private lateinit var server: HttpServer

    /** 请求观测账（线程安全；格式 `"METHOD /path [range] -> code"`） */
    val requestLog: MutableList<String> = CopyOnWriteArrayList()

    /** 非空 = 要求 `Authorization` 头逐字匹配该值，否则 401（AUTH 归类测试用） */
    @Volatile
    var requireAuth: String? = null

    /** true = GET 一律 500（瞬态网络错注入：HEAD 正常、下载必败，正好命中照片下载计数路径） */
    @Volatile
    var failGets: Boolean = false

    /** true = Range 请求只回一半（Content-Range 如实报实发区间与全长）——
     *  模拟服务端收紧 Range：客户端干净读完 206 但未收满，走断点暂停路径 */
    @Volatile
    var truncateGets: Boolean = false

    val baseUrl: String
        get() = "http://127.0.0.1:${(server.address as InetSocketAddress).port}/dav/"

    /** 当前端口号（配合 [stop] 制造「死端口」网络错误场景） */
    val port: Int
        get() = (server.address as InetSocketAddress).port

    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav") { exchange -> handle(exchange) }
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    /** 断言便捷：清空请求观测账 */
    fun clearLog() {
        requestLog.clear()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val expected = requireAuth
            if (expected != null && exchange.requestHeaders.getFirst("Authorization") != expected) {
                respond(exchange, 401, -1)
                return
            }
            val rel = exchange.requestURI.path.removePrefix("/dav/").removePrefix("/dav")
            when (exchange.requestMethod.uppercase()) {
                "MKCOL" -> mkcol(exchange, rel)
                "PROPFIND" -> propfind(exchange, rel)
                "PUT" -> put(exchange, rel)
                "HEAD" -> head(exchange, rel)
                "GET" -> if (failGets) respond(exchange, 500, -1) else get(exchange, rel)
                "DELETE" -> delete(exchange, rel)
                else -> respond(exchange, 405, -1)
            }
        } catch (t: Throwable) {
            runCatching { respond(exchange, 500, -1) }
        } finally {
            exchange.close()
        }
    }

    private fun mkcol(exchange: HttpExchange, rel: String) {
        val key = rel.removeSuffix("/")
        if (key.isEmpty() || dirs.contains(key) || dirs.any { it.startsWith("$key/") }) {
            respond(exchange, 405, -1) // 已存在 → 客户端幂等消化
        } else {
            dirs.add(key)
            respond(exchange, 201, -1)
        }
    }

    private fun propfind(exchange: HttpExchange, rel: String) {
        val dir = rel.removeSuffix("/")
        // 真实化（T-5 走查教训）：不存在的 collection 必须 404（RFC 4918），与坚果云/
        // 真实 WebDAV 一致。此前无条件 207 掩盖了「全新账本首次开启同步」的 404 处理缺口。
        // 存在 = 根（空串）/ MKCOL 建过 / 有子项（文件或子目录隐含父目录）。
        val exists = dir.isEmpty() ||
            dirs.contains(dir) ||
            files.keys.any { it.startsWith("$dir/") } ||
            dirs.any { it.startsWith("$dir/") }
        if (!exists) {
            respond(exchange, 404, -1)
            return
        }
        exchange.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
        val children = files.keys.filter { it.startsWith("$dir/") && !it.substringAfter("$dir/").contains('/') }
        val xml = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<D:multistatus xmlns:D="DAV:">""")
            append("""<D:response><D:href>/dav/$dir/</D:href><D:propstat><D:prop>""")
            append("""<D:resourcetype><D:collection/></D:resourcetype>""")
            append("""</D:prop></D:propstat></D:response>""")
            for (name in children.sorted()) {
                append("""<D:response><D:href>/dav/$name</D:href><D:propstat><D:prop>""")
                append("""<D:resourcetype/>""")
                append("""<D:getetag>${etags[name] ?: ""}</D:getetag>""")
                append("""<D:getcontentlength>${files[name]?.size ?: 0}</D:getcontentlength>""")
                append("""</D:prop></D:propstat></D:response>""")
            }
            append("</D:multistatus>")
        }.toByteArray()
        exchange.responseHeaders.add("ETag", "\"dir\"")
        respond(exchange, 207, xml.size, xml)
    }

    private fun put(exchange: HttpExchange, rel: String) {
        val body = exchange.requestBody.readBytes()
        val existingEtag = etags[rel]
        val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
        val ifMatch = exchange.requestHeaders.getFirst("If-Match")
        if (ifNoneMatch == "*" && existingEtag != null) {
            respond(exchange, 412, -1)
            return
        }
        if (ifMatch != null && ifMatch != existingEtag) {
            respond(exchange, 412, -1)
            return
        }
        val etag = "\"e${++etagSeq}\""
        files[rel] = body
        etags[rel] = etag
        exchange.responseHeaders.add("ETag", etag)
        respond(exchange, 201, -1)
    }

    private fun head(exchange: HttpExchange, rel: String) {
        val body = files[rel]
        if (body == null) {
            respond(exchange, 404, -1)
            return
        }
        exchange.responseHeaders.add("ETag", etags[rel] ?: "")
        exchange.responseHeaders.add("Content-Length", body.size.toString())
        respond(exchange, 200, -1)
    }

    private fun get(exchange: HttpExchange, rel: String) {
        val body = files[rel]
        if (body == null) {
            respond(exchange, 404, -1)
            return
        }
        val range = exchange.requestHeaders.getFirst("Range")
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=")
            val start = spec.substringBefore('-').toLongOrNull() ?: 0L
            val endText = spec.substringAfter('-', "")
            val end = if (endText.isEmpty()) body.size - 1L else endText.toLong().coerceAtMost(body.size - 1L)
            if (start >= body.size) {
                respond(exchange, 416, -1)
                return
            }
            var serveEnd = end
            if (truncateGets) {
                // 只回一半，但 Content-Range 如实报实发区间与全长——干净的 206，
                // 客户端读到「合法但未收满」的响应（现实中服务端收紧 Range 的形态）
                serveEnd = ((start + body.size) / 2 - 1).coerceAtLeast(start)
            }
            val slice = body.copyOfRange(start.toInt(), (serveEnd + 1).toInt())
            exchange.responseHeaders.add("ETag", etags[rel] ?: "")
            exchange.responseHeaders.add("Content-Range", "bytes $start-$serveEnd/${body.size}")
            respond(exchange, 206, slice.size, slice)
            return
        }
        exchange.responseHeaders.add("ETag", etags[rel] ?: "")
        respond(exchange, 200, body.size, body)
    }

    private fun delete(exchange: HttpExchange, rel: String) {
        if (files.remove(rel) == null) {
            respond(exchange, 404, -1) // 客户端幂等消化
        } else {
            etags.remove(rel)
            respond(exchange, 204, -1)
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, length: Int, body: ByteArray? = null) {
        requestLog += "${exchange.requestMethod} ${exchange.requestURI.path} " +
            "[${exchange.requestHeaders.getFirst("Range") ?: ""}] -> $code"
        if (length >= 0 && body != null) {
            exchange.sendResponseHeaders(code, length.toLong())
            exchange.responseBody.use { it.write(body) }
        } else {
            exchange.sendResponseHeaders(code, -1)
        }
    }
}
