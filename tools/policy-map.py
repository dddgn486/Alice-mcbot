#!/usr/bin/env python3
"""写入策略表（R1 / D-207 ①）的**视图生成 + 一致性断言**（零依赖，只用标准库）。

单一出处是**代码**（`action/WritePolicyMatrix.java`）——这是 Forge 模组，运行期必须有一张
编译进 jar 的表；docs 里的 CSV 是**给人看的视图**（可被覆盖，不要手改）。

``--check`` 断言（任一不成立即非零退出 + 打印 ``POLICY_MATRIX_CHECK_RESULT FAIL``）：

1. 表对 ``Zone × Task`` **全枚举**（一行不缺、一行不多）；
2. 每个 ``WriteReason`` 至少被一行登记（无孤儿理由）；
3. 每个 ``MovementGrant`` 至少被一行引用；
4. 每个 ``PathRequest`` 工厂都被某个 ``MovementGrant`` 认领（词表 = 工厂名，不许有孤儿工厂）；
5. 代码里出现的每个 ``requester`` 字面量，都能被登记表（前缀规则或派生规则）认出；
6. 磁盘上的 CSV 与重新生成的结果一致（防"改了代码忘了更新视图"）。

派生规则（②③）是**从 Java 源码里解析出来**的，不是在 Python 里抄一份：解析不到就**响亮失败**，
不静默放行（否则脚本会变成"永远 PASS 的橡皮图章"）。

用法：``bash tools/policy-map.sh`` / ``bash tools/check-policy-matrix.sh``
"""
from __future__ import annotations

import csv
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "com", "dddgn", "alice")
MATRIX_JAVA = os.path.join(SRC, "action", "WritePolicyMatrix.java")
REASON_JAVA = os.path.join(SRC, "action", "WriteReason.java")
REQUEST_JAVA = os.path.join(SRC, "pathing", "core", "search", "PathRequest.java")
OUT_CSV = os.path.join(ROOT, "docs", "authz", "POLICY_MATRIX.csv")

CSV_HEADER = ["id", "zone", "task", "obligation", "movements", "movement_types",
              "reasons_count", "reasons", "code_ref", "note"]


def read(path: str) -> str:
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text: str) -> str:
    """去掉注释，**但认得字符串**。

    踩过的坑（2026-09-14）：表里有一条 `code_ref` 是 `"task/*CheckTask.java"`——
    里面的 `/*` 被朴素正则当成注释开头，一路吞到下一个 `*/`，把半张表删掉、报"括号不配对"。
    """
    out, index, in_string, escaped = [], 0, False, False
    while index < len(text):
        ch = text[index]
        if in_string:
            out.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            index += 1
            continue
        if ch == '"':
            in_string = True
            out.append(ch)
            index += 1
            continue
        if text.startswith("//", index):
            while index < len(text) and text[index] != "\n":
                index += 1
            continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            index = len(text) if end < 0 else end + 2
            continue
        out.append(ch)
        index += 1
    return "".join(out)


def split_top_level(text: str) -> list[str]:
    """按**顶层**逗号切分（忽略括号内与字符串内的逗号）。"""
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
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current).strip())
            current = []
            continue
        current.append(ch)
    tail = "".join(current).strip()
    if tail:
        parts.append(tail)
    return parts


def balanced(text: str, start: int) -> tuple[str, int]:
    """从 ``text[start]``（必须是左括号）取到配对的右括号，返回（内容, 右括号下标）。"""
    depth, index, in_string, escaped = 0, start, False, False
    while index < len(text):
        ch = text[index]
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
                return text[start + 1:index], index
        index += 1
    raise ValueError("括号不配对")


def java_strings(text: str) -> list[str]:
    return re.findall(r'"((?:[^"\\]|\\.)*)"', text)


# ---------------------------------------------------------------- 解析 Java

