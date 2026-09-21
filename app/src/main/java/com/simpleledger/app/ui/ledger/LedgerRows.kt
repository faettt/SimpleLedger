package com.simpleledger.app.ui.ledger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryFull
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.logic.SectionMoveRules
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.icon.slCategoryIcon
import com.simpleledger.app.ui.theme.TabularNums
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.simpleledger.app.data.local.entity.ReimburseState
import com.simpleledger.app.ui.components.EntryStatusCluster
import com.simpleledger.app.ui.theme.tapeColor

/*
 * 共享的账目展示组件：日分组标题 / 账目行 / 金额文本。
 *
 * 这几个组件同时被「列表栏」与「搜索结果栏」（LedgerSearchPane）复用，金额文本还被统计页
 * （StatsScreen）引用，因此单独成文件、保持 `internal` / `public` 可见性不变。
 * 它们在同一个包 `com.simpleledger.app.ui.ledger` 内，全限定名与拆分前完全一致，
 * 故所有既有引用（含跨包的 `import ...ledger.AmountText`）无需任何改动。
 */

/** 日分组标题：日期 + 当日收支汇总 */
@Composable
internal fun DayHeader(dateLabel: String, expenseCents: Long, incomeCents: Long) {
    val hidden = LocalHideAmounts.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dateLabel,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (hidden) {
                stringResource(R.string.day_summary_hidden)
            } else {
                stringResource(
                    R.string.day_summary,
                    Money.formatCents(expenseCents),
                    Money.formatCents(incomeCents),
                )
            },
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = TabularNums,
        )
    }
}

