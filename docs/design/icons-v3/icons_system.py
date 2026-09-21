#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B/C/D/E/F/G 组图标 + App 图标（adaptive 三层 + 三个候选方案）"""

from spec import (Icon, rp, ci, fi, INK, PAPER, SLIP, RULE_STRONG,
                  EXPENSE, INCOME, TAPE)

# ---------------------------------------------------------------------
# B · 底部导航 4 枚（索引贴四槽；选中态用主墨、未选中用次要墨，靠 tint 实现）
# ---------------------------------------------------------------------
NAV = [
    Icon("nav-section", "nav", "full", [
        rp(6.2, 4.6, 13.2, 8.4, 1.4, 1.6, 1.5, 1.3),
        rp(4.4, 7.9, 15.2, 11.3, 1.4, 1.6, 1.5, 1.3),
        "M7.5 10.8v5.6",
    ], replaces="🗂️", replaces_type="emoji", usage="底部导航 · 分区（首屏）"),

    Icon("nav-ledger", "nav", "full", [
        rp(4.6, 4.5, 14.8, 15.0, 1.5, 1.7, 1.6, 1.4),
        "M8.1 4.7v14.6",
        "M10.8 8.5h5.2M10.8 12.0h5.2M10.8 15.5h3.2",
    ], replaces="📒", replaces_type="emoji", usage="底部导航 · 明细"),

    Icon("nav-stats", "nav", "full", [
        "M4.4 19.2h15.2",
        rp(6.3, 12.4, 3.0, 6.6, 0.8, 1.0, 0.9, 0.7),
        rp(10.5, 8.9, 3.0, 10.1, 0.8, 1.0, 0.9, 0.7),
        rp(14.7, 14.1, 3.0, 4.9, 0.8, 1.0, 0.9, 0.7),
    ], replaces="📊", replaces_type="emoji", usage="底部导航 · 统计"),

    Icon("nav-mine", "nav", "full", [
        ci(12, 8.5, 3.6),
        "M5.4 19.4c.6-3.6 3.2-5.6 6.6-5.6s6.0 2.0 6.6 5.6",
    ], replaces="👤", replaces_type="emoji", usage="底部导航 · 我的"),
]

# ---------------------------------------------------------------------
# C · 功能图标 9 枚（全部重画路径；代码里现用的 Material 默认图标由它们接班）
# ---------------------------------------------------------------------
FUNCTION = [
    Icon("add", "function", "full", [
        "M12 5.2v13.6M5.2 12h13.6",
    ], replaces="Icons.Filled.Add", replaces_type="material", usage="新增（新建分区 / 分类）"),

    Icon("close", "function", "full", [
        "M6.2 6.2l11.6 11.6M17.8 6.1L6.1 17.8",
    ], replaces="Icons.Filled.Close", replaces_type="material", usage="关闭（表单标题栏在左）"),

    Icon("delete", "function", "full", [
        "M4.6 6.4h14.8",
        "M9.8 6.4V4.9h4.4v1.5",
        "M6.6 6.6v11.1c0 1.2.9 2.1 2.1 2.1h6.6c1.2 0 2.1-.9 2.1-2.1V6.6",
        "M10.2 10.3v6.1M13.8 10.3v6.1",
    ], replaces="Icons.Filled.Delete", replaces_type="material", usage="删除账目 / 分区 / 分类"),

    Icon("check", "function", "full", [
        "M5.0 12.6l4.6 4.6L19.2 7.4",
    ], replaces="Icons.Filled.Check", replaces_type="material", usage="确认 / 保存 / 已核对"),

    Icon("edit", "function", "full", [
        "M4.9 19.1l.9-3.6L15.5 5.8a2.0 2.0 0 0 1 2.8 2.8L8.6 18.2l-3.7.9z",
        "M13.9 7.4l2.8 2.8",
    ], replaces="Icons.Filled.Edit", replaces_type="material", usage="编辑"),

    Icon("arrow-left", "function", "full", [
        "M14.6 5.4L8.0 12.0l6.6 6.6",
    ], replaces="Icons.AutoMirrored.KeyboardArrowLeft", replaces_type="material", usage="返回 / 上一月"),

    Icon("arrow-right", "function", "full", [
        "M9.4 5.4L16.0 12.0l-6.6 6.6",
    ], replaces="Icons.AutoMirrored.KeyboardArrowRight", replaces_type="material", usage="进入 / 下一月"),

    Icon("arrow-up", "function", "full", [
        "M5.4 14.6L12.0 8.0l6.6 6.6",
    ], replaces="Icons.Filled.KeyboardArrowUp", replaces_type="material", usage="展开 / 上移排序"),

    Icon("arrow-down", "function", "full", [
        "M5.4 9.4l6.6 6.6 6.6-6.6",
    ], replaces="Icons.Filled.KeyboardArrowDown", replaces_type="material", usage="收起 / 下移排序"),
]

