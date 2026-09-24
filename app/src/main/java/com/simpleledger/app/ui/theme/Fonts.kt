package com.simpleledger.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.simpleledger.app.R

/* ============================================================
   字体设置（2026-09-24 字体体系 v2 · 一元楷体定案）
   ============================================================
   【字族一元制】界面**全部文字**只用一种字族：霞鹜文楷（LXGW WenKai）。
   内容层级/状态差异一律靠排版属性（字重/字号/行高/字距）与状态墨色表达，
   **严禁**通过更换字体种类区分内容（旧「装饰位楷体 / 信息位无衬线」二元制作废）。

   【中英文回退 · 内建在字形层】res/font/lxgw_wenkai.ttf 是 v1.522 全量源的
   定制子集，字符覆盖：
     ● ASCII 95/95（英文、数字、半角标点）
     ● GB2312 全集 6763 汉字 + 标点区（、。「」《》【】全角符号）
     ● 通用标点补缺（− U+2212、• U+2022、– "" ''、①②③、⚠、‰ …）
   —— 中文、英文、数字、常用符号**全部原生楷体**，不存在「缺字掉别的字体」。
   子集构建脚本：docs/design/font-subset/build.py（源 LXGWWenKai-Regular v1.522）。

   【终极缺字兜底 = 平台字体回退链】超出子集的生僻汉字、emoji 等，由
   Android Minikin 的系统字形回退自动补齐（系统衬线/无衬线/emoji）。
   注意：Compose 1.12 起 PlatformTextStyle(fontFamilyFallback) API 已整体移除，
   不再有「显式 Serif → SansSerif 链」的配置口；回退顺序由系统 fonts.xml 决定。
   回退仅用于补齐缺字，不参与内容区分（决策二）。
   ============================================================ */

/** 主字族：霞鹜文楷（OFL 协议）子集 —— 全界面唯一字族 */
val KaitiFont: FontFamily = FontFamily(Font(R.font.lxgw_wenkai))

/** token 基座：楷体，各档 token 由此 copy 出 */
internal val kai = TextStyle(fontFamily = KaitiFont)
