#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build.py —— 装配器：从单一几何源产出全部交付物
  icons/*.svg + manifest.json + preview.html + SlIconsV3.kt
  + appicon/*.svg + Android res XML + 自检 contact-sheet.svg
运行：python3 build.py
"""

from __future__ import annotations

import json
import pathlib
import re

import spec
from spec import (Icon, INK, INK_DARK, INK2, PAPER, SLIP, SUNKEN, RULE_STRONG,
                  EXPENSE, INCOME, TAPE, TIER)
import icons_category
import icons_system

OUT = pathlib.Path(__file__).resolve().parent
ICON_DIR = OUT / "icons"
APPICON_DIR = OUT / "appicon"
RES_DIR = OUT / "android_res"

FILL_EXCEPTIONS = {  # 语义实心点白名单（v3 修订，manifest.meta 同步声明）
    "status-cleared", "gamepad", "pet", "teddy", "grad-cap",
    "haircut", "banknote", "baby", "phone",
}


def circle_d(cx, cy, r):
    """圆 → path d（Compose addPathNodes 只吃 path data）"""
    return (f"M{cx - r:.2f} {cy:.2f}"
            f"a{r:.2f} {r:.2f} 0 1 0 {2 * r:.2f} 0"
            f"a{r:.2f} {r:.2f} 0 1 0 {-2 * r:.2f} 0")


def to_kotlin_paths(icon: Icon):
    out = []
    for el in icon.paths:
        if el.startswith("<circle"):
            m = re.search(r'cx="([-\d.]+)"', el); cx = float(m.group(1))
            m = re.search(r'cy="([-\d.]+)"', el); cy = float(m.group(1))
            m = re.search(r'r="([-\d.]+)"', el); r = float(m.group(1))
            d = circle_d(cx, cy, r)
            out.append("pf" if 'fill="1"' in el else "p")
            out[-1] = f"{out[-1]}(\"{d}\")"
        else:
            out.append(f'p("{el}")')
    return ", ".join(out)


def validate(icons: list[Icon]):
    errs, warns = [], []
    names = [i.name for i in icons]
    if len(names) != 72:
        errs.append(f"图标数 {len(names)} ≠ 72")
    if len(set(names)) != len(names):
        errs.append("存在重名图标")
    ids = [i.icon_id for i in icons if i.icon_id]
    if ids != list(range(1, 51)):
        errs.append("iconId 1–50 不连续")
    for ic in icons:
        if ic.tier not in TIER:
            errs.append(f"{ic.name}: 未知档位")
        filled = [e for e in ic.paths if e.startswith("<circle") and 'fill="1"' in e]
        if filled and ic.name not in FILL_EXCEPTIONS:
            errs.append(f"{ic.name}: 出现未申报的实心点")
        if not filled and ic.name in FILL_EXCEPTIONS and ic.group in ("category",):
            pass
        pts = spec.sample_d(" ".join(e for e in ic.paths if not e.startswith("<circle")))
        for el in ic.paths:
            if el.startswith("<circle"):
                pts += spec._elem_points(el)
        if pts:
            xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
            m = 0.9
            if min(xs) < m or min(ys) < m or max(xs) > ic.grid - m or max(ys) > ic.grid - m:
                warns.append(f"{ic.name}: 触碰安全边 bbox=({min(xs):.1f},{min(ys):.1f})-({max(xs):.1f},{max(ys):.1f})")
    return errs, warns


def write_svgs(icons):
    ICON_DIR.mkdir(exist_ok=True, parents=True)
    for ic in icons:
        (ICON_DIR / f"{ic.name}.svg").write_text(ic.svg(INK) + "\n", encoding="utf-8")


def write_manifest(icons):
    meta = {
        "name": "简账 SimpleLedger · 手账风格图标集 v3「纸墨手绘」",
        "version": "3.0.0",
        "updated": "2026-09-22",
        "supersedes": "docs/design/icons (v2.0.0, 72 枚) —— 保留作回滚基线",
        "grid": {k: v["grid"] for k, v in TIER.items()},
        "stroke": {k: v["stroke"] for k, v in TIER.items()},
        "caps": "round / round",
        "fill": "none；仅语义实心点显式填充（见 fillExceptions）",
        "fillExceptions": {k: sum(1 for e in i.paths if e.startswith('<circle') and 'fill="1"' in e)
                           # ⚠️ 必须 sorted：FILL_EXCEPTIONS 是 set，字符串 set 的迭代顺序
                           # 随进程哈希随机化 —— 不排序的话每次重跑 manifest.json 的
                           # fillExceptions 键序都会变，入库产物与生成链就永远对不上
                           # （实测：重跑一次 git status 就脏）。
                           for k in sorted(FILL_EXCEPTIONS) for i in icons if i.name == k},
        "color": "单色 currentColor —— 颜色语义已被「分区身份」独占（规范 R3/F2）",
        "handFeel": "路径刻意不对称（不等角半径 rp），手工感写在几何里，运行时绝不抖动",
        "centering": "光学居中内建：生成时采样包围盒，修正量见每枚 centering 字段（±2.5 封顶）",
        "appIcon": {
            "chosen": "concept-a「纸墨账本」",
            "adaptive": "background=@color/ic_launcher_background(#F7F3E9) / foreground=ic_launcher_foreground / monochrome=ic_launcher_monochrome",
            "safeZone": "108 视口，圆形可见区 r33 @(54,54)；描边 4.5 ≈ 48dp 下 2.0dp，与 1.5/24 同节奏",
        },
    }
    data = {"meta": meta, "icons": [i.manifest_entry() for i in icons]}
    (OUT / "manifest.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def write_kotlin(icons):
    cats = [i for i in icons if i.group == "category"]
    navs = [i for i in icons if i.group == "nav"]
    funcs = [i for i in icons if i.group == "function"]
    inls = [i for i in icons if i.group == "inline"]
    xss = [i for i in icons if i.group == "xs"]
    lgs = [i for i in icons if i.group == "illustration"]
    sts = [i for i in icons if i.group == "status"]

    def kname(ic: Icon) -> str:
        """Kotlin 属性名：nav/status 组挂在嵌套 object 下，剥掉组前缀（与 v2 API 兼容）"""
        if ic.group == "nav" and ic.name.startswith("nav-"):
            return ic.camel[len("Nav"):]
        if ic.group == "status" and ic.name.startswith("status-"):
            return ic.camel[len("Status"):]
        return ic.camel

    def val(ic, indent="        "):
        tx = f"{ic.dx}f" if ic.dx else "0f"
        ty = f"{ic.dy}f" if ic.dy else "0f"
        return (f"{indent}val {kname(ic)}: ImageVector by lazy {{\n"
                f"{indent}    buildIcon(\"{kname(ic)}\", {ic.grid}, {ic.stroke}f, {tx}, {ty},\n"
                f"{indent}        {to_kotlin_paths(ic)},\n{indent}    )\n{indent}}}\n")

    head = '''package com.simpleledger.app.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/*
 * 手绘图标集 v3「纸墨手绘」（由 docs/design/icons-v3/build.py 生成，请勿手改）
 * =====================================================================
 * 规范：24dp 画布（插图 32dp）· 圆头端点 · 只描边不填充（语义点除外）· 单色
 *      描边按光学尺寸分档 —— full 1.5 / inline 1.7 / xs 2.0 / lg 1.6 / status 1.8
 *      光学居中修正量由生成器采样包围盒算出，已写入 buildIcon 的平移参数。
 *
 * 本文件为 drop-in 候选：整文件替换 SlIcons.kt + SlCategoryIcons.kt（API 兼容）。
 */

