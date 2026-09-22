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
import com.simpleledger.app.ui.theme.KaitiFont
import com.simpleledger.app.ui.theme.TabularNums
import com.simpleledger.app.ui.theme.expenseColor
import com.simpleledger.app.ui.theme.warnColor
import com.simpleledger.app.util.Money
import com.simpleledger.app.ui.icon.slCategoryIcon
import androidx.compose.foundation.layout.PaddingValues
import com.simpleledger.app.ui.components.SlipCard
import com.simpleledger.app.ui.theme.tapeColor
import com.simpleledger.app.ui.theme.BarShape

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

    // 该分区的胶带色。分区身份的第一识别通道 —— 比读文字或认图标都快，
    // 也是 F4「去掉分区名文字前缀」成立的前提。
    val sectionColor = tapeColor(total.colorIndex)

    // 进度条填充三档（规范 §2.2 + §3.2 语义色表）：
    //   常态 = **该分区的胶带色**（不是主色！原本写死 primary 是规范违背，2026-09-21 修正）
    //   接近上限（≥90% 未超）= 预警 `#92570A`
    //   超支 = 朱砂 `#A83E33`
    // 三档都只改填充色，不改尺寸 —— 进度条高度恒定 6dp，避免"越紧张越跳动"。
    val barColor = when {
        overspent -> expenseColor()
        nearLimit -> warnColor()
        else -> sectionColor
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

    SlipCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = speech },
        edgeColor = sectionColor,
        // 左边距 20dp：让出 4dp 色条 + 呼吸；其余三边 16dp
        contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    // 分区卡片上的图标用**该分区的胶带色** —— F2「图标一律单色」的唯一例外：
                    // 这里颜色语义就是分区自己，不会与别的语义打架。
                    // （账目行 / 分类选择网格里的图标必须中性色，见 SlIconTile 的注释）
                    Icon(
                        imageVector = slCategoryIcon(total.iconId),
                        contentDescription = null,
                        tint = sectionColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = total.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        // 「分区名」装饰位用楷体（规范 D1）
                        fontFamily = KaitiFont,
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
                // 手账进度条：6dp 高 + 1dp 微圆角（规范 radius.progressBar）。
                // 原来用 99dp 胶囊是「UI 感」，与纸张的直角语言冲突。
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(BarShape),
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
