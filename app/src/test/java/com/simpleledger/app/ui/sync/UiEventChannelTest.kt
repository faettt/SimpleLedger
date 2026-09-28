package com.simpleledger.app.ui.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * U-18 一次性 UI 事件通道（[UiEventChannel]）JVM 实测。
 *
 * 缺陷回归：事件原由 StateFlow（`UiState.event` 等）承载——data class 同值事件被
 * conflated 合并 → 同值事件第二次永不展示；纸签展示中离开页面清空动作不执行 →
 * 回页重放旧事件。Channel 语义：每个事件是独立队列元素、**接收即消费**、
 * 无人收集期间发出的事件入队缓存按序送达。
 *
 * 落点说明（沿 U-8 测试口径）：SyncSettingsViewModel / MemberManageViewModel /
 * ConflictTrashViewModel 构造参数含需 Android Context 的 SyncPrefs、动作走
 * viewModelScope（Main 调度器），纯 JVM 不可构造；故把事件承载机制下沉为本纯逻辑类
 * JVM 实测，VM 只保留「动作完成 → eventBus.send(事件)」的薄壳接线。
 */
class UiEventChannelTest {

    private companion object {
        /** 正常接收的上限（元素在队时应即刻返回；超时视为未送达） */
        const val RECEIVE_TIMEOUT_MS = 2_000L

        /** 「不再有事件」的判定窗：通道已空时 receive 挂起直到超时返回 null */
        const val DRAIN_PROBE_MS = 200L
    }

    @Test
    fun consecutiveEqualEventsAreBothDelivered() = runBlocking {
        val bus = UiEventChannel<String>()
        // 两次同值事件（如两次空跑的 SyncDone，outcome 全等；或连续两次测连成功）
        bus.send("TestOk")
        bus.send("TestOk")
        assertEquals(
            "第一次事件送达",
            "TestOk",
            withTimeoutOrNull(RECEIVE_TIMEOUT_MS) { bus.events.receive() },
        )
        assertEquals(
            "U-18 回归：同值事件不得被合并吞掉——第二次也要出纸签",
            "TestOk",
            withTimeoutOrNull(RECEIVE_TIMEOUT_MS) { bus.events.receive() },
        )
    }

    @Test
    fun consumedEventIsNotReplayedToANewCollector() = runBlocking {
        val bus = UiEventChannel<String>()
        bus.send("重置完成")
        assertEquals(
            "上一轮收集取走并展示（接收即消费）",
            "重置完成",
            withTimeoutOrNull(RECEIVE_TIMEOUT_MS) { bus.events.receive() },
        )
        // 模拟「纸签展示中离开页面 → 回页」：新一轮 LaunchedEffect 从头收集
        assertNull(
            "U-18 回归：已消费的事件不得向新收集者重放",
            withTimeoutOrNull(DRAIN_PROBE_MS) { bus.events.receive() },
        )
    }

    @Test
    fun eventsEmittedWithoutCollectorAreBufferedInOrder() = runBlocking {
        val bus = UiEventChannel<Int>()
        // 无收集者期间连发（如「立即同步」在页面外完成）：入队缓存，不丢不乱序
        bus.send(1)
        bus.send(2)
        bus.send(3)
        assertEquals(1, withTimeoutOrNull(RECEIVE_TIMEOUT_MS) { bus.events.receive() })
        assertEquals(2, withTimeoutOrNull(RECEIVE_TIMEOUT_MS) { bus.events.receive() })
        assertEquals(3, withTimeoutOrNull(RECEIVE_TIMEOUT_MS) { bus.events.receive() })
    }

    @Test
    fun distinctEventsKeepEmissionOrder() = runBlocking {
        val bus = UiEventChannel<String>()
        bus.send("失败")
        bus.send("成功")
        assertEquals(
            "FIFO：先发先达（纸签按发生顺序逐张出）",
            "失败",
            withTimeoutOrNull(RECEIVE_TIMEOUT_MS) { bus.events.receive() },
        )
        assertEquals(
            "成功",
            withTimeoutOrNull(RECEIVE_TIMEOUT_MS) { bus.events.receive() },
        )
    }
}
