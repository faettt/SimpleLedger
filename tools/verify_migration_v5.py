#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v4 → v5 迁移 SQL 的**离线实证验证**（多端同步身份 + 四张同步支撑表）。

为什么需要它
------------
与 verify_migration_v4.py 同一方法论：Room 的 MigrationTestHelper 需要 instrumented
test（真机/模拟器），本机跑不了。于是把 Kotlin 里的迁移 SQL **原样解析出来**，灌进真实
SQLite 引擎跑一遍——不在 Python 里另写一份 SQL，否则测的就不是将要执行的东西。
唯一的例外是 [SeedIds.seed] 的确定性派生公式（UUID.nameUUIDFromBytes / MD5）：
这里用 Python 逐字节复刻 + RFC 金标钉死（与 MigrationSqlTest 的金标同一组），
两侧任一漂移都会红。

覆盖内容
--------
1. 用 4.json 的真实 createSql 建出 v4 库，灌入种子数据 + 干扰行（同名克隆 / 改图标 / 自定义）
2. 解析并执行 v4→v5 全部 51 条语句（含 22 条种子行确定性 syncId 覆盖）
3. 断言：行数不变 / 引用关系不变 / 随机与种子 syncId 各正确且全库唯一 /
   新索引齐全（含 UNIQUE）/ 新表结构齐整 / 与 5.json（若已生成）逐列逐索引一致

用法
----
    python3 tools/verify_migration_v5.py
