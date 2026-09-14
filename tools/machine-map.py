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
  上游不许有表里没分类的类型）。**按命名空间分别跑**（`UPSTREAMS`）：拿 Mekanism 的类去核 `thermal:` 的行必然全错。
  找不到 jar / 没装 `javap` ⇒ Tier B 跳过并**明确说明跳过了什么**。
  ⚠️ `UPSTREAMS[...]["jarjar"]`：有的模组把内容藏在**内嵌 jar（JiJ）**里（Thermal 的 `thermal_core`，
  见 `docs/THERMAL_S1_FACTS.md` §0）。`mods/*.jar` 逐个 `unzip` 看不到内嵌 jar ⇒ 必须显式解出来放进
  `javap` 的 classpath，否则 Tier B 会得出"上游没有这些类型"的**假结论**。

Tier B 是这套检查真正的牙齿：上游升级加了新机器类型时，**构建前**就会点名，
而不是等运行期 `unmapped=[…]` 在日志里被忽略。取证方式见
`docs/reviews/2026-09-14-3B-S3-机器映射勘察.md` §1.1（同一套 `javap` 证据链）。

## 为什么"覆盖"必须双向

单向（表里有的都在上游存在）挡不住"上游新增了、我们没分类"；反过来单向挡不住"表里写了
上游没有的 id"（那是伪造/笔误）。两个方向都要断言，才叫"单一出处 *且* 完整"。
**多模组后还要加一维**：每个已收录的命名空间都要**全量**（表里出现 `thermal:` 的行，就要求 thermal 全量，
不允许"只登记 13 行、剩下 19 个类型没人管"——那正是这个闸门存在的理由）。

用法：
    python3 tools/machine-map.py                 # 打印表 + 跑 Tier A + Tier B（每个命名空间各一条结论）
    python3 tools/machine-map.py --write         # 重新生成 docs/MACHINE_MAP.csv
    python3 tools/machine-map.py --check         # 断言 CSV 不陈旧 + 两个 Tier（CI/构建前用）
    python3 tools/machine-map.py --jar <path>    # 覆盖**第一个**声明的上游 jar（缺省按客户端 mods 目录 glob）
    python3 tools/machine-map.py --mods-dir <dir>  # 换客户端 mods 目录（默认写死在 DEFAULT_MODS_DIR）
