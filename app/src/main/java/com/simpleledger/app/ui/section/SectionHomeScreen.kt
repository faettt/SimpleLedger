package com.simpleledger.app.ui.section

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import com.simpleledger.app.ui.components.slTitleRule
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.SlipShape
import com.simpleledger.app.ui.theme.slAnimateItem
import com.simpleledger.app.ui.theme.SlButtonShape
import kotlinx.coroutines.launch

/**
 * 分区首屏（默认 tab，FR-07）。
 *
 * - 卡片列表：emoji + 名 + 本月花销 + 预算进度条（FR-10~13）
 * - 顶部右上「排序」按钮切换排序态（Q-14 / 裁定 C-3：上下移，不做长按拖拽）
 * - 空态（0 分区）与「新建分区」入口（空 / 非空均有，EC-04 / EC-11a）
 * - Expanded 限宽 [ContentMaxWidth.Narrow]（640dp，P2-4：分区卡密度）
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
    // A1：长按分区卡弹出的纸片菜单锚点（记录分区 id， Popup 挂在对应卡片上）
    var menuTargetId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

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
        // ⚠️ 外层 AppRoot 的 Scaffold 已消费 systemBars insets，内层必须显式归零 ——
        // M3 Scaffold 默认 contentWindowInsets=systemBars 会把状态栏再扣一次，
        // 标题上方多出 ~52dp 空隙（实测标题距屏顶 124dp 而非 72dp）
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
        snackbarHost = { SlSnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // 手账不用阴影：层级由「纸叠纸」表达，FAB 也拉到 0
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                // M3 FAB 默认 16dp 圆角，收进「按钮 4」体系（P1-1 延伸收口）
                shape = SlButtonShape,
                onClick = {
                editTarget = null
                showSectionDialog = true
            }) {
                Icon(SlIcons.Ui.Add, contentDescription = null)
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
                    // 楷体页眉 + 签名双线（规范 §2.2）：双线跟标题文字宽，外包 Box 占位
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.nav_sections),
                            // 页面主标题装饰位：headlineK（楷体 Regular）
                            style = SlType.headline,
                            modifier = Modifier.slTitleRule(),
                        )
                    }
                    if (!state.isEmpty) {
                        TextButton(onClick = viewModel::toggleReorder) {
                            Text(
                                if (state.reorderMode) {
                                    stringResource(R.string.section_sort_done)
                                } else {
                                    stringResource(R.string.section_sort)
                                },
                                style = SlType.label,
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
                            Box {
                                SectionCard(
                                    total = total,
                                    reorderMode = state.reorderMode,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < state.cards.lastIndex,
                                    onClick = { onOpenSection(total.sectionId) },
                                    onMoveUp = { viewModel.moveSection(total.sectionId, -1) },
                                    onMoveDown = { viewModel.moveSection(total.sectionId, +1) },
                                    // A1：长按分区卡 → 「编辑 / 删除分区」纸片菜单
                                    onLongClick = { menuTargetId = total.sectionId },
                                    // 增删移位动效（Motion）：重排/新建/删除分区时滑移过渡
                                    modifier = slAnimateItem(
                                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    ),
                                )
                                if (menuTargetId == total.sectionId) {
                                    SectionCardMenu(
                                        onEdit = {
                                            menuTargetId = null
                                            scope.launch {
                                                editTarget = viewModel.sectionOf(total.sectionId)
                                                showSectionDialog = true
                                            }
                                        },
                                        onDelete = {
                                            menuTargetId = null
                                            deleteTarget = total
                                        },
                                        onDismiss = { menuTargetId = null },
                                    )
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(96.dp)) }
                    }
                }
            }
        }

        if (layout == WindowLayout.Expanded) {
            ContentWidth(maxWidth = ContentMaxWidth.Narrow) { body() }
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

/**
 * 组合删除分区的确认文案（A1 新口径：分区 + 账目**一并删除并留底**，回收站整包恢复；
 * 专属分类降级为全局）。阻塞原因由数据层给出可读文案，直接展示。
 */
@Composable
private fun sectionDeleteMessage(impact: SectionDeleteImpact?): String {
    impact?.blockedReason?.let { return it }
    val entryCount = impact?.entryCount ?: 0
    val exclusiveCount = impact?.exclusiveCategoryCount ?: 0
    // v4：删除提示文案不拼 emoji（这是读给用户听的字，图标在此无信息量）
    val entriesLine = if (entryCount > 0) {
        stringResource(R.string.delete_section_body_entries, entryCount)
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

/**
 * 分区卡纸片菜单（A1 定案 V1「纸片菜单」）：纸面填充 + 0.5dp 描边 + 3dp 圆角
 * （与 ConfirmDialog / SlipCard 同一套纸语言），无阴影；「删除分区」用朱砂。
 * 锚定卡片右上、下移 57dp（卡头行高度，让菜单贴着触发手指出现在卡片身上）。
 */
@Composable
private fun SectionCardMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopEnd,
        // 锚定卡片右上、下移 57dp：与 A/B 实测的 DpOffset(0, 57dp) 同位
        offset = IntOffset(0, with(LocalDensity.current) { 57.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(168.dp)
                .background(MaterialTheme.colorScheme.surface, SlipShape)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, SlipShape),
        ) {
            SectionCardMenuItem(stringResource(R.string.section_menu_edit), danger = false, onClick = onEdit)
            SectionCardMenuItem(stringResource(R.string.section_menu_delete), danger = true, onClick = onDelete)
        }
    }
}

/** 纸片菜单项：整行可点（≥48dp 触控目标），危险项用 error 色 */
@Composable
private fun SectionCardMenuItem(label: String, danger: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Text(
            text = label,
            style = SlType.body,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
