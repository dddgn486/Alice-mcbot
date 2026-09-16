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
SITES_CSV = os.path.join(ROOT, "docs", "authz", "CONTAINER_WRITE_SITES.csv")

#: 容器写入调用点的三种模式（文件级；见 ``container_write_sites``）。
HANDLER_MUTATION = re.compile(r"\.(insertItem|extractItem)\(")
MENU_CLICK = re.compile(r"\.clicked\(|\.click\(|MenuSession\.open\(")
FOREIGN_SETITEM = re.compile(r"([A-Za-z_][\w.()\[\]\"]{0,60}?)\.setItem\(")

#: **已登记的写入原语入口**：调用它们的文件也是容器写入点（转义层：`FurnaceStation` 那一层
#: 自己点不动任何东西，真正动手的是调用方）。新加写入原语时要把它加到这里——
#: 这是本工具里唯一一份"必须手维护"的清单，加它的理由与 `PREFIX_RULES` 相同：
#: 不做传递闭包推断（猜错会静默漏点），宁可显式列。
WRITER_HELPERS = re.compile(
    r"(FurnaceStation\.(placeOne|takeAll)"
    r"|StationProvision\.(moveIntoContainer|moveOutOfContainer)"
    r"|ChestBotTransferPrimitive\.(sourceChestToBot|botToDestinationChest)"
    r"|InventoryCraft\.craft\()")

#: **故意未登记**的 requester 字面量：自检用它断言"登记缺口 ⇒ 留痕但不拒"这条口径
#: （`WritePolicyCheckTask.container_gate_live`）。**只放自检用的名字**，且必须是源码里真实存在的字面量
#: （下面的检查会验证，防陈旧条目）。生产 requester 一律不许进这里。
INTENTIONAL_UNREGISTERED = {"no-such-requester-xyz"}

#: 调用点分类（登记表里只能填这几个；加新类别=改这里，**不许随手写形容词**）。
SITE_CATEGORIES = {
    "gated",              # bot 经闸门写容器（调用 consumeContainerWrite）
    "menu-protocol",      # 菜单协议层本身（点在 bot 菜单上；执法在调用方）
    "own-inventory",      # bot 自己的背包/合成格：不是世界写入
    "scenario-seeding",   # 夹具搭场景（测试前把箱子摆好）
    "primitive-isolation",# 夹具在隔离层直接驱动搬运原语（**已知边界**：不过闸门）
    "probe",              # 探针/诊断用途的临时写入
}

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
    # 派生规则标记（T1 / R-3，2026-09-14）：真源从 `WritePolicyMatrix` **挪到了** `task/Task.java` 的
    # `SELF_CHECK_MARKERS`（原先两处各写一份，与 `Task.isSelfCheck()` 漂移了 18 个类）。
    # ⇒ 这里改读新真源；**读不到照样响亮失败**，不允许"解析不到就静默放行"
    #   （旧正则失效时正是本门禁抓到的，说明这道保险是有效的）。
    derived_markers = []
    task_iface = os.path.join(ROOT, "src/main/java/com/dddgn/alice/task/Task.java")
    if os.path.exists(task_iface):
        marker_block = re.search(r"SELF_CHECK_MARKERS\s*=\s*\n?\s*List\.of\(([^)]*)\)",
                                 read(task_iface))
        if marker_block:
            derived_markers = java_strings(marker_block.group(1))
    if not derived_markers:
        raise ValueError("无法从 `task/Task.java` 的 `SELF_CHECK_MARKERS` 解析派生规则标记"
                         "（真源缺失或正则失效）⇒ 拒绝静默放行")
    if "looksLikeSelfCheck" not in text:
        raise ValueError("`WritePolicyMatrix` 没有调用 `Task.looksLikeSelfCheck`"
                         "（T1/R-3 的唯一真源接线断了）⇒ 拒绝静默放行")
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


