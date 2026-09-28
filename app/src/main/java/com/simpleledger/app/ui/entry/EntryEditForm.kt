package com.simpleledger.app.ui.entry

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.CategoryEntity
import com.simpleledger.app.data.local.entity.EntryType
import com.simpleledger.app.data.local.entity.SectionEntity
import com.simpleledger.app.ui.components.CategoryDialog
import com.simpleledger.app.ui.components.slFilterChipColors
import com.simpleledger.app.ui.theme.SlButtonShape
import com.simpleledger.app.ui.theme.SlMotion
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.SlipShape
import com.simpleledger.app.ui.theme.incomeColor
import com.simpleledger.app.ui.theme.slSegmentShape
import com.simpleledger.app.ui.theme.slStandard
import com.simpleledger.app.ui.theme.slTween
import com.simpleledger.app.util.DateTimes
import com.simpleledger.app.util.Money
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import com.simpleledger.app.ui.icon.slCategoryIcon
import com.simpleledger.app.ui.icon.SlIcons

/**
 * 记一笔 / 编辑账目的表单主体。
 *
 * 刻意不依赖 Scaffold 与导航：三种宿主（手机全屏页、折叠屏居中浮层、大屏右侧面板）
 * 都复用这一个组件，保证行为完全一致——包括键盘、日期时间选择器与贴图查看。
 *
 * 「分区优先」后的变化：
 * - 移除「分区」chips，改为**只读分区行**（FR-21/22）；表单内不存在改分区控件（Q-07）
 * - 分类区**分组标题**「本分区专属」/「全局」（FR-24，允许同名不合并 Q-02）
 * - 空态 + 「＋ 新建分类」就地创建（EC-05）
 * - 编辑态把「不在候选内的当前分类」作为独立**历史分类** chip 呈现（EC-09）
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
    /** 「保存到相册」入口（D3 分档流程由宿主接线）；null = 不显示（除虫评审 #7 的 compact 缺口修复） */
    onSaveImage: ((String) -> Unit)? = null,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showCreateCategory by remember { mutableStateOf(false) }
    var enlargedPath by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            // 支出 / 收入
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.type == EntryType.EXPENSE,
                    onClick = { viewModel.setType(EntryType.EXPENSE) },
                    shape = slSegmentShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.expense)) }
                SegmentedButton(
                    selected = state.type == EntryType.INCOME,
                    onClick = { viewModel.setType(EntryType.INCOME) },
                    shape = slSegmentShape(index = 1, count = 2),
                ) {
                    Text(
                        stringResource(R.string.income),
                        color = if (state.type == EntryType.INCOME) {
                            incomeColor()
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 金额：绝对主角，超大字号 + 常驻光标
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("¥", style = SlType.display)
                OutlinedTextField(
                    value = state.amountText,
                    onValueChange = viewModel::setAmount,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    placeholder = { Text("0.00", style = SlType.display) },
                    // 金额输入 = display（40/44/700 自带 tnum，硬规则 R1）
                    textStyle = SlType.display,
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
                            label = { Text(presetLabel(cents), style = SlType.label) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 分区：只读展示（FR-22），不可点击改分区
            Text(stringResource(R.string.section), style = SlType.label)
            Spacer(modifier = Modifier.height(8.dp))
            ReadOnlySectionRow(state)

            Spacer(modifier = Modifier.height(16.dp))

            // 分类：分组呈现（专属在前 / 全局在后）+ 空态 + 就地新建
            Text(stringResource(R.string.category), style = SlType.label)
            Spacer(modifier = Modifier.height(8.dp))

            val candidatesEmpty =
                state.exclusiveCategories.isEmpty() && state.globalCategories.isEmpty()

            if (candidatesEmpty && state.historicalCategory == null) {
                // EC-05：当前分区 + 当前类型下候选集合为空 → 空态引导
                Text(
                    text = stringResource(R.string.category_empty_hint),
                    style = SlType.bodySm,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.historicalCategory?.let { historical ->
                    // EC-09：不在候选内的当前分类，独立呈现、保留、不静默改写
                    CategoryGroup(
                        title = stringResource(R.string.category_group_historical),
                        categories = listOf(historical),
                        selectedCategoryId = state.selectedCategoryId,
                        onSelect = viewModel::selectCategory,
                    )
                }
                if (state.exclusiveCategories.isNotEmpty()) {
                    CategoryGroup(
                        title = stringResource(R.string.category_group_exclusive),
                        categories = state.exclusiveCategories,
                        selectedCategoryId = state.selectedCategoryId,
                        onSelect = viewModel::selectCategory,
                    )
                }
                if (state.globalCategories.isNotEmpty()) {
                    CategoryGroup(
                        title = stringResource(R.string.category_group_global),
                        categories = state.globalCategories,
                        selectedCategoryId = state.selectedCategoryId,
                        onSelect = viewModel::selectCategory,
                    )
                }
            }
            TextButton(
                onClick = { showCreateCategory = true },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(stringResource(R.string.category_create_inline), style = SlType.label)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 时间
            Text("时间", style = SlType.label)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // v4：emoji 前缀 → 行内图标（inline 档 14dp；图标描述置 null，日期文本本身已是语义）
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = SlIcons.Ui.CalendarInline,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(DateTimes.dateLabel(DateTimes.toLocalDate(state.entryTime)))
                        }
                    },
                )
                AssistChip(
                    onClick = { showTimePicker = true },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = SlIcons.Ui.ClockInline,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(DateTimes.timeLabel(DateTimes.toLocalTime(state.entryTime)))
                        }
                    },
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
            Text("贴图", style = SlType.label)
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
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, SlipShape)
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
                                SlIcons.Ui.Close,
                                contentDescription = "移除图片",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                item {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, SlButtonShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(onClick = onPickImages) {
                            Text("＋ 图片", style = SlType.label)
                        }
                    }
                }
            }

        }

        // sticky 操作条（与筛选面板同一族修复，P1-D2）：主操作首屏可达，不随表单滚动
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.saving,
                    // M3 Button 默认胶囊，显式收 4dp（规范 D2，P1-1）
                    shape = SlButtonShape,
                    modifier = Modifier
                        .weight(if (state.isEdit) 1f else 1.4f)
                        // heightIn 而非 height：2.0× 字号下固定高度会把按钮文字竖向裁掉
                        .heightIn(min = 52.dp),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(SlIcons.Ui.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        // P2-3（fs2.0 换行）：按钮文字单行 + autoSize 收缩
                        Text(
                            if (state.isEdit) "保存修改" else stringResource(R.string.save),
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.5.sp, stepSize = 0.25.sp),
                        )
                    }
                }
                if (!state.isEdit) {
                    OutlinedButton(
                        onClick = viewModel::saveAndContinue,
                        enabled = !state.saving,
                        shape = SlButtonShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                    ) {
                        // P2-3（fs2.0 换行）：按钮文字单行 + autoSize 收缩
                        Text(
                            "保存并再记",
                            style = SlType.label,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.5.sp, stepSize = 0.25.sp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // 贴图放大查看：铺满宿主区域，点击任意处关闭。
        // 进出场与纸片菜单（SectionHomeScreen.kt 纸片菜单）同规格：进场标准档 250ms
        // PaperOut 淡入 + 从 0.96 居中放大（纸被轻放），出场快档 150ms PaperIn 淡出 +
        // 缩回（纸被抽走），不再瞬现瞬灭。
        //
        // enlargedPath 置 null 只应触发退场：visible 已为 false 而内容仍在播退场动画，
        // 仿 EntryEditHostGate 的 lastValue 保尾值模式，用 lastEnlargedPath 锁住最后
        // 一次非空路径——否则退场期间图片瞬间消失、只剩空壳动画。
        var lastEnlargedPath by remember { mutableStateOf(enlargedPath) }
        if (enlargedPath != null) {
            lastEnlargedPath = enlargedPath
        }
        AnimatedVisibility(
            visible = enlargedPath != null,
            enter = fadeIn(slStandard(SlMotion.PaperOut)) +
                scaleIn(slStandard(SlMotion.PaperOut), 0.96f, TransformOrigin.Center),
            exit = fadeOut(slTween(SlMotion.FastMs, SlMotion.PaperIn)) +
                scaleOut(slTween(SlMotion.FastMs, SlMotion.PaperIn), 0.96f, TransformOrigin.Center),
        ) {
            lastEnlargedPath?.let { path ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.92f))
                        .clickable { enlargedPath = null },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = stringResource(R.string.a11y_image_viewer_enlarged),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                    ) {
                        // D3「保存到相册」入口（除虫评审 #7：compact 全屏编辑此前无保存路径）；
                        // 按钮消费自身点击，不会触发背景的「点击任意处关闭」
                        if (onSaveImage != null) {
                            TextButton(onClick = { onSaveImage(path) }) {
                                Text(
                                    text = stringResource(R.string.save_to_gallery),
                                    color = Color.White,
                                    style = SlType.bodySm,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.image_viewer_close_hint),
                            color = Color.White.copy(alpha = 0.7f),
                            style = SlType.bodySm,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (showCreateCategory) {
        CategoryDialog(
            initial = null,
            defaultType = state.type,
            onDismiss = { showCreateCategory = false },
            onSave = { _, name, _, type ->
                // EC-05：类型 / 归属默认跟随当前表单与分区，故忽略 type 参数（由 VM 决定）
                // v4：就地新建不带图标选择（快速路径），新分类落默认图标 43 = tag（见 VM 注释）
                viewModel.createCategoryInline(name)
                showCreateCategory = false
            },
        )
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
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
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
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 只读分区行：图标 + 分区名（+ 提示），明确不可点击改分区（FR-22） */
@Composable
private fun ReadOnlySectionRow(state: EntryEditUiState) {
    val section = state.section
    // P2-4（fs2.0 分区名隐没）：大字号下 Name 独占一行、提示走第二行，
    // 免得提示文字把分区名挤成省略号
    val wideType = LocalConfiguration.current.fontScale <= 1.3f
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = SlButtonShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (wideType) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReadOnlySectionName(section, Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.section_readonly_hint),
                    style = SlType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        } else {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                ReadOnlySectionName(section, Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.section_readonly_hint),
                    style = SlType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 只读分区行的「图标 + 名称」段（两种布局共用；名称可省略号截断） */
@Composable
private fun ReadOnlySectionName(section: SectionEntity?, nameModifier: Modifier) {
    // v4：只读分区行改「图标 + 名称」（FR-22：明确不可点击改分区；emoji 退场）
    if (section != null) {
        Icon(
            imageVector = slCategoryIcon(section.iconId),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(7.dp))
    }
    Text(
        text = section?.name ?: stringResource(R.string.no_section),
        // 分区名位：title（楷体，字族一元）
        style = SlType.title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = nameModifier,
    )
}

/** 一组分类：分组小标题 + chips（专属 / 全局 / 历史共用） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryGroup(
    title: String,
    categories: List<CategoryEntity>,
    selectedCategoryId: Long?,
    onSelect: (Long) -> Unit,
) {
    Text(
        text = title,
        style = SlType.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        categories.forEach { category ->
            FilterChip(
                selected = selectedCategoryId == category.id,
                onClick = { onSelect(category.id) },
                label = {
                    // v4：分类候选 chip 改「图标 + 名称」（emoji 退场）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = slCategoryIcon(category.iconId),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(category.name)
                    }
                },
                // 分类候选 chip 走与其余 8 处筛选 chip 完全相同的统一配色口径
                // （slFilterChipColors 已同时含 selectedContainerColor + selectedLabelColor）
                colors = slFilterChipColors(),
            )
        }
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
            Icon(SlIcons.Ui.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
        }
        Text(
            text = stringResource(if (isEdit) R.string.edit_entry else R.string.add_entry),
            style = SlType.title,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        if (isEdit && onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    // 手绘描边墨青（P1-5）：不再用红实心垃圾桶双重强调，破坏性语义交给确认对话框
                    SlIcons.Ui.Delete,
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
