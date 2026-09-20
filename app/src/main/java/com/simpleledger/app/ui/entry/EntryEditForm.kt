package com.simpleledger.app.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 记一笔 / 编辑账目的表单主体。
 *
 * 刻意不依赖 Scaffold 与导航：三种宿主（手机全屏页、折叠屏居中浮层、大屏右侧面板）
 * 都复用这一个组件，保证行为完全一致——包括键盘、日期时间选择器与贴图查看。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryEditForm(
    state: EntryEditUiState,
    viewModel: EntryEditViewModel,
    onPickImages: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    quickAmounts: List<Long> = emptyList(),
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var enlargedPath by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            // 支出 / 收入
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.type == EntryType.EXPENSE,
                    onClick = { viewModel.setType(EntryType.EXPENSE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("支出") }
                SegmentedButton(
                    selected = state.type == EntryType.INCOME,
                    onClick = { viewModel.setType(EntryType.INCOME) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(
                        "收入",
                        color = if (state.type == EntryType.INCOME) {
                            incomeColor()
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 金额：绝对主角，44px 超大字号 + 常驻光标
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("¥", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = state.amountText,
                    onValueChange = viewModel::setAmount,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    placeholder = { Text("0.00", fontSize = 24.sp) },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }

            // 快捷金额：用户自定义档位，点一下直接填入
            if (quickAmounts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quickAmounts.forEach { cents ->
                        AssistChip(
                            onClick = { viewModel.fillAmount(cents) },
                            label = { Text(presetLabel(cents), fontSize = 13.sp) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 分类
            Text("分类", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.categories.forEach { category ->
                    FilterChip(
                        selected = state.selectedCategoryId == category.id,
                        onClick = { viewModel.selectCategory(category.id) },
                        label = { Text("${category.emoji} ${category.name}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 分区（含分区备注与月度预算提示）
            Text("分区", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sections, key = { it.id }) { section ->
                    FilterChip(
                        selected = state.selectedSectionId == section.id,
                        onClick = { viewModel.selectSection(section.id) },
                        label = { Text("${section.emoji} ${section.name}") },
                    )
                }
            }
            val currentSection = state.sections.firstOrNull { it.id == state.selectedSectionId }
            if (currentSection != null) {
                val hints = buildList {
                    if (currentSection.note.isNotBlank()) add("📌 ${currentSection.note}")
                    if (currentSection.budgetCents > 0) {
                        add("月度预算 ${Money.formatWithSymbol(currentSection.budgetCents)}")
                    }
                }
                if (hints.isNotEmpty()) {
                    Text(
                        text = hints.joinToString(" · "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 时间
            Text("时间", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text("📅 " + DateTimes.dateLabel(DateTimes.toLocalDate(state.entryTime))) },
                )
                AssistChip(
                    onClick = { showTimePicker = true },
                    label = { Text("🕐 " + DateTimes.timeLabel(DateTimes.toLocalTime(state.entryTime))) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 单独备注
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::setNote,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("备注（仅本笔可见，如：和谁一起、买了什么）") },
                minLines = 2,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 贴图
            Text("贴图", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.images, key = { it.key }) { image ->
                    Box(modifier = Modifier.size(84.dp)) {
                        AsyncImage(
                            model = image.localPath?.let { File(it) },
                            contentDescription = "贴图，点击放大",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .clickable { enlargedPath = image.localPath },
                        )
                        IconButton(
                            onClick = {
                                viewModel.removeImage(state.images.indexOfFirst { it.key == image.key })
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(24.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "移除图片",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                item {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(onClick = onPickImages) {
                            Text("＋ 图片", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 行动区：主「保存」+ 次「保存并再记」（仅新建时出现）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.saving,
                    modifier = Modifier
                        .weight(if (state.isEdit) 1f else 1.4f)
                        // heightIn 而非 height：2.0× 字号下固定 52dp 会把「保存修改」竖向裁掉，
                        // 用最小高度约束既保住原视觉，又允许文字放大时按钮自然长高
                        .heightIn(min = 52.dp),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (state.isEdit) "保存修改" else "保存")
                    }
                }
                if (!state.isEdit) {
                    OutlinedButton(
                        onClick = viewModel::saveAndContinue,
                        enabled = !state.saving,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                    ) {
                        // 去掉 maxLines=1：2.0× 字号下「保存并再记」需要换行显示，硬截断会丢字
                        Text("保存并再记", fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // 贴图放大查看：铺满宿主区域，点击任意处关闭
        enlargedPath?.let { path ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { enlargedPath = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = File(path),
                    contentDescription = "放大查看贴图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "点击任意处关闭",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp),
                )
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(state.entryTime)
                .atZone(ZoneId.systemDefault()).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { viewModel.setDateFromUtcMillis(it) }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        val local = DateTimes.toLocalTime(state.entryTime)
        val timeState = rememberTimePickerState(
            initialHour = local.hour,
            initialMinute = local.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setTime(LocalTime.of(timeState.hour, timeState.minute))
                        showTimePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
        )
    }
}

/** 快捷金额标签：整元显示为「¥10」，带角分则显示完整金额 */
private fun presetLabel(cents: Long): String = if (cents % 100L == 0L) {
    "¥${cents / 100}"
} else {
    "¥" + Money.formatCents(cents)
}

/** 表单顶部的标题栏（三种宿主共用，保证关闭与删除入口一致；按设计稿 ✕ 在左） */
@Composable
fun EntryFormHeader(
    isEdit: Boolean,
    onClose: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
        }
        Text(
            text = if (isEdit) "编辑账目" else "记一笔",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        if (isEdit && onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}
