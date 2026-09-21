package com.simpleledger.app.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.repo.DeletedEntrySnapshot
import com.simpleledger.app.data.repo.EntryDraft
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.data.settings.AppSettings
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.YearMonth

/** 明细页筛选条件 */
data class LedgerFilters(
    val sectionId: Long? = null,
    val categoryId: Long? = null,
    val type: Int? = null,
)

/** 按天分组的账目 */
data class DayGroup(
    val date: LocalDate,
    val entries: List<EntryFull>,
    val expenseCents: Long,
    val incomeCents: Long,
)

data class LedgerUiState(
    val month: YearMonth = YearMonth.now(),
    val filters: LedgerFilters = LedgerFilters(),
    val sections: List<SectionEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val groups: List<DayGroup> = emptyList(),
    val expenseCents: Long = 0,
    val incomeCents: Long = 0,
    val isEmpty: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(
    private val repo: LedgerRepository,
    private val settings: AppSettings,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val filters = MutableStateFlow(LedgerFilters())

    private val entriesFlow = combine(month, filters) { m, f ->
        val (start, end) = DateTimes.monthRange(m)
        Triple(start, end, f)
    }.flatMapLatest { (start, end, f) ->
        repo.observeEntries(start, end, f.sectionId, f.categoryId, f.type)
    }

    private val totalsFlow = combine(month, filters) { m, f ->
        val (start, end) = DateTimes.monthRange(m)
        Triple(start, end, f)
    }.flatMapLatest { (start, end, f) ->
        repo.observeTypeTotals(start, end, f.sectionId, f.categoryId)
    }

    val state: StateFlow<LedgerUiState> = combine(
        month,
        filters,
        entriesFlow,
        totalsFlow,
        combine(repo.observeSections(), repo.observeCategories(EntryType.EXPENSE), repo.observeCategories(EntryType.INCOME)) { s, ec, ic -> Triple(s, ec + ic, Unit) },
    ) { m, f, entries, totals, (sections, categories, _) ->
        val groups = entries
            .groupBy { DateTimes.toLocalDate(it.entry.entryTime) }
            .map { (date, list) ->
                DayGroup(
                    date = date,
                    entries = list,
                    expenseCents = list.filter { it.entry.type == EntryType.EXPENSE }.sumOf { it.entry.amountCents },
                    incomeCents = list.filter { it.entry.type == EntryType.INCOME }.sumOf { it.entry.amountCents },
                )
            }
            .sortedByDescending { it.date }
        LedgerUiState(
            month = m,
            filters = f,
            sections = sections,
            categories = categories,
            groups = groups,
            expenseCents = totals.firstOrNull { it.type == EntryType.EXPENSE }?.total ?: 0,
            incomeCents = totals.firstOrNull { it.type == EntryType.INCOME }?.total ?: 0,
            isEmpty = entries.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    fun prevMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { it.plusMonths(1) }
    fun goToday() = month.update { YearMonth.now() }

    /* ---------- 全局搜索 ---------- */

    private val searchQuery = MutableStateFlow("")
    private val searchType = MutableStateFlow<Int?>(null)
    private val searchActiveState = MutableStateFlow(false)

    val searchActive: StateFlow<Boolean> = searchActiveState.asStateFlow()

    /** 当前搜索关键词（UI 用于回显与「无结果」文案） */
    val searchKeyword: StateFlow<String> = searchQuery.asStateFlow()

    /** 当前搜索的收支过滤（null 表示不限） */
    val searchFilterType: StateFlow<Int?> = searchType.asStateFlow()

    /** 进入 / 退出搜索：退出时清空关键词与过滤，避免下次进入残留 */
    fun openSearch() {
        searchActiveState.value = true
    }

    fun closeSearch() {
        searchActiveState.value = false
        searchQuery.value = ""
        searchType.value = null
    }

    fun setSearchQuery(q: String) = searchQuery.update { q }

    /** 收支过滤三态循环：null → 支出 → 收入 → null */
    fun toggleSearchType() = searchType.update { cur ->
        when (cur) {
            null -> EntryType.EXPENSE
            EntryType.EXPENSE -> EntryType.INCOME
            else -> null
        }
    }

    fun setSearchType(type: Int?) = searchType.update { type }

    /** 搜索结果：按月份分组、时间倒序；关键词不足 1 字不查 */
    val searchResults: StateFlow<List<DayGroup>> =
        combine(searchQuery, searchType) { q, t -> q.trim() to t }
            .flatMapLatest { (q, t) ->
                if (q.isEmpty()) flowOf(emptyList()) else repo.observeSearch(q, t)
            }
            .map { entries ->
                entries
                    .groupBy { DateTimes.toLocalDate(it.entry.entryTime) }
                    .map { (date, list) ->
                        DayGroup(
                            date = date,
                            entries = list,
                            expenseCents = list.filter { it.entry.type == EntryType.EXPENSE }.sumOf { it.entry.amountCents },
                            incomeCents = list.filter { it.entry.type == EntryType.INCOME }.sumOf { it.entry.amountCents },
                        )
                    }
                    .sortedByDescending { it.date }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 结果是否可能被 DAO 的 `LIMIT` 截断。
     *
     * 截断必须让用户感知：搜到 200 条却静默截尾，用户找不到目标账目时只会得出
     * 「我没记过这笔」的错误结论。命中数恰好等于上限时无法区分「刚好 200 条」与
     * 「超过 200 条被截」，按后者提示更安全——多一句提示的成本远低于一次误判。
     */
    val searchTruncated: StateFlow<Boolean> = searchResults
        .map { days -> days.sumOf { it.entries.size } >= SEARCH_RESULT_LIMIT }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /* ---------- 大屏列表–详情双栏 ---------- */

    private val selectedEntryId = MutableStateFlow<Long?>(null)

    /** 右侧详情面板当前选中的账目；编辑后自动刷新 */
    val selectedEntry: StateFlow<EntryFull?> = selectedEntryId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeEntryFull(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectEntry(id: Long?) {
        selectedEntryId.value = id
    }

    fun filterSection(sectionId: Long?) =
        filters.update { it.copy(sectionId = if (it.sectionId == sectionId) null else sectionId) }

    fun filterType(type: Int?) =
        filters.update { it.copy(type = if (it.type == type) null else type) }

    fun filterCategory(categoryId: Long?) =
        filters.update { it.copy(categoryId = if (it.categoryId == categoryId) null else categoryId) }

    /** 一键清除全部筛选（空态里也提供这个入口） */
    fun clearFilters() = filters.update { LedgerFilters() }

    /**
     * 生成保存成功的提示文案，如「🔨 装修 · ¥2,000.00」。
     * 返回 null 表示条目不存在（例如刚被撤销删除）。
     *
     * 隐私模式：提示条会亮在屏幕中央且停留数秒，是全屏最显眼的金额位；此处读实时开关
     * （`settings.hideAmounts`），hidden 时把金额位换成「金额已隐藏」，形成
     * 「🔨 装修 · 金额已隐藏」——否则防住了列表却防不住提示条。
     */
    suspend fun describeEntry(entryId: Long): String? {
        val full = repo.getEntryFull(entryId) ?: return null
        val amount = if (settings.hideAmounts.value) "金额已隐藏" else Money.formatWithSymbol(full.entry.amountCents)
        val section = full.section
        // v4：读屏文案不拼 emoji（TalkBack 念 emoji 是噪音）；分区身份由 UI 图标/色条表达
        return if (section != null) "${section.name} · $amount" else amount
    }

    /** 撤销删除：把刚保存的账目删掉（提示条里的「撤销」） */
    suspend fun undoDelete(entryId: Long) {
        runCatching { repo.deleteEntry(entryId) }
    }

    /**
     * 长按 / 详情栏删除：返回快照，供 4 秒撤销窗口内完整恢复（含贴图）。
     * 与「撤销刚保存的账目」不同——那时账目还在，这里账目已删，必须靠快照重建。
     */
    suspend fun deleteEntryWithSnapshot(entryId: Long): DeletedEntrySnapshot? {
        val snapshot = repo.deleteEntryWithSnapshot(entryId)
        if (selectedEntryId.value == entryId) selectedEntryId.value = null
        return snapshot
    }

    suspend fun restoreDeleted(snapshot: DeletedEntrySnapshot) {
        repo.restoreEntry(snapshot)
    }

    /**
     * 长按菜单：切换**核对**维度（规范 §3.5 入口）。
     *
     * 开关式而非只置真：账目行上的 ✓ 是「这笔跟过了」，误点了要能撤回来。
     * 与报销维度分开两次调用 —— 两个字段正交，一次只动一个。
     */
    suspend fun setReconciled(entryId: Long, value: Boolean) =
        repo.setEntryReconciled(entryId, value)

    /** 长按菜单：设置**报销**维度（不适用 / 待报销 / 已报销）。 */
    suspend fun setReimburseState(entryId: Long, value: Int) =
        repo.setEntryReimburseState(entryId, value)

    /** 撤销窗口结束，清掉暂存的贴图文件 */
    suspend fun discardParkedImages() = repo.discardParkedImages()

    /** 长按菜单：复制一笔 —— 金额 / 分类 / 分区 / 备注照搬，时间改为此刻，贴图不带 */
    suspend fun duplicateEntry(entryId: Long): Long? {
        val full = repo.getEntryFull(entryId) ?: return null
        return runCatching {
            repo.saveEntry(
                EntryDraft(
                    id = null,
                    type = full.entry.type,
                    amountCents = full.entry.amountCents,
                    categoryId = full.entry.categoryId,
                    sectionId = full.entry.sectionId,
                    entryTime = System.currentTimeMillis(),
                    note = full.entry.note,
                )
            )
        }.getOrNull()
    }

    /**
     * 长按菜单：移动到其它分区。
     *
     * [newCategoryId] 非空时**同时改分类**：这是修复 P1-1 的关键——当原分类是其它分区的
     * 专属分类、无法随账目平移到目标分区时，UI 会让用户为目标分区重选一个同类型分类，
     * 再一次性提交「改分区 + 改分类」。两者在同一 `saveEntry` 事务内完成，不会出现
     * 「分区=旅行、分类只属于装修」的中间态。为 null 时仅改分区、保留原分类。
     *
     * 贴图记录保持不变（`keptImagePaths`），不会因为移动而丢图。
     */
    suspend fun moveEntryToSection(
        entryId: Long,
        sectionId: Long,
        newCategoryId: Long? = null,
    ): Boolean {
        val full = repo.getEntryFull(entryId) ?: return false
        return runCatching {
            repo.saveEntry(
                EntryDraft(
                    id = entryId,
                    type = full.entry.type,
                    amountCents = full.entry.amountCents,
                    categoryId = newCategoryId ?: full.entry.categoryId,
                    sectionId = sectionId,
                    entryTime = full.entry.entryTime,
                    note = full.entry.note,
                    keptImagePaths = full.images.map { it.filePath },
                )
            )
        }.isSuccess
    }

    companion object {
        /**
         * 搜索命中上限，须与 `EntryDao.observeSearch` 的 `LIMIT` 保持一致。
         * 两处改动必须同步——数值不一致会让截断提示要么不出现、要么误报。
         */
        const val SEARCH_RESULT_LIMIT = 200

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as com.simpleledger.app.LedgerApp
                LedgerViewModel(app.container.repository, app.container.settings)
            }
        }
    }
}
