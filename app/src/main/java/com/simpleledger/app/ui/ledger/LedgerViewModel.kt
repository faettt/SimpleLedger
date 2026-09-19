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
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
class LedgerViewModel(private val repo: LedgerRepository) : ViewModel() {

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
     */
    suspend fun describeEntry(entryId: Long): String? {
        val full = repo.getEntryFull(entryId) ?: return null
        val amount = Money.formatWithSymbol(full.entry.amountCents)
        val section = full.section
        return if (section != null) "${section.emoji} ${section.name} · $amount" else amount
    }

    /** 撤销删除：把刚保存的账目删掉（提示条里的「撤销」） */
    suspend fun undoDelete(entryId: Long) {
        runCatching { repo.deleteEntry(entryId) }
    }

    /** 大屏详情栏里确认删除：删除后清空选中，避免详情栏指向已不存在的账目 */
    suspend fun deleteEntryNow(entryId: Long) {
        runCatching { repo.deleteEntry(entryId) }
        selectedEntryId.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as com.simpleledger.app.LedgerApp
                LedgerViewModel(app.container.repository)
            }
        }
    }
}
