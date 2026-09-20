package com.simpleledger.app.ui.section

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.R
import com.simpleledger.app.data.local.entity.SectionTotal
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.logic.BudgetCalculator
import com.simpleledger.app.ui.theme.TabularNums
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.warnColor
import com.simpleledger.app.util.Money
import com.simpleledger.app.ui.icon.slCategoryIcon

/**
 * 分区首屏卡片：emoji + 分区名 + **本月花销** + **预算进度条**（FR-10）。
 *
 * 口径（FR-11/12）：
 * - 「本月花销」= 该分区当前自然月的**支出合计**（不含收入）；
 * - `预算 > 0` 才显示进度条，支出**正好等于**预算不算超支；用量 ≥90% 视为接近上限；
 * - 预算 = 0（未设预算）时只显示花销，不显示进度条。
 *
 * 无障碍（同步点 #6）：整卡给一段语义摘要，隐藏金额时**绝不朗读真实金额**。
 * 金额读写复用既有 `Money.toChineseSpeech`。
 */
@Composable
fun SectionCard(
    total: SectionTotal,
    reorderMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hidden = LocalHideAmounts.current
    val hasBudget = total.budgetCents > 0
    val ratio = BudgetCalculator.budgetRatio(total.expense, total.budgetCents)
    val overspent = BudgetCalculator.isOverspent(total.expense, total.budgetCents)
    val nearLimit = BudgetCalculator.isNearLimit(total.expense, total.budgetCents)

    val barColor = when {
        overspent -> expenseColor()
        nearLimit -> warnColor()
        else -> MaterialTheme.colorScheme.primary
    }

    // 读屏串：buildString 的 lambda 非 Composable，先把文案解析到局部变量
    val speechMonthExpense = stringResource(R.string.a11y_section_month_expense)
    val speechAmountHidden = stringResource(R.string.amount_hidden)
    val speechBudgetHidden = stringResource(R.string.a11y_budget_hidden)
    val speechOverspent = stringResource(
        R.string.a11y_overspent,
        Money.toChineseSpeech(total.expense - total.budgetCents),
    )
    val speechBudgetUsed = stringResource(R.string.a11y_budget_used, (ratio * 100).toInt())
    val speechNoBudget = stringResource(R.string.budget_none)
    val speech = buildString {
        append(total.name)
        append("，$speechMonthExpense")
        append(if (hidden) speechAmountHidden else Money.toChineseSpeech(total.expense))
        when {
            hidden && hasBudget -> append("，$speechBudgetHidden")
            overspent -> append("，$speechOverspent")
            hasBudget -> append("，$speechBudgetUsed")
            else -> append("，$speechNoBudget")
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = speech },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    // v4：emoji → 手绘图标。
                    // TODO(M2 主题重写)：此处改为「左侧 4dp 分区胶带色边 + 图标用胶带色」，
                    //   见 tokens-journal.json 的 tape.palette 与 F4 决策。
                    Icon(
                        imageVector = slCategoryIcon(total.iconId),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = total.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (hidden) "••••" else Money.formatWithSymbol(total.expense),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = TabularNums,
                        maxLines = 1,
                    )
                }
                if (reorderMode) {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.a11y_move_up_section),
                            tint = if (canMoveUp) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                            },
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.a11y_move_down_section),
                            tint = if (canMoveDown) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                            },
                        )
                    }
                }
            }

            if (hasBudget) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = barColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (hidden) {
                            stringResource(R.string.section_card_used_hidden)
                        } else {
                            stringResource(
                                R.string.section_card_used,
                                Money.formatWithSymbol(total.expense),
                                Money.formatWithSymbol(total.budgetCents),
                            )
                        },
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = TabularNums,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = when {
                            hidden -> ""
                            overspent -> stringResource(
                                R.string.section_card_overspent,
                                Money.formatWithSymbol(total.expense - total.budgetCents),
                            )
                            else -> stringResource(
                                R.string.section_card_remaining,
                                Money.formatWithSymbol(total.budgetCents - total.expense),
                            )
                        },
                        fontSize = 11.5.sp,
                        fontWeight = if (overspent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (overspent) expenseColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = TabularNums,
                    )
                }
            }
        }
    }
}
