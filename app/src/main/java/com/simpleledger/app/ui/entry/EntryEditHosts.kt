package com.simpleledger.app.ui.entry

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.LedgerApp

/** 记一笔 / 编辑账目在列表页内的两种承载形态 */
enum class EntryEditHostStyle {
    /** Medium（600–840dp）：居中浮层 560dp，压暗内容但保留导航 Rail */
    Centered,

    /** Expanded（≥840dp）：右侧面板 480dp，与列表并列、不遮挡 */
    Panel,
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
    style: EntryEditHostStyle,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onSaved: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    val viewModel: EntryEditViewModel = viewModel(
        key = "entry_$entryId",
        factory = EntryEditViewModel.factory(entryId),
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
                    shape = RoundedCornerShape(28.dp),
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
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
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
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}
