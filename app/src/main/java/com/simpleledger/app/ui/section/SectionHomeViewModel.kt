package com.simpleledger.app.ui.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.SectionTotal
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.data.repo.SectionDeleteImpact
import com.simpleledger.app.util.DateTimes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

data class SectionHomeUiState(
    /** 固定当前自然月（Q-10，无切换） */
    val month: YearMonth = YearMonth.now(),
    val cards: List<SectionTotal> = emptyList(),
    /** 排序态（Q-14 / 裁定 C-3）：由首屏右上「排序」按钮切换 */
    val reorderMode: Boolean = false,
    /** 0 分区 → 空态（EC-04） */
    val isEmpty: Boolean = false,
)

/**
 * 分区首屏状态与动作。
 *
 * 数据源恒为 `observeSectionHome`（**LEFT JOIN 版**），空分区也必须出现（Q-06 / EC-04）。
 * 预算进度条配色复用既有纯函数 `BudgetCalculator`（口径与统计页一致）。
 */
class SectionHomeViewModel(private val repo: LedgerRepository) : ViewModel() {

    // Q-10：首屏固定为当前自然月，不做月份切换
    private val month: YearMonth = YearMonth.now()

    private val reorderMode = MutableStateFlow(false)

    val state: StateFlow<SectionHomeUiState> = combine(
        repo.observeSectionHome(
            DateTimes.monthRange(month).first,
            DateTimes.monthRange(month).second,
        ),
        reorderMode,
    ) { cards, reorder ->
        SectionHomeUiState(
            month = month,
            cards = cards,
            reorderMode = reorder,
            isEmpty = cards.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionHomeUiState(month = month))

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    fun toggleReorder() = reorderMode.update { !it }

    fun saveSection(id: Long?, name: String, emoji: String, note: String, budgetCents: Long) {
        if (name.isBlank()) {
            _error.value = "分区名称不能为空"
            return
        }
        viewModelScope.launch {
            runCatching {
                repo.saveSection(
                    SectionEntity(
                        id = id ?: 0,
                        name = name.trim(),
                        emoji = emoji,
                        note = note.trim(),
                        budgetCents = budgetCents,
                    )
                )
            }.onFailure { e -> _error.value = e.message ?: "保存失败" }
        }
    }

    fun deleteSection(id: Long) {
        viewModelScope.launch {
            repo.deleteSection(id).onFailure { e -> _error.value = e.message ?: "删除失败" }
        }
    }

    /** 分区排序：在同一份真实分区列表内交换相邻两项后整表重写 sortOrder */
    fun moveSection(id: Long, direction: Int) {
        viewModelScope.launch {
            val list = repo.observeSections().first().toMutableList()
            val index = list.indexOfFirst { it.id == id }
            val target = index + direction
            if (index < 0 || target !in list.indices) return@launch
            list[index] = list[target].also { list[target] = list[index] }
            repo.reorderSections(list)
        }
    }

    /** 删除前的影响描述（供确认框） */
    suspend fun impactOf(id: Long): SectionDeleteImpact = repo.sectionDeleteImpact(id)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                SectionHomeViewModel(app.container.repository)
            }
        }
    }
}