# ---------------------------------------------------------------------
# D · 行内标记 4 枚（12–18dp：减笔画 + 描边 1.7 补偿）
# ---------------------------------------------------------------------
INLINE = [
    Icon("calendar-inline", "inline", "inline", [
        rp(4.6, 5.8, 14.8, 13.4, 1.5, 1.7, 1.6, 1.4),
        "M8.4 3.9v3.3M15.6 3.9v3.3",
    ], replaces="📅", replaces_type="emoji", usage="日期字段 label 前缀（~15dp）· 省去日期点阵"),

    Icon("clock-inline", "inline", "inline", [
        ci(12, 12, 7.6),
        "M12 7.7V12l2.9 1.8",
    ], replaces="🕐", replaces_type="emoji", usage="时间字段 label 前缀（~15dp）"),

    Icon("note-inline", "inline", "inline", [
        rp(4.4, 5.3, 15.2, 11.0, 2.5, 2.7, 2.3, 2.1),
        "M8.9 16.2L7.5 19.3l3.2-1.7",
    ], replaces="💬", replaces_type="emoji", usage="账目行「有备注」标记（~12dp）· 气泡内不画线"),

    Icon("camera-inline", "inline", "inline", [
        rp(4.2, 7.5, 15.6, 10.9, 1.6, 1.8, 1.6, 1.4),
        "M9.1 7.4l1.0-2.1h3.8l1.0 2.1",
        ci(12, 12.9, 2.8),
    ], replaces="📷", replaces_type="emoji", usage="账目行「有图 N 张」标记（~12dp）"),
]

# ---------------------------------------------------------------------
# E · 极小对勾（11dp、纯白、18dp 方块内；描边 2.0 单笔画）
# ---------------------------------------------------------------------
XS = [
    Icon("check-xs", "xs", "xs", [
        "M5.8 12.9l4.1 4.1L18.4 7.4",
    ], replaces="✓", replaces_type="emoji", usage="选项已生效标记（11dp，白色）"),
]

# ---------------------------------------------------------------------
# F · 空态插图 2 枚（32 网格 / 描边 1.6 / 细节可画足）
# ---------------------------------------------------------------------
LG = [
    Icon("empty-lg", "illustration", "lg", [
        "M16 10.3c-2.4-2.0-5.9-2.6-9.9-2.2v14.6c4.0-.4 7.5.2 9.9 2.2",
        "M16 10.3c2.4-2.0 5.9-2.6 9.9-2.2v14.6c-4.0-.4-7.5.2-9.9 2.2",
        "M16 10.3v14.6",
        "M20.0 8.7v5.0l1.9-1.5 1.9 1.5V9.3",
    ], replaces="🗒️", replaces_type="emoji", usage="空态插图（32dp）· 一本翻开的空白手账 + 书签带"),

    Icon("hint-lg", "illustration", "lg", [
        "M4.2 13.4h8.0",
        "M12.2 13.4v-1.8c0-1.0.8-1.8 1.8-1.8h5.4a2.2 2.2 0 0 1 2.2 2.2v2.6a5.6 5.6 0 0 1-5.6 5.6h-.6"
        "a5.6 5.6 0 0 1-4.8-2.7l-1.4-2.3",
        "M14.7 10.0v1.7M17.3 10.0v1.7",
    ], replaces="👈", replaces_type="emoji", usage="空态提示插图（32dp）· 指向左侧的手"),
]

# ---------------------------------------------------------------------
# G · 状态符号 2 枚（14dp 符号槽；报销维度 ○ / ●）
# ---------------------------------------------------------------------
STATUS = [
    Icon("status-pending", "status", "status", [
        ci(12, 12, 6.3),
    ], replaces="待报销", replaces_type="emoji", usage="空心圆 · 14dp 符号槽，朱砂色"),

    Icon("status-cleared", "status", "status", [
        fi(12, 12, 6.3),
    ], replaces="已报销", replaces_type="emoji", usage="实心圆 · 14dp 符号槽，松烟墨绿色"),
]


def load_system():
    return NAV + FUNCTION + INLINE + XS + LG + STATUS


# ---------------------------------------------------------------------
# App 图标（adaptive：background 纸色 / foreground 手绘 / monochrome 单色）
# 108 视口，安全区 = 圆 r33 @(54,54)。描边 4.5 ≈ 48dp 下 2dp，与 1.5/24 同节奏。
# ---------------------------------------------------------------------