def container_write_sites() -> set[str]:
    """**疑似写容器**的源文件（相对 ``SRC``）——"疑似"宁可宽：漏一个 = 闸门外的洞。

    三条模式（都先剥注释，否则文档里提到 ``menu.clicked(...)`` 会被当成调用点）：

    1. capability 直写：``.insertItem(`` / ``.extractItem(``（真正会改容器的那两个 false 调用都在其中）；
    2. 菜单层直点：``.clicked(``（``MenuSession`` 内部）与 ``.click(``（``MenuSession.click`` 的调用者）；
    3. 容器对象直写：``X.setItem(``，其中 ``X`` **不是** bot 自己的背包（``getInventory()`` 那一类）；
    4. 打开容器菜单：``MenuSession.open(``（**开菜单本身不是写入**，但它是"接下来会点它"的前置，
       真实缺口往往只在调用方 ⇒ 必须被登记表命名一次）。

    判据是"文件级"而不是"行级"：行级正则必然漏（多行调用、包装函数），
    而文件级漏不掉——只要文件里出现过任一模，就必须在登记表里被**命名**一次。
    """
    # ⚠️ 2026-09-14（T0-a 堵假绿）：**自查判据从"子串"改成"精确形态 + 整词"**（fail-closed）。
    # 旧判据 `any(hint in receiver for hint in ("getInventory()","inventory","botInv","playerInv","inv"))`
    # 里的 `"inv"` 是**子串匹配** ⇒ 接收者只要叫 `chest_inv` / `source_inventory` / `my_inv` /
    # `staging_inventory`，全部被判成"bot 自己背包" ⇒ **静默豁免、无需登记**（三路审计 E-6 已实测）。
    # 现在只豁免**无歧义**的自身背包形态；**认不出一律按"要登记"处理**（未知 ⇒ 视为外部容器）：
    #   · `X.getInventory(...)`（原版 Player/Entity 的自身背包 API）；
    #   · **整词**（接收者的最后一个标识符）∈ `{botInv, playerInv, inventory, inv}` ——
    #     `gui/BotInventoryService.java:215` 与 `gui/BotInventoryFixture.java:36` 实测都是
    #     `Inventory inv = bot.getInventory()`，即 bot 自身背包。
    # ⚠️ 已知边界（如实记录，不假装覆盖）：若将来有人把**外部容器**存进名叫 `inv`/`inventory` 的局部变量，
    # 本判据仍会漏。这是"无法做数据流分析"的代价；换成完全 fail-closed 会立刻误报 13 个
    # bot 自身背包的写入点（实测），把信号淹掉。⇒ 选"整词匹配 + 记录边界"，而不是"子串匹配 + 沉默"。
    own_inventory_exact = ("botInv", "playerInv", "inventory", "inv")

    def is_own_inventory(receiver: str) -> bool:
        if "getInventory(" in receiver:
            return True
        tail = re.split(r"[.\s(\[]", receiver.strip())[-1] if receiver.strip() else ""
        return tail in own_inventory_exact

    sites: set[str] = set()
    for dirpath, _dirs, files in os.walk(SRC):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            body = strip_comments(read(path))
            if HANDLER_MUTATION.search(body) or MENU_CLICK.search(body) or WRITER_HELPERS.search(body):
                sites.add(os.path.relpath(path, SRC))
                continue
            for match in FOREIGN_SETITEM.finditer(body):
                receiver = match.group(1).strip()
                if is_own_inventory(receiver):
                    continue
                sites.add(os.path.relpath(path, SRC))
                break
    return sites


def read_container_site_registry() -> dict[str, dict[str, str]]:
    """读 ``docs/authz/CONTAINER_WRITE_SITES.csv``（``site,category,gated,why``）。"""
    if not os.path.exists(SITES_CSV):
        return {}
    rows: dict[str, dict[str, str]] = {}
    with open(SITES_CSV, "r", encoding="utf-8") as handle:
        for raw in csv.DictReader(handle):
            site = (raw.get("site") or "").strip()
            if not site or site.startswith("#"):
                continue
            rows[site] = {
                "category": (raw.get("category") or "").strip(),
                "gated": (raw.get("gated") or "").strip(),
                "enforced_by": (raw.get("enforced_by") or "").strip(),
                "why": (raw.get("why") or "").strip(),
            }
    return rows


