#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SVG → Compose ImageVector 生成器。

输入  docs/design/icons/*.svg + manifest.json（与设计规范同源）
位置  tools/generate_icons.py（与 verify_migration_v4.py 同类：构建期工具，不放设计资源目录）
输出  app/src/main/java/com/simpleledger/app/ui/icon/
        SlIcons.kt          辅助函数 + 导航/功能/行内/插图/状态 共 22 枚
        SlCategoryIcons.kt  分类图标 50 枚（单文件过大，独立出来）

为什么用 ImageVector 而不是 VectorDrawable
----------------------------------------
项目现有 24 处调用都是 `Icon(imageVector = Icons.Filled.X, …)`，而 `Icons.Filled.*`
本身就是 ImageVector。走 ImageVector 后调用点只改名字（`Icons.Filled.Add` → `SlIcons.Ui.Add`），
不必把参数从 `imageVector` 改成 `painter = painterResource(…)`，也不会出现两种图标类型并存。

规范要点（详见 docs/design/icons/manifest.json）
-----------------------------------------------
· 画布 24dp（插图 32dp）· 圆头端点 · 只描边不填充 · 单色（由 Icon 的 tint 着色）
· 描边按光学尺寸分档：full 1.5 / inline 1.7 / xs 2.0 / lg 1.6 / status 1.8
· 手工感来自路径几何本身的不对称，**不在运行时抖动**
"""

from __future__ import annotations

import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]   # tools/ → 仓库根
ICON_DIR = ROOT / "docs/design/icons"
OUT_DIR = ROOT / "app/src/main/java/com/simpleledger/app/ui/icon"

ELEMENT = re.compile(r"<(path|circle)\b([^>]*?)/?>", re.S)

_cent = ICON_DIR / "_centering.json"
CENTERING = (json.loads(_cent.read_text(encoding="utf-8"))["offsets"]
             if _cent.exists() else {})
ATTR = re.compile(r'([a-zA-Z-]+)="([^"]*)"')


def pascal(name: str) -> str:
    return "".join(part.capitalize() for part in re.split(r"[-_]", name))


def circle_to_path(cx: float, cy: float, r: float) -> str:
    """圆 → 等价路径（两段半圆弧），保证所有图形都用 path 表达，风格统一。"""
    return (f"M{cx - r:.1f} {cy:.1f}"
            f"a{r:.1f} {r:.1f} 0 1 0 {r * 2:.1f} 0"
            f"a{r:.1f} {r:.1f} 0 1 0 -{r * 2:.1f} 0")


def parse_svg(path: pathlib.Path) -> "list[tuple[str, bool]]":
    """返回 [(pathData, isFilled)]。isFilled = 该元素显式带了非 none 的 fill。"""
    src = path.read_text(encoding="utf-8")
    out: list[tuple[str, bool]] = []
    for tag, raw in ELEMENT.findall(src):
        attrs = dict(ATTR.findall(raw))
        filled = attrs.get("fill", "none") not in ("none", "")
        if tag == "path":
            d = attrs.get("d", "").strip()
        else:
            d = circle_to_path(float(attrs["cx"]), float(attrs["cy"]), float(attrs["r"]))
        if d:
            out.append((d, filled))
    return out


def kotlin_str(s: str) -> str:
    """Kotlin 字符串字面量转义（路径里只有 ASCII，但仍统一处理反斜杠与引号）。"""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


HELPERS = '''package com.simpleledger.app.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/*
 * 手绘图标集（由 docs/design/icons/_generate.py 生成，请勿手改）
 * ==================================================================
 * 规范：24dp 画布（插图 32dp）· 圆头端点 · 只描边不填充 · 单色
 *      描边按光学尺寸分档 —— full 1.5 / inline 1.7 / xs 2.0 / lg 1.6 / status 1.8
 *
 * 为什么小尺寸要单独出图标：同一图形在 11dp 与 24dp 下不能共用一套细节。
 * 笔画少的在小尺寸下会糊成一团灰，笔画多的在大尺寸下会显空。所以
 * inline / xs 档**减笔画并把描边加粗**——这不是冗余资源，是必需项。
 *
 * 颜色：路径一律用 `Color.Black` 构建，实际颜色由 `Icon(tint = …)` 或
 * `LocalContentColor` 决定。**分类图标不得上色**——颜色语义已被「分区身份」独占
 * （分区色条 / 胶带色板），若分类图标也上色，同一行会出现两套色彩语义。
 *
 * 手工感来自路径几何本身的不对称，**不在运行时抖动**：抖动会在滚动时闪烁，
 * 且在不同屏幕密度下渲染成糊团。
 */

