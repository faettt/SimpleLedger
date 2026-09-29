package com.simpleledger.app.sync

import com.simpleledger.app.sync.dav.DavErrors
import com.simpleledger.app.sync.dav.DavException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 可定位 detail（P1-1/G-2）JVM 实测：钉死 [DavErrors.detailOf] 的输出形状，
 * 外加「档位 ↔ detail 形状」的配对断言（403 换档不影响 detail）。
 *
 * 映射表本身（401/407 → AUTH、403 → ACCESS_DENIED、429 → NETWORK）由 `MiniWebDavClientTest`
 * 用 [DavErrors.httpToSyncError] 钉住，本测试不复述；这里只管「失败时能带走什么定位信息」——
 * 只有档位没有 detail 时，用户看到「凭证无效」只能反复改密码。
 */
class DavErrorsDetailTest {

    @Test
    fun httpDetailCarriesCodeMethodAndPath() {
        assertEquals(
            "四码同形：detail 必须能分辨是哪一码打到哪个地址",
            "HTTP 403 PROPFIND /dav/",
            DavErrors.detailOf(DavException.Http(403, "PROPFIND", "/dav/")),
        )
        assertEquals("HTTP 401 GET /dav/meta", DavErrors.detailOf(DavException.Http(401, "GET", "/dav/meta")))
        assertEquals("HTTP 407 PUT /dav/chunk", DavErrors.detailOf(DavException.Http(407, "PUT", "/dav/chunk")))
    }

    @Test
    fun forbiddenKeepsLocatableDetailAfterReclassification() {
        val forbidden = DavException.Http(403, "PROPFIND", "/dav/")
        assertEquals(
            "403 已按 U-4/T4 定稿独立成档（401 才是凭证码）",
            DavErrors.ACCESS_DENIED,
            DavErrors.toSyncErrorName(forbidden),
        )
        assertEquals(
            "DavErrors 的档位字符串必须是 SyncError 的真实枚举名（打错会静默退化成 UNKNOWN）",
            SyncError.ACCESS_DENIED,
            SyncError.fromName(DavErrors.toSyncErrorName(forbidden)),
        )
        assertEquals(
            "换档不换 detail 形状：仍能定位到 method/path/code",
            "HTTP 403 PROPFIND /dav/",
            DavErrors.detailOf(forbidden),
        )
    }

    @Test
    fun nonHttpDetailFallsBackToMessage() {
        assertEquals(
            "网络档沿用异常摘要（与 failedAt 同口径）",
            "网络错误: timeout",
            DavErrors.detailOf(DavException.Network(IOException("timeout"))),
        )
        assertEquals(
            "口令档给 KCV 摘要",
            "同步口令不一致（KCV 校验失败）",
            DavErrors.detailOf(DavException.BadPassword()),
        )
    }

    @Test
    fun detailIsNeverEmpty() {
        assertEquals(
            "无 message 的异常退化为类名，绝不能产出空串（空 detail 等于没带）",
            "java.net.SocketTimeoutException",
            DavErrors.detailOf(SocketTimeoutException()),
        )
        assertFalse(DavErrors.detailOf(IOException()).isEmpty())
    }
}
