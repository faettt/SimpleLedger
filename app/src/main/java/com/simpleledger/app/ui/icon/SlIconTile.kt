package com.simpleledger.app.ui.icon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 手账风格的图标容器（手写件，**不在生成器范围内**）。
 *
 * 统一了原来散落在各处的「40dp 圆形 + 1dp 描边 + emoji Text」写法：
 * emoji 退场后，所有图标位都走这里，保证尺寸、描边、留白全站一致。
 *
 * 规范依据（docs/design/journal-style-spec-2026-09-20.md）：
 * · 图标用 [Icon] 渲染 ImageVector，颜色走 [tint] —— 默认 `LocalContentColor`，
 *   由父级的 `MaterialTheme.colorScheme.onSurface` 决定。**分类图标不得上分区色**
 *   （F2：同一行内颜色的唯一含义是分区）。
 * · 触控目标：本组件只负责"显示"，点击语义由调用方包 `clickable`，
 *   调用方必须保证最终可点区域 ≥ 48dp。
 *
 * @param icon        要显示的图标
 * @param tileSize    外框尺寸（默认 40dp，与原 emoji 圆框一致，列表行高不变）
 * @param iconSize    图标本体尺寸（默认 21dp ≈ 原 emoji 20sp 的光学大小）
 * @param contentDescription 无障碍描述；**装饰性图标必须传 null**，
 *                    否则 TalkBack 会把同一个图标读两遍（读屏已有相邻文字兜底）
 */
@Composable
fun SlIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tileSize: Dp = 40.dp,
    iconSize: Dp = 21.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = Color.Transparent,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    borderWidth: Dp = 1.dp,
    shape: Shape = CircleShape,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(tileSize)
            .background(containerColor, shape)
            .border(borderWidth, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 分类图标选择网格（取代原 42 格 emoji 网格 `EmojiGrid`）。
 *
 * 规范依据：
 * · 枚举来自 [SlCategoryIcons.allIcons]，50 枚、iconId 升序 —— 与数据库 `iconId` 语义一致，
 *   选中的值直接存库，不需要任何"emoji ↔ 图标"翻译。
 * · **单元格 48dp**：设计硬规则「触控目标 ≥ 48dp」。原 emoji 网格是 38dp，本来就不达标，
 *   这次一并修掉。
 * · 选中态 = 2dp 主色描边 + primaryContainer 底 —— 边框与底色**同时**变化
 *   （只靠变色对色弱用户不可辨，见设计硬规则 R6 的同源逻辑）。
 * · 高度封顶后内部滚动：50 枚在弹窗里不可能一次全铺开，固定高度比撑高弹窗更可控。
 *
 * @param selectedIconId 当前选中的 iconId（会高亮）
 * @param onPick         选中回调，回传 iconId（直接可存库）
 */
@Composable
fun SlIconGrid(
    selectedIconId: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    gridHeight: Dp = 260.dp,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.height(gridHeight),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(2.dp),
    ) {
        items(items = SlCategoryIcons.allIcons, key = { it.first }) { (iconId, icon) ->
            val selected = iconId == selectedIconId
            val shape = CircleShape
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(shape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                        shape,
                    )
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape,
                    )
                    .clickable { onPick(iconId) },
                contentAlignment = Alignment.Center,
            ) {
                // 装饰性图标：contentDescription 置 null。选中态由格子本身的
                // border/背景表达，TalkBack 语义由外层（如 semantics selected）补充更合适，
                // 这里如果给描述会导致 50 个图标被逐个念出来，反而不可用。
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
