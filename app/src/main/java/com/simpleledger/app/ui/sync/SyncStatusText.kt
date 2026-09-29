package com.simpleledger.app.ui.sync

import com.simpleledger.app.R
import com.simpleledger.app.sync.SkipReason
import com.simpleledger.app.sync.SyncState

/*
 * 同步文案口径（纯映射，无 Compose 依赖 → JVM 单测可直接钉死）。
 *
 * 与 SyncStatusBadge.syncStateLabel 的分工：角标（读屏 / 我的页入口）只用四态；
 * 状态详情标题多一条 P0-2 口径——待传 > 0 时「已同步」是假话，必须改口。
 * 文案本身仍全在 strings.xml（§7-7），本文件只做「状态 / 成因 → 资源 id」。
 */

/** 四态 → 状态文案资源（角标读屏与状态详情共用的同一口径） */
fun syncStateRes(state: SyncState): Int = when (state) {
    SyncState.Never -> R.string.sync_status_never
    SyncState.Idle -> R.string.sync_status_idle
    is SyncState.Syncing -> R.string.sync_status_syncing
    is SyncState.Failed -> R.string.sync_status_failed
}

/**
 * 状态详情标题口径（P0-2）：**仅 Idle + 有待传**时改说「待上传 N 条」。
 *
 * 为什么只压 Idle 一态：
 * - [SyncState.Failed] / [SyncState.Syncing] 是更强的事实（失败 / 正在进行），
 *   它们下面本来就另有错误行，改口反而盖掉当前处境；
 * - [SyncState.Never] 未配置同步，outbox 里那些操作是「还没接入的本地欠账」，
 *   说成「待上传」会让用户以为已经在传；
 * - 只有 Idle 是在明确宣称「已同步」——这正是要纠正的那句假话。
 */
fun syncStateTitleRes(state: SyncState, pendingCount: Int): Int =
    if (showsPendingIndicator(state, pendingCount)) {
        R.string.sync_status_pending
    } else {
        syncStateRes(state)
    }

/**
 * 待传指示是否生效（与 [syncStateTitleRes] 同一判据，UI 用它决定要不要再补一行
 * 「还没传上去」的说明——改口只解决「说的是不是实话」，说清楚「要不要做点什么」还得第二行）。
 */
fun showsPendingIndicator(state: SyncState, pendingCount: Int): Boolean =
    state is SyncState.Idle && pendingCount > 0

/* ================================================================ 跳过成因（P1-3） */

/**
 * 「本轮未执行」的成因 → 纸签文案资源。
 *
 * 旧口径是「已跳过（60 秒内已同步过）」一句到底，两种成因皆错：未配置时是引擎拿不到
 * 远端，压根没进过 60s 去抖窗口；单飞被挡时用户按的是「立即同步」，而 MANUAL 本来就不
 * 过去抖（[com.simpleledger.app.sync.SyncManager.syncNow] 无自动窗口）。所以必须分因。
 *
 * [reason] 为 null 只出现在旧构造路径（跳过轮必有成因，见 `SyncOutcome` 的
 * 「skipped = true ⇒ skipReason 非空」不变量）：此时只说**确定成立**的事实，不猜成因、
 * 更不把单飞误判成未配置。
 */
fun skipReasonRes(reason: SkipReason?): Int = when (reason) {
    SkipReason.NOT_CONFIGURED -> R.string.sync_skip_not_configured
    SkipReason.IN_FLIGHT -> R.string.sync_skip_in_flight
    null -> R.string.sync_skip_unexecuted
}
