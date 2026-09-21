package com.simpleledger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simpleledger.app.ui.theme.SlipShape
import com.simpleledger.app.ui.theme.SlipStackOffset

/*
 * 反馈层：Snackbar 的**唯一宿主**。
 *
 * 规范依据：docs/design/journal-style-spec-2026-09-20.md §2.12（v2.1 新增）。
 *
 * ## 为什么要包一层宿主组件，而不是每处写 snackbar = { }
 *
 * 项目里原有 **7 处** `SnackbarHost(snackbarHostState)`（记账表单 / 分类管理 / 分区管理 /
 * 分区首屏 / 分区详情 / 我的 / 明细）。把自定义样式抄 7 遍，第一次改样式就会漏掉几处，
 * 于是同一个 App 里出现两种长相的提示条。全部改走本宿主之后，样式只有一个定义点。
 *
 * ## 容器语言：纸签叠垫纸
 *
 * 原先沿用 Material3 默认的 `inverseSurface` 反色浮层 —— 那是**阴影时代**的做法。
 * 规范 §1.4 早已把全项目的「层级表达」从阴影 elevation 换成纸叠纸
 * （背后错位 3dp 的垫纸 `#EAE3D2`），§1.2 也写明「手账里没有卡片阴影，只有纸叠纸」。
 * 实测旧样式比页面纸底 `#F7F3E9` 更暗、且无描边无垫纸，色差极小，
 * 读起来像"一块脏纸"而不是"浮在纸上的东西"。
 *
 * 于是这里逐项落实 §2.12：
 * · 纸片 `#FDFBF6` 填充 + 0.5dp `#C9BFA8` 描边 + 右下错位 3dp 垫纸 —— 与 `SlipCard`
 *   用的是同一套元素、同一个 [SlipStackOffset] 常量，不新造视觉词汇；
 * · 圆角 3dp（纸片圆角），无阴影；
 * · 左右 16dp 对齐页面内容边距，距底栏顶留 8dp 呼吸；
 * · 文字与动作按钮同为**主墨青**（对比 11.09:1），动作只靠文字表达，不加下划线，
 *   更**不加左侧语义色条** —— 左色条在本项目已强绑定「分区身份」(F4)，
 *   再让它表示"成功 / 失败"会违反 §3.4「不得出现两套色彩语义」。
 */

/**
 * Snackbar 宿主。用途与 `SnackbarHost` 完全一致，只是换成了手账的纸签样式。
 *
 * ```kotlin
 * Scaffold(snackbarHost = { SlSnackbarHost(snackbarHostState) }) { ... }
 * ```
 */
@Composable
fun SlSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data -> SlSnackbar(data) },
    )
}

/**
 * 单条纸签。
 *
 * 这里用的是 M3 的 [SnackbarData] 而不是自己接 `String`：`performAction()` 要把
 * 点击结果回传给 `showSnackbar` 的调用方（那里在等 `SnackbarResult`，删除后撤销
 * 就是靠它实现的），自己重造一份会把这条链路断掉。
 */
@Composable
private fun SlSnackbar(data: SnackbarData) {
    val stackOffset = SlipStackOffset
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 左右 16dp 对齐页面内容边距；底部留 8dp 呼吸 + 让出垫纸溢出的 3dp，
            // 否则垫纸会被宿主区域裁掉，"纸叠纸"就白做了
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp + stackOffset),
    ) {
        // 垫纸：与纸签同尺寸、右下错位，露出右下两条边 → 读作「下面还压着一张」。
        // 必须声明在纸签**之前**（Box 内后声明的画在上层）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = stackOffset, y = stackOffset)
                .background(MaterialTheme.colorScheme.surfaceDim, SlipShape),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SlipShape)
                .background(MaterialTheme.colorScheme.surface, SlipShape)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, SlipShape)
                // 48dp 触控友好高度。不必为了"看起来更轻"压薄 —— 提示条本来就该好点。
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = data.visuals.message,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            data.visuals.actionLabel?.let { label ->
                Spacer(modifier = Modifier.width(8.dp))
                // 用 TextButton 而不是给 Text 挂 clickable：TextButton 自带 48dp 最小触控区
                // 与按钮语义（TalkBack 会念成「按钮」而不是一段普通文字），这两件事自己糊容易漏。
                TextButton(
                    onClick = { data.performAction() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