def appicon_layers(concept: str):
    """返回 (bg_color, [元素/路径], mono_paths)：concept ∈ {a, b, c}"""
    ink = "#17403A"
    teal = TAPE[0][1]
    if concept == "a":
        fg = [
            rp(29.5, 25.5, 49, 57, 3.0, 3.6, 3.2, 2.8),          # 本子外廓
            "M40.5 25.7v56.2",                                     # 书脊
            "M55.5 44.5l5.8 8.2M67.1 44.5l-5.8 8.2M61.3 52.7v13.5M56.2 58.0h10.2",  # ¥
        ]
        tape = "M25.0 32.5l29.5-9.4 2.7 8.3-29.5 9.4z"             # 胶带（青绿，斜贴）
        mono = [rp(29.5, 25.5, 49, 57, 3.0, 3.6, 3.2, 2.8),
                "M40.5 25.7v56.2",
                "M55.5 44.5l5.8 8.2M67.1 44.5l-5.8 8.2M61.3 52.7v13.5M56.2 58.0h10.2"]
        return PAPER, fg, tape, mono
    if concept == "b":
        fg = [
            "M45.5 32.5L54.0 44.5M62.5 32.5L54.0 44.5M54.0 44.5v25.5",
            "M46.0 51.0h16.0",
            "M42.0 76.5h24.0",
            "M48.0 82.5h12.0",
        ]
        tape = None   # B 方案无胶带 —— 胶带会让 ¥ 读成「发芽」
        mono = [p for p in fg]
        return PAPER, fg, tape, mono
    if concept == "c":
        fg = [
            rp(28.5, 26.5, 44, 16, 2.0, 2.4, 2.2, 1.8),
            rp(33.0, 45.0, 44, 16, 2.0, 2.4, 2.2, 1.8),
            rp(26.0, 63.5, 48, 17, 2.0, 2.4, 2.2, 1.8),
            "M46.5 65.5l3.5 5.0M53.5 65.5l-3.5 5.0M50.0 70.5v8.0M46.8 72.7h6.4",
        ]
        fills = [TAPE[2][1], TAPE[1][1], TAPE[0][1]]  # 赭黄 / 灰蓝 / 青绿
        mono = [rp(28.5, 26.5, 44, 16, 2.0, 2.4, 2.2, 1.8),
                rp(33.0, 45.0, 44, 16, 2.0, 2.4, 2.2, 1.8),
                rp(26.0, 63.5, 48, 17, 2.0, 2.4, 2.2, 1.8),
                "M46.5 65.5l3.5 5.0M53.5 65.5l-3.5 5.0M50.0 70.5v8.0M46.8 72.7h6.4"]
        return PAPER, fg, fills, mono
    raise ValueError(concept)


def appicon_svg(concept: str, px=None, mask=None, dark=False):
    """概念稿 SVG。mask: None(方形) / 'circle' / 'squircle'"""
    bg, fg, extra, _ = appicon_layers(concept)
    if dark:
        bg = "#1C1A16"
    g = 108
    size = f' width="{px}" height="{px}"' if px else ""
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {g} {g}" fill="none"{size}>']
    if mask == "circle":
        out.append(f'<defs><clipPath id="m"><circle cx="54" cy="54" r="54"/></clipPath></defs>')
        out.append('<g clip-path="url(#m)">')
    elif mask == "squircle":
        out.append('<defs><clipPath id="m"><rect x="0" y="0" width="108" height="108" rx="30"/></clipPath></defs>')
        out.append('<g clip-path="url(#m)">')
    else:
        out.append("<g>")
    out.append(f'  <rect width="108" height="108" fill="{bg}"/>')
    if dark:
        out.append('  <rect width="108" height="108" fill="#F5F0E4" opacity="0.03"/>')
    ink = "#F5F0E4" if dark else "#17403A"
    weak = "#C0B8A6" if dark else "#55736B"
    st = f'stroke="{ink}" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round" fill="none"'
    if concept == "a":
        out.append(f'  <g {st}>')
        for d in fg:
            out.append(f'    <path d="{d}"/>')
        out.append("  </g>")
        out.append(f'  <path d="{extra}" fill="{TAPE[0][1]}" stroke="none" opacity="0.92"/>')
        # 胶带要压在本子上 → 顺序：先画 fg 再画胶带已满足；但弱线在胶带外，无碰撞
    elif concept == "b":
        out.append(f'  <g {st}>')
        for i, d in enumerate(fg):
            c = weak if i >= 2 else ink
            out.append(f'    <path d="{d}" stroke="{c}"/>')
        out.append("  </g>")
    elif concept == "c":
        for i, d in enumerate(fg[:3]):
            out.append(f'  <path d="{d}" fill="{extra[i]}" stroke="#17403A" stroke-width="4" '
                       f'stroke-linejoin="round" opacity="0.95"/>')
        out.append(f'  <g stroke="{PAPER}" stroke-width="3.6" stroke-linecap="round" '
                   f'stroke-linejoin="round" fill="none">')
        out.append(f'    <path d="{fg[3]}"/>')
        out.append("  </g>")
    out.append("</g></svg>")
    return "\n".join(out)