def parse_reason_enum() -> list[str]:
    body = read(REASON_JAVA)
    body = body[body.index("public enum WriteReason"):]
    body = body[:body.index(";")]
    names = []
    for line in body.splitlines():
        match = re.match(r"\s*([A-Z][A-Z0-9_]*)\s*\(", line)
        if match:
            names.append(match.group(1))
    return names


def parse_factories() -> list[str]:
    body = read(REQUEST_JAVA)
    return re.findall(r"public static PathRequest (\w+)\s*\(", body)


def parse_enum(text: str, name: str) -> list[str]:
    """取 ``enum <name> { ... }`` 的常量名（按顶层逗号切分，允许多个常量写在同一行）。"""
    marker = f"enum {name}"
    if marker not in text:
        raise ValueError(f"源码里找不到 `{marker}`")
    open_index = text.index("{", text.index(marker))
    body, _ = balanced(text, open_index)
    names = []
    for item in split_top_level(body):
        match = re.match(r"([A-Z][A-Z0-9_]*)", item.strip())
        if match:
            names.append(match.group(1))
    if not names:
        raise ValueError(f"`enum {name}` 解析不出常量名 ⇒ 拒绝静默放行")
    return names


def parse_matrix() -> dict:
    text = strip_comments(read(MATRIX_JAVA))

    grants = re.findall(r"^\s*([A-Z_]+)\(\"(\w+)\"\)", text, flags=re.M)
    grant_types = {}
    for name, factory in grants:
        grant_types[name] = factory

    # 命名理由集合：`private static final Set<WriteReason> NAME = Set.of(...)`
    named_sets: dict[str, list[str]] = {}
    for name, body in re.findall(
            r"Set<WriteReason>\s+([A-Z_]+)\s*=\s*(Set\.of\s*\([^;]*?\));", text, flags=re.S):
        named_sets[name] = java_strings(body) or re.findall(r"WriteReason\.(\w+)", body)

    rows_block = text[text.index("public static final List<Row> ROWS"):]
    rows = []
    for match in re.finditer(r"new Row\s*\(", rows_block):
        inner, _ = balanced(rows_block, match.end() - 1)
        fields = split_top_level(inner)
        if len(fields) != 8:
            raise ValueError(f"Row 字段数不是 8（实际 {len(fields)}）：{inner[:80]}…")
        row_id = java_strings(fields[0])[0]
        zone = fields[1].split(".")[-1]
        task = fields[2].split(".")[-1]
        obligation = fields[3].split(".")[-1]
        movements_field = fields[4]
        if movements_field == "null":
            movements: list[str] = []
        elif movements_field in named_sets:
            movements = named_sets[movements_field]
        else:
            movements = re.findall(r"MovementGrant\.(\w+)", movements_field)
        reasons_field = fields[5]
        if "values()" in reasons_field:
            reasons = ["*"]
        elif reasons_field in named_sets:
            reasons = named_sets[reasons_field]
        else:
            reasons = re.findall(r"WriteReason\.(\w+)", reasons_field)
        rows.append({
            "id": row_id, "zone": zone, "task": task, "obligation": obligation,
            "movements": movements, "movements_declared": movements_field != "null",
            "reasons": reasons, "code_ref": java_strings(fields[6])[0],
            "note": java_strings(fields[7])[0],
        })

    zone_names = parse_enum(text, "Zone")
    task_names = parse_enum(text, "Task")

    prefix_rules = re.findall(r'new String\[\]\{"([^"]+)",\s*"(\w+)"\}', text)
    if not prefix_rules:
        raise ValueError("无法从源码解析 PREFIX_RULES（正则失效）⇒ 拒绝静默放行")
    derived_markers = []
    marker_block = re.search(r"for \(String marker : List\.of\(([^)]*)\)\)", text)
    if marker_block:
        derived_markers = java_strings(marker_block.group(1))
    if not derived_markers:
        raise ValueError("无法从源码解析派生规则标记（正则失效）⇒ 拒绝静默放行")
    derived_body = text[text.index("private static Task derivedTask"):]
    derived_body = derived_body[:derived_body.index("public static Task taskOf")]
    family_rules = [(keyword, task) for condition, task in
                    re.findall(r"if \(([^{}]*)\)\s*\{\s*return Task\.(\w+);", derived_body)
                    for keyword in re.findall(r'contains\("(\w+)"\)', condition)]
    if not family_rules:
        raise ValueError("无法从源码解析任务族规则（正则失效）⇒ 拒绝静默放行")

    return {
        "rows": rows, "zones": zone_names, "tasks": task_names,
        "grants": grant_types, "prefix_rules": prefix_rules,
        "derived_markers": derived_markers, "family_rules": family_rules,
    }


