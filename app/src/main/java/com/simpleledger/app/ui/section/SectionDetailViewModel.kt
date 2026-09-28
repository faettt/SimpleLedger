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
import com.simpleledger.app.logic.SectionDetailFilterPlan
import com.simpleledger.app.ui.ledger.DayGroup
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth

data class SectionDetailUiState(
    val section: SectionEntity? = null,
    /** 按天分组（Q-12，与明细页一致），时间倒序 */
    val groups: List<DayGroup> = emptyList(),
    val expenseCents: Long = 0,
    val incomeCents: Long = 0,
    /** 当前时间窗内无账目 */
    val isEmpty: Boolean = false,
    /** 时间窗筛选：null = 全部时间（默认，任务书裁定原文）；非 null = 只看该自然月 */
    val monthFilter: YearMonth? = null,
    /**
     * 该分区是否存过**任何**账目（空态两分支的判据）。
     * 初始 `true`：首帧前无法知道真假，先按「有账目」渲染可杜绝空分区闪现
     * 「筛选月无账目」这类错误空态（与空态分支判定一致，见
     * [SectionDetailFilterPlan.emptyBranch]）。
     */
    val hasAnyEntry: Boolean = true,
)

/** 单趟分组汇总的结果（纯变换产物，见 [toSectionAgg]） */
data class SectionDetailAgg(
    val groups: List<DayGroup>,
    val expenseCents: Long,
    val incomeCents: Long,
)

/**
 * 单趟完成「按天分组 + 组内小计 + 总计」：一次遍历同时累计三层数字，
 * 替代旧实现的三次全量 filter/sumOf。入参沿用 SQL 的 `entryTime DESC, id DESC`——
 * 组内条目顺序与旧 `groupBy` 结果一致，组间再按日期倒序。纯函数，JVM 可复核口径。
 */
internal fun toSectionAgg(entries: List<EntryFull>): SectionDetailAgg {
    var totalExpense = 0L
    var totalIncome = 0L

    class DayAccumulator(val date: LocalDate) {
        val entries = ArrayList<EntryFull>()
        var expense = 0L
        var income = 0L
    }

    // groupBy 语义：保 encounter order（LinkedHashMap），首见日期即该天的第一条（最新）
    val days = LinkedHashMap<LocalDate, DayAccumulator>()
    for (full in entries) {
        // getOrPut 的回调不带键参数，日期需先落局部变量再传给 DayAccumulator
        val date = DateTimes.toLocalDate(full.entry.entryTime)
        val day = days.getOrPut(date) { DayAccumulator(date) }
        day.entries.add(full)
        when (full.entry.type) {
            EntryType.EXPENSE -> {
                totalExpense += full.entry.amountCents
                day.expense += full.entry.amountCents
            }
            EntryType.INCOME -> {
                totalIncome += full.entry.amountCents
                day.income += full.entry.amountCents
            }
        }
    }
    return SectionDetailAgg(
        groups = days.values.map { DayGroup(it.date, it.entries, it.expense, it.income) }
            .sortedByDescending { it.date },
        expenseCents = totalExpense,
        incomeCents = totalIncome,
    )
}