退出码 0 = 全部通过。
"""

from __future__ import annotations

import hashlib
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
# 0. SeedIds：UUID.nameUUIDFromBytes 的 Python 逐字节复刻（+ 金标钉死）
# ---------------------------------------------------------------------------
def name_uuid_hex32(key: str) -> str:
    """Java UUID.nameUUIDFromBytes(bytes) 的 hex32：MD5 后置 version=3 / variant=2。"""
    b = bytearray(hashlib.md5(key.encode("utf-8")).digest())
    b[6] = (b[6] & 0x0F) | 0x30
    b[8] = (b[8] & 0x3F) | 0x80
    return b.hex()


def seed_section(name: str) -> str:
    return "sd" + name_uuid_hex32("section:" + name)[:30]


def seed_category(type_: int, section_name: str | None, name: str) -> str:
    return "sd" + name_uuid_hex32(f"category:{type_}:{section_name or ''}:{name}")[:30]


# 与 MigrationSqlTest 同一组 RFC 式金标（两侧任一公式漂移都会红）
GOLDENS = {
    "section|日常开支": "sd05948a76a5233ccebe001dad42a63e",
    "section|装修": "sd1056d1fadccb3c758c73dfdd5f72c0",
    "section|旅行": "sdbe6ff5dc48ef304aaf3d3bc0e123b0",
    "category|0||餐饮": "sdef2a17de26533d868d4b39f02722ad",
    "category|0||交通": "sd71f609c0b79334f995719dbb87fa34",
    "category|0||购物": "sd07e72de901ef3592a8637e74d7456f",
    "category|0||居住": "sd09ba2516708d3ec183d459318f6674",
    "category|0||医疗": "sd15384270ffc63b8cba5f74050c9738",
    "category|0||娱乐": "sd093b209f1f8d33eebbcf2e76e9a6a1",
    "category|0||学习": "sdfdb7acf1ad0138b69dd79f7de8f35a",
    "category|0||其他支出": "sd452344bf97583dbb86b06cb474f20d",
    "category|1||工资": "sd8803f75cfb4a3a519ae3ef83c49a28",
    "category|1||理财": "sd42ec544700943cffb8fc870e5935fb",
    "category|1||红包": "sd97bda608be5b35d39c79a6c24273aa",
    "category|1||其他收入": "sd3923bb02822733a7894cd5a09e1736",
    "category|0|装修|主材": "sd262aeca685e239958765bf34ecbd2d",
    "category|0|装修|人工": "sd97c75c38ab003937adbdc493c2d00d",
    "category|0|装修|家具": "sd9a6ab9f33a303e9bb37f4423cc3717",
    "category|0|装修|家电": "sd7b1a5f8b41f135e39ddeb5ad761bd5",
    "category|0|装修|设计费": "sd60a22752e2a232119fff97056701d4",
    "category|1|装修|报销": "sd1b4aa01a333139e498a6726e08c2a3",
    "category|1|装修|退款": "sd0fcfc90aee313b4eae6004278cf089",
}


def check_goldens() -> None:
    got = {f"section|{n}": seed_section(n) for n in ("日常开支", "装修", "旅行")}
    for t, s, n in [
        (0, None, "餐饮"), (0, None, "交通"), (0, None, "购物"), (0, None, "居住"),
        (0, None, "医疗"), (0, None, "娱乐"), (0, None, "学习"), (0, None, "其他支出"),
        (1, None, "工资"), (1, None, "理财"), (1, None, "红包"), (1, None, "其他收入"),
        (0, "装修", "主材"), (0, "装修", "人工"), (0, "装修", "家具"), (0, "装修", "家电"),
        (0, "装修", "设计费"), (1, "装修", "报销"), (1, "装修", "退款"),
    ]:
        got[f"category|{t}|{s or ''}|{n}"] = seed_category(t, s, n)
    check(got == GOLDENS, "SeedIds 公式与 MigrationSqlTest 金标逐条一致（22 条）",
          str({k: (got.get(k), v) for k, v in GOLDENS.items() if got.get(k) != v}))
    check(len(GOLDENS) == 22, "金标 22 条（3 分区 + 19 分类）")


# ---------------------------------------------------------------------------
# 1. 从 Kotlin 源码解析出运行时真正会执行的 SQL
# ---------------------------------------------------------------------------
ESCAPE = re.compile(r"\\u([0-9A-Fa-f]{4})")


def kt_unescape(literal: str) -> str:
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


def parse_seed_rows() -> tuple[list[tuple[str, int]], list[tuple[str, int, int, str | None]]]:
    """从 SectionFirstSeed.kt 解析种子行 (分区: name/iconId) 与 (分类: name/iconId/type/归属分区名)。

    注意：源码里名字 / 归属可能是字面量，也可能是 `ZHUANGXIU_SECTION_NAME` 常量引用——
    两种写法都要解析（这正是「原样解析而非另写一份」的代价，值得付）。
    """
    src = (KT / "SectionFirstSeed.kt").read_text(encoding="utf-8")
    m = re.search(r'const val ZHUANGXIU_SECTION_NAME = "([^"]+)"', src)
    assert m, "SectionFirstSeed.kt 里找不到 ZHUANGXIU_SECTION_NAME"
    zx = m.group(1)

    def resolve(token: str) -> str | None:
        if token == "null":
            return None
        if token.startswith('"'):
            return token[1:-1]
        assert token == "ZHUANGXIU_SECTION_NAME", f"无法解析的标识符: {token}"
        return zx

    sections = [
        (resolve(name), int(icon))
        for name, icon in re.findall(
            r'SeedSection\((null|"[^"]+"|ZHUANGXIU_SECTION_NAME),\s*(\d+),', src)
    ]
    cats: list[tuple[str, int, int, str | None]] = []
    for name, icon, type_name, scope in re.findall(
            r'SeedCategory\((null|"[^"]+"|ZHUANGXIU_SECTION_NAME),\s*(\d+),\s*EntryType\.(\w+),\s*'
            r'(null|"[^"]+"|ZHUANGXIU_SECTION_NAME)\)',
            src):
        type_ = 0 if type_name == "EXPENSE" else 1
        cats.append((resolve(name), int(icon), type_, resolve(scope)))
    assert len(sections) == 3, f"分区种子应 3 条，实际 {len(sections)}"
    assert len(cats) == 19, f"分类种子应 19 条，实际 {len(cats)}"
    return sections, cats


SEED_SECTIONS, SEED_CATEGORIES = parse_seed_rows()

STATIC_NAMES = [
    "CREATE_TABLE_MEMBERS", "CREATE_TABLE_SYNC_OPS", "CREATE_INDEX_SYNC_OPS_OUTBOX",
    "CREATE_INDEX_SYNC_OPS_ROW", "CREATE_TABLE_SYNC_TRASH", "CREATE_TABLE_SYNC_REMOTE_FILES",
    "ADD_SECTION_SYNC_ID", "ADD_SECTION_VERSION_SEQ", "ADD_SECTION_UPDATED_AT",
    "ADD_CATEGORY_SYNC_ID", "ADD_CATEGORY_VERSION_SEQ", "ADD_CATEGORY_UPDATED_AT",
    "ADD_ENTRY_SYNC_ID", "ADD_ENTRY_VERSION_SEQ", "ADD_ENTRY_MEMBER_ID",
    "ADD_IMAGE_SYNC_ID", "ADD_IMAGE_VERSION_SEQ", "ADD_IMAGE_UPDATED_AT", "ADD_IMAGE_CONTENT_HASH",
    "BACKFILL_SECTION_SYNC_ID", "BACKFILL_CATEGORY_SYNC_ID",
    "BACKFILL_ENTRY_SYNC_ID", "BACKFILL_IMAGE_SYNC_ID",
    "CREATE_SECTION_SYNC_ID_INDEX", "CREATE_CATEGORY_SYNC_ID_INDEX",
    "CREATE_ENTRY_SYNC_ID_INDEX", "CREATE_IMAGE_SYNC_ID_INDEX",
    "CREATE_ENTRY_MEMBER_ID_INDEX", "CREATE_IMAGE_CONTENT_HASH_INDEX",
]


def parse_static_sql(region: str, name: str) -> str:
    m = re.search(
        rf"(?:const\s+)?val\s+{name}(?:\s*:\s*String)?\s*=\s*(.*?)"
        rf"(?=\n\n|\n    /\*\*|\n    const|\n    val|\n\}})",
        region, re.S)
    assert m, f"MigrationSql.kt 里找不到常量 {name}"
    lits = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    sql = "".join(kt_unescape(x) for x in lits)
    assert "${" not in sql, f"{name} 仍有未替换的插值: {sql[:200]}"
    return sql


def eval_seed_expr(expr: str) -> str:
    """求值「字符串拼接 + SeedIds.xxx(...) 调用」形态的语句表达式。"""

    def cat_repl(m: re.Match) -> str:
        type_ = int(m.group(1))
        scope = m.group(2)
        name = m.group(3)
        section_name = None if scope == "null" else scope[1:-1]
        return '"' + seed_category(type_, section_name, name) + '"'

    expr = re.sub(r'SeedIds\.category\((\d+),\s*(null|"[^"]*"),\s*"([^"]*)"\)', cat_repl, expr)
    expr = re.sub(r'SeedIds\.section\("([^"]*)"\)',
                  lambda m: '"' + seed_section(m.group(1)) + '"', expr)
    assert "SeedIds" not in expr, f"无法求值的 SeedIds 调用残留: {expr}"
    lits = re.findall(r'"((?:[^"\\]|\\.)*)"', expr)
    return "".join(kt_unescape(x) for x in lits)


def parse_v5_statements() -> "list[tuple[str, str]]":
    src = (KT / "MigrationSql.kt").read_text(encoding="utf-8")
    region = src[src.index("v4 → v5"):]
    statics = {name: parse_static_sql(region, name) for name in STATIC_NAMES}

    start = region.index("val V5_STATEMENTS")
    block = region[start:region.index("\n    )", start)]
    out: list[tuple[str, str]] = []
    for line in block.splitlines():
        m = re.match(r'\s*"([^"]+)" to (.+),\s*$', line)
        if not m:
            continue
        name, rhs = m.group(1), m.group(2).strip()
        if re.fullmatch(r"[A-Z_0-9]+", rhs):
            sql = statics[rhs]
        else:
            sql = eval_seed_expr(rhs)
        assert sql.strip() and ";" not in sql, f"{name} 为空或含分号"
        out.append((name, sql))
    assert len(out) == 51, f"V5_STATEMENTS 应 51 条，实际 {len(out)}"
    return out


MIGRATION = parse_v5_statements()


def check_seed_fix_templates() -> None:
    """种子覆盖语句必须与「SectionFirstSeed + SeedIds + MIN(rowid) 模板」逐字一致。"""
    by_name = dict(MIGRATION)
    bad = []
    for name, icon in SEED_SECTIONS:
        expect = ("UPDATE sections SET syncId = '" + seed_section(name) +
                  f"' WHERE rowid = (SELECT MIN(rowid) FROM sections WHERE name = '{name}' AND iconId = {icon})")
        if by_name.get(f"SEED_SECTION_SYNC_ID_{name}") != expect:
            bad.append(name)
    for name, icon, type_, scope in SEED_CATEGORIES:
        sid = seed_category(type_, scope, name)
        if scope is None:
            scope_sql = "sectionId IS NULL"
        else:
            scope_sql = ("sectionId IN (SELECT id FROM sections WHERE syncId = '" +
                         seed_section(scope) + "')")
        expect = ("UPDATE categories SET syncId = '" + sid +
                  f"' WHERE rowid = (SELECT MIN(rowid) FROM categories WHERE name = '{name}' " +
                  f"AND type = {type_} AND iconId = {icon} AND {scope_sql})")
        if by_name.get(f"SEED_CATEGORY_SYNC_ID_{name}") != expect:
            bad.append(name)
    check(not bad, "22 条种子覆盖语句与 SectionFirstSeed + SeedIds 模板逐字一致", str(bad))


# ---------------------------------------------------------------------------
# 2. 建 v4 库并灌数据（含干扰行：同名克隆 / 改图标 / 自定义）
# ---------------------------------------------------------------------------
NOW = 1_726_800_000_000


def build_v4(db_path: pathlib.Path) -> dict:
    schema = json.loads((SCHEMA_DIR / "4.json").read_text(encoding="utf-8"))["database"]
    con = sqlite3.connect(db_path)
    for e in schema["entities"]:
        con.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for ix in e.get("indices", []):
            con.execute(ix["createSql"].replace("${TABLE_NAME}", e["tableName"]))

    # 分区：3 个种子行（未改名未换图标）+ 3 个干扰行
    sections = [
        (1, "日常开支", 1, "日常生活开销", 500_000, 0, 0, NOW),
        (2, "装修", 17, "主材与人工，控制在 26 万内", 26_000_000, 2, 1, NOW),
        (3, "旅行", 13, "出发前把大头订完", 0, 1, 2, NOW),
        # 干扰 1：同名但换过图标 → 不该拿确定性 id
        (4, "日常开支", 5, "我改过图标", 0, 3, 3, NOW),
        # 干扰 2：用户自建分区 → 随机 id
        (5, "自定义分区", 3, "", 0, 4, 4, NOW),
        # 干扰 3：同名同图标的克隆行（MIN(rowid) 守卫：只有最早一行拿确定性 id）
        (6, "装修", 17, "克隆", 0, 2, 5, NOW),
    ]
    con.executemany(
        "INSERT INTO sections (id,name,iconId,note,budgetCents,colorIndex,sortOrder,createdAt) "
        "VALUES (?,?,?,?,?,?,?,?)", sections)

    # 分类：19 个种子行（iconId/归属与 SectionFirstSeed 一致）+ 4 个干扰行
    cats = [
        (1, "餐饮", 2, 0, None, 0), (2, "交通", 8, 0, None, 1), (3, "购物", 15, 0, None, 2),
        (4, "居住", 16, 0, None, 3), (5, "医疗", 20, 0, None, 4), (6, "娱乐", 22, 0, None, 5),
        (7, "学习", 26, 0, None, 6), (8, "其他支出", 42, 0, None, 7),
        (9, "工资", 36, 1, None, 0), (10, "理财", 37, 1, None, 1),
        (11, "红包", 32, 1, None, 2), (12, "其他收入", 41, 1, None, 3),
        (13, "主材", 44, 0, 2, 0), (14, "人工", 45, 0, 2, 1), (15, "家具", 46, 0, 2, 2),
        (16, "家电", 47, 0, 2, 3), (17, "设计费", 48, 0, 2, 4),
        (18, "报销", 49, 1, 2, 0), (19, "退款", 50, 1, 2, 1),
        # 干扰 1：全局「餐饮」克隆 → MIN(rowid) 只给 id=1
        (20, "餐饮", 2, 0, None, 8),
        # 干扰 2：用户自建分类
        (21, "自定义", 43, 0, None, 9),
        # 干扰 3：同名但换过图标 → 不命中
        (22, "餐饮", 3, 0, None, 10),
        # 干扰 4：装修「主材」克隆（挂同一分区）
        (23, "主材", 44, 0, 2, 5),
    ]
    con.executemany(
        "INSERT INTO categories (id,name,iconId,type,sectionId,sortOrder) VALUES (?,?,?,?,?,?)", cats)

    entries = [
        (1, 0, 200_000, 1, 1, NOW - 1000, "午饭", 0, 0, NOW, NOW),
        (2, 0, 12_750, 13, 2, NOW - 2000, "瓷砖", 1, 0, NOW, NOW + 500),
        (3, 1, 1_200_000, 9, 1, NOW - 3000, "月薪", 0, 2, NOW, NOW),
        (4, 0, 5_850, 1, 4, NOW - 4000, "", 0, 0, NOW, NOW),
        (5, 0, 2_580, 21, 5, NOW - 5000, "自定义分类上的账", 0, 0, NOW, NOW),
    ]
    con.executemany(
        "INSERT INTO entries (id,type,amountCents,categoryId,sectionId,entryTime,note,"
        "reconciled,reimburseState,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?)", entries)
    con.executemany(
        "INSERT INTO entry_images (id,entryId,filePath,sortOrder) VALUES (?,?,?,?)",
        [(1, 2, "/data/img/a.jpg", 0), (2, 2, "/data/img/b.jpg", 1)])

    con.execute("PRAGMA user_version = 4")
    con.commit()

    snapshot = {
        "counts": {t: con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
                   for t in ("sections", "categories", "entries", "entry_images")},
        "sum": con.execute("SELECT SUM(amountCents) FROM entries").fetchone()[0],
        "entry_refs": con.execute(
            "SELECT id, categoryId, sectionId FROM entries ORDER BY id").fetchall(),
        "image_refs": con.execute(
            "SELECT id, entryId, filePath, sortOrder FROM entry_images ORDER BY id").fetchall(),
        "entry_updated_at": dict(con.execute("SELECT id, updatedAt FROM entries")),
        "cat_refs": con.execute(
            "SELECT id, sectionId FROM categories ORDER BY id").fetchall(),
    }
    con.close()
    return snapshot


# ---------------------------------------------------------------------------
# 3. 跑迁移 + 断言
# ---------------------------------------------------------------------------
def run_and_verify() -> None:
    print(f"\n{'=' * 78}\n▶ v4 库 → v5 迁移实跑（foreign_keys=OFF，Room 的实际状态）\n{'=' * 78}")
    db_path = pathlib.Path("/tmp/_sl_v5.db")
    if db_path.exists():
        db_path.unlink()
    before = build_v4(db_path)

    con = sqlite3.connect(db_path)
    try:
        for name, sql in MIGRATION:
            con.execute(sql)
        con.execute("PRAGMA user_version = 5")
        con.commit()
    except sqlite3.Error as exc:
        check(False, "迁移执行无异常", f"{type(exc).__name__}: {exc}")
        con.close()
        return
    check(True, f"迁移执行无异常（{len(MIGRATION)} 条语句）")

    # —— 行数与金额：R-11 逐笔无损 ——
    for table, want in before["counts"].items():
        got = con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        check(got == want, f"{table} 行数 {want} 保持不变", f"实际 {got}")
    check(con.execute("SELECT SUM(amountCents) FROM entries").fetchone()[0] == before["sum"],
          "金额总和不变")
    check(con.execute("SELECT id, categoryId, sectionId FROM entries ORDER BY id").fetchall()
          == before["entry_refs"], "entries → categories/sections 引用逐笔不变（本地 Long id 原值）")
    check(con.execute(
        "SELECT id, entryId, filePath, sortOrder FROM entry_images ORDER BY id").fetchall()
        == before["image_refs"], "entry_images → entries 引用不变")
    check(con.execute("SELECT id, sectionId FROM categories ORDER BY id").fetchall()
          == before["cat_refs"], "categories → sections 归属不变（全局仍为 NULL，无 0L）")
    check(dict(con.execute("SELECT id, updatedAt FROM entries")) == before["entry_updated_at"],
          "entries.updatedAt 原值不被回填改写")
    check(dict(con.execute("SELECT id, updatedAt FROM sections"))
          == {i: NOW for i in (1, 2, 3, 4, 5, 6)}, "sections.updatedAt 回填为 createdAt")
    check(con.execute("SELECT COUNT(*) FROM entries WHERE memberId IS NOT NULL").fetchone()[0] == 0,
          "存量账目 memberId 保持 NULL（未知成员）")

    # —— syncId：形态 / 唯一 / 随机与种子各就各位 ——
    all_ids: list[str] = []
    for table in ("sections", "categories", "entries", "entry_images"):
        ids = [r[0] for r in con.execute(f"SELECT syncId FROM {table}")]
        all_ids.extend(ids)
        check(len(ids) == len(set(ids)), f"{table}.syncId 表内唯一")
        check(all(re.fullmatch(r"(?:[0-9a-f]{32}|sd[0-9a-f]{30})", i) for i in ids),
              f"{table}.syncId 形态正确（随机 32hex / 种子 sd+30hex）")
    check(len(all_ids) == len(set(all_ids)), "四表 syncId 全局无碰撞")

    sec_ids = dict(con.execute("SELECT id, syncId FROM sections"))
    for sid, (name, icon) in zip((1, 2, 3), SEED_SECTIONS):
        want = seed_section(name)
        check(sec_ids[sid] == want, f"种子分区「{name}」syncId 确定性一致", sec_ids[sid])
        check(re.fullmatch(r"sd[0-9a-f]{30}", want) is not None, f"种子分区「{name}」金标形态 sd+30hex")
    check(re.fullmatch(r"[0-9a-f]{32}", sec_ids[4]) is not None
          and sec_ids[4] != seed_section("日常开支"),
          "同名换图标行不拿确定性 id（保持随机 32hex）")
    check(re.fullmatch(r"[0-9a-f]{32}", sec_ids[5]) is not None, "自建分区为随机 32hex")
    check(re.fullmatch(r"[0-9a-f]{32}", sec_ids[6]) is not None
          and sec_ids[6] != seed_section("装修"),
          "同名克隆行只有 MIN(rowid) 一行拿确定性 id（撞唯一索引防护生效）")

    cat_ids = dict(con.execute("SELECT id, syncId FROM categories"))
    seed_cat_by_id = dict(zip(range(1, 20), SEED_CATEGORIES))
    for cid in range(1, 20):
        name, _icon, type_, scope = seed_cat_by_id[cid]
        want = seed_category(type_, scope, name)
        check(cat_ids[cid] == want, f"种子分类「{name}」syncId 确定性一致", cat_ids[cid])
    check(re.fullmatch(r"[0-9a-f]{32}", cat_ids[20]) is not None
          and cat_ids[20] != seed_category(0, None, "餐饮"),
          "全局「餐饮」克隆行保持随机")
    check(re.fullmatch(r"[0-9a-f]{32}", cat_ids[21]) is not None, "自建分类为随机 32hex")
    check(cat_ids[22] != seed_category(0, None, "餐饮"), "换图标的「餐饮」不拿确定性 id")
    check(cat_ids[23] != seed_category(0, "装修", "主材"), "装修「主材」克隆行保持随机")

    # —— 新索引：名字 / 位置 / UNIQUE 与 Room 契约一致 ——
    def index_cols(table: str, index: str) -> list[str]:
        return [r[2] for r in con.execute(f"PRAGMA index_info(`{index}`)")]

    def index_unique(table: str, index: str) -> bool:
        for r in con.execute(f"PRAGMA index_list(`{table}`)"):
            if r[1] == index:
                return bool(r[2])
        return False

    expect_idx = [
        ("sections", "index_sections_syncId", ["syncId"], True),
        ("categories", "index_categories_syncId", ["syncId"], True),
        ("entries", "index_entries_syncId", ["syncId"], True),
        ("entry_images", "index_entry_images_syncId", ["syncId"], True),
        ("entries", "index_entries_memberId", ["memberId"], False),
        ("entry_images", "index_entry_images_contentHash", ["contentHash"], False),
        ("sync_ops", "index_sync_ops_outbox", ["uploaded", "createdAt"], False),
        ("sync_ops", "index_sync_ops_row", ["rowKind", "rowSyncId"], False),
    ]
    for table, name, cols, unique in expect_idx:
        have_cols = index_cols(table, name)
        check(have_cols == cols and index_unique(table, name) == unique,
              f"索引 {name}({', '.join(cols)}) 存在且 UNIQUE={unique}", f"实际 {have_cols}")

    # —— 新表：结构 + 冒烟写读 ——
    def columns(table: str) -> dict:
        return {r[1]: (r[2], r[3]) for r in con.execute(f"PRAGMA table_info({table})")}

    check(set(columns("members")) == {"syncId", "name", "hidden", "createdAt", "updatedAt", "versionSeq"},
          "members 列集合齐整", str(columns("members")))
    check(set(columns("sync_ops")) == {"opId", "rowKind", "rowSyncId", "opType", "actorId",
                                       "memberId", "seq", "baseSeq", "payload", "origin",
                                       "applied", "uploaded", "chunkName", "createdAt"},
          "sync_ops 列集合齐整（含 U-3 的可空 baseSeq）", str(columns("sync_ops")))
    check(set(columns("sync_trash")) == {"deleteOpId", "rowKind", "rowSyncId", "kind", "snapshot",
                                         "deletedAt", "deletedByMemberId", "conflict",
                                         "conflictActorId", "resolved"},
          "sync_trash 列集合齐整（含 U-3 的 kind 来源字段）", str(columns("sync_trash")))
    check(set(columns("sync_remote_files")) == {"remoteName", "kind", "etag", "size",
                                                "downloadedAt", "uploadedAt"},
          "sync_remote_files 列集合齐整", str(columns("sync_remote_files")))

    con.execute("INSERT INTO members (syncId, name, hidden, createdAt, updatedAt, versionSeq) "
                "VALUES ('sd05948a76a5233ccebe001dad42a63e', '妈妈', 0, 1, 1, 0)")
    con.execute("INSERT INTO sync_ops (opId, rowKind, rowSyncId, opType, actorId, memberId, seq, "
                "baseSeq, payload, origin, applied, uploaded, chunkName, createdAt) "
                "VALUES ('op-1', 'ENTRY', 'sd05948a76a5233ccebe001dad42a63e', 'UPSERT', 'dev-1', "
                "NULL, 1, NULL, '{}', 'LOCAL', 1, 0, NULL, 1)")
    con.execute("INSERT INTO sync_trash (deleteOpId, rowKind, rowSyncId, kind, snapshot, deletedAt, "
                "deletedByMemberId, conflict, conflictActorId, resolved) "
                "VALUES ('op-2', 'ENTRY', 'x', 'OVERWRITE', '{}', 1, NULL, 1, 'dev-2', 0)")
    con.execute("INSERT INTO sync_remote_files (remoteName, kind, etag, size, downloadedAt, uploadedAt) "
                "VALUES ('abc.op', 'OP_CHUNK', 'W/\"1\"', 10, 0, 1)")
    con.commit()
    check(con.execute("SELECT COUNT(*) FROM members").fetchone()[0] == 1
          and con.execute("SELECT COUNT(*) FROM sync_ops").fetchone()[0] == 1
          and con.execute("SELECT COUNT(*) FROM sync_trash").fetchone()[0] == 1
          and con.execute("SELECT COUNT(*) FROM sync_remote_files").fetchone()[0] == 1,
          "四张新表冒烟写读正常")

    # —— 与 Room 导出的 5.json 对账（编译产物实证 schema 校验兼容）——
    v5 = SCHEMA_DIR / "5.json"
    if v5.exists():
        exp = json.loads(v5.read_text(encoding="utf-8"))["database"]
        check(exp["version"] == 5, "5.json 版本号为 5")
        tables = {"sections", "categories", "entries", "entry_images",
                  "members", "sync_ops", "sync_trash", "sync_remote_files"}
        check({e["tableName"] for e in exp["entities"]} == tables, "5.json 实体集合齐整")
        for e in exp["entities"]:
            name = e["tableName"]
            want = {f["columnName"]: (f["affinity"], 1 if f.get("notNull", False) else 0)
                    for f in e["fields"]}
            got = columns(name)
            check(set(want) == set(got), f"{name} 列集合与 5.json 一致",
                  f"期望 {sorted(want)} / 实际 {sorted(got)}")
            bad = [f"{c}: 期望 {want[c]} 实际 {got[c]}" for c in want
                   if c in got and want[c] != got[c]]
            check(not bad, f"{name} 逐列类型 / NOT NULL 与 5.json 一致", "; ".join(bad))
            want_idx = {(ix["name"], tuple(ix["columnNames"]), bool(ix["unique"]))
                        for ix in e.get("indices", [])}
            have_idx = {(r[1], tuple(index_cols(name, r[1])), bool(r[2]))
                        for r in con.execute(f"PRAGMA index_list(`{name}`)")}
            check(want_idx <= have_idx, f"{name} 索引名 / 列序 / UNIQUE 与 5.json 一致",
                  f"期望 {sorted(want_idx)} / 实际 {sorted(have_idx)}")
    else:
        print("  ⏭  5.json 尚未生成（首次编译后才有），跳过结构比对")

    con.close()


def main() -> int:
    print("=" * 78)
    print("v4 → v5 迁移离线验证（多端同步身份 + 同步支撑表）")
    print(f"  从源码解析: {len(MIGRATION)} 条 SQL · 种子覆盖 {len(SEED_SECTIONS) + len(SEED_CATEGORIES)} 条")
    print("=" * 78)
    check_goldens()
    check_seed_fix_templates()
    run_and_verify()

    print("\n" + "=" * 78)
    print("结论")
    print("=" * 78)
    print("  · v4→v5 全程零 DROP、零重建表：只 ADD COLUMN / UPDATE / CREATE TABLE(新表) / CREATE INDEX。")
    print("    entries→categories/sections 的本地 Long 引用原值不动 ⇒ 迁移前后逐笔无损（R-11）。")
    print("  · syncId 两阶段回填：全体随机 32hex → 种子行覆盖确定性 id（SeedIds，与 seed() 共用）；")
    print("    MIN(rowid) 守卫保证同名克隆行不重复占确定性 id，唯一索引可建。")
    print("  · 种子 syncId 金标 22 条 = Kotlin(UUID.nameUUIDFromBytes) ↔ Python(MD5 复刻) 双向钉死。")
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
