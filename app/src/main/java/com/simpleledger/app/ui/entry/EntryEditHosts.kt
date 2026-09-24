package com.simpleledger.app.ui.entry

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.R
import com.simpleledger.app.ui.theme.SlMotion
import com.simpleledger.app.ui.theme.SlipShape
import com.simpleledger.app.ui.theme.slTween

/** 记一笔 / 编辑账目在列表页内的两种承载形态 */
enum class EntryEditHostStyle {
    /** Medium（600–840dp）：居中浮层 560dp，压暗内容但保留导航 Rail */
    Centered,

    /** Expanded（≥840dp）：右侧面板 480dp，与列表并列、不遮挡 */
    Panel,
}

/**
 * 编辑宿主的**进出闸门**（Motion.kt「纸的物理」）。
 *
 * 直接 `if (visible) EntryEditHost(...)` 是瞬时装卸——浮层"啪"地出现/消失，
 * 与全项目的纸感节奏脱节。这里换成纸片进出：
 *
 * · **Centered（居中浮层）**：像一张纸轻放上桌——24dp 微升 + 淡入 250ms
 *   （PaperOut）；退场快抽——150ms 淡出（PaperIn）。
 * · **Panel（右侧板）**：像一张纸从右侧插进来——右移 24dp + 淡入 250ms；
 *   退场向右抽走 150ms。
 *
 * 退场动画期间内容保持**最后一次非空参数**继续组合（[lastEntryId]），
 * 否则 visible 翻 false 的瞬间表单已拿到空 id，退场动画画的是一张空白纸。
 */
@Composable
fun EntryEditHostGate(
    visible: Boolean,
    entryId: Long,
    sectionId: Long,
    style: EntryEditHostStyle,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onSaved: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    var lastEntryId by remember { mutableStateOf(entryId) }
    var lastSectionId by remember { mutableStateOf(sectionId) }
    if (visible) {
        lastEntryId = entryId
        lastSectionId = sectionId
    }

    // 位移像素：transition lambda 非组合上下文，在此一次性换算供闭包捕获
    val slidePx = with(LocalDensity.current) { SlMotion.ShiftStandard.roundToPx() }

    val enter: EnterTransition
    val exit: ExitTransition
    when (style) {
        // 居中浮层：轻放上桌（微升 24dp + 淡入），快抽离场（150ms 淡出 + 下沉 8dp）
        EntryEditHostStyle.Centered -> {
            enter = fadeIn(slTween(SlMotion.StandardMs, SlMotion.PaperOut)) +
                slideInVertically(slTween(SlMotion.StandardMs, SlMotion.PaperOut)) { slidePx }
            exit = fadeOut(slTween(SlMotion.FastMs, SlMotion.PaperIn)) +
                slideOutVertically(slTween(SlMotion.FastMs, SlMotion.PaperIn)) { slidePx / 3 }
        }

        // 右侧板：像一张纸从右侧插进来 / 向右抽走
        EntryEditHostStyle.Panel -> {
            enter = fadeIn(slTween(SlMotion.StandardMs, SlMotion.PaperOut)) +
                slideInHorizontally(slTween(SlMotion.StandardMs, SlMotion.PaperOut)) { slidePx }
            exit = fadeOut(slTween(SlMotion.FastMs, SlMotion.PaperIn)) +
                slideOutHorizontally(slTween(SlMotion.FastMs, SlMotion.PaperIn)) { slidePx }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
        modifier = modifier,
    ) {
        EntryEditHost(
            entryId = lastEntryId,
            sectionId = lastSectionId,
            style = style,
            snackbarHostState = snackbarHostState,
            onDismiss = onDismiss,
            onSaved = onSaved,
            modifier = Modifier.fillMaxSize(),
            dense = dense,
        )
    }
}

/**
 * 列表页内的编辑宿主。
 *
 * 与手机的全屏路由宿主共用同一个 [EntryEditForm]，因此键盘行为、日期时间选择器、
 * 贴图查看、校验与保存语义完全一致——差异只在外壳。
 */
@Composable
fun EntryEditHost(
    entryId: Long,
    sectionId: Long,
    style: EntryEditHostStyle,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onSaved: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    val viewModel: EntryEditViewModel = viewModel(
        key = "entry_$entryId",
        factory = EntryEditViewModel.factory(entryId, sectionId),
    )
    val state by viewModel.state.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val app = context.applicationContext as LedgerApp
    val quickAmounts by app.container.settings.quickAmounts.collectAsState()

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 6),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addImages(uris)
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            onSaved(state.savedEntryId)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.clearNotice()
        }
    }

    val form: @Composable (Modifier) -> Unit = { formModifier ->
        Column(modifier = formModifier) {
            EntryFormHeader(
                isEdit = state.isEdit,
                onClose = onDismiss,
                onDelete = if (state.isEdit) {
                    { showDeleteConfirm = true }
                } else {
                    null
                },
            )
            EntryEditForm(
                state = state,
                viewModel = viewModel,
                onPickImages = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 16.dp,
                ),
                quickAmounts = quickAmounts.filter { it > 0 },
            )
        }
    }

    when (style) {
        EntryEditHostStyle.Centered -> {
            BoxWithConstraints(
                modifier = modifier
                    .fillMaxSize()
                    // 不做「点击外部关闭」：表单里可能有已输入的内容，误触代价高于一次多余的点击
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center,
            ) {
                // 高度上限取两个约束的较小者：设计上限 760dp，以及「窗口高度 − 32dp」。
                // 矮窗口（手机横屏 412dp）下若仍用 760dp，Surface 会高出屏幕被裁切，
                // 表单底部的保存按钮将点不到——表单自身会滚动，所以限高是安全的。
                val maxPopupHeight = minOf(760.dp, maxHeight - 32.dp).coerceAtLeast(160.dp)
                Surface(
                    modifier = Modifier
                        .width(560.dp)
                        .heightIn(max = maxPopupHeight)
                        .padding(vertical = 16.dp),
                    // 对话框档 8dp（规范 D2，P1-2）：居中浮层是「对话框」性质，不再用 28dp 大圆角
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 12.dp,
                ) {
                    form(Modifier.fillMaxSize())
                }
            }
        }

        EntryEditHostStyle.Panel -> {
            Surface(
                // widthIn 而非固定 width：窗口刚好 840dp 时右侧只剩约 330dp，
                // 固定 480dp 会溢出被裁切。这样是「最多 480dp」，窄了就自适应。
                // 矮窗口再收到 420dp：与列表栏的收紧同步，避免左右都变胖把内容挤没
                modifier = modifier
                    .widthIn(max = if (dense) 420.dp else 480.dp)
                    .fillMaxHeight()
                    .padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
                // 纸片档 3dp + 0.5dp 描边（规范 §1.4「纸叠纸」，P1-2）：
                // 侧栏是贴在列表旁的纸片，不是浮层 —— 去阴影改描边，层级语言与 SlipCard 一致
                shape = SlipShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                form(Modifier.fillMaxSize())
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这笔账目？") },
            text = { Text("删除后可用明细页提示条里的「撤销」恢复，贴图也会一并删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteEntry()
                    },
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