def requester_literals() -> list[tuple[str, str]]:
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
    literals = requester_literals()
    for requester, where in literals:
        if requester in INTENTIONAL_UNREGISTERED:
            continue
        if classify(requester, matrix) is None:
            unmatched.append(f"{requester}（{where}）")
    seen_literals = {value for value, _where in literals}
    for intentional in sorted(INTENTIONAL_UNREGISTERED - seen_literals):
        problems.append(f"INTENTIONAL_UNREGISTERED 里的 {intentional} 在源码里已不存在（陈旧条目 ⇒ 删）")
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

    # ⑦ 容器写入调用点覆盖（R1 收口，2026-09-14）：**模型在表里声明了容器写入这一维，就必须知道
    #    谁真的在写容器** —— 否则"矩阵有没有拒绝权"这个问题无从回答（闸门装在半数调用点之外 =
    #    没有闸门）。判据三条：
    #      a) 代码里每个"疑似写容器的文件"都必须登记在 CONTAINER_WRITE_SITES.csv（新写入点 ⇒ 逼一次决策）；
    #      b) 每个 `gated=yes` 的文件必须真的调用 `consumeContainerWrite`（防"声明已接线"的谎）；
    #      c) 每个 `gated=no` 的行必须写明 `why`（禁止静默豁免）。
    sites = container_write_sites()
    registered = read_container_site_registry()
    for site in sorted(sites):
        if site not in registered:
            problems.append(f"未登记的容器写入调用点 {site}"
                            f"（新写入点必须进 {os.path.relpath(SITES_CSV, ROOT)}："
                            f"要么接 consumeContainerWrite，要么写明豁免理由）")
    for site, row in sorted(registered.items()):
        if site not in sites:
            problems.append(f"{os.path.relpath(SITES_CSV, ROOT)} 里的 {site} 已不存在或不再写容器"
                            f"（陈旧登记 ⇒ 删行）")
            continue
        source = read(os.path.join(SRC, site))
        if row["gated"] == "yes" and "consumeContainerWrite" not in source:
            problems.append(f"{site} 登记为 gated=yes，但源码里没有 consumeContainerWrite 调用")
        if row["gated"] not in ("yes", "no"):
            problems.append(f"{site} 的 gated 取值非法：{row['gated']}（只能是 yes/no）")
        if row["gated"] == "no" and len(row["why"]) < 8:
            problems.append(f"{site} 豁免了容器写入闸门但没写理由（why 太短）")
        if row["category"] not in SITE_CATEGORIES:
            problems.append(f"{site} 的 category 非法：{row['category']}（合法值 {sorted(SITE_CATEGORIES)}）")
        # `enforced_by` = "这一层的写入由谁过闸门"——把散文理由变成**可机器验证的链**：
        # 点名的文件必须存在、且真的调用 consumeContainerWrite（否则就是"登记已接线"的谎）。
        for enforcer in [e.strip() for e in row["enforced_by"].split(";") if e.strip()]:
            enforcer_path = os.path.join(SRC, enforcer)
            if not os.path.exists(enforcer_path):
                problems.append(f"{site} 的 enforced_by 指向不存在的文件：{enforcer}")
            elif "consumeContainerWrite" not in read(enforcer_path):
                problems.append(f"{site} 的 enforced_by={enforcer} 并没有调用 consumeContainerWrite")

    # ⑨ 菜单写入原语的**编译期强制**（R5-残 收口，2026-09-16）：`FurnaceStation` 是样板 ——
    #    「调本原语必须显式交出 WriteGrant，且理由必须属于菜单写入家族」。
    #    这三处原语若签名里没有 WriteGrant，就等于回到"记账靠调用方自觉"（三路审计 §3.1 R-5 的原缺口，
    #    R5-残 登记了 `StationProvision.click`／`InventoryCraft.click` 两处未做）。
    for rel in ("task/craft/FurnaceStation.java", "task/craft/InventoryCraft.java",
                "task/craft/StationProvision.java"):
        src = read(os.path.join(SRC, rel))
        overloads = list(re.finditer(r"private static boolean click\(", src))
        if not overloads:
            problems.append(f"{rel} 里找不到 click 原语（改名？同步本规则）")
        for m in overloads:
            # 平衡括号取形参表：**每个重载**都必须带 WriteGrant（只查一个会被另一个重载"顶绿"，
            # 2026-09-16 反向对照实测：把 5 参重载改成 Object 时旧规则仍 PASS ⇒ 规则太弱）
            i, depth, j = m.end() - 1, 0, m.end() - 1
            while j < len(src):
                if src[j] == '(':
                    depth += 1
                elif src[j] == ')':
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            params = src[i + 1:j]
            if "WriteGrant" not in params:
                problems.append(f"{rel} 的 click 重载（形参 {params.strip()[:60]}）没有 WriteGrant"
                                f"（R5-残：编译期强制被移除）")

    # ⑧（信息性，不判红）死值雷达：只在 WriteReason/WritePolicyMatrix 里出现的理由 = 没有调用点
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
    gated_sites = [s for s, row in registered.items() if row["gated"] == "yes"]
    print(f"POLICY_MATRIX_CHECK_RESULT PASS: {len(matrix['rows'])} 行 / "
          f"{len(parse_reason_enum())} 理由 / {len(factories)} 工厂 / "
          f"{len(requester_literals())} requester 字面量 / "
          f"容器写入调用点 {len(registered)} 个（过闸门 {len(gated_sites)}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
