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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.simpleledger.app.data.repo.GalleryExportOutcome
import com.simpleledger.app.ui.theme.SlEasing
import com.simpleledger.app.ui.theme.SlShift
import com.simpleledger.app.ui.theme.SlTempo
import com.simpleledger.app.ui.theme.SlipShape
import com.simpleledger.app.ui.theme.slScene
import kotlinx.coroutines.launch

/** 记一笔 / 编辑账目在列表页内的两种承载形态 */
enum class EntryEditHostStyle {
    /** Medium（600–840dp）：居中浮层 560dp，压暗内容但保留导航 Rail */
    Centered,

    /** Expanded（≥840dp）：右侧面板 480dp，与列表并列、不遮挡 */
    Panel,
}

/** 「保存到相册」SAF 续篇请求（D3 分档，与 LedgerScreen 同口径）：待导出贴图 + CreateDocument 建议名 / MIME */
internal data class SafExportRequest(
    val imagePath: String,
    val suggestedName: String,
    val mimeType: String,
)

/**
 * 编辑宿主的**进出闸门**（Motion.kt v2「有重量的纸」，场景轨）。
 *
 * 直接 `if (visible) EntryEditHost(...)` 是瞬时装卸——浮层"啪"地出现/消失，
 * 与全项目的纸感节奏脱节。这里换成纸片进出：
 *
 * · **Centered（居中浮层）**：像一张纸轻放上桌——24dp 微升 + 淡入 250ms
 *   （Enter）；退场快抽——150ms 淡出 + 下沉 8dp（Exit）。
 * · **Panel（右侧板）**：像一张纸从右侧插进来——右移 24dp + 淡入 250ms；
 *   退场向右抽走 150ms（Exit）。
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
    val slidePx = with(LocalDensity.current) { SlShift.Standard.roundToPx() }

    val enter: EnterTransition
    val exit: ExitTransition
    when (style) {
        // 居中浮层：轻放上桌（微升 24dp + 淡入），快抽离场（150ms 淡出 + 下沉 8dp）
        // ——出场的 8dp = 标准位移的 1/3，浮层是「就地沉下」不是「抽走」；
        // 与 Panel 的 24dp 不对称是裁定保留（motion-spec 走查基线）。
        EntryEditHostStyle.Centered -> {
            enter = fadeIn(slScene(SlTempo.Base, SlEasing.Enter)) +
                slideInVertically(slScene(SlTempo.Base, SlEasing.Enter)) { slidePx }
            exit = fadeOut(slScene(SlTempo.Fast, SlEasing.Exit)) +
                slideOutVertically(slScene(SlTempo.Fast, SlEasing.Exit)) { slidePx / 3 }
        }

        // 右侧板：像一张纸从右侧插进来 / 向右抽走（全程标准位移 24dp）
        EntryEditHostStyle.Panel -> {
            enter = fadeIn(slScene(SlTempo.Base, SlEasing.Enter)) +
                slideInHorizontally(slScene(SlTempo.Base, SlEasing.Enter)) { slidePx }
            exit = fadeOut(slScene(SlTempo.Fast, SlEasing.Exit)) +
                slideOutHorizontally(slScene(SlTempo.Fast, SlEasing.Exit)) { slidePx }
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

    // ---------- 「保存到相册」（D3 分档，与 LedgerScreen 详情栏同口径） ----------
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
                        duration = SnackbarDuration.Short,
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
                duration = SnackbarDuration.Short,
            )
            is GalleryExportOutcome.NeedsSaf -> pendingSafExport = SafExportRequest(
                imagePath = imagePath,
                suggestedName = outcome.suggestedName,
                mimeType = outcome.mimeType,
            )
            is GalleryExportOutcome.Failed -> snackbarHostState.showSnackbar(
                message = context.getString(R.string.save_to_gallery_failed, outcome.reason),
                duration = SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            // U-13：先取局部变量、先消费（复位 VM 的 saved 终态）、再回调——本宿主按
            // viewModel(key = "entry_$entryId") 在同一返回栈条目内缓存 VM，saved 原先
            // 永不复位：保存/删除关闭面板后再点开同一笔账，LaunchedEffect 重进组合读到
            // 残留 true 会立即再回调 onSaved，面板闪现即自动关闭并重复弹「已记入」。
            val savedId = state.savedEntryId
            viewModel.consumeSaved()
            onSaved(savedId)
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
                onSaveImage = { path -> scope.launch { saveImageWithNotice(path) } },
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
