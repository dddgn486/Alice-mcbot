#!/usr/bin/env python3
"""结构门禁：**"拆了它会让自己掉下去"的判据必须唯一、且用在动作那一刻**。

## 为什么这条规则该存在

`D-399` C 的判据（`pos == 脚位下一格 && !canWalkOn(pos)`）已经存在很久，但 `D-472`（2026-09-27）
取证发现它有**两个可观测的失效形态**，而且**两个都真的发生了**：

1. **时机错** —— 判据只在 `RestoreScopeTask.pickNext()`（**取件那一刻**）求值，而危险是
   `APPROACH`「站上去」**之后**才产生的（`D-406` §三 早已定过这个根因，只是没排上）。
2. **旁路** —— `D-365` 的"就地挖"（`MineBlockRunner.mineInPlace`）**跳过 Movement 层**
   （Baritone `MovementDownward.java:61` 在**代价值**里就要求 `canWalkOn(x, y-2, z)`），
   于是 Alice 特有的这条优化成了一条**绕过内核安全前置**的通道。

实测后果（`exec_support`，CORE）：bot 站上自己刚放的支撑块 → 就地拆脚下那格 → **掉进 12 格深坑**
（脚位 y 64 → 59），而旧判据（下面空气 + `restoredBlocks≥1` + `scaffoldLeft==0`）**恰好被这次掉落满足**
⇒ **假绿**。修法（`D-472`）= 判据收敛成**唯一出处** `MovementHelper.underfootUnsafe`，
并在**两个**动作点求值。

⚠️ 为什么必须门禁化：下一个会话做 `step 5b` / 挖矿线 `L1` 化时**都要动 `MineBlockRunner`**
（`task/mining/*` 与 `action/*` 都在改动面上），而"删掉一行 `if`"在 diff 里**看不出来是安全问题**
（行为夹具只在 CORE 轮里跑，且这条通道的现场是**偶发**的：`D-472` 前 4 次 core 里 3 次走旁路）。

## 断言（任一不成立 ⇒ 非零退出）

| # | 断言 | 咬什么 |
|---|---|---|
| **A** | `pathing/MovementHelper.java` 里**声明形**存在 `underfootUnsafe(`，且**全仓只有这一处定义** | 判据被复制成第二套（`D-472` 前的两套口径：`blockPosition()` vs `footCell()`） |
| **B** | `action/mining/MineBlockRunner.java` 的 **`canMineInPlace()` 方法体**里调用了它 | 就地挖的旁路重新打开（= 本门禁存在的首要理由） |
| **C1** | `task/RestoreScopeTask.java` 的 **`pickNext()` 方法体**里调用了它 | 取件闸被删 ⇒ 重新"站上去"（时机错） |
| **C2** | 同文件的 **`startSideBreak()` 方法体**里调用了它 | **动作那一刻**的判定被删 ⇒ 失败时归因退回笼统的 `side_break_failed`（`D-403` 的"如实归因"丢失） |
| **D** | 旧的重复形状 `pos.equals(footNow.below())` **全仓 0 处** | 第二套口径回来（A 的另一种失效形态） |
| **E** | 判据声明处必须**同时**出现 `footCell(` 与 `canWalkOn(` | 判据被换成"只看是否在脚下"（那会让**每次向下挖**都先走位，实测 2475 次 `mine_in_place` 里 129 次是脚下） |

## ⚠️ 本门禁**不**覆盖什么

- **不判物理/行为**：本门禁只做**静态形状**断言。真正"bot 有没有掉下去"由行为夹具负责 ——
  `mine_regression` 的 `exec_support`（`noFall` 断言）与 `restore_underfoot_safety` 的臂①/臂④。
- **不判 `RestoreScopeTask` 的编排对不对**（顺序、账本口径、掉落物）—— 那是行为夹具的事。
- **不判"有没有第三处该用而没用"**：新出现的破坏路径（例如新的 `Mine*Runner`）本门禁**看不见**。
  触发条件 = 又出现一次"bot 拆掉自己脚下的方块并掉下去"的实测。

## 红臂

`--selftest` 每次运行都跑：合成片段逐条打**单个**判据函数（本仓"臂打错地方"复发过 3 次，
所以臂不许跨判据）。另附**真树红臂**（人工跑，不进门禁；`D-472` 已实测记录）：

1. 删掉 `MineBlockRunner.canMineInPlace()` 里那一段 ⇒ 必须红在 **B**；
2. 删掉 `MovementHelper.underfootUnsafe` 的声明 ⇒ 必须红在 **A**（且 B/C/E 连带）；
3. 把 `RestoreScopeTask.pickNext()` 的判定改回"当场放弃" ⇒ 必须红在 **C1**。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"

#: 判据的**声明形**（⚠️ 判据必须写在**声明**上：本仓 `R1` 臂实测过"调用形判据被悬空调用点满足"的假绿）。
DECL_NEEDLE = "public static boolean underfootUnsafe("
#: 判据必须问的两个问题（缺一个就是另一套语义）。
SHAPE_NEEDLES = ("footCell(", "canWalkOn(")
#: `D-472` 之前的第二套口径（`RestoreScopeTask` 里内联的那份）。
DUPLICATE_SHAPE = "pos.equals(footNow.below())"

MOVEMENT_HELPER = "com/dddgn/alice/pathing/MovementHelper.java"
MINE_RUNNER = "com/dddgn/alice/action/mining/MineBlockRunner.java"
RESTORE = "com/dddgn/alice/task/RestoreScopeTask.java"

#: 动作点人口下限：`canMineInPlace` 1 + `pickNext` 1 + `startSideBreak` 1 = 3。
MIN_SITES = 3

_FILES = (MOVEMENT_HELPER, MINE_RUNNER, RESTORE)


def strip_comments(code: str) -> str:
    """去块注释/行注释（判据只看**代码**，不看注释里写的故事）。"""
    code = re.sub(r"/\*.*?\*/", " ", code, flags=re.S)
    return re.sub(r"//[^\n]*", " ", code)


def method_body(code: str, signature: str) -> str | None:
    """按签名子串取**方法体**（花括号配对）；找不到 ⇒ None。

    ⚠️ 必须取**方法体**而不是"整个文件里出现过"：`MineBlockRunner` 里 `mineInPlace` 的日志字符串
    也含 `underfootUnsafe`…（这正是"判据被别的形状满足"那族陷阱）。
    """
    start = code.find(signature)
    if start < 0:
        return None
    brace = code.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[brace:i + 1]
    return None


def check_a_unique_declaration(files: dict[str, str]) -> list[str]:
    problems: list[str] = []
    text = strip_comments(files.get(MOVEMENT_HELPER, ""))
    if DECL_NEEDLE not in text:
        problems.append(f"A: `{MOVEMENT_HELPER}` 里找不到**声明形** `{DECL_NEEDLE}`"
                        f" ⇒ 判据被删/改名/换成调用形（判据必须写在声明上）")
    others = [rel for rel, raw in files.items()
              if rel != MOVEMENT_HELPER and DECL_NEEDLE in strip_comments(raw)]
    if others:
        problems.append(f"A: `underfootUnsafe` 在**别处**也被定义了：{sorted(others)}"
                        f" ⇒ 判据被复制成第二套（`D-472` 前正是两套口径："
                        f"`blockPosition().below()` vs `footCell().below()`）")
    return problems


def check_b_mine_runner(files: dict[str, str]) -> list[str]:
    body = method_body(strip_comments(files.get(MINE_RUNNER, "")), "private boolean canMineInPlace()")
    if body is None:
        return [f"B: `{MINE_RUNNER}` 里找不到 `canMineInPlace()` 方法体 ⇒ 就地挖的入口被改名/搬走，"
                f"本门禁要跟着改"]
    if "underfootUnsafe(" not in body:
        return [f"B: `canMineInPlace()` **不再**调用 `underfootUnsafe(` ⇒ `D-365` 的\"就地挖\""
                f"重新变成绕过内核安全前置的旁路（Baritone `MovementDownward.java:61` 的"
                f"`canWalkOn(x, y-2, z)` 同形前置被摘掉）"]
    return []


def check_c_restore_sites(files: dict[str, str]) -> list[str]:
    code = strip_comments(files.get(RESTORE, ""))
    problems: list[str] = []
    for signature, tag, why in (
            ("private Task.Status pickNext()", "C1",
             "取件闸被删 ⇒ 重新\"站上去\"（`D-406` §三 的时机错）"),
            ("private Task.Status startSideBreak()", "C2",
             "**动作那一刻**的判定被删 ⇒ 失败时归因退回笼统的 `side_break_failed`（`D-403` 要求如实归因）"),
    ):
        body = method_body(code, signature)
        if body is None:
            problems.append(f"{tag}: `{RESTORE}` 里找不到 `{signature}` ⇒ 方法被改名/搬走，本门禁要跟着改")
        elif "underfootUnsafe(" not in body:
            problems.append(f"{tag}: `{signature}` **不再**调用 `underfootUnsafe(` ⇒ {why}")
    return problems


def check_d_no_duplicate_shape(files: dict[str, str]) -> list[str]:
    hits = [rel for rel, raw in files.items() if DUPLICATE_SHAPE in strip_comments(raw)]
    if hits:
        return [f"D: `{DUPLICATE_SHAPE}` 又出现了（{sorted(hits)}）⇒ `D-472` 前的第二套口径回来了"
                f"（口径必须唯一，否则两处会漂移）"]
    return []


def check_e_shape(files: dict[str, str]) -> list[str]:
    """判据本体必须**同时**问"是不是脚下"与"拆完有没有落脚面"。"""
    code = strip_comments(files.get(MOVEMENT_HELPER, ""))
    body = method_body(code, "public static boolean underfootUnsafe(")
    if body is None:
        return []   # A 已经报过
    missing = [n for n in SHAPE_NEEDLES if n not in body]
    if missing:
        return [f"E: `underfootUnsafe` 的方法体里缺少 {missing} ⇒ 判据被换成\"只看是否在脚下\"；"
                f"那会让**每次向下挖**都被迫先走位（实测 2475 次 `mine_in_place` 里 129 次是脚下）"]
    return []


def check_population(files: dict[str, str]) -> list[str]:
    sites = 0
    for rel, signature in ((MINE_RUNNER, "private boolean canMineInPlace()"),
                           (RESTORE, "private Task.Status pickNext()"),
                           (RESTORE, "private Task.Status startSideBreak()")):
        body = method_body(strip_comments(files.get(rel, "")), signature)
        if body and "underfootUnsafe(" in body:
            sites += 1
    if sites < MIN_SITES:
        return [f"人口下限：动作点只剩 {sites} 处（下限 {MIN_SITES}）⇒ 判据形同虚设"]
    return []


CHECKS = (check_a_unique_declaration, check_b_mine_runner, check_c_restore_sites,
          check_d_no_duplicate_shape, check_e_shape)


def inspect(files: dict[str, str]) -> list[str]:
    """纯函数：`{相对 SRC 的 posix 路径: 内容}` ⇒ 问题列表（空 = 绿）。"""
    problems: list[str] = []
    for fn in CHECKS:
        problems.extend(fn(files))
    problems.extend(check_population(files))
    return problems


def scan() -> dict[str, str]:
    files: dict[str, str] = {}
    for rel in _FILES:
        path = SRC / rel
        if path.exists():
            files[rel] = path.read_text(encoding="utf-8", errors="replace")
    return files


# ==================== 红臂（每次运行都跑；每臂只打一条判据） ====================

_HELPER_OK = ("public static boolean underfootUnsafe(ServerLevel level, "
              "net.minecraft.server.level.ServerPlayer bot, BlockPos target) { "
              "return target.equals(footCell(level, bot).below()) && !canWalkOn(level, target); }")
_RUNNER_OK = "private boolean canMineInPlace() { if (x) { return false; } if (underfootUnsafe(level, bot, target)) { return false; } return y; }"
_RESTORE_OK = ("private Task.Status pickNext() { if (underfootUnsafe(level, bot, pos)) { return startSideBreak(); } return z; } "
               "private Task.Status startSideBreak() { underfootRefused = underfootUnsafe(level, bot, current); return w; }")


def _green() -> dict[str, str]:
    return {MOVEMENT_HELPER: _HELPER_OK, MINE_RUNNER: _RUNNER_OK, RESTORE: _RESTORE_OK}


SELFTEST_CASES: list[tuple[str, dict[str, str], bool]] = (
    ("绿：判据唯一 + 三个动作点都在", _green(), False),
    ("红（A）：判据声明被删", {MOVEMENT_HELPER: "class MovementHelper { }", MINE_RUNNER: _RUNNER_OK,
                        RESTORE: _RESTORE_OK}, True),
    ("红（A）：判据在别处被复制成第二套",
     {**_green(), "com/dddgn/alice/task/SomeNewTask.java":
      "class SomeNewTask { public static boolean underfootUnsafe(ServerLevel level, "
      "net.minecraft.server.level.ServerPlayer bot, BlockPos target) { return false; } }"}, True),
    ("红（B）：`canMineInPlace()` 不再问判据（就地挖旁路重开）",
     {**_green(), MINE_RUNNER: "private boolean canMineInPlace() { if (x) { return false; } return y; }"}, True),
    ("红（C1）：`pickNext()` 不再问判据（时机错回来）",
     {**_green(), RESTORE: "private Task.Status pickNext() { return z; } "
                           "private Task.Status startSideBreak() { underfootRefused = underfootUnsafe(level, bot, current); return w; }"}, True),
    ("红（C2）：`startSideBreak()` 不再问判据（归因丢失）",
     {**_green(), RESTORE: "private Task.Status pickNext() { if (underfootUnsafe(level, bot, pos)) { return startSideBreak(); } return z; } "
                           "private Task.Status startSideBreak() { return w; }"}, True),
    ("红（D）：旧的内联重复口径回来了",
     {**_green(), RESTORE: _RESTORE_OK + " void old() { if (pos.equals(footNow.below())) { } }"}, True),
    ("红（E）：判据只看\"是否在脚下\"（缺 `canWalkOn`）",
     {**_green(), MOVEMENT_HELPER:
      "public static boolean underfootUnsafe(ServerLevel level, "
      "net.minecraft.server.level.ServerPlayer bot, BlockPos target) { "
      "return target.equals(footCell(level, bot).below()); }"}, True),
)


def run_selftest() -> list[str]:
    problems: list[str] = []
    for label, files, expect_red in SELFTEST_CASES:
        found = inspect(files)
        is_red = bool(found)
        if is_red != expect_red:
            problems.append(f"红臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、"
                            f"实得{'红' if is_red else '绿'}（{found}）")
    return problems


def main() -> int:
    problems = [f"[红臂] {p}" for p in run_selftest()]
    files = scan()
    problems.extend(inspect(files))

    if problems:
        print("UNDERFOOT_SAFETY_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        print("  ⇒ 依据：`D-399` C（判据本身）+ `D-406` §三（时机）+ `D-472`（唯一出处 + 三个动作点）")
        return 1

    print("UNDERFOOT_SAFETY_RESULT PASS: 判据唯一（`MovementHelper.underfootUnsafe`）· "
          f"动作点 3 处（`MineBlockRunner.canMineInPlace` / `RestoreScopeTask.pickNext` / "
          f"`RestoreScopeTask.startSideBreak`）· 旧口径 {DUPLICATE_SHAPE} 0 处")
    print(f"  红臂 {len(SELFTEST_CASES)}/{len(SELFTEST_CASES)} · "
          f"⚠️ 本门禁只做静态形状断言，「行为」由 `mine_regression`(exec_support noFall) 与 "
          f"`restore_underfoot_safety`(臂①/臂④) 负责")
    return 0


if __name__ == "__main__":
    sys.exit(main())
