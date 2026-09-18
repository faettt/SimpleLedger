package com.simpleledger.app.ui.entry

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.repo.EntryDraft
import com.simpleledger.app.data.repo.LedgerRepository
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

/** 一张贴图：既有图（已存储路径）或新图（待导入 Uri） */
data class PendingImage(
    val existingPath: String? = null,
    val newUri: Uri? = null,
) {
    val key: String get() = existingPath ?: newUri.toString()
}

data class EntryEditUiState(
    val isEdit: Boolean = false,
    val type: Int = EntryType.EXPENSE,
    val amountText: String = "",
    val categories: List<com.simpleledger.app.data.local.entity.CategoryEntity> = emptyList(),
    val selectedCategoryId: Long? = null,
    val sections: List<com.simpleledger.app.data.local.entity.SectionEntity> = emptyList(),
    val selectedSectionId: Long? = null,
    val entryTime: Long = System.currentTimeMillis(),
    val note: String = "",
    val images: List<PendingImage> = emptyList(),
    val loading: Boolean = true,
    val saved: Boolean = false,
    val error: String? = null,
)

class EntryEditViewModel(
    private val repo: LedgerRepository,
    private val entryId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(EntryEditUiState(isEdit = entryId > 0))
    val state: StateFlow<EntryEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val sections = repo.observeSections().first()
            var editData: EntryFull? = null
            if (entryId > 0) {
                editData = repo.getEntryFull(entryId)
            }
            val type = editData?.entry?.type ?: EntryType.EXPENSE
            _state.update {
                it.copy(
                    sections = sections,
                    selectedSectionId = editData?.entry?.sectionId ?: sections.firstOrNull()?.id,
                )
            }
            loadCategories(type)
            if (editData != null) {
                _state.update {
                    it.copy(
                        type = type,
                        amountText = Money.formatCents(editData.entry.amountCents).replace(",", ""),
                        selectedCategoryId = editData.entry.categoryId,
                        entryTime = editData.entry.entryTime,
                        note = editData.entry.note,
                        images = editData.images.map { img -> PendingImage(existingPath = img.filePath) },
                        loading = false,
                    )
                }
            } else {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private suspend fun loadCategories(type: Int) {
        val cats = repo.observeCategories(type).first()
        _state.update { old ->
            old.copy(
                categories = cats,
                selectedCategoryId = old.selectedCategoryId?.takeIf { id -> cats.any { it.id == id } }
                    ?: cats.firstOrNull()?.id,
            )
        }
    }

    fun setType(type: Int) {
        if (_state.value.type == type) return
        _state.update { it.copy(type = type, selectedCategoryId = null) }
        viewModelScope.launch { loadCategories(type) }
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

    fun selectCategory(id: Long) = _state.update { it.copy(selectedCategoryId = id) }
    fun selectSection(id: Long) = _state.update { it.copy(selectedSectionId = id) }
    fun setNote(text: String) = _state.update { it.copy(note = text) }

    fun setDate(date: LocalDate) = _state.update {
        val time = LocalTime.ofInstant(Instant.ofEpochMilli(it.entryTime), ZoneId.systemDefault())
        it.copy(entryTime = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    fun setTime(time: LocalTime) = _state.update {
        val date = LocalDate.ofInstant(Instant.ofEpochMilli(it.entryTime), ZoneId.systemDefault())
        it.copy(entryTime = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    /** DatePicker 传回的是 UTC 毫秒，需要转成本地日期 */
    fun setDateFromUtcMillis(utcMillis: Long) {
        val date = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        setDate(date)
    }

    fun addImages(uris: List<Uri>) = _state.update {
        it.copy(images = it.images + uris.map { uri -> PendingImage(newUri = uri) })
    }

    fun removeImage(index: Int) = _state.update {
        it.copy(images = it.images.filterIndexed { i, _ -> i != index })
    }

    fun save() {
        val current = _state.value
        val cents = Money.parseToCents(current.amountText)
        if (cents == null) {
            _state.update { it.copy(error = "请输入有效金额") }
            return
        }
        val categoryId = current.selectedCategoryId ?: run {
            _state.update { it.copy(error = "请选择分类") }
            return
        }
        val sectionId = current.selectedSectionId ?: run {
            _state.update { it.copy(error = "请选择分区") }
            return
        }
        viewModelScope.launch {
            runCatching {
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
                        newImageUris = current.images.mapNotNull { it.newUri },
                    )
                )
            }.onSuccess {
                _state.update { it.copy(saved = true, error = null) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: "保存失败") }
            }
        }
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

    companion object {
        fun factory(entryId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as com.simpleledger.app.LedgerApp
                EntryEditViewModel(app.container.repository, entryId)
            }
        }
    }
}