/**
 * 账目行：分类名（主）· 时间/分区/标记（次）· 金额（视觉最重）。
 * 备注不展开全文，仅用 💬 标记存在性——明细页的首要任务是快速扫视。
 *
 * 长按弹出快捷菜单（复制一笔 / 移动到其它分区 / 删除）。菜单锚定在本行而非手指坐标：
 * 行内交互用锚定行更可预期，也能保住 combinedClickable 带来的涟漪反馈与读屏语义
 * （自行处理指针事件会失去这两者）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryRow(
    full: EntryFull,
    selected: Boolean,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    showSectionPrefix: Boolean = false,
    /** 长按菜单：切换核对维度（规范 §3.5 入口）。传 null 则不显示该项。 */
    onToggleReconciled: ((Boolean) -> Unit)? = null,
    /** 长按菜单：设置报销维度。传 null 则不显示这三项。 */
    onSetReimburseState: ((Int) -> Unit)? = null,
) {
    val isIncome = full.entry.type == EntryType.INCOME
    val hidden = LocalHideAmounts.current
    val meta = buildString {
        append(DateTimes.timeLabel(DateTimes.toLocalTime(full.entry.entryTime)))
        // v4：分区名不再拼 emoji（F4——分区身份由色条/图标表达，文字只留名称）
        full.section?.let { append(" · ${it.name}") }
    }
    // v4：📷 / 💬 从「拼进字符串的 emoji」改成**渲染出来的行内图标**（SlIcons.Ui.CameraInline /
    // NoteInline）。它们与读屏串 speech 的分工：视觉用图标，语义由 speechImageCount / speechHasNote 兜底。
    val hasNote = full.entry.note.isNotBlank()
    val imageCount = full.images.size
    var menuOpen by remember { mutableStateOf(false) }
    val uncategorizedLabel = stringResource(R.string.uncategorized)

    // F4 的例外（规范 §2.4 末尾 + §188）：**「全部分区」筛选视图**下补回分区文字前缀。
    // 触发条件比「视图」更窄一层 —— 只有**分区专属分类**才补：
    //   专属分类（category.sectionId != null）名可能在不同分区重名（PRD q-02），非消歧不可；
    //   全局分类（sectionId == null）名字全局唯一，补前缀只是噪音。
    // 规范 §2.4 的示意图正是这个口径：「装修·主材」「装修·人工」带前缀，而全局的「餐饮」
    // 「工资」不带 —— 照图实现，不自作主张。
    // ⚠️ 前缀只加在**视觉文本**上：读屏串末尾已单独念分区名，再加前缀会念两遍。
    val plainCategoryName = full.category?.name ?: uncategorizedLabel
    val needPrefix = showSectionPrefix &&
        full.category?.sectionId != null &&
        full.section != null
    val categoryLabel =
        if (needPrefix) "${full.section.name} · $plainCategoryName" else plainCategoryName

    // 读屏串：行内三个 Text 节点会被 TalkBack 分三次朗读，失去「这是一笔账」的整体语义。
    // 顺序按「最重要在前」：方向 → 分类 → 金额 → 时间 → 分区 → 备注/单据。
    // 方向词必须显式给出：视觉靠 −/+ 与颜色，读屏念不出颜色、也不会把符号当方向。
    // 文案取自 strings.xml：buildString 的 lambda 非 Composable，故先把文案解析到局部变量。
    val speechDirection = stringResource(if (isIncome) R.string.income else R.string.expense)
    val speechUncategorized = stringResource(R.string.uncategorized)
    // 隐私模式：绝不把真实金额交给读屏，否则打码只挡了眼睛、挡不住耳朵
    val speechAmount = if (hidden) {
        stringResource(R.string.amount_hidden)
    } else {
        Money.toChineseSpeech(full.entry.amountCents)
    }
    val speechHasNote = stringResource(R.string.a11y_has_note)
    val speechImageCount = stringResource(R.string.a11y_image_count, full.images.size)
    // v4：状态词必须进读屏串 —— 视觉上是两个 14dp 的小符号，读屏用户完全看不到
    val speechReconciled = stringResource(R.string.a11y_reconciled)
    val speechReimbursePending = stringResource(R.string.a11y_reimburse_pending)
    val speechReimburseCleared = stringResource(R.string.a11y_reimburse_cleared)
    val speech = buildString {
        append(speechDirection)
        append("，")
        append(full.category?.name ?: speechUncategorized)
        append("，")
        append(speechAmount)
        // 状态紧跟在金额之后：它是「这笔账还要不要跟」的可操作信息，优先级高于时间
        if (full.entry.reconciled) append("，$speechReconciled")
        when (full.entry.reimburseState) {
            ReimburseState.PENDING -> append("，$speechReimbursePending")
            ReimburseState.CLEARED -> append("，$speechReimburseCleared")
            else -> Unit
        }
        append("，")
        append(DateTimes.timeLabel(DateTimes.toLocalTime(full.entry.entryTime)))
        full.section?.let { append("，${it.name}") }
        if (full.entry.note.isNotBlank()) append("，$speechHasNote")
        if (full.images.isNotEmpty()) append("，$speechImageCount")
    }

    val moreActionsLabel = stringResource(R.string.a11y_more_actions)

    Box(modifier = Modifier.fillMaxWidth()) {
        // 分区胶带色：账目行颜色的**唯一**含义（F4 —— 分区身份由色条承担，
        // 所以分类名不再带「装修 · 」文字前缀，符号簇也不再引入第三套色彩语义）
        // （唯一的例外是「全部分区」视图下对专属分类补前缀，见上方 categoryLabel）
        val barColor = tapeColor(full.section?.colorIndex ?: 0)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
                // 左侧 3dp 通高色条用 drawBehind 绘制而非加布局节点：
                // 布局方案要让它的 fillMaxHeight 拿到确定高度，就得引入 IntrinsicSize.Min
                // 或嵌套一层 Row；绘制方案零结构改动，边界也真正贴到行的上下沿。
                .drawBehind {
                    drawRect(
                        color = barColor,
                        topLeft = Offset.Zero,
                        size = Size(3.dp.toPx(), size.height),
                    )
                }
                // mergeDescendants 把行内子文本合并成一个语义节点；下拉菜单是 Box 的兄弟节点、
                // 不在本 Row 内，因此不会被吞进来（否则会一次念出全部菜单项）
                .semantics(mergeDescendants = true) { contentDescription = speech }
                .combinedClickable(
                    onClick = onClick,
                    onLongClickLabel = moreActionsLabel,
                    onLongClick = { menuOpen = true },
                )
                // start 留 12dp 给色条（3dp 色条 + 9dp 呼吸）
                .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态符号簇：核对 ✓ + 报销 ○/●（两个槽恒定占位 14dp，空槽不收缩，
            // 否则同行不同账目的分类名会左右错位，流水列表的纵向节奏就断了）
            EntryStatusCluster(
                reconciled = full.entry.reconciled,
                reimburseState = full.entry.reimburseState,
            )
            Spacer(modifier = Modifier.width(7.dp))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isIncome) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // v4：emoji → 手绘图标。兜底 43 = tag，与迁移兜底一致；
                // 装饰性 → contentDescription null，读屏由 speech 串负责
                Icon(
                    imageVector = slCategoryIcon(full.category?.iconId ?: 43),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = categoryLabel,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // v4：meta 行拆成「文本 + 行内图标」。图标 12dp（inline 档，描边 1.7），
                // contentDescription 置 null —— 语义由 speech 串统一给出，避免重复朗读
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = meta,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (imageCount > 0) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Icon(
                            imageVector = SlIcons.Ui.CameraInline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = imageCount.toString(),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hasNote) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Icon(
                            imageVector = SlIcons.Ui.NoteInline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            AmountText(amountCents = full.entry.amountCents, isIncome = isIncome)
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.duplicate_one)) },
                onClick = {
                    menuOpen = false
                    onDuplicate()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_section_title)) },
                onClick = {
                    menuOpen = false
                    onMove()
                },
            )

            // v4 双维状态入口（规范 §3.5）。两项都放在「移动」与「删除」之间：
            // 删除是破坏性动作，必须离上面的日常动作远一点。
            onToggleReconciled?.let { toggle ->
                val already = full.entry.reconciled
                DropdownMenuItem(
                    text = {
                        // 名词会被读成「当前状态」，动作词才读得懂点下去的结果
                        Text(
                            stringResource(
                                if (already) R.string.entry_unmark_reconciled
                                else R.string.entry_mark_reconciled,
                            )
                        )
                    },
                    onClick = {
                        menuOpen = false
                        toggle(!already)
                    },
                )
            }
            onSetReimburseState?.let { setState ->
                // 三条里只显示与当前状态不同的两条 —— 否则菜单里会出现
                // 「标记待报销」而它已经待报销，点下去毫无反馈。
                val current = full.entry.reimburseState
                if (current != ReimburseState.PENDING) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.entry_mark_reimburse_pending)) },
                        onClick = {
                            menuOpen = false
                            setState(ReimburseState.PENDING)
                        },
                    )
                }
                if (current != ReimburseState.CLEARED) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.entry_mark_reimburse_cleared)) },
                        onClick = {
                            menuOpen = false
                            setState(ReimburseState.CLEARED)
                        },
                    )
                }
                if (current != ReimburseState.NONE) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.entry_clear_reimburse)) },
                        onClick = {
                            menuOpen = false
                            setState(ReimburseState.NONE)
                        },
                    )
                }
            }

            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/* ---------------------------------------------------------------- 金额 */

