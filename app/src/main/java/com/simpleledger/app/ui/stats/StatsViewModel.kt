package com.simpleledger.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.data.local.entity.CategoryTotal
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.logic.CategoryShare
import com.simpleledger.app.logic.StatsCalculator
import com.simpleledger.app.util.DateTimes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.YearMonth

data class StatsUiState(
    val month: YearMonth = YearMonth.now(),
    val expenseCents: Long = 0,
    val incomeCents: Long = 0,
    val shares: List<CategoryShare> = emptyList(),
    val daily: List<Pair<Int, Long>> = emptyList(),
    val daysInMonth: Int = YearMonth.now().lengthOfMonth(),
    val isEmpty: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(private val repo: LedgerRepository) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    private val scoped = month.flatMapLatest { m ->
        val (start, end) = DateTimes.monthRange(m)
        combine(
            repo.observeTypeTotals(start, end),
            repo.observeCategoryTotals(EntryType.EXPENSE, start, end),
            repo.observeEntries(start, end),
        ) { totals, categoryTotals, entries ->
            StatsUiState(
                month = m,
                expenseCents = totals.firstOrNull { it.type == EntryType.EXPENSE }?.total ?: 0,
                incomeCents = totals.firstOrNull { it.type == EntryType.INCOME }?.total ?: 0,
                // 读图兜底①在这里做，而不是在图表组件里：合并后的列表同时喂给
                // 环图、图例和读屏串，三处共用一份数据，不会各画各的。
                shares = StatsCalculator.mergeSmallShares(
                    StatsCalculator.categoryShares(categoryTotals)
                ),
                daily = StatsCalculator.dailyExpense(entries.map { it.entry })
                    .map { (date, cents) -> date.dayOfMonth to cents },
                daysInMonth = m.lengthOfMonth(),
                isEmpty = entries.isEmpty(),
            )
        }
    }

    val state: StateFlow<StatsUiState> = scoped
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun prevMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { it.plusMonths(1) }
    fun goToday() = month.update { YearMonth.now() }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                StatsViewModel(app.container.repository)
            }
        }
    }
}
