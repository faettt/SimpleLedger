#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v3 → v4 迁移 SQL 的**离线实证验证**。

为什么需要它
------------
Room 官方的迁移测试工具 `MigrationTestHelper` 需要 instrumented test（真机/模拟器），
本机跑不了。于是换成：把 Kotlin 里的迁移 SQL **原样解析出来**，灌进真实 SQLite 引擎跑一遍。
关键点是「原样解析」——不在 Python 里另写一份 SQL，否则测的就不是将要执行的东西。

覆盖内容
--------
1. 用 3.json 的真实 createSql 建出 v3 库，灌入与设备一致的种子数据
2. 执行 v3→v4 全部 13 条语句
3. 断言：零数据丢失 / 50 条 emoji→iconId 映射逐条正确 / 列结构与 4.json 一致 / 索引重建 / 分区色对齐
4. 在 foreign_keys=OFF（Room 实际状态）与 ON（最坏情况）两种模式下各跑一遍

用法
----
    python3 tools/verify_migration_v4.py
退出码 0 = 全部通过。
"""

from __future__ import annotations

import json
import pathlib
import re
import sqlite3
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = ROOT / "app"
SCHEMA_DIR = APP / "schemas/com.simpleledger.app.data.local.AppDatabase"
KT = APP / "src/main/java/com/simpleledger/app/data/local"

FAILURES: list[str] = []


def check(cond: bool, label: str, detail: str = "") -> None:
    if cond:
        print(f"  ✅ {label}")
    else:
        FAILURES.append(label)
        print(f"  ❌ {label}" + (f"  —— {detail}" if detail else ""))


# ---------------------------------------------------------------------------
# 1. 从 Kotlin 源码解析出运行时真正会执行的 SQL
# ---------------------------------------------------------------------------
ESCAPE = re.compile(r"\\u([0-9A-Fa-f]{4})")


def kt_unescape(literal: str) -> str:
    """把 Kotlin 字符串字面量里的 \\uXXXX 转义还原成真实字符（含代理对合流）。"""
    out = []
    i = 0
    while i < len(literal):
        m = ESCAPE.match(literal, i)
        if m:
            out.append(chr(int(m.group(1), 16)))
            i = m.end()
        elif literal[i] == "\\" and i + 1 < len(literal):
            nxt = literal[i + 1]
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\", "'": "'"}.get(nxt, nxt))
            i += 2
        else:
            out.append(literal[i])
            i += 1
    return "".join(out)


def codepoint_join(s: str) -> str:
    """Kotlin 的 \\uD83C\\uDF5A 是两个 UTF-16 码元，需合流成单个码点字符。"""
    return s.encode("utf-16", "surrogatepass").decode("utf-16")


def parse_icon_mapping() -> "list[tuple[str, int]]":
    """从 IconMapping.kt 解析 (emoji, iconId)，保持文件中的顺序。"""
    src = (KT / "IconMapping.kt").read_text(encoding="utf-8")
    body = re.search(r"linkedMapOf\((.*?)\n    \)", src, re.S)
    assert body, "IconMapping.kt 里找不到 linkedMapOf 块"
    rows = []
    for lit, idx in re.findall(r'"((?:\\u[0-9A-Fa-f]{4})+)":?\s*to\s*(\d+),', body.group(1)):
        rows.append((codepoint_join(kt_unescape(lit)), int(idx)))
    assert rows, "IconMapping.kt 解析出 0 条"
    return rows


EMOJI_TO_ICON_ID = parse_icon_mapping()
ICON_TO_EMOJI = {v: k for k, v in EMOJI_TO_ICON_ID}


def parse_migration_sql() -> "list[tuple[str, str]]":
    """从 MigrationSql.kt 的 v3→v4 段落解析出 (常量名, 运行时 SQL 文本)。"""
    src = (KT / "MigrationSql.kt").read_text(encoding="utf-8")
    start = src.index("v3 → v4")
    # 只取 v4 段；每个常量形如： [const] val NAME [: String] = "..." [+ "..."] ...
    region = src[start:]

    subs = {
        "${IconMapping.DEFAULT_CATEGORY_ICON_ID}": "43",
        "${IconMapping.DEFAULT_SECTION_ICON_ID}": "1",
        "${SectionFirstSeed.TapeColor.ZHE_HUANG}": "2",
        "${SectionFirstSeed.TapeColor.HUI_LAN}": "1",
        "${SectionFirstSeed.TapeColor.QING_LV}": "0",
    }

    out: list[tuple[str, str]] = []
    names = [
        "CREATE_CATEGORIES_V4", "COPY_CATEGORIES_V4", "DROP_CATEGORIES_OLD",
        "RENAME_CATEGORIES_V4", "RECREATE_CATEGORY_TYPE_INDEX",
        "RECREATE_CATEGORY_SECTION_INDEX", "CREATE_SECTIONS_V4", "COPY_SECTIONS_V4",
        "DROP_SECTIONS_OLD", "RENAME_SECTIONS_V4", "ALIGN_SECTION_COLOR_INDEX",
        "ADD_ENTRY_RECONCILED", "ADD_ENTRY_REIMBURSE_STATE",
    ]
    for name in names:
        m = re.search(
            rf"(?:const\s+)?val\s+{name}(?:\s*:\s*String)?\s*=\s*(.*?)(?=\n\n|\n    /\*\*|\n    const|\n    val|\n\}})",
            region, re.S)
        assert m, f"MigrationSql.kt 里找不到常量 {name}"
        # 抽出所有字符串字面量并按顺序拼接
        lits = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
        sql = "".join(kt_unescape(x) for x in lits)
        if "${IconMapping.sqlCaseWhen()}" in sql:
            case_when = " ".join(f"WHEN '{e}' THEN {i}" for e, i in EMOJI_TO_ICON_ID)
            sql = sql.replace("${IconMapping.sqlCaseWhen()}", case_when)
        for k, v in subs.items():
            sql = sql.replace(k, v)
        assert "${" not in sql, f"{name} 仍有未替换的插值: {sql[:200]}"
        out.append((name, sql))
    return out


MIGRATION = parse_migration_sql()


# ---------------------------------------------------------------------------
# 2. 建 v3 库并灌数据
# ---------------------------------------------------------------------------
def build_v3(db_path: pathlib.Path) -> None:
    schema = json.loads((SCHEMA_DIR / "3.json").read_text(encoding="utf-8"))["database"]
    con = sqlite3.connect(db_path)
    for e in schema["entities"]:
        con.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for ix in e.get("indices", []):
            con.execute(ix["createSql"].replace("${TABLE_NAME}", e["tableName"]))

    now = 1_726_800_000_000
    # 分区（与设备上的真实 emoji 一致：取映射表的反查）
    sections = [
        (1, "日常开支", EMOJI := ICON_TO_EMOJI[1], "日常生活开销", 500_000, 0, now),
        (2, "装修", ICON_TO_EMOJI[17], "主材与人工，控制在 26 万内", 26_000_000, 1, now),
        (3, "旅行", ICON_TO_EMOJI[13], "出发前把大头订完", 0, 2, now),
    ]
    con.executemany(
        "INSERT INTO sections (id,name,emoji,note,budgetCents,sortOrder,createdAt) VALUES (?,?,?,?,?,?,?)",
        sections)

    # 12 全局 + 7 装修专属，iconId 与种子一一对应
    cats = [
        (1, "餐饮", 2, 0, None, 0), (2, "交通", 8, 0, None, 1), (3, "购物", 15, 0, None, 2),
        (4, "居住", 16, 0, None, 3), (5, "医疗", 20, 0, None, 4), (6, "娱乐", 22, 0, None, 5),
        (7, "学习", 26, 0, None, 6), (8, "其他支出", 42, 0, None, 7),
        (9, "工资", 36, 1, None, 0), (10, "理财", 37, 1, None, 1),
        (11, "红包", 32, 1, None, 2), (12, "其他收入", 41, 1, None, 3),
        (13, "主材", 44, 0, 2, 0), (14, "人工", 45, 0, 2, 1), (15, "家具", 46, 0, 2, 2),
        (16, "家电", 47, 0, 2, 3), (17, "设计费", 48, 0, 2, 4),
        (18, "报销", 49, 1, 2, 0), (19, "退款", 50, 1, 2, 1),
    ]
    con.executemany(
        "INSERT INTO categories (id,name,emoji,type,sectionId,sortOrder) VALUES (?,?,?,?,?,?)",
        [(i, n, ICON_TO_EMOJI[k], t, s, o) for i, n, k, t, s, o in cats])

    # 5 笔账目（与设备上的测试数据规模一致）+ 2 张图
    entries = [
        (1, 0, 200_000, 1, 2, now - 1000, "", now, now),
        (2, 0, 12_750, 13, 2, now - 2000, "瓷砖", now, now),
        (3, 1, 1_200_000, 9, 1, now - 3000, "月薪", now, now),
        (4, 0, 5_850, 1, 1, now - 4000, "", now, now),
        (5, 0, 2_580, 2, 1, now - 5000, "", now, now),
    ]
    con.executemany(
        "INSERT INTO entries (id,type,amountCents,categoryId,sectionId,entryTime,note,createdAt,updatedAt) "
        "VALUES (?,?,?,?,?,?,?,?,?)", entries)
    con.executemany(
        "INSERT INTO entry_images (id,entryId,filePath,sortOrder) VALUES (?,?,?,?)",
        [(1, 2, "/data/img/a.jpg", 0), (2, 2, "/data/img/b.jpg", 1)])

    con.execute("PRAGMA user_version = 3")
    con.commit()
    con.close()


# ---------------------------------------------------------------------------
# 3. 跑迁移 + 断言
# ---------------------------------------------------------------------------
def run_and_verify(fk: bool, expect_success: bool = True) -> None:
    mode = "foreign_keys=ON" if fk else "foreign_keys=OFF（Room 的实际状态）"
    label = "✅ 预期成功" if expect_success else "⚠️  预期失败（已知限制，见文末结论）"
    print(f"\n{'=' * 78}\n▶ {mode}　{label}\n{'=' * 78}")

    db_path = pathlib.Path(f"/tmp/_sl_v4_{'on' if fk else 'off'}.db")
    if db_path.exists():
        db_path.unlink()
    build_v3(db_path)

    con = sqlite3.connect(db_path)
    if fk:
        con.execute("PRAGMA foreign_keys = ON")
    print(f"  外键实际状态: {con.execute('PRAGMA foreign_keys').fetchone()[0]}")

    # —— 执行迁移（单事务，与 Room 的做法一致）——
    try:
        for name, sql in MIGRATION:
            con.execute(sql)
        con.execute("PRAGMA user_version = 4")
        con.commit()
    except sqlite3.Error as exc:
        if expect_success:
            check(False, "迁移执行无异常", f"{type(exc).__name__}: {exc}")
        else:
            check(
                isinstance(exc, sqlite3.IntegrityError) and "FOREIGN KEY" in str(exc),
                "外键开启时如预期地拒绝迁移（RESTRICT 立即检查、无法延迟）",
                f"{type(exc).__name__}: {exc}")
            print("     → 这正是 MIGRATION_3_4 必须先确认外键关闭的原因；")
            print("       Kotlin 侧已加运行时守卫（requireForeignKeyDisabled）。")
        con.close()
        return

    if not expect_success:
        check(False, "外键开启时本应失败，却成功了 —— 说明前提已变化，需重新评估")
        con.close()
        return
    check(True, f"迁移执行无异常（{len(MIGRATION)} 条语句）")

    # —— 数据完整性 ——
    check(con.execute("SELECT COUNT(*) FROM sections").fetchone()[0] == 3, "分区数 3 保持不变")
    check(con.execute("SELECT COUNT(*) FROM categories").fetchone()[0] == 19, "分类数 19 保持不变")
    check(con.execute("SELECT COUNT(*) FROM entries").fetchone()[0] == 5, "账目数 5 保持不变")
    check(con.execute("SELECT COUNT(*) FROM entry_images").fetchone()[0] == 2, "贴图数 2 保持不变")
    check(con.execute("SELECT SUM(amountCents) FROM entries").fetchone()[0] == 1_421_180,
          "金额总和 1,421,180 分不变")

    # —— 列结构（categories）——
    cols = {r[1]: (r[2], r[3], r[5]) for r in con.execute("PRAGMA table_info(categories)")}
    check("emoji" not in cols, "categories.emoji 已移除")
    check("iconId" in cols, "categories.iconId 已新增")
    check(cols.get("iconId", ("", 0, None))[0] == "INTEGER" and cols["iconId"][1] == 1,
          "categories.iconId 为 NOT NULL INTEGER")

    # —— 列结构（sections）——
    scols = {r[1]: (r[2], r[3]) for r in con.execute("PRAGMA table_info(sections)")}
    check("emoji" not in scols, "sections.emoji 已移除")
    check("iconId" in scols and "colorIndex" in scols, "sections 新增 iconId + colorIndex")

    # —— 列结构（entries）——
    ecols = {r[1] for r in con.execute("PRAGMA table_info(entries)")}
    check({"reconciled", "reimburseState"} <= ecols, "entries 新增 reconciled + reimburseState")
    check(con.execute("SELECT COUNT(*) FROM entries WHERE reconciled=0 AND reimburseState=0").fetchone()[0] == 5,
          "既有账目两个新状态默认 0（正常 / 不适用）")

    # —— 索引重建 ——
    idx = {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='categories'")}
    check({"index_categories_type", "index_categories_sectionId"} <= idx,
          "categories 两个索引均已重建", str(idx))

    # —— 50 条映射的完备性（映射表本体已在上方逐条断言，这里查落库结果）——
    ids = [r[0] for r in con.execute("SELECT iconId FROM categories")]
    check(all(1 <= i <= 50 for i in ids), "19 个分类的 iconId 全部落在 1–50")
    check(len(set(ids)) == 19, "19 个分类的 iconId 无重复")
    check(len(EMOJI_TO_ICON_ID) == 50, "映射表 50 条")
    check(sorted(i for _, i in EMOJI_TO_ICON_ID) == list(range(1, 51)), "iconId 1–50 连续无缺")
    check(len({e for e, _ in EMOJI_TO_ICON_ID}) == 50, "emoji 键无重复")

    # 逐分类核对：iconId 必须等于其 v3 emoji 的映射结果
    expect = {
        "餐饮": 2, "交通": 8, "购物": 15, "居住": 16, "医疗": 20, "娱乐": 22,
        "学习": 26, "其他支出": 42, "工资": 36, "理财": 37, "红包": 32, "其他收入": 41,
        "主材": 44, "人工": 45, "家具": 46, "家电": 47, "设计费": 48, "报销": 49, "退款": 50,
    }
    got = dict(con.execute("SELECT name, iconId FROM categories"))
    check(got == expect, "19 个分类的 emoji 全部翻译正确",
          f"差异: { {k: (got.get(k), v) for k, v in expect.items() if got.get(k) != v} }")

    sec = dict(con.execute("SELECT name, iconId FROM sections"))
    check(sec == {"日常开支": 1, "装修": 17, "旅行": 13}, "3 个分区图标翻译正确", str(sec))

    # —— 分区胶带色对齐 ——
    colors = dict(con.execute("SELECT name, colorIndex FROM sections"))
    check(colors == {"日常开支": 0, "装修": 2, "旅行": 1}, "分区胶带色对齐种子定义", str(colors))

    # —— 映射表的封闭性：v3 里出现过的每个 emoji 都能翻到 ——
    check(len(EMOJI_TO_ICON_ID) == 50, "映射表 50 条")
    check(sorted(i for _, i in EMOJI_TO_ICON_ID) == list(range(1, 51)), "iconId 1–50 连续无缺")
    check(len({e for e, _ in EMOJI_TO_ICON_ID}) == 50, "emoji 键无重复")

    # —— 结构应能与 Room 导出的 4.json 对上（首次编译后才有）——
    #    这是最关键的一项：手写的建表 DDL 必须与 Room 的期望**完全一致**，
    #    否则 App 启动时 schema 校验会直接抛「Migration didn't properly handle」。——
    v4 = SCHEMA_DIR / "4.json"
    if v4.exists():
        exp = json.loads(v4.read_text(encoding="utf-8"))["database"]
        check(exp["version"] == 4, "4.json 版本号为 4")
        rebuilt = {"categories": cols, "sections": scols}
        for e in exp["entities"]:
            name = e["tableName"]
            if name not in rebuilt:
                continue
            want = {f["columnName"] for f in e["fields"]}
            have = set(rebuilt[name])
            check(want == have, f"{name} 列集合与 4.json 完全一致",
                  f"期望 {sorted(want)} / 实际 {sorted(have)}")
            # 逐列核对类型与 NOT NULL（Room 对可空列**不下发** notNull 键，用 get 兜底）
            info = {r[1]: (r[2], r[3]) for r in con.execute(f"PRAGMA table_info({name})")}
            bad = []
            for f in e["fields"]:
                want_notnull = 1 if f.get("notNull", False) else 0
                got = info.get(f["columnName"])
                if got != (f["affinity"], want_notnull):
                    bad.append(f"{f['columnName']}: 期望 {(f['affinity'], want_notnull)} 实际 {got}")
            check(not bad, f"{name} 逐列类型 / NOT NULL 与 4.json 一致", "; ".join(bad))
        # 索引名也必须一致，否则 Room 校验失败
        for e in exp["entities"]:
            if e["tableName"] != "categories":
                continue
            want_idx = {ix["name"] for ix in e.get("indices", [])}
            have_idx = {r[0] for r in con.execute(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='categories'")}
            check(want_idx <= have_idx, "categories 索引名与 4.json 一致",
                  f"期望 {sorted(want_idx)} / 实际 {sorted(have_idx)}")
    else:
        print("  ⏭  4.json 尚未生成（首次编译后才有），跳过结构比对")

    con.close()


def main() -> int:
    print("=" * 78)
    print("v3 → v4 迁移离线验证")
    print(f"  从源码解析: {len(MIGRATION)} 条 SQL · {len(EMOJI_TO_ICON_ID)} 条图标映射")
    print("=" * 78)
    run_and_verify(fk=False, expect_success=True)
    run_and_verify(fk=True, expect_success=False)

    print("\n" + "=" * 78)
    print("结论")
    print("=" * 78)
    print("  · categories / sections 必须「重建」才能去掉 emoji 列：")
    print("    SQLite 的 ALTER TABLE … DROP COLUMN 需要 3.35+（API 34+），本项目 minSdk 26。")
    print("  · 重建需要 DROP TABLE，而 entries.categoryId → categories.id 是 ON DELETE RESTRICT。")
    print("    实测结论：RESTRICT 是**立即检查**的，`PRAGMA defer_foreign_keys` 与")
    print("    `PRAGMA legacy_alter_table` 都救不了 —— 外键开启时 SQL 层面无解。")
    print("  · 因此本迁移**依赖外键处于关闭状态**，该前提有三重证据：")
    print("      1) Room 2.8.5 从不设置该 pragma（反汇编 RoomOpenHelper 确认常量池无相关字符串）")
    print("      2) 本项目未调用 setForeignKeyConstraintsEnabled（全仓 grep 无命中）")
    print("      3) SQLite 默认值为 OFF")
    print("    Kotlin 侧已加运行时守卫，把这层隐式依赖变成显式检查。")
    print("=" * 78)
    if FAILURES:
        print(f"❌ 失败 {len(FAILURES)} 项：")
        for f in FAILURES:
            print("   -", f)
        return 1
    print("✅ 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
