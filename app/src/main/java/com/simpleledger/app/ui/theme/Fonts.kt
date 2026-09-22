package com.simpleledger.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.simpleledger.app.R

/**
 * 装饰位手写体：**霞鹜文楷**（LXGW WenKai，OFL 协议）子集 —— 规范三支柱之二。
 *
 * 只允许出现在 5 个装饰位（规范 D1）：**标题 / 日期 / 分区名 / 空态 / 页码（月份）**。
 * ⚠️ 硬规则 R1：**金额绝不使用楷体**（楷体数字是比例宽度，`999.00` 与 `1,111.00`
 * 小数点对不齐）—— 金额一律 [TabularNums] + 默认无衬线。
 *
 * 字体是子集（GB2312 一级汉字 + 数字 + 常用标点），用户输入的生僻字若不在子集内，
 * Android 平台的字体回退链会自动用系统无衬线补上（不会出豆腐块）。
 */
val KaitiFont: FontFamily = FontFamily(Font(R.font.lxgw_wenkai))
