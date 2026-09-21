#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ops_gen.py —— 产出 Ardot 画布 batch_edit 操作文件（ops/*.txt）

批次模型（绑定只在同一 batch_edit 调用内有效，跨批次用真实节点 ID）：
  ops_shells.txt   —— 首批：板面 + 8 个 section 外壳 + 关键子容器 → 返回全部 ID
  ops_s0_title / s0_swatches / s0_tape … —— 内容批次，首 op 自建子树根
占位符：{{BOARD}} {{S0}} {{S1}} {{S1_ROW}} {{S2}} … {{S7}} —— 粘贴时替换为真实 ID。
每个文件 ≤25 ops。
"""

from __future__ import annotations

import json
import pathlib
import re

import spec
from spec import (INK, INK2, INK3, INK_DARK, PAPER, SLIP, SUNKEN, SLIP_UNDER,
                  RULE, RULE_STRONG, EXPENSE, INCOME, TAPE)
import icons_category
import icons_system

OUT = pathlib.Path(__file__).resolve().parent / "ops"
OUT.mkdir(exist_ok=True, parents=True)

HAN = {"family": "Source Han Sans CN", "style": "Regular"}
HAN_M = {"family": "Source Han Sans CN", "style": "Medium"}
SERIF = {"family": "Noto Serif SC", "style": "SemiBold"}
SERIF_R = {"family": "Noto Serif SC", "style": "Regular"}

_ops = []
_file = ["ops_shells.txt"]


def _setfile(name):
    global _ops, _file
    _emit()
    _file = [name]


def _emit():
    if _ops:
        (OUT / _file[0]).write_text("\n".join(_ops) + "\n", encoding="utf-8")
    _ops.clear()


def _P(parent):
    p = str(parent)
    if re.fullmatch(r"n\d+", p):
        return p
    return '"' + p + '"'


def fr(parent, name, **props):
    b = f"n{len(_ops)}"
    node = {"type": "frame", "name": name}
    node.update(props)
    _ops.append(f'{b}=I({_P(parent)}, {json.dumps(node, ensure_ascii=False)})')
    return b


def tx(parent, name, content, size, color=INK, font=HAN, **props):
    b = f"n{len(_ops)}"
    node = {"type": "text", "name": name, "content": content, "fontSize": size,
            "fontName": font, "fill": color}
    node.update(props)
    _ops.append(f'{b}=I({_P(parent)}, {json.dumps(node, ensure_ascii=False)})')
    return b


def sv(parent, name, svg_str, px, **props):
    b = f"n{len(_ops)}"
    node = {"type": "frame", "name": name, "layout": "none", "width": px, "height": px, "svg": svg_str}
    node.update(props)
    _ops.append(f'{b}=I({_P(parent)}, {json.dumps(node, ensure_ascii=False)})')
    return b


def icon(parent, ic, px, name=None, color=INK):
    return sv(parent, name or f"icon-{ic.name}", ic.svg(color), px)


def sec(parent, name):
    return fr(parent, name, layout="vertical", gap=28, width="fill_container",
              height="hug_contents", fills=[])


# =====================================================================
# shells：板面 + 8 section + 关键子容器
# =====================================================================

def gen_shells():
    board = fr("4:1", "简账图标系统 v3 · 纸墨手绘", width=1600, height="hug_contents",
               layout="vertical", padding=64, gap=64, fill=PAPER)
    s0 = sec(board, "S0 页眉与色板")
    s1 = sec(board, "S1 App 图标三方案")
    r1 = fr(s1, "S1 方案卡横排", layout="horizontal", gap=24, width="fill_container",
            height="hug_contents", fills=[])
    s2 = sec(board, "S2 选定方案拆解")
    s3 = sec(board, "S3 绘图规范")
    s4 = sec(board, "S4 A组图标墙")
    g4 = fr(s4, "S4 图标网格", layout="wrap", width="fill_container", height="hug_contents",
            gap=14, counterAxisSpacing=14)
    s5 = sec(board, "S5 B–G组")
    s6 = sec(board, "S6 场景验证")
    b6 = fr(s6, "S6 底部横排", layout="horizontal", gap=40, width="fill_container",
            height="hug_contents", fills=[])
    s7 = sec(board, "S7 交付清单")
    _emit()


# =====================================================================
# S0
# =====================================================================

def gen_s0():
    _setfile("ops_s0_title.txt")
    tx("{{S0}}", "kicker", "SimpleLedger · ICON SYSTEM V3 · 2026-09-22", 28, color=INK2)
    tx("{{S0}}", "title", "简账 · 图标系统 v3「纸墨手绘」", 64, font=SERIF)
    tx("{{S0}}", "sub", "72 枚全量重绘 + App 图标三案定稿 · 单色墨青 · 圆头手绘 · 光学居中内建 · 与手账规范 v2.1 同源",
       30, color=INK2, width="fill_container")

    _setfile("ops_s0_swatches.txt")
    row = fr("{{S0}}", "色板 · 纸墨语义", layout="horizontal", gap=16, height="hug_contents", fills=[])
    sw = [("纸 PAPER", PAPER), ("纸片 SLIP", SLIP), ("凹面 SUNKEN", SUNKEN), ("线 RULE", RULE),
          ("主墨 INK", INK), ("次墨 INK2", INK2), ("朱砂 支出", EXPENSE), ("松烟 收入", INCOME)]
    for nm, c in sw:
        s = fr(row, f"swatch-{nm}", layout="vertical", gap=8, height="hug_contents", fills=[],
               counterAxisAlignItems="CENTER")
        fr(s, f"chip-{nm}", width=104, height=64, cornerRadius=3,
           fill=c, stroke=RULE_STRONG, strokeWeight=1)
        tx(s, f"lbl-{nm}", nm, 28, color=INK2)

    _setfile("ops_s0_tape.txt")
    row2 = fr("{{S0}}", "色板 · 胶带", layout="horizontal", gap=16, height="hug_contents", fills=[],
              counterAxisAlignItems="CENTER")
    for nm, c in TAPE:
        fr(row2, f"tape-{nm}", width=56, height=40, cornerRadius=3, fill=c)
    tx(row2, "tape-lbl", "和纸胶带色板 8 色 —— 分区身份专用，图标一律不沾（分区卡上除外）", 28, color=INK2)
    _emit()


# =====================================================================
# S1 · 三方案卡（每卡一棵自包含子树）
# =====================================================================

CONCEPTS = [
    ("a", "方案 A「纸墨账本」 · 推荐 · 定稿",
     "手绘本子 + ¥ + 青绿胶带压角。账本剪影在 48dp 仍一眼可辨；胶带是全图唯一色强调，与手账语言同构。"),
    ("b", "方案 B「双线墨签」 · 备选",
     "大 ¥ + 页眉双线押脚，墨味最足。但 ¥ 是记账品类通用符号，识别度让给了语义；胶带会让它读成「发芽」。"),
    ("c", "方案 C「索引贴叠」 · 备选",
     "三张索引贴错位叠放，导航隐喻直给；三条色带在 40px 以下拥挤，¥ 也小。适合做备胎或启动图元素。"),
]


def gen_s1():
    for i, (c, nm, desc) in enumerate(CONCEPTS):
        _setfile(f"ops_s1_card_{c}.txt")
        card = fr("{{S1_ROW}}", f"card-{c}", layout="vertical", gap=16, width=458, height="hug_contents",
                  fill=SLIP, cornerRadius=3, stroke=RULE_STRONG, strokeWeight=1, padding=24)
        tx(card, f"nm-{c}", nm, 32, font=SERIF)
        rrow = fr(card, f"masks-{c}", layout="horizontal", gap=16, height="hug_contents", fills=[])
        for mk, r in (("square", 10), ("circle", 999), ("squircle", 34)):
            m = fr(rrow, f"mask-{c}-{mk}", width=116, height=116, cornerRadius=r,
                   clipsContent=True, fill=PAPER)
            sv(m, f"svg-{c}-{mk}", icons_system.appicon_svg(c), 116)
        srow = fr(card, f"sizes-{c}", layout="horizontal", gap=14, height="hug_contents", fills=[],
                  counterAxisAlignItems="MAX")
        for px in (72, 56, 40):
            sv(srow, f"sz-{c}-{px}", icons_system.appicon_svg(c), px)
        tx(card, f"desc-{c}", desc, 28, color=INK2, width="fill_container")
        _emit()


# =====================================================================
# S2 · 定稿拆解
# =====================================================================

def gen_s2():
    _setfile("ops_s2_layers.txt")
    tx("{{S2}}", "t", "② 方案 A 定稿 · adaptive 三层拆解", 44, font=SERIF)
    row = fr("{{S2}}", "layers", layout="horizontal", gap=32, height="hug_contents", fills=[],
             counterAxisAlignItems="MIN")
    lay = [("背景层 background", "bg", "纸色 #F7F3E9 —— 启动屏 windowBackground 同源，冷启动无跳色"),
           ("前景层 foreground", "fg", "墨青 #17403A 手绘本子 + ¥；青绿胶带压左上角，全图唯一色强调"),
           ("单色层 monochrome", "mono", "仅墨线轮廓（本子 + 书脊 + ¥），系统重着色，主题图标 / 抽屉用")]
    for nm, kind, desc in lay:
        col = fr(row, f"layer-{kind}", layout="vertical", gap=12, width=280, height="hug_contents", fills=[])
        m = fr(col, f"mask-{kind}", width=220, height=220, cornerRadius=36, clipsContent=True,
               fill=PAPER, stroke=RULE_STRONG, strokeWeight=1)
        if kind == "mono":
            _, fg, _tape, mono = icons_system.appicon_layers("a")
            paths = "".join(f'<path d="{d}"/>' for d in mono)
            svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" fill="none" width="220" height="220">'
                   f'<g stroke="#17403A" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round" '
                   f'fill="none">{paths}</g></svg>')
        else:
            svg = icons_system.appicon_svg("a")
        sv(m, f"svg-{kind}", svg, 220)
        tx(col, f"nm-{kind}", nm, 30, font=HAN_M)
        tx(col, f"desc-{kind}", desc, 28, color=INK2, width="fill_container")
    _emit()

    _setfile("ops_s2_extras.txt")
    row2 = fr("{{S2}}", "extras", layout="horizontal", gap=32, height="hug_contents", fills=[],
              counterAxisAlignItems="MIN")
    _, fg, _tape, _mono = icons_system.appicon_layers("a")
    kz = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="220" height="220" fill="none">'
          f'<circle cx="54" cy="54" r="33" stroke="{EXPENSE}" stroke-width="1.6" stroke-dasharray="4 3"/>'
          f'<circle cx="54" cy="54" r="54" stroke="{RULE_STRONG}" stroke-width="1.2" stroke-dasharray="4 3"/>'
          f'<rect x="21" y="21" width="66" height="66" stroke="{RULE_STRONG}" stroke-width="1.2"/>'
          f'<g stroke="{INK2}" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" fill="none" opacity="0.9">'
          + "".join(f'<path d="{d}"/>' for d in fg) +
          f'<path d="{_tape}" fill="{TAPE[0][1]}" stroke="none" opacity="0.5"/></g></svg>')
    col = fr(row2, "safezone", layout="vertical", gap=12, width=280, height="hug_contents", fills=[])
    m = fr(col, "kz-mask", width=220, height=220, cornerRadius=10, clipsContent=True, fill=SLIP,
           stroke=RULE_STRONG, strokeWeight=1)
    sv(m, "kz-svg", kz, 220)
    tx(col, "kz-nm", "安全区", 30, font=HAN_M)
    tx(col, "kz-desc", "虚线 = 圆形可见区 r33 与方形 66 盒；¥ 与胶带全部落在圆内，圆形蒙版不裁切", 28,
       color=INK2, width="fill_container")
    dk = fr(row2, "dark", layout="vertical", gap=12, width=280, height="hug_contents", fills=[])
    m = fr(dk, "dark-mask", width=220, height=220, cornerRadius=36, clipsContent=True, fill="#1C1A16")
    sv(m, "dark-svg", icons_system.appicon_svg("a", dark=True), 220)
    tx(dk, "dark-nm", "深色壁纸语境", 30, font=HAN_M)
    tx(dk, "dark-desc", "纸底不换向（App 图标无深色变体）：暖纸在深炭壁纸上自带「贴纸感」，墨青轮廓仍 10.35:1", 28,
       color=INK2, width="fill_container")
    hm = fr(row2, "home", layout="vertical", gap=12, width=320, height="hug_contents", fills=[])
    strip = fr(hm, "home-strip", layout="horizontal", gap=20, height="hug_contents", fills=[],
               counterAxisAlignItems="MAX", padding=4)
    for px in (104, 88, 72, 56):
        sv(strip, f"home-{px}", icons_system.appicon_svg("a"), px)
    tx(hm, "home-nm", "主屏尺寸梯度 104 / 88 / 72 / 56px", 30, font=HAN_M)
    tx(hm, "home-desc", "最小 56px 下书脊与 ¥ 仍可辨；胶带色块承担第一眼识别", 28, color=INK2,
       width="fill_container")
    _emit()


# =====================================================================
# S3 · 绘图规范
# =====================================================================

def gen_s3(icons):
    by = {i.name: i for i in icons}
    _setfile("ops_s3_title.txt")
    tx("{{S3}}", "t", "③ 绘图规范 · 网格 / 描边档位 / 手绘几何", 44, font=SERIF)
    _emit()

    _setfile("ops_s3_row1.txt")
    row = fr("{{S3}}", "row1", layout="horizontal", gap=32, height="hug_contents", fills=[],
             counterAxisAlignItems="MIN")
    grid_svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="240" height="240" fill="none">'
        f'<rect x="0" y="0" width="24" height="24" fill="{SLIP}"/>')
    for k in range(0, 25, 2):
        grid_svg += (f'<path d="M{k} 0V24" stroke="{RULE}" stroke-width="0.15"/>'
                     f'<path d="M0 {k}H24" stroke="{RULE}" stroke-width="0.15"/>')
    grid_svg += (f'<rect x="2" y="2" width="20" height="20" stroke="{RULE_STRONG}" stroke-width="0.25" stroke-dasharray="1 0.8"/>'
                 f'<circle cx="12" cy="12" r="7.5" stroke="{INK}" stroke-width="1.5"/>'
                 f'<circle cx="12" cy="12" r="2.6" stroke="{INK}" stroke-width="1.5"/>'
                 f'<path d="M12 2v2M12 20v2M2 12h2M20 12h2" stroke="{EXPENSE}" stroke-width="0.5"/>'
                 f'</svg>')
    col = fr(row, "grid-col", layout="vertical", gap=12, height="hug_contents", fills=[])
    m = fr(col, "grid-mask", width=240, height=240, cornerRadius=3, clipsContent=True, fill=SLIP,
           stroke=RULE_STRONG, strokeWeight=1)
    sv(m, "grid-svg", grid_svg, 240)
    tx(col, "grid-nm", "24 网格 · 安全边 2 · 视觉重心居中", 30, font=HAN_M)
    txt = fr(row, "rules", layout="vertical", gap=14, width="fill_container", height="hug_contents", fills=[])
    for line in ("画布 full/inline/xs/status = 24，lg = 32；四周留 2 安全边",
                 "描边五档：full 1.5 · inline 1.7 · xs 2.0 · status 1.8 · lg 1.6",
                 "端点一律 round cap / round join，禁方头与尖角",
                 "除语义实心点外不填充；实心点必须显式 fill，不许靠描边糊",
                 "颜色：单色 currentColor —— 颜色语义已被分区身份独占（R3 / F2）",
                 "手工感 = 路径几何的不对称（不等角 rp 四角），运行时绝不抖动（R4）"):
        tx(txt, f"rule-{line[:6]}", "· " + line, 28, color=INK, width="fill_container")
    _emit()

    _setfile("ops_s3_ladder.txt")
    tx("{{S3}}", "ladder-t", "描边档位 · 按真实使用尺寸渲染（不是缩略图）", 32, font=SERIF_R, color=INK)
    lad = fr("{{S3}}", "ladder", layout="horizontal", gap=20, height="hug_contents", fills=[],
             counterAxisAlignItems="MAX")
    ladder_spec = [("nav-ledger", 48, INK, "nav 24dp · 1.5", SLIP),
                   ("calendar-inline", 30, INK, "inline 15dp · 1.7", SLIP),
                   ("status-pending", 28, EXPENSE, "status 14dp · 1.8", SLIP),
                   ("check-xs", 22, "#FFFFFF", "xs 11dp · 2.0 白", INK),
                   ("empty-lg", 64, INK, "lg 32dp · 1.6", SLIP)]
    for nm, px, c, lbl, bgc in ladder_spec:
        cell = fr(lad, f"lad-{nm}", layout="vertical", gap=8, height="hug_contents", fills=[],
                  counterAxisAlignItems="CENTER")
        box = fr(cell, f"ladbox-{nm}", width=px + 24, height=px + 24, cornerRadius=3, fill=bgc,
                 stroke=RULE_STRONG if bgc == SLIP else INK, strokeWeight=1,
                 layout="horizontal", primaryAxisAlignItems="CENTER", counterAxisAlignItems="CENTER")
        icon(box, by[nm], px, color=c)
        tx(cell, f"ladlbl-{nm}", lbl, 28, color=INK2)
    _emit()

    _setfile("ops_s3_row3.txt")
    row3 = fr("{{S3}}", "row3", layout="horizontal", gap=32, height="hug_contents", fills=[],
              counterAxisAlignItems="MIN")
    zoom = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="4 14 10 8" width="240" height="192" fill="none">'
            f'<path d="{spec.rp(6, 16, 12, 4, 2.0, 1.4, 2.2, 1.6)}" stroke="{INK}" stroke-width="1.5" '
            f'stroke-linecap="round" stroke-linejoin="round"/></svg>')
    rpz = fr(row3, "rp-zoom", layout="vertical", gap=12, height="hug_contents", fills=[])
    m = fr(rpz, "rp-mask", width=240, height=192, cornerRadius=3, clipsContent=True, fill=SLIP,
           stroke=RULE_STRONG, strokeWeight=1)
    sv(m, "rp-svg", zoom, 240)
    tx(rpz, "rp-nm", "rp() 不等角圆角 · 4× 放大", 30, font=HAN_M)
    tx(rpz, "rp-desc", "四角半径 2.0 / 1.4 / 2.2 / 1.6 ——「手绘但确定」的签名笔触，纸片类图形一律走它", 28,
       color=INK2, width=280)
    dots = fr(row3, "dots", layout="vertical", gap=12, height="hug_contents", fills=[])
    tx(dots, "dots-nm", "语义实心点白名单（9 枚）", 30, font=HAN_M)
    drow = fr(dots, "dots-row", layout="horizontal", gap=14, height="hug_contents", fills=[])
    for nm in ("status-cleared", "gamepad", "pet", "teddy", "grad-cap", "haircut", "banknote", "baby", "phone"):
        icon(drow, by[nm], 40)
    tx(dots, "dots-desc", "眼睛 / 按键 / 钱点 / 流苏点用显式填充；其余 63 枚 0 填充。生成器自检白名单，多一个即报错", 28,
       color=INK2, width=420)
    _emit()


# =====================================================================
# S4 · 图标墙（每批 8 枚 = 24 ops）
# =====================================================================

def gen_s4(cats):
    for k in range(0, len(cats), 8):
        chunk = cats[k:k + 8]
        _setfile(f"ops_s4_wall_{k // 8 + 1}.txt")
        for ic in chunk:
            tile = fr("{{S4_GRID}}", f"tile-{ic.icon_id:02d}-{ic.name}", width=178, height=124,
                      layout="vertical", gap=6, padding=8, primaryAxisAlignItems="CENTER",
                      counterAxisAlignItems="CENTER",
                      fill=SLIP, cornerRadius=3, stroke=RULE_STRONG, strokeWeight=1)
            icon(tile, ic, 56)
            lbl = ic.usage.split(" /")[0].split("（")[0]
            tx(tile, f"lbl-{ic.icon_id:02d}", f"{ic.icon_id:02d} · {lbl}", 26,
               color=INK2, textAlignHorizontal="CENTER", width="fill_container")
        _emit()


# =====================================================================
# S5 · B–G 组
# =====================================================================

def gen_s5(icons):
    by = {i.name: i for i in icons}
    _setfile("ops_s5_title.txt")
    tx("{{S5}}", "t", "⑤ B–G 组 · 导航 / 功能 / 行内 / 极小 / 插图 / 状态", 44, font=SERIF)
    _emit()

    _setfile("ops_s5_nav.txt")
    tx("{{S5}}", "nav-t", "B · 底部导航 ×「索引贴」栏（2× 渲染；选中签凸出 24 = 12dp×2）", 32, font=SERIF_R, color=INK)
    bar = fr("{{S5}}", "navbar", layout="vertical", width="fill_container", height="hug_contents", fills=[])
    fr(bar, "divider", width="fill_container", height=2, fill=RULE_STRONG)
    tabs = fr(bar, "tabs", layout="horizontal", gap=6, width="fill_container", height="hug_contents",
              fill=PAPER, padding=32, counterAxisAlignItems="MAX")
    nav_data = [("nav-section", "分区", True), ("nav-ledger", "明细", False),
                ("nav-stats", "统计", False), ("nav-mine", "我的", False)]
    for nm, lbl, sel in nav_data:
        tab = fr(tabs, f"tab-{nm}", width=290, height=108 if sel else 84,
                 layout="horizontal", gap=12, cornerRadius=7,
                 fill=SUNKEN if sel else SLIP, stroke=RULE_STRONG if not sel else None,
                 strokeWeight=1, primaryAxisAlignItems="CENTER", counterAxisAlignItems="CENTER")
        icon(tab, by[nm], 44 if sel else 40, color=INK if sel else INK2)
        tx(tab, f"tablbl-{nm}", lbl, 30, font=HAN_M if sel else HAN, color=INK if sel else INK2)
    tx("{{S5}}", "nav-note", "选中 = 凹面底 + 主墨 + 半粗字重 + 凸出；未选中 = 纸片底 + 次墨。选中态五个信号全部来自「纸与字」",
       28, color=INK2)
    _emit()

    _setfile("ops_s5_fn_a.txt")
    tx("{{S5}}", "fn-t", "C · 功能图标 9 枚（代码里 24 处 Material 默认图标由它们接班）", 32, font=SERIF_R, color=INK)
    fn = fr("{{S5}}", "fn-grid", layout="wrap", width="fill_container", height="hug_contents",
            gap=12, counterAxisSpacing=12)
    for ic in [i for i in icons if i.group == "function"][:5]:
        tile = fr(fn, f"fn-{ic.name}", width=170, height=112, layout="vertical", gap=6, padding=8,
                  primaryAxisAlignItems="CENTER", counterAxisAlignItems="CENTER",
                  fill=SLIP, cornerRadius=3, stroke=RULE_STRONG, strokeWeight=1)
        icon(tile, ic, 44)
        tx(tile, f"fnlbl-{ic.name}", ic.name, 26, color=INK2, textAlignHorizontal="CENTER", width="fill_container")
    _emit()

    _setfile("ops_s5_fn_b.txt")
    for ic in [i for i in icons if i.group == "function"][5:]:
        tile = fr("{{S5_FN_GRID}}", f"fn-{ic.name}", width=170, height=112, layout="vertical", gap=6, padding=8,
                  primaryAxisAlignItems="CENTER", counterAxisAlignItems="CENTER",
                  fill=SLIP, cornerRadius=3, stroke=RULE_STRONG, strokeWeight=1)
        icon(tile, ic, 44)
        tx(tile, f"fnlbl-{ic.name}", ic.name, 26, color=INK2, textAlignHorizontal="CENTER", width="fill_container")
    _emit()

    _setfile("ops_s5_de.txt")
    tx("{{S5}}", "de-t", "D 行内（真实 15dp）· E 极小（11dp 白）· G 状态（14dp 槽）", 32, font=SERIF_R, color=INK)
    derow = fr("{{S5}}", "de-row", layout="horizontal", gap=24, height="hug_contents", fills=[],
               counterAxisAlignItems="MAX")
    for nm in ("calendar-inline", "clock-inline", "note-inline", "camera-inline"):
        cell = fr(derow, f"de-{nm}", layout="vertical", gap=8, height="hug_contents", fills=[],
                  counterAxisAlignItems="CENTER")
        icon(cell, by[nm], 30)
        tx(cell, f"de-lbl-{nm}", nm.replace("-inline", ""), 28, color=INK2)
    xcell = fr(derow, "de-check-xs", layout="vertical", gap=8, height="hug_contents", fills=[],
               counterAxisAlignItems="CENTER")
    chip = fr(xcell, "xs-chip", width=44, height=44, cornerRadius=3, fill=INK,
              layout="horizontal", primaryAxisAlignItems="CENTER", counterAxisAlignItems="CENTER")
    icon(chip, by["check-xs"], 22, color="#FFFFFF")
    tx(xcell, "xs-lbl", "check-xs 11dp", 28, color=INK2)
    for nm, c in (("status-pending", EXPENSE), ("status-cleared", INCOME)):
        cell = fr(derow, f"st-{nm}", layout="vertical", gap=8, height="hug_contents", fills=[],
                  counterAxisAlignItems="CENTER")
        icon(cell, by[nm], 28, color=c)
        tx(cell, f"st-lbl-{nm}", nm.replace("status-", ""), 28, color=INK2)
    _emit()

    _setfile("ops_s5_lg.txt")
    tx("{{S5}}", "lg-t", "F · 空态插图（32 网格，装 68dp 凹面方块）", 32, font=SERIF_R, color=INK)
    lgrow = fr("{{S5}}", "lg-row", layout="horizontal", gap=24, height="hug_contents", fills=[],
               counterAxisAlignItems="MIN")
    for nm, cap in (("empty-lg", "empty-lg · 通用空态"), ("hint-lg", "hint-lg · 双栏引导")):
        cell = fr(lgrow, f"lg-{nm}", layout="vertical", gap=10, height="hug_contents", fills=[],
                  counterAxisAlignItems="CENTER")
        tile = fr(cell, f"lg-tile-{nm}", width=136, height=136, cornerRadius=6, fill=SUNKEN,
                  layout="horizontal", primaryAxisAlignItems="CENTER", counterAxisAlignItems="CENTER")
        icon(tile, by[nm], 96)
        tx(cell, f"lg-lbl-{nm}", cap, 28, color=INK2)
    _emit()


# =====================================================================
# S6 · 场景验证
# =====================================================================

def gen_s6(icons):
    by = {i.name: i for i in icons}
    _setfile("ops_s6_title.txt")
    tx("{{S6}}", "t", "⑥ 场景验证 · 与现有组件同框（2× 渲染）", 44, font=SERIF)
    _emit()

    rows = [("装修·主材", "−3,200.00", EXPENSE, TAPE[0][1], ["status-pending", "check"], [], "待报销 · 已核对"),
            ("餐饮", "−127.50", EXPENSE, TAPE[5][1], ["check"], ["note-inline"], "已核对 · 有备注"),
            ("工资", "+12,000.00", INCOME, TAPE[1][1], [], ["note-inline", "camera-inline"], "收入 · 有图 2 张")]

    def row_ops(parent, k, cat, amt, amt_c, bar_c, cluster, marks, note):
        row = fr(parent, f"row-{k}", width=1120, height=124, layout="horizontal", fill=SLIP,
                 cornerRadius=3, stroke=RULE_STRONG, strokeWeight=1, counterAxisAlignItems="CENTER")
        fr(row, f"bar-{k}", width=6, height=124, fill=bar_c)
        cl = fr(row, f"cluster-{k}", layout="horizontal", gap=8, height="hug_contents", fills=[],
                counterAxisAlignItems="CENTER")
        for cnm in cluster:
            icon(cl, by[cnm], 28, color=EXPENSE if cnm == "status-pending" else INK)
        tx(row, f"cat-{k}", cat, 30, color=INK)
        mkrow = fr(row, f"marks-{k}", layout="horizontal", gap=6, height="hug_contents", fills=[],
                   counterAxisAlignItems="CENTER")
        for mnm in marks:
            icon(mkrow, by[mnm], 24, color=INK3)
        tx(mkrow, f"mnote-{k}", note, 26, color=INK3)
        fr(row, f"sp-{k}", width="fill_container", height=10, fills=[])
        tx(row, f"amt-{k}", amt, 30, color=amt_c, font=HAN_M)
        fr(row, f"pad-{k}", width=24, height=10, fills=[])

    _setfile("ops_s6_rows_a.txt")
    tx("{{S6}}", "row-t", "明细页账目行 —— 色条(分区身份) + 符号簇 + 行内标记 + 金额", 32, font=SERIF_R, color=INK)
    row_ops("{{S6}}", 0, *rows[0])
    row_ops("{{S6}}", 1, *rows[1])
    _emit()

    _setfile("ops_s6_rows_b.txt")
    row_ops("{{S6}}", 2, *rows[2])
    _emit()

    _setfile("ops_s6_form.txt")
    tx("{{S6}}", "form-t", "记账表单 —— 字段前缀行内图标", 32, font=SERIF_R, color=INK)
    frow = fr("{{S6}}", "form", layout="horizontal", gap=48, height="hug_contents", fills=[],
              counterAxisAlignItems="CENTER")
    for nm, lbl in (("calendar-inline", "日期"), ("clock-inline", "时间")):
        g = fr(frow, f"f-{nm}", layout="horizontal", gap=10, height="hug_contents", fills=[],
               counterAxisAlignItems="CENTER")
        icon(g, by[nm], 30)
        tx(g, f"f-lbl-{nm}", lbl, 30, color=INK2)
    _emit()

    _setfile("ops_s6_bottom_a.txt")
    tx("{{S6}}", "empty-t", "空态与分类选择网格（表单里的 50 格候选，节选）", 32, font=SERIF_R, color=INK)
    empty = fr("{{S6_BOT}}", "empty", layout="vertical", gap=12, width=360, height="hug_contents", fills=[],
               counterAxisAlignItems="CENTER")
    tile = fr(empty, "empty-tile", width=136, height=136, cornerRadius=6, fill=SUNKEN,
              layout="horizontal", primaryAxisAlignItems="CENTER", counterAxisAlignItems="CENTER")
    icon(tile, by["empty-lg"], 96)
    tx(empty, "empty-lbl", "今天还没有账目", 30, font=SERIF_R, color=INK2)
    _emit()

    _setfile("ops_s6_bottom_b.txt")
    grid = fr("{{S6_BOT}}", "catgrid", layout="wrap", width=760, height="hug_contents", gap=10,
              counterAxisSpacing=10, fill=SLIP, cornerRadius=3, stroke=RULE_STRONG, strokeWeight=1,
              padding=12)
    cat_sel = ("rice-bowl", "noodle-bowl", "coffee", "beer", "salad", "donut",
               "bus", "car", "taxi", "bicycle", "fuel", "plane",
               "bag", "house", "hammer", "brick", "sofa", "tv",
               "book", "grad-cap", "gamepad", "film", "ball", "gift")
    for nm in cat_sel[:12]:
        cell = fr(grid, f"cg-{nm}", width=88, height=88, cornerRadius=3, fill=SUNKEN,
                  layout="horizontal", primaryAxisAlignItems="CENTER", counterAxisAlignItems="CENTER")
        icon(cell, by[nm], 44)
    _emit()

    _setfile("ops_s6_bottom_c.txt")
    for nm in cat_sel[12:]:
        cell = fr("{{S6_CGRID}}", f"cg-{nm}", width=88, height=88, cornerRadius=3, fill=SUNKEN,
                  layout="horizontal", primaryAxisAlignItems="CENTER", counterAxisAlignItems="CENTER")
        icon(cell, by[nm], 44)
    _emit()


# =====================================================================
# S7 · 交付清单
# =====================================================================

def gen_s7():
    _setfile("ops_s7.txt")
    tx("{{S7}}", "t", "⑦ 交付清单 · 代码集成路径", 44, font=SERIF)
    lines = [
        "docs/design/icons-v3/icons/*.svg —— 72 枚矢量源（单色墨 / 光学居中已写入 transform）",
        "docs/design/icons-v3/manifest.json —— 机器可读契约：名称 / 分组 / 档位 / 替换来源 / iconId / 居中量",
        "docs/design/icons-v3/preview.html —— 自包含审查页：全量 + 尺寸梯 + 深色抽查 + App 图标三案",
        "docs/design/icons-v3/SlIconsV3.kt —— drop-in 候选：整文件替换 SlIcons.kt + SlCategoryIcons.kt（API 兼容）",
        "docs/design/icons-v3/android_res/ —— adaptive 前景 + 单色层（已集成进 app res）",
        "app/src/main/res/values/colors.xml —— ic_launcher_background 已改为纸色 #F7F3E9",
        "回滚基线：docs/design/icons（v2 全套）保留未动；v2→v3 仅为路径数据替换，iconId / API 零变化",
    ]
    for ln in lines:
        tx("{{S7}}", f"d-{ln[:8]}", "· " + ln, 28, color=INK, width="fill_container")
    _emit()


def main():
    icons = icons_category.load() + icons_system.load_system()
    cats = [i for i in icons if i.group == "category"]
    gen_shells(); _emit()
    gen_s0()
    gen_s1()
    gen_s2()
    gen_s3(icons)
    gen_s4(cats)
    gen_s5(icons)
    gen_s6(icons)
    gen_s7()
    total = 0
    for f in sorted(OUT.glob("*.txt")):
        cnt = len([l for l in f.read_text().splitlines() if l.strip()])
        total += cnt
        print(f"{f.name}: {cnt}")
    print(f"TOTAL {total} ops / {len(list(OUT.glob('*.txt')))} files")


if __name__ == "__main__":
    main()
