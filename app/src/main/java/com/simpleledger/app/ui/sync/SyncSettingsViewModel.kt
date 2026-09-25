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
import com.simpleledger.app.sync.account.SyncPrefs
import com.simpleledger.app.sync.account.WebDavCred
import com.simpleledger.app.sync.account.WebDavCredIssue
import com.simpleledger.app.sync.photo.PhotoTransfer
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
    data class SetupFailed(val error: SyncError) : SyncEvent

    data class SyncDone(val outcome: SyncOutcome) : SyncEvent
    data object ResetDone : SyncEvent
    data object ResetFailed : SyncEvent
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
    val busy: Boolean = false,
    val event: SyncEvent? = null,
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

    private val _state = MutableStateFlow(SyncSettingsUiState())
    val state: StateFlow<SyncSettingsUiState> = _state.asStateFlow()

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

    /** R-01 连通性测试（只读探针）：成功/失败都出纸签，不弹窗 */
    fun testConnection() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val event = when (val result = syncManager.testConnection(currentCred())) {
            TestResult.Ok -> SyncEvent.TestOk
            is TestResult.InvalidCred -> SyncEvent.TestInvalid(result.issue)
            is TestResult.Failed -> SyncEvent.TestFailed(result.reason)
        }
        _state.update { it.copy(busy = false, event = event) }
    }

    /** 首次接入（§4.2）：凭证 + 同步口令 + 默认成员名（后续在成员管理页改名） */
    fun enableSync() = viewModelScope.launch {
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
            is SetupResult.Failed -> SyncEvent.SetupFailed(result.reason)
        }
        _state.update { it.copy(busy = false, event = event) }
    }

    /** 「立即同步」（S6：结果以纸签告知，失败不弹窗） */
    fun syncNow() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val outcome = syncManager.syncNow()
        refresh()
        _state.update { it.copy(busy = false, event = SyncEvent.SyncDone(outcome)) }
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
                event = if (result.isSuccess) SyncEvent.ResetDone else SyncEvent.ResetFailed,
            )
        }
    }

    fun clearEvent() = _state.update { it.copy(event = null) }

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
