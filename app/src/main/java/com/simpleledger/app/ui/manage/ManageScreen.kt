package com.simpleledger.app.ui.manage

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.ui.components.ConfirmDialog
import com.simpleledger.app.util.EmojiChoices

private enum class ManageTab { SECTIONS, CATEGORIES }

@Composable
fun ManageScreen(viewModel: ManageViewModel = viewModel(factory = ManageViewModel.Factory)) {
    val state by viewModel.state.collectAsState()
    val error by viewModel.error.collectAsState()
    var tab by remember { mutableIntStateOf(ManageTab.SECTIONS.ordinal) }
    val snackbarHostState = remember { SnackbarHostState() }

    var sectionDialogTarget by remember { mutableStateOf<SectionEntity?>(null) }
    var showSectionDialog by remember { mutableStateOf(false) }
    var categoryDialogTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var categoryTypeFilter by remember { mutableStateOf(EntryType.EXPENSE) }

    LaunchedEffect(error) {
        error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (tab == ManageTab.SECTIONS.ordinal) {
                        sectionDialogTarget = null
                        showSectionDialog = true
                    } else {
                        categoryDialogTarget = null
                        showCategoryDialog = true
                    }
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (tab == ManageTab.SECTIONS.ordinal) "新建分区" else "新建分类")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("分区") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("分类") })
            }

            if (tab == ManageTab.SECTIONS.ordinal) {
                SectionList(
                    sections = state.sections,
                    onEdit = {
                        sectionDialogTarget = it
                        showSectionDialog = true
                    },
                    onDelete = viewModel::deleteSection,
                    onMove = viewModel::moveSection,
                    bottomPadding = 96.dp,
                )
            } else {
                CategoryList(
                    categories = if (categoryTypeFilter == EntryType.EXPENSE) {
                        state.expenseCategories
                    } else {
                        state.incomeCategories
                    },
                    typeFilter = categoryTypeFilter,
                    onTypeFilterChange = { categoryTypeFilter = it },
                    onEdit = {
                        categoryDialogTarget = it
                        showCategoryDialog = true
                    },
                    onDelete = viewModel::deleteCategory,
                    onMove = { id, dir -> viewModel.moveCategory(id, categoryTypeFilter, dir) },
                    bottomPadding = 96.dp,
                )
            }
        }
    }

    if (showSectionDialog) {
        SectionDialog(
            initial = sectionDialogTarget,
            onDismiss = { showSectionDialog = false },
            onSave = { id, name, emoji, note ->
                viewModel.saveSection(id, name, emoji, note)
                showSectionDialog = false
            },
        )
    }

    if (showCategoryDialog) {
        CategoryDialog(
            initial = categoryDialogTarget,
            defaultType = categoryTypeFilter,
            onDismiss = { showCategoryDialog = false },
            onSave = { id, name, emoji, type ->
                viewModel.saveCategory(id, name, emoji, type)
                showCategoryDialog = false
            },
        )
    }
}

// ---------------------------------------------------------------- 分区列表

