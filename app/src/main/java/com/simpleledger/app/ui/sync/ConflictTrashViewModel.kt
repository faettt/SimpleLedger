package com.simpleledger.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.data.local.dao.SectionDao
import com.simpleledger.app.data.local.dao.SyncDao
import com.simpleledger.app.data.local.entity.ConflictTrashEntity
import com.simpleledger.app.data.local.entity.RowKindValue
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.logic.TrashAggregation
import com.simpleledger.app.sync.SyncManager
import com.simpleledger.app.sync.account.SyncPrefs
import com.simpleledger.app.sync.op.OpApplier
import com.simpleledger.app.sync.op.OpRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 回收站一次性提示类型（UI 映射文案） */
enum class TrashMessage { RESTORE_OK, RESTORE_REHOME, PURGE_OK, CLEAR_OK, OP_FAILED }

/** 回收站一次性提示事件（RESTORE_REHOME 携带改换后的分区名供文案拼接） */
data class TrashEvent(val type: TrashMessage, val rehomeSectionName: String? = null)

/**
 * 回收站一行的展示投影（纯展示字段，不动实体）。
 *
 * **文案不在这里拼**（strings.xml 全部文案收口）：本类只给「谁删的 / 冲突对方是不是本机 /
 * 摘要主体名」等原料，「被 X 删除，被 Y 修改」等成句文案由 Screen 用 stringResource 组装。
 *
 * @param amountCents 账目行留底的金额（分）；null = 非账目行（分区/分类/贴图）
 * @param isIncome    账目方向（金额符号用）
 * @param deletedByName 删除方成员名；空串 = 成员不可考（UI 映射「未知成员」）
 * @param conflictActorIsSelf 冲突对方是否本机（UI 映射「本机」/「另一台设备」）
 * @param summaryName 摘要主体名（ENTRY=备注，SECTION/CATEGORY=名称；IMAGE 不用）
 * @param sectionColorIndex 原分区胶带色 0–7（F4 左色条）
 */
data class TrashUiRow(
    val entity: ConflictTrashEntity,
    val amountCents: Long?,
    val isIncome: Boolean,
    val deletedByName: String,
    val conflictActorIsSelf: Boolean,
    val summaryName: String,
    val sectionColorIndex: Int,
)

/**
 * 聚合后的展示 / 恢复单元（A1 整包口径）。
 *
 * 分区删除 = 分区 + 其下账目 + 贴图**一个包**：包头行给「谁删的 / 何时删」等文案原料，
 * [entryCount] / [imageCount] 供「分区『X』及 N 笔账目（含 M 张贴图）」标题；恢复与
 * 彻底删除都以包为单位（rows 已按 SECTION → ENTRY → IMAGE 分层序排好）。
 */
data class TrashUiGroup(
    /** TrashAggregation.GROUP_SECTION / GROUP_ENTRY / GROUP_SOLO */
    val kind: Int,
    /** 包头行投影（分区包 = 分区留底，单笔 = 账目留底，散条 = 自身） */
    val head: TrashUiRow,
    /** 包内全部留底行（恢复 / 彻底删除逐行走） */
    val rows: List<ConflictTrashEntity>,
    val entryCount: Int,
    val imageCount: Int,
) {
    /** 列表 key / 操作入口 = 包头行的 deleteOpId */
    val deleteOpId: String get() = head.entity.deleteOpId
}

/**
 * 冲突回收站 VM（U-3/R-09/R-21 + A1 整包聚合）。
 *
 * - 列表 = `observeVisibleTrash()` 经 [TrashAggregation.group] **整包聚合**：
 *   分区删除展示为一个包「分区『X』及 N 笔账目」，恢复 = 分区与账目一起回来；
 * - 恢复 / 彻底删除接 [OpApplier.restoreTrashEntry] / [purgeTrashEntry]
 *   （事务内记 TRASH_ACT），按包内分层序逐行走；
 * - 单笔恢复的原分区已死 → [LedgerRepository.rehomeRestoredEntry] 迁到存活分区最前
 *   （RestoreFallbacks）+ RESTORE_REHOME 提示；
 * - R-21 自动清理：进页时清 90 天前留底，并对到期行做照片物理释放判据
 *   （[OpApplier.releaseRetainedPhotos]，A2 照片挂起：误宽不误漏）；
 * - 手动清空 = 逐条 purge（TRASH_ACT 随同步带到其余设备）。
 */
