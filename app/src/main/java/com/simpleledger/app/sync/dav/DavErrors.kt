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
 * | 401 / 407 | AUTH | 凭证错——**401 才是凭证码**（U-4/T4 定稿）。失败另带 [DavErrors.detailOf] 定位 |
 * | 403 | ACCESS_DENIED | 服务端拒绝访问。403 有≥4 种与密码无关的语义：额度用尽 / 目录不可写 /
 *   二次验证 / 风控频控（证据与 URL 见 `outputs/sync-diagnosis-2026-09-29/坚果云403语义调研.md`）。
 *   **不并进 AUTH**（用户会盲改密码），**也不并进 QUOTA**（目录、安全设置会被误诊成额度不足） |
 * | 429 | NETWORK | 限流，瞬态可重试。防御性分支：坚果云实测频控走 503（调研 §3.5），其它 WebDAV 实现确有 429 |
 * | 412 | CONFLICT_WRITE | 条件写冲突（If-Match 失败 / If-None-Match:* 撞已存在） |
 * | 413 / 507 / 509 | QUOTA | 空间/流量不足（对自建 sabre-dav / Nextcloud 是正确码，保持不变） |
 * | 423 | NETWORK | DAV 锁占用，属瞬态，按可重试处理 |
 * | 其余 5xx | NETWORK | 服务端瞬态错误，可重试 |
 * | 超时 / IOException | NETWORK | 网络不可用 |
 * | 密文格式不符 / GCM 认证失败 | CORRUPTED | 「数据已损坏」 |
 * | 其余 | UNKNOWN | 兜底 |
 */
object DavErrors {

    const val NETWORK = "NETWORK"
    const val AUTH = "AUTH"
    const val ACCESS_DENIED = "ACCESS_DENIED"
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
        401, 407 -> AUTH // 401 = 凭证码（407 = 代理要求认证，同档）
        403 -> ACCESS_DENIED // U-4/T4 定稿：≥4 种与密码无关的语义，不可并进 AUTH / QUOTA
        412 -> CONFLICT_WRITE
        413, 507, 509 -> QUOTA
        423 -> NETWORK
        429 -> NETWORK // 限流（防御性；坚果云频控实测 503，落下面的 5xx 分支）
        in 500..599 -> NETWORK
        else -> UNKNOWN
    }

    /**
     * 失败定位摘要（v1.4.2 排障口的统一口径）：回答「哪一步、打到哪个地址、回来什么码」。
     *
     * 为什么拒绝档尤其需要：403（[ACCESS_DENIED]）与 401（[AUTH]）语义完全不同却都表现为
     * 「进不去」，用户看到「凭证无效」只会去改密码；带上 `HTTP 403 PROPFIND /dav/` 才能分辨
     * 「服务端压根不让进」与「请求本身有问题」，报障截图也才可定位。
     *
     * 与 [toSyncErrorName] 同样是**幂等纯函数**（禁读系统时间 / 网络，单测钉死输出），
     * 且不含中文文案——文案归 `strings.xml`（§7-7），本函数只产出可供拼装的定位串。
     */
    fun detailOf(t: Throwable): String = when (t) {
        is DavException.Http -> "HTTP ${t.code} ${t.method} ${t.path}"
        else -> t.message ?: t.javaClass.name
    }
}
