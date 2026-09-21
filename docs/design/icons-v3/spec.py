#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
简账 SimpleLedger · 图标系统 v3「纸墨手绘」—— 公共规范与工具
================================================================
单一几何源：所有图标路径集中在本包，SVG / manifest / preview / Kotlin / 画布
ops 全部由 build.py 从同一份数据产出，杜绝「图上的蓝不是库里的蓝」式漂移。

v3 相对 v2 的升级（重绘原则）：
  1. 统一笔画节奏 —— 主轮廓 + ≤2 条细节笔画，删掉发丝级噪点；
  2. 光学居中内建 —— 生成时采样路径包围盒，居中修正量直接写进产物
     （v2 靠外部 audit 工具补丁，v3 是生成器的一部分）；
  3. 签名元素贯穿 —— 纸片类图形一律走不等角圆角 rp()，有机图形用
     刻意不对称的贝塞尔，四角半径只取 {1.1 ... 2.2} 离散档位；
  4. 补齐缺口 —— App 图标（adaptive 三层）、并承接 v2 真机走查的修正
     （hammer 木槌造型等）。

不可偏离的绘图规范（承自 v2 规范 §3.2，未改动）：
  画布 full/inline/xs/status = 24；lg = 32；安全边 2（lg 2.5）
  描边 full 1.5 / inline 1.7 / xs 2.0 / lg 1.6 / status 1.8
  端点圆头；除语义实心点外不填充；单色 currentColor
