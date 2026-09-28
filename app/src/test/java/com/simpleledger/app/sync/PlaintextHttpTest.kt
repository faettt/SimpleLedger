package com.simpleledger.app.sync

import com.simpleledger.app.sync.account.WebDavCred
import com.simpleledger.app.sync.account.WebDavCredIssue
import com.simpleledger.app.sync.account.isPlaintextHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-8 明文传输判定验收：[isPlaintextHttp] 全 case 矩阵 + `WebDavCred.validate`
 * 语义钉死（http **不在**格式校验拦截范围——明文风险由 UI 知情确认兜底，U-8 裁定）。
 */
class PlaintextHttpTest {

    // ------------------------------------------------------------ isPlaintextHttp

    @Test
    fun httpSchemesAreDetected() {
        assertTrue("标准 http", isPlaintextHttp("http://dav.example.com/dav/"))
        assertTrue("大小写不敏感", isPlaintextHttp("HTTP://dav.example.com"))
        assertTrue("混合大小写", isPlaintextHttp("Http://dav.example.com"))
        assertTrue("容忍首尾空白", isPlaintextHttp("  http://nas.local:5005/  "))
        assertTrue("带端口与路径", isPlaintextHttp("http://192.168.1.8:5005/home"))
    }

    @Test
    fun secureAndMalformedInputsAreNotPlaintext() {
        assertTrue(!isPlaintextHttp("https://dav.jianguoyun.com/dav/"))
        assertTrue(!isPlaintextHttp("HTTPS://dav.example.com"))
        assertTrue(!isPlaintextHttp("hTtPs://x.example.com"))
        assertTrue("空串", !isPlaintextHttp(""))
        assertTrue("纯空白", !isPlaintextHttp("   "))
        assertTrue("无 scheme", !isPlaintextHttp("dav.example.com/dav/"))
        assertTrue("非 http scheme", !isPlaintextHttp("ftp://dav.example.com"))
        assertTrue("前缀相似但非 scheme", !isPlaintextHttp("httpfoo://x"))
        assertTrue("https 内嵌 http 字样", !isPlaintextHttp("https://http://x"))
        assertTrue("仅 scheme 无体", !isPlaintextHttp("http:"))
    }

    /**
     * U-14 回归：判定与 OkHttp 同源解析后，缺斜杠的宽容 http 形态必须判明文——
     * 旧实现按 "://" 字面切 scheme 判 false，警示横幅与知情确认门全 Pass，
     * 而 OkHttp 照样把缺斜杠 URL 解析为 http 明文连接（Basic 凭证明文上网）。
     * 判定矩阵经 okhttp-android 5.5.0 AAR classes javac+java 实测钉死。
     */
    @Test
    fun lenientHttpFormsArePlaintext() {
        assertTrue("缺一个斜杠也是 http（U-14）", isPlaintextHttp("http:/dav.example.com"))
        assertTrue("缺斜杠带端口", isPlaintextHttp("http:/192.168.1.10:5005/"))
        assertTrue("仅 scheme 冒号后直写主机", isPlaintextHttp("http:192.168.1.10:5005/"))
    }

    // ------------------------------------------------------------ validate 语义钉死

    @Test
    fun validateDoesNotRejectPlaintextHttp() {
        // U-8 裁定：http 是合法形态（局域网自建），格式校验不拦；
        // 明文风险由设置页警示 + 接入/测连前一次性确认兜底
        val http = WebDavCred(baseUrl = "http://nas.local:5005/", username = "u", appPassword = "p")
        assertNull("http 凭证应通过格式校验", http.validate())
        val https = WebDavCred(baseUrl = "https://dav.jianguoyun.com/dav/", username = "u", appPassword = "p")
        assertNull(https.validate())
    }

    @Test
    fun validateStillRejectsMalformedUrl() {
        assertEquals(
            "空地址按 URL_EMPTY 拦",
            WebDavCredIssue.URL_EMPTY,
            WebDavCred(baseUrl = "", username = "u", appPassword = "p").validate(),
        )
        assertEquals(
            "无 scheme 的地址按 URL_INVALID 拦",
            WebDavCredIssue.URL_INVALID,
            WebDavCred(baseUrl = "dav.example.com", username = "u", appPassword = "p").validate(),
        )
    }
}
