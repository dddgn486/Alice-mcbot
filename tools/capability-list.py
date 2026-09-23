#!/usr/bin/env python3
"""**能力清单**（`B3` / `Q-22`，2026-09-23）：把「它知道的自己」做成**从代码生成的表**。

出处 = `survey/29 §3.8⑥`（用户已裁定同意，`Q-22`）：

> 把「它知道的自己」做成一张**生成的表**，而不是记忆：把"我能做什么/不能做什么"做成**从代码生成的能力清单**，
> 照项目四个现成模子（**单一出处 + 双向防漂移 + 解析不到就响亮失败 + 全挂 `check-all`**）。

## 本文件的两个反向纪律

* **不引入第五个真相源**：这里**不新增**任何事实表 —— 每一节都只是把一个**已有的单一出处**读出来渲染成人读视图
  （`GoalAction` 白名单 / `JobRequest.Kind` + `JobKindContract` / `MovementType` / `CheckModules` + 模块 `CheckStep`
  + `RegressionBatteryTask.CURATION` / `docs/MACHINE_MAP.csv` / `RiskSwitches` / `ZoneAuthority`）。
  清单与出处不一致时**以代码为准**，生成器改的是清单，不是代码。
* **判据不许同义反复**（`A2`/`D-417` 的教训）：如果只断言"生成的文档 == 生成的文档"，只要重新生成就一定一致
  ⇒ 那是空判据。**牙齿在 `--check` 的跨出处断言**（见 `ASSERTIONS`）：步声明 ↔ `CURATION`、
  模块档位 ↔ `CURATION` 档位、模块注册表 ↔ 电池成员、`Kind` ↔ 契约表、`MovementType` ↔ `changesWorld()`。
  每一对回答的都是「**同一个事实有没有两个家**」。

## 用法

    python3 tools/capability-list.py            # 打印到 stdout（不改盘）
    python3 tools/capability-list.py --write    # 重新生成 docs/CAPABILITY_LIST.md
    python3 tools/capability-list.py --check    # 门禁：不陈旧 + 全部跨出处断言（挂在 tools/check-all.sh）

退出码：`0` = PASS；`1` = FAIL（含"解析崩塌"——**解析不到就响亮失败**，不许少一行悄悄过）。
"""

from __future__ import annotations

import argparse
import csv
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "CAPABILITY_LIST.md"
CSV = ROOT / "docs" / "MACHINE_MAP.csv"
JAVA = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"

# --------------------------------------------------------------------------------------
# 豁免表：**唯一合法的「对面没有」**，且必须能被反向核对
# （豁免项若**真的**出现在对面 ⇒ 过期豁免 ⇒ 红 —— 豁免不是许愿池）
# --------------------------------------------------------------------------------------
# ⚠️ 步一级**不需要**豁免表：电池只从**它自己用到的模块**取步（`buildSteps`），
# 所以"模块不在电池里"这件事在步一级表现为"它压根没被声明" ⇒ A1 是干净的双向断言。
# 模块一级才需要豁免（见 A3），因为 `CheckModules.ALL` 是 `module:<id>` 的入口，比电池更大。
MODULE_EXEMPT = {
    "HarnessSelfModule": "整模块 expectedVerdict=FAIL（故意被打断两次）⇒ 只由 module:harness_self 单独跑，不进电池",
}

# 每节的人口下限（防空集真 —— `Z4` 的教训：`isEmpty()` 式的判据在"什么都没解析出来"时也成立）。
# ⚠️ 刻意**略低于**当前真值：下限只负责"挡住解析崩塌/表被掏空"，**不负责锁死项数**
# （项数由 `A8 文档不陈旧` + `A1 双向` 保证 —— 删一步只要 `--write` 重新生成，不必改本文件）。
POP_FLOOR = {
    "goal_actions": 4,
    "job_kinds": 4,
    "movements": 8,
    "modules": 15,
    "battery_steps": 80,
    "machine_rows": 1,
    "optional_switches": 2,
    "bottom_lines": 2,
    "zone_verdicts": 3,
    "zone_acts": 2,
}


