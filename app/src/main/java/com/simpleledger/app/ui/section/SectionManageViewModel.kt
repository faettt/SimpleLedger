package com.simpleledger.app.ui.section

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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SectionManageUiState(
    val section: SectionEntity? = null,
    /** 仅该分区专属分类（FR-18：不出现其他分区的专属分类） */
    val expenseCategories: List<CategoryEntity> = emptyList(),
    val incomeCategories: List<CategoryEntity> = emptyList(),
)

/**
 * 分区管理页状态与动作（N11）。
 *
 * 职责：编辑分区信息（名称 / emoji / 备注 / 预算）+ 管理**该分区专属分类**（CRUD + 手动排序）。
 * 排序作用域收窄到 (类型, 该分区) —— 与全局分类排序互相独立（EC-01）。
 */
class SectionManageViewModel(
    private val repo: LedgerRepository,
    private val sectionId: Long,
) : ViewModel() {

    val state: StateFlow<SectionManageUiState> = combine(
        repo.observeSections().map { list -> list.firstOrNull { it.id == sectionId } },
        repo.observeSectionCategories(sectionId, EntryType.EXPENSE),
        repo.observeSectionCategories(sectionId, EntryType.INCOME),
    ) { section, expense, income ->
        SectionManageUiState(section = section, expenseCategories = expense, incomeCategories = income)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionManageUiState())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    fun saveSection(id: Long?, name: String, iconId: Int, note: String, budgetCents: Long) {
        if (name.isBlank()) {
            _error.value = "分区名称不能为空"
            return
        }
        viewModelScope.launch {
            runCatching {
                // ⚠️ colorIndex 不在编辑表单里：编辑时必须回填原值，
                //    否则每次保存分区都会把胶带色重置成 0（青绿）。
                val existing = id?.let { repo.getSection(it) }
                repo.saveSection(
                    SectionEntity(
                        id = id ?: sectionId,
                        name = name.trim(),
                        iconId = iconId,
                        note = note.trim(),
                        budgetCents = budgetCents,
                        colorIndex = existing?.colorIndex ?: 0,
                    )
                )
            }.onFailure { e -> _error.value = e.message ?: "保存失败" }
        }
    }

    /** 新建时归属固定为当前分区；编辑保持原归属（Q-11 不做改归属） */
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
                        sectionId = existing?.sectionId ?: sectionId,
                        sortOrder = existing?.sortOrder ?: 0,
                    )
                )
            }.onFailure { e -> _error.value = e.message ?: "保存失败" }
        }
    }

    /** B4：删除分类（去向单选，destinationId = null 落默认「未分类」哨兵） */
    fun deleteCategory(id: Long, destinationId: Long? = null) {
        viewModelScope.launch {
            repo.deleteCategory(id, destinationId)
                .onFailure { e -> _error.value = e.message ?: "删除失败" }
        }
    }

    /** 同类型、同分区内交换相邻两项 */
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
        fun factory(sectionId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                SectionManageViewModel(app.container.repository, sectionId)
            }
        }
    }
}