class ConflictTrashViewModel(
    private val syncDao: SyncDao,
    private val sectionDao: SectionDao,
    private val opApplier: OpApplier,
    private val opRecorder: OpRecorder,
    private val prefs: SyncPrefs,
    private val repo: LedgerRepository,
) : ViewModel() {

    val groups: StateFlow<List<TrashUiGroup>> =
        syncDao.observeVisibleTrash()
            .map { list -> aggregate(list) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<TrashEvent?>(null)
    val message: StateFlow<TrashEvent?> = _message.asStateFlow()

    init {
        // R-21 自动清理：90 天前留底全部清除（无论 resolved 状态），不影响正常账目；
        // 清理前先把到期行抓出来做照片释放判据（A2：只有双重引用归零才物理删文件）
        viewModelScope.launch {
            runCatching {
                val cutoff = System.currentTimeMillis() - SyncManager.TRASH_RETENTION_MILLIS
                val expiring = syncDao.listTrashBefore(cutoff)
                syncDao.purgeTrashBefore(cutoff)
                opApplier.releaseRetainedPhotos(expiring)
            }
        }
    }

    /** 恢复：整包恢复（包头 + 成员账目 + 贴图按分层序逐行回插），单笔带死分区兜底 */
    fun restore(deleteOpId: String) = viewModelScope.launch {
        val group = findGroup(deleteOpId) ?: run {
            _message.update { TrashEvent(TrashMessage.OP_FAILED) }
            return@launch
        }
        // rows 已按恢复顺序（SECTION → ENTRY → IMAGE）排好：分区先回来，
        // 成员账目的引用一次到位——整包恢复不会出现挂 0 占位的中间态
        val allOk = group.rows.all { row ->
            runCatching { opApplier.restoreTrashEntry(opRecorder, row.deleteOpId) }
                .getOrDefault(false)
        }
        if (!allOk) {
            _message.update { TrashEvent(TrashMessage.OP_FAILED) }
            return@launch
        }
        // A1 单笔兜底：原分区已死（恢复后仍挂 0 占位）→ 迁到存活分区排序最前的一个
        if (group.kind == TrashAggregation.GROUP_ENTRY &&
            group.head.entity.rowKind == RowKindValue.ENTRY
        ) {
            val rehomeId = runCatching {
                repo.rehomeRestoredEntry(group.head.entity.rowSyncId)
            }.getOrNull()
            if (rehomeId != null) {
                val sectionName = runCatching { sectionDao.getById(rehomeId)?.name }.getOrNull() ?: ""
                _message.update { TrashEvent(TrashMessage.RESTORE_REHOME, sectionName) }
                return@launch
            }
        }
        _message.update { TrashEvent(TrashMessage.RESTORE_OK) }
    }

    /** 彻底删除：整包逐行 TRASH_ACT(PURGE)；逐行触发照片释放判据（A2） */
    fun purge(deleteOpId: String) = viewModelScope.launch {
        val group = findGroup(deleteOpId) ?: run {
            _message.update { TrashEvent(TrashMessage.OP_FAILED) }
            return@launch
        }
        val allOk = group.rows.all { row ->
            runCatching { opApplier.purgeTrashEntry(opRecorder, row.deleteOpId) }
                .getOrDefault(false)
        }
        _message.update { TrashEvent(if (allOk) TrashMessage.PURGE_OK else TrashMessage.OP_FAILED) }
    }

    /** 手动清空：逐条走 purge（每条记 TRASH_ACT，其余设备同步隐藏） */
    fun clearAll() = viewModelScope.launch {
        val visible = syncDao.listVisibleTrash()
        val allOk = visible.all { entry ->
            runCatching { opApplier.purgeTrashEntry(opRecorder, entry.deleteOpId) }
                .getOrDefault(false)
        }
        _message.update { TrashEvent(if (allOk) TrashMessage.CLEAR_OK else TrashMessage.OP_FAILED) }
    }

    fun clearMessage() = _message.update { null }

    // ------------------------------------------------------------------ 聚合与投影

    /** 按 deleteOpId 反查所在聚合包（列表 key = 包头 deleteOpId，先按包头匹配） */
    private fun findGroup(deleteOpId: String): TrashUiGroup? =
        groups.value.firstOrNull { group ->
            group.deleteOpId == deleteOpId || group.rows.any { it.deleteOpId == deleteOpId }
        }

    /** 可见留底 → 整包聚合 + 包头投影（Flow.map 的 lambda 可挂起，逐行查成员名安全） */
    private suspend fun aggregate(list: List<ConflictTrashEntity>): List<TrashUiGroup> =
        TrashAggregation.group(list).map { group ->
            TrashUiGroup(
                kind = group.kind,
                head = toUiRow(group.head),
                rows = group.rows,
                entryCount = group.entryCount,
                imageCount = group.imageCount,
            )
        }

    private suspend fun toUiRow(entity: ConflictTrashEntity): TrashUiRow {
        val json = runCatching { JSONObject(entity.snapshot) }.getOrElse { JSONObject() }
        // 删除方名字只取原料；成员不可考 → 空串，由 UI 映射「未知成员」（文案收口）
        val deletedByName = entity.deletedByMemberId
            ?.let { id -> runCatching { syncDao.getMember(id)?.name }.getOrNull() }
            ?: ""
        // 冲突对方只有设备 actorId、无成员映射 → 映射成「本机 / 另一台设备」两档（遗留口径见报告）
        val conflictActorIsSelf =
            entity.conflictActorId != null && entity.conflictActorId == prefs.deviceId

        val amountCents: Long?
        val isIncome: Boolean
        val summaryName: String
        val colorIndex: Int
        when (entity.rowKind) {
            RowKindValue.ENTRY -> {
                amountCents = json.optLong("amountCents", 0L)
                isIncome = json.optInt("type", 0) == 1
                summaryName = json.optString("note", "")
                colorIndex = sectionColorOf(json.optString("sectionSyncId", ""))
            }
            RowKindValue.SECTION -> {
                amountCents = null
                isIncome = false
                summaryName = json.optString("name", "")
                colorIndex = json.optInt("colorIndex", 0)
            }
            RowKindValue.CATEGORY -> {
                amountCents = null
                isIncome = false
                summaryName = json.optString("name", "")
                colorIndex = sectionColorOf(json.optString("sectionSyncId", ""))
            }
            RowKindValue.IMAGE -> {
                amountCents = null
                isIncome = false
                summaryName = ""
                colorIndex = 0
            }
            else -> {
                amountCents = null
                isIncome = false
                summaryName = ""
                colorIndex = 0
            }
        }

        return TrashUiRow(
            entity = entity,
            amountCents = amountCents,
            isIncome = isIncome,
            deletedByName = deletedByName,
            conflictActorIsSelf = conflictActorIsSelf,
            summaryName = summaryName,
            sectionColorIndex = colorIndex,
        )
    }

    /** ENTRY/CATEGORY 的原分区胶带色（F4 左色条）；分区已不在 → 兜底 0 */
    private suspend fun sectionColorOf(sectionSyncId: String): Int {
        if (sectionSyncId.isEmpty()) return 0
        return runCatching { sectionDao.getBySyncId(sectionSyncId)?.colorIndex ?: 0 }
            .getOrDefault(0)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                ConflictTrashViewModel(
                    syncDao = app.container.database.syncDao(),
                    sectionDao = app.container.database.sectionDao(),
                    opApplier = app.container.opApplier,
                    opRecorder = app.container.opRecorder,
                    prefs = app.container.syncPrefs,
                    repo = app.container.repository,
                )
            }
        }
    }
}