/** 一条路径：[d] 为 SVG path data；[filled] 为 true 时填充而非描边（语义需要时使用） */
internal data class IconPath(val d: String, val filled: Boolean = false)

/** 描边路径 */
internal fun p(d: String) = IconPath(d)

/** 填充路径（仅用于「已完成」实心圆、动物眼睛等语义必需处） */
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
    // 光学居中：把字形平移到画布中心（修正量由 tools/audit_icons.py --emit-centering 生成）
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
 * 图标集入口。
 *
 * 命名与分组：`SlIcons.Category.RiceBowl` / `SlIcons.Nav.Section` / `SlIcons.Ui.Add` …
 * 与 `docs/design/icons/manifest.json` 的 `name` 一一对应。
 *
 * 用 `by lazy`：72 枚若在类初始化时全建，会拖慢冷启动；实际每次只用其中几枚。
 */
object SlIcons {

    /** 分类 / 分区图标 50 枚（iconId 1–50） */
    val Category: SlCategoryIcons get() = SlCategoryIcons

'''

FOOTER_TAIL = '''}
'''

CATEGORY_HEADER = '''package com.simpleledger.app.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 分类 / 分区图标 50 枚（**与数据库 `iconId` 1–50 严格一一对应**）。
 *
 * 由 docs/design/icons/_generate.py 生成，请勿手改。
 * 映射关系见 `IconMapping`；UI 侧用 `slCategoryIcon(iconId)` 取图并自带兜底。
 *
 * 单列成文件是因为 50 枚一次生成会让 SlIcons.kt 超过 800 行。
 */
