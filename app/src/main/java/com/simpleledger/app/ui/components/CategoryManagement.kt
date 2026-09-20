package com.simpleledger.app.ui.components

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.util.EmojiChoices
import com.simpleledger.app.util.Money

/*
 * 分区管理（N10）与全局分类管理（N12）**共用**的管理组件。
 *
 * 从原 `ManageScreen` 抽出：两处若各写一套，走样只是时间问题。这里保持「列表只上报意图、
 * 由各自页面持有确认弹窗与文案」的分工——因为删除分区 / 分类的确认文案在两种上下文里不同
 * （账目去向、分类去向），不适合塞进组件。
 */

/** 分区列表（分区管理页 / 分区首屏排序态均可复用）：emoji + 名称 + 预算/备注 + 上下移/编辑/删除 */
@Composable
fun SectionManageList(
    sections: List<SectionEntity>,
    onEdit: (SectionEntity) -> Unit,
    onDelete: (SectionEntity) -> Unit,
    onMove: (Long, Int) -> Unit,
    bottomPadding: Dp = 96.dp,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(sections, key = { _, section -> section.id }) { index, section ->
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val budgetLine = if (section.budgetCents > 0) {
                            stringResource(R.string.section_manage_budget, Money.formatWithSymbol(section.budgetCents))
                        } else {
                            stringResource(R.string.budget_none)
                        }
                        Text(
                            text = buildString {
                                append(budgetLine)
                                if (section.note.isNotBlank()) append(" · ${section.note}")
                            },
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconActionButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.a11y_move_up), enabled = index > 0) {
                        onMove(section.id, -1)
                    }
                    IconActionButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.a11y_move_down), enabled = index < sections.lastIndex) {
                        onMove(section.id, +1)
                    }
                    IconActionButton(Icons.Filled.Edit, stringResource(R.string.edit)) { onEdit(section) }
                    IconActionButton(Icons.Filled.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) {
                        onDelete(section)
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(bottomPadding)) }
    }
}

/**
 * 分类列表：类型切换 + 手动排序。
 *
 * [onDelete] 只上报「用户想删哪个」，确认弹窗与文案由各页面决定（分区专属 / 全局语义不同）。
 */
@Composable
fun CategoryList(
    categories: List<CategoryEntity>,
    typeFilter: Int,
    onTypeFilterChange: (Int) -> Unit,
    onEdit: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    onMove: (Long, Int) -> Unit,
    emptyText: String,
    bottomPadding: Dp = 96.dp,
) {
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
            ) { Text(stringResource(R.string.category_type_expense)) }
            SegmentedButton(
                selected = typeFilter == EntryType.INCOME,
                onClick = { onTypeFilterChange(EntryType.INCOME) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.category_type_income)) }
        }

        if (categories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyText,
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconActionButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.a11y_move_up), enabled = index > 0) {
                                onMove(category.id, -1)
                            }
                            IconActionButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.a11y_move_down), enabled = index < categories.lastIndex) {
                                onMove(category.id, +1)
                            }
                            IconActionButton(Icons.Filled.Edit, stringResource(R.string.edit)) { onEdit(category) }
                            IconActionButton(Icons.Filled.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) {
                                onDelete(category)
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(bottomPadding)) }
            }
        }
    }
}

@Composable
fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.28f),
            modifier = Modifier.size(19.dp),
        )
    }
}

/** 分区新建 / 编辑弹窗：名称 + emoji + 月度预算 + 分区备注（分区首屏与分区管理页共用） */
@Composable
fun SectionDialog(
    initial: SectionEntity?,
    onDismiss: () -> Unit,
    onSave: (id: Long?, name: String, emoji: String, note: String, budgetCents: Long) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "📌") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var budgetText by remember {
        mutableStateOf(
            initial?.budgetCents?.takeIf { it > 0 }?.let { Money.formatCents(it).replace(",", "") } ?: ""
        )
    }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.new_section else R.string.edit_section)) },
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
                        onValueChange = {
                            name = it
                            if (nameError && it.isNotBlank()) nameError = false
                        },
                        placeholder = { Text(stringResource(R.string.section_name_hint)) },
                        singleLine = true,
                        isError = nameError,
                        supportingText = if (nameError) {
                            { Text(stringResource(R.string.section_name_required)) }
                        } else {
                            null
                        },
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
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { input ->
                        val cleaned = input.filter { it.isDigit() || it == '.' }
                        val valid = cleaned.contains('.').let { hasDot ->
                            if (hasDot) {
                                val parts = cleaned.split('.')
                                parts.size <= 2 && (parts.getOrNull(1)?.length ?: 0) <= 2
                            } else {
                                true
                            }
                        }
                        if (valid && cleaned.length <= 12) budgetText = cleaned
                    },
                    placeholder = { Text(stringResource(R.string.section_dialog_budget_hint)) },
                    prefix = { Text("¥") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text(stringResource(R.string.section_dialog_note_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) {
                    // 校验失败时保持弹窗与已填内容，就地提示
                    nameError = true
                } else {
                    onSave(
                        initial?.id,
                        name.trim(),
                        emoji,
                        note,
                        Money.parseToCents(budgetText) ?: 0L,
                    )
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 分类新建 / 编辑弹窗：名称 + emoji + 类型（分区专属分类与全局分类管理页共用） */
@Composable
fun CategoryDialog(
    initial: CategoryEntity?,
    defaultType: Int,
    onDismiss: () -> Unit,
    onSave: (id: Long?, name: String, emoji: String, type: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "🏷️") }
    // P2-6：类型不可在弹窗内修改——由入口上下文决定（编辑沿用原类型；新建取入口的默认类型）。
    val type = initial?.type ?: defaultType
    var showEmojiPicker by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.new_category else R.string.edit_category)) },
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
                        onValueChange = {
                            name = it
                            if (nameError && it.isNotBlank()) nameError = false
                        },
                        placeholder = { Text(stringResource(R.string.category_name_hint)) },
                        singleLine = true,
                        isError = nameError,
                        supportingText = if (nameError) {
                            { Text(stringResource(R.string.category_name_required)) }
                        } else {
                            null
                        },
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
                // P2-6：弹窗内不再提供类型开关——类型由入口上下文决定（分区管理 / 全局分类页的类型
                // 切换，或记一笔表单的当前类型），弹窗里的开关会被调用方忽略，属「无效控件」。
                // 新建时用一行说明告知将创建的类型；编辑时不改类型，故不展示。
                if (initial == null) {
                    Text(
                        text = stringResource(
                            R.string.category_dialog_type_hint,
                            stringResource(
                                if (type == EntryType.INCOME) {
                                    R.string.category_type_income
                                } else {
                                    R.string.category_type_expense
                                }
                            ),
                        ),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) {
                    nameError = true
                } else {
                    onSave(initial?.id, name.trim(), emoji, type)
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** emoji 快速选择网格（分区 / 分类弹窗共用） */
@Composable
fun EmojiGrid(onPick: (String) -> Unit) {
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