/**
 * 分区详情状态与动作。
 *
 * 数据源：默认「全部时间」（该分区自始至今的账目，任务书裁定原文），可按自然月筛选。
 * 按天分组（复用 [DayGroup]）。删除 / 复制 / 撤销与明细页口径一致。
 *
 * 筛选语义：
 * - [monthFilter] 活在 VM（viewModel key 按 sectionId 区分），离开页面 VM 销毁即复位
 *   「全部」；月模式下「保存 / 复制跟随账目落月」（见 [landOn]）也只在停留期内有效。
 * - 换流瞬态：切换筛选时 combine 会先用旧分组拼出一帧中间态，随后新查询落位纠正——
 *   接受该瞬态（列表骨架不变，无闪烁级问题）。
 * - 数据量口径：全量窗口一次查尽，**不设上限、不接 Paging**；单分区破 1 万行
 *   再评估（届时连同内存分组一并复核）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SectionDetailViewModel(
    private val repo: LedgerRepository,
    private val settings: AppSettings,
    private val sectionId: Long,
) : ViewModel() {

    /** 时间窗筛选（null = 全部时间）。UI 的筛选条 / 空态 / 跳月都从 [state] 读它的现值。 */
    private val monthFilter = MutableStateFlow<YearMonth?>(null)

    private val sectionFlow = repo.observeSections()
        .map { list -> list.firstOrNull { it.id == sectionId } }

    /**
     * 换流：null → 全量窗口 (Long.MIN_VALUE, Long.MAX_VALUE)——DatePicker 未收窄
     * yearRange，负 epoch 的 entryTime 可达，不能取巧用某年 1 月当下界；
     * 月 → [DateTimes.monthRange]。分组汇总的重活挂在 .map 并 flowOn(Default) 下放，
     * combine 只拼字段。sectionId/entryTime 有索引（Entities.kt），两个窗口都走索引。
     */
    private val entriesAggFlow: Flow<SectionDetailAgg> = monthFilter
        .flatMapLatest { filter ->
            val (start, end) = filter?.let { DateTimes.monthRange(it) }
                ?: (Long.MIN_VALUE to Long.MAX_VALUE)
            repo.observeEntries(
                start = start,
                end = end,
                sectionId = sectionId,
            )
        }
        .map { entries -> toSectionAgg(entries) }
        .flowOn(Dispatchers.Default)

    /**
     * 「分区是否存过任何账目」。工单前置项（`observeAnyBySection` EXISTS 一行）裁定前
     * 先走**域内兜底**：同仓库全量窗口查询取非空——全部模式下与主查询重复物化一份，
     * 接线点不变；所有者批准后把本 flow 换成 EXISTS 转发即可（返工面 = 这一行）。
     */
    private val hasAnyEntryFlow: Flow<Boolean> = repo.observeEntries(
        start = Long.MIN_VALUE,
        end = Long.MAX_VALUE,
        sectionId = sectionId,
    ).map { it.isNotEmpty() }

    val state: StateFlow<SectionDetailUiState> = combine(
        sectionFlow,
        entriesAggFlow,
        hasAnyEntryFlow,
        monthFilter,
    ) { section, agg, hasAny, filter ->
        SectionDetailUiState(
            section = section,
            groups = agg.groups,
            expenseCents = agg.expenseCents,
            incomeCents = agg.incomeCents,
            // 窗口空 ⟺ 分组为空（toSectionAgg 对空输入返回空组）
            isEmpty = agg.groups.isEmpty(),
            monthFilter = filter,
            hasAnyEntry = hasAny,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionDetailUiState())

    // ---------- 时间窗筛选 ----------

    /** 切回「全部时间」（筛选条「全部」chip、月空态「看全部时间」） */
    fun setMonthFilter(month: YearMonth?) {
        monthFilter.value = month
    }

    /** 筛选条 ‹ › 步进（仅月模式有入口；全部模式无操作）。跨年环绕见 plan。 */
    fun stepMonth(delta: Int) {
        val current = monthFilter.value ?: return
        monthFilter.value = SectionDetailFilterPlan.stepMonth(current, delta)
    }

    /**
     * 「按月筛选」入口：只从全部进入，落点 = 点击时刻分组的首组（最新账目月）；
     * 尚无分组（首帧前点击）回退当月——可能落无账月，由空态「看全部时间」一键自愈。
     */
    fun enterMonthMode() {
        val latest = state.value.groups.firstOrNull()?.date
        monthFilter.value = SectionDetailFilterPlan.enterMonthFilter(latest, LocalDate.now())
    }

    /**
     * 保存 / 复制 / 编辑成功后的跳月：「账目落哪月筛哪月」，**仅月筛选生效**——
     * 全部模式不跳（否则最高频的记账操作会被反复收窄进单月），裁定全文见
     * [SectionDetailFilterPlan.landTarget]。撤销删除后该月变空，由
     * 「筛选月无账目 + 看全部时间」空态自愈。
     */
    fun landOn(entryMonth: YearMonth) {
        monthFilter.value = SectionDetailFilterPlan.landTarget(monthFilter.value, entryMonth)
    }

    /** 账目所在月（保存提示弹前查询用）；账目已不存在（如并发删除）返回 null */
    suspend fun entryMonthOf(entryId: Long): YearMonth? =
        repo.getEntryFull(entryId)?.let { full ->
            YearMonth.from(DateTimes.toLocalDate(full.entry.entryTime))
        }

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
        // v4：读屏文案不再拼 emoji —— TalkBack 会把 emoji 念成「表情符号」，
        // 对视障用户是噪音。分区身份由 UI 侧的图标/色条表达，读屏只留语义文本。
        return if (section != null) "${section.name} · $amount" else amount
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
