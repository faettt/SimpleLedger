package com.simpleledger.app.ui.sync

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.sync.SyncError
import com.simpleledger.app.sync.SyncOutcome
import com.simpleledger.app.sync.SyncState
import com.simpleledger.app.sync.account.WebDavCredIssue
import com.simpleledger.app.sync.account.isPlaintextHttp
import com.simpleledger.app.sync.photo.PhotoTransfer
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.ConfirmDialog
import com.simpleledger.app.ui.components.ContentMaxWidth
import com.simpleledger.app.ui.components.ContentWidth
import com.simpleledger.app.ui.components.SlSnackbarHost
import com.simpleledger.app.ui.components.SlipCard
import com.simpleledger.app.ui.components.slTitleRule
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.theme.SlStatus
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.SlButtonShape
import com.simpleledger.app.ui.theme.SlChipShape
import com.simpleledger.app.ui.theme.warnColor
import com.simpleledger.app.util.DateTimes

/*
 * 同步设置页（U-1 载体/凭证/口令/照片/立即同步/重置 + U-5 状态详情）。
 *
 * 规范约束：纸片化 0.5dp 描边无阴影（SlipCard）、Snackbar 纸签无语义色条
 * （SlSnackbarHost 唯一宿主）、楷体全书一元制（SlType）、4dp 间距网格、
 * 颜色语义被分区身份独占（同步状态零新增颜色，仅 R-19 流量预警复用 warnColor 豁免口）。
 */

