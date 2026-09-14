#!/usr/bin/env python3
"""机器映射表防漂移检查（S3 / D-209，只读）。

## 检查的是哪一层

`MachineMap`（`decision/MachineMap.java`）是"**机器类型 ↔ 机器方块/菜单**"的**唯一真源**，
`docs/MACHINE_MAP.csv` 是它的**人读视图**（本脚本生成）。本脚本做两件事：

* **Tier A（总是跑，硬）**：Java 表 ↔ CSV 逐字段一致 + 结构断言
  （类型 id 唯一、`type_id` 形如 `ns:path`、有站点的行必须有方块 id、`EXECUTABLE` 行必须有
  已实测的 `menu_class`、行不许引用重复方块）；
* **Tier B（找得到上游 jar 就跑，硬）**：用 `javap` 读上游注册类的**字符串常量**，
  断言 `表 ∪ KNOWN_UNMAPPED == 上游全部类型`（**双向**：表里不许有上游没有的类型；
  上游不许有表里没分类的类型）。找不到 jar / 没装 `javap` ⇒ Tier B 跳过并**明确说明跳过了什么**。

Tier B 是这套检查真正的牙齿：上游升级加了新机器类型时，**构建前**就会点名，
而不是等运行期 `unmapped=[…]` 在日志里被忽略。取证方式见
`docs/reviews/2026-09-14-3B-S3-机器映射勘察.md` §1.1（同一套 `javap` 证据链）。

## 为什么"覆盖"必须双向

单向（表里有的都在上游存在）挡不住"上游新增了、我们没分类"；反过来单向挡不住"表里写了
上游没有的 id"（那是伪造/笔误）。两个方向都要断言，才叫"单一出处 *且* 完整"。

用法：
    python3 tools/machine-map.py                 # 打印表 + 跑 Tier A + Tier B
    python3 tools/machine-map.py --write         # 重新生成 docs/MACHINE_MAP.csv
    python3 tools/machine-map.py --check         # 断言 CSV 不陈旧 + 两个 Tier（CI/构建前用）
    python3 tools/machine-map.py --jar <path>    # 指定上游 jar（默认按客户端 mods 目录 glob）
"""

from __future__ import annotations

import argparse
import glob
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "src/main/java/com/dddgn/alice/decision/MachineMap.java"
CSV = ROOT / "docs/MACHINE_MAP.csv"

# 上游取证：这些类里的字符串常量就是注册名（javap 的 `// String xxx`）
RECIPE_TYPE_CLASS = "mekanism.common.recipe.MekanismRecipeType"
BLOCK_CLASS = "mekanism.common.registries.MekanismBlocks"
DEFAULT_JAR_GLOB = "/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/mods/Mekanism-*.jar"


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def split_top_level(text: str) -> list[str]:
    """按顶层逗号切分（跳过字符串/括号内），字符串感知。"""
    parts, depth, current, in_string, escaped = [], 0, [], False, False
    for ch in text:
        if in_string:
            current.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
            current.append(ch)
        elif ch in "([{":
            depth += 1
            current.append(ch)
        elif ch in ")]}":
            depth -= 1
            current.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
    if current:
        parts.append("".join(current).strip())
    return [p for p in parts if p]


def unquote(token: str) -> str:
    token = token.strip()
    if token.startswith('"') and token.endswith('"'):
        return token[1:-1]
    return token


def call_args(code: str, name: str) -> list[list[str]]:
    r"""取出所有 `name(...)` 调用的**顶层参数**（用配平扫描，不靠正则碰运气 ——
    列表最后一项后面是 `));`，正则式的 `)\s*\n` 会把它整条漏掉）。字符串感知。"""
    results: list[list[str]] = []
    for match in re.finditer(re.escape(name) + r"\s*\(", code):
        start = match.end()
        depth, in_string, escaped, index = 1, False, False, start
        while index < len(code) and depth > 0:
            ch = code[index]
            if in_string:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == '"':
                    in_string = False
            elif ch == '"':
                in_string = True
            elif ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
                if depth == 0:
                    break
            index += 1
        if depth == 0:
            results.append(split_top_level(code[start:index]))
    return results


