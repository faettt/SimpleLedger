package com.simpleledger.app.ui.mine

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.data.settings.AppSettings
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.ContentMaxWidth
import com.simpleledger.app.ui.components.ContentWidth
import com.simpleledger.app.ui.security.canAuthenticate
import com.simpleledger.app.util.Money
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.material3.Icon
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.components.SlSnackbarHost
import com.simpleledger.app.ui.components.slTitleRule
import com.simpleledger.app.ui.sync.SyncStatusBadge
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.SlipShape
import com.simpleledger.app.ui.theme.SlChipShape
import com.simpleledger.app.ui.theme.slSegmentShape

/**
 * 「我的」页。
 *
 * [layout] 只在 Expanded 下用于居中限宽（设置/说明类内容舒适宽度 640dp，比列表类更紧）；
 * Compact / Medium 不做任何包装，与改动前逐像素一致。
 * 注意限宽只套在可滚动的设置列表上，页面中央的「处理中」遮罩仍铺满整屏。
 */
@Composable
fun MineScreen(
    layout: WindowLayout = WindowLayout.Compact,
    onNavigateGlobalCategories: () -> Unit = {},
    onNavigateSyncSettings: () -> Unit = {},
    onNavigateMembers: () -> Unit = {},
    onNavigateTrash: () -> Unit = {},
    viewModel: MineViewModel = viewModel(factory = MineViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val quickAmounts by viewModel.quickAmounts.collectAsState()
    var showQuickAmounts by remember { mutableStateOf(false) }
    val hideAmounts by viewModel.hideAmounts.collectAsState()
    val appLock by viewModel.appLock.collectAsState()
    val secureScreen by viewModel.secureScreen.collectAsState()
    // T-5 同步入口：四态角标 / 回收站计数 / 成员数
    val syncState by viewModel.syncState.collectAsState()
    val trashCount by viewModel.trashCount.collectAsState()
    val memberCount by viewModel.memberCount.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    val restorePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::requestRestore) }

    // 导出成功后交给系统分享 / 保存
    LaunchedEffect(state.shareFile) {
        val file = state.shareFile ?: return@LaunchedEffect
        shareFile(context, file)
        viewModel.consumeShareFile()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text = when (message) {
            MineMessage.EXPORT_FAILED -> context.getString(R.string.mine_export_failed)
            MineMessage.RESTORE_DONE -> context.getString(R.string.mine_restore_done)
            MineMessage.RESTORE_FAILED -> context.getString(R.string.mine_restore_failed)
            MineMessage.RESTORE_NOT_BACKUP -> context.getString(R.string.mine_restore_not_backup)
            MineMessage.RESTORE_NEWER_SCHEMA -> context.getString(R.string.mine_restore_newer_schema)
        }
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(
        // ⚠️ 外层 AppRoot 已消费 systemBars insets，内层归零防双重避让（详见 SectionHomeScreen 注释）
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
        snackbarHost = { SlSnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 可滚动的设置列表单独抽出：限宽外壳只切换包装，列表本体只有一份，避免两套代码走样
            val settings: @Composable () -> Unit = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.nav_mine),
                        // 页面主标题装饰位：headlineK（楷体 Regular）；bottom 14dp 给双线留出呼吸
                        style = SlType.headline,
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 14.dp)
                            .slTitleRule(),
                    )

                    PrivacyCard(
                        countsText = stringResource(
                            R.string.mine_entry_total,
                            state.entryCount,
                            state.sectionCount,
                            state.categoryCount,
                        ),
                    )

                    GroupTitle(stringResource(R.string.mine_group_appearance))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.mine_theme),
                                style = SlType.title,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                AppSettings.ThemeMode.all.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = themeMode == mode,
                                        onClick = { viewModel.setThemeMode(mode) },
                                        shape = slSegmentShape(
                                            index = index,
                                            count = AppSettings.ThemeMode.all.size,
                                        ),
                                    ) {
                                        // P2-2（fs2.0「跟随系统」换行 + ✓ 重叠）：
                                        // 段内文字单行 + autoSize 收缩，选中勾与文字不再打架
                                        Text(
                                            AppSettings.ThemeMode.label(mode),
                                            style = SlType.label,
                                            maxLines = 1,
                                            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.5.sp, stepSize = 0.25.sp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    SwitchRow(
                        title = stringResource(R.string.mine_dynamic_color),
                        desc = stringResource(R.string.mine_dynamic_color_desc),
                        checked = dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )

                    GroupTitle("记账")
                    ActionRow(
                        title = "快捷金额",
                        desc = quickAmounts.filter { it > 0 }
                            .joinToString(" · ") { Money.formatWithSymbol(it) }
                            .ifBlank { "未设置" },
                        enabled = true,
                        onClick = { showQuickAmounts = true },
                    )
                    // Q-03：全局分类入口（一次定义、到处可用）
                    ActionRow(
                        title = stringResource(R.string.mine_global_categories),
                        desc = stringResource(R.string.mine_global_categories_desc),
                        enabled = true,
                        onClick = onNavigateGlobalCategories,
                    )

                    // T-5：同步三入口（同步设置带四态角标、回收站带计数角标）
                    GroupTitle(stringResource(R.string.mine_sync_group))
                    ActionRow(
                        title = stringResource(R.string.mine_sync_settings),
                        desc = stringResource(R.string.mine_sync_settings_desc),
                        enabled = true,
                        onClick = onNavigateSyncSettings,
                        // 四态角标（U-4）：失败不弹窗，点角标进状态详情
                        trailing = { SyncStatusBadge(state = syncState, onClick = onNavigateSyncSettings) },
                    )
                    ActionRow(
                        title = stringResource(R.string.mine_sync_members),
                        desc = stringResource(R.string.mine_sync_members_desc, memberCount),
                        enabled = true,
                        onClick = onNavigateMembers,
                    )
                    ActionRow(
                        title = stringResource(R.string.mine_sync_trash),
                        desc = stringResource(R.string.mine_sync_trash_desc),
                        enabled = true,
                        onClick = onNavigateTrash,
                        // 有留底时数量角标（U-3）
                        trailing = { if (trashCount > 0) CountBadge(trashCount) },
                    )

                    GroupTitle(stringResource(R.string.mine_group_data))
                    ActionRow(                    title = stringResource(R.string.mine_export_csv),
                        desc = stringResource(R.string.mine_export_csv_desc),
                        enabled = !state.busy,
                        onClick = viewModel::exportCsv,
                    )
                    ActionRow(
                        title = stringResource(R.string.mine_backup),
                        desc = stringResource(R.string.mine_backup_desc),
                        enabled = !state.busy,
                        onClick = viewModel::createBackup,
                    )
                    ActionRow(
                        title = stringResource(R.string.mine_restore),
                        desc = stringResource(R.string.mine_restore_desc),
                        enabled = !state.busy,
                        onClick = {
                            restorePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        },
                    )

                    GroupTitle(stringResource(R.string.mine_group_security))
                    SwitchRow(
                        title = stringResource(R.string.mine_hide_amounts),
                        desc = stringResource(R.string.mine_hide_amounts_desc),
                        checked = hideAmounts,
                        onCheckedChange = viewModel::setHideAmounts,
                    )
                    SwitchRow(
                        title = stringResource(R.string.mine_app_lock),
                        desc = stringResource(R.string.mine_app_lock_desc),
                        checked = appLock,
                        // 设备无任何可用认证方式时**阻止开启**，否则开完就把自己锁在应用外面，且无路可退。
                        // 这是必须处理的失败路径：提示原因并保持开关为「关」。
                        onCheckedChange = { enable ->
                            if (!enable || canAuthenticate(context)) {
                                viewModel.setAppLock(enable)
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.mine_app_lock_unavailable)
                                    )
                                }
                            }
                        },
                    )
                    SwitchRow(
                        title = stringResource(R.string.mine_secure_screen),
                        desc = stringResource(R.string.mine_secure_screen_desc),
                        checked = secureScreen,
                        onCheckedChange = viewModel::setSecureScreen,
                    )

                    GroupTitle(stringResource(R.string.mine_group_about))
                    ActionRow(
                        title = stringResource(R.string.mine_version),
                        desc = versionName,
                        enabled = false,
                        onClick = {},
                    )

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }

            if (layout == WindowLayout.Expanded) {
                ContentWidth(maxWidth = ContentMaxWidth.Narrow) { settings() }
            } else {
                settings()
            }

            if (state.busy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    state.restoreConfirmUri?.let {
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(stringResource(R.string.mine_restore)) },
            text = { Text(stringResource(R.string.mine_restore_confirm)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) {
                    Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showQuickAmounts) {
        QuickAmountsDialog(
            initial = quickAmounts,
            onDismiss = { showQuickAmounts = false },
            onSave = { values ->
                viewModel.setQuickAmounts(values)
                showQuickAmounts = false
            },
        )
    }
}

/** 快捷金额档位设置：固定 3 个槽位，留空表示不在「记一笔」里显示该档 */
@Composable
private fun QuickAmountsDialog(
    initial: List<Long>,
    onDismiss: () -> Unit,
    onSave: (List<Long>) -> Unit,
) {
    val texts = remember {
        mutableStateListOf<String>().apply {
            repeat(3) { index ->
                val cents = initial.getOrNull(index) ?: 0L
                add(if (cents > 0) Money.formatCents(cents).replace(",", "") else "")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快捷金额") },
        text = {
            Column {
                Text(
                    text = "在「记一笔」里显示为可一键填入的金额。留空表示不显示该档位。",
                    style = SlType.bodySm,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                texts.forEachIndexed { index, value ->
                    OutlinedTextField(
                        value = value,
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
                            if (valid && cleaned.length <= 10) texts[index] = cleaned
                        },
                        prefix = { Text("¥") },
                        placeholder = { Text("档位 ${index + 1}") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(texts.map { Money.parseToCents(it) ?: 0L }) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 隐私承诺卡：本地优先产品的信任基石，放在第一屏 */
@Composable
private fun PrivacyCard(countsText: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // v4：去渐变 —— 手账是平的，不用渐变；改为实心主色（墨青）。
            // 白色文字压在墨青上 11.47:1，无障碍无虞。
            // TODO(M3 纸片化)：此处改为「纸片 + 墨色左边条」，与其余卡片同一套容器语言。
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = SlipShape,
            )
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(Color.White.copy(alpha = 0.22f), SlChipShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // v4：字符 ✓ → 手绘 check-xs（xs 档描边 2.0，专为 11dp 白色小位设计）
                    Icon(
                        imageVector = SlIcons.Ui.CheckXs,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.mine_privacy_title),
                    style = SlType.title,
                    color = Color.White,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.mine_privacy_desc),
                style = SlType.bodySm,
                color = Color.White.copy(alpha = 0.88f),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = countsText,
                style = SlType.meta,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = SlType.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun ActionRow(
    title: String,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit,
    /** 行尾附加物（T-5：同步四态角标 / 回收站计数角标）；null = 只有「›」 */
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = SlType.title)
                if (desc.isNotBlank()) {
                    Text(
                        desc,
                        style = SlType.bodySm,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
            Text(
                "›",
                style = SlType.title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 小计数角标：凹面底 + 楷体小字（无颜色语义——颜色语义被分区身份独占） */
@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = SlChipShape,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = count.toString(),
            style = SlType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = SlType.title)
                if (desc.isNotBlank()) {
                    Text(desc, style = SlType.bodySm, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** 通过 FileProvider 把导出文件交给系统分享面板（保存到网盘 / 发给自己） */
private fun shareFile(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val mime = if (file.extension.equals("csv", true)) "text/csv" else "application/zip"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.mine_share))
        )
    }
}