object SlCategoryIcons {

'''


def emit_icon(name: str, size: int, stroke: float, paths: "list[tuple[str, bool]]",
              indent: str) -> list[str]:
    """生成一个 `val X: ImageVector by lazy { buildIcon(...) }`"""
    off = CENTERING.get(name, [0, 0])
    lines = [f"{indent}val {pascal(name)}: ImageVector by lazy {{",
             f"{indent}    buildIcon(",
             f'{indent}        "{name}",',
             f"{indent}        size = {size},",
             f"{indent}        strokeWidth = {stroke}f,",
             f"{indent}        translationX = {off[0]}f,",
             f"{indent}        translationY = {off[1]}f,"]
    for d, filled in paths:
        lines.append(f"{indent}        {'pf' if filled else 'p'}({kotlin_str(d)}),")
    lines += [f"{indent}    )", f"{indent}}}", ""]
    return lines


def main() -> None:
    manifest = json.loads((ICON_DIR / "manifest.json").read_text(encoding="utf-8"))
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    groups: dict[str, list] = {}
    for item in manifest["icons"]:
        groups.setdefault(item["group"], []).append(item)

    # ---------- SlIcons.kt：辅助函数 + 22 枚非分类图标 ----------
    # 按「对象」归并：nav→Nav；function/inline/xs→Ui；illustration→Illustration；status→Status
    bucket_order = [
        ("Nav", ["nav"], "底部导航（4 枚）"),
        ("Ui", ["function", "inline", "xs"], "功能图标 + 行内标记 + 极小对勾（13 枚）"),
        ("Illustration", ["illustration"], "空态插图（2 枚，32dp 画布）"),
        ("Status", ["status"], "账目状态符号（2 枚，报销维度）"),
    ]

    out = [HELPERS]
    for obj, keys, doc in bucket_order:
        out.append(f"    /** {doc} */")
        out.append(f"    object {obj} {{")
        out.append("")
        for key in keys:
            for item in groups.get(key, []):
                paths = parse_svg(ICON_DIR / f"{item['name']}.svg")
                # nav 组的成员名去掉 nav- 前缀（调用点写 SlIcons.Nav.Section，而不是 Nav.NavSection）
                # nav / status 组去掉前缀，避免调用点出现 SlIcons.Nav.NavSection、
                # SlIcons.Status.StatusPending 这类重复命名
                _strip = {"nav": "nav-", "status": "status-"}
                kname = item["name"]
                if item["group"] in _strip:
                    kname = kname.removeprefix(_strip[item["group"]])
                out += emit_icon(kname, item["viewBox"], float(item["strokeWidth"]),
                                 paths, "        ")
        out.append("    }")
        out.append("")

    out.append("}")
    out.append("")
    (OUT_DIR / "SlIcons.kt").write_text("\n".join(out), encoding="utf-8")
    nav_ui = sum(len(groups.get(k, [])) for _, ks, _ in bucket_order for k in ks)

    # ---------- SlCategoryIcons.kt：50 枚分类图标 ----------
    cat = [CATEGORY_HEADER]
    for item in groups["category"]:
        paths = parse_svg(ICON_DIR / f"{item['name']}.svg")
        cat += emit_icon(item["name"], item["viewBox"], float(item["strokeWidth"]),
                         paths, "    ")
    # iconId → ImageVector 查表（UI 层唯一入口；越界兜底到 Tag，与数据库迁移兜底一致）
    cat.append("    /**")
    cat.append("     * 全部 50 枚（iconId 升序）。供图标选择网格枚举——")
    cat.append("     * 顺序与 manifest.json 一致，选择网格的行/列布局以此为唯一依据。")
    cat.append("     */")
    cat.append("    val allIcons: List<Pair<Int, ImageVector>> = listOf(")
    for item in groups["category"]:
        cat.append(f'        {item["iconId"]} to {pascal(item["name"])},')
    cat.append("    )")
    cat.append("")
    cat.append("    /**")
    cat.append("     * iconId → ImageVector。**UI 层取分类图标的唯一入口**。")
    cat.append("     *")
    cat.append("     * 越界或未知值一律兜底到 [Tag]（43）—— 与数据库迁移 `MIGRATION_3_4` 的")
    cat.append("     * `ELSE ${IconMapping.DEFAULT_CATEGORY_ICON_ID}` 是同一个兜底，保证任何脏数据")
    cat.append("     * （旧版本残留、手动改库、未来 iconId 被删除）都只会显示默认图标，不会崩。")
    cat.append("     */")
    cat.append("    fun slCategoryIcon(iconId: Int): ImageVector = when (iconId) {")
    for item in groups["category"]:
        cat.append(f'        {item["iconId"]} -> {pascal(item["name"])}')
    cat.append("        else -> Tag")
    cat.append("    }")
    cat.append("}")
    cat.append("")
    cat.append("/**")
    cat.append(" * 顶层委托：让调用点可以 `import …ui.icon.slCategoryIcon` 后短名调用，")
    cat.append(" * 而不必写成 `SlCategoryIcons.slCategoryIcon(…)`。")
    cat.append(" * 实际逻辑在 [SlCategoryIcons.slCategoryIcon]，含越界兜底。")
    cat.append(" */")
    cat.append("fun slCategoryIcon(iconId: Int): ImageVector = SlCategoryIcons.slCategoryIcon(iconId)")
    cat.append("")
    (OUT_DIR / "SlCategoryIcons.kt").write_text("\n".join(cat), encoding="utf-8")

    print(f"✓ SlIcons.kt            {nav_ui} 枚（Nav/Ui/Illustration/Status）")
    print(f"✓ SlCategoryIcons.kt    {len(groups['category'])} 枚（分类 / 分区）")
    print(f"  输出目录 {OUT_DIR.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
