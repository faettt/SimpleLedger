package com.simpleledger.app.ui.section

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.repo.CategoryDeleteImpact
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.CategoryDeleteDialog
import com.simpleledger.app.ui.components.CategoryDialog
import com.simpleledger.app.ui.components.CategoryList
import com.simpleledger.app.ui.components.ConfirmDialog
import com.simpleledger.app.ui.components.ContentMaxWidth
import com.simpleledger.app.ui.components.ContentWidth
import com.simpleledger.app.ui.components.SectionDialog
import com.simpleledger.app.util.Money
import androidx.compose.foundation.layout.size
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.icon.slCategoryIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.FloatingActionButtonDefaults
import com.simpleledger.app.ui.components.SlSnackbarHost
import com.simpleledger.app.ui.components.slTitleRule
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.SlButtonShape

/**
 * 分区管理页（N10 / FR-18/19）。
 *
 * - 编辑分区信息（名称 / emoji / 备注 / 预算）
 * - 管理**该分区专属分类**：CRUD + 手动排序（只显示该分区的专属分类，不出现其他分区的）
 * - 删除分类给出「账目去向」确认文案，失败给可读原因（FR-41/42）
 */
@Composable
fun SectionManageScreen(
    sectionId: Long,
    onBack: () -> Unit,
    layout: WindowLayout = WindowLayout.Compact,
    viewModel: SectionManageViewModel = viewModel(
        key = "section_manage_$sectionId",
        factory = SectionManageViewModel.factory(sectionId),
    ),
) {
    val state by viewModel.state.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var categoryTypeFilter by remember { mutableStateOf(EntryType.EXPENSE) }
    var showSectionDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryEditTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleteImpact by remember { mutableStateOf<CategoryDeleteImpact?>(null) }

    LaunchedEffect(error) {
        error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }
    LaunchedEffect(deleteTarget) {
        deleteImpact = deleteTarget?.let { viewModel.categoryImpact(it.id) }
    }

    Scaffold(
        // ⚠️ 外层 AppRoot 已消费 systemBars insets，内层归零防双重避让（详见 SectionHomeScreen 注释）
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
        snackbarHost = { SlSnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // 手账不用阴影：层级由「纸叠纸」表达，FAB 也拉到 0
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                shape = SlButtonShape,
                onClick = {
                categoryEditTarget = null
                showCategoryDialog = true
            }) {
                Icon(SlIcons.Ui.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.new_category))
            }
        },
    ) { padding ->
        val body: @Composable () -> Unit = {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                    // 楷体页眉 + 签名双线（规范 §2.2）
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.section_manage_title),
                            // 页面主标题装饰位：headlineK（楷体 Regular，decorativeWeightRule）
                            style = SlType.headline,
                            modifier = Modifier.slTitleRule(),
                        )
                    }
                }

                // 分区信息卡（编辑入口）
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // v4：标题改「图标 + 分区名」（emoji 退场）
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                state.section?.let {
                                    Icon(
                                        imageVector = slCategoryIcon(it.iconId),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(7.dp))
                                }
                                Text(
                                    text = state.section?.name ?: "",
                                    // 分区名装饰位：titleK（楷体 Regular）
                                    style = SlType.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val budget = state.section?.budgetCents ?: 0L
                            val budgetLine = if (budget > 0) {
                                stringResource(R.string.section_manage_budget, Money.formatWithSymbol(budget))
                            } else {
                                stringResource(R.string.budget_none)
                            }
                            Text(
                                text = buildString {
                                    append(budgetLine)
                                    state.section?.note?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                },
                                style = SlType.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { showSectionDialog = true }) {
                            Text(stringResource(R.string.edit_section))
                        }
                    }
                }

                // 该分区专属分类
                Text(
                    text = stringResource(R.string.section_manage_exclusive_categories),
                    style = SlType.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp),
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CategoryList(
                        categories = if (categoryTypeFilter == EntryType.EXPENSE) {
                            state.expenseCategories
                        } else {
                            state.incomeCategories
                        },
                        typeFilter = categoryTypeFilter,
                        onTypeFilterChange = { categoryTypeFilter = it },
                        onEdit = {
                            categoryEditTarget = it
                            showCategoryDialog = true
                        },
                        onDelete = { deleteTarget = it },
                        onMove = { id, dir -> viewModel.moveCategory(id, categoryTypeFilter, dir) },
                        emptyText = stringResource(R.string.section_manage_no_categories),
                        bottomPadding = 96.dp,
                    )
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
            initial = state.section,
            onDismiss = { showSectionDialog = false },
            onSave = { id, name, emoji, note, budgetCents ->
                viewModel.saveSection(id, name, emoji, note, budgetCents)
                showSectionDialog = false
            },
        )
    }

    if (showCategoryDialog) {
        CategoryDialog(
            initial = categoryEditTarget,
            defaultType = categoryTypeFilter,
            onDismiss = { showCategoryDialog = false },
            onSave = { id, name, emoji, type ->
                viewModel.saveCategory(id, name, emoji, type)
                showCategoryDialog = false
            },
        )
    }

    deleteTarget?.let { target ->
        CategoryDeleteDialog(
            title = stringResource(R.string.delete_category_title, target.name),
            impact = deleteImpact,
            // B4：去向单选（默认「未分类」，哨兵恒排末尾）；destinationId 交给数据层解析兜底
            onConfirm = { destinationId ->
                viewModel.deleteCategory(target.id, destinationId)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}
