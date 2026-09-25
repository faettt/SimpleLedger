package com.simpleledger.app.ui.mine

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.data.export.DataExporter
import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.data.settings.AppSettings
import com.simpleledger.app.sync.SyncManager
import com.simpleledger.app.sync.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 一次性提示（由界面映射为文案，便于后续多语言） */
enum class MineMessage { EXPORT_FAILED, RESTORE_DONE, RESTORE_FAILED }

data class MineUiState(
    val entryCount: Int = 0,
    val sectionCount: Int = 0,
    val categoryCount: Int = 0,
    val busy: Boolean = false,
    val message: MineMessage? = null,
    /** 待分享 / 保存的导出文件 */
    val shareFile: File? = null,
    /** 待确认的恢复来源 */
    val restoreConfirmUri: Uri? = null,
)

class MineViewModel(
    private val repository: LedgerRepository,
    private val exporter: DataExporter,
    private val settings: AppSettings,
    private val syncManager: SyncManager,
    private val syncDao: SyncDao,
) : ViewModel() {

    val themeMode: StateFlow<String> = settings.themeMode
    val dynamicColor: StateFlow<Boolean> = settings.dynamicColor
    val hideAmounts: StateFlow<Boolean> = settings.hideAmounts
    val appLock: StateFlow<Boolean> = settings.appLock
    val secureScreen: StateFlow<Boolean> = settings.secureScreen
    val quickAmounts: StateFlow<List<Long>> = settings.quickAmounts

    /** T-5：「同步设置」行尾四态角标（U-4） */
    val syncState: StateFlow<SyncState> = syncManager.state

    /** T-5：「冲突回收站」行尾计数角标（U-3：有留底时提示数量） */
    val trashCount: StateFlow<Int> = syncDao.observeVisibleTrashCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** T-5：「成员管理」行描述里的成员数 */
    val memberCount: StateFlow<Int> = syncDao.observeMembers()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _state = MutableStateFlow(MineUiState())
    val state: StateFlow<MineUiState> = _state.asStateFlow()

    init {
        refreshCounts()
    }

    fun refreshCounts() = viewModelScope.launch {
        val (entries, sections, categories) = repository.counts()
        _state.update {
            it.copy(entryCount = entries, sectionCount = sections, categoryCount = categories)
        }
    }

    fun exportCsv() = produceExport { exporter.exportCsv() }

    fun createBackup() = produceExport { exporter.createBackup() }

    private fun produceExport(block: suspend () -> File?) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null) }
        val file = block()
        _state.update {
            it.copy(
                busy = false,
                shareFile = file,
                message = if (file == null) MineMessage.EXPORT_FAILED else null,
            )
        }
    }

    fun consumeShareFile() = _state.update { it.copy(shareFile = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun requestRestore(uri: Uri) = _state.update { it.copy(restoreConfirmUri = uri) }

    fun cancelRestore() = _state.update { it.copy(restoreConfirmUri = null) }

    fun confirmRestore() {
        val uri = _state.value.restoreConfirmUri ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, restoreConfirmUri = null) }
            val success = exporter.restoreBackup(uri)
            _state.update {
                it.copy(
                    busy = false,
                    message = if (success) MineMessage.RESTORE_DONE else MineMessage.RESTORE_FAILED,
                )
            }
        }
    }

    fun setThemeMode(mode: String) = settings.setThemeMode(mode)

    fun setDynamicColor(enabled: Boolean) = settings.setDynamicColor(enabled)

    fun setHideAmounts(enabled: Boolean) = settings.setHideAmounts(enabled)

    fun setAppLock(enabled: Boolean) = settings.setAppLock(enabled)

    fun setSecureScreen(enabled: Boolean) = settings.setSecureScreen(enabled)

    fun setQuickAmounts(values: List<Long>) = settings.setQuickAmounts(values)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                MineViewModel(
                    repository = app.container.repository,
                    exporter = app.container.exporter,
                    settings = app.container.settings,
                    syncManager = app.container.syncManager,
                    syncDao = app.container.database.syncDao(),
                )
            }
        }
    }
}
