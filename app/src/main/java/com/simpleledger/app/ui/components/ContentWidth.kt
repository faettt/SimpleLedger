package com.simpleledger.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 内容最大宽度分档。
 *
 * 大屏上让文字段落铺满整屏会拉长视线回扫距离，反而更难读；因此按页面**信息类型**分档，
 * 而不是一刀切：
 * - [Wide] 图表 / 看板类：越宽越能展示数据关系，只限制到 1200dp 免得极端宽屏失衡；
 * - [Standard] 表单 / 列表类：720dp 是单列阅读的舒适上限；
 * - [Narrow] 设置 / 说明类：640dp 更紧凑，避免一行只有几个字的空旷感。
 */
object ContentMaxWidth {
    val Wide: Dp = 1200.dp
    val Standard: Dp = 720.dp
    val Narrow: Dp = 640.dp
}

/**
 * 把页面内容限制在 [maxWidth] 内并水平居中。
 *
 * 实现要点（顺序不可颠倒）：
 * 1. 外层 `Box(fillMaxSize)` 铺满可用空间——它是**滚动容器与内容之间的中立层**，本身不
 *    参与滚动，只负责居中；
 * 2. 内层先 `fillMaxHeight()` 再 `widthIn(max)` 再 `fillMaxWidth()`：
 *    - 想要「限宽但不拉伸」，直觉写法是 `fillMaxWidth().widthIn(max)`，但那样 `fillMaxWidth`
 *      会先把宽度顶到父级上限，后续 `widthIn(max)` 只剩「压小」的意义，居中也随之失效；
 *      正确顺序是让 `widthIn` 先限制上界，再 `fillMaxWidth` 在**该上界内**撑满；
 *    - `fillMaxHeight()` 必须在前：内层若全用 `widthIn`，高度会退化为 wrap-content，把它
 *      直接塞进 LazyColumn 会得到零高度、无法滚动。先占满高度，才能保住纵向滚动。
 *
 * 该组件本身**不含滚动**，滚动仍由页面自己的 `LazyColumn` / `verticalScroll` 承担。
 */
@Composable
fun ContentWidth(
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = maxWidth)
                .fillMaxWidth(),
        ) {
            content()
        }
    }
}