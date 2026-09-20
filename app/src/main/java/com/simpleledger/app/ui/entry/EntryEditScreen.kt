package com.simpleledger.app.ui.entry

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.LedgerApp

/**
 * 手机（Compact）宿主：全屏页面。
 * 平板与桌面不走这里——它们用列表页内的居中浮层 / 右侧面板，避免打断浏览上下文。
 */
@Composable
fun EntryEditScreen(
    entryId: Long,
    onDone: (Long?) -> Unit,
    viewModel: EntryEditViewModel = viewModel(
        key = "entry_$entryId",
        factory = EntryEditViewModel.factory(entryId),
    ),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
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
        if (state.saved) onDone(state.savedEntryId)
    }
    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }
    // 「保存并再记」不退出表单，提示就地消化
    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearNotice()
        }
    }

    Scaffold(
        topBar = {
            EntryFormHeader(
                isEdit = state.isEdit,
                onClose = { onDone(null) },
                onDelete = if (state.isEdit) {
                    { showDeleteConfirm = true }
                } else {
                    null
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        EntryEditForm(
            state = state,
            viewModel = viewModel,
            onPickImages = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.padding(padding),
            quickAmounts = quickAmounts.filter { it > 0 },
        )
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
