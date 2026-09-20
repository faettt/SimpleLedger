package com.simpleledger.app.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.repo.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GlobalCategoriesUiState(
    /** sectionId IS NULL 的分类 */
    val expenseCategories: List<CategoryEntity> = emptyList(),
    val incomeCategories: List<CategoryEntity> = emptyList(),
)

/**
 * 全局分类管理状态与动作（N13 / Q-03）。
 *
 * 全局分类 = 用户资产（一次定义、到处可用，FR-01/US-4）。排序作用域为 (类型, 全局)，
 * 与任一分区专属分类的排序互相独立（EC-01）。
 */
class GlobalCategoriesViewModel(private val repo: LedgerRepository) : ViewModel() {

    val state: StateFlow<GlobalCategoriesUiState> = combine(
        repo.observeGlobalCategories(EntryType.EXPENSE),
        repo.observeGlobalCategories(EntryType.INCOME),
    ) { expense, income ->
        GlobalCategoriesUiState(expenseCategories = expense, incomeCategories = income)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalCategoriesUiState())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    fun saveCategory(id: Long?, name: String, iconId: Int, type: Int) {
        if (name.isBlank()) {
            _error.value = "分类名称不能为空"
            return
        }
        viewModelScope.launch {
            runCatching {
                val existing = id?.let { repo.getCategory(it) }
                repo.saveCategory(
                    CategoryEntity(
                        id = id ?: 0,
                        name = name.trim(),
                        iconId = iconId,
                        type = type,
                        // 全局分类：sectionId 恒为 null（严禁用 0L 表示全局）
                        sectionId = existing?.sectionId,
                        sortOrder = existing?.sortOrder ?: 0,
                    )
                )
            }.onFailure { e -> _error.value = e.message ?: "保存失败" }
        }
    }

    fun deleteCategory(id: Long) {
        viewModelScope.launch {
            repo.deleteCategory(id).onFailure { e -> _error.value = e.message ?: "删除失败" }
        }
    }

    fun moveCategory(id: Long, type: Int, direction: Int) {
        val source = if (type == EntryType.EXPENSE) state.value.expenseCategories else state.value.incomeCategories
        val list = source.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0 || target !in list.indices) return
        list[index] = list[target].also { list[target] = list[index] }
        viewModelScope.launch { repo.reorderCategories(list) }
    }

    suspend fun categoryImpact(id: Long) = repo.categoryDeleteImpact(id)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                GlobalCategoriesViewModel(app.container.repository)
            }
        }
    }
}
