package com.simpleledger.app.ui.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.ui.Routes
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.entry.EntryEditHost
import com.simpleledger.app.ui.entry.EntryEditHostStyle
import com.simpleledger.app.ui.ledger.DayHeader
import com.simpleledger.app.ui.ledger.EntryRow
import com.simpleledger.app.ui.ledger.RESULT_SAVED_ENTRY_ID
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.slAnimateItem
import com.simpleledger.app.ui.theme.SlButtonShape
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.icon.slCategoryIcon
import androidx.compose.ui.graphics.Color
import com.simpleledger.app.ui.components.SlSnackbarHost
import com.simpleledger.app.ui.components.slTitleRule

/**
 * 分区详情（FR-16~20）。
 *
 * - 顶部：返回 + 「emoji 分区名」+ 右上「管理」
 * - 列表：按天分组的该分区账目（Q-12，复用 [DayHeader] / [EntryRow]）
 * - 底部：「记一笔」（拇指可达）
 *
 * 承载形态随窗口变化（同步点 #7）：Compact 走全屏路由；Medium 居中浮层；Expanded 右侧面板。
 * 保存后**停留**在分区详情（EC-11d），可连续记账；列表与首屏卡片数据源同为 Flow，自动刷新（EC-11b）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionDetailScreen(
    sectionId: Long,
    resultHandle: SavedStateHandle,
    onBack: () -> Unit,
    onManage: (Long) -> Unit,
    onCreateEntry: (Long) -> Unit,
    onEditEntry: (Long) -> Unit,
    layout: WindowLayout = WindowLayout.Compact,
    dense: Boolean = false,
    viewModel: SectionDetailViewModel = viewModel(
        key = "section_detail_$sectionId",
        factory = SectionDetailViewModel.factory(sectionId),
    ),
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val inPlaceEdit = layout != WindowLayout.Compact
    var editingEntryId by rememberSaveable { mutableStateOf<Long?>(null) }

    suspend fun showSavedNotice(entryId: Long) {
        val label = viewModel.describeEntry(entryId) ?: return
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.saved_toast, label),
            actionLabel = context.getString(R.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            runCatching { viewModel.deleteEntryWithSnapshot(entryId) }
        }
    }

    // Compact：全屏记账页保存后回到本页——读取回传的 entryId 并显示「已记入…」+撤销（EC-11d）
    LaunchedEffect(Unit) {
        resultHandle.getStateFlow(RESULT_SAVED_ENTRY_ID, -1L).collect { savedId ->
            if (savedId > 0) {
                resultHandle[RESULT_SAVED_ENTRY_ID] = -1L
                showSavedNotice(savedId)
            }
        }
    }

    suspend fun duplicateWithNotice(entryId: Long) {
        val newId = viewModel.duplicateEntry(entryId)
        snackbarHostState.showSnackbar(
            message = context.getString(
                if (newId != null) R.string.duplicate_done else R.string.duplicate_failed
            ),
            duration = SnackbarDuration.Short,
        )
    }

    suspend fun deleteWithUndo(entryId: Long) {
        val snapshot = viewModel.deleteEntryWithSnapshot(entryId) ?: return
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.delete_done),
            actionLabel = context.getString(R.string.undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.restoreDeleted(snapshot)
            snackbarHostState.showSnackbar(context.getString(R.string.restored), duration = SnackbarDuration.Short)
        } else {
            viewModel.discardParkedImages()
        }
    }

    val startCreate = {
        if (inPlaceEdit) editingEntryId = Routes.NEW_ENTRY_ID else onCreateEntry(sectionId)
    }
    val rowClick: (Long) -> Unit = { id ->
        if (inPlaceEdit) editingEntryId = id else onEditEntry(id)
    }

    Scaffold(
        // ⚠️ 外层 AppRoot 已消费 systemBars insets，内层归零防双重避让（详见 SectionHomeScreen 注释）
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
        snackbarHost = { SlSnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            SlIcons.Ui.ArrowLeft,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    // v4：标题栏改「图标 + 分区名」（设计 §2.3），emoji 退场
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        state.section?.let {
                            Icon(
                                imageVector = slCategoryIcon(it.iconId),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = state.section?.name ?: "",
                            // 分区名装饰位：headlineK（楷体 Regular）+ 页眉双线（规范 §2.3）
                            style = SlType.headline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.slTitleRule(),
                        )
                    }
                    TextButton(onClick = { onManage(sectionId) }) {
                        Text(stringResource(R.string.section_manage))
                    }
                }

                SectionSummary(state.expenseCents, state.incomeCents)

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.isEmpty -> EmptyHint(
                            text = stringResource(R.string.section_detail_empty),
                            actionLabel = stringResource(R.string.add_entry),
                            onAction = startCreate,
                        )

                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            state.groups.forEach { group ->
                                item(key = "header_${group.date}") {
                                    DayHeader(
                                        dateLabel = DateTimes.dayLabel(group.date),
                                        expenseCents = group.expenseCents,
                                        incomeCents = group.incomeCents,
                                    )
                                }
                                items(group.entries, key = { it.entry.id }) { full ->
                                    EntryRow(
                                        full = full,
                                        selected = false,
                                        onClick = { rowClick(full.entry.id) },
                                        onDuplicate = { scope.launch { duplicateWithNotice(full.entry.id) } },
                                        onMove = {},
                                        onDelete = { scope.launch { deleteWithUndo(full.entry.id) } },
                                        // 增删移位动效（Motion）
                                        modifier = slAnimateItem(),
                                    )
                                }
                                item(key = "space_${group.date}") { Spacer(modifier = Modifier.height(8.dp)) }
                            }
                            item { Spacer(modifier = Modifier.height(12.dp)) }
                        }
                    }
                }

                // 底部「记一笔」：拇指可达
                Button(
                    onClick = startCreate,
                    shape = SlButtonShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.add_entry))
                }
            }

            // 非 Compact：编辑以列表页内浮层 / 右侧面板承载（不打断上下文）
            if (inPlaceEdit && editingEntryId != null) {
                EntryEditHost(
                    entryId = editingEntryId ?: Routes.NEW_ENTRY_ID,
                    sectionId = sectionId,
                    style = if (layout == WindowLayout.Expanded) {
                        EntryEditHostStyle.Panel
                    } else {
                        EntryEditHostStyle.Centered
                    },
                    dense = dense,
                    snackbarHostState = snackbarHostState,
                    onDismiss = { editingEntryId = null },
                    onSaved = { savedId ->
                        editingEntryId = null
                        savedId?.let { scope.launch { showSavedNotice(it) } }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SectionSummary(expenseCents: Long, incomeCents: Long) {
    val hidden = LocalHideAmounts.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                R.string.section_detail_expense,
                if (hidden) "••••" else Money.formatWithSymbol(expenseCents),
            ),
            style = SlType.bodySm,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.section_detail_income,
                if (hidden) "••••" else Money.formatWithSymbol(incomeCents),
            ),
            style = SlType.bodySm,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
