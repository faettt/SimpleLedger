package com.simpleledger.app.ui.section

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.SectionEntity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import com.simpleledger.app.ui.icon.slCategoryIcon

/**
 * 明细页「记一笔」的**分区选择器**（Q-13：先进此步，不可跳过）。
 *
 * 这与 D8「记账必须先进分区」不冲突——它依然强制分区，只是把「选分区」这一步提前到入口，
 * 而非取消。0 分区时给出可读提示 + 去分区首屏新建（EC-04）。
 */
@Composable
fun SectionPickerDialog(
    sections: List<SectionEntity>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onGoToSections: () -> Unit,
) {
    if (sections.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.entry_pick_section_empty_title)) },
            text = { Text(stringResource(R.string.entry_pick_section_empty_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    onGoToSections()
                    onDismiss()
                }) { Text(stringResource(R.string.entry_pick_section_go)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.entry_pick_section)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                sections.forEach { section ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(section.id) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // v4：分区选择行改「图标 + 名称」（emoji 退场）
                        Icon(
                            imageVector = slCategoryIcon(section.iconId),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = section.name,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
