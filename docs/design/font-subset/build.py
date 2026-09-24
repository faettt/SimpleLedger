#!/usr/bin/env python3
"""楷体子集构建脚本 —— res/font/lxgw_wenkai.ttf 的唯一生成入口。

用法：
    python3 build.py <LXGWWenKai-Regular.ttf> <输出 lxgw_wenkai.ttf>

字符覆盖（2026-09-24 字体体系 v2 · 一元楷体）：
  ● ASCII 0x20–0x7E（英文、数字、半角标点）
  ● Latin-1 0xA0–0xFF（¥ · × ° 等）
  ● 通用标点 0x2010–0x205F（– — '' "" • … ‰ 等）
  ● 数学减号 U+2212（金额「−¥」）、箭头 0x2190–0x21A3
  ● 带圈数字 ①–⑳、⚠(U+26A0) + VS15
  ● CJK 符号区 0x3000–0x303F（、。「」《》【】等）
  ● 全角形式 0xFF01–0xFF5E
  ● GB2312 全集 6763 汉字（一级 + 二级）

源字体：LXGW WenKai（霞鹜文楷）v1.522，OFL-1.1 协议
  https://github.com/lxgw/LxgwWenKai/releases （下载慢可加 https://gh-proxy.com/ 前缀）
"""
import sys


def build_charset() -> set:
    chars = set()
    chars.update(chr(c) for c in range(0x20, 0x7F))       # ASCII
    chars.update(chr(c) for c in range(0xA0, 0x100))      # Latin-1
    chars.update(chr(c) for c in range(0x2010, 0x2060))   # 通用标点
    chars.add(chr(0x2212))                                # − 金额减号
    chars.update(chr(c) for c in range(0x2190, 0x21A4))   # 箭头
    chars.update(chr(c) for c in range(0x2460, 0x2474))   # ①–⑳
    chars.update([chr(0x26A0), chr(0xFE0E)])              # ⚠ 文本呈现
    chars.update(chr(c) for c in range(0x3000, 0x3040))   # CJK 符号
    chars.update([chr(0x3005), chr(0x3007)])              # 々 〇
    chars.update(chr(c) for c in range(0xFF01, 0xFF60))   # 全角形式
    for hi in range(0xA1, 0xF8):                          # GB2312 全集
        for lo in range(0xA1, 0xFF):
            try:
                chars.update(bytes([hi, lo]).decode('gb2312'))
            except Exception:
                pass
    return {c for c in chars if ord(c) >= 0x20}


def main() -> None:
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    src, dst = sys.argv[1], sys.argv[2]
    charset_path = '/tmp/lxgw_charset.txt'
    with open(charset_path, 'w', encoding='utf-8') as f:
        f.write(''.join(sorted(build_charset())))
    from fontTools import subset
    options = subset.Options()
    options.layout_features = ['*']
    options.name_IDs = ['*']
    options.name_legacy = True
    options.name_languages = ['*']
    font = subset.load_font(src, options)
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(text=open(charset_path, encoding='utf-8').read())
    subsetter.subset(font)
    subset.save_font(font, dst, options)
    print('done:', dst)


if __name__ == '__main__':
    main()
