package com.simpleledger.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.MemberEntity
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.sync.SyncManager
import com.simpleledger.app.sync.account.SyncPrefs
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 成员管理页一次性提示（UI 映射文案） */
enum class MemberManageMessage { NAME_REQUIRED, NAME_TAKEN, CLAIM_OK, RENAME_OK, HIDE_OK, UNHIDE_OK, OP_FAILED }

/** 一次性事件：提示类型 + 涉及的成员名（文案格式化用） */
data class MemberManageEvent(val message: MemberManageMessage, val name: String = "")

/**
 * 成员管理页 VM（U-2/R-20/U-10）。
 *
 * - 首次认领：[SyncManager.claimMember]（全书唯一名；**撞名不复用**——认领是
 *   「给自己起名」，与 setupAccount 的同名复用语义不同，撞名提示换名）；
 * - 改名 / 隐藏走 [LedgerRepository]（与业务写同一套「事务 + 埋点」范式，
 *   MEMBER UPSERT 随操作日志同步到其余设备）；
 * - 改名对历史账目即时生效（R-20）：账目经 `EntryFull.member` 关系取名，无需回写。
 */
class MemberManageViewModel(
    private val syncDao: SyncDao,
    private val repository: LedgerRepository,
    private val syncManager: SyncManager,
    private val prefs: SyncPrefs,
) : ViewModel() {

    val members: StateFlow<List<MemberEntity>> =
        syncDao.observeMembers()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selfMemberId = MutableStateFlow(prefs.selfMemberId)
    val selfMemberId: StateFlow<String?> = _selfMemberId.asStateFlow()

    /** 认领撞名（U-2 冲突提示换名） */
    private val _claimConflict = MutableStateFlow(false)
    val claimConflict: StateFlow<Boolean> = _claimConflict.asStateFlow()

    /**
     * 一次性事件通道（U-18）：StateFlow 承载会合并吞掉同值事件、展示中离页回页重放；
     * Channel「接收即消费」两症皆除（机制与缺陷说明见 [UiEventChannel]）。
     * 页面单消费者：LaunchedEffect for 循环。
     */
    private val eventBus = UiEventChannel<MemberManageEvent>()
    val events: ReceiveChannel<MemberManageEvent> = eventBus.events

    /** 首次认领成员名（全书唯一；撞名提示换名，不静默复用他人身份） */
    fun claim(name: String) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            eventBus.send(MemberManageEvent(MemberManageMessage.NAME_REQUIRED))
            return@launch
        }
        if (syncDao.findMemberByName(trimmed) != null) {
            _claimConflict.update { true }
            return@launch
        }
        val syncId = syncManager.claimMember(trimmed)
        if (syncId == null) {
            eventBus.send(MemberManageEvent(MemberManageMessage.OP_FAILED))
        } else {
            _selfMemberId.update { syncId }
            _claimConflict.update { false }
            eventBus.send(MemberManageEvent(MemberManageMessage.CLAIM_OK, trimmed))
        }
    }

    fun clearClaimConflict() = _claimConflict.update { false }

    /** 本机 / 任意成员改名（R-20：历史账目即时生效）；撞名拒绝 */
    fun rename(syncId: String, newName: String) = viewModelScope.launch {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            eventBus.send(MemberManageEvent(MemberManageMessage.NAME_REQUIRED))
            return@launch
        }
        val ok = repository.renameMember(syncId, trimmed)
        eventBus.send(
            if (ok) {
                MemberManageEvent(MemberManageMessage.RENAME_OK, trimmed)
            } else {
                MemberManageEvent(MemberManageMessage.NAME_TAKEN)
            },
        )
    }

    /** 隐藏 / 取消隐藏（成员可隐藏不可删） */
    fun setHidden(syncId: String, hidden: Boolean) = viewModelScope.launch {
        val name = members.value.firstOrNull { it.syncId == syncId }?.name ?: ""
        val ok = repository.setMemberHidden(syncId, hidden)
        eventBus.send(
            when {
                !ok -> MemberManageEvent(MemberManageMessage.OP_FAILED)
                hidden -> MemberManageEvent(MemberManageMessage.HIDE_OK, name)
                else -> MemberManageEvent(MemberManageMessage.UNHIDE_OK, name)
            },
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                MemberManageViewModel(
                    syncDao = app.container.database.syncDao(),
                    repository = app.container.repository,
                    syncManager = app.container.syncManager,
                    prefs = app.container.syncPrefs,
                )
            }
        }
    }
}
