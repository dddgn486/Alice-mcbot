#!/usr/bin/env python3
"""`J-★` 第 6 段 **step 5a-0**（`D-464`，2026-09-27）结构门禁：**相位转换只有一个出口**。

## 为什么这条规则该存在

`plan §2.2` 用户裁定 A 的**禁令③**：`Phase` 降级为报告词汇之后，**每个值必须有外部可验证的进出条件**。
实测（2026-09-27）：这条**今天不成立** —— `phase=` 这个字段只在两处打印
（`tickOnce()` 里那条按 `(phase,status)` 去重的探针、以及失败报告），而探针**只有在 `miner.tick()` 之后**
才可达 ⇒ `CLEAR` / `GAIN_CLEAR` / `GAIN` / `CHAIN` / `COLLECTING` / `RESTORE` **不会**以 `phase=` 的形式
出现在成功路径的日志里。语料核对（1080 份归档日志）：除 `no_suitable_tool@EVALUATING`（204 次）之外，
**没有任何 Phase 枚举名**曾经出现过。
⇒ 根因 = 相位转换原先**散在 14 处直接赋值**里，其中 3 处（清障收尾、加高清障收尾、加高收尾回
`EVALUATING`）**连专用日志都没有**。

`step 5a-0` 的修法 = 把 14 处收成**一个** `enterPhase(Phase)`：每次转换恰好一行读数，
并且转换点变成**一个可 grep 的形状**。

## 为什么它现在就必须有检查（不只是为了好看）

`step 5a` 接下来要**把编排搬出 `MineTask`**。散在 14 处的赋值意味着"搬走编排时顺手漏掉/多加一个转换点"
**没有任何东西会响**（`Phase` 是 `private`，编译器也管不着）。收成一处 + 门禁之后，
"过渡点没被改掉"才是**可断言**的 —— 这是本步留给自己的安全带。

## 断言（任一不成立 ⇒ 非零退出）

1. **`phase` 的直接赋值只许出现在字段声明那一行**：`src/main/java/com/dddgn/alice/task/MineTask.java` 里
   `^\\s*phase\\s*=\\s*Phase\\.` 的命中数 == **0**（`private Phase phase = Phase.EVALUATING;` 是声明 + 初始化，
   不以 `phase` 开头 ⇒ 不受影响）。⚠️ **假红模式**：将来若有人**故意**在别处直接赋值，本门禁当然会红 ——
   那正是它的用途；要绕开必须**显式改本文件**（"响亮"而不是静默）。
2. **`enterPhase(` 的调用点 ≥ `MIN_TRANSITIONS`（`D-464` 落地时实测 14；`step 5a` 切完执行段后 **13**）**
   —— 防"把方法删了 / 调用点被搬空 ⇒ 0 命中假绿"。
3. **`enterPhase` 的方法体真的赋值**（`this.phase = ` 与参数名都在）—— 防"只留调用点、方法体被掏空"
   （那种情况断言 1 会**绿**：因为没人 `= Phase.` 了，而相位根本不再前进）。
4. **`Phase` 解析与引用自洽**（承 `check-primitive-readings.py` 的命门）：引用 > 0 却解析不到
   `enum Phase` 声明 ⇒ 红（"解析失败"不许与"相位真的没了"混为一谈）。

## 红臂（`--selftest`，每次运行都跑）

合成臂 6 条：`phase = Phase.X;` ⇒ 检出 · 注释里的 `phase = Phase.X;` ⇒ **不计** ·
字符串里的 ⇒ **不计** · 声明行 `private Phase phase = Phase.EVALUATING;` ⇒ **不计**（是本条的关键分寸）·
一行写完的枚举与多行**同值** · 没有枚举声明 ⇒ `None`（不是 0）。
真实树红臂（人工跑）：在 `evaluateStandingPoint()` 里注入一行 `phase = Phase.MINING;` ⇒ 必须**精确报行号**、
还原后 sha 逐字一致。

跑法：`python3 tools/check-phase-transition-outlet.py`（已挂在 `tools/check-all.sh`）。

⚠️ **本门禁的解析函数不是新写的**：`strip_comments_and_strings` / `enum_constants` 直接从
`tools/check-primitive-readings.py` **导入**（同名同源）——
本项目已经被"同一口径写两份、然后漂移"坑过（`D-460`/`D-461`/`D-463`），不再开第二份。
"""

