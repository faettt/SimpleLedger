package com.simpleledger.app.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.repo.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ManageUiState(
    val sections: List<SectionEntity> = emptyList(),
    val expenseCategories: List<CategoryEntity> = emptyList(),
    val incomeCategories: List<CategoryEntity> = emptyList(),
)

class ManageViewModel(private val repo: LedgerRepository) : ViewModel() {

    val state: StateFlow<ManageUiState> = kotlinx.coroutines.flow.combine(
        repo.observeSections(),
        repo.observeCategories(EntryType.EXPENSE),
        repo.observeCategories(EntryType.INCOME),
    ) { sections, expense, income ->
        ManageUiState(sections, expense, income)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageUiState())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    // ---------- 分区 ----------

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

    fun moveSection(id: Long, direction: Int) {
        val list = state.value.sections.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0 || target !in list.indices) return
        list[index] = list[target].also { list[target] = list[index] }
        viewModelScope.launch { repo.reorderSections(list) }
    }

    // ---------- 分类 ----------

    fun saveCategory(id: Long?, name: String, emoji: String, type: Int) {
        if (name.isBlank()) {
            _error.value = "分类名称不能为空"
            return
        }
        viewModelScope.launch {
            runCatching { repo.saveCategory(CategoryEntity(id = id ?: 0, name = name.trim(), emoji = emoji, type = type)) }
                .onFailure { e -> _error.value = e.message ?: "保存失败" }
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

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                ManageViewModel(app.container.repository)
            }
        }
    }
}
