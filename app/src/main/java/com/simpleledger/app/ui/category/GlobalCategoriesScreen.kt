package com.simpleledger.app.ui.category

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.repo.CategoryDeleteImpact
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.CategoryDialog
import com.simpleledger.app.ui.components.CategoryList
import com.simpleledger.app.ui.components.ConfirmDialog
import com.simpleledger.app.ui.components.ContentMaxWidth
import com.simpleledger.app.ui.components.ContentWidth
import com.simpleledger.app.ui.section.categoryDeleteMessage
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.FloatingActionButtonDefaults
import com.simpleledger.app.ui.components.SlSnackbarHost
import com.simpleledger.app.ui.components.slTitleRule
import com.simpleledger.app.ui.theme.KaitiFont

/**
 * 全局分类管理页（N12 / Q-03）。
 *
 * 入口：「我的 → 记账 → 全局分类」。全局分类对**所有分区**可见；在此新增后，任一分区都能选到
 * （EC-01 验收）。排序作用域为 (类型, 全局)，与各分区专属分类互相独立。
 */
@Composable
fun GlobalCategoriesScreen(
    onBack: () -> Unit,
    layout: WindowLayout = WindowLayout.Compact,
    viewModel: GlobalCategoriesViewModel = viewModel(factory = GlobalCategoriesViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var categoryTypeFilter by remember { mutableStateOf(EntryType.EXPENSE) }
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

        containerColor = Color.Transparent,
        snackbarHost = { SlSnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // 手账不用阴影：层级由「纸叠纸」表达，FAB 也拉到 0
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),onClick = {
                categoryEditTarget = null
                showCategoryDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
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
                        .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                    // 楷体页眉 + 签名双线（规范 §2.2）
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.global_categories_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = KaitiFont,
                            modifier = Modifier.slTitleRule(),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.global_categories_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
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
                        emptyText = stringResource(R.string.global_categories_empty),
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
        val impact = deleteImpact
        ConfirmDialog(
            title = stringResource(R.string.delete_category_title, target.name),
            text = categoryDeleteMessage(impact),
            onConfirm = { viewModel.deleteCategory(target.id) },
            onDismiss = { deleteTarget = null },
            // P2-4：影响描述异步加载，加载完成前 impact 为 null——保持禁用，避免首帧闪现可点
            confirmEnabled = impact != null && impact.blockedReason == null,
        )
    }
}
