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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.data.settings.AppSettings
import java.io.File

@Composable
fun MineScreen(viewModel: MineViewModel = viewModel(factory = MineViewModel.Factory)) {
    val state by viewModel.state.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val hideAmounts by viewModel.hideAmounts.collectAsState()

    val context = LocalContext.current
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
        }
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.nav_mine),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
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
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            AppSettings.ThemeMode.all.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = AppSettings.ThemeMode.all.size,
                                    ),
                                ) {
                                    Text(AppSettings.ThemeMode.label(mode), fontSize = 13.sp)
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

                GroupTitle(stringResource(R.string.mine_group_data))
                ActionRow(
                    title = stringResource(R.string.mine_export_csv),
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

                GroupTitle(stringResource(R.string.mine_group_about))
                ActionRow(
                    title = stringResource(R.string.mine_version),
                    desc = versionName,
                    enabled = false,
                    onClick = {},
                )

                Spacer(modifier = Modifier.height(32.dp))
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
}

/** 隐私承诺卡：本地优先产品的信任基石，放在第一屏 */
@Composable
private fun PrivacyCard(countsText: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)),
                ),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(9.dp))
                Text(
                    text = stringResource(R.string.mine_privacy_title),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.mine_privacy_desc),
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = countsText,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun ActionRow(
    title: String,
    desc: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (desc.isNotBlank()) {
                    Text(
                        desc,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "›",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (desc.isNotBlank()) {
                    Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
