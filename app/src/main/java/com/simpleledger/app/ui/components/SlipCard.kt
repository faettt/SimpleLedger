package com.simpleledger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simpleledger.app.ui.theme.SlipShape
import com.simpleledger.app.ui.theme.SlipStackOffset

/**
 * 纸片：手账里承载内容的「一张纸」。
 *
 * 规范依据（docs/design/journal-style-spec-2026-09-20.md §2.2 / §5）：
 *
 * · **不用阴影**。手账里没有卡片投影，只有一张纸压在另一张纸上。
 *   层级由三样东西表达：纸片填充（比页面亮一层）、0.5dp 描边、
 *   以及可选的 [stacked]「垫纸」（背后错位 3dp 的更深一层纸）。
 * · 圆角 3dp（近直角）。大圆角会退回「便当盒贴纸」的观感。
 * · 内边距默认 20dp（规范 `spacing.slipPadding`）。
 *
 * @param stacked 是否在背后垫一层错位纸。用于需要"这张纸被特别放在上面"的强调位
 *               （如分区首屏的选中卡、空态引导卡）。注意它会向右下溢出 3dp，
 *               调用方应确保四周有余量。
 * @param edgeColor 左侧竖边色。传入分区胶带色即成「分区卡」——分区身份的第一识别通道，
 *                  比读文字快。**必须画在内部**：画在 modifier 上会被纸片自身的填充盖住。
 * @param onClick 传入则整张纸可点（触控目标由调用方保证 ≥48dp）。
 */
@Composable
fun SlipCard(
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    edgeColor: Color? = null,
    edgeWidth: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier) {
        if (stacked) {
            // 垫纸：与纸片同尺寸、错位 3dp，露出右下两条边 → 读作「下面还压着一张」
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = SlipStackOffset, y = SlipStackOffset)
                    .background(MaterialTheme.colorScheme.surfaceDim, SlipShape),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SlipShape)
                .background(MaterialTheme.colorScheme.surface, SlipShape)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, SlipShape)
                // 左侧边：画在填充与描边**之后**（内层），否则会被纸片填充盖掉
                .then(
                    if (edgeColor != null) {
                        Modifier.drawBehind {
                            drawRect(edgeColor, size = Size(edgeWidth.toPx(), size.height))
                        }
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
                )
                .padding(contentPadding),
            content = content,
        )
    }
}
