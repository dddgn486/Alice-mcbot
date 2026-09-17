#!/usr/bin/env python3
"""自检夹具卫生检查（2026-09-14 落地的静态规则，只读）。

## 为什么需要它

`RegressionBatteryTask` 判定一步是否通过的口径是**唯一**的：

    skipped ? "SKIP" : ((status == Status.DONE && idempotent) ? "PASS" : "FAIL")
    （task/RegressionBatteryTask.java:558）

它**不看夹具自己打印的 `verdict=`**。所以夹具内部红了、却写死 `return Status.DONE` 时，
日志里会同时出现：

    [WritePolicy] SUMMARY … grants_semantics=FAIL … verdict=FAIL
    [Regression] SUMMARY … write_policy=PASS … (28/28) → PASS

——**假红被吞成静默绿**。2026-09-14 R1 实测踩到：`WritePolicyCheckTask` / `RecoverabilityCheckTask`
（前者照抄后者）是全仓唯二写死 `done ? Status.DONE : Status.RUNNING` 的夹具，
其余 20 个 `*CheckTask` 都规规矩矩 `failures.isEmpty() ? Status.DONE : Status.FAILED`。
规则落成静态检查，避免"下次再抄一份错模板"。

## 规则

* `R1`（硬）：**返回 `Status.DONE` 的方法，必须存在一条能产出 `Status.FAILED` 的路径**
  —— 即"终态能表达失败"（**存在性**检查：方法内直接 `return FAILED`，或经本文件内的方法调用
  传递到 `finish()` 这类判定方法；用调用图闭包判定，避免误伤 early-return 与 `finish()` 委托风格）。
* `R2`（硬）：**不允许**出现"一行 `return` 里同时含 `DONE` 与 `RUNNING`、却不含 `FAILED`"的形状
  —— 终态二选一里没有失败分支，内部失败必然被吞（实测踩过的 `done ? Status.DONE : Status.RUNNING`
  正是这个形状）。
* `R3`（信息性）：打印了 `SUMMARY` 却没有 `verdict=` 的夹具，列出来（便于人工决定是否补）。
* `R4`（硬，2026-09-17）：**同一个方法里不许既 `teleportTo(...)` 又 `check("premise_on_ground", ...)`**
  —— `teleportTo` 的**那一 tick**，`bot.onGround()` 读到的仍是**上一处**的状态（物理下一 tick 才重算）
  ⇒ 同一个错误读法给出**两种相反的假判决**，而且都实测发生过：
  ① **假绿**：上一处站着 ⇒ 读到 true ⇒ 前提"通过"，下一步真开菜单时 `MenuSession` 的 K-3 门硬拒
     `menu_not_settled`（`machine_station` 单跑红就是这个 ✗）；
  ② **假红**：上一处在空中/刚被传送 ⇒ 读到 false ⇒ 前提当场判红，而 bot 明明站得好好的
     （`machine_cycle` / `craft_machine` **只在模块化之后**红 ✗，旧电池靠"上一步恰好把 bot 留成站姿"蒙对）。
  正解：传送 tick 只 `record` 原始读数，落地判据用 `FixturePremise.settledOnGround(bot, phaseTicks)` 延后复核 ✓。

**口径诚实说明**：静态分析**无法**证明"终态确实读了 verdict"（R1 只证明"能表达失败"）。
真实缺陷形状由 R2 精确拦截；两者都过之后，仍以真机电池 + 代码评审为准。

退出码：R1/R2 有违例 = 1，否则 0。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_ROOTS = [ROOT / "src/main/java/com/dddgn/alice/task",
                ROOT / "src/main/java/com/dddgn/alice/job"]

# 自检夹具的命名形态（它们产出 SUMMARY、被电池当一步跑）
FIXTURE_NAME = re.compile(r"(Check|Probe)\w*Task\.java$")
METHOD_SIG = re.compile(
    r"^\s{4}(?:@\w+\s*)?(?:public|private|protected)\b.*?\b(\w+)\s*\([^;]*\)\s*\{\s*$")
RETURNS_FAILED = re.compile(r"\breturn\b[^;\n]*Status\.FAILED")
RETURNS_DONE = re.compile(r"\breturn\b[^;\n]*Status\.DONE")


def fixtures() -> list[Path]:
    found = []
    for base in SOURCE_ROOTS:
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            if FIXTURE_NAME.search(path.name):
                found.append(path)
    return found


def strip_comments(text: str) -> str:
    """去掉注释但**保留行号**（块注释按原换行数补空行），避免报错行号漂移。"""
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def methods(lines: list[str]) -> list[tuple[str, int, str]]:
    """粗略按方法签名 + 花括号配平切块（够用于本仓风格），产出 (方法名, 起始行号, 块文本)。"""
    out: list[tuple[str, int, str]] = []
    i = 0
    while i < len(lines):
        match = METHOD_SIG.match(lines[i])
        if match:
            depth = lines[i].count("{") - lines[i].count("}")
            body = [lines[i]]
            j = i + 1
            while j < len(lines) and depth > 0:
                depth += lines[j].count("{") - lines[j].count("}")
                body.append(lines[j])
                j += 1
            out.append((match.group(1), i + 1, "\n".join(body)))
            i = j
        else:
            i += 1
    return out


def can_fail(blocks: list[tuple[str, int, str]]) -> set[str]:
    """调用图闭包：哪些方法（含传递调用）能 `return Status.FAILED`。"""
    body_of = {name: body for name, _, body in blocks}
    able = {name for name, _, body in blocks if RETURNS_FAILED.search(body)}
    changed = True
    while changed:
        changed = False
        for name, body in body_of.items():
            if name in able:
                continue
            if any(other in able and re.search(r"\b" + re.escape(other) + r"\s*\(", body)
                   for other in body_of):
                able.add(name)
                changed = True
    return able


def teleport_tick_premise(blocks: list[tuple[str, int, str]]) -> list[tuple[int, str]]:
    """R4：同一方法体内既有 `teleportTo(` 又有 `check("premise_on_ground"`，
    **却没有用 `settledOnGround(...)` 把判据延后到物理结算之后** ⇒ 陈旧读数被当判据。

    （判据只认"补救措施在不在"：传送与判据写在同一方法里是可以的 ——
     唯一的要求是**判据必须等结算**，即出现 `settledOnGround` ✓。）
    """
    hits = []
    for _name, start, body in blocks:
        if ("teleportTo(" in body
                and re.search(r'check\(\s*"premise_on_ground"', body)
                and "settledOnGround" not in body):
            hits.append((start, "先 teleportTo 再 check(\"premise_on_ground\")，且没有 settledOnGround 复核"))
    return hits


def bad_shapes(code: str) -> list[tuple[int, str]]:
    """一行 return 里 DONE 与 RUNNING 并存、却没有 FAILED ⇒ 失败分支缺失。"""
    hits = []
    for number, line in enumerate(code.splitlines(), start=1):
        if not re.search(r"\breturn\b", line):
            continue
        if "Status.DONE" in line and "Status.RUNNING" in line and "Status.FAILED" not in line:
            hits.append((number, line.strip()))
    return hits


def main() -> int:
    seen = fixtures()
    r1_fail: list[tuple[str, int, int]] = []       # 文件, 方法起始行, 该方法内 DONE 行
    r2_fail: list[tuple[str, int, str]] = []
    r3_info: list[str] = []
    r4_fail: list[tuple[str, int, str]] = []

    for path in seen:
        raw = path.read_text(encoding="utf-8")
        code = strip_comments(raw)
        rel = path.relative_to(ROOT).as_posix()
        blocks = methods(code.splitlines())
        able = can_fail(blocks)

        for name, start, body in blocks:
            if name in able:
                continue
            for n, line in enumerate(body.splitlines()):
                if RETURNS_DONE.search(line):
                    r1_fail.append((rel, start, start + n))
                    break

        for number, line in bad_shapes(code):
            r2_fail.append((rel, number, line))

        for start, why in teleport_tick_premise(blocks):
            r4_fail.append((rel, start, why))

        if "SUMMARY" in raw and "verdict=" not in raw:
            r3_info.append(rel)

    for rel, number, line in r2_fail:
        print(f"[R2] 失败分支缺失（return 行里 DONE+RUNNING 且无 FAILED）: {rel}:{number}: {line}")
    for rel, start, done_line in r1_fail:
        print(f"[R1] 方法（起始行 {start}）返回 Status.DONE，但没有任何路径能返回"
              f" Status.FAILED ⇒ 内部失败会被吞成静默绿: {rel}:{done_line}")
    for rel, start, why in r4_fail:
        print(f"[R4] 传送那一 tick 读 `onGround` 当判据（陈旧值 ⇒ 假绿/假红）: {rel}:{start}: {why}"
              " ⇒ 传送 tick 只 record，落地用 FixturePremise.settledOnGround(...) 复核")
    for rel in r3_info:
        print(f"[R3·信息] 打印 SUMMARY 但没有 verdict= : {rel}")

    ok = not r1_fail and not r2_fail and not r4_fail
    print(f"FIXTURE_HYGIENE_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"{len(seen)} 个夹具 / R1 违例={len(r1_fail)} / R2 违例={len(r2_fail)} / "
          f"R4 违例={len(r4_fail)} / R3 提示={len(r3_info)}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