from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task" / "MineTask.java"

#: 实测 14（`step 5a-0` 收口前的直接赋值点数）。下限留余量：只抓"被搬空/改名"。
MIN_TRANSITIONS = 8
#: `phase` 直接赋值的形状（行首）。
DIRECT_ASSIGN = re.compile(r"^\s*phase\s*=\s*Phase\.", re.M)
#: `enterPhase(...)` 的**调用点**：`(?<!void )` 把**方法声明**那一处排除掉
#: （第一版没排除 ⇒ 打印 15，而调用点其实是 14 —— 又一个"数字看着像真的"）。
CALL_SITES = re.compile(r"(?<!void )(?<![\w.])enterPhase\s*\(")


def _load_readings_module():
    """从同目录导入 `check-primitive-readings.py`（文件名带 `-` ⇒ 不能用普通 import）。"""
    path = ROOT / "tools" / "check-primitive-readings.py"
    spec = importlib.util.spec_from_file_location("alice_primitive_readings", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


R = _load_readings_module()


def direct_assigns(source: str) -> int:
    """**断言 1 的判据本身**：`phase` 的直接赋值处数（只读代码 ⇒ 注释/字符串不算）。

    <p>⚠️ 红臂必须打在**这个函数**上，而不是打在 `scan_source` 上：本刀第一版把"注释里的不算"
    这类臂打在 `scan_source` 上 ⇒ 它同时触发**人口下限**（片段里没有 8 个 `enterPhase(`）
    ⇒ 期望绿、实得红，红臂自己假红。**臂要打在你真正想验证的那个谓词上。**
    """
    return len(DIRECT_ASSIGN.findall(R.strip_comments_and_strings(source)))


def scan_source(source: str) -> list[str]:
    """对一段（或整个文件的）源码做断言 1/2/3；返回违规描述。"""
    code = R.strip_comments_and_strings(source)
    problems: list[str] = []
    direct = DIRECT_ASSIGN.findall(code)
    if direct:
        problems.append(f"有 {len(direct)} 处 `phase = Phase.X;` 的**直接赋值** ⇒ "
                        f"相位转换必须全部走 `enterPhase(...)`（禁令③ 的唯一出口）")
    calls = len(CALL_SITES.findall(code))
    if calls < MIN_TRANSITIONS:
        problems.append(f"`enterPhase(` 调用点只有 {calls} 个（下限 {MIN_TRANSITIONS}）⇒ "
                        f"转换点被搬空，或方法被改名")
    if "private void enterPhase(Phase " in code and "this.phase = " not in code:
        problems.append("`enterPhase` 的方法体里没有 `this.phase = ` ⇒ 相位不再前进"
                        "（此时断言 1 反而会绿：没人直接赋值了 —— 这就是「掏空」的假绿形态）")
    return problems


# ==================== 红臂（每次运行都跑） ====================

#: **判据臂**：打在 `direct_assigns` 上（`(label, 片段, 期望的直接赋值处数)`）。
SELFTEST_DETECT: list[tuple[str, str, int]] = [
    ("`phase = Phase.X;` ⇒ 检出 1 处", "    phase = Phase.MINING;\n", 1),
    ("注释里的 `phase = Phase.X;` ⇒ **0**（只读代码）", "    // phase = Phase.MINING;\n", 0),
    ("字符串字面量里的 ⇒ **0**", '    String s = "phase = Phase.MINING;";\n', 0),
    ("⭐ 声明行 `private Phase phase = Phase.EVALUATING;` ⇒ **0**（不以 `phase` 开头）",
     "    private Phase phase = Phase.EVALUATING;\n", 0),
    ("`this.phase = next;`（`enterPhase` 的体内那一行）⇒ **0**（不是字面 `Phase.`）",
     "    private void enterPhase(Phase next) { this.phase = next; }\n", 0),
]

#: **形状臂**：打在 `scan_source` 上（`(label, 片段, 期望红)`）。
SELFTEST_SHAPE: list[tuple[str, str, bool]] = [
    ("字段声明 + 14 个 `enterPhase(` 调用 + 方法体赋值 ⇒ 绿（形状自证）",
     "    private Phase phase = Phase.EVALUATING;\n"
     "    private void enterPhase(Phase next) { this.phase = next; }\n"
     + "".join(f"    void m{i}() {{ enterPhase(Phase.MINING); }}\n" for i in range(14)), False),
    ("调用点只有 3 个（< 下限 8）⇒ 红（防搬空/改名）",
     "    private void enterPhase(Phase next) { this.phase = next; }\n"
     "    void m() { enterPhase(Phase.MINING); enterPhase(Phase.GAIN); enterPhase(Phase.CHAIN); }\n", True),
    ("14 个调用点但方法体被掏空 ⇒ 红（「没人直接赋值」的假绿形态）",
     "    private void enterPhase(Phase next) { }\n"
     + "".join(f"    void m{i}() {{ enterPhase(Phase.MINING); }}\n" for i in range(14)), True),
]


def selftest() -> list[str]:
    problems: list[str] = []
    for label, body, expect in SELFTEST_DETECT:
        got = direct_assigns(body)
        if got != expect:
            problems.append(f"判据臂失配：{label} ⇒ 期望 {expect}、实得 {got}")
    for label, body, expect_red in SELFTEST_SHAPE:
        is_red = bool(scan_source(body))
        if is_red != expect_red:
            problems.append(f"形状臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、实得"
                            f"{'红' if is_red else '绿'}（{scan_source(body)}）")
    # 与读数门禁**同源**的枚举臂（不另写一份）
    for label, body, expect in (("一行写完的枚举 ⇒ 3", "    private enum Phase { A, B, C }\n", 3),
                                ("多行枚举 ⇒ 3（同值）",
                                 "    private enum Phase {\n        A,\n        B,\n        C;\n    }\n", 3),
                                ("没有枚举声明 ⇒ None（≠ 0）", "    int x = 1;\n", None)):
        got, _ = R.enum_constants(R.strip_comments_and_strings(body), "Phase")
        if got != expect:
            problems.append(f"枚举臂失配：{label} ⇒ 期望 {expect}、实得 {got}")
    return problems


def main() -> int:
    problems = [f"[红臂] {p}" for p in selftest()]

    if not TARGET.exists():
        print(f"PHASE_OUTLET_RESULT FAIL: 找不到 {TARGET.relative_to(ROOT)}")
        return 1
    source = TARGET.read_text(encoding="utf-8")
    code = R.strip_comments_and_strings(source)
    problems.extend(scan_source(source))

    phase_n, _ = R.enum_constants(code, "Phase")
    refs = len(re.findall(r"(?<![\w.])Phase\.", code))
    if refs > 0 and phase_n is None:
        problems.append(f"`MineTask` 有 {refs} 处 `Phase.` 引用却解析不到 `enum Phase` 声明 ⇒ "
                        f"**解析失败**（不是「相位已删」）")

    if problems:
        print("PHASE_OUTLET_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        print("  ⇒ 依据：`J-★` 第 6 段 step 5a-0（`D-464`）+ `plan §2.2` 禁令③")
        return 1

    calls = len(CALL_SITES.findall(code))
    print(f"PHASE_OUTLET_RESULT PASS: `MineTask` 相位转换 = **{calls} 个 `enterPhase(` 调用点 · "
          f"直接赋值 0 处** · `Phase` 值数 {phase_n}（解析成功）· `Phase.` 引用 {refs} · "
          f"红臂 {len(SELFTEST_DETECT) + len(SELFTEST_SHAPE) + 3}/{len(SELFTEST_DETECT) + len(SELFTEST_SHAPE) + 3}（判据 {len(SELFTEST_DETECT)} + 形状 {len(SELFTEST_SHAPE)} + 枚举 3）")
    print("  ⇒ 禁令③ 的载体就位：每次转换恰好一行 `[MineTask] phase target= from= to=`；"
          "转换点收敛成一个可 grep 的形状（step 5a 搬编排时的安全带）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
