package com.simpleledger.app.ui.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.repo.DeletedEntrySnapshot
import com.simpleledger.app.data.repo.EntryDraft
import com.simpleledger.app.data.repo.LedgerRepository
import com.simpleledger.app.data.settings.AppSettings
import com.simpleledger.app.ui.ledger.DayGroup
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

data class SectionDetailUiState(
    val section: SectionEntity? = null,
    /** 按天分组（Q-12，与明细页一致） */
    val groups: List<DayGroup> = emptyList(),
    val expenseCents: Long = 0,
    val incomeCents: Long = 0,
    val isEmpty: Boolean = false,
)

/**
 * 分区详情状态与动作。
 *
 * 数据源：该分区当前自然月的账目（`observeEntries(start, end, sectionId = 该分区)`），
 * 按天分组（复用 [DayGroup]）。删除 / 复制 / 撤销与明细页口径一致。
 */
class SectionDetailViewModel(
    private val repo: LedgerRepository,
    private val settings: AppSettings,
    private val sectionId: Long,
) : ViewModel() {

    private val month: YearMonth = YearMonth.now()

    private val sectionFlow = repo.observeSections()
        .map { list -> list.firstOrNull { it.id == sectionId } }

    private val entriesFlow = repo.observeEntries(
        start = DateTimes.monthRange(month).first,
        end = DateTimes.monthRange(month).second,
        sectionId = sectionId,
    )

    val state: StateFlow<SectionDetailUiState> = combine(
        sectionFlow,
        entriesFlow,
    ) { section, entries ->
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

        SectionDetailUiState(
            section = section,
            groups = groups,
            expenseCents = entries.filter { it.entry.type == EntryType.EXPENSE }.sumOf { it.entry.amountCents },
            incomeCents = entries.filter { it.entry.type == EntryType.INCOME }.sumOf { it.entry.amountCents },
            isEmpty = entries.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionDetailUiState())

    suspend fun deleteEntryWithSnapshot(entryId: Long): DeletedEntrySnapshot? =
        repo.deleteEntryWithSnapshot(entryId)

    /** 撤销「刚保存的账目」（提示条里的「撤销」） */
    suspend fun undoDelete(entryId: Long) {
        runCatching { repo.deleteEntry(entryId) }
    }

    suspend fun restoreDeleted(snapshot: DeletedEntrySnapshot) = repo.restoreEntry(snapshot)

    suspend fun discardParkedImages() = repo.discardParkedImages()

    /** 复制一笔：金额 / 分类 / 分区 / 备注照搬，时间改为此刻，贴图不带 */
    suspend fun duplicateEntry(entryId: Long): Long? {
        val full: EntryFull = repo.getEntryFull(entryId) ?: return null
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
     * 保存成功提示文案，如「🔨 装修 · ¥2,000.00」。
     * 隐私模式下金额位替换为「金额已隐藏」，与明细页口径一致（FR-39 / 同步点 #6）。
     */
    suspend fun describeEntry(entryId: Long): String? {
        val full = repo.getEntryFull(entryId) ?: return null
        val amount = if (settings.hideAmounts.value) {
            "金额已隐藏"
        } else {
            Money.formatWithSymbol(full.entry.amountCents)
        }
        val section = full.section
        return if (section != null) "${section.emoji} ${section.name} · $amount" else amount
    }

    companion object {
        fun factory(sectionId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LedgerApp
                SectionDetailViewModel(app.container.repository, app.container.settings, sectionId)
            }
        }
    }
}
