#!/usr/bin/env python3
"""终态执行记录（D-134）的**接线规则**（挂在 `tools/check-all.sh`；失败即构建红）。

出处：2026-09-16 审计表全表复核（D-258）发现的两条 ——
  · **J-3**：`BotManager` 的 `taskKind` 取 `getClass().getSimpleName()`（换实现类就换名字）
  · **J-1**：`terminalReason`/`botId` 是 D-134 加的字段，但 `llm_contract` 只断言 failure* ⇒ 「进了 prompt」无判据

R1（J-3）`BotManager` 里给 `taskKind` 赋值的**那条语句**必须引用 `taskName()`（稳定标识）。
R2（J-1）`DecisionSnapshot.lastTerminalJson` 必须写出 `terminalReason` 与 `botId` 两个键。
R3（J-1）`TaskExecutionRecord`/`TaskOutcome` 的构造器必须带 `terminalReason`/`botId` 形参。
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"


def read(rel: str) -> str:
    return (SRC / rel).read_text(encoding="utf-8")


def rule_r1():
    """R1：`taskKind` 的赋值必须能**追溯到 `taskName()`** —— 允许两种写法：
      (a) RHS 里直接出现 `taskName()`；
      (b) RHS 调用本文件内的一个方法（如 `stableTaskKind(...)`），**该方法体**里出现 `taskName()`。

    ⚠️ 2026-09-16 反向对照把规则逼成了现在这样：早先"赋值语句 + 前 300 字符窗口"会被邻近的
    无关 `taskName()` 顶绿（注入 `getClass().getSimpleName()` 仍然 PASS）⇒ 那是假绿。
    """
    text = read("bot/BotManager.java")
    problems = []
    hits = 0
    for m in re.finditer(r"\btaskKind\s*=", text):
        line_start = text.rfind("\n", 0, m.start()) + 1
        line = text[line_start:text.find("\n", m.start())]
        if "String taskKind" in line:        # 字段声明，不是"定名字"
            continue
        semi = text.find(";", m.start())
        stmt = text[m.start():semi + 1] if semi > 0 else text[m.start():m.end() + 50]
        hits += 1
        if "taskName()" in stmt:
            continue
        resolved = False
        for call in re.findall(r"\b([A-Za-z_]\w*)\s*\(", stmt):
            decl = re.search(r"\b" + re.escape(call) + r"\s*\([^)]*\)\s*\{", text)
            if not decl:
                continue
            body_end = text.find("\n    }", decl.end())
            body = text[decl.end():body_end if body_end > 0 else decl.end() + 2000]
            if "taskName()" in body:
                resolved = True
                break
        if not resolved:
            problems.append("BotManager 的 taskKind 赋值追溯不到 taskName()："
                            + " ".join(stmt.split())[:90])
    if hits == 0:
        problems.append("BotManager 里找不到 taskKind 赋值（改名？同步本规则）")
    return problems


def rule_r2():
    """R2：快照生成处必须写出 `terminalReason` 与 `botId`。

    ⚠️ 切片必须落在**方法体**上：第一次出现的 `lastTerminalJson` 是 javadoc 里的提及
    （2026-09-16 反向对照实测：按第一次出现切片 ⇒ 规则恒红，属自造假警）。
    """
    text = read("decision/DecisionSnapshot.java")
    anchor = text.find("public static JsonObject lastTerminalJson(")
    if anchor < 0:
        return ["DecisionSnapshot 里找不到 lastTerminalJson(...)（改名？同步本规则）"]
    body = text[anchor:anchor + 2000]
    problems = []
    for key in ("terminalReason", "botId"):
        if f'addProperty("{key}"' not in body:
            problems.append(f"DecisionSnapshot.lastTerminalJson 没有写出 {key}（终态事实读不到）")
    return problems


def rule_r3():
    problems = []
    for rel, keys in (("bot/TaskExecutionRecord.java", ("terminalReason", "botId")),
                      ("bot/TaskOutcome.java", ("terminalReason", "botId"))):
        text = read(rel)
        for key in keys:
            if key not in text:
                problems.append(f"{rel} 不含 {key} 字段（D-134 的接线被拆）")
    return problems


def main() -> int:
    r1, r2, r3 = rule_r1(), rule_r2(), rule_r3()
    for line in r1:
        print(f"[R1·J-3 稳定标识] {line}")
    for line in r2 + r3:
        print(f"[R2/R3·J-1 终态事实] {line}")
    ok = not (r1 or r2 or r3)
    print(f"EXEC_RECORD_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"taskKind 接线={len(r1)} / 快照字段={len(r2)} / 记录字段={len(r3)}"
          f"（R1 = taskKind 必须用 taskName()；R2/R3 = terminalReason+botId 必须进快照与记录）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