@Composable
private fun SectionList(
    sections: List<SectionEntity>,
    onEdit: (SectionEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    bottomPadding: Dp,
) {
    var deleteTarget by remember { mutableStateOf<SectionEntity?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(sections, key = { it.id }) { section ->
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(section.emoji, fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            section.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (section.note.isNotBlank()) {
                            Text(
                                section.note,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconActionButton(Icons.Filled.KeyboardArrowUp, "上移") { onMove(section.id, -1) }
                    IconActionButton(Icons.Filled.KeyboardArrowDown, "下移") { onMove(section.id, +1) }
                    IconActionButton(Icons.Filled.Edit, "编辑") { onEdit(section) }
                    IconActionButton(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error) {
                        deleteTarget = section
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(bottomPadding)) }
    }

    deleteTarget?.let { target ->
        val fallback = sections.firstOrNull { it.id != target.id }
        ConfirmDialog(
            title = "删除分区「${target.name}」？",
            text = fallback?.let {
                "删除后，该分区下的账目将自动移入「${it.emoji} ${it.name}」。"
            } ?: "至少需要保留一个分区。",
            onConfirm = {
                onDelete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

// ---------------------------------------------------------------- 分类列表

@Composable
private fun CategoryList(
    categories: List<CategoryEntity>,
    typeFilter: Int,
    onTypeFilterChange: (Int) -> Unit,
    onEdit: (CategoryEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    bottomPadding: Dp,
) {
    var deleteTarget by remember { mutableStateOf<CategoryEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(
                selected = typeFilter == EntryType.EXPENSE,
                onClick = { onTypeFilterChange(EntryType.EXPENSE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("支出分类") }
            SegmentedButton(
                selected = typeFilter == EntryType.INCOME,
                onClick = { onTypeFilterChange(EntryType.INCOME) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("收入分类") }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(categories, key = { it.id }) { category ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(category.emoji, fontSize = 20.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            category.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconActionButton(Icons.Filled.KeyboardArrowUp, "上移") { onMove(category.id, -1) }
                        IconActionButton(Icons.Filled.KeyboardArrowDown, "下移") { onMove(category.id, +1) }
                        IconActionButton(Icons.Filled.Edit, "编辑") { onEdit(category) }
                        IconActionButton(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error) {
                            deleteTarget = category
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(bottomPadding)) }
        }
    }

    deleteTarget?.let { target ->
        val fallback = categories.firstOrNull { it.id != target.id }
        ConfirmDialog(
            title = "删除分类「${target.name}」？",
            text = fallback?.let {
                "删除后，使用该分类的账目将自动改为「${it.emoji} ${it.name}」。"
            } ?: "至少需要保留一个分类。",
            onConfirm = {
                onDelete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun IconActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(19.dp))
    }
}

// ---------------------------------------------------------------- 弹窗

/** 分区新建 / 编辑弹窗：名称 + emoji + 分区备注 */
@Composable
private fun SectionDialog(
    initial: SectionEntity?,
    onDismiss: () -> Unit,
    onSave: (id: Long?, name: String, emoji: String, note: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "📌") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var showEmojiPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建分区" else "编辑分区") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .clickable { showEmojiPicker = !showEmojiPicker },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("分区名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (showEmojiPicker) {
                    Spacer(modifier = Modifier.height(8.dp))
                    EmojiGrid(onPick = {
                        emoji = it
                        showEmojiPicker = false
                    })
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("分区备注（如：预算多少、给谁用）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(initial?.id, name, emoji, note) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 分类新建 / 编辑弹窗：名称 + emoji + 类型 */
@Composable
private fun CategoryDialog(
    initial: CategoryEntity?,
    defaultType: Int,
    onDismiss: () -> Unit,
    onSave: (id: Long?, name: String, emoji: String, type: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "🏷️") }
    var type by remember { mutableStateOf(initial?.type ?: defaultType) }
    var showEmojiPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建分类" else "编辑分类") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .clickable { showEmojiPicker = !showEmojiPicker },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("分类名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (showEmojiPicker) {
                    Spacer(modifier = Modifier.height(8.dp))
                    EmojiGrid(onPick = {
                        emoji = it
                        showEmojiPicker = false
                    })
                }
                Spacer(modifier = Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = type == EntryType.EXPENSE,
                        onClick = { type = EntryType.EXPENSE },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("支出") }
                    SegmentedButton(
                        selected = type == EntryType.INCOME,
                        onClick = { type = EntryType.INCOME },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("收入") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(initial?.id, name, emoji, type) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EmojiGrid(onPick: (String) -> Unit) {
    Column {
        EmojiChoices.chunked(7).forEach { rowEmojis ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowEmojis.forEach { candidate ->
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clickable { onPick(candidate) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(candidate, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}