"""

from __future__ import annotations

import json
import math
import pathlib
import re

INK = "#17403A"          # 主墨（亮色）——文档 SVG 用；Compose 侧仍走 tint
INK_DARK = "#F5F0E4"     # 米白墨（深色预览用）
EXPENSE = "#A83E33"      # 印章朱砂
INCOME = "#3E6B4E"       # 松烟墨绿

# 纸系（画布 / 预览用）
PAPER = "#F7F3E9"
SLIP = "#FDFBF6"
SUNKEN = "#EFE9DC"
SLIP_UNDER = "#EAE3D2"
RULE = "#E4DCC8"
RULE_STRONG = "#C9BFA8"
INK2 = "#55736B"
INK3 = "#7A8F87"

TAPE = [  # 胶带色板 8 色（light）
    ("青绿", "#0F766E"), ("灰蓝", "#4E7396"), ("赭黄", "#A8792C"), ("陶土", "#A85C4A"),
    ("藤紫", "#7367A0"), ("苔绿", "#5C7A4A"), ("藕粉", "#A86B85"), ("灰褐", "#7E7062"),
]

TIER = {  # 光学尺寸档位（承自规范 §3.3）
    "full":   {"grid": 24, "stroke": 1.5},
    "inline": {"grid": 24, "stroke": 1.7},
    "xs":     {"grid": 24, "stroke": 2.0},
    "status": {"grid": 24, "stroke": 1.8},
    "lg":     {"grid": 32, "stroke": 1.6},
}


def rp(x, y, w, h, r=2.0, r2=None, r3=None, r4=None):
    """不等角圆角矩形 —— 一切「纸片 / 卡片 / 器件」的轮廓。
    四角半径刻意略有差异：手绘但确定，不是 CAD 的绝对规整。"""
    r2 = r if r2 is None else r2
    r3 = r if r3 is None else r3
    r4 = r if r4 is None else r4
    return (
        f"M{x + r:.1f} {y:.1f}"
        f"h{w - r - r2:.1f}"
        f"a{r2:.1f} {r2:.1f} 0 0 1 {r2:.1f} {r2:.1f}"
        f"v{h - r2 - r3:.1f}"
        f"a{r3:.1f} {r3:.1f} 0 0 1 -{r3:.1f} {r3:.1f}"
        f"h-{w - r3 - r4:.1f}"
        f"a{r4:.1f} {r4:.1f} 0 0 1 -{r4:.1f} -{r4:.1f}"
        f"v-{h - r4 - r:.1f}"
        f"a{r:.1f} {r:.1f} 0 0 1 {r:.1f} -{r:.1f}z"
    )


def ci(cx, cy, r):
    """空心圆元素"""
    return f'<circle cx="{cx}" cy="{cy}" r="{r}"/>'


def fi(cx, cy, r):
    """实心圆元素（仅限语义必需：状态符号 / 眼睛 / 按键）"""
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="1"/>'  # fill=1 为标记，写出时替换


# ---------------------------------------------------------------------
# 路径包围盒采样（光学居中）
# ---------------------------------------------------------------------

_TOK = re.compile(r"([MmLlHhVvCcSsQqTtAaZz])|([+-]?(?:\d*\.\d+|\d+\.?)(?:[eE][+-]?\d+)?)")


def _arc_to_cubics(x1, y1, rx, ry, phi_deg, fa, fs, x2, y2):
    """SVG 椭圆弧 → 三次贝塞尔段（W3C 规范附录 B 算法）"""
    if x1 == x2 and y1 == y2:
        return []
    phi = math.radians(phi_deg)
    rx, ry = abs(rx), abs(ry)
    dx2, dy2 = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    x1p = cos_p * dx2 + sin_p * dy2
    y1p = -sin_p * dx2 + cos_p * dy2
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if fa == fs:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cos_p * cxp - sin_p * cyp + (x1 + x2) / 2.0
    cy = sin_p * cxp + cos_p * cyp + (y1 + y2) / 2.0
    def ang(ux, uy, vx, vy):
        d = math.hypot(ux, uy) * math.hypot(vx, vy)
        c = max(-1.0, min(1.0, (ux * vx + uy * vy) / d)) if d else 1.0
        a = math.acos(c)
        if ux * vy - uy * vx < 0:
            a = -a
        return a
    th1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dth > 0:
        dth -= 2 * math.pi
    elif fs and dth < 0:
        dth += 2 * math.pi
    segs = max(1, int(math.ceil(abs(dth) / (math.pi / 2))))
    delta = dth / segs
    t = 4 / 3 * math.tan(delta / 4)
    out = []
    th = th1
    px, py = x1, y1
    for _ in range(segs):
        cos1, sin1 = math.cos(th), math.sin(th)
        cos2, sin2 = math.cos(th + delta), math.sin(th + delta)
        e1x, e1y = cx + rx * cos2, cy + ry * sin2
        c1x = px + t * (-rx * sin1 * cos_p - ry * cos1 * sin_p)
        c1y = py + t * (-rx * sin1 * sin_p + ry * cos1 * cos_p)
        c2x = e1x - t * (-rx * sin2 * cos_p - ry * cos2 * sin_p)
        c2y = e1y - t * (-rx * sin2 * sin_p + ry * cos2 * cos_p)
        out.append((c1x, c1y, c2x, c2y, e1x, e1y))
        px, py = e1x, e1y
        th += delta
    return out


def _cubic(p0, p1, p2, p3, n=16):
    pts = []
    for i in range(1, n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
        y = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def sample_d(d, arc_n=12):
    """采样一条 path d 的全部坐标点（用于包围盒估算）"""
    pts = []
    toks = [(m.group(1), m.group(2)) for m in _TOK.finditer(d)]
    i = 0
    cx = cy = sx = sy = 0.0
    px = py = None  # 上一个三次/二次控制点（S/T 用）
    cmd = None
    nums = []

    def take(n):
        nonlocal i
        vals = []
        while len(vals) < n and i < len(toks):
            t_cmd, t_num = toks[i]
            if t_cmd:
                break
            vals.append(float(t_num))
            i += 1
        return vals

    while i < len(toks):
        t_cmd, t_num = toks[i]
        if t_cmd:
            cmd = t_cmd
            i += 1
            if cmd in "Zz":
                cx, cy = sx, sy
                pts.append((cx, cy))
                continue
        if cmd is None:
            i += 1
            continue
        rel = cmd.islower()
        C = cmd.upper()
        if C == "M":
            v = take(2)
            if len(v) < 2:
                break
            x, y = (cx + v[0], cy + v[1]) if rel else (v[0], v[1])
            cx, cy, sx, sy = x, y, x, y
            pts.append((x, y))
            cmd = "l" if rel else "L"
        elif C == "L":
            v = take(2)
            if len(v) < 2:
                break
            x, y = (cx + v[0], cy + v[1]) if rel else (v[0], v[1])
            cx, cy = x, y
            pts.append((x, y))
        elif C == "H":
            v = take(1)
            if not v:
                break
            cx = cx + v[0] if rel else v[0]
            pts.append((cx, cy))
        elif C == "V":
            v = take(1)
            if not v:
                break
            cy = cy + v[0] if rel else v[0]
            pts.append((cx, cy))
        elif C in "CSQTA":
            n_param = {"C": 6, "S": 4, "Q": 4, "T": 2, "A": 7}[C]
            v = take(n_param)
            if len(v) < n_param:
                break
            if C == "C":
                (x1, y1), (x2, y2), (x, y) = [(cx + v[k], cy + v[k + 1]) if rel else (v[k], v[k + 1]) for k in (0, 2, 4)]
                pts += _cubic((cx, cy), (x1, y1), (x2, y2), (x, y))
                px, py = x2, y2
                cx, cy = x, y
            elif C == "S":
                (x2, y2), (x, y) = [(cx + v[k], cy + v[k + 1]) if rel else (v[k], v[k + 1]) for k in (0, 2)]
                x1 = 2 * cx - px if px is not None else cx
                y1 = 2 * cy - py if py is not None else cy
                pts += _cubic((cx, cy), (x1, y1), (x2, y2), (x, y))
                px, py = x2, y2
                cx, cy = x, y
            elif C == "Q":
                (qx, qy), (x, y) = [(cx + v[k], cy + v[k + 1]) if rel else (v[k], v[k + 1]) for k in (0, 2)]
                c1 = (cx + 2 / 3 * (qx - cx), cy + 2 / 3 * (qy - cy))
                c2 = (x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y))
                pts += _cubic((cx, cy), c1, c2, (x, y))
                px, py = qx, qy
                cx, cy = x, y
            elif C == "T":
                x, y = (cx + v[0], cy + v[1]) if rel else (v[0], v[1])
                qx = 2 * cx - px if px is not None else cx
                qy = 2 * cy - py if py is not None else cy
                c1 = (cx + 2 / 3 * (qx - cx), cy + 2 / 3 * (qy - cy))
                c2 = (x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y))
                pts += _cubic((cx, cy), c1, c2, (x, y))
                px, py = qx, qy
                cx, cy = x, y
            elif C == "A":
                if rel:
                    x2, y2 = cx + v[5], cy + v[6]
                else:
                    x2, y2 = v[5], v[6]
                segs = _arc_to_cubics(cx, cy, v[0], v[1], v[2], v[3], v[4], x2, y2)
                for c1x, c1y, c2x, c2y, ex, ey in segs:
                    pts += _cubic((cx, cy), (c1x, c1y), (c2x, c2y), (ex, ey), n=arc_n)
                cx, cy = x2, y2
    return pts


def _elem_points(el):
    """单个元素字符串（circle 或 path d）的采样点"""
    if el.startswith("<circle"):
        cx = float(re.search(r'cx="([-\d.]+)"', el).group(1))
        cy = float(re.search(r'cy="([-\d.]+)"', el).group(1))
        r = float(re.search(r'r="([-\d.]+)"', el).group(1))
        pts = []
        for k in range(24):
            a = 2 * math.pi * k / 24
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
        return pts
    return sample_d(el)


def optical_offset(paths, grid):
    """返回 (dx, dy)：把字形包围盒中心平移到画布中心。0.1 精度，±2.5 封顶。"""
    all_pts = []
    for el in paths:
        if el.startswith("<circle"):
            all_pts += _elem_points(el)
        else:
            all_pts += sample_d(el)
    if not all_pts:
        return 0.0, 0.0
    xs = [p[0] for p in all_pts]
    ys = [p[1] for p in all_pts]
    c = grid / 2.0
    dx = round((c - (min(xs) + max(xs)) / 2) * 10) / 10
    dy = round((c - (min(ys) + max(ys)) / 2) * 10) / 10
    dx = max(-2.5, min(2.5, dx))
    dy = max(-2.5, min(2.5, dy))
    return dx, dy


# ---------------------------------------------------------------------
# 图标数据模型与 SVG 写出
# ---------------------------------------------------------------------

class Icon:
    def __init__(self, name, group, tier, paths, replaces="", replaces_type="", usage="", icon_id=None):
        self.name = name
        self.group = group
        self.tier = tier
        self.paths = paths            # d 字符串 / <circle .../> 元素混合列表
        self.replaces = replaces
        self.replaces_type = replaces_type
        self.usage = usage
        self.icon_id = icon_id
        spec = TIER[tier]
        self.grid = spec["grid"]
        self.stroke = spec["stroke"]
        self.dx, self.dy = optical_offset(paths, self.grid)

    @property
    def camel(self):
        return "".join(w.capitalize() for w in self.name.split("-"))

    def svg(self, color=INK, px=None):
        """单枚 SVG 文档字符串；px 为输出尺寸（None 则不带 width/height）"""
        g = self.grid
        size = f' width="{px}" height="{px}"' if px else ""
        out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {g} {g}" fill="none"{size}>']
        tr = ""
        if self.dx or self.dy:
            tr = f' transform="translate({self.dx} {self.dy})"'
        out.append(f'<g{tr} stroke="{color}" stroke-width="{self.stroke}" '
                   f'stroke-linecap="round" stroke-linejoin="round" fill="none">')
        for el in self.paths:
            if el.startswith("<circle"):
                if 'fill="1"' in el:
                    el = el.replace('fill="1"', f'fill="{color}" stroke="none"')
                else:
                    el = el.replace("/>", ' stroke="%s" stroke-width="%s"/>' % (color, self.stroke)) \
                        if 'stroke=' not in el else el
                out.append("  " + el)
            else:
                out.append(f'  <path d="{el}"/>')
        out.append("</g></svg>")
        return "\n".join(out)

    def manifest_entry(self):
        return {
            "name": self.name,
            "group": self.group,
            "opticalSize": self.tier,
            "viewBox": self.grid,
            "strokeWidth": self.stroke,
            "replaces": self.replaces,
            "replacesType": self.replaces_type,
            "iconId": self.icon_id,
            "centering": {"dx": self.dx, "dy": self.dy},
            "usage": self.usage,
        }