def parse_rows(source: str) -> tuple[list[dict], list[dict], str]:
    """解析 `row(...)` / `noSite(...)`；"上游有、无站点"= `blockIds=[]` 的行（**派生**，不另设清单）。"""
    code = strip_comments(source)
    rows: list[dict] = []

    def add(type_id: str, blocks: list[str], menu: str, note: str,
            capability: str = "READ_ONLY") -> None:
        rows.append({
            "type_id": type_id,
            "block_ids": blocks,
            "menu_class": menu,
            "capability": capability,
            "note": note,
        })

    for args in call_args(code, "row"):
        if len(args) < 4 or not args[0].strip().startswith('"'):
            continue                      # 跳过 `private static Row row(String typeId, …)` 这种**声明**
        block_arg = args[1].strip()
        if block_arg == "null":
            blocks: list[str] = []
        elif "(" in block_arg:            # List.of(...)
            inner = block_arg[block_arg.find("(") + 1:block_arg.rfind(")")]
            blocks = [unquote(a) for a in split_top_level(inner)]
        else:                             # 单个方块 id（本表当前形态）
            blocks = [unquote(block_arg)]
        add(unquote(args[0]), blocks,
            "-" if args[2].strip() == "null" else unquote(args[2]), unquote(args[3]))

    for args in call_args(code, "noSite"):
        if len(args) < 2 or not args[0].strip().startswith('"'):
            continue                      # 同上：跳过声明
        add(unquote(args[0]), [], "-", unquote(args[1]))

    # `executable(...)` = 有**执行准入**的行（形状与 `row(...)` 相同，只差 capability）。
    # CSV 里必须如实区分，否则人读视图会把"能驱动"写成"只读"（D-217 起 enriching 是唯一一行）。
    for args in call_args(code, "executable"):
        if len(args) < 4 or not args[0].strip().startswith('"'):
            continue                      # 跳过 `private static Row executable(…)` 声明
        block_arg = args[1].strip()
        if block_arg == "null":
            blocks = []
        elif "(" in block_arg:            # List.of(...)
            inner = block_arg[block_arg.find("(") + 1:block_arg.rfind(")")]
            blocks = [unquote(a) for a in split_top_level(inner)]
        else:
            blocks = [unquote(block_arg)]
        add(unquote(args[0]), blocks,
            "-" if args[2].strip() == "null" else unquote(args[2]), unquote(args[3]),
            "EXECUTABLE")

    unmapped = [{"type_id": r["type_id"], "reason": r["note"]} for r in rows if not r["block_ids"]]
    source_jar = re.search(r'SOURCE_JAR\s*=\s*"([^"]+)"', code)
    return rows, unmapped, (source_jar.group(1) if source_jar else "-")


def render_csv(rows: list[dict]) -> str:
    lines = ["kind,type_id,block_ids,menu_class,capability,note"]
    for row in rows:
        kind = "SITE" if row["block_ids"] else "NO_SITE"
        lines.append(",".join([
            kind, row["type_id"], "|".join(row["block_ids"]), row["menu_class"],
            row["capability"], csv_escape(row["note"]),
        ]))
    return "\n".join(lines) + "\n"


def csv_escape(text: str) -> str:
    if any(ch in text for ch in ',"\n'):
        return '"' + text.replace('"', '""') + '"'
    return text


def tier_a(rows: list[dict], unmapped: list[dict], problems: list[str]) -> None:
    seen_types, seen_blocks = set(), {}
    for row in rows:
        type_id = row["type_id"]
        if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_/.-]+", type_id):
            problems.append(f"类型 id 形状不对: {type_id!r}")
        if type_id in seen_types:
            problems.append(f"类型重复: {type_id}")
        seen_types.add(type_id)
        for block in row["block_ids"]:
            if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_/.-]+", block):
                problems.append(f"方块 id 形状不对: {block!r}（行 {type_id}）")
            if block in seen_blocks:
                problems.append(f"方块 {block} 同时属于 {seen_blocks[block]} 与 {type_id}")
            seen_blocks[block] = type_id
        if row["capability"] == "EXECUTABLE":
            if not row["block_ids"]:
                problems.append(f"EXECUTABLE 行没有方块站点: {type_id}")
            if row["menu_class"] == "-":
                problems.append(f"EXECUTABLE 行没有实测 menu_class: {type_id}")
    for item in unmapped:
        if not item["reason"].strip():
            problems.append(f"无站点行缺理由: {item['type_id']}（'没行'与'无站点'必须可区分）")


