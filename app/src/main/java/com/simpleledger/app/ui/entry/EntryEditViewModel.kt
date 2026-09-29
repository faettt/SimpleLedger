package com.simpleledger.app.ui.entry

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.data.local.IconMapping
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.repo.EntryDraft
import com.simpleledger.app.data.repo.GalleryExportOutcome
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.data.settings.AppSettings
import com.simpleledger.app.data.local.SectionFirstSeed
import com.simpleledger.app.logic.CategoryCandidates
import com.simpleledger.app.util.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 一张贴图：已入库的既有图，或刚选好待在缓存中入库的新图 */
data class PendingImage(
    val existingPath: String? = null,
    val pendingPath: String? = null,
) {
    val key: String get() = existingPath ?: pendingPath ?: ""
    val localPath: String? get() = existingPath ?: pendingPath
}

data class EntryEditUiState(
    val isEdit: Boolean = false,
    /** 固定的记账分区（新建=入口带入；编辑=账目所属），全程只读（FR-21/Q-07） */
    val sectionId: Long = 0,
    /** 只读展示分区（FR-22） */
    val section: SectionEntity? = null,
    val type: Int = EntryType.EXPENSE,
    val amountText: String = "",
    /** 本分区专属候选（专属在前） */
    val exclusiveCategories: List<CategoryEntity> = emptyList(),
    /** 全局候选 */
    val globalCategories: List<CategoryEntity> = emptyList(),
    /** 打开表单时为 null（Q-01：取消自动选中） */
    val selectedCategoryId: Long? = null,
    /** EC-09：不在候选内的当前分类（历史分类），保留展示、可保存、不静默改写 */
    val historicalCategory: CategoryEntity? = null,
    val entryTime: Long = System.currentTimeMillis(),
    val note: String = "",
    val images: List<PendingImage> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val savedEntryId: Long? = null,
    val notice: String? = null,
    val error: String? = null,
)

/**
 * U-13：一次性消费 saved 终态（纯函数，便于 JVM 单测钉死口径）。
 *
 * 缺陷背景：保存成功（[EntryEditViewModel.doSave]）与删除成功（[EntryEditViewModel.deleteEntry]）
 * 都会把 [EntryEditUiState.saved] 置 true 且原先永不复位；而就地编辑宿主按
 * `viewModel(key = "entry_$entryId")` 在同一返回栈条目内缓存 VM——面板关闭后再打开
 * 同一笔账时，LaunchedEffect(state.saved) 读到残留的 true 会立刻再回调一次 onSaved，
 * 编辑面板闪现即自动关闭并重复弹「已记入」，大屏上无法二次编辑同一笔账。
 *
 * 口径：saved=true → 返回「复位后的状态 + 本次保存的账目 id」；
 * saved=false → 返回 null（无可消费事件，即消费过一次后不得再次触发）。
 */
internal fun EntryEditUiState.takeSavedOutcome(): Pair<EntryEditUiState, Long?>? =
    if (saved) copy(saved = false, savedEntryId = null) to savedEntryId else null

/**
 * 记一笔 / 编辑账目的状态与动作。
 *
 * 「分区优先」后的关键变化：
 * - **分区即上下文**：VM 接收固定 [sectionIdArg]，表单内无任何改分区控件（FR-21/Q-07）；
 * - **取消分类自动选中**：打开表单 [EntryEditUiState.selectedCategoryId] 为 null（Q-01/FR-27）；
 * - **候选按 (分区, 类型) 查询**：`observeCandidates` 唯一真源，UI 不得再拼接/去重（Q-02）；
 * - **EC-09 历史分类**：编辑老账时，若当前分类不在候选内，作为独立「历史分类」保留。
 */
