package com.simpleledger.app.ui.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.data.local.entity.SectionTotal
import com.simpleledger.app.data.repo.SectionDeleteImpact
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.ConfirmDialog
import com.simpleledger.app.ui.components.ContentMaxWidth
import com.simpleledger.app.ui.components.ContentWidth
import com.simpleledger.app.ui.components.EmptyHint
import com.simpleledger.app.ui.components.SectionDialog
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.FloatingActionButtonDefaults
import com.simpleledger.app.ui.components.SlSnackbarHost

/**
 * 分区首屏（默认 tab，FR-07）。
 *
 * - 卡片列表：emoji + 名 + 本月花销 + 预算进度条（FR-10~13）
 * - 顶部右上「排序」按钮切换排序态（Q-14 / 裁定 C-3：上下移，不做长按拖拽）
 * - 空态（0 分区）与「新建分区」入口（空 / 非空均有，EC-04 / EC-11a）
 * - Expanded 限宽 [ContentMaxWidth.Standard]
 */
@Composable
fun SectionHomeScreen(
    onOpenSection: (Long) -> Unit,
    layout: WindowLayout = WindowLayout.Compact,
    viewModel: SectionHomeViewModel = viewModel(factory = SectionHomeViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSectionDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SectionEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<SectionTotal?>(null) }
    var deleteImpact by remember { mutableStateOf<SectionDeleteImpact?>(null) }

    LaunchedEffect(error) {
        error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }
    LaunchedEffect(deleteTarget) {
        deleteImpact = deleteTarget?.let { viewModel.impactOf(it.sectionId) }
    }

    Scaffold(

        containerColor = Color.Transparent,
        snackbarHost = { SlSnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // 手账不用阴影：层级由「纸叠纸」表达，FAB 也拉到 0
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),onClick = {
                editTarget = null
                showSectionDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.new_section))
            }
        },
    ) { padding ->
        val body: @Composable () -> Unit = {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.nav_sections),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (!state.isEmpty) {
                        TextButton(onClick = viewModel::toggleReorder) {
                            Text(
                                if (state.reorderMode) {
                                    stringResource(R.string.section_sort_done)
                                } else {
                                    stringResource(R.string.section_sort)
                                },
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                if (state.isEmpty) {
                    EmptyHint(
                        text = stringResource(R.string.section_home_empty),
                        actionLabel = stringResource(R.string.new_section),
                        onAction = {
                            editTarget = null
                            showSectionDialog = true
                        },
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(state.cards, key = { _, total -> total.sectionId }) { index, total ->
                            SectionCard(
                                total = total,
                                reorderMode = state.reorderMode,
                                canMoveUp = index > 0,
                                canMoveDown = index < state.cards.lastIndex,
                                onClick = { onOpenSection(total.sectionId) },
                                onMoveUp = { viewModel.moveSection(total.sectionId, -1) },
                                onMoveDown = { viewModel.moveSection(total.sectionId, +1) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                            )
                        }
                        item { Spacer(modifier = Modifier.height(96.dp)) }
                    }
                }
            }
        }

        if (layout == WindowLayout.Expanded) {
            ContentWidth(maxWidth = ContentMaxWidth.Standard) { body() }
        } else {
            body()
        }
    }

    if (showSectionDialog) {
        SectionDialog(
            initial = editTarget,
            onDismiss = { showSectionDialog = false },
            onSave = { id, name, emoji, note, budgetCents ->
                viewModel.saveSection(id, name, emoji, note, budgetCents)
                showSectionDialog = false
            },
        )
    }

    deleteTarget?.let { target ->
        val impact = deleteImpact
        ConfirmDialog(
            title = stringResource(R.string.delete_section_title, target.name),
            text = sectionDeleteMessage(impact),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { viewModel.deleteSection(target.sectionId) },
            onDismiss = { deleteTarget = null },
            // P2-4：影响描述是异步加载的，加载完成前 impact 为 null——此时必须保持禁用，
            // 否则首帧会出现「闪现可点」，用户抢在数据返回前点删会以空影响执行删除。
            confirmEnabled = impact != null && impact.blockedReason == null,
        )
    }
}

/** 组合删除分区的确认文案：同时说明「账目去向 + 分类去向」（FR-40） */
@Composable
private fun sectionDeleteMessage(impact: SectionDeleteImpact?): String {
    // 阻塞原因由数据层给出可读文案，直接展示
    impact?.blockedReason?.let { return it }
    val entryCount = impact?.entryCount ?: 0
    val exclusiveCount = impact?.exclusiveCategoryCount ?: 0
    // v4：删除提示文案不拼 emoji（这是读给用户听的字，图标在此无信息量）
    val fallbackName = impact?.fallback?.name
    val entriesLine = if (entryCount > 0 && fallbackName != null) {
        stringResource(R.string.delete_section_body_entries, entryCount, fallbackName)
    } else {
        stringResource(R.string.delete_section_body_no_entries)
    }
    val categoriesLine = if (exclusiveCount > 0) {
        stringResource(R.string.delete_section_body_keep_cats, exclusiveCount)
    } else {
        stringResource(R.string.delete_section_body_no_cats)
    }
    return "$entriesLine\n$categoriesLine"
}