def strip_comments(text: str) -> str:
    """去 `//` 行注释与 `/* */` 块注释（**必须去掉 javadoc**：`{@link CheckStep}` 会被算成一次出现）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def enum_constants(body: str) -> list[str]:
    """枚举常量名（去注释、取第一个 `;` 之前的常量区，按 `,`/换行切开）—— 不假设"一行一个"。"""
    head = strip_comments(body).split(";", 1)[0]
    parts = [p.strip() for p in re.split(r"[,\n]", head)]
    return [p for p in parts if re.fullmatch(r"[A-Z][A-Z0-9_]*", p)]


def read(path: pathlib.Path) -> str:
    if not path.is_file():
        fail(f"找不到出处文件 {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


PROBLEMS: list[str] = []


def fail(msg: str) -> None:
    PROBLEMS.append(msg)


# ======================================================================================
# 出处解析器：每个都必须能给出"独立计数"，用于交叉核对（解析崩塌 = 红，而不是少一行）
# ======================================================================================

def parse_goal_actions() -> tuple[list[str], str]:
    """目标级动作白名单 = `GoalAction.parse` 的 `case`（`check-goal-vocabulary` 断言它与 prompt 词表集合相等）。"""
    text = read(JAVA / "decision" / "GoalAction.java")
    start = text.index("switch (action)")
    end = text.index("default ->", start)
    body = text[start:end]
    actions = re.findall(r'case\s+"([a-z_]+)"\s*->', body)
    calls = len(re.findall(r'case\s+"[a-z_]+"\s*->', body))
    if calls != len(actions):
        fail(f"[解析崩塌] GoalAction：case 出现 {calls} 次、解析出 {len(actions)} 个")
    return actions, "decision/GoalAction.java#parse（case 白名单）"


def parse_job_kinds() -> tuple[list[str], str]:
    text = read(JAVA / "job" / "JobRequest.java")
    m = re.search(r"enum Kind\s*\{(.*?)\n    \}", text, re.S)
    if not m:
        fail("[解析崩塌] JobRequest.Kind 枚举没找到（形状变了？）")
        return [], "job/JobRequest.java#Kind"
    body = strip_comments(m.group(1))
    kinds = enum_constants(body)
    if not kinds:
        fail("[解析崩塌] JobRequest.Kind 里一个常量都没解析出来")
    return kinds, "job/JobRequest.java#Kind"


def parse_job_contracts() -> tuple[dict[str, tuple[str, str, str]], str]:
    """kind → (成功判据, 读世界事实的方法, 不一致怎么办)；三个字段都必须非空（字段校验归 `check-job-kind-contracts`）。"""
    text = read(JAVA / "job" / "JobKindContract.java")
    rows = re.findall(r"new Contract\(JobRequest\.Kind\.([A-Z0-9_]+)\s*,(.*?)\)\s*,?\s*\n", text, re.S)
    calls = len(re.findall(r"new Contract\(", text))
    if calls != len(rows):
        fail(f"[解析崩塌] JobKindContract：`new Contract(` 出现 {calls} 次、解析出 {len(rows)} 行")
    out: dict[str, tuple[str, str, str]] = {}
    for name, rest in rows:
        literals = re.findall(r'"((?:[^"\\]|\\.)*)"', rest)
        if len(literals) < 3:
            fail(f"[解析崩塌] JobKindContract.{name} 只有 {len(literals)} 个字符串字段（应为 3）")
            continue
        out[name] = (literals[0], literals[1], literals[2])
    if not out:
        fail("[解析崩塌] JobKindContract 表一行都没解析出来")
    return out, "job/JobKindContract.java#TABLE"


def parse_movements() -> tuple[list[str], set[str], str]:
    """移动原语 + 「改世界」的那一族（`changesWorld()` 的名单是**第二处**声明）。"""
    text = read(JAVA / "pathing" / "core" / "MovementType.java")
    m = re.search(r"public enum MovementType\s*\{(.*?)\n\}", text, re.S)
    if not m:
        fail("[解析崩塌] MovementType 枚举没找到（形状变了？）")
        return [], set(), "pathing/core/MovementType.java"
    body = strip_comments(m.group(1))
    # 常量区 = 第一个 `;` 之前
    movements = enum_constants(body)
    cw = re.search(r"changesWorld\(\)\s*\{(.*?)\n    \}", body, re.S)
    changing: set[str] = set()
    if cw:
        changing = set(re.findall(r"this\s*==\s*([A-Z][A-Z0-9_]*)", cw.group(1)))
    else:
        fail("[解析崩塌] MovementType.changesWorld() 没找到（它是规划期写入口径的单一出处）")
    if not movements:
        fail("[解析崩塌] MovementType 里一个常量都没解析出来")
    return movements, changing, "pathing/core/MovementType.java#changesWorld"


def parse_modules() -> tuple[dict[str, dict], str]:
    """模块注册表（`CheckModules.ALL`）→ 每个模块的 id/标题/期望判决/步声明。"""
    reg = read(JAVA / "task" / "check" / "CheckModules.java")
    m = re.search(r"List<CheckModule> ALL = List\.of\((.*?)\);", reg, re.S)
    if not m:
        fail("[解析崩塌] CheckModules.ALL 没找到（形状变了？）")
        return {}, "task/check/CheckModules.java#ALL"
    classes = re.findall(r"new\s+([A-Za-z0-9_]+)\(\)", m.group(1))
    mod_dir = JAVA / "task" / "check" / "modules"
    out: dict[str, dict] = {}
    for cls in classes:
        src = read(mod_dir / f"{cls}.java")
        mid = re.search(r'public String id\(\)\s*\{\s*return\s*"(.*?)"', src, re.S)
        title = re.search(r'public String title\(\)\s*\{\s*return\s*"(.*?)"', src, re.S)
        verdict = re.search(r'public String expectedVerdict\(\)\s*\{\s*return\s*"(.*?)"', src, re.S)
        steps = re.findall(
            r'CheckStep\.(?:of|skippable|keeping)\(\s*"([A-Za-z0-9_]+)"\s*,\s*'
            r'(?:[A-Za-z0-9_.]*\.)?CheckProfile\.([A-Z]+)',
            src, re.S)
        # ---- 独立计数（解析崩塌即红）：去注释/去 import 后，工厂调用必须**只有**这三种形状且数目相等 ----
        code = "\n".join(l for l in strip_comments(src).splitlines() if not l.strip().startswith("import "))
        factories = re.findall(r"\bCheckStep\.([A-Za-z0-9_]+)\(", code)
        unknown_factory = sorted(set(factories) - {"of", "skippable", "keeping"})
        if unknown_factory or len(factories) != len(steps):
            fail(f"[解析崩塌] {cls}：`CheckStep.xxx(` 调用 {len(factories)} 次 / 解析出 {len(steps)} 步"
                 + (f" / 出现了没见过的工厂 {unknown_factory}" if unknown_factory else "")
                 + " ⇒ 步声明形状变了，请同步本解析器（不许少一行悄悄过）")
        if mid is None:
            fail(f"[解析崩塌] {cls} 没有可解析的 id()")
            continue
        out[cls] = {
            "class": cls,
            "id": mid.group(1),
            "title": title.group(1) if title else "",
            "expected": verdict.group(1) if verdict else "PASS",
            "steps": [{"name": n, "profile": p} for n, p in steps],
        }
    if not out:
        fail("[解析崩塌] 一个模块都没解析出来")
    return out, "task/check/CheckModules.java#ALL + modules/*.java 的 CheckStep 声明"


def parse_battery() -> tuple[dict, str]:
    """电池：`CURATION` 归属表 + 内联步 + 模块使用序（= CORE 的真实运行序）。"""
    text = read(JAVA / "task" / "RegressionBatteryTask.java")
    marker = "private static final Map<String, Profile> CURATION"
    if marker not in text:
        fail("[解析崩塌] 找不到 CURATION（归属表是档位的唯一出处）")
        return {}, "task/RegressionBatteryTask.java#CURATION"
    region = text[text.index(marker):]
    entries = re.findall(r'Map\.entry\(\s*"([A-Za-z0-9_]+)"\s*,\s*Profile\.([A-Z]+)\s*\)', region)
    if len(re.findall(r"Map\.entry\(", region)) != len(entries):
        fail(f"[解析崩塌] CURATION：`Map.entry(` 出现 {len(re.findall(r'Map.entry(', region))} 次、"
             f"解析出 {len(entries)} 条 ⇒ 归属表形状变了")
    dups = {n for n, _ in entries if [x for x, _ in entries].count(n) > 1}
    if dups:
        fail(f"[解析崩塌] CURATION 里有重名条目 {sorted(dups)}（Map.ofEntries 会在运行期抛异常）")

    build = text[text.index("private void buildSteps()"):text.index("// ==================== 执行 ====================")]
    # buildSteps 里的**顺序**：模块 take-all 与内联步交替出现，顺序即真实运行序
    order: list[tuple[str, str]] = []   # (kind, payload)
    for m in re.finditer(r'new com\.dddgn\.alice\.task\.check\.modules\.([A-Za-z0-9_]+)\(\)\.steps\(|'
                         r'\bstep\(\s*"([A-Za-z0-9_]+)"', build):
        if m.group(1):
            order.append(("module", m.group(1)))
        else:
            order.append(("inline", m.group(2)))
    if not order:
        fail("[解析崩塌] buildSteps() 里既没解析出模块、也没解析出内联步")
    return {
        "curation": dict(entries),
        "order": order,
    }, "task/RegressionBatteryTask.java#CURATION（+ buildSteps 顺序）"


def parse_machine_rows() -> tuple[list[dict], str]:
    """机器映射表的人读视图（**新鲜度由 `check-machine-map` 门禁**，这里只读它的行做人口/汇总）。"""
    if not CSV.is_file():
        fail(f"找不到 {CSV.relative_to(ROOT)}（跑 `python3 tools/machine-map.py --write` 生成）")
        return [], "docs/MACHINE_MAP.csv（由 tools/machine-map.py 生成）"
    rows = list(csv.DictReader(CSV.read_text(encoding="utf-8").splitlines()))
    if not rows:
        fail("[解析崩塌] MACHINE_MAP.csv 里一行都没有")
    return rows, "docs/MACHINE_MAP.csv（由 tools/machine-map.py 生成）"


def parse_risk_switches() -> tuple[dict[str, dict], str]:
    text = read(JAVA / "pathing" / "risk" / "RiskSwitches.java")
    known = re.search(r"Set<String> KNOWN\s*=\s*java\.util\.Set\.of\((.*?)\);", text, re.S)
    optional = re.search(r"Map<String, String> OPTIONAL\s*=\s*java\.util\.Map\.of\((.*?)\);", text, re.S)
    bottom = re.search(r"Map<String, String> BOTTOM_LINES\s*=\s*java\.util\.Map\.of\((.*?)\);", text, re.S)
    if not (known and optional and bottom):
        fail("[解析崩塌] RiskSwitches 的 KNOWN/OPTIONAL/BOTTOM_LINES 有一张没找到（形状变了？）")
        return {}, "pathing/risk/RiskSwitches.java"

    # `Map.of(k1, v1, k2, v2, …)`：**键既可能是大写常量名、也可能是字符串字面量**（`BOTTOM_LINES` 就是后者）
    # ⇒ 先按"原子"（常量名 / 字符串）切出来，再两两配对；这样两种写法都不会解析成空表。
    def atoms(body: str) -> list[str]:
        return [m.group(1) if m.group(1) else m.group(2)
                for m in re.finditer(r'([A-Z][A-Z0-9_]*)|"((?:[^"\\]|\\.)*)"', body)]

    def pairs(body: str) -> dict[str, str]:
        a = atoms(body)
        return {a[i]: a[i + 1] for i in range(0, len(a) - 1, 2)}

    known_names = atoms(known.group(1))
    opt, bot = pairs(optional.group(1)), pairs(bottom.group(1))
    if not opt or not bot:
        fail("[解析崩塌] RiskSwitches 的可选/底线表解析为空")
    return {"known": known_names, "optional": opt, "bottom": bot}, "pathing/risk/RiskSwitches.java"


def parse_zone() -> tuple[dict[str, list[str]], str]:
    text = read(JAVA / "protection" / "ZoneAuthority.java")
    out: dict[str, list[str]] = {}
    for name in ("Verdict", "Act"):
        m = re.search(rf"enum {name}\s*\{{(.*?)\n    \}}", text, re.S)
        if not m:
            fail(f"[解析崩塌] ZoneAuthority.{name} 没找到")
            out[name] = []
            continue
        body = strip_comments(m.group(1))
        vals = enum_constants(body)
        if not vals:
            fail(f"[解析崩塌] ZoneAuthority.{name} 里一个常量都没解析出来")
        out[name] = vals
    return out, "protection/ZoneAuthority.java（Verdict / Act）"


# ======================================================================================
# 渲染
# ======================================================================================

def tier_rank(t: str) -> int:
    return {"BASELINE": 0, "MAIN": 1, "EXTRA": 2}.get(t, 3)


def build_model() -> dict:
    actions, actions_src = parse_goal_actions()
    kinds, kinds_src = parse_job_kinds()
    contracts, contracts_src = parse_job_contracts()
    movements, changing, move_src = parse_movements()
    modules, modules_src = parse_modules()
    battery, battery_src = parse_battery()
    machines, machines_src = parse_machine_rows()
    switches, switches_src = parse_risk_switches()
    zone, zone_src = parse_zone()

    # ---- 运行序（模块 take-all 按声明序展开；内联步原样）----
    module_of: dict[str, str] = {}
    run: list[dict] = []
    for kind, payload in battery["order"]:
        if kind == "module":
            mod = modules.get(payload)
            if mod is None:
                fail(f"[解析崩塌] buildSteps() 用了模块 {payload}，但注册表里没有它")
                continue
            for st in mod["steps"]:
                module_of[st["name"]] = mod["id"]
                run.append({"name": st["name"], "module": mod["id"], "profile_decl": st["profile"]})
        else:
            module_of[payload] = "(内联)"
            run.append({"name": payload, "module": "(内联)", "profile_decl": None})

    model = {
        "goal_actions": (actions, actions_src),
        "job_kinds": (kinds, kinds_src),
        "job_contracts": (contracts, contracts_src),
        "movements": ((movements, changing), move_src),
        "modules": (modules, modules_src),
        "battery": (battery, battery_src),
        "machines": (machines, machines_src),
        "switches": (switches, switches_src),
        "zone": (zone, zone_src),
        "run": run,
        "module_of": module_of,
    }
    return model


# ======================================================================================
# 跨出处断言（门禁的牙齿）
# ======================================================================================

def assert_all(model: dict) -> list[str]:
    """返回"每个断言跑没跑 + 结论"的人读行；失败一律进 PROBLEMS。"""
    lines: list[str] = []
    battery: dict = model["battery"][0]
    curation: dict[str, str] = battery["curation"]
    run: list[dict] = model["run"]
    modules: dict = model["modules"][0]

    # ---- A7 人口下限（防空集真）----
    pop = {
        "goal_actions": len(model["goal_actions"][0]),
        "job_kinds": len(model["job_kinds"][0]),
        "movements": len(model["movements"][0][0]),
        "modules": len(modules),
        "battery_steps": len(curation),
        "machine_rows": len(model["machines"][0]),
        "optional_switches": len(model["switches"][0]["optional"]),
        "bottom_lines": len(model["switches"][0]["bottom"]),
        "zone_verdicts": len(model["zone"][0]["Verdict"]),
        "zone_acts": len(model["zone"][0]["Act"]),
    }
    for key, floor in POP_FLOOR.items():
        got = pop[key]
        if got < floor:
            fail(f"[人口不足] {key}: {got} < 下限 {floor} ⇒ 解析崩塌或事实被删空（空集真不许过）")
    lines.append(f"A7 人口下限：{len(POP_FLOOR)} 项全部 ≥ 下限（{', '.join(f'{k}={pop[k]}' for k in POP_FLOOR)}）")

    # ---- A1 步声明 ↔ CURATION（双向；步一级**不需要**豁免表，见文件头的说明）----
    declared = [s["name"] for s in run]
    declared_set, curation_set = set(declared), set(curation)
    dup = sorted({n for n in declared if declared.count(n) > 1})
    if dup:
        fail(f"[A1] 步声明里有重名 {dup}（同名两步会让判决行/台账指代不清）")
    missing_in_decl = sorted(curation_set - declared_set)
    missing_in_cur = sorted(declared_set - curation_set)
    if missing_in_decl:
        fail(f"[A1] CURATION 登记了、但代码里**没有**这些步的声明：{missing_in_decl}（= 运行期 `phantom=[…]`）")
    if missing_in_cur:
        fail(f"[A1] 代码声明了这些步、CURATION **没登记**：{missing_in_cur}"
             f" ⇒ 要么补 CURATION，要么它就不该在电池里（= 运行期 `unclassified=[…]`）")
    lines.append(f"A1 步声明 ↔ CURATION：{len(declared)} 个声明 = {len(curation_set)} 个登记，双向无差")

    # ---- A2 模块档位声明 ↔ CURATION 档位（同一个事实今天有两个家）----
    mism = [(s["name"], s["module"], s["profile_decl"], curation[s["name"]])
            for s in run if s["profile_decl"] and s["name"] in curation
            and s["profile_decl"] != curation[s["name"]]]
    for name, mod, a, b in mism:
        fail(f"[A2] {mod} 把 `{name}` 声明成 {a}，而 CURATION 说 {b} ⇒ 同一个档位有两个家，"
             f"`Phase 1b` 落地时会把档位悄悄改掉")
    lines.append(f"A2 模块档位声明 ↔ CURATION 档位：{sum(1 for s in run if s['profile_decl'])} 处声明 0 处不一致")

    # ---- A3 模块注册表 ↔ 电池成员（缺席者必须带理由）----
    in_battery = {p for k, p in battery["order"] if k == "module"}
    absent = sorted(set(modules) - in_battery)
    unknown_absent = [c for c in absent if c not in MODULE_EXEMPT]
    stale_mod_exempt = [c for c in MODULE_EXEMPT if c not in absent]
    if unknown_absent:
        fail(f"[A3] 这些模块在注册表里、但电池不跑、也没在 MODULE_EXEMPT 里写明理由：{unknown_absent}")
    if stale_mod_exempt:
        fail(f"[A3] MODULE_EXEMPT 里有**过期豁免**（它其实已经进电池了）：{stale_mod_exempt}")
    lines.append(f"A3 模块注册表 ↔ 电池成员：{len(modules)} 个模块，电池跑 {len(in_battery)} 个 + 豁免 {len(MODULE_EXEMPT)} 个")

    # ---- A4 Kind ↔ 契约表（现有门禁只查 Kind→表 单向）----
    kinds = model["job_kinds"][0]
    contracts: dict = model["job_contracts"][0]
    no_contract = [k for k in kinds if k not in contracts]
    orphan = sorted(set(contracts) - set(kinds))
    if no_contract:
        fail(f"[A4] 这些 kind 没有契约行：{no_contract}（成功判据必须能与世界事实对账）")
    if orphan:
        fail(f"[A4] 契约表里有**不属于任何 kind** 的行：{orphan}（反向：表比枚举多）")
    lines.append(f"A4 JobRequest.Kind ↔ JobKindContract：{len(kinds)} 个 kind 与表双向一致")

    # ---- A5 MovementType.changesWorld() 名单 ⊆ 常量 ----
    movements, changing = model["movements"][0]
    bogus = sorted(changing - set(movements))
    if bogus:
        fail(f"[A5] changesWorld() 点到了不存在的 Movement 常量：{bogus}")
    if not changing:
        fail("[A5] changesWorld() 一个名字都没解析出来（写入口径的单一出处不能是空集）")
    lines.append(f"A5 MovementType ↔ changesWorld()：{len(changing)}/{len(movements)} 个改世界，名单全在常量里")

    # ---- A6 风险开关：可选表 ↔ KNOWN（解析自检；该规则的强门禁在 risk-surface.py）----
    sw = model["switches"][0]
    if set(sw["optional"]) != set(sw["known"]):
        fail(f"[A6] RiskSwitches.OPTIONAL 的键 {sorted(sw['optional'])} 与 KNOWN {sorted(sw['known'])} 不一致"
             f"（本解析器或 shape 变了；强门禁见 tools/risk-surface.py）")
    lines.append(f"A6 玩家开关：可选 {len(sw['optional'])} 个 ↔ KNOWN {len(sw['known'])} 个一致；底线 {len(sw['bottom'])} 个")

    # ---- A9 手写文档**不许再自称清单/计数**（否则就是在生成物之外又留了一个会漂的家）----
    hand = ROOT / "docs" / "BATTERY_CURATION.md"
    if hand.is_file():
        bad = []
        for i, line in enumerate(hand.read_text(encoding="utf-8").splitlines(), 1):
            if re.match(r"^#{2,3}\s*\**\s*(BASELINE|MAIN|EXTRA|当前归属表)", line) and re.search(r"（\s*\d", line):
                bad.append(f"{i}: {line.strip()[:70]}")
        for b in bad:
            fail(f"[A9] docs/BATTERY_CURATION.md 的手写小节又自称清单/计数了（{b}）"
                 f" ⇒ 逐条清单与项数只许在 `docs/CAPABILITY_LIST.md`（生成物）")
        lines.append(f"A9 手写文档不自称清单：docs/BATTERY_CURATION.md 的小节标题无计数（0 处）")
    return lines


# ======================================================================================
# 渲染文档
# ======================================================================================

def render(model: dict) -> str:
    battery: dict = model["battery"][0]
    curation: dict[str, str] = battery["curation"]
    run: list[dict] = model["run"]
    modules: dict = model["modules"][0]
    contracts: dict = model["job_contracts"][0]
    movements, changing = model["movements"][0]
    sw = model["switches"][0]
    zone = model["zone"][0]
    machines: list[dict] = model["machines"][0]

    core = [s for s in run if curation.get(s["name"]) != "EXTRA"]
    core_no = {s["name"]: i + 1 for i, s in enumerate(core)}
    counts = {t: sum(1 for v in curation.values() if v == t) for t in ("BASELINE", "MAIN", "EXTRA")}

    L: list[str] = []
    A = L.append
    A("# Alice 能力清单（**生成物 —— 禁手改**）")
    A("")
    A("> `B3` / `Q-22`（`survey/29 §3.8⑥`：把「它知道的自己」做成**从代码生成的表**，"
      "而不是记忆 —— 「它以为的自己」和「真实的自己」结构上不可能分叉）。")
    A(f"> 生成器 = `tools/capability-list.py`；门禁 = `tools/check-capability-list.sh`（已挂 `tools/check-all.sh`）。")
    A("> **手改本文件没有意义**：`--check` 会重新生成并与磁盘逐字节比对，不一致即**构建红**。")
    A("> 本文件**不含时间戳**（确定性输出 ⇒ 没改东西时 `git diff` 是空的）。")
    A("")
    A("**怎么读**：每一节 = 一个**已有单一出处**的人读视图；清单与代码不一致时**以代码为准**。"
      "「证据」列给出该条目的出处方法，便于回溯。")
    A("")
    A("## §0 总览")
    A("")
    A("| 面 | 数 | 节 |")
    A("|---|---|---|")
    A(f"| 目标级动作（它**能说出**什么） | {len(model['goal_actions'][0])} | §1 |")
    A(f"| 作业种类（它**能接什么活**） | {len(model['job_kinds'][0])} | §2 |")
    A(f"| 移动原语（它**能怎么动**） | {len(movements)}（其中改世界 {len(changing)}） | §3 |")
    A(f"| 自检模块 | {len(modules)} | §4.1 |")
    A(f"| 自检步（能力清单用它证明自己） | {len(curation)} = BASELINE {counts['BASELINE']} + MAIN {counts['MAIN']} "
      f"+ EXTRA {counts['EXTRA']}（**CORE 实跑 {len(core)}**） | §4.2 |")
    A(f"| 机器类型（上游已登记） | {len(machines)} 行 | §5 |")
    A(f"| 玩家能调的开关 | {len(sw['optional'])} | §6 |")
    A(f"| 底线（**任何玩家入口都不许出现**） | {len(sw['bottom'])} | §7 |")
    A(f"| 区块授权判定 | Verdict {len(zone['Verdict'])} × Act {len(zone['Act'])} | §7 |")
    A("")
    A("---")
    A("")
    A("## §1 目标级：它**能说出**什么（`GoalAction` 白名单）")
    A("")
    A(f"出处：`{model['goal_actions'][1]}`。它**只允许**说这 {len(model['goal_actions'][0])} 个动作；"
      "词表与 prompt 文本的一致性由 `tools/check-goal-vocabulary.sh` 断言（集合相等，多一个少一个都红）。")
    A("")
    A("| 动作 |")
    A("|---|")
    for a in model["goal_actions"][0]:
        A(f"| `{a}` |")
    A("")
    A("> 纪律（`survey/29 §3.8⑦`）：**最强的限制不是限制它能「说」什么，而是限制它能「说得出」什么** ——"
      "所以这张表本身就是一道限制。")
    A("")
    A("## §2 作业级：它**能接什么活**（`JobRequest.Kind` × 契约）")
    A("")
    A(f"出处：`{model['job_kinds'][1]}` + `{model['job_contracts'][1]}`。"
      "每个 kind 的**成功判据必须能在世界里判**（`D-349`），且「读世界事实」的方法由门禁核对真的存在。")
    A("")
    A("| kind | 成功判据（与什么对账） | 读世界事实 | 对不上时怎么办 |")
    A("|---|---|---|---|")
    for k in model["job_kinds"][0]:
        if k in contracts:
            c, q, o = contracts[k]
            A(f"| `{k}` | {c} | `{q}` | {o} |")
        else:
            A(f"| `{k}` | ⚠️ **缺契约行** | — | — |")
    A("")
    A("## §3 移动级：它**能怎么动**（`MovementType`）")
    A("")
    A(f"出处：`{model['movements'][1]}`。**改世界**的那一族 = 规划期写入信封的唯一口径"
      "（`D-241`：`PathRequest.pureTraversal()` 与 `WriteEnvelopes` 都从这里取）。")
    A("")
    A("| Movement | 会改世界？ |")
    A("|---|---|")
    for mv in movements:
        A(f"| `{mv}` | {'**是**（需显式授权 + 预算）' if mv in changing else '否（纯通行）'} |")
    A("")
    A("## §4 自证级：它**能证明自己什么**（自检模块 × 电池步）")
    A("")
    A(f"出处：`{model['modules'][1]}` + `{model['battery'][1]}`。")
    A("")
    A(f"### §4.1 自检模块（{len(modules)} 个）")
    A("")
    A("| 模块 id | 标题 | 期望判决 | 步数 | 电池内？ |")
    A("|---|---|---|---|---|")
    in_battery = {p for kind, p in battery["order"] if kind == "module"}
    for cls, mod in modules.items():
        yes = "✅" if cls in in_battery else f"❌（豁免：{MODULE_EXEMPT.get(cls, '**未登记理由**')}）"
        A(f"| `{mod['id']}` | {mod['title']} | `{mod['expected']}` | {len(mod['steps'])} | {yes} |")
    A("")
    A(f"### §4.2 电池步（{len(curation)} 步；`#` = CORE 运行序，`—` = 只在 FULL 跑）")
    A("")
    A("| # | 步名 | 档位 | 来源 |")
    A("|---|---|---|---|")
    for s in run:
        tier = curation.get(s["name"])
        if tier is None:
            continue
        no = core_no.get(s["name"], "—")
        A(f"| {no} | `{s['name']}` | {tier} | {s['module']} |")
    A("")
    A("> 档位语义（`docs/BATTERY_CURATION.md` §1）：`BASELINE` = 坏了就不能信任 bot 的任何动作；"
      "`MAIN` = 当前主线；`EXTRA` = 已验收/与主线无关/贵 ⇒ 默认不跑（`/alice battery full` 才跑）。")
    A("> **CORE = BASELINE + MAIN = "
      f"{counts['BASELINE'] + counts['MAIN']} 步**。")
    A("")
    A("## §5 机器级：它**能运营什么机器**（`MachineMap`）")
    A("")
    A(f"出处：`{model['machines'][1]}`（**新鲜度由 `tools/check-machine-map.sh` 门禁**：Java 表 ↔ CSV 逐字段一致，"
      "并用 `javap` 读上游 jar 的注册名做**双向覆盖**断言）。这里只给汇总与人读指针。")
    A("")
    namespaces: dict[str, int] = {}
    for r in machines:
        tid = (r.get("type_id") or "").strip()
        ns = tid.split(":", 1)[0] if ":" in tid else "(无命名空间)"
        namespaces[ns] = namespaces.get(ns, 0) + 1
    A("| 命名空间 | 已登记类型数 |")
    A("|---|---|")
    for ns in sorted(namespaces):
        A(f"| `{ns}` | {namespaces[ns]} |")
    A("")
    A(f"共 **{len(machines)}** 行；逐行明细 = `docs/MACHINE_MAP.csv`（生成物）。")
    A("")
    A("## §6 开关面：玩家**能调什么**（`RiskSwitches.KNOWN`）")
    A("")
    A(f"出处：`{model['switches'][1]}`。**判定准则**：默认值就是现状、且打开**只会收紧不会放宽**"
      "（⇒ 玩家调不坏安全）。")
    A("")
    A("| 开关 | 为什么它可以是可选的 |")
    A("|---|---|")
    for k, why in sw["optional"].items():
        A(f"| `{k.lower()}` | {why} |")
    A("")
    A("> ⚠️ 名字的**常量名**在代码里是 `" + "` / `".join(sorted(sw["optional"])) + "`。")
    A("")
    A("## §7 不能做什么（底线 + 授权面）")
    A("")
    A("### §7.1 底线（**任何玩家可写入口都不许出现**）")
    A("")
    A(f"出处：`{model['switches'][1]}` 的 `BOTTOM_LINES`。门禁 `tools/risk-surface.py`（`B2`）断言这些名字"
      "不出现在命令/配置面，且与可选开关不相交。")
    A("")
    A("| 底线 | 谁在强制它 |")
    A("|---|---|")
    for k, why in sw["bottom"].items():
        A(f"| `{k}` | {why} |")
    A("")
    A("### §7.2 区块授权面（世界写入为什么会被拒）")
    A("")
    A(f"出处：`{model['zone'][1]}`。判定三态 + 两种动作；授权/预算/账本的完整清单 = `docs/authz/OVERVIEW.md`"
      "（生成物，出处 = `docs/authz/AUTHZ_REGISTRY.csv`）。")
    A("")
    A("| Verdict | 含义 |")
    A("|---|---|")
    meaning = {
        "NOT_GATED": "该区块**未认领** ⇒ 本判据不适用（野外）",
        "ALLOW": "有任务区覆盖 + 等级够 ⇒ 放行（仍受授权/预算/账本约束）",
        "DENY": "拒绝（带码）",
    }
    for v in zone["Verdict"]:
        A(f"| `{v}` | {meaning.get(v, '—')} |")
    A("")
    A("| Act |")
    A("|---|")
    for a in zone["Act"]:
        A(f"| `{a}` |")
    A("")
    A("## §8 门禁在断言什么（**跨出处**的双向；「生成的文档 == 生成的文档」是同义反复，不算判据）")
    A("")
    A("| # | 断言 |")
    A("|---|---|")
    A("| A1 | 步声明（模块 `CheckStep` ∪ 电池内联 `step(...)`）**↔** `CURATION` 键（双向，无豁免） |")
    A("| A2 | 模块 `CheckProfile` **↔** `CURATION` 档位（同一个档位今天有**两个家**） |")
    A("| A3 | `CheckModules.ALL` **↔** 电池成员（缺席者必须在 `MODULE_EXEMPT` 里带理由） |")
    A("| A4 | `JobRequest.Kind` **↔** `JobKindContract` 表（现有门禁只查单向） |")
    A("| A5 | `MovementType` 常量 **↔** `changesWorld()` 名单 |")
    A("| A6 | `RiskSwitches.OPTIONAL` 键 **↔** `KNOWN`（解析自检；强门禁在 `risk-surface.py`） |")
    A("| A7 | 人口下限（防空集真）+ 每节的**独立计数**交叉核对（**解析不到就响亮失败**） |")
    A("| A8 | 本文件**不陈旧**（重新生成 ⇒ 逐字节相同） |")
    A("| A9 | `docs/BATTERY_CURATION.md` 的手写小节**不许再自称清单/计数**（它只留「为什么」） |")
    A("")
    A("> 之前这些一致性里，只有 A1 被断言过 —— 而且是在**运行期**（`RegressionBatteryTask.prepareSteps` 自校验，"
      "要起一次服务端 ≈20–26 s + 跑完预算才响）。A2/A3/A4 的反向**此前没有任何断言**。")
    A("")
    while L and L[-1] == "":
        L.pop()
    L.append("")
    return "\n".join(L)


# ======================================================================================
# main
# ======================================================================================

def main() -> int:
    ap = argparse.ArgumentParser(description="Alice 能力清单（生成物）")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--write", action="store_true", help="重新生成 docs/CAPABILITY_LIST.md")
    g.add_argument("--check", action="store_true", help="门禁：不陈旧 + 跨出处断言")
    args = ap.parse_args()

    try:
        model = build_model()
        assertions = assert_all(model)
        text = render(model)
    except Exception as exc:   # noqa: BLE001 —— 门禁**不许**以"裸异常"的形式失败（那是崩溃，不是判决）
        print(f"CAPABILITY_LIST_CHECK_RESULT FAIL: 解析器异常（{type(exc).__name__}: {exc}）")
        print("  [能力清单] 这属于「解析不到就响亮失败」：出处文件形状变了 ⇒ 请同步 tools/capability-list.py")
        for p in PROBLEMS:
            print(f"  [能力清单] {p}")
        return 1

    if args.check:
        if not DOC.is_file():
            fail(f"{DOC.relative_to(ROOT)} 不存在（跑 `python3 tools/capability-list.py --write`）")
        else:
            on_disk = DOC.read_text(encoding="utf-8")
            if on_disk != text:
                fail(f"{DOC.relative_to(ROOT)} **陈旧**：与重新生成的结果逐字节不同 ⇒ "
                     f"跑 `python3 tools/capability-list.py --write` 后提交")

    if PROBLEMS:
        print("CAPABILITY_LIST_CHECK_RESULT FAIL: " + f"{len(PROBLEMS)} 条")
        for p in PROBLEMS:
            print(f"  [能力清单] {p}")
        return 1

    if args.check:
        battery = model["battery"][0]
        curation = battery["curation"]
        core = sum(1 for v in curation.values() if v != "EXTRA")
        for line in assertions:
            print(f"  · {line}")
        print(f"CAPABILITY_LIST_CHECK_RESULT PASS: 步 {len(curation)}（CORE {core}）· 模块 {len(model['modules'][0])} · "
              f"目标动作 {len(model['goal_actions'][0])} · kind {len(model['job_kinds'][0])} · 移动 {len(model['movements'][0][0])} · "
              f"机器 {len(model['machines'][0])} 行 · 开关 {len(model['switches'][0]['optional'])}+{len(model['switches'][0]['bottom'])} · "
              f"跨出处断言 {len(assertions)} 族全绿 · 文档不陈旧")
        return 0

    if args.write:
        DOC.write_text(text, encoding="utf-8")
        print(f"已写入 {DOC.relative_to(ROOT)}（{len(text.splitlines())} 行）")
        return 0

    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