@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    layout: WindowLayout = WindowLayout.Compact,
    viewModel: SyncSettingsViewModel = viewModel(factory = SyncSettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showPasswordHelp by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showInsecureHttpConfirm by remember { mutableStateOf(false) }

    // 一次性事件 → 纸签（测试连接成功/失败、接入结果、同步结果、重置结果）。
    // U-18：事件走 Channel（接收即消费），for 循环逐个展示——同值连发不再被 StateFlow
    // 合并吞掉；展示中途离开页面时事件已被取走，回页不再重放旧事件。
    LaunchedEffect(Unit) {
        for (event in viewModel.events) {
            if (event is SyncEvent.InsecureHttpConfirm) {
                // U-8：明文传输确认走弹窗（纸签承接不了「继续/取消」二选一）
                showInsecureHttpConfirm = true
                continue
            }
            snackbarHostState.showSnackbar(eventText(context, event))
        }
    }

    Scaffold(
        // ⚠️ 外层 AppRoot 已消费 systemBars insets，内层归零防双重避让
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
        snackbarHost = { SlSnackbarHost(snackbarHostState) },
    ) { padding ->
        val body: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(SlIcons.Ui.ArrowLeft, contentDescription = stringResource(R.string.back))
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sync_title),
                            style = SlType.headline,
                            modifier = Modifier.slTitleRule(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ——— U-5 状态详情 ———
                StatusCard(syncState = syncState, state = state)

                // ——— 载体两枚纸片选项 ———
                GroupCaption(stringResource(R.string.sync_group_carrier))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    CarrierOption(
                        title = stringResource(R.string.sync_carrier_jianguoyun),
                        desc = stringResource(R.string.sync_carrier_jianguoyun_desc),
                        selected = state.carrier == SyncCarrier.JIANGUOYUN,
                        onClick = { viewModel.setCarrier(SyncCarrier.JIANGUOYUN) },
                        modifier = Modifier.weight(1f),
                    )
                    CarrierOption(
                        title = stringResource(R.string.sync_carrier_webdav),
                        desc = stringResource(R.string.sync_carrier_webdav_desc),
                        selected = state.carrier == SyncCarrier.WEBDAV,
                        onClick = { viewModel.setCarrier(SyncCarrier.WEBDAV) },
                        modifier = Modifier.weight(1f),
                    )
                }

                // ——— 同步账户 ———
                GroupCaption(stringResource(R.string.sync_group_account))
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::setBaseUrl,
                    label = { Text(stringResource(R.string.sync_field_url), style = SlType.label) },
                    singleLine = true,
                    enabled = !state.configured && !state.busy,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                // U-8：明文传输警示（http 地址）。不硬禁——局域网自建合法，但必须知情；
                // 接入/测连时另有一次性知情确认（InsecureHttpConfirm 弹窗）
                if (isPlaintextHttp(state.baseUrl)) {
                    Text(
                        text = stringResource(R.string.sync_insecure_http_warn),
                        style = SlType.bodySm.merge(SlStatus.warningSm),
                        color = warnColor(),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::setUsername,
                    label = { Text(stringResource(R.string.sync_field_username), style = SlType.label) },
                    singleLine = true,
                    enabled = !state.configured && !state.busy,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = state.appPassword,
                    onValueChange = viewModel::setAppPassword,
                    label = { Text(stringResource(R.string.sync_field_app_password), style = SlType.label) },
                    singleLine = true,
                    enabled = !state.configured && !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 「如何生成应用密码？」图文引导（R-02，坚果云路径）
                if (state.carrier == SyncCarrier.JIANGUOYUN) {
                    TextButton(onClick = { showPasswordHelp = !showPasswordHelp }) {
                        Text(
                            text = stringResource(R.string.sync_password_help_toggle),
                            style = SlType.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (showPasswordHelp) {
                        SlipCard(contentPadding = PaddingValues(16.dp)) {
                            Text(stringResource(R.string.sync_password_help_1), style = SlType.bodySm)
                            Text(stringResource(R.string.sync_password_help_2), style = SlType.bodySm)
                            Text(stringResource(R.string.sync_password_help_3), style = SlType.bodySm)
                            Text(stringResource(R.string.sync_password_help_4), style = SlType.bodySm)
                            Text(stringResource(R.string.sync_password_help_5), style = SlType.bodySm)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = viewModel::testConnection,
                    enabled = !state.busy,
                    shape = SlButtonShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sync_test), style = SlType.label)
                }

                // ——— 同步口令（首次设置 / 已设置态）———
                GroupCaption(stringResource(R.string.sync_group_password))
                // 楷体一行说明（U-1 指定文案）
                Text(
                    text = stringResource(R.string.sync_password_hint),
                    style = SlType.bodySm,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (state.configured || state.passwordSet) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.sync_password_field),
                            style = SlType.title,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sync_password_set),
                            style = SlType.bodySm.merge(SlStatus.selectedSm),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = state.syncPassword,
                        onValueChange = viewModel::setSyncPassword,
                        label = { Text(stringResource(R.string.sync_password_field), style = SlType.label) },
                        singleLine = true,
                        enabled = !state.busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // P1-2：口令是跨设备共享密钥，但框长得跟本机开锁密码一样——
                    // 新设备用户会自己发明一个，然后撞 BAD_PASSWORD。这行必须贴着输入框。
                    Text(
                        text = stringResource(R.string.sync_password_shared_hint),
                        style = SlType.bodySm,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // ——— 同步选项 ———
                GroupCaption(stringResource(R.string.sync_group_general))
                SlipCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.sync_wifi_only), style = SlType.title)
                            Text(
                                text = stringResource(R.string.sync_wifi_only_desc),
                                style = SlType.bodySm,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.wifiOnlyPhotos,
                            onCheckedChange = viewModel::setWifiOnlyPhotos,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                if (!state.configured) {
                    Button(
                        onClick = viewModel::enableSync,
                        enabled = !state.busy && state.syncPassword.isNotEmpty(),
                        shape = SlButtonShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.sync_enable), style = SlType.label)
                    }
                } else {
                    Button(
                        onClick = viewModel::syncNow,
                        enabled = !state.busy,
                        shape = SlButtonShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.sync_now), style = SlType.label)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = syncTimeText(context, state.lastSyncAt),
                        style = SlType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                // 「重置同步」低饱和文字入口（不与主操作抢视觉）
                TextButton(
                    onClick = { showResetConfirm = true },
                    enabled = !state.busy && state.configured,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(stringResource(R.string.sync_reset), style = SlType.bodySm)
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        if (layout == WindowLayout.Expanded) {
            ContentWidth(maxWidth = ContentMaxWidth.Narrow) { body() }
        } else {
            body()
        }
    }

    if (showResetConfirm) {
        // 纸片二次确认（ConfirmDialog：弹窗纸片 + 8dp 弹窗圆角，全 App 唯一确认语言）
        ConfirmDialog(
            title = stringResource(R.string.sync_reset_confirm_title),
            text = stringResource(R.string.sync_reset_confirm),
            confirmLabel = stringResource(R.string.confirm),
            onConfirm = {
                showResetConfirm = false
                viewModel.resetSync()
            },
            onDismiss = { showResetConfirm = false },
        )
    }

    if (showInsecureHttpConfirm) {
        // U-8：明文传输知情确认（ConfirmDialog 全 App 唯一确认语言；确认后本会话不再重复）
        ConfirmDialog(
            title = stringResource(R.string.sync_insecure_http_confirm_title),
            text = stringResource(R.string.sync_insecure_http_confirm),
            confirmLabel = stringResource(R.string.sync_insecure_http_continue),
            onConfirm = {
                showInsecureHttpConfirm = false
                viewModel.confirmInsecureHttp()
            },
            onDismiss = {
                showInsecureHttpConfirm = false
                viewModel.cancelInsecureHttp()
            },
        )
    }
}

/** U-5 状态详情纸片：四态角标 + 状态文字 + 上次同步 + 最近错误 + 本月照片流量 */
@Composable
private fun StatusCard(syncState: SyncState, state: SyncSettingsUiState) {
    val context = LocalContext.current
    SlipCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SyncStatusBadge(state = syncState, onClick = { /* 本页即状态详情 */ })
            Spacer(modifier = Modifier.width(4.dp))
            Column {
                // P0-2：待传 > 0 时标题由「已同步」改口为「待上传 N 条」（口径见 syncStateTitleRes）
                Text(stringResource(syncStateTitleRes(syncState, state.pendingCount)), style = SlType.title)
                Text(
                    text = syncTimeText(context, state.lastSyncAt),
                    style = SlType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 第二行说清「不是失败、不用手工做什么」——只改标题容易被读成一种错误态
                if (showsPendingIndicator(syncState, state.pendingCount)) {
                    Text(
                        text = stringResource(R.string.sync_status_pending_hint),
                        style = SlType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val error = state.lastError
                if (error != null) {
                    Text(
                        text = stringResource(R.string.sync_last_error, syncErrorLabel(error)),
                        style = SlType.bodySm.merge(SlStatus.errorSm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(
                R.string.sync_photo_usage,
                formatBytes(state.monthlyUpBytes),
                formatBytes(state.monthlyDownBytes),
            ),
            style = SlType.bodySm,
        )
        Text(
            text = stringResource(R.string.sync_quota_hint),
            style = SlType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // R-19：≥80% 额度角标（预警复用 warnColor 语义豁免口，不新增颜色语义）
        if (quotaWarn(state.monthlyUpBytes, state.monthlyDownBytes)) {
            Text(
                text = stringResource(R.string.sync_quota_warn),
                style = SlType.bodySm.merge(SlStatus.warningSm),
                color = warnColor(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // U-7：损坏分片隔离透出（隔离不挡其余同步，但内容缺失必须明说非静默；
        // 文案自带逃生口指引：「立即同步」重试 / 重置清除）
        if (state.quarantinedChunks > 0) {
            Text(
                text = stringResource(R.string.sync_quarantined_chunks, state.quarantinedChunks),
                style = SlType.bodySm.merge(SlStatus.warningSm),
                color = warnColor(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // U-7 照片侧对称：坏照片隔离透出（样式与文案结构照抄分片条——
        // 隔离不挡其余照片同步，「立即同步」重试 / 重置清除）
        if (state.quarantinedPhotos > 0) {
            Text(
                text = stringResource(R.string.sync_quarantined_photos, state.quarantinedPhotos),
                style = SlType.bodySm.merge(SlStatus.warningSm),
                color = warnColor(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 载体纸片选项：选中 = 垫纸浮现（纸叠纸语言）+ ✓，不给颜色加语义 */
@Composable
private fun CarrierOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SlipCard(
        modifier = modifier,
        stacked = selected,
        contentPadding = PaddingValues(12.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (selected) SlType.title.merge(SlStatus.selected) else SlType.title,
                )
                Text(
                    text = desc,
                    style = SlType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = SlIcons.Ui.CheckXs,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupCaption(text: String) {
    Text(
        text = text,
        style = SlType.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** 事件 → 纸签文案（含错误中文映射） */
private fun eventText(context: Context, event: SyncEvent): String = when (event) {
    SyncEvent.TestOk -> context.getString(R.string.sync_test_ok)
    is SyncEvent.TestInvalid -> context.getString(credIssueRes(event.issue))
    is SyncEvent.TestFailed -> failedText(context, event.error, event.detail)
    is SyncEvent.SetupOk -> context.getString(R.string.sync_setup_ok, event.exportedOps)
    SyncEvent.SetupBadPassword -> context.getString(R.string.sync_setup_bad_password)
    is SyncEvent.SetupInvalid -> context.getString(credIssueRes(event.issue))
    is SyncEvent.SetupFailed -> failedText(context, event.error, event.detail)
    is SyncEvent.SyncDone -> outcomeText(context, event.outcome)
    SyncEvent.ResetDone -> context.getString(R.string.sync_reset_done)
    SyncEvent.ResetFailed -> context.getString(R.string.sync_reset_failed)
    // U-8：知情确认走弹窗路径，永不以纸签呈现（此分支只为 when 穷尽）
    SyncEvent.InsecureHttpConfirm -> ""
}

/**
 * 失败纸签（v1.4.2 排障口）：有 [detail] 就附上可定位摘要——测连与接入两条路径同口径，
 * AUTH / ACCESS_DENIED 与 UNKNOWN 一视同仁，不再只有兜底档带得起定位信息。
 */
private fun failedText(context: Context, error: SyncError, detail: String?): String =
    if (detail != null) {
        context.getString(R.string.sync_event_failed_detail, errorText(context, error), detail)
    } else {
        context.getString(R.string.sync_event_failed, errorText(context, error))
    }

private fun outcomeText(context: Context, outcome: SyncOutcome): String = when {
    !outcome.success ->
        context.getString(R.string.sync_event_failed, errorText(context, outcome.error ?: SyncError.UNKNOWN))
    outcome.skipped -> context.getString(skipReasonRes(outcome.skipReason))
    else -> context.getString(R.string.sync_last_done, outcome.pushedOps, outcome.appliedOps)
}

private fun errorText(context: Context, error: SyncError): String = context.getString(
    when (error) {
        SyncError.NETWORK -> R.string.sync_error_network
        SyncError.AUTH -> R.string.sync_error_auth
        SyncError.ACCESS_DENIED -> R.string.sync_error_access_denied
        SyncError.BAD_PASSWORD -> R.string.sync_error_bad_password
        SyncError.QUOTA -> R.string.sync_error_quota
        SyncError.CORRUPTED -> R.string.sync_error_corrupted
        SyncError.CONFLICT_WRITE -> R.string.sync_error_conflict_write
        SyncError.UNKNOWN -> R.string.sync_error_unknown
    }
)

private fun credIssueRes(issue: WebDavCredIssue): Int = when (issue) {
    WebDavCredIssue.URL_EMPTY -> R.string.cred_issue_url_empty
    WebDavCredIssue.URL_INVALID -> R.string.cred_issue_url_invalid
    WebDavCredIssue.USERNAME_EMPTY -> R.string.cred_issue_username_empty
    WebDavCredIssue.APP_PASSWORD_EMPTY -> R.string.cred_issue_app_password_empty
}

private fun syncTimeText(context: Context, at: Long): String =
    if (at <= 0L) {
        context.getString(R.string.sync_status_never)
    } else {
        context.getString(
            R.string.sync_last_sync,
            DateTimes.dateLabel(DateTimes.toLocalDate(at)) + " " +
                DateTimes.timeLabel(DateTimes.toLocalTime(at)),
        )
    }

/** 字节数人读（照片流量展示） */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** R-19：任一方向用量 ≥ 额度 80%（整数比较，避免浮点） */
private fun quotaWarn(upBytes: Long, downBytes: Long): Boolean =
    upBytes * 5 >= PhotoTransfer.QUOTA_UPLOAD_BYTES * 4 ||
        downBytes * 5 >= PhotoTransfer.QUOTA_DOWNLOAD_BYTES * 4
