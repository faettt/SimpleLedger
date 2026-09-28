package com.simpleledger.app.ui.sync

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.MemberEntity
import com.simpleledger.app.ui.WindowLayout
import com.simpleledger.app.ui.components.ContentMaxWidth
import com.simpleledger.app.ui.components.ContentWidth
import com.simpleledger.app.ui.components.SlSnackbarHost
import com.simpleledger.app.ui.components.SlipCard
import com.simpleledger.app.ui.components.slTitleRule
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.theme.SlStatus
import com.simpleledger.app.ui.theme.SlType
import com.simpleledger.app.ui.theme.SlButtonShape
import com.simpleledger.app.ui.theme.SlChipShape

/*
 * 成员管理页（U-2/R-20/U-10）：认领成员名 · 成员列表 · 改名 · 隐藏。
 * 成员**可隐藏不可删**（主理人拍板）；改名对历史账目即时生效（EntryFull.member 关系）。
 */

@Composable
fun MemberManageScreen(
    onBack: () -> Unit,
    layout: WindowLayout = WindowLayout.Compact,
    viewModel: MemberManageViewModel = viewModel(factory = MemberManageViewModel.Factory),
) {
    val members by viewModel.members.collectAsState()
    val selfMemberId by viewModel.selfMemberId.collectAsState()
    val claimConflict by viewModel.claimConflict.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var claimName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<MemberEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    // 一次性事件 → 纸签；U-18：Channel 承载（接收即消费：展示中离开不回放、同值连发不合并）
    LaunchedEffect(Unit) {
        for (e in viewModel.events) {
            snackbarHostState.showSnackbar(eventText(context, e))
        }
    }

    // U-10 同名双留：并发撞名时 DB 双留（不加唯一约束），UI 提示改名消歧
    val duplicateNames = remember(members) {
        members.groupBy { it.name }.filterValues { it.size > 1 }.keys
    }

    Scaffold(
        // ⚠️ 外层 AppRoot 已消费 systemBars insets，内层归零防双重避让
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = Color.Transparent,
        snackbarHost = { SlSnackbarHost(snackbarHostState) },
    ) { padding ->
        val body: @Composable () -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(SlIcons.Ui.ArrowLeft, contentDescription = stringResource(R.string.back))
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.members_title),
                            style = SlType.headline,
                            modifier = Modifier.slTitleRule(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ——— 首次认领（本机尚无成员身份）———
                if (selfMemberId == null) {
                    SlipCard(stacked = true, contentPadding = PaddingValues(16.dp)) {
                        Text(stringResource(R.string.members_claim_title), style = SlType.title)
                        Text(
                            text = stringResource(R.string.members_claim_hint),
                            style = SlType.bodySm,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        OutlinedTextField(
                            value = claimName,
                            onValueChange = {
                                claimName = it
                                if (claimConflict) viewModel.clearClaimConflict()
                            },
                            label = { Text(stringResource(R.string.members_claim_name_hint), style = SlType.label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (claimConflict) {
                            Text(
                                text = stringResource(R.string.members_claim_conflict),
                                style = SlType.bodySm.merge(SlStatus.errorSm),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Button(
                            onClick = { viewModel.claim(claimName) },
                            enabled = claimName.isNotBlank(),
                            shape = SlButtonShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.members_claim), style = SlType.label)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // ——— U-10 同名双留提示改名（我起草的文案）———
                if (duplicateNames.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.members_dup_banner),
                        style = SlType.bodySm.merge(SlStatus.warningSm),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                if (members.isEmpty()) {
                    Text(
                        text = stringResource(R.string.members_empty),
                        style = SlType.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                } else {
                    members.forEach { member ->
                        MemberCard(
                            member = member,
                            isSelf = member.syncId == selfMemberId,
                            duplicate = member.name in duplicateNames,
                            onRename = {
                                renameTarget = member
                                renameText = member.name
                            },
                            onToggleHidden = { viewModel.setHidden(member.syncId, !member.hidden) },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        if (layout == WindowLayout.Expanded) {
            ContentWidth(maxWidth = ContentMaxWidth.Narrow) { body() }
        } else {
            body()
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = renameText,
            hint = stringResource(R.string.members_rename_hint),
            onValueChange = { renameText = it },
            onDismiss = { renameTarget = null },
            onConfirm = {
                viewModel.rename(target.syncId, renameText)
                renameTarget = null
            },
        )
    }
}

/** 成员行纸片：楷体名 + 「本机」/「已隐藏」标记 + 改名/隐藏（不可删） */
@Composable
private fun MemberCard(
    member: MemberEntity,
    isSelf: Boolean,
    duplicate: Boolean,
    onRename: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    SlipCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = member.name,
                style = SlType.title,
                modifier = Modifier.weight(1f),
            )
            if (isSelf) {
                SmallBadge(text = stringResource(R.string.members_self_badge), selected = true)
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (member.hidden) {
                SmallBadge(text = stringResource(R.string.members_hidden_badge), selected = false)
            }
        }
        if (duplicate) {
            Text(
                text = stringResource(R.string.members_dup_row),
                style = SlType.meta.merge(SlStatus.warningSm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = onRename) {
                Text(stringResource(R.string.members_rename), style = SlType.label)
            }
            TextButton(onClick = onToggleHidden) {
                Text(
                    text = stringResource(
                        if (member.hidden) R.string.members_unhide else R.string.members_hide,
                    ),
                    style = SlType.label,
                )
            }
        }
    }
}

/** 小标记：凹面底 + 楷体小字（无颜色语义，分区身份仍独占颜色） */
@Composable
private fun SmallBadge(text: String, selected: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = SlChipShape,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = if (selected) SlType.meta.merge(SlStatus.selectedSm) else SlType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    hint: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.members_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = initial,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.members_claim_name_hint), style = SlType.label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = hint,
                    style = SlType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = initial.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun eventText(context: Context, event: MemberManageEvent): String = when (event.message) {
    MemberManageMessage.NAME_REQUIRED -> context.getString(R.string.members_name_required)
    MemberManageMessage.NAME_TAKEN -> context.getString(R.string.members_name_taken)
    MemberManageMessage.CLAIM_OK -> context.getString(R.string.members_claim_done, event.name)
    MemberManageMessage.RENAME_OK -> context.getString(R.string.members_renamed, event.name)
    MemberManageMessage.HIDE_OK -> context.getString(R.string.members_hidden_ok, event.name)
    MemberManageMessage.UNHIDE_OK -> context.getString(R.string.members_unhidden_ok, event.name)
    MemberManageMessage.OP_FAILED -> context.getString(R.string.trash_action_failed)
}
