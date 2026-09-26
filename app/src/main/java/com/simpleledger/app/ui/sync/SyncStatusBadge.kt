package com.simpleledger.app.ui.sync

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.simpleledger.app.R
import com.simpleledger.app.sync.SyncError
import com.simpleledger.app.sync.SyncState
import com.simpleledger.app.ui.icon.SyncIcons
import com.simpleledger.app.ui.theme.SlMotion

/*
 * 同步状态角标（U-4）。
 *
 * 设计约束（规范 §3.4）：颜色语义被分区身份独占，**同步状态零新增颜色语义**——
 * 四态只用「图标形态 + 文案」区分，四枚图标一律主墨单色。
 * 失败态不弹任何窗（S6 静默），点角标进状态详情（同步设置页顶部）。
 */

/** 同步状态一句话（角标读屏 / 状态详情共用一份文案口径） */
@Composable
fun syncStateLabel(state: SyncState): String = when (state) {
    SyncState.Never -> stringResource(R.string.sync_status_never)
    SyncState.Idle -> stringResource(R.string.sync_status_idle)
    is SyncState.Syncing -> stringResource(R.string.sync_status_syncing)
    is SyncState.Failed -> stringResource(R.string.sync_status_failed)
}

/** 最近错误中文映射（U-5：枚举名 → 用户文案，数据层不写死文案） */
@Composable
fun syncErrorLabel(error: SyncError): String = stringResource(
    when (error) {
        SyncError.NETWORK -> R.string.sync_error_network
        SyncError.AUTH -> R.string.sync_error_auth
        SyncError.BAD_PASSWORD -> R.string.sync_error_bad_password
        SyncError.QUOTA -> R.string.sync_error_quota
        SyncError.CORRUPTED -> R.string.sync_error_corrupted
        SyncError.CONFLICT_WRITE -> R.string.sync_error_conflict_write
        SyncError.UNKNOWN -> R.string.sync_error_unknown
    }
)

/**
 * 同步四态角标：从未同步（空心圆）/ 已同步（✓）/ 同步中（旋转墨点）/ 失败（「!」）。
 *
 * 同步中的墨点走 SlMotion 档位旋转：周期 = [SlMotion.SlowMs] × 6（≈1.9s/圈），
 * 不散写时长；曲线取 Linear —— 无限循环的首尾必须相接，缓动会在每圈接缝处顿一下。
 * 系统「移除动画」时 MotionDurationScale 自动把周期压到 0，墨点瞬时转完不挡信息。
 *
 * @param onClick 点角标进状态详情（失败不弹窗，S6）
 */
@Composable
fun SyncStatusBadge(
    state: SyncState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = syncStateLabel(state)
    val icon = when (state) {
        SyncState.Never -> SyncIcons.Never
        SyncState.Idle -> SyncIcons.Done
        is SyncState.Syncing -> SyncIcons.Syncing
        is SyncState.Failed -> SyncIcons.Failed
    }
    val rotation = if (state is SyncState.Syncing) {
        val transition = rememberInfiniteTransition(label = "syncSpin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = SlMotion.SlowMs * 6, easing = SlMotion.Linear),
                repeatMode = RepeatMode.Restart,
            ),
            label = "syncSpinAngle",
        ).value
    } else {
        0f
    }

    Box(
        modifier = modifier
            // 触控目标 ≥48dp（无障碍红线）；图标本身 24dp
            .size(48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}