/** 一条路径：[d] 为 SVG path data；[filled] 为 true 时填充而非描边（语义需要时使用） */
internal data class IconPath(val d: String, val filled: Boolean = false)

internal fun p(d: String) = IconPath(d)

internal fun pf(d: String) = IconPath(d, filled = true)

internal fun buildIcon(
    name: String,
    size: Int,
    strokeWidth: Float,
    translationX: Float = 0f,
    translationY: Float = 0f,
    vararg paths: IconPath,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = size.dp,
    defaultHeight = size.dp,
    viewportWidth = size.toFloat(),
    viewportHeight = size.toFloat(),
).apply {
    group(translationX = translationX, translationY = translationY) {
    paths.forEach { path ->
        if (path.filled) {
            addPath(pathData = addPathNodes(path.d), fill = SolidColor(Color.Black))
        } else {
            addPath(
                pathData = addPathNodes(path.d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
    }
}.build()

/**
 * A 组 · 分类图标（iconId 1–50，存库集合）
 */
'''

    body = "object SlCategoryIcons {\n"
    for ic in cats:
        body += f"    /** iconId {ic.icon_id} · {ic.usage}（替换 {ic.replaces}） */\n"
        body += val(ic, indent="    ")
    body += "\n    /** 全部 50 枚（iconId 升序），供图标选择网格枚举 */\n"
    body += "    val allIcons: List<Pair<Int, ImageVector>> = listOf(\n"
    body += "".join(f"        {ic.icon_id} to {kname(ic)},\n" for ic in cats)
    body += "    )\n\n"
    body += ("    /**\n"
             "     * iconId → ImageVector。**UI 层取分类图标的唯一入口**。\n"
             "     * 越界或未知值兜底到 [Tag]（43）—— 与数据库迁移 MIGRATION_3_4 的 ELSE 同一兜底。\n"
             "     */\n"
             "    fun slCategoryIcon(iconId: Int): ImageVector = when (iconId) {\n")
    body += "".join(f"        {ic.icon_id} -> {kname(ic)}\n" for ic in cats)
    body += "        else -> Tag\n    }\n"
    body += "}\n"
    body += "\n/** 顶层委托：调用点可 `import …ui.icon.slCategoryIcon` 后短名调用 */\n"
    body += "fun slCategoryIcon(iconId: Int): ImageVector = SlCategoryIcons.slCategoryIcon(iconId)\n"

    body += "\n/** 图标集入口：SlIcons.Category.* / SlIcons.Nav.* / SlIcons.Ui.* / SlIcons.Illustration.* / SlIcons.Status.* */\n"
    body += "object SlIcons {\n\n    val Category: SlCategoryIcons get() = SlCategoryIcons\n"
    body += "\n    /** B 组 · 底部导航 */\n    object Nav {\n"
    for ic in navs:
        body += val(ic)
    body += "    }\n"
    body += "\n    /** C 组功能 + D 组行内 + E 组极小（使用场景见注释） */\n    object Ui {\n"
    for ic in funcs + inls + xss:
        body += f"    /** {ic.usage}（替换 {ic.replaces}） */\n"
        body += val(ic)
    body += "    }\n"
    body += "\n    /** F 组 · 空态插图 */\n    object Illustration {\n"
    for ic in lgs:
        body += val(ic)
    body += "    }\n"
    body += "\n    /** G 组 · 状态符号（报销维度；核对维度复用 Ui.Check） */\n    object Status {\n"
    for ic in sts:
        body += val(ic)
    body += "    }\n}\n"

    (OUT / "SlIconsV3.kt").write_text(head + body, encoding="utf-8")


def write_appicon():
    APPICON_DIR.mkdir(exist_ok=True, parents=True)
    RES_DIR.mkdir(exist_ok=True, parents=True)
    for c in ("a", "b", "c"):
        (APPICON_DIR / f"concept-{c}.svg").write_text(
            icons_system.appicon_svg(c, px=512) + "\n", encoding="utf-8")
    (APPICON_DIR / "chosen-layers.svg").write_text(
        icons_system.appicon_svg("a", px=512) + "\n", encoding="utf-8")

    bg, fg, tape, mono = icons_system.appicon_layers("a")

    def vec_xml(paths, fill_color, stroke_color, stroke_w):
        parts = ['<?xml version="1.0" encoding="utf-8"?>',
                 '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
                 '    android:width="108dp" android:height="108dp"',
                 '    android:viewportWidth="108" android:viewportHeight="108">']
        for d in paths:
            parts.append(f'    <path android:pathData="{d}"')
            if fill_color and not stroke_color:
                parts.append(f'        android:fillColor="{fill_color}"/>')
            else:
                parts.append(f'        android:fillColor="#00000000"')
                parts.append(f'        android:strokeColor="{stroke_color}"')
                parts.append(f'        android:strokeWidth="{stroke_w}"')
                parts.append(f'        android:strokeLineCap="round"')
                parts.append(f'        android:strokeLineJoin="round"/>')
        parts.append('</vector>')
        return "\n".join(parts)

    # foreground：墨线 + 青绿胶带压角
    fg_xml = ['<?xml version="1.0" encoding="utf-8"?>',
              '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
              '    android:width="108dp" android:height="108dp"',
              '    android:viewportWidth="108" android:viewportHeight="108">']
    fg_xml.append('    <!-- 简账 v3 App 图标「纸墨账本」：手绘本子 + ¥ + 青绿胶带（safe zone r33@54） -->')
    for d in fg:
        fg_xml.append(f'    <path android:pathData="{d}" android:fillColor="#00000000"')
        fg_xml.append(f'        android:strokeColor="#17403A" android:strokeWidth="4.5"')
        fg_xml.append(f'        android:strokeLineCap="round" android:strokeLineJoin="round"/>')
    fg_xml.append(f'    <path android:pathData="{tape}" android:fillColor="#0F766E"/>')
    fg_xml.append('</vector>')
    (RES_DIR / "ic_launcher_foreground.xml").write_text("\n".join(fg_xml), encoding="utf-8")

    # monochrome：仅墨线（系统会重着色，颜色值不参与、只看 alpha）
    (RES_DIR / "ic_launcher_monochrome.xml").write_text(
        vec_xml(mono, None, "#000000", 4.5), encoding="utf-8")

    (RES_DIR / "colors.xml.snippet.txt").write_text(
        '<color name="ic_launcher_background">#F7F3E9</color>\n', encoding="utf-8")
    (RES_DIR / "ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        '</adaptive-icon>\n', encoding="utf-8")


# ---------------------------------------------------------------------
# 自检 contact sheet：qlmanage 渲染后肉眼过一遍
# ---------------------------------------------------------------------

def write_contact_sheet(icons):
    cols, cell, scale, label_h = 8, 108, 3.0, 22
    rows = (len(icons) + cols - 1) // cols
    w = cols * cell + 40
    h = rows * (cell + label_h) + 60
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">']
    out.append(f'<rect width="{w}" height="{h}" fill="{PAPER}"/>')
    for idx, ic in enumerate(icons):
        r, c = divmod(idx, cols)
        x = 20 + c * cell
        y = 20 + r * (cell + label_h)
        body = ic.svg(INK).replace(f'viewBox="0 0 {ic.grid} {ic.grid}"',
                                   f'x="{x + 8}" y="{y + 2}" width="{ic.grid * scale}" height="{ic.grid * scale}"')
        body = body.replace("<svg ", "<svg ", 1)
        out.append(body.replace("</svg>", "</svg>"))
        out.append(f'<text x="{x + cell / 2}" y="{y + cell + 2}" font-size="13" '
                   f'fill="{INK2}" text-anchor="middle" font-family="monospace">{idx + 1} {ic.name}</text>')
    out.append("</svg>")
    (OUT / "contact-sheet.svg").write_text("\n".join(out), encoding="utf-8")


def write_preview(icons):
    def card(ic, px, color=INK, bg="transparent"):
        svg_body = ic.svg(color)
        svg_inline = re.sub(r'\s(width|height)="[^"]*"', "", svg_body, count=1)
        rep = ic.replaces
        rep_html = (f'<s style="opacity:.45">{rep}</s>' if ic.replaces_type == "emoji" else rep)
        extra = f' · iconId {ic.icon_id}' if ic.icon_id else ""
        return (f'<div class="card" style="background:{bg}">'
                f'<div class="ico" style="width:{px}px;height:{px}px">{svg_inline}</div>'
                f'<div class="nm">{ic.name}</div>'
                f'<div class="meta">{rep_html}</div>'
                f'<div class="meta">{ic.tier} · 描边 {ic.stroke} · 居中({ic.dx:+.1f},{ic.dy:+.1f}){extra}</div>'
                f'<div class="use">{ic.usage}</div></div>')

    by = lambda g: [i for i in icons if i.group == g]
    grp_names = {"category": "A · 分类 / 分区（iconId 1–50）", "nav": "B · 底部导航",
                 "function": "C · 功能图标（Material 9 枚接班）", "inline": "D · 行内标记（12–18dp）",
                 "xs": "E · 极小对勾（11dp）", "illustration": "F · 空态插图（32 网格）",
                 "status": "G · 状态符号（14dp 槽）"}

    nav_html = "".join(card(i, 44) for i in by("nav"))
    tape_chips = "".join(f'<span class="chip" style="background:{c}" title="{n}"></span>' for n, c in TAPE)
    dark_nav = "".join(card(i, 44, color=INK_DARK) for i in by("nav")[:2])

    def _inline(ic, px, color=INK):
        return re.sub(r'\s(width|height)="[^"]*"', "", ic.svg(color), count=1)

    ladder_items = [
        (by("nav")[1], 24, INK, "nav 24dp · 1.5", "transparent"),
        (by("inline")[0], 15, INK, "inline 15dp · 1.7", "transparent"),
        (by("status")[0], 14, EXPENSE, "status 14dp · 1.8", "transparent"),
        (by("xs")[0], 11, "#FFFFFF", "xs 11dp · 2.0 白", INK),
        (by("illustration")[0], 32, INK, "lg 32dp · 1.6", "transparent"),
    ]
    ladder_cells = "".join(
        f'<div class="litem"><div class="ico {"inkchip" if bg == INK else ""}" '
        f'style="width:{px + 10}px;height:{px + 10}px;background:{bg}">{_inline(ic, px, color)}</div>{lbl}</div>'
        for ic, px, color, lbl, bg in ladder_items)
    ladder = f'<div class="ladder">{ladder_cells}</div>'

    app_html = ""
    for c, nm, desc in (("a", "方案 A「纸墨账本」★ 推荐", "手绘本子 + ¥ + 青绿胶带压角：账本剪影 48dp 仍可辨，胶带是唯一色强调"),
                        ("b", "方案 B「双线墨签」", "大 ¥ + 页眉双线：墨味最足，但 ¥ 语义泛（任何记账 App 可用）"),
                        ("c", "方案 C「索引贴叠」", "三张索引贴：导航隐喻直给，小尺寸下三条色带易糊")):
        masks = "".join(
            f'<div class="mk {m}">{icons_system.appicon_svg(c, px=104, mask=None)}</div>'
            for m in ("sq", "ci", "squ"))
        app_html += (f'<div class="acard"><div class="anm">{nm}</div>'
                     f'<div class="arow">{masks}</div><div class="adesc">{desc}</div></div>')

    html = f'''<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<title>简账图标集 v3「纸墨手绘」</title>
<style>
  body{{background:{PAPER};color:{INK};font-family:"Source Han Sans CN","Noto Sans CJK SC",sans-serif;margin:0;padding:48px 40px}}
  h1,h2{{font-family:"LXGW WenKai","Kaiti SC",serif;font-weight:600}}
  h1{{font-size:30px;margin:0 0 4px}} h2{{font-size:20px;margin:44px 0 6px}}
  .sub{{color:{INK2};font-size:13px;margin-bottom:8px}}
  .chip{{display:inline-block;width:22px;height:22px;border-radius:3px;border:.5px solid {RULE_STRONG};margin-right:4px;vertical-align:middle}}
  .grid{{display:flex;flex-wrap:wrap;gap:10px}}
  .card{{width:158px;background:{SLIP};border:.5px solid {RULE_STRONG};border-radius:3px;padding:10px}}
  .ico{{display:flex;align-items:center;justify-content:center;margin:2px 0 6px}}
  .nm{{font-size:12px;font-weight:600}} .meta{{font-size:10.5px;color:{INK2}}} .use{{font-size:10.5px;color:{INK2};margin-top:2px}}
  .ladder{{display:flex;gap:18px;align-items:flex-end;background:{SLIP};border:.5px solid {RULE_STRONG};border-radius:3px;padding:14px}}
  .litem{{font-size:10.5px;color:{INK2};text-align:center}} .inkchip{{background:{INK};border-radius:3px;padding:2px}}
  .acard{{background:{SLIP};border:.5px solid {RULE_STRONG};border-radius:3px;padding:14px;margin-bottom:12px}}
  .anm{{font-weight:600;font-size:14px}} .arow{{display:flex;gap:14px;margin:10px 0}}
  .mk{{width:104px;height:104px;overflow:hidden}} .mk.ci{{border-radius:999px}} .mk.squ{{border-radius:30px}} .mk.sq{{border-radius:10px}}
  .adesc{{font-size:11.5px;color:{INK2}}}
  .dark{{background:#1C1A16;border-radius:3px;padding:12px;display:inline-flex;gap:10px}}
  .dark .card{{background:transparent;border-color:#4A4438}}
</style></head><body>
<h1>简账 SimpleLedger · 图标集 v3「纸墨手绘」</h1>
<div class="sub">72 枚 · full 1.5 / inline 1.7 / xs 2.0 / status 1.8 / lg 1.6 · 圆头 · 单色墨 · 光学居中内建 · 胶带色板 {tape_chips}</div>
{ladder}
<h2>{grp_names["category"]}</h2><div class="grid">{"".join(card(i, 44) for i in by("category"))}</div>
<h2>{grp_names["nav"]}</h2><div class="grid">{nav_html}</div>
<h2>{grp_names["function"]}</h2><div class="grid">{"".join(card(i, 44) for i in by("function"))}</div>
<h2>{grp_names["inline"]}</h2><div class="grid">{"".join(card(i, 30) for i in by("inline"))}</div>
<h2>{grp_names["xs"]} / {grp_names["status"]}</h2><div class="grid">{"".join(card(i, 30, color="#FFFFFF", bg=INK) for i in by("xs"))}{card(by("status")[0], 30, color=EXPENSE)}{card(by("status")[1], 30, color=INCOME)}</div>
<h2>{grp_names["illustration"]}</h2><div class="grid">{"".join(card(i, 64) for i in by("illustration"))}</div>
<h2>深色主题抽查（米白墨 #F5F0E4 on #1C1A16）</h2><div class="dark">{dark_nav}</div>
<h2>App 图标 · 三方案（108 视口 / 圆形可见区 r33）</h2>{app_html}
</body></html>'''
    (OUT / "preview.html").write_text(html, encoding="utf-8")


def main():
    icons = icons_category.load() + icons_system.load_system()
    errs, warns = validate(icons)
    for e in errs:
        print("ERROR:", e)
    for w in warns:
        print("WARN :", w)
    if errs:
        raise SystemExit(1)
    write_svgs(icons)
    write_manifest(icons)
    write_kotlin(icons)
    write_appicon()
    write_contact_sheet(icons)
    write_preview(icons)
    print(f"OK · {len(icons)} icons → {ICON_DIR}")
    print(f"OK · manifest.json / preview.html / SlIconsV3.kt / appicon/ / android_res/ / contact-sheet.svg")


if __name__ == "__main__":
    main()