class EntryEditViewModel(
    private val repo: LedgerRepository,
    private val settings: AppSettings,
    private val entryId: Long,
    private val sectionIdArg: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(EntryEditUiState(isEdit = entryId > 0))
    val state: StateFlow<EntryEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val editData = if (entryId > 0) repo.getEntryFull(entryId) else null
            val resolvedSectionId = editData?.entry?.sectionId
                ?: sectionIdArg.takeIf { it > 0 }
                ?: 0L
            val type = editData?.entry?.type ?: EntryType.EXPENSE
            val section = repo.getSection(resolvedSectionId)

            _state.update {
                it.copy(
                    isEdit = entryId > 0,
                    sectionId = resolvedSectionId,
                    section = section,
                    type = type,
                    amountText = editData
                        ?.let { data -> Money.formatCents(data.entry.amountCents).replace(",", "") }
                        ?: "",
                    entryTime = editData?.entry?.entryTime ?: System.currentTimeMillis(),
                    note = editData?.entry?.note ?: "",
                    images = editData?.images?.map { img -> PendingImage(existingPath = img.filePath) }
                        ?: emptyList(),
                    selectedCategoryId = editData?.entry?.categoryId,
                    loading = false,
                )
            }
            loadCandidates(resolvedSectionId, type, editData?.entry?.categoryId)
        }
    }

    /**
     * 按 (分区, 类型) 重载候选，并处理 EC-09：若 [keepSelection] 不在候选内，则作为历史分类保留。
     */
    private suspend fun loadCandidates(sectionId: Long, type: Int, keepSelection: Long?) {
        val candidates = repo.observeCandidates(sectionId, type).first()
        val partition = CategoryCandidates.partition(candidates)
        val inCandidates = keepSelection != null && candidates.any { it.id == keepSelection }
        val historical = if (keepSelection != null && !inCandidates) {
            repo.getCategory(keepSelection)
        } else {
            null
        }
        _state.update { old ->
            old.copy(
                exclusiveCategories = partition.exclusive,
                globalCategories = partition.global,
                selectedCategoryId = keepSelection,
                historicalCategory = historical,
            )
        }
    }

    /** 切类型：清空分类选择并重载候选（历史分类属于旧类型，一并清掉） */
    fun setType(type: Int) {
        if (_state.value.type == type) return
        _state.update { it.copy(type = type, selectedCategoryId = null, historicalCategory = null) }
        viewModelScope.launch { loadCandidates(_state.value.sectionId, type, null) }
    }

    fun selectCategory(id: Long) {
        val s = _state.value
        val isCandidate = s.exclusiveCategories.any { it.id == id } ||
            s.globalCategories.any { it.id == id }
        _state.update {
            it.copy(
                selectedCategoryId = id,
                historicalCategory = if (isCandidate) null else it.historicalCategory,
            )
        }
    }

    /** 金额输入限制：数字 + 一个小数点 + 最多两位小数 */
    fun setAmount(text: String) {
        val cleaned = text.filter { it.isDigit() || it == '.' }
        val valid = cleaned.contains('.').let { hasDot ->
            if (hasDot) {
                val parts = cleaned.split('.')
                parts.size <= 2 && parts.getOrNull(1)?.length?.let { it <= 2 } ?: true
            } else true
        }
        if (valid && cleaned.length <= 12) {
            _state.update { it.copy(amountText = cleaned) }
        }
    }

    fun setNote(text: String) = _state.update { it.copy(note = text) }

    /**
     * EC-05：就地新建分类——类型默认跟随当前表单的支出/收入，归属默认当前分区；
     * 建好后重载候选并自动选中新分类。
     *
     * ⚠️ v4：就地新建**不带图标选择**（这是快速路径，只有一个名字输入框），
     * 因此新分类落到默认图标 43 = tag —— 与数据库迁移的兜底值一致。
     * 用户后续可在分类管理里换图标。
     */
    fun createCategoryInline(name: String) {
        if (name.isBlank()) {
            _state.update { it.copy(error = "分类名称不能为空") }
            return
        }
        val s = _state.value
        viewModelScope.launch {
            runCatching {
                val id = repo.saveCategory(
                    CategoryEntity(
                        name = name.trim(),
                        // 默认图标 43 = tag（快速路径不带图标选择，见上方注释）
                        iconId = IconMapping.DEFAULT_CATEGORY_ICON_ID,
                        type = s.type,
                        sectionId = s.sectionId.takeIf { it > 0 },
                    )
                )
                loadCandidates(s.sectionId, s.type, id)
            }.onFailure { e -> _state.update { it.copy(error = e.message ?: "分类创建失败") } }
        }
    }

    fun setDate(date: LocalDate) = _state.update {
        // P0 修复：LocalTime.ofInstant 需 API 31（LocalDate.ofInstant 需 API 34），minSdk 26 上
        // Android 8~13 会 NoSuchMethodError 闪退。atZone().toLocalTime() 自 API 26 可用，
        // 与下方 setDateFromUtcMillis 同一安全写法。
        val time = Instant.ofEpochMilli(it.entryTime).atZone(ZoneId.systemDefault()).toLocalTime()
        it.copy(entryTime = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    fun setTime(time: LocalTime) = _state.update {
        val date = Instant.ofEpochMilli(it.entryTime).atZone(ZoneId.systemDefault()).toLocalDate()
        it.copy(entryTime = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    /** DatePicker 传回的是 UTC 毫秒，需要转成本地日期 */
    fun setDateFromUtcMillis(utcMillis: Long) {
        val date = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        setDate(date)
    }

    /** 选好图片后立即读入缓存（此时 Uri 读权限有效），失败会提示用户 */
    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imported = uris.map { uri -> repo.importPendingImage(uri) }
            val success = imported.filterNotNull()
            val failedCount = imported.size - success.size
            _state.update { old ->
                old.copy(
                    images = old.images + success.map { PendingImage(pendingPath = it) },
                    error = if (failedCount > 0) "有 $failedCount 张图片读取失败，请换一张试试" else old.error,
                )
            }
        }
    }

    fun removeImage(index: Int) = _state.update {
        it.copy(images = it.images.filterIndexed { i, _ -> i != index })
    }

    /** 快捷金额：点一下直接填入（覆盖当前值），不做累加 */
    fun fillAmount(cents: Long) {
        _state.update { it.copy(amountText = Money.formatCents(cents).replace(",", "")) }
    }

    fun save() = doSave(continueAfter = false)

    /**
     * 保存并再记一笔（EC-07）：保留**分区 + 类型 + 时间**，清空**分类选择**、金额、备注与贴图；
     * 提示条显示「已记入 🔨 装修 · ¥XXX」。
     */
    fun saveAndContinue() = doSave(continueAfter = true)

    private fun doSave(continueAfter: Boolean) {
        val current = _state.value
        if (current.saving) return

        val cents = Money.parseToCents(current.amountText)
        if (cents == null) {
            _state.update { it.copy(error = "请输入有效金额") }
            return
        }
        val sectionId = current.sectionId.takeIf { it > 0 } ?: run {
            _state.update { it.copy(error = "缺少分区上下文") }
            return
        }

        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                // B2：记账不选分类 = 落「未分类」哨兵（不再硬拦「请选择分类」）。
                // 哨兵解析失败（极端：补种也失败）按保存失败提示，绝不静默换分类。
                val unclassifiedId = repo.unclassifiedCategoryId(current.type)
                val categoryId = SectionFirstSeed.Unclassified.saveCategoryId(
                    current.selectedCategoryId,
                    unclassifiedId,
                )
                repo.saveEntry(
                    EntryDraft(
                        id = if (entryId > 0) entryId else null,
                        type = current.type,
                        amountCents = cents,
                        categoryId = categoryId,
                        sectionId = sectionId,
                        entryTime = current.entryTime,
                        note = current.note,
                        keptImagePaths = current.images.mapNotNull { it.existingPath },
                        pendingImagePaths = current.images.mapNotNull { it.pendingPath },
                    )
                )
            }.onSuccess { savedId ->
                if (continueAfter) {
                    val section = current.section
                    val amountLabel = if (settings.hideAmounts.value) {
                        "金额已隐藏"
                    } else {
                        Money.formatWithSymbol(cents)
                    }
                    val label = buildString {
                        // v4：不再拼 emoji 前缀（F4——分区身份由色条/图标承担，文字只留名称）
                        if (section != null) append("${section.name} · ")
                        append(amountLabel)
                    }
                    _state.update {
                        it.copy(
                            saving = false,
                            amountText = "",
                            note = "",
                            images = emptyList(),
                            selectedCategoryId = null,
                            historicalCategory = null,
                            savedEntryId = savedId,
                            notice = "已记入 $label",
                            error = null,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(saving = false, saved = true, savedEntryId = savedId, error = null)
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(saving = false, error = e.message ?: "保存失败") }
            }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /**
     * U-13：消费 saved 终态并复位。宿主在发出 onSaved 回调**之前**调用（先取局部变量、
     * 先消费、再回调），保证「保存/删除完成」事件在整个 VM 生命周期内至多触发一次——
     * 即便 VM 被同一返回栈条目按 key 缓存复用（大屏就地编辑），也不会把上一次的终态
     * 带进下一次打开。
     */
    fun consumeSaved() {
        _state.update { it.takeSavedOutcome()?.first ?: it }
    }

    fun deleteEntry() {
        if (entryId <= 0) return
        viewModelScope.launch {
            runCatching { repo.deleteEntry(entryId) }
                .onSuccess { _state.update { it.copy(saved = true, error = null) } }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: "删除失败") } }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------- 「保存到相册」透传（D3 分档，签名见 ImageStorage.exportImageToGallery） ----------

    suspend fun exportImageToGallery(imagePath: String): GalleryExportOutcome =
        repo.exportImageToGallery(imagePath)

    suspend fun writeExportImageToSafTarget(target: Uri, imagePath: String): Boolean =
        repo.writeExportImageToSafTarget(target, imagePath)

    companion object {
        fun factory(entryId: Long, sectionId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as com.simpleledger.app.LedgerApp
                EntryEditViewModel(app.container.repository, app.container.settings, entryId, sectionId)
            }
        }
    }
}