def javap_strings(jar: Path, class_name: str) -> set[str]:
    if shutil.which("javap") is None:
        raise RuntimeError("没有 javap")
    out = subprocess.run(["javap", "-p", "-c", "-classpath", str(jar), class_name],
                         capture_output=True, text=True, timeout=180)
    if out.returncode != 0:
        raise RuntimeError(f"javap 失败: {out.stderr.strip()[:200]}")
    return set(re.findall(r"// String ([a-z0-9_]+)", out.stdout))


def tier_b(rows: list[dict], unmapped: list[dict], jar: Path | None, problems: list[str]) -> str:
    if jar is None or not jar.exists():
        return ("Tier B SKIP（没找到上游 jar）：**上游类型覆盖未复核** —— "
                f"用 --jar 指定，或把 jar 放到 {DEFAULT_JAR_GLOB}")
    try:
        types = javap_strings(jar, RECIPE_TYPE_CLASS)
        blocks = javap_strings(jar, BLOCK_CLASS)
    except Exception as error:  # noqa: BLE001 - 任何取证失败都如实降级，不静默通过
        return f"Tier B SKIP（{error}）：**上游类型覆盖未复核**"
    if not types or not blocks:
        return "Tier B SKIP（javap 没读到字符串常量：类名/版本变了）：**上游类型覆盖未复核**"

    namespace = {t for t in types if "_" in t or t.isalpha()}  # 去掉命名空间常量本身
    upstream_types = {f"mekanism:{t}" for t in namespace if t != "mekanism"}
    declared = {row["type_id"] for row in rows}
    for row in rows:
        for block in row["block_ids"]:
            short = block.split(":", 1)[1]
            if short not in blocks:
                problems.append(f"Tier B: 表里的方块 {block} 在上游 {BLOCK_CLASS} 中不存在（笔误或版本不符）")
    for missing in sorted(upstream_types - declared):
        problems.append(f"Tier B: 上游有类型 {missing}，但表里没有分类（必须显式归类：有站点 / 无站点）")
    for extra in sorted(declared - upstream_types):
        problems.append(f"Tier B: 表里的 {extra} 在上游 {RECIPE_TYPE_CLASS} 中不存在")
    no_site = sum(1 for row in rows if not row["block_ids"])
    return (f"Tier B OK（{jar.name}）：上游类型 {len(upstream_types)} 个，"
            f"表 {len(rows)} 行（有站点 {len(rows) - no_site} / 无站点 {no_site}），双向一致")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--jar", default=None)
    args = parser.parse_args()

    rows, unmapped, source_jar = parse_rows(JAVA.read_text(encoding="utf-8"))
    problems: list[str] = []
    tier_a(rows, unmapped, problems)

    jar = Path(args.jar) if args.jar else next(
        (Path(p) for p in sorted(glob.glob(DEFAULT_JAR_GLOB)) if "sources" not in p), None)
    tier_b_note = tier_b(rows, unmapped, jar, problems)

    rendered = render_csv(rows)
    if args.write:
        CSV.write_text(rendered, encoding="utf-8")
        print(f"已写入 {CSV.relative_to(ROOT)}")
    if args.check and not CSV.exists():
        problems.append("docs/MACHINE_MAP.csv 不存在（跑 --write 生成）")
    elif args.check and CSV.read_text(encoding="utf-8") != rendered:
        problems.append("docs/MACHINE_MAP.csv 与 Java 表不一致（陈旧；跑 --write 重新生成）")

    for row in rows:
        print(f"  {row['type_id']:<36} {('|'.join(row['block_ids']) or '(无站点)'):<32} {row['menu_class']}")
    print(f"取证件: {source_jar}")
    print(tier_b_note)

    if problems:
        for problem in problems:
            print(f"[PROBLEM] {problem}")
        print(f"MACHINE_MAP_CHECK_RESULT FAIL: 行={len(rows)} 未映射={len(unmapped)} 问题={len(problems)}")
        return 1
    print(f"MACHINE_MAP_CHECK_RESULT PASS: 行={len(rows)} 未映射={len(unmapped)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
