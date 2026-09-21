package com.simpleledger.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simpleledger.app.data.local.entity.ReimburseState
import com.simpleledger.app.ui.icon.SlIcons
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.incomeColor

/**
 * 账目状态符号簇（D4 裁定：**双维度独立字段**，可叠加）。
 *
 * 规范依据（docs/design/journal-style-spec-2026-09-20.md §2.4 / §3.5）：
 *
 * ```
 * 槽 1 核对：空（未核对） | ✓ 墨青（已核对）
 * 槽 2 报销：空（不适用） | ○ 朱砂（待报销） | ● 松烟（已报销）
 * ```
 *
 * 三个设计约束：
 * ① **簇内间距只有 2dp**，两个槽紧凑成一体；簇与分类名之间才留 7dp。
 *    这样既省宽度，又让「这是一组状态」读得出来。
 * ② **槽位恒定占位**（各 14dp）。空槽不收缩——否则同行不同账目的分类名会错位，
 *    流水列表的纵向节奏就断了。
 * ③ 颜色只表达**状态语义**（朱砂=待办、松烟=完成），不参与分区身份。
 *    分区身份由账目行左侧的 3dp 胶带色条承担（见 `EntryRow`）。
 *
 * 无障碍：本组件是**装饰性**的（`contentDescription = null`），
 * 状态词由账目行的读屏串统一给出（见 `LedgerRows` 的 speech 拼装），
 * 避免 TalkBack 把「图标 + 文本」读两遍。
 */
@Composable
fun EntryStatusCluster(
    reconciled: Boolean,
    reimburseState: Int,
    modifier: Modifier = Modifier,
) {
    val slot = 14.dp
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 槽 1：核对
        if (reconciled) {
            Icon(
                imageVector = SlIcons.Ui.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(slot),
            )
        } else {
            Spacer(Modifier.size(slot))
        }

        // 槽 2：报销
        when (reimburseState) {
            ReimburseState.PENDING -> Icon(
                imageVector = SlIcons.Status.Pending,
                contentDescription = null,
                tint = expenseColor(),
                modifier = Modifier.size(slot),
            )

            ReimburseState.CLEARED -> Icon(
                imageVector = SlIcons.Status.Cleared,
                contentDescription = null,
                tint = incomeColor(),
                modifier = Modifier.size(slot),
            )

            else -> Spacer(Modifier.size(slot))
        }
    }
}