"""

from __future__ import annotations

import argparse
import fnmatch
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "src/main/java/com/dddgn/alice/decision/MachineMap.java"
CSV = ROOT / "docs/MACHINE_MAP.csv"

# 上游取证：这些类里的字符串常量就是注册名（javap 的 `// String xxx`）。
#
# **每个模组一条**：表是"上游全部类型"的单一出处，覆盖必须**按命名空间分别双向断言** ——
# 拿 Mekanism 的类去核 `thermal:` 的行必然全错，反过来也一样。
#
# ⚠️ `jarjar` 那一栏是 2026-09-14 踩过的坑：Thermal 的 11 个 `device_*` 方块、32 个类型里的
# device/fuel 那些，全在 `thermal_foundation` 的**内嵌 jar** `META-INF/jarjar/thermal_core-*.jar` 里。
# `mods/*.jar` 逐个 `unzip` **看不到内嵌 jar**（只显示为一行）⇒ 必须显式解出来放进 `javap` 的 classpath，
# 否则 Tier B 会得出"上游没有这些类型"的**假结论**（S1 事实表 §0 记了这次误判）。
UPSTREAMS = {
    "mekanism": {
        "jars": ["Mekanism-*.jar"],
        "jarjar": [],
        "type_classes": ["mekanism.common.recipe.MekanismRecipeType"],
        "block_classes": ["mekanism.common.registries.MekanismBlocks"],
    },
    "thermal": {
        "jars": ["thermal_expansion-*.jar", "thermal_foundation-*.jar", "cofh_core-*.jar"],
        "jarjar": [("thermal_foundation-*.jar", "META-INF/jarjar/thermal_core-*.jar")],
        "type_classes": ["cofh.thermal.core.init.registries.TCoreRecipeTypes"],
        "block_classes": ["cofh.thermal.expansion.init.registries.TExpBlocks",
                          "cofh.thermal.core.init.registries.TCoreBlocks"],
    },
}

DEFAULT_MODS_DIR = "/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/mods"


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


STRING_CONCAT = re.compile(r'^\s*"(?:[^"\\]|\\.)*"(?:\s*\+\s*"(?:[^"\\]|\\.)*")*\s*$')


def unquote(token: str) -> str:
    """取出 # 字面量的值：**单字面量，或 `"a" + "b"` 这种拼接**。

    拼接必须在这里合掉：`split_top_level` 只按顶层逗号切分，`"a"\n + "b"` 会整段留在参数里，
    而"首尾都是引号"的朴素判断对它不成立 ⇒ 会把 **Java 源码片段**（含换行与 `+`）写进 CSV
    （实测踩到过：`mekanism:enriching` 的备注在 CSV 里断成两行、还带一个 `+`）。
    """
    token = token.strip()
    if STRING_CONCAT.match(token):
        return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', token)).replace('\\"', '"')
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
    # 取证件：常量可能是跨行字符串拼接（`"a" + "b"`）⇒ 按拼接逐段取回，不截断
    sources: list[str] = []
    for match in re.finditer(r'(SOURCE_JAR\w*)\s*=\s*((?:"[^"]*"\s*\+?\s*)+)', code):
        value = "".join(re.findall(r'"([^"]*)"', match.group(2)))
        sources.append(f"{match.group(1)}={value}")
    return rows, unmapped, sources


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


def find_jar(mods_dir: Path, pattern: str) -> Path | None:
    return next((Path(p) for p in sorted(glob.glob(str(mods_dir / pattern))) if "sources" not in p), None)


def extract_jarjar(outer: Path, pattern: str, into: Path) -> Path | None:
    """把外层 jar 里的**内嵌 jar** 解出来 —— `javap` 的 classpath 不能指向"jar 里的 jar"。

    没有这一步，Thermal 的 11 个 `device_*` 方块与 12 个 device/fuel 类型会被 Tier B 判成"上游没有"（假结论）。
    """
    import zipfile
    with zipfile.ZipFile(outer) as archive:
        names = sorted(n for n in archive.namelist() if fnmatch.fnmatch(n, pattern))
        if not names:
            return None
        target = into / Path(names[-1]).name
        target.write_bytes(archive.read(names[-1]))
        return target


def javap_strings(classpath: list[Path], class_name: str) -> set[str]:
    if shutil.which("javap") is None:
        raise RuntimeError("没有 javap")
    out = subprocess.run(
        ["javap", "-p", "-c", "-classpath", os.pathsep.join(str(p) for p in classpath), class_name],
        capture_output=True, text=True, timeout=180)
    if out.returncode != 0:
        raise RuntimeError(f"javap 失败（{class_name}）: {out.stderr.strip()[:160]}")
    return set(re.findall(r"// String ([a-z0-9_]+)", out.stdout))


def check_namespace(namespace: str, spec: dict, rows: list[dict], mods_dir: Path,
                    jar_override: Path | None, problems: list[str],
                    unverified: list[str]) -> str:
    """复核一个命名空间。

    ⚠️ 2026-09-14（T0-a 堵假绿）：**"没复核"必须与"复核通过"分开**。
    原实现把"缺 jar / javap 读不到 / 取证异常"三种情况都 `return "Tier B SKIP…"` 作为**普通注记**，
    而 `main()` 只看 `problems` ⇒ **缺 jar 时仍打印 `PASS` 且 `exit 0`**（三路审计 E-4 已实跑复现：
    `--mods-dir /nonexistent-dir`）。这等于"这句断言根本没执行"被记成"断言通过"。
    现在这些情况一律进 `unverified`，由 `main()` 判决为 `INCOMPLETE`（退出码 2）；
    只有在显式接受（`--allow-unverified-upstream`，例如 CI 上没有上游 jar）时才降级为
    `INCOMPLETE_ACCEPTED` —— **任何路径都不会再打印 `PASS`**。
    """
    jars: list[Path] = []
    for index, pattern in enumerate(spec["jars"]):
        jar = jar_override if (jar_override is not None and index == 0) else find_jar(mods_dir, pattern)
        if jar is not None and jar.exists():
            jars.append(jar)
    if not jars:
        unverified.append(f"{namespace}：没找到上游 jar {spec['jars']}")
        return (f"Tier B SKIP（{namespace}）：没找到上游 jar {spec['jars']}（用 --mods-dir 指定目录）"
                f" ⇒ **该命名空间的上游覆盖未复核**（INCOMPLETE，不是 PASS）")

    with tempfile.TemporaryDirectory() as tmp:
        classpath = list(jars)
        for outer_pattern, inner_pattern in spec.get("jarjar", []):
            outer = find_jar(mods_dir, outer_pattern)
            if outer is None:
                problems.append(f"Tier B: {namespace} 声明了内嵌 jar {inner_pattern}，"
                                f"但外层 {outer_pattern} 不存在")
                continue
            inner = extract_jarjar(outer, inner_pattern, Path(tmp))
            if inner is None:
                problems.append(f"Tier B: {outer.name} 里找不到内嵌 {inner_pattern}"
                                f" —— 上游换了打包方式？Tier B 会因此漏掉一大片类型")
            else:
                classpath.append(inner)
        try:
            types: set[str] = set()
            for class_name in spec["type_classes"]:
                types |= javap_strings(classpath, class_name)
            blocks: set[str] = set()
            for class_name in spec["block_classes"]:
                blocks |= javap_strings(classpath, class_name)
        except Exception as error:  # noqa: BLE001 - 取证失败如实降级，但不许静默通过
            unverified.append(f"{namespace}：javap 取证失败（{error}）")
            return (f"Tier B SKIP（{namespace}: {error}）⇒ **该命名空间的上游覆盖未复核**"
                    f"（INCOMPLETE，不是 PASS）")

        if not types or not blocks:
            unverified.append(f"{namespace}：javap 未读到字符串常量（类名/版本变了）")
            return (f"Tier B SKIP（{namespace}：javap 没读到字符串常量，类名/版本变了）"
                    f" ⇒ **该命名空间的上游覆盖未复核**（INCOMPLETE，不是 PASS）")

        upstream_types = {f"{namespace}:{t}" for t in types
                          if t != namespace and ("_" in t or t.isalpha())}
        declared = {row["type_id"] for row in rows if row["type_id"].startswith(namespace + ":")}
        for row in rows:
            if not row["type_id"].startswith(namespace + ":"):
                continue
            for block in row["block_ids"]:
                short = block.split(":", 1)[1]
                if short not in blocks:
                    problems.append(f"Tier B: 表里的方块 {block} 在上游 {namespace} 的方块类里不存在"
                                    f"（笔误或版本不符）")
        for missing in sorted(upstream_types - declared):
            problems.append(f"Tier B: 上游有类型 {missing}，但表里没有分类（必须显式归类：有站点 / 无站点）")
        for extra in sorted(declared - upstream_types):
            problems.append(f"Tier B: 表里的 {extra} 在上游 {namespace} 的配方类型类里不存在")

        namespace_rows = [row for row in rows if row["type_id"].startswith(namespace + ":")]
        no_site = sum(1 for row in namespace_rows if not row["block_ids"])
        return (f"Tier B OK（{namespace}）：上游类型 {len(upstream_types)} 个，"
                f"表 {len(namespace_rows)} 行（有站点 {len(namespace_rows) - no_site} / 无站点 {no_site}），"
                f"双向一致；classpath={'、'.join(p.name for p in classpath)}")


def tier_b(rows: list[dict], mods_dir: Path, jar_override: Path | None, problems: list[str],
           unverified: list[str]) -> list[str]:
    """按命名空间分别双向复核（表的覆盖口径是"**每个已收录模组全量**"）。"""
    notes: list[str] = []
    for namespace in sorted({row["type_id"].split(":", 1)[0] for row in rows}):
        spec = UPSTREAMS.get(namespace)
        if spec is None:
            problems.append(f"Tier B: 表里有 `{namespace}:` 的行，但工具里**没登记它的上游取证方式**"
                            f"（type/block 类）⇒ 这一族永远不会被复核")
            continue
        notes.append(check_namespace(namespace, spec, rows, mods_dir, jar_override, problems, unverified))
    return notes


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--jar", default=None,
                        help="覆盖**第一个**声明的上游 jar（缺省按 --mods-dir glob）")
    parser.add_argument("--mods-dir", default=DEFAULT_MODS_DIR)
    parser.add_argument("--allow-unverified-upstream", action="store_true",
                        help="显式接受「Tier B 上游覆盖未复核」（例如 CI 上没有上游 jar）。"
                             "此时判决为 INCOMPLETE_ACCEPTED（退出码 0），**仍不打印 PASS**")
    args = parser.parse_args()

    rows, unmapped, sources = parse_rows(JAVA.read_text(encoding="utf-8"))
    problems: list[str] = []
    unverified: list[str] = []
    tier_a(rows, unmapped, problems)

    mods_dir = Path(args.mods_dir)
    tier_b_notes = tier_b(rows, mods_dir, Path(args.jar) if args.jar else None, problems, unverified)

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
    print("取证件: " + (" | ".join(sources) if sources else "-"))
    for note in tier_b_notes:
        print(note)

    if problems:
        for problem in problems:
            print(f"[PROBLEM] {problem}")
        print(f"MACHINE_MAP_CHECK_RESULT FAIL: 行={len(rows)} 未映射={len(unmapped)} 问题={len(problems)}")
        return 1
    if unverified:
        # ⚠️ T0-a：**"没复核"绝不等于"复核通过"**。缺上游 jar / javap 读不到 ⇒ 这句断言根本没执行。
        # 默认判 INCOMPLETE（退出码 2，与"真失败"=1 区分开，调用方可分别处理）；
        # 只有显式 `--allow-unverified-upstream` 才降级为 INCOMPLETE_ACCEPTED（退出码 0）——
        # **两种都不会打印 `PASS`**，所以日志里再也不会出现"缺 jar 却说 PASS"。
        for item in unverified:
            print(f"[UNVERIFIED] {item}")
        if args.allow_unverified_upstream:
            print(f"MACHINE_MAP_CHECK_RESULT INCOMPLETE_ACCEPTED: 行={len(rows)} 未映射={len(unmapped)}"
                  f" 未复核={len(unverified)}（已显式接受：环境缺上游 jar ⇒ **上游覆盖断言本轮未执行**）")
            return 0
        print(f"MACHINE_MAP_CHECK_RESULT INCOMPLETE: 行={len(rows)} 未映射={len(unmapped)}"
              f" 未复核={len(unverified)}（缺上游 jar ⇒ 上层覆盖断言未执行；"
              f"要显式接受请加 --allow-unverified-upstream）")
        return 2
    print(f"MACHINE_MAP_CHECK_RESULT PASS: 行={len(rows)} 未映射={len(unmapped)}"
          f"（Tier A 表结构 + Tier B 上游双向覆盖均已执行）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
