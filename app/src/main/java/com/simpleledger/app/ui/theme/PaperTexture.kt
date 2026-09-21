package com.simpleledger.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 纸质层：在底色上叠一层极弱的横线纹样。
 *
 * 规范依据（docs/design/journal-style-spec-2026-09-20.md §1.3 硬规则 R2）：
 *
 * ① **纹样不承担文字对齐职责**。
 *    线只负责「这是一张纸」，不参与排版。所以线与字**刻意不齐**——
 *    如果让正文落在横线上，就得锁死行高，而系统字号一旦放大到 2.0×
 *    文字会顶破行线或被迫溢出滚动（与验收项冲突）。
 *    本实现因此按固定 dp 间距平铺，与内容行高无关。
 *
 * ② **不透明度 ≤ 4%**，且必须实测不让正文跌破 4.5:1。
 *
 * ⚠️ 为什么用「主墨 4%」而不是直接用分隔线色 `outline`（#E4DCC8）：
 *    分隔线与纸底本就接近，再叠 4% 之后几乎不可见（等于没画）。
 *    用主墨按 4% 混出的实际线色约 #EEEBE2 —— 视觉上就是一条很淡的纸纹，
 *    而正文墨青压在其上仍有 **9.68:1**，远高于 AA 门槛。
 *    也就是说：**「4%」约束的是不透明度，不是指定某个颜色**。
 *
 * @param spacing   线间距（规范 28dp）
 * @param lineWidth 线宽（规范 1dp）
 * @param opacity   不透明度（规范上限 0.04）
 */
@Composable
fun Modifier.paperTexture(
    spacing: Dp = 28.dp,
    lineWidth: Dp = 1.dp,
    opacity: Float = 0.04f,
    color: Color = MaterialTheme.colorScheme.onBackground,
): Modifier {
    val density = LocalDensity.current
    val stepPx = with(density) { spacing.toPx() }
    val widthPx = with(density) { lineWidth.toPx() }
    val lineColor = color.copy(alpha = opacity)

    return drawBehind {
        // 从第一条线开始平铺到屏幕底部。整屏约 20–30 条，逐条 drawRect 的开销可忽略。
        var y = stepPx
        while (y < size.height) {
            drawRect(
                color = lineColor,
                topLeft = Offset(0f, y),
                size = Size(size.width, widthPx),
            )
            y += stepPx
        }
    }
}