def requester_literals() -> list[tuple[str, str]]:
    """代码里所有 ``requester`` 字面量（文件:行 + 值）。"""
    found: dict[str, str] = {}
    patterns = [
        re.compile(r"WriteGrant\.of\(\s*\"([^\"]+)\""),
        re.compile(r"PathRequest\.\w+\([^;]*?\"([a-zA-Z][\w:.-]*)\"\s*\)"),
    ]
    for dirpath, _dirs, files in os.walk(SRC):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, SRC)
            for lineno, line in enumerate(read(path).splitlines(), start=1):
                for pattern in patterns:
                    for value in pattern.findall(line):
                        found.setdefault(value, f"{rel}:{lineno}")
    return sorted(found.items())


def classify(requester: str, matrix: dict) -> str | None:
    """**镜像** `WritePolicyMatrix.taskOf` 的判定顺序（前缀 → 标记 → 族 → 剥 `task` 后再来一遍）。"""
    for prefix, task in matrix["prefix_rules"]:
        if requester.startswith(prefix):
            return task
    normalized = requester.lower().replace("_", "").replace("-", "")
    for marker in matrix["derived_markers"]:
        if marker in normalized:
            return "DIAGNOSTIC"
    stripped = normalized[:-4] if normalized.endswith("task") else normalized
    for keyword, task in matrix["family_rules"]:
        if keyword in stripped:
            return task
    for marker in matrix["derived_markers"]:
        if marker in stripped:
            return "DIAGNOSTIC"
    return None


def build_rows_csv(matrix: dict) -> list[list[str]]:
    out = []
    for row in matrix["rows"]:
        types: list[str] = []
        factory_by_grant = matrix["grants"]
        for grant in row["movements"]:
            types.append(factory_by_grant.get(grant, "?"))
        reasons = row["reasons"]
        out.append([
            row["id"], row["zone"], row["task"], row["obligation"],
            "未声明" if not row["movements_declared"] else "|".join(row["movements"]),
            "未声明" if not row["movements_declared"] else "|".join(types),
            str(len(reasons)) if reasons != ["*"] else "全部",
            "全部" if reasons == ["*"] else "|".join(reasons),
            row["code_ref"], row["note"],
        ])
    return out


def generate_csv(matrix: dict) -> str:
    import io
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(CSV_HEADER)
    writer.writerows(build_rows_csv(matrix))
    return buffer.getvalue()


