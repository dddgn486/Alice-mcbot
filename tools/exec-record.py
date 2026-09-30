#!/usr/bin/env python3
"""终态执行记录（D-134）的**接线规则**（挂在 `tools/check-all.sh`；失败即构建红）。

出处：2026-09-16 审计表全表复核（D-258）发现的两条 ——
  · **J-3**：`BotManager` 的 `taskKind` 取 `getClass().getSimpleName()`（换实现类就换名字）
  · **J-1**：`terminalReason`/`botId` 是 D-134 加的字段，但 `llm_contract` 只断言 failure* ⇒ 「进了 prompt」无判据

R1（J-3）`BotManager` 里给 `taskKind` 赋值的**那条语句**必须引用 `taskName()`（稳定标识）。
R2（J-1）`DecisionSnapshot.lastTerminalJson` 必须写出 `terminalReason` 与 `botId` 两个键。
R3（J-1）`TaskExecutionRecord`/`TaskOutcome` 的构造器必须带 `terminalReason`/`botId` 形参。
R6（`D-560` 第 4 条，刀 4）**发料维可见**：`provision` 必须①进记录分量 ②由**发料动作**打标
   （不是调用方手写常量）③进终态日志 ④有"真的会发生"的反空转靶子。
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


def rule_r4():
    """R4（J-3 补全，2026-09-17）：**声明了 `NAME` 的 Job 必须覆写 `taskName()` 并返回它**。

    为什么：客户端实测（2026-09-17 10:35）看到 `task_execution_terminal kind=LumberJob` ——
    `LumberJob` 明明有 `NAME = "lumber"` 却没覆写 ⇒ 稳定标识只对覆写过的类生效（原先只有 `CraftJob`）。
    规则把"名字从 `NAME` 来"这件事变成机器可查的，防止新增 Job 又漂回类名。
    """
    problems = []
    jobs = sorted((SRC / "job").rglob("*.java"))
    for path in jobs:
        text = path.read_text(encoding="utf-8")
        if "public static final String NAME" not in text:
            continue
        m = re.search(r"public String taskName\(\)\s*\{([^}]*)\}", text)
        rel = path.relative_to(SRC)
        if not m:
            problems.append(f"{rel} 声明了 NAME 但没有覆写 taskName()（终态 kind 会退回类名）")
        elif "NAME" not in m.group(1):
            problems.append(f"{rel} 的 taskName() 没有返回 NAME（稳定标识又漂了）")
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


def rule_r5():
    """R5（F1 地基，2026-09-17 用户裁定「先做地基」）：**驱动者身份位必须端到端在位**。

    背景（`survey/16 §3`）：今天的终态记录/快照/日志里只有 `botId`，没有"谁驱动的"这一维 ⇒
    "玩家让做的 / LLM 自己决定的 / 夹具跑的"在事后**无法区分**，而外部驱动者/陪伴/桌面 AI 那条线
    全都要先有这一维。本规则锁住三处：记录分量、终态日志、决策快照。
    """
    problems = []
    record = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "bot" / "TaskExecutionRecord.java"
    manager = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "bot" / "BotManager.java"
    snapshot = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "decision" / "DecisionSnapshot.java"
    if record.exists() and "String driver" not in record.read_text(encoding="utf-8"):
        problems.append("TaskExecutionRecord 没有 `driver` 分量（F1：终态记录必须能归因到驱动者）")
    if manager.exists():
        text = manager.read_text(encoding="utf-8")
        if "driver={}" not in text and "driver=" not in text:
            problems.append("BotManager 的终态日志里找不到 `driver=`（F1：日志必须能归因）")
        if "Driver.of(" not in text:
            problems.append("BotManager 没有读 Driver.of(...)（F1：记录的 driver 从哪来？）")
    if snapshot.exists() and '"driver"' not in snapshot.read_text(encoding="utf-8"):
        problems.append("DecisionSnapshot 没把 `driver` 写进快照（F1：决策层看不到就白做）")
    return problems


def rule_r6():
    """R6（`D-560` 第 4 条，刀 4）：**发料事实必须进结果，且必须真的会被打上**。

    用户原话：「**不是不能白送工具，只是不应该被白送工具的行为污染结果**」——
    这句话唯一能执行的形式 = **把"这次发过料"写进结果**（`bot/TaskExecutionRecord.provision`）。
    ⚠️ 只加一个字段**不算做到**：它必须是**发料动作自己**打的标（⛔ 不是调用方手写），
    否则手写点必然漏，而漏掉的形态正是"结果被污染却查不出来"——本规则防的就是这个假绿。

    四问（每条都有一个可执行断言）：
      ① 记录分量在？(`String provision`)
      ② 打标处**是发料动作**？(`FixtureToolKit` 真造物处 / `ToolSupply.promoteFromMain` 各 ≥1 处)
      ③ 进终态日志？（`task_terminal_provision` 行里要有 `provision=`）
      ④ **反空转**：`BotSession` 必须真的从 `pendingProvision`/`taskProvision` 取值
         （若 `recordTerminal` 写死 `"none"`，前三条仍可全绿 ⇒ 必须有这条）。
    """
    problems = []
    record = read("bot/TaskExecutionRecord.java")
    manager = read("bot/BotManager.java")
    tool_supply = read("bot/ToolSupply.java")
    fixture_kit = SRC / "item" / "FixtureToolKit.java"
    if "String provision" not in record:
        problems.append("TaskExecutionRecord 没有 `provision` 分量（`D-560` 第 4 条：发料事实必须进结果）")
    if "provision=" not in manager or "task_terminal_provision" not in manager:
        problems.append("BotManager 的终态日志里找不到 `task_terminal_provision ... provision=`（留痕读不到）")
    # ③ 进快照？（决策层看不到就白做 —— 与 R5 的 `driver` 同一条理由）
    snapshot = read("decision/DecisionSnapshot.java")
    if 'addProperty("provision"' not in snapshot:
        problems.append("DecisionSnapshot.lastTerminalJson 没把 `provision` 写进快照"
                        "（`D-560` 第 4 条：决策层读不到就白留痕）")
    # ② 打标处必须是**发料动作**：两个漏斗各至少一处
    if '"PROMOTE_ONLY")' not in tool_supply:
        problems.append("ToolSupply.promoteFromMain 没有 `markProvision(\"PROMOTE_ONLY\")`"
                        "（只搬运也是发料，必须留痕）")
    if not fixture_kit.exists():
        problems.append("缺 item/FixtureToolKit.java（改名？同步本规则）")
    else:
        fk = fixture_kit.read_text(encoding="utf-8")
        if fk.count('"DEV_CREATE")') < 2:
            problems.append(f"FixtureToolKit 的**造物**分支只有 {fk.count(chr(34)+'DEV_CREATE'+chr(34))} 处打标"
                            "（两条造物路径：填充空格 ＋ 强制覆盖，都必须打；"
                            "⚠️ 而「快捷栏已够」/「从主背包搬入」**不是**造物，⛔ 不许打）")
    # ④ 反空转：记录里的 provision 必须**从会话取**，不许写死
    if "String provision = taskProvision;" not in manager:
        problems.append("BotManager 的终态记录没有从会话取 `taskProvision`"
                        "（若写死 `\"none\"` ⇒ 本维永远空白，前三条仍会全绿）")
    if "pendingProvision" not in manager or "public void markProvision(" not in manager \
            or "public static void markProvision(BotPlayer bot, String label)" not in manager:
        problems.append("BotManager/BotSession 没有 `markProvision(...)`（空安全静态口 ＋ 实例口都必须在）"
                        " / `pendingProvision`（打标入口缺失）")
    return problems


def main() -> int:
    r1, r2, r3, r4, r5, r6 = rule_r1(), rule_r2(), rule_r3(), rule_r4(), rule_r5(), rule_r6()
    for line in r1:
        print(f"[R1·J-3 稳定标识] {line}")
    for line in r2 + r3:
        print(f"[R2/R3·J-1 终态事实] {line}")
    for line in r4:
        print(f"[R4·J-3 Job 稳定标识] {line}")
    for line in r5:
        print(f"[R5·F1 驱动者身份] {line}")
    for line in r6:
        print(f"[R6·D-560 发料留痕] {line}")
    ok = not (r1 or r2 or r3 or r4 or r5 or r6)
    print(f"EXEC_RECORD_CHECK_RESULT {'PASS' if ok else 'FAIL'}: "
          f"taskKind 接线={len(r1)} / 快照字段={len(r2)} / 记录字段={len(r3)} / Job 稳定标识={len(r4)}"
          f" / 驱动者身份={len(r5)} / 发料留痕={len(r6)}"
          f"（R1 = taskKind 必须用 taskName()；R2/R3 = terminalReason+botId 必须进快照与记录；"
          f"R4 = 声明 NAME 的 Job 必须覆写 taskName()；R5 = driver 维端到端；"
          f"R6 = `D-560` 第 4 条：发料事实必须进结果且由**发料动作**打标）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
