package com.simpleledger.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.sync.SetupResult
import com.simpleledger.app.sync.SyncError
import com.simpleledger.app.sync.SyncManager
import com.simpleledger.app.sync.SyncOutcome
import com.simpleledger.app.sync.SyncState
import com.simpleledger.app.sync.TestResult
import com.simpleledger.app.sync.account.AccountStore
import com.simpleledger.app.sync.account.InsecureHttpGate
import com.simpleledger.app.sync.account.SyncPrefs
import com.simpleledger.app.sync.account.WebDavCred
import com.simpleledger.app.sync.account.WebDavCredIssue
import com.simpleledger.app.sync.photo.PhotoTransfer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 同步载体两枚（U-1）：坚果云（默认）/ 其他 WebDAV */
enum class SyncCarrier { JIANGUOYUN, WEBDAV }

/**
 * 同步设置页一次性事件（UI 映射为纸签文案；数据层不写死文案，§7-7）。
 */
sealed interface SyncEvent {
    data object TestOk : SyncEvent
    data class TestInvalid(val issue: WebDavCredIssue) : SyncEvent
    data class TestFailed(val error: SyncError) : SyncEvent

    /** 接入成功；[exportedOps] = 存量导出条数，[outcome] = 首轮双向同步结果 */
    data class SetupOk(val exportedOps: Int, val outcome: SyncOutcome) : SyncEvent
    data object SetupBadPassword : SyncEvent
    data class SetupInvalid(val issue: WebDavCredIssue) : SyncEvent
    /**
     * 接入失败；[detail] 诊断信息（失败步骤 + 异常摘要），UNKNOWN 档 UI 透出（v1.4.2 排障口）。
     */
    data class SetupFailed(val error: SyncError, val detail: String? = null) : SyncEvent

    data class SyncDone(val outcome: SyncOutcome) : SyncEvent
    data object ResetDone : SyncEvent
    data object ResetFailed : SyncEvent

    /**
     * U-8 明文传输知情确认：URL 为 http 时发起接入/测连前的一次性确认。
     * UI 走确认弹窗（不走纸签——纸签承接不了「继续/取消」二选一），
     * 用户确认后本会话不再重复确认。
     */
    data object InsecureHttpConfirm : SyncEvent
}

data class SyncSettingsUiState(
    /** 是否已完成同步配置（凭证 + 派生密钥齐备） */
    val configured: Boolean = false,
    /** 同步口令是否已设置（KDF 盐存在 = 已设置） */
    val passwordSet: Boolean = false,
    val carrier: SyncCarrier = SyncCarrier.JIANGUOYUN,
    val baseUrl: String = SyncSettingsViewModel.JIANGUOYUN_URL,
    val username: String = "",
    val appPassword: String = "",
    /** 尚未接入时的「设置同步口令」输入 */
    val syncPassword: String = "",
    /** 「仅 Wi-Fi 传照片」（R-17，默认开；设备本地偏好不同步） */
    val wifiOnlyPhotos: Boolean = true,
    /** 上次同步成功时刻（0 = 从未同步） */
    val lastSyncAt: Long = 0L,
    /** 最近错误（枚举）；null = 无错误 */
    val lastError: SyncError? = null,
    /** 本月照片流量字节 (上行, 下行)（R-19） */
    val monthlyUpBytes: Long = 0L,
    val monthlyDownBytes: Long = 0L,
    /** 当前被隔离的损坏分片数（U-7：0 = 无；「立即同步」重试，重置清零） */
    val quarantinedChunks: Int = 0,
    val busy: Boolean = false,
)

/**
 * 同步设置页 VM（U-1/U-5）：载体/凭证/口令/照片偏好/立即同步/重置 + 状态详情数据。
 *
 * 全部同步动作只经 [SyncManager] 门面（T-4 契约）：testConnection / setupAccount /
 * syncNow / resetSync；失败一律以事件回 UI（S6 数据层不弹窗）。
 */