/** 列表内金额：语义色 + 正负号 + 等宽数字，保证纵向可扫视 */
@Composable
fun AmountText(
    amountCents: Long,
    isIncome: Boolean,
    fontSize: TextUnit = 15.sp,
) {
    val hidden = LocalHideAmounts.current
    Text(
        text = if (hidden) {
            "••••"
        } else {
            (if (isIncome) "+" else "−") + Money.formatWithSymbol(kotlin.math.abs(amountCents))
        },
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        color = if (isIncome) incomeColor() else expenseColor(),
        style = TabularNums,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

/**
 * 移动到其它分区。
 *
 * 关键约束（PRD EC-06 / QA P1-1）：不允许出现「分区 = X、分类只属于 Y」的非法组合。
 * 因此不能只改 `sectionId` 而保留原分类，必须判断分类在目标分区是否仍合法：
 * - 全局分类（`sectionId == null`）→ 任一分区可见，**直接允许**，仅改分区；
 * - 专属分类本属于目标分区 → **直接允许**，仅改分区；
 * - 专属分类属于**其它**分区 → 追加一步「为目标分区重选同类型分类」，
 *   默认预选目标分区的兜底分类（「其他支出」/「其他收入」，否则候选第一项）；
 *   若目标分区没有可用的同类型分类，则给出提示并**禁止确认**（绝不静默改成兜底分类）。
 *
 * 确认时一次性提交「改分区 +（必要时）改分类」，由同一事务完成，避免中间态。
 */
@Composable
internal fun MoveSectionDialog(
    entry: EntryFull,
    sections: List<SectionEntity>,
    allCategories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onConfirm: (sectionId: Long, newCategoryId: Long?) -> Unit,
) {
    // 隐私模式：对话框抬头即以真实金额列明「要移动的是哪一笔」，是容易被忽略的视觉泄露点
    val hidden = LocalHideAmounts.current
    val currentCategory = entry.category
    val targets = sections.filter { it.id != entry.entry.sectionId }

    // null = 第一步（选目标分区）；非 null = 第二步（为该目标分区重选分类）
    var reselectSectionId by remember { mutableStateOf<Long?>(null) }

    // 目标分区的同类型候选：口径与 CategoryDao.observeCandidates 一致
    // （type 匹配 且 (全局 or 本分区专属)），排序「专属在前 → sortOrder → id」
    val candidates: List<CategoryEntity> = remember(reselectSectionId, allCategories) {
        val sid = reselectSectionId
        if (sid == null) {
            emptyList()
        } else {
            allCategories
                .filter { it.type == entry.entry.type && (it.sectionId == null || it.sectionId == sid) }
                .sortedWith(compareBy({ if (it.sectionId == null) 1 else 0 }, { it.sortOrder }, { it.id }))
        }
    }

    // 默认预选兜底分类；进入第二步或候选变化时重置为兜底预选（remember 的 key 变化即重置）
    var selectedCategoryId by remember(reselectSectionId, candidates) {
        mutableStateOf(SectionMoveRules.pickFallback(candidates, entry.entry.type)?.id)
    }

    // buildString 的 lambda 非 Composable，先把所有文案解析到局部变量
    val uncategorized = stringResource(R.string.uncategorized)
    val noSection = stringResource(R.string.no_section)
    val hiddenAmount = stringResource(R.string.amount_hidden)
    val currentSectionLabel = entry.section?.name ?: noSection
    val currentLine = stringResource(R.string.move_current_section, currentSectionLabel)
    // v4：读屏/拼接文案一律不带 emoji（TalkBack 念 emoji 是噪音）
    val categoryLabel = entry.category?.name ?: uncategorized
    val amountText = if (hidden) hiddenAmount else Money.formatWithSymbol(entry.entry.amountCents)
    val headerLine = "$categoryLabel · $amountText　$currentLine"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_section_title)) },
        text = {
            Column {
                Text(
                    text = headerLine,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (reselectSectionId == null) {
                    // 第一步：选目标分区。兼容则直接完成，不兼容则进入第二步。
                    if (targets.isEmpty()) {
                        Text(stringResource(R.string.move_no_other_section), fontSize = 13.sp)
                    } else {
                        targets.forEach { section ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (SectionMoveRules.canKeepCategory(currentCategory, section.id)) {
                                            onConfirm(section.id, null)
                                        } else {
                                            reselectSectionId = section.id
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 分区选择行：图标（分区自身 iconId）+ 名称
                                Icon(
                                    imageVector = slCategoryIcon(section.iconId),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(modifier = Modifier.width(7.dp))
                                Text(section.name, fontSize = 14.5.sp)
                            }
                        }
                    }
                } else {
                    // 第二步：说明原因 + 列出目标分区同类型候选 + 兜底预选
                    val target = targets.firstOrNull { it.id == reselectSectionId }
                    Text(
                        text = stringResource(
                            R.string.move_reselect_hint,
                            target?.let { it.name } ?: "",
                        ),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (candidates.isEmpty()) {
                        // 目标分区没有可用的同类型分类 → 禁止确认（不静默兜底）
                        Text(
                            text = stringResource(R.string.move_no_candidate),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            candidates.forEach { candidate ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { selectedCategoryId = candidate.id }
                                        .padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selectedCategoryId == candidate.id,
                                        onClick = { selectedCategoryId = candidate.id },
                                    )
                                    Text(
                                        text = candidate.name,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (reselectSectionId != null) {
                TextButton(
                    enabled = selectedCategoryId != null,
                    onClick = {
                        val sid = reselectSectionId
                        val cid = selectedCategoryId
                        if (sid != null && cid != null) onConfirm(sid, cid)
                    },
                ) { Text(stringResource(R.string.move_confirm)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}