package com.simpleledger.app.sync.dav

import com.simpleledger.app.sync.crypto.SyncCryptoException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * WebDAV / 解密层抛出的语义化异常。
 *
 * 与 UI 解耦：数据层只抛/归类，**不写死文案**（§7-7）；T-4 的 `SyncError` 枚举名
 * 由 [DavErrors.toSyncErrorName] 给出，文案全进 `strings.xml`。
 */
sealed class DavException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** HTTP 非 2xx（405/301 等幂等语义在客户端内部消化，不会抛出） */
    class Http(val code: Int, val method: String, val path: String) :
        DavException("WebDAV $method $path → HTTP $code")

    /** 网络层失败（DNS/连接/超时/中断），可重试 */
    class Network(cause: Throwable) : DavException("网络错误: ${cause.message}", cause)

    /** 口令不一致（meta 头 KCV 校验失败，R-04）——在下载任何数据前终止 */
    class BadPassword : DavException("同步口令不一致（KCV 校验失败）")

    /** 云端数据已损坏 / 格式不符（magic、版本、GCM 认证失败） */
    class Corrupted(message: String, cause: Throwable? = null) : DavException(message, cause)
}

/**
 * HTTP / 异常 → `SyncError` 枚举**名**映射（T-4 用 `SyncError.valueOf` 反查）。
 *
 * 映射表（名称即 T-4 `SyncState.kt` 的枚举名契约，不得漂移）：
 *
 * | 情形 | 归类 | 说明 |
 * |---|---|---|
 * | KCV 校验失败 | BAD_PASSWORD | 「口令不一致」，用户换口令重试 |
 * | 401 / 403 / 407 | AUTH | 凭证错。⚠️ 403 在坚果云可能是**超额**语义——U-4 待实测定稿 |
 * | 412 | CONFLICT_WRITE | 条件写冲突（If-Match 失败 / If-None-Match:* 撞已存在） |
 * | 413 / 507 / 509 | QUOTA | 空间/流量不足（坚果云月上传 1GB 超额预期落此档，U-4） |
 * | 423 | NETWORK | DAV 锁占用，属瞬态，按可重试处理 |
 * | 其余 5xx | NETWORK | 服务端瞬态错误，可重试 |
 * | 超时 / IOException | NETWORK | 网络不可用 |
 * | 密文格式不符 / GCM 认证失败 | CORRUPTED | 「数据已损坏」 |
 * | 其余 | UNKNOWN | 兜底 |
 */
object DavErrors {

    const val NETWORK = "NETWORK"
    const val AUTH = "AUTH"
    const val BAD_PASSWORD = "BAD_PASSWORD"
    const val QUOTA = "QUOTA"
    const val CORRUPTED = "CORRUPTED"
    const val CONFLICT_WRITE = "CONFLICT_WRITE"
    const val UNKNOWN = "UNKNOWN"

    /** 任意 Throwable → `SyncError` 枚举名（幂等纯函数，禁止读系统时间/网络） */
    fun toSyncErrorName(t: Throwable): String = when (t) {
        is DavException.BadPassword -> BAD_PASSWORD
        is DavException.Corrupted -> CORRUPTED
        is DavException.Http -> httpToSyncError(t.code)
        is DavException.Network -> NETWORK
        is SyncCryptoException -> CORRUPTED
        is SocketTimeoutException -> NETWORK
        is IOException -> NETWORK
        else -> UNKNOWN
    }

    /** 单独抽出便于单测钉死映射表 */
    fun httpToSyncError(code: Int): String = when (code) {
        401, 403, 407 -> AUTH // 403 语义待坚果云实测（U-4）：当前按凭证错归类
        412 -> CONFLICT_WRITE
        413, 507, 509 -> QUOTA
        423 -> NETWORK
        in 500..599 -> NETWORK
        else -> UNKNOWN
    }
}