class SyncSettingsViewModel(
    private val syncManager: SyncManager,
    private val account: AccountStore,
    private val prefs: SyncPrefs,
    private val photo: PhotoTransfer,
) : ViewModel() {

    /** 角标状态（U-4 四态） */
    val syncState: StateFlow<SyncState> = syncManager.state

    /** U-8 会话内知情确认门（判定与记忆下沉为纯逻辑 JVM 可测；VM 只做事件与续跑编排） */
    private val insecureHttpGate = InsecureHttpGate()

    /** U-8 被拦截门挂起的动作（确认后续跑） */
    private var pendingAfterInsecureConfirm: (() -> Unit)? = null

    private val _state = MutableStateFlow(SyncSettingsUiState())
    val state: StateFlow<SyncSettingsUiState> = _state.asStateFlow()

    /**
     * 一次性事件通道（U-18）：事件不再存进 [SyncSettingsUiState]——StateFlow 承载会把
     * 同值事件合并吞掉、展示中离开页面还会回页重放；Channel「接收即消费」两症皆除
     * （机制与缺陷说明见 [UiEventChannel]）。页面单消费者：LaunchedEffect for 循环。
     */
    private val eventBus = UiEventChannel<SyncEvent>()
    val events: ReceiveChannel<SyncEvent> = eventBus.events

    init {
        val saved = account.load()
        _state.update {
            it.copy(
                carrier = if (saved != null && saved.baseUrl != JIANGUOYUN_URL) {
                    SyncCarrier.WEBDAV
                } else {
                    SyncCarrier.JIANGUOYUN
                },
                baseUrl = saved?.baseUrl ?: JIANGUOYUN_URL,
                username = saved?.username ?: "",
                appPassword = saved?.appPassword ?: "",
            )
        }
        refresh()
    }

    fun setCarrier(carrier: SyncCarrier) = _state.update {
        it.copy(
            carrier = carrier,
            // 坚果云模式预填标准地址；从坚果云切到自定义时把预填清掉，免得照抄进别人的网盘
            baseUrl = when {
                carrier == SyncCarrier.JIANGUOYUN -> JIANGUOYUN_URL
                it.baseUrl == JIANGUOYUN_URL -> ""
                else -> it.baseUrl
            },
        )
    }

    fun setBaseUrl(value: String) = _state.update { it.copy(baseUrl = value) }

    fun setUsername(value: String) = _state.update { it.copy(username = value) }

    fun setAppPassword(value: String) = _state.update { it.copy(appPassword = value) }

    fun setSyncPassword(value: String) = _state.update { it.copy(syncPassword = value) }

    /** 「仅 Wi-Fi 传照片」（R-17）：设备本地偏好，不参与同步 */
    fun setWifiOnlyPhotos(enabled: Boolean) {
        prefs.wifiOnlyPhotos = enabled
        _state.update { it.copy(wifiOnlyPhotos = enabled) }
    }

    /** R-01 连通性测试（只读探针）：成功/失败都出纸签，不弹窗；http URL 先过 U-8 知情确认 */
    fun testConnection() = viewModelScope.launch {
        if (gateOnInsecureHttp(::testConnectionImpl)) return@launch
        testConnectionImpl()
    }

    private fun testConnectionImpl(): Job = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val event = when (val result = syncManager.testConnection(currentCred())) {
            TestResult.Ok -> SyncEvent.TestOk
            is TestResult.InvalidCred -> SyncEvent.TestInvalid(result.issue)
            is TestResult.Failed -> SyncEvent.TestFailed(result.reason)
        }
        // U-18：事件出通道（不再进 state），busy 归位与事件发射分离
        _state.update { it.copy(busy = false) }
        eventBus.send(event)
    }

    /** 首次接入（§4.2）：凭证 + 同步口令 + 默认成员名（后续在成员管理页改名）；http URL 先过 U-8 知情确认 */
    fun enableSync() = viewModelScope.launch {
        val password = _state.value.syncPassword
        if (password.isEmpty()) return@launch
        if (gateOnInsecureHttp(::enableSyncImpl)) return@launch
        enableSyncImpl()
    }

    private fun enableSyncImpl(): Job = viewModelScope.launch {
        val password = _state.value.syncPassword
        if (password.isEmpty()) return@launch
        _state.update { it.copy(busy = true) }
        val event = when (
            val result = syncManager.setupAccount(
                cred = currentCred(),
                password = password.toCharArray(),
                memberName = SyncManager.DEFAULT_MEMBER_NAME,
            )
        ) {
            is SetupResult.Success -> {
                refresh()
                SyncEvent.SetupOk(result.exportedOps, result.outcome)
            }

            SetupResult.BadPassword -> SyncEvent.SetupBadPassword
            is SetupResult.InvalidCred -> SyncEvent.SetupInvalid(result.issue)
            is SetupResult.Failed -> SyncEvent.SetupFailed(result.reason, result.detail)
        }
        _state.update { it.copy(busy = false) }
        eventBus.send(event)
    }

    /** 「立即同步」（S6：结果以纸签告知，失败不弹窗） */
    fun syncNow() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val outcome = syncManager.syncNow()
        refresh()
        _state.update { it.copy(busy = false) }
        eventBus.send(SyncEvent.SyncDone(outcome))
    }

    /** 重置同步（R-05）：云端作废、本地账本零触碰 */
    fun resetSync() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val result = syncManager.resetSync()
        refresh()
        _state.update {
            it.copy(
                busy = false,
                syncPassword = "",
            )
        }
        eventBus.send(if (result.isSuccess) SyncEvent.ResetDone else SyncEvent.ResetFailed)
    }

    /**
     * U-8 明文传输拦截门：URL 为 http 且本会话尚未知情确认 → 发一次性确认事件、
     * 挂起动作（确认后续跑），返回 true = 已拦下。https 或已确认直接放行。
     */
    private fun gateOnInsecureHttp(pending: () -> Unit): Boolean {
        if (insecureHttpGate.decide(_state.value.baseUrl) == InsecureHttpGate.Decision.Pass) return false
        pendingAfterInsecureConfirm = pending
        eventBus.send(SyncEvent.InsecureHttpConfirm)
        return true
    }

    /** U-8：用户知情选择继续——本会话内不再重复确认，续跑挂起的接入/测连动作 */
    fun confirmInsecureHttp() {
        insecureHttpGate.confirm()
        val pending = pendingAfterInsecureConfirm
        pendingAfterInsecureConfirm = null
        pending?.invoke()
    }

    /** U-8：用户取消——丢弃挂起的动作，停留原页 */
    fun cancelInsecureHttp() {
        pendingAfterInsecureConfirm = null
    }

    /** 刷新状态详情数据（上次同步 / 最近错误 / 照片流量 / 配置态） */
    fun refresh() {
        val usage = photo.monthlyUsage()
        _state.update {
            it.copy(
                configured = syncManager.isConfigured(),
                passwordSet = prefs.kdfParams() != null,
                wifiOnlyPhotos = prefs.wifiOnlyPhotos,
                lastSyncAt = prefs.lastSyncAt,
                lastError = prefs.lastError?.let(SyncError::fromName),
                monthlyUpBytes = usage.first,
                monthlyDownBytes = usage.second,
                quarantinedChunks = syncManager.quarantinedChunkCount(),
            )
        }
    }

    private fun currentCred(): WebDavCred {
        val s = _state.value
        return WebDavCred(baseUrl = s.baseUrl, username = s.username, appPassword = s.appPassword)
    }

    companion object {
        /** 坚果云 WebDAV 标准地址（U-1 预填） */
        const val JIANGUOYUN_URL = "https://dav.jianguoyun.com/dav/"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                SyncSettingsViewModel(
                    syncManager = app.container.syncManager,
                    account = app.container.syncAccount,
                    prefs = app.container.syncPrefs,
                    photo = app.container.photoTransfer,
                )
            }
        }
    }
}
