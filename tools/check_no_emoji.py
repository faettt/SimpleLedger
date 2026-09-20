#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验收门禁：**代码**中不得出现 emoji（注释里的 ⚠️/示例除外）。

为什么区分注释
--------------
emoji 退场的对象是「会被渲染出来的东西」——图标位、文案、读屏串。
注释里的 ⚠️ 是工程文档的警示标记，🔨 是 KDoc 里的历史示例引用，
它们永远不会出现在用户屏幕上。粗糙的全文件 grep 会把两者混在一起，
要么误报 51 处注释、要么漏掉真问题。

判据
----
· 逐行判断是否处于注释（行注释 // 或块注释 /* */ 内部），只检查**代码段**
· emoji 区段：U+1F300–1FAFF、U+2600–27BF、U+2B00–2BFF、U+FE0F（变体选择符）
· **不含** U+2190–21FF：→ 这类排版箭头是注释里的常规文字，不是图标

用法
----
    python3 tools/check_no_emoji.py
退出码 0 = 通过。
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java"

EMOJI = re.compile(r"[\U0001F300-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\uFE0F]")


def _strip_strings(line: str) -> str:
    """剥掉字符串字面量，避免字符串里的 // 或 emoji 被误判。"""
    return re.sub(r'"(?:[^"\\]|\\.)*"', '""', line)


def is_comment_only(line: str) -> bool:
    """该行是否纯注释（剥掉字符串与行注释后没有可编译内容）。"""
    s = _strip_strings(line).split("//")[0].strip()
    if not s:
        return True
    # KDoc/块注释行：以 /* 、 * 开头，或只剩 */
    return s.startswith("/*") or s.startswith("*") or s == "*/"


def main() -> int:
    problems: list[str] = []
    for p in sorted(SRC.rglob("*.kt")):
        lines = p.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if not EMOJI.search(line):
                continue
            # 注释行（含 KDoc）里的 emoji 是文档，不是渲染资产
            if is_comment_only(line):
                continue
            # 行尾注释：只看 // 之前的代码段
            code = _strip_strings(line).split("//")[0]
            if not EMOJI.search(code):
                continue
            rel = p.relative_to(SRC)
            marks = " ".join(f"U+{ord(c):04X}" for c in set(EMOJI.findall(code)))
            problems.append(f"{rel}:{i + 1}  {marks}  {line.strip()[:80]}")

    if problems:
        print(f"❌ 代码中出现 emoji（{len(problems)} 处）—— emoji 无法被 tint / 缩放 / 统一风格：")
        for x in problems:
            print("  ", x)
        return 1
    print("✅ 代码中零 emoji（注释文档除外）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
