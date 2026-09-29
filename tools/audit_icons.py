#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
图标几何审查器 —— 把「好看不好看」里能用数字说清的部分量出来。

审查四项（都是图标集最容易出问题、且肉眼难察觉的地方）：
  1. 光学居中：图形包围盒中心相对画布中心的偏移。偏移大 = 看着"歪"
  2. 安全边：含描边的包围盒应落在 [2, 尺寸-2] 内。越界 = 贴边、拥挤
  3. 视觉重量：以「总墨长」为代理量。同类图标之间差异过大会显得忽轻忽重
  4. 比例：宽高比。异常值是画歪或形状认不出的信号

用法
----
    python3 tools/audit_icons.py            # 全部图标
    python3 tools/audit_icons.py --group category
退出码 0 = 无问题项。
"""

from __future__ import annotations

import json
import math
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

# 审查对象必须是**当前出货的那一套**。v3「纸墨手绘」(2026-09-22) 落地后，
# 出货源是 docs/design/icons-v3/icons/ —— 其生成物 SlIconsV3.kt 与
# app/src/main/java/com/simpleledger/app/ui/icon/SlIcons.kt 逐字节相同。
# 旧的 docs/design/icons/ 是 v2.0.0 遗留（路径带手绘抖动），已被取代；
# 早期版本误指向它，导致几何审查一直在量一套不再出货的图。
# 可用 --icon-dir <path> 覆盖（见 main()）。
ICON_DIR = ROOT / "docs/design/icons-v3/icons"

# 出货集应有 72 枚（manifest.json 的 icons 长度）。数量不符通常意味着
# 指错了目录或生成链没跑完 —— 与其静默量错对象，不如直接报错。
EXPECTED_ICON_COUNT = 72

NUM = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
CMD = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]")

SAFE_MARGIN = 2.0        # 设计规范：四周留 2dp 安全边
CENTER_TOLERANCE = 0.6   # 包围盒中心偏移超过这个值就算偏心


# ---------------------------------------------------------------------------
# 路径 → 采样点（弧用端点参数化按 SVG 规范 F.6.5 展开）
# ---------------------------------------------------------------------------
def _arc_points(x1, y1, rx, ry, phi_deg, large, sweep, x2, y2, n=24):
    if rx == 0 or ry == 0:
        return [(x2, y2)]
    phi = math.radians(phi_deg)
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    x1p = cos_p * dx2 + sin_p * dy2
    y1p = -sin_p * dx2 + cos_p * dy2
    rx, ry = abs(rx), abs(ry)
    lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cos_p * cxp - sin_p * cyp + (x1 + x2) / 2.0
    cy = sin_p * cxp + cos_p * cyp + (y1 + y2) / 2.0

    def ang(ux, uy, vx, vy):
        d = (ux * vx + uy * vy) / (math.hypot(ux, uy) * math.hypot(vx, vy))
        a = math.acos(max(-1.0, min(1.0, d)))
        return -a if ux * vy - uy * vx < 0 else a

    th1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dth > 0:
        dth -= 2 * math.pi
    elif sweep and dth < 0:
        dth += 2 * math.pi
    return [
        (cx + rx * math.cos(phi) * math.cos(th1 + dth * i / n)
            - ry * math.sin(phi) * math.sin(th1 + dth * i / n),
         cy + rx * math.sin(phi) * math.cos(th1 + dth * i / n)
            + ry * math.cos(phi) * math.sin(th1 + dth * i / n))
        for i in range(1, n + 1)
    ]


def _bez(p0, p1, p2, p3, n=16):
    out = []
    for i in range(1, n + 1):
        t = i / n
        u = 1 - t
        out.append((
            u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
            u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1],
        ))
    return out


def flatten(d: str):
    """把 path data 展平成点列。返回 [(点, 是否是子路径起点)]"""
    toks = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)", d)
    pts, cur, start = [], (0.0, 0.0), (0.0, 0.0)
    i, cmd, prev_c, prev_q = 0, None, None, None
    while i < len(toks):
        if CMD.match(toks[i]):
            cmd = toks[i]
            i += 1
        if cmd is None:
            break

        def f(k):
            return float(toks[k])

        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            x, y = f(i), f(i + 1); i += 2
            cur = (cur[0] + x, cur[1] + y) if rel else (x, y)
            start = cur
            pts.append((cur, True))
            cmd = "l" if rel else "L"
        elif c == "L":
            x, y = f(i), f(i + 1); i += 2
            cur = (cur[0] + x, cur[1] + y) if rel else (x, y)
            pts.append((cur, False))
        elif c == "H":
            x = f(i); i += 1
            cur = (cur[0] + x, cur[1]) if rel else (x, cur[1])
            pts.append((cur, False))
        elif c == "V":
            y = f(i); i += 1
            cur = (cur[0], cur[1] + y) if rel else (cur[0], y)
            pts.append((cur, False))
        elif c in ("C", "S"):
            if c == "C":
                x1, y1, x2, y2, x, y = (f(i + k) for k in range(6)); i += 6
                if rel:
                    x1, y1, x2, y2, x, y = (cur[0] + x1, cur[1] + y1, cur[0] + x2,
                                            cur[1] + y2, cur[0] + x, cur[1] + y)
            else:
                x2, y2, x, y = (f(i + k) for k in range(4)); i += 4
                if rel:
                    x2, y2, x, y = cur[0] + x2, cur[1] + y2, cur[0] + x, cur[1] + y
                x1, y1 = (2 * cur[0] - prev_c[0], 2 * cur[1] - prev_c[1]) if prev_c else cur
            for p in _bez(cur, (x1, y1), (x2, y2), (x, y)):
                pts.append((p, False))
            prev_c, cur = (x2, y2), (x, y)
        elif c in ("Q", "T"):
            if c == "Q":
                x1, y1, x, y = (f(i + k) for k in range(4)); i += 4
                if rel:
                    x1, y1, x, y = cur[0] + x1, cur[1] + y1, cur[0] + x, cur[1] + y
            else:
                x, y = f(i), f(i + 1); i += 2
                if rel:
                    x, y = cur[0] + x, cur[1] + y
                x1, y1 = (2 * cur[0] - prev_q[0], 2 * cur[1] - prev_q[1]) if prev_q else cur
            for p in _bez(cur, (x1, y1), (x1, y1), (x, y)):
                pts.append((p, False))
            prev_q, cur = (x1, y1), (x, y)
        elif c == "A":
            rx, ry, rot, large, sweep, x, y = (f(i + k) for k in range(7)); i += 7
            if rel:
                x, y = cur[0] + x, cur[1] + y
            for p in _arc_points(cur[0], cur[1], rx, ry, rot, int(large), int(sweep), x, y):
                pts.append((p, False))
            cur = (x, y)
        elif c == "Z":
            pts.append((start, False))
            cur = start
        else:
            i += 1
        prev_c = prev_c if c in ("C", "S") else None
        prev_q = prev_q if c in ("Q", "T") else None
    return pts


def measure(svg: pathlib.Path):
    src = svg.read_text(encoding="utf-8")
    size = float(re.search(r'viewBox="0 0 (\d+)', src).group(1))
    sw = float(re.search(r'stroke-width="([\d.]+)"', src).group(1))
    # 光学居中修正：生成器会把字形包在 <g transform="translate(dx dy)"> 里。
    # 审查时必须把它算进去，否则会拿"修正前的坐标"去判断，得到假阳性。
    g = re.search(r'<g transform="translate\(([-\d.]+) ([-.\d]+)\)"', src)
    tx, ty = (float(g.group(1)), float(g.group(2))) if g else (0.0, 0.0)
    all_pts, ink = [], 0.0
    for tag, raw in re.findall(r"<(path|circle)\b([^>]*?)/?>", src):
        attrs = dict(re.findall(r'([a-zA-Z-]+)="([^"]*)"', raw))
        filled = attrs.get("fill", "none") not in ("none", "")
        if tag == "circle":
            cx, cy, r = float(attrs["cx"]), float(attrs["cy"]), float(attrs["r"])
            pts = ([((cx + r, cy), False)]
                   + [(p, False) for p in _arc_points(cx + r, cy, r, r, 0, 1, 1, cx - r, cy)]
                   + [(p, False) for p in _arc_points(cx - r, cy, r, r, 0, 1, 1, cx + r, cy)])
            w = 0.0 if filled else sw
        else:
            pts = flatten(attrs.get("d", ""))
            w = 0.0 if filled else sw
        seq = [(x + tx, y + ty) for x, y in (p for p, _ in pts)]
        ink += sum(math.dist(seq[k], seq[k + 1]) for k in range(len(seq) - 1))
        all_pts += seq
    if not all_pts:
        return None
    xs = [p[0] for p in all_pts]
    ys = [p[1] for p in all_pts]
    pad = sw / 2
    bx0, bx1 = min(xs) - pad, max(xs) + pad
    by0, by1 = min(ys) - pad, max(ys) + pad
    return {
        "size": size, "stroke": sw, "ink": ink,
        "bbox": (bx0, by0, bx1, by1),
        "offset": (round((bx0 + bx1) / 2 - size / 2, 2),
                   round((by0 + by1) / 2 - size / 2, 2)),
        "margin": round(min(bx0, by0, size - bx1, size - by1), 2),
        "aspect": round((bx1 - bx0) / max(1e-6, by1 - by0), 2),
    }


def main() -> int:
    global ICON_DIR
    if "--icon-dir" in sys.argv:
        ICON_DIR = pathlib.Path(sys.argv[sys.argv.index("--icon-dir") + 1]).resolve()
    group = None
    if "--group" in sys.argv:
        group = sys.argv[sys.argv.index("--group") + 1]
    emit = "--emit-centering" in sys.argv

    # manifest 位置随代际不同：v2 与 svg 同目录，v3 在 svg 目录的上一层。
    manifest_path = ICON_DIR / "manifest.json"
    if not manifest_path.exists():
        manifest_path = ICON_DIR.parent / "manifest.json"
    if not manifest_path.exists():
        print(f"❌ 找不到 manifest.json（已试 {ICON_DIR} 及其上一层）")
        return 2

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    meta = {i["name"]: i for i in manifest["icons"]}

    svgs = sorted(ICON_DIR.glob("*.svg"))
    # 量错对象比量出问题更糟：先确认审的是完整出货集，再往下算。
    if not group and len(svgs) != EXPECTED_ICON_COUNT:
        print(f"❌ {ICON_DIR} 下有 {len(svgs)} 个 SVG，"
              f"与出货集应有的 {EXPECTED_ICON_COUNT} 枚不符 —— 请确认审查对象。")
        return 2

    rows = []
    for f in svgs:
        if group and meta.get(f.stem, {}).get("group") != group:
            continue
        m = measure(f)
        if m:
            # 视觉重量代理：墨长 × 描边宽 / 画布尺寸。
            # 三者都影响"看上去有多重"——不归一化就拿 32 网格的插图和 24 网格的图标比，是无意义的。
            m["weight"] = m["ink"] * m["stroke"] / m["size"]
            rows.append((f.stem, meta.get(f.stem, {}), m))

    # —— 光学居中修正量 ——
    # 把整个字形平移，使「含描边的包围盒」中心落到画布中心。
    # 只平移、不缩放；且平移后必须仍满足安全边 ≥ SAFE_MARGIN，否则夹紧并告警。
    if emit:
        corr = {}
        for name, _, m in rows:
            bx0, by0, bx1, by1 = m["bbox"]
            size = m["size"]
            dx = round(size / 2 - (bx0 + bx1) / 2, 2)
            dy = round(size / 2 - (by0 + by1) / 2, 2)
            # 夹紧：平移后最小边距仍要 >= SAFE_MARGIN
            for axis, d, lo, hi in (("x", dx, bx0, bx1), ("y", dy, by0, by1)):
                new_lo, new_hi = lo + d, hi + d
                if new_lo < SAFE_MARGIN:
                    d = SAFE_MARGIN - lo
                if new_hi > size - SAFE_MARGIN:
                    d = size - SAFE_MARGIN - hi
                if axis == "x":
                    dx = round(d, 2)
                else:
                    dy = round(d, 2)
            if abs(dx) > 0.05 or abs(dy) > 0.05:
                corr[name] = [dx, dy]
        out = ICON_DIR / "_centering.json"
        out.write_text(json.dumps(
            {"$comment": "光学居中修正量（由 tools/audit_icons.py --emit-centering 生成）。"
                         "把字形平移使含描边的包围盒中心落到画布中心；只平移不缩放，"
                         "且夹紧保证安全边 >= 2dp。两个生成器都读这个文件。",
             "offsets": corr}, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"✓ 写出 {out.relative_to(ROOT)}：{len(corr)} 枚需要修正\n")

    print("审查判据（每条都说明为什么）：")
    print("  · 偏心：所有图标都应视觉居中，这**跨组可比**。|偏移| > 0.8 需修，> 0.5 观察")
    print("  · 重量：墨长×描边/画布。**只在组内比**，且用 **MAD 稳健离群检验**")
    print("         （|值-中位数| > 2.5×MAD 才算离群 —— 看的是分布里有没有断档，")
    print("          而不是「偏离中位数百分之多少」。后者切一条右偏分布必然误伤）")
    print("     ⚠️ 该代理有两处固有偏差，必须人工判别，不能盲信：")
    print("        a) 对角线：同视觉跨度的 × 比 + 墨长更长 → close/add 类会被误判「过重」")
    print("        b) 组内异质：功能组里 5 笔画的垃圾桶和 1 笔画的箭头本就不可比")
    print("  · 比例：**只报告不判错**。瓶子本来就细高、箭头本来就扁宽，比例异常不等于难看")
    print(f"  · 安全边：含描边包围盒应 ≥ {SAFE_MARGIN}dp\n")

    issues = []
    for grp in sorted({r[1].get("group", "?") for r in rows}):
        sel = [r for r in rows if r[1].get("group") == grp]
        ws = sorted(r[2]["weight"] for r in sel)
        med = ws[len(ws) // 2]
        mad = sorted(abs(w - med) for w in ws)[len(ws) // 2] or 0.15
        # 统计可靠性门槛：MAD 相对中位数太小 = 数据挤成一簇（如功能组里 4 个同款箭头），
        # 此时离群带会被压得极窄，把正常差异也判成离群。这种情况**不给结论**，只报分布。
        reliable = (mad / med) >= 0.15 if med else False
        lo_cut, hi_cut = med - 2.5 * mad, med + 2.5 * mad
        if reliable:
            print(f"{'=' * 96}\n【{grp}】{len(sel)} 枚　中位数 {med:.1f}　MAD {mad:.1f}　"
                  f"离群带 <{lo_cut:.1f} 或 >{hi_cut:.1f}\n{'=' * 96}")
        else:
            print(f"{'=' * 96}\n【{grp}】{len(sel)} 枚　中位数 {med:.1f}　MAD {mad:.1f}　"
                  f"⚠️ 分布过紧（MAD/中位数 = {mad / med:.2f} < 0.15）→ **不做重量离群判定**\n"
                  f"  理由：该组由若干「同款重复」与若干「结构完全不同」的符号混成，"
                  f"墨长差异反映的是图形固有复杂度，不是设计缺陷。\n{'=' * 96}")
        print(f"{'图标':<18}{'墨长':>7}{'重量':>7}{'偏心x':>7}{'偏心y':>7}{'边距':>7}{'比例':>6}  判定")
        print("-" * 96)
        for name, mm, m in sorted(sel, key=lambda r: -r[2]["weight"]):
            flags = []
            if m["margin"] < SAFE_MARGIN - 0.05:
                flags.append("触边")
            ox, oy = m["offset"]
            if abs(ox) > 0.8 or abs(oy) > 0.8:
                flags.append("偏心")
            elif abs(ox) > 0.5 or abs(oy) > 0.5:
                flags.append("略偏")
            if reliable:
                if m["weight"] > hi_cut:
                    flags.append("过重")
                elif m["weight"] < lo_cut:
                    flags.append("过轻")
            tick = "✅" if not flags else "⚠️ " + " ".join(flags)
            print(f"{name:<18}{m['ink']:>7.1f}{m['weight']:>7.1f}{ox:>7}{oy:>7}"
                  f"{m['margin']:>7}{m['aspect']:>6}  {tick}")
            # 「略偏」不算问题项，只提示
            hard = [f for f in flags if f != "略偏"]
            if hard:
                issues.append((grp, name, hard))

    print("-" * 96)
    # 门禁分级 —— 这是关于工具本身的设计决定：
    #   · 偏心 / 触边 是**精确的几何事实**（包围盒算出来的），可作硬门禁
    #   · 重量 只是**有已知偏差的代理量**（对角线偏差 + 组内异质，见上方说明），
    #     不该有能力让构建失败。只作提示。
    hard = [(g, n, f) for g, n, f in issues if set(f) & {"偏心", "触边"}]
    soft = [(g, n, f) for g, n, f in issues if not (set(f) & {"偏心", "触边"})]
    print(f"硬问题（偏心 / 触边）{len(hard)}　·　重量提示 {len(soft)}")
    for g, n, f in hard:
        print(f"  ❌ [{g}] {n:<18} {' '.join(f)}")
    for g, n, f in soft:
        print(f"  ⚠️  [{g}] {n:<18} {' '.join(f)}（代理量提示，非缺陷；如判断为真问题再人工调整）")
    return 1 if hard else 0


if __name__ == "__main__":
    sys.exit(main())
