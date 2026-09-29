package com.simpleledger.app.ui.sync

import com.simpleledger.app.R
import com.simpleledger.app.sync.SkipReason
import com.simpleledger.app.sync.SyncError
import com.simpleledger.app.sync.SyncPhase
import com.simpleledger.app.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-2 状态详情标题口径（[syncStateTitleRes] / [showsPendingIndicator]）JVM 实测。
 *
 * 缺陷回归：`SyncState` 只有 Never/Idle/Syncing/Failed 四态，同步成功后无条件 Idle，
 * 标题恒说「已同步」——用户随后新记 20 笔，outbox 里 20 条待传也照旧显示「已同步」。
 * 修法是数据层 outbox 计数（Room Flow）接进状态卡，标题在 **Idle + 待传 > 0** 时改口；
 * 本测试钉死「哪几态改口、哪几态不改口」，防止以后为了让角标好看把口径放大或收窄。
 *
 * 纯函数（不碰 Compose / Android 资源取值），故与 UiEventChannelTest 同口径走 JVM：
 * VM 侧因 SyncPrefs 需 Context、动作走 Main 调度器，不可纯 JVM 构造。
 */
class SyncStatusTextTest {

    @Test
    fun idleWithPendingRetitlesToPending() {
        assertEquals(
            "Idle + 待传 3 条 → 待上传 3 条（不再是「已同步」）",
            R.string.sync_status_pending,
            syncStateTitleRes(SyncState.Idle, 3),
        )
        assertTrue("第二条说明随改口一起出现", showsPendingIndicator(SyncState.Idle, 1))
    }

    @Test
    fun idleWithEmptyOutboxKeepsIdle() {
        assertEquals(
            "outbox 空 → 维持「已同步」",
            R.string.sync_status_idle,
            syncStateTitleRes(SyncState.Idle, 0),
        )
        assertFalse("无待传就不该出现「还没传上去」的说明", showsPendingIndicator(SyncState.Idle, 0))
    }

    @Test
    fun strongerStatesAreNotOverriddenByPendingCount() {
        assertEquals(
            "同步中即使有历史待传，也说「同步中」",
            R.string.sync_status_syncing,
            syncStateTitleRes(SyncState.Syncing(SyncPhase.PUSH_OPS), 7),
        )
        assertEquals(
            "失败态压过待传（下面另有错误行）",
            R.string.sync_status_failed,
            syncStateTitleRes(SyncState.Failed(SyncError.AUTH, 0L), 7),
        )
        assertEquals(
            "从未同步 = 还没接入，outbox 是本地欠账，不能说成「待上传」",
            R.string.sync_status_never,
            syncStateTitleRes(SyncState.Never, 7),
        )
        assertFalse(
            "失败态的角标说明也不该被待传口径顶掉",
            showsPendingIndicator(SyncState.Failed(SyncError.NETWORK, 1L), 7),
        )
    }

    @Test
    fun fourStateMappingMatchesBadgeWording() {
        assertEquals(R.string.sync_status_never, syncStateRes(SyncState.Never))
        assertEquals(R.string.sync_status_idle, syncStateRes(SyncState.Idle))
        assertEquals(R.string.sync_status_syncing, syncStateRes(SyncState.Syncing(SyncPhase.MERGE)))
        assertEquals(R.string.sync_status_failed, syncStateRes(SyncState.Failed(SyncError.QUOTA, 0L)))
    }

    @Test
    fun skipReasonGetsItsOwnWording() {
        assertEquals(
            "未配置 → 说「同步尚未开启」，不是「60 秒内已同步过」",
            R.string.sync_skip_not_configured,
            skipReasonRes(SkipReason.NOT_CONFIGURED),
        )
        assertEquals(
            "单飞占用 → 说「正在同步中，请稍候」（手动同步压根不过去抖）",
            R.string.sync_skip_in_flight,
            skipReasonRes(SkipReason.IN_FLIGHT),
        )
        assertEquals(
            "旧构造路径兜底只说确定成立的事实",
            R.string.sync_skip_unexecuted,
            skipReasonRes(null),
        )

        // P1-3 的病灶就是一句到底：三种情形必须落在三个不同资源上，复用即回归缺陷
        val distinct = setOf(
            skipReasonRes(SkipReason.NOT_CONFIGURED),
            skipReasonRes(SkipReason.IN_FLIGHT),
            skipReasonRes(null),
        )
        assertEquals("两种成因 + null 兜底不得共用同一句文案", 3, distinct.size)
    }
}