def main() -> int:
    check = "--check" in sys.argv
    try:
        matrix = parse_matrix()
    except Exception as exc:                                   # noqa: BLE001 —— 解析失败要响亮
        print(f"POLICY_MATRIX_CHECK_RESULT FAIL: 解析源码失败：{exc}")
        return 1

    problems: list[str] = []

    # ① 全枚举
    expected = len(matrix["zones"]) * len(matrix["tasks"])
    if len(matrix["rows"]) != expected:
        problems.append(f"行数 {len(matrix['rows'])} ≠ Zone×Task {expected}")
    for zone in matrix["zones"]:
        for task in matrix["tasks"]:
            hits = [r for r in matrix["rows"] if r["zone"] == zone and r["task"] == task]
            if len(hits) != 1:
                problems.append(f"{zone}×{task} 行数={len(hits)}（应为 1）")

    # ② 理由无孤儿
    covered: set[str] = set()
    for row in matrix["rows"]:
        if row["reasons"] == ["*"]:
            covered.update(parse_reason_enum())
        else:
            covered.update(row["reasons"])
    for reason in parse_reason_enum():
        if reason not in covered:
            problems.append(f"孤儿理由 {reason}（没有任何行登记）")

    # ③ 授权无孤儿
    used_grants: set[str] = set()
    for row in matrix["rows"]:
        used_grants.update(row["movements"])
    for grant in matrix["grants"]:
        if grant not in used_grants:
            problems.append(f"孤儿授权 {grant}（没有任何行引用）")

    # ④ 工厂词表无孤儿
    factories = set(parse_factories())
    claimed = set(matrix["grants"].values())
    for factory in sorted(factories):
        if factory not in claimed:
            problems.append(f"PathRequest 工厂 {factory} 没有对应的 MovementGrant（词表不完整）")

    # ④b 词表里的工厂名必须在 PathRequest 里真的存在（含实例方法 `pureTraversal`）
    request_source = read(REQUEST_JAVA)
    for grant, factory in matrix["grants"].items():
        if not re.search(rf"\b{factory}\s*\(", request_source):
            problems.append(f"MovementGrant.{grant} 指向的工厂 `{factory}` 在 PathRequest.java 里不存在")

    # ⑤ requester 字面量可归类
    unmatched = []
    for requester, where in requester_literals():
        if classify(requester, matrix) is None:
            unmatched.append(f"{requester}（{where}）")
    if unmatched:
        problems.append("未登记的 requester 字面量：" + "; ".join(unmatched))

    # ⑥ 视图不过期
    expected_csv = generate_csv(matrix)
    if check:
        on_disk = read(OUT_CSV) if os.path.exists(OUT_CSV) else ""
        if on_disk != expected_csv:
            problems.append(f"{os.path.relpath(OUT_CSV, ROOT)} 已过期（跑 bash tools/policy-map.sh 重新生成）")
    else:
        os.makedirs(os.path.dirname(OUT_CSV), exist_ok=True)
        with open(OUT_CSV, "w", encoding="utf-8") as handle:
            handle.write(expected_csv)
        print(f"已生成 {os.path.relpath(OUT_CSV, ROOT)}（{len(matrix['rows'])} 行）")

    # ⑦（信息性，不判红）死值雷达：只在 WriteReason/WritePolicyMatrix 里出现的理由 = 没有调用点
    code_corpus = []
    for dirpath, _dirs, files in os.walk(SRC):
        for name in files:
            if name.endswith(".java") and name not in ("WriteReason.java", "WritePolicyMatrix.java"):
                code_corpus.append(read(os.path.join(dirpath, name)))
    corpus = "\n".join(code_corpus)
    unused = [reason for reason in parse_reason_enum() if f"WriteReason.{reason}" not in corpus]
    if unused:
        print(f"提示（非失败）：这些理由在 {len(code_corpus)} 个源文件里没有任何调用点 ⇒ 疑似死值：{unused}")

    print(f"表：rows={len(matrix['rows'])} zones={len(matrix['zones'])} tasks={len(matrix['tasks'])} "
          f"grant={len(matrix['grants'])} 注册前缀={len(matrix['prefix_rules'])} "
          f"派生标记={matrix['derived_markers']} 族规则={len(matrix['family_rules'])}")
    if problems:
        print("POLICY_MATRIX_CHECK_RESULT FAIL")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"POLICY_MATRIX_CHECK_RESULT PASS: {len(matrix['rows'])} 行 / "
          f"{len(parse_reason_enum())} 理由 / {len(factories)} 工厂 / "
          f"{len(requester_literals())} requester 字面量")
    return 0


if __name__ == "__main__":
    sys.exit(main())
