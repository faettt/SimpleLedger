package com.simpleledger.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionTotal
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.logic.CategoryShare
import com.simpleledger.app.logic.SectionShare
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
    /** 分类金额（横向条形图），已按金额降序、未做合并（用户裁定：全部展开 + 金额标注） */
    val shares: List<CategoryShare> = emptyList(),
    /** 分区占比（分区占比环图），支出为 0 的分区已剔除 */
    val sectionShares: List<SectionShare> = emptyList(),
    val sections: List<SectionTotal> = emptyList(),
    /** 双图联动：当前被点选的分区；null = 未选中（条形图显示全部分类） */
    val selectedSectionId: Long? = null,
    val daily: List<Pair<Int, Long>> = emptyList(),
    val daysInMonth: Int = YearMonth.now().lengthOfMonth(),
    val isEmpty: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(private val repo: LedgerRepository) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    /** 双图联动（§2.5 G4）：环图上被点选的分区。切月时必须清掉，见 [selectSection]。 */
    private val selectedSection = MutableStateFlow<Long?>(null)

    private val scoped = month.flatMapLatest { m ->
        val (start, end) = DateTimes.monthRange(m)
        combine(
            repo.observeTypeTotals(start, end),
            repo.observeCategoryTotals(EntryType.EXPENSE, start, end),
            repo.observeEntries(start, end),
            repo.observeSectionHome(start, end),
        ) { totals, categoryTotals, entries, sectionTotals ->
            StatsUiState(
                month = m,
                expenseCents = totals.firstOrNull { it.type == EntryType.EXPENSE }?.total ?: 0,
                incomeCents = totals.firstOrNull { it.type == EntryType.INCOME }?.total ?: 0,
                // 分类条形图：**全部展开**（用户裁定），不做小项合并 ——
                // 横向条形图的短条本身可读，且每根条右侧都标了金额（兜底②），
                // 不再需要旧版「小扇区并入其他」的机制（那是写给角度图的）。
                shares = StatsCalculator.categoryShares(categoryTotals),
                // 分区环图：扇区 = 分区，只按支出聚合、支出为 0 的分区剔除
                sectionShares = StatsCalculator.sectionShares(sectionTotals),
                sections = sectionTotals,
                daily = StatsCalculator.dailyExpense(entries.map { it.entry })
                    .map { (date, cents) -> date.dayOfMonth to cents },
                daysInMonth = m.lengthOfMonth(),
                isEmpty = entries.isEmpty(),
            )
        }
    }

    /**
     * 选中态在**这里**才与数据流合并，而不是塞进上面 `combine` 的某个分支里。
     *
     * ⚠️ 实测踩过的坑：如果把 `selectedSection.value` 直接读进上面那个 combine 的
     * 结果构造里，选中态就**不在任何流的观察名单上** —— 点扇区后
     * `selectedSection` 变了、combine 却不重跑，UI 纹丝不动
     * （真机上点图例行与点扇区都毫无反应，就是这个原因）。
     */
    val state: StateFlow<StatsUiState> = combine(scoped, selectedSection) { data, selected ->
        data.copy(selectedSectionId = selected)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    /** 双图联动：点环图某分区 → 条形图缩为该分区内的分类。再点同一扇区取消。 */
    fun selectSection(sectionId: Long) {
        selectedSection.update { current -> if (current == sectionId) null else sectionId }
    }

    fun prevMonth() {
        // 切月必须清掉选中：上个月选中的分区在新的月份里可能一分没花，
        // 若带着选中态过去，条形图会显示成空——用户会以为是数据丢了。
        selectedSection.update { null }
        month.update { it.minusMonths(1) }
    }

    fun nextMonth() {
        selectedSection.update { null }
        month.update { it.plusMonths(1) }
    }

    fun goToday() {
        selectedSection.update { null }
        month.update { YearMonth.now() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                StatsViewModel(app.container.repository)
            }
        }
    }
}
