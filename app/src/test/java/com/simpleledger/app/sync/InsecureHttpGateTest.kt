package com.simpleledger.app.sync

import com.simpleledger.app.sync.account.InsecureHttpGate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U-8 明文传输知情确认门（[InsecureHttpGate]）JVM 实测：
 * http 拦下待确认 / 确认后会话内放行且不再重问 / https 与畸形输入不触发 / 取消不落记忆。
 *
 * 落点说明（U-8 测试口径）：SyncSettingsViewModel 纯 JVM 不可构造——构造参数
 * `SyncPrefs` 需 android Context、动作方法走 viewModelScope（Main 调度器）——
 * 故把「判定 + 确认记忆」下沉为本纯逻辑类；VM 只保留事件发射与续跑编排的薄壳
 * （decide()==Ask ⇒ 发 InsecureHttpConfirm 事件且不发起请求，两者按构造绑定）。
 */
class InsecureHttpGateTest {

    @Test
    fun httpUrlIsHeldForConfirmation() {
        val gate = InsecureHttpGate()
        assertEquals(
            "http 地址首次动作应拦下待确认（VM 据此发警示事件、不发起请求）",
            InsecureHttpGate.Decision.Ask,
            gate.decide("http://nas.local:5005/"),
        )
    }

    @Test
    fun confirmReleasesThisSessionWithoutReAsking() {
        val gate = InsecureHttpGate()
        assertEquals(InsecureHttpGate.Decision.Ask, gate.decide("http://nas.local:5005/"))
        gate.confirm()
        assertEquals("确认后放行", InsecureHttpGate.Decision.Pass, gate.decide("http://nas.local:5005/"))
        assertEquals(
            "会话级记忆：换一个 http 地址也不再询问（U-8 定案口径）",
            InsecureHttpGate.Decision.Pass,
            gate.decide("HTTP://other.nas:5006/"),
        )
    }

    @Test
    fun httpsNeverTriggersGate() {
        val gate = InsecureHttpGate()
        assertEquals(
            "https 不触发知情确认",
            InsecureHttpGate.Decision.Pass,
            gate.decide("https://dav.jianguoyun.com/dav/"),
        )
        assertEquals(
            "https 大小写混合同样放行",
            InsecureHttpGate.Decision.Pass,
            gate.decide("HTTPS://dav.example.com"),
        )
    }

    @Test
    fun emptyAndMalformedUrlsPassThrough() {
        val gate = InsecureHttpGate()
        // 畸形 URL 的拦截是 WebDavCred.validate 的职责，门只回答「有没有明文风险」
        assertEquals(InsecureHttpGate.Decision.Pass, gate.decide(""))
        assertEquals(InsecureHttpGate.Decision.Pass, gate.decide("   "))
        assertEquals(InsecureHttpGate.Decision.Pass, gate.decide("dav.example.com/dav/"))
        assertEquals(InsecureHttpGate.Decision.Pass, gate.decide("httpfoo://x"))
    }

    /**
     * U-14 回归：`http:/…`（少写一个斜杠）经 OkHttp 宽容解析是合法 http 明文连接，
     * 门必须拦下知情确认——旧判定按 "://" 字面切 scheme 误判非 http 而直接 Pass，
     * 警示横幅与确认弹窗全部不出现（判定矩阵经 okhttp-android 5.5.0 classes 实测钉死）。
     */
    @Test
    fun lenientSlashlessHttpFormIsHeldForConfirmation() {
        val gate = InsecureHttpGate()
        assertEquals(
            "缺斜杠 http 也应拦下待确认（U-14）",
            InsecureHttpGate.Decision.Ask,
            gate.decide("http:/dav.example.com"),
        )
        // 知情确认后会话内放行（与标准形态同一记忆口径）
        gate.confirm()
        assertEquals(InsecureHttpGate.Decision.Pass, gate.decide("http:/dav.example.com"))
    }

    @Test
    fun cancelDoesNotRememberConfirmation() {
        val gate = InsecureHttpGate()
        assertEquals(InsecureHttpGate.Decision.Ask, gate.decide("http://nas.local:5005/"))
        // 取消 = 不调 confirm()：记忆不落，下一次动作照拦（知情确认必须主动选择）
        assertEquals(
            "取消后再次动作应再次询问",
            InsecureHttpGate.Decision.Ask,
            gate.decide("http://nas.local:5005/"),
        )
    }

    @Test
    fun judgeIsInjectable() {
        val gate = InsecureHttpGate(judge = { url -> url.startsWith("ws:") })
        assertEquals("自定义判定生效（注入缝存在）", InsecureHttpGate.Decision.Ask, gate.decide("ws://x"))
        assertEquals("非判定命中的地址照常放行", InsecureHttpGate.Decision.Pass, gate.decide("http://x"))
    }
}
