#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 docs/design/journal-style-preview.html —— 手账风格设计预览（自包含单文件）。
把 72 枚 SVG 内联进 HTML，便于逐枚审查线条质量。
"""
from __future__ import annotations
import json
import pathlib
import re

D = pathlib.Path(__file__).resolve().parent
ICONS = D / "icons"
MAN = json.loads((ICONS / "manifest.json").read_text(encoding="utf-8"))


def svg_src(name: str) -> str:
    s = (ICONS / f"{name}.svg").read_text(encoding="utf-8")
    s = re.sub(r'\s*(width|height)="\d+"', "", s, count=2)
    return s


def icon(name, box=26, cls=""):
    return f'<span class="ic {cls}" style="--s:{box}px">{svg_src(name)}</span>'


GROUP_TITLE = {
    "category": ("A", "分类 / 分区图标", "50 枚 · 存为 iconId（1–50），是用户能挑的那一整套。原来这 50 个位置全是彩色 emoji。"),
    "nav": ("B", "底部导航", "4 枚 · 原为 🗂️📒📊👤，现在装进「索引贴」形态"),
    "function": ("C", "功能图标", "9 枚 · 现有 Material 几何线条图标全部手工重绘（不是加粗描边，是重新画路径）"),
    "inline": ("D", "行内标记", "4 枚 · 使用尺寸只有 12–18dp，必须减笔画 + 描边加粗，否则糊成一团灰"),
    "xs": ("E", "极小对勾", "1 枚 · MineScreen 里 11sp 纯白、18dp 方块内，单笔画 + 描边 2.0"),
    "illustration": ("F", "空态插图", "2 枚 · 32 网格，装在 68dp 圆角方块里，细节可以画足"),
    "status": ("G", "状态符号", "2 枚 · 报销维度的 ○ / ●（核对维度复用 function/check）"),
}


def icon_group(key):
    items = [i for i in MAN["icons"] if i["group"] == key]
    num, title, desc = GROUP_TITLE[key]
    cards = []
    for it in items:
        src = it["replaces"]
        is_mat = it["replacesType"] == "material"
        tag = f'<code class="mat">{src}</code>' if is_mat else f'<span class="emo">{src}</span>'
        extra = f'<em>iconId {it["iconId"]}</em>' if it["iconId"] else f'<em>{it["opticalSize"]}</em>'
        cards.append(
            f'<figure class="card">'
            f'{icon(it["name"], 30 if it["group"] == "illustration" else 26)}'
            f'<figcaption><b>{it["name"]}</b>{tag}<small>{it["usage"]}</small>{extra}</figcaption>'
            f'</figure>')
    return (f'<section class="grp"><header><span class="badge">{num}</span>'
            f'<h3>{title}</h3><p>{desc}</p></header>'
            f'<div class="grid">{"".join(cards)}</div></section>')


TAPE = [
    ("青绿", "#0F766E", "4.95"), ("灰蓝", "#4E7396", "4.49"), ("赭黄", "#A8792C", "3.48"),
    ("陶土", "#A85C4A", "3.62"), ("藤紫", "#7367A0", "4.21"), ("苔绿", "#5C7A4A", "4.36"),
    ("藕粉", "#A86B85", "3.72"), ("灰褐", "#7E7062", "3.88"),
]
tape_cells = "".join(
    f'<div class="tape"><i style="background:{hexv}"></i><b>{nm}</b><code>{hexv}</code><small>{cr}:1</small></div>'
    for nm, hexv, cr in TAPE)

INK_ROWS = [
    ("主墨 ink", "#17403A", "10.35 / 11.07", "正文 · 标题 · 金额 · 图标", "ok"),
    ("次要墨 ink2", "#55736B", "4.67 / 4.99", "时间 · 分区 · 元信息。**这是能用的最浅一档**", "ok"),
    ("装饰墨 ink3", "#7A8F87", "3.10", "仅 ≥18px 装饰文字，不得承载需阅读的元信息", "warn"),
]
ink_rows = "".join(
    f'<tr><td><span class="sw" style="background:{h}"></span>{n}</td><td><code>{h}</code></td>'
    f'<td class="{"good" if ok == "ok" else "warncell"}">{c}</td><td class="use">{u}</td></tr>'
    for n, h, c, u, ok in INK_ROWS)

SEMANTIC = [
    ("支出 expense", "#A83E33", "5.52", "印章朱砂"),
    ("收入 income", "#3E6B4E", "5.54", "松烟墨绿"),
    ("预警 warn", "#92570A", "5.29", "预算超支"),
    ("信息 info", "#2B6CB0", "4.89", "提示"),
]
sem_cells = "".join(
    f'<div class="tape"><i style="background:{h}"></i><b>{n}</b><code>{h}</code><small>{cr}:1 · {u}</small></div>'
    for n, h, cr, u in SEMANTIC)

# 索引贴导航示意
def nav_preview(active=1):
    labels = [("nav-section", "分区"), ("nav-ledger", "明细"), ("nav-stats", "统计"), ("nav-mine", "我的")]
    tabs = []
    for i, (nm, lb) in enumerate(labels):
        sel = " sel" if i == active else ""
        tabs.append(f'<div class="tab{sel}">{icon(nm, 22)}<span>{lb}</span></div>')
    return f'<div class="navprev">{"".join(tabs)}</div>'


def row_preview(sec_color, sec_label, slots, cat, amount, tint=""):
    sym = ""
    for s in slots:
        sym += icon(s, 14, "sym") if s else '<span class="sym empty"></span>'
    return (f'<div class="lrow" style="{tint}">'
            f'<i class="bar" style="background:{sec_color}"></i>'
            f'<span class="slots">{sym}</span>'
            f'<span class="cat">{cat}</span>'
            f'<span class="amt">{amount}</span></div>')


ROWS = (
    row_preview("#A8792C", "装修", [None, "status-pending"], "主材", "−3,200.00") +
    row_preview("#A8792C", "装修", ["check", "status-cleared"], "人工", "−8,000.00") +
    row_preview("#A8792C", "装修", [None, "status-cleared"], "家具", "−12,600.00") +
    row_preview("#0F766E", "日常开支", ["check", None], "餐饮", "−127.50") +
    row_preview("#4E7396", "旅行", [None, None], "机票", "−1,860.00")
)

HTML = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>简账 SimpleLedger · 手账风格设计规范</title>
<style>
:root{{
  --paper:#F7F3E9; --slip:#FDFBF6; --under:#EAE3D2; --sunken:#EFE9DC;
  --rule:#E4DCC8; --ruleS:#C9BFA8;
  --ink:#17403A; --ink2:#55736B; --ink3:#7A8F87;
  --exp:#A83E33; --inc:#3E6B4E;
  --kai:"LXGW WenKai","LXGW WenKai Lite","Noto Serif CJK SC",serif;
}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--paper);color:var(--ink);
  font:15px/1.75 -apple-system,"Noto Sans CJK SC","PingFang SC",system-ui,sans-serif;
  -webkit-font-smoothing:antialiased}}
.wrap{{max-width:1080px;margin:0 auto;padding:56px 28px 96px}}
h1{{font-family:var(--kai);font-size:38px;margin:0 0 6px;font-weight:400;letter-spacing:.01em}}
.sub{{color:var(--ink2);font-size:14px;margin:0 0 28px}}
h2{{font-family:var(--kai);font-size:25px;font-weight:400;margin:60px 0 4px;
  padding-bottom:9px;border-bottom:2px solid var(--ink);position:relative}}
h2::after{{content:"";position:absolute;left:0;right:14%;bottom:-5px;height:1px;background:var(--ruleS)}}
h2 em{{font-style:normal;font-size:12.5px;color:var(--ink3);margin-left:10px;
  font-family:system-ui;letter-spacing:.06em}}
h3{{font-size:16px;margin:0;font-weight:600}}
p{{margin:10px 0}}
code{{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;
  background:var(--sunken);padding:1.5px 5px;border-radius:3px;color:var(--ink)}}
.lede{{color:var(--ink2);font-size:14px;margin:12px 0 22px;max-width:76ch}}

.slip{{background:var(--slip);border:.5px solid var(--ruleS);border-radius:3px;padding:20px}}
.cols{{display:grid;gap:12px}}
.c2{{grid-template-columns:repeat(2,minmax(0,1fr))}}
.c3{{grid-template-columns:repeat(3,minmax(0,1fr))}}
.c4{{grid-template-columns:repeat(4,minmax(0,1fr))}}
.pillars .slip h3{{font-family:var(--kai);font-weight:400;font-size:19px;margin-bottom:8px}}
.pillars .slip p{{font-size:13.5px;color:var(--ink2);margin:0}}

.rules{{list-style:none;padding:0;margin:16px 0 0}}
.rules li{{position:relative;padding:8px 0 8px 30px;font-size:14px;
  border-bottom:.5px dashed var(--rule)}}
.rules li:last-child{{border-bottom:none}}
.rules li::before{{content:"";position:absolute;left:6px;top:17px;width:9px;height:9px;
  border:1.5px solid var(--exp);border-radius:50%}}

.ic{{display:inline-flex;align-items:center;justify-content:center;
  width:var(--s);height:var(--s);flex:0 0 auto}}
.ic svg{{width:100%;height:100%;display:block}}

.tapegrid{{display:grid;grid-template-columns:repeat(8,minmax(0,1fr));gap:9px;margin-top:6px}}
.tape{{text-align:center}}
.tape i{{display:block;height:44px;border-radius:3px;border:.5px solid rgba(23,64,58,.16)}}
.tape b{{display:block;font-size:12.5px;font-weight:600;margin-top:6px}}
.tape code{{background:none;padding:0;font-size:11px;color:var(--ink2)}}
.tape small{{display:block;font-size:10.5px;color:var(--ink3);margin-top:2px}}

table{{width:100%;border-collapse:collapse;font-size:13.5px;margin-top:8px}}
th,td{{text-align:left;padding:9px 10px;border-bottom:.5px solid var(--rule)}}
th{{font-size:12px;color:var(--ink2);font-weight:600;letter-spacing:.02em}}
td.use{{color:var(--ink2);font-size:12.5px}}
td.good{{color:var(--inc);font-weight:600}}
td.warncell{{color:var(--exp);font-weight:600}}
.sw{{display:inline-block;width:13px;height:13px;border-radius:3px;
  border:.5px solid rgba(23,64,58,.25);vertical-align:-2px;margin-right:7px}}

.grp{{margin-top:26px}}
.grp>header{{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap;margin-bottom:12px}}
.grp>header p{{margin:0;font-size:12.5px;color:var(--ink2);flex:1 1 100%}}
.badge{{display:inline-flex;align-items:center;justify-content:center;
  width:21px;height:21px;border-radius:3px;background:var(--ink);color:var(--paper);
  font-size:11.5px;font-weight:600}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(126px,1fr));gap:9px}}
.card{{margin:0;background:var(--slip);border:.5px solid var(--ruleS);border-radius:3px;
  padding:14px 8px 11px;text-align:center;position:relative}}
.card figcaption{{margin-top:9px}}
.card b{{display:block;font-size:11.5px;font-weight:600;word-break:break-all}}
.card .emo{{display:inline-block;font-size:15px;line-height:1;margin-top:5px;
  opacity:.42;text-decoration:line-through;text-decoration-color:var(--exp)}}
.card .mat{{display:block;font-size:10px;background:none;padding:0;margin-top:5px;
  color:var(--ink3);word-break:break-all}}
.card small{{display:block;font-size:10.5px;color:var(--ink2);margin-top:5px;line-height:1.45}}
.card em{{display:block;font-style:normal;font-size:10px;color:var(--ink3);margin-top:4px}}

.navprev{{display:flex;gap:4px;align-items:flex-end;background:var(--slip);
  border:.5px solid var(--ruleS);border-radius:3px;padding:14px 14px 0}}
.tab{{flex:1;height:56px;display:flex;flex-direction:column;align-items:center;
  justify-content:center;gap:3px;background:var(--paper);border:.5px solid var(--ruleS);
  border-bottom:none;border-radius:7px 7px 0 0;color:var(--ink2);font-size:11px}}
.tab.sel{{height:68px;background:#E4EFEC;border-top:2px solid #0F766E;color:var(--ink)}}

.listprev{{background:var(--slip);border:.5px solid var(--ruleS);border-radius:3px;
  padding:6px 14px}}
.lrow{{display:flex;align-items:center;height:62px;border-bottom:.5px solid var(--rule);gap:0}}
.lrow:last-child{{border-bottom:none}}
.bar{{width:3px;align-self:stretch;flex:0 0 auto}}
.slots{{display:flex;gap:2px;margin-left:10px;flex:0 0 auto}}
.sym{{width:14px;height:14px;display:inline-flex}}
.sym.empty{{display:inline-block}}
.cat{{flex:1;min-width:0;margin-left:7px;font-size:15px}}
.amt{{font-variant-numeric:tabular-nums;font-size:15px;color:var(--exp)}}

.progbars{{display:flex;flex-direction:column;gap:14px}}
.pb label{{display:flex;justify-content:space-between;font-size:12.5px;
  color:var(--ink2);margin-bottom:5px}}
.pb .track{{height:6px;border-radius:1px;background:var(--sunken);
  border:.5px solid var(--ruleS);overflow:hidden}}
.pb .fill{{height:100%}}

.chips{{display:flex;gap:7px;flex-wrap:wrap;margin-top:6px}}
.chip{{font-size:12.5px;padding:5px 13px;border-radius:6px;border:.5px solid var(--ruleS);
  color:var(--ink2)}}
.chip.on{{background:var(--ink);color:var(--paper);border-color:var(--ink)}}
.btn{{display:inline-block;font-size:13.5px;padding:10px 22px;border-radius:4px;
  background:var(--ink);color:var(--paper);font-weight:600}}
.input{{display:block;border-radius:4px;border:.5px solid var(--ruleS);background:var(--sunken);
  padding:11px 13px;font-size:13.5px;color:var(--ink3)}}

.hand{{font-family:var(--kai);font-weight:400}}
.cmp{{display:grid;grid-template-columns:1fr 1fr;gap:0;border:.5px solid var(--ruleS);
  border-radius:3px;overflow:hidden}}
.cmp>div{{padding:15px 17px}}
.cmp>div:first-child{{background:var(--paper);border-right:.5px solid var(--ruleS)}}
.cmp h4{{margin:0 0 8px;font-size:11.5px;color:var(--ink3);font-weight:600;letter-spacing:.04em}}
.cmp .t1{{font-size:22px}}
.cmp .t2{{font-size:15px;margin-top:5px}}
.numdemo{{font-variant-numeric:tabular-nums}}
.numkai{{font-family:var(--kai)}}

.foot{{margin-top:70px;padding-top:22px;border-top:.5px solid var(--ruleS);
  font-size:12.5px;color:var(--ink3)}}
@media(max-width:860px){{.c3,.c4,.cmp{{grid-template-columns:1fr}}.tapegrid{{grid-template-columns:repeat(4,1fr)}}}}
</style></head><body><div class="wrap">

<h1>手账风格设计规范</h1>
<p class="sub">简账 SimpleLedger · v2.0 · 2026-09-20 · 取代「温和人文派」1.0.0</p>

<p class="lede">深度定档 <b>皮肤派</b>：布局骨架与信息架构<b>一个像素都不动</b>，手账感全部由「纸质层 + 手写体 + 手绘线条」三件事承担。所有颜色都在纸底 <code>#F7F3E9</code> 上实算过对比度。</p>

<h2>01 · 风格定义 <em>STYLE DEFINITION</em></h2>
<div class="cols c3 pillars">
  <div class="slip"><h3>纸质层</h3><p>暖纸底 + 极弱纸纹（≤4% 不透明度）。纹样<b>不承担文字对齐职责</b>——线与字刻意不齐，这样 2.0× 字号无障碍不受影响。</p></div>
  <div class="slip"><h3>手写体</h3><p>装饰位内嵌「霞鹜文楷」子集，信息位系统无衬线。楷体只用 Regular，且<b>绝不用于金额</b>。</p></div>
  <div class="slip"><h3>手绘线条</h3><p>纸片 + 0.5dp 描边替代阴影；72 枚图标全部 1.5dp 圆头手绘线稿。手工感写在<b>路径几何</b>里，不在运行时抖动。</p></div>
</div>
<ul class="rules">
  <li>所有金额强制 <code>tabular-nums</code> 等宽数字。楷体数字是比例宽度，会破坏流水列表的纵向对齐</li>
  <li>纸纹不透明度 ≤ 4%，且绝不压在正文行内做基线对齐（会锁死行高，与 2.0× 字号直接冲突）</li>
  <li>同一行内颜色只有一个含义：<b>分区</b>。分类图标与状态符号一律不用分区色，否则出现两套色彩语义</li>
  <li>手绘感来自路径本身的不对称，<b>绝不运行时抖动</b>——滚动会闪、不同 DPI 会糊</li>
  <li>触控目标 ≥ 48dp。索引贴未选中签视觉 42dp，但触控区向外扩到 48dp</li>
  <li>支出 / 收入除颜色外必须带 <b>−</b> / <b>+</b> 符号，色盲可辨</li>
</ul>

<h2>02 · 色彩与纸张 <em>COLOR &amp; PAPER</em></h2>
<p class="lede">暗色主题是同一套「纸与墨」逻辑的换向——纸变深、墨变浅，手绘线条逻辑完全一致。实测：米白墨 <code>#F5F0E4</code> 在深炭纸 <code>#1C1A16</code> 上是 <b>15.35:1</b>。</p>

<div class="cols c2" style="margin-bottom:20px">
  <div class="slip">
    <h3 style="margin-bottom:12px">纸与墨（浅色）</h3>
    <table>
      <tr><th>色阶</th><th>值</th><th>纸底对比</th><th>用途</th></tr>
      {ink_rows}
    </table>
  </div>
  <div class="slip">
    <h3 style="margin-bottom:12px">纸面层次（无阴影，靠纸叠纸）</h3>
    <div style="position:relative;margin-bottom:20px">
      <div style="background:var(--paper);border:.5px solid var(--ruleS);border-radius:3px;padding:15px;font-size:12.5px;color:var(--ink2)">页面纸 <code>#F7F3E9</code></div>
      <div style="position:absolute;left:12px;right:12px;top:34px;bottom:-9px;background:var(--under);border-radius:3px;z-index:-1"></div>
      <div style="background:var(--slip);border:.5px solid var(--ruleS);border-radius:3px;padding:15px;margin:12px 12px 0;font-size:12.5px;color:var(--ink2)">纸片 <code>#FDFBF6</code> + 背后错位 3dp 的垫纸 <code>#EAE3D2</code></div>
    </div>
    <table>
      <tr><th>角色</th><th>浅色</th><th>深色</th></tr>
      <tr><td>页面纸</td><td><code>#F7F3E9</code></td><td><code>#1C1A16</code></td></tr>
      <tr><td>纸片</td><td><code>#FDFBF6</code></td><td><code>#262320</code></td></tr>
      <tr><td>垫纸</td><td><code>#EAE3D2</code></td><td><code>#131210</code></td></tr>
      <tr><td>次级填充</td><td><code>#EFE9DC</code></td><td><code>#2F2B26</code></td></tr>
      <tr><td>分隔线</td><td><code>#E4DCC8</code></td><td><code>#3A352D</code></td></tr>
      <tr><td>纸片描边</td><td><code>#C9BFA8</code></td><td><code>#4A4438</code></td></tr>
    </table>
  </div>
</div>

<div class="slip">
  <h3 style="margin-bottom:12px">语义色 · 印章朱砂 / 松烟墨绿 <span style="font-size:12px;font-weight:400;color:var(--ink2)">（色相不变，守住红涨绿跌；只降一档饱和度，让它从「UI 色」变成「墨色」）</span></h3>
  <div class="tapegrid">{sem_cells}</div>
  <p style="font-size:12.5px;color:var(--ink2);margin:14px 0 0">原 <code>#B84742</code> 换到新纸色后只剩 4.68 / 4.60，余量不到 0.2——纸色再暖一档就跌破 4.5。朱砂 / 松烟是 5.52 / 5.54，余量充足。</p>
</div>

<div class="slip" style="margin-top:14px">
  <h3 style="margin-bottom:12px">和纸胶带色板 · 8 色 <span style="font-size:12px;font-weight:400;color:var(--ink2)">（每分区一卷专属色，用户自选；同时充当按分区的图表配色）</span></h3>
  <div class="tapegrid">{tape_cells}</div>
  <p style="font-size:12.5px;color:var(--ink2);margin:14px 0 0">全部 ≥3:1（图形元素阈值），但<b>不作为承载文字的底色</b>。分区色与图表色从此是同一套——不会再出现「图表里的绿不是分区的绿」。</p>
</div>

<h2>03 · 纸张纹理 <em>PAPER TEXTURE</em></h2>
<div class="cols c2">
  <div class="slip" style="padding:0;overflow:hidden">
    <div style="height:186px;background:var(--paper);position:relative">
      <div style="position:absolute;inset:0;background:repeating-linear-gradient(to bottom,transparent 0 27px,var(--rule) 27px 28px);opacity:.55"></div>
      <div style="position:relative;padding:18px">
        <div class="hand" style="font-size:19px">9月20日　周六</div>
        <div style="height:2px;background:var(--ink);width:52%;margin-top:3px"></div>
        <div style="display:flex;justify-content:space-between;font-size:12.5px;margin-top:14px"><span>○ 工资</span><span style="color:var(--inc)">+12,000.00</span></div>
        <div style="display:flex;justify-content:space-between;font-size:12.5px;margin-top:12px"><span>● 餐饮</span><span style="color:var(--exp)">−127.50</span></div>
        <div style="display:flex;justify-content:space-between;font-size:12.5px;margin-top:12px"><span>○ 交通</span><span style="color:var(--exp)">−25.80</span></div>
      </div>
    </div>
    <div style="padding:14px 20px 18px;font-size:12.5px;color:var(--ink2)">
      <b>横线纹（默认）</b>　间距 28dp · 线宽 1dp · ≤4% 不透明度 —— 注意线与字<b>故意不齐</b>
    </div>
  </div>
  <div class="slip">
    <h3 style="margin-bottom:10px">为什么不做「横线真对齐」</h3>
    <p style="font-size:13.5px;color:var(--ink2)">让字正好落在横线上需要<b>锁死行高</b>。你的验收项里有 <b>2.0× 字号</b>——字号一放大，文字就会顶破行线，或被迫溢出滚动。</p>
    <p style="font-size:13.5px;color:var(--ink2)">所以本方案把纹样降级为<b>纯质感</b>：它只负责“这是一张纸”，不参与排版。代价是手账感少一点，收益是无障碍与任意字号缩放全部保住。</p>
    <div style="margin-top:14px;border:.5px solid var(--ruleS);border-radius:3px;overflow:hidden">
      <div style="background:var(--paper);padding:12px 14px;border-bottom:.5px solid var(--ruleS)">
        <div style="font-size:11.5px;color:var(--ink3);margin-bottom:7px">点阵纹（可选）　间距 14dp · 点径 1.4dp</div>
        <div style="height:54px;background-image:radial-gradient(var(--ruleS) 1px,transparent 1px);background-size:14px 14px"></div>
      </div>
    </div>
  </div>
</div>

<h2>04 · 字体 <em>TYPOGRAPHY</em></h2>
<p class="lede">Android 没有中文手写体，所以内嵌「霞鹜文楷」（OFL 开源）的<b>常用字子集</b>，约 300–800 KB，<b>只用于装饰位</b>。信息位留在系统无衬线。楷体只用 Regular——楷体加粗会失去书写感，需要强调时改字号或墨色深浅。</p>
<div class="cmp">
  <div>
    <h4>装饰位 · 霞鹜文楷（本页标题即用此字体）</h4>
    <div class="hand t1">分区　明细　统计　我的</div>
    <div class="hand t2">9月20日　周六　·　第 3 页 / 共 12 页</div>
    <div class="hand t2" style="color:var(--ink2);margin-top:10px">这一页还没记满，留着空行。</div>
  </div>
  <div>
    <h4>信息位 · 系统无衬线 + 等宽数字</h4>
    <div class="t1 numdemo">+12,000.00　−2,211.80</div>
    <div class="t2" style="color:var(--ink2)">08:38　日常开支　预算 5,000 · 已用 44%</div>
    <div style="margin-top:12px;border-top:.5px dashed var(--rule);padding-top:12px">
      <div style="font-size:11.5px;color:var(--ink3);margin-bottom:6px">楷体数字的问题（比例宽度）</div>
      <div class="numkai" style="font-size:14px">999.00<br>1,111.00</div>
      <div class="numdemo" style="font-size:14px;margin-top:6px;color:var(--inc)">999.00<br>1,111.00</div>
    </div>
  </div>
</div>

<h2>05 · 形状与组件 <em>SHAPE &amp; COMPONENTS</em></h2>
<p class="lede">圆角整体从「柔和」转向「纸张」：卡片 3 / 进度条 1 / chip 6 / 输入与按钮 4 / 对话框 8。取消 999 胶囊与 20、28 大圆角。信息密度<b>保持不变</b>——列表行仍 62dp，不增加阅读压力。</p>
<div class="cols c2">
  <div class="slip">
    <h3 style="margin-bottom:12px">底部导航 · 索引贴</h3>
    {nav_preview(1)}
    <p style="font-size:12.5px;color:var(--ink3);margin:12px 0 0">未选中签视觉 42dp，选中凸出到 54dp（<b>选中态不止变色，还有形变</b>）。未选中签触控区外扩到 48dp 以满足无障碍——底栏总高因此比原版高约 8dp。</p>
  </div>
  <div class="slip">
    <h3 style="margin-bottom:12px">其他组件</h3>
    <div class="chips">
      <span class="chip on">全部分区</span><span class="chip">日常开支</span><span class="chip">装修</span>
    </div>
    <div class="progbars" style="margin-top:16px">
      <div class="pb"><label><span>日常开支</span><span>2,212.30 / 5,000</span></label>
        <div class="track"><div class="fill" style="width:44%;background:#0F766E"></div></div></div>
      <div class="pb"><label><span>装修 · 26 万红线</span><span>2,000.00 / 260,000</span></label>
        <div class="track"><div class="fill" style="width:1%;background:#A8792C"></div></div></div>
    </div>
    <div style="margin-top:16px;display:flex;gap:10px;align-items:center;flex-wrap:wrap">
      <span class="btn">记一笔</span>
      <span class="input" style="flex:1;min-width:150px">备注…</span>
    </div>
    <p style="font-size:12.5px;color:var(--ink3);margin:12px 0 0">进度条填充使用<b>该分区的胶带色</b>；超支时整条改朱砂色，作为装修 26 万红线的可视锚点。</p>
  </div>
</div>

<div class="slip" style="margin-top:14px">
  <h3 style="margin-bottom:12px">账目行构成 <span style="font-size:12px;font-weight:400;color:var(--ink2)">（D4 选了双维度状态字段，所以有两个符号槽）</span></h3>
  <div class="listprev">{ROWS}</div>
  <p style="font-size:12.5px;color:var(--ink2);margin:12px 0 0">
    <b>分区名不再作为文字前缀出现</b>（原来是「装修 · 主材」）——分区身份已由左侧 3dp 色条承担。这一步刚好抵消双符号槽吃掉的 40dp，可行宽反而更宽裕。仅「全部分区」视图补回文字前缀防同名歧义。
  </p>
  <div style="display:flex;gap:26px;margin-top:14px;flex-wrap:wrap;font-size:12.5px;color:var(--ink2)">
    <span>{icon("status-pending", 15)} <code>status-pending</code>　待报销（朱砂）</span>
    <span>{icon("status-cleared", 15)} <code>status-cleared</code>　已报销（松烟）</span>
    <span>{icon("check", 15)} <code>check</code>　已核对（墨青）</span>
    <span><span style="display:inline-block;width:14px;height:14px;vertical-align:-2px"></span> 槽位空 = 默认</span>
  </div>
</div>

<h2>06 · 页面骨架 <em>SCREEN FRAMES</em></h2>
<p class="lede">布局骨架与原版一致，只换质感。下面四屏是骨干：分区首屏（默认落点）、明细页、统计页（手绘图表）、我的页。</p>
<div class="cols c4" style="gap:14px">
  <div class="slip" style="padding:14px">
    <div style="font-size:11.5px;color:var(--ink3);margin-bottom:9px">分区首屏</div>
    <div class="hand" style="font-size:17px">分区</div>
    <div style="height:2px;background:var(--ink);width:44%;margin-top:3px"></div>
    <div style="height:1px;background:var(--ruleS);width:70%;margin:3px 0 12px"></div>
    <div style="background:var(--slip);border:.5px solid var(--ruleS);border-left:4px solid #0F766E;border-radius:3px;padding:10px;margin-bottom:9px">
      <div style="font-size:12.5px;font-weight:600">日常开支</div>
      <div style="font-size:11px;color:var(--ink2)">2,212.30 / 5,000</div>
      <div style="height:5px;border:.5px solid var(--ruleS);border-radius:1px;margin-top:5px;position:relative"><div style="position:absolute;inset:0;right:56%;background:#0F766E"></div></div>
    </div>
    <div style="background:var(--slip);border:.5px solid var(--ruleS);border-left:4px solid #A8792C;border-radius:3px;padding:10px">
      <div style="font-size:12.5px;font-weight:600">装修</div>
      <div style="font-size:11px;color:var(--ink2)">2,000.00 / 260,000</div>
      <div style="height:5px;border:.5px solid var(--ruleS);border-radius:1px;margin-top:5px;position:relative"><div style="position:absolute;left:0;top:0;bottom:0;width:2%;background:#A8792C"></div></div>
    </div>
    <div style="font-size:11px;color:var(--ink3);margin-top:10px">纸片化：去阴影、近直角，色条即身份</div>
  </div>

  <div class="slip" style="padding:14px">
    <div style="font-size:11.5px;color:var(--ink3);margin-bottom:9px">明细页</div>
    <div style="display:flex;justify-content:space-between;align-items:baseline">
      <span class="hand" style="font-size:17px">明细</span><span style="font-size:11px;color:var(--ink2)">9月20日</span></div>
    <div style="height:2px;background:var(--ink);width:44%;margin-top:3px"></div>
    <div style="height:1px;background:var(--ruleS);width:70%;margin:3px 0 10px"></div>
    <div style="display:flex;gap:5px;margin-bottom:11px;flex-wrap:wrap">
      <span class="chip on" style="font-size:10.5px;padding:3px 8px">全部</span>
      <span class="chip" style="font-size:10.5px;padding:3px 8px">待报销</span>
      <span class="chip" style="font-size:10.5px;padding:3px 8px">装修</span>
    </div>
    <div style="font-size:12px;display:flex;justify-content:space-between;padding:5px 0;border-top:1px solid var(--ink)">
      <span>工资</span><span style="color:var(--inc);font-variant-numeric:tabular-nums">+12,000.00</span></div>
    <div style="font-size:12px;display:flex;justify-content:space-between;padding:5px 0">
      <span>餐饮</span><span style="color:var(--exp);font-variant-numeric:tabular-nums">−127.50</span></div>
    <div style="font-size:12px;display:flex;justify-content:space-between;padding:5px 0">
      <span>居住</span><span style="color:var(--exp);font-variant-numeric:tabular-nums">−2,000.00</span></div>
    <div style="font-size:11px;color:var(--ink3);margin-top:10px">楷体只用在页眉与日期</div>
  </div>

  <div class="slip" style="padding:14px">
    <div style="font-size:11.5px;color:var(--ink3);margin-bottom:9px">统计页（手绘图表）</div>
    <div class="hand" style="font-size:17px">统计</div>
    <div style="height:2px;background:var(--ink);width:44%;margin-top:3px"></div>
    <div style="height:1px;background:var(--ruleS);width:70%;margin:3px 0 12px"></div>
    <div style="display:flex;align-items:flex-end;gap:7px;height:78px;border-bottom:1.5px solid var(--ink);padding-bottom:0">
      <div style="flex:1;height:64%;background:#0F766E;border-radius:2px 3px 0 0"></div>
      <div style="flex:1;height:92%;background:#A8792C;border-radius:3px 2px 0 0"></div>
      <div style="flex:1;height:38%;background:#4E7396;border-radius:2px 3px 0 0"></div>
      <div style="flex:1;height:56%;background:#A85C4A;border-radius:3px 2px 0 0"></div>
    </div>
    <div style="font-size:10.5px;color:var(--ink2);display:flex;gap:7px;margin-top:5px">
      <span style="flex:1;text-align:center">日常</span><span style="flex:1;text-align:center">装修</span>
      <span style="flex:1;text-align:center">旅行</span><span style="flex:1;text-align:center">其他</span></div>
    <div style="font-size:11px;color:var(--ink3);margin-top:10px">马克笔涂条（手绘），长度信息不失真；<br>占比数值直接标在图上，小扇区设最小可视角度</div>
  </div>

  <div class="slip" style="padding:14px">
    <div style="font-size:11.5px;color:var(--ink3);margin-bottom:9px">我的页</div>
    <div class="hand" style="font-size:17px">我的</div>
    <div style="height:2px;background:var(--ink);width:44%;margin-top:3px"></div>
    <div style="height:1px;background:var(--ruleS);width:70%;margin:3px 0 12px"></div>
    <div style="background:var(--slip);border:.5px solid var(--ruleS);border-radius:3px;padding:11px">
      <div style="display:flex;align-items:center;gap:9px">
        <span style="width:18px;height:18px;background:#E4EFEC;border-radius:3px;display:inline-flex;align-items:center;justify-content:center">{icon("check-xs", 13)}</span>
        <span style="font-size:12.5px">外观 · 跟随系统</span>
      </div>
    </div>
    <div style="font-size:12px;color:var(--ink2);display:flex;flex-direction:column;gap:8px;margin-top:12px">
      <span>全局分类管理</span><span>数据导出 / 备份</span><span>隐私模式</span><span>应用锁</span>
    </div>
    <div style="font-size:11px;color:var(--ink3);margin-top:10px">11sp 那个极小对勾就是 E 组的 <code>check-xs</code></div>
  </div>
</div>

<h2>07 · 图标全表 <em>ICON SET · 72 ICONS</em></h2>
<p class="lede">全部手绘重绘，<b>一枚都不是 emoji 剪影描边</b>。画布 24dp（插图 32dp）、描边 1.5dp、圆头端点、只描边不填充、单色墨青。手工感来自不等角半径与刻意的不对称——<b>几何里就有手绘感，运行时绝不抖动</b>。</p>
<p class="lede" style="color:var(--ink3);font-size:13px">划掉的彩色字符是每枚要替换掉的原图标。生成器：<code>icons/_generate.py</code>，映射表：<code>icons/manifest.json</code>。</p>

{"".join(icon_group(k) for k in ["category", "nav", "function", "inline", "xs", "illustration", "status"])}

<h2>08 · 迁移清单 <em>MIGRATION</em></h2>
<div class="cols c2">
  <div class="slip">
    <h3 style="margin-bottom:10px">数据库 v3 → v4</h3>
    <table>
      <tr><th>表</th><th>变更</th></tr>
      <tr><td><code>sections</code></td><td>新增 <code>colorIndex INTEGER NOT NULL DEFAULT 0</code>（0–7 → 胶带色板）</td></tr>
      <tr><td><code>categories</code></td><td>新增 <code>iconId INTEGER NOT NULL DEFAULT 43</code>；<b>DROP emoji 列</b></td></tr>
      <tr><td><code>entries</code></td><td>新增 <code>reconciled INTEGER NOT NULL DEFAULT 0</code>、<code>reimburseState INTEGER NOT NULL DEFAULT 0</code></td></tr>
    </table>
    <p style="font-size:12.5px;color:var(--ink2);margin-top:12px">迁移前会先扫一遍现有 emoji 字符串，按 50 条映射表翻成 <code>iconId</code>；未命中落到 43（tag）。<b>用户挑 emoji 只能从 42 格候选网格点选、没有自由输入</b>，所以 emoji 是封闭集合，迁移不会丢数据。</p>
  </div>
  <div class="slip">
    <h3 style="margin-bottom:10px">代码改动点（emoji 全面退场）</h3>
    <table>
      <tr><th>位置</th><th>替换为</th></tr>
      <tr><td><code>SectionFirstSeed</code> / <code>MigrationSql</code></td><td>种子与迁移改用 <code>iconId</code> 写入</td></tr>
      <tr><td><code>EmojiChoices.kt</code>（42 项）</td><td>删除，改为 50 枚分类图标选择网格</td></tr>
      <tr><td><code>AppRoot.kt</code> 导航 4 个 emoji</td><td>nav-section / nav-ledger / nav-stats / nav-mine</td></tr>
      <tr><td><code>EntryEditForm.kt</code> label 前缀</td><td>calendar-inline / clock-inline</td></tr>
      <tr><td><code>LedgerRows.kt</code> 的 💬 / 📷</td><td>note-inline / camera-inline</td></tr>
      <tr><td><code>Common.kt</code> 空态的 🗒️</td><td>empty-lg</td></tr>
      <tr><td><code>LedgerDetailPane.kt</code> 的 👈</td><td>hint-lg</td></tr>
      <tr><td><code>MineScreen.kt</code> 的 ✓</td><td>check-xs</td></tr>
      <tr><td>Material 图标 9 枚（24 处）</td><td>本套手绘同名图标</td></tr>
    </table>
  </div>
</div>

<p class="foot">生成自 <code>docs/design/icons/manifest.json</code> · 令牌见 <code>tokens-journal.json</code> · 规范正文见 <code>journal-style-spec-2026-09-20.md</code></p>
</div></body></html>
"""

(D / "journal-style-preview.html").write_text(HTML, encoding="utf-8")
print(f"✓ journal-style-preview.html  ({len(HTML) / 1024:.0f} KB)")
print(f"  内联图标 {len(MAN['icons'])} 枚")
