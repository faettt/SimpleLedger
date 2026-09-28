package com.simpleledger.app.ui.entry

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.LedgerApp
import com.simpleledger.app.R
import com.simpleledger.app.data.repo.GalleryExportOutcome
import androidx.compose.ui.graphics.Color
import com.simpleledger.app.ui.components.SlSnackbarHost
import kotlinx.coroutines.launch

/**
 * 手机（Compact）宿主：全屏页面。
 * 平板与桌面不走这里——它们用列表页内的居中浮层 / 右侧面板，避免打断浏览上下文。
 *
 * [sectionId] 为记账分区上下文（新建=入口带入；编辑=账目所属），表单内只读（Q-07）。
 */
@Composable
fun EntryEditScreen(
    entryId: Long,
    sectionId: Long,
    onDone: (Long?) -> Unit,
    viewModel: EntryEditViewModel = viewModel(
        key = "entry_$entryId",
        factory = EntryEditViewModel.factory(entryId, sectionId),
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

    // ---------- 「保存到相册」（D3 分档，与 EntryEditHost / LedgerScreen 同口径） ----------
    val scope = rememberCoroutineScope()
    var pendingSafExport by remember { mutableStateOf<SafExportRequest?>(null) }
    val safExportLauncher = key(pendingSafExport?.mimeType ?: "image/*") {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(pendingSafExport?.mimeType ?: "image/*"),
        ) { created ->
            val request = pendingSafExport
            pendingSafExport = null
            if (created != null && request != null) {
                scope.launch {
                    val ok = viewModel.writeExportImageToSafTarget(created, request.imagePath)
                    snackbarHostState.showSnackbar(
                        message = context.getString(
                            if (ok) R.string.save_to_gallery_done
                            else R.string.save_to_gallery_write_failed
                        ),
                        duration = androidx.compose.material3.SnackbarDuration.Short,
                    )
                }
            }
        }
    }
    LaunchedEffect(pendingSafExport) {
        pendingSafExport?.let { request -> safExportLauncher.launch(request.suggestedName) }
    }
    suspend fun saveImageWithNotice(imagePath: String) {
        when (val outcome = viewModel.exportImageToGallery(imagePath)) {
            is GalleryExportOutcome.Saved -> snackbarHostState.showSnackbar(
                message = context.getString(R.string.save_to_gallery_done),
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
            is GalleryExportOutcome.NeedsSaf -> pendingSafExport = SafExportRequest(
                imagePath = imagePath,
                suggestedName = outcome.suggestedName,
                mimeType = outcome.mimeType,
            )
            is GalleryExportOutcome.Failed -> snackbarHostState.showSnackbar(
                message = context.getString(R.string.save_to_gallery_failed, outcome.reason),
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            // U-13：与 EntryEditHost 同口径——先消费 saved 终态再回调。Compact 全屏路由
            // 每次导航新建 VM（不复用），此处消费属防御性对齐，保证两处宿主行为一致。
            val savedId = state.savedEntryId
            viewModel.consumeSaved()
            onDone(savedId)
        }
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
        // ⚠️ 外层 AppRoot 已消费 systemBars insets，内层归零防双重避让（详见 SectionHomeScreen 注释）
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
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
        snackbarHost = { SlSnackbarHost(snackbarHostState) },
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
            onSaveImage = { path -> scope.launch { saveImageWithNotice(path) } },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            // U-14：文案单一真源（EntryDeleteConfirm.kt）——旧文案承诺的「提示条撤销」
            // 该路径并不存在，且贴图文件按留底策略保留，据实改写
            title = { Text(ENTRY_DELETE_CONFIRM_TITLE) },
            text = { Text(ENTRY_DELETE_CONFIRM_BODY) },
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
