#!/usr/bin/env python3
"""结构门禁：**已冻结的功能不许静默消失，也不许静默扩张**。

## 为什么这条规则该存在

用户 2026-09-27 裁定：**冻结连锁挖掘执行器** —— 理由逐字是「它属于**模组兼容交付内容**，
只是一个**提前太多**的实验性产物」。

⚠️ 它必须门禁化，而不是只写进文档，因为**那段代码长得像死代码**：
`MiningTuning.chainMode` 的默认是 `ChainMode.OFF`（`shouldChain()` 对 `OFF` 直接 `return false`），
无头电池里 `[ChainMine]` **0 行**（模组不在场），夹具里的两条连选用例**已撤出**（`D-470`）。
⇒ 下一个会话在做 `step 5b` / `L1` 化重构时，**最可能发生的意外就是"顺手把这段清理掉"** ——
而那不是清理，是**销毁一份将来要交付的实验产物**。
本仓对冻结的既有口径（鱼骨线，`OPEN_ITEMS_LEDGER` 的冻结声明）逐字是「转挂账，**不许静默消失**」；
对**代码**，这句可执行化的形态就是本门禁。

## 断言（任一不成立 ⇒ 非零退出）

对 `FREEZE_REGISTRY` 里的**每一条冻结项**：

1. **符号必须还在**（`symbols`）—— 每个 `文件 → 必需子串` 都要能命中；
   少了任何一个 ⇒ 红（"静默消失"）。⚠️ 判据是**子串存在**，不是哈希：
   冻结不许被"顺手改语义"，但**不禁止**注释/缩进/搬行（那样会把正常重构也判红）。
2. **冻结的功能必须仍然"默认关闭"**（`defaults_off`）—— 被冻结的东西要是**默认生效**，
   它就不再是"提前太多的实验产物"，而是**悄悄进了生产**；这类改动必须**显式解冻**才能做。
3. **不许长出新消费者**（`consumers_allowlist`）—— 全仓提到该符号的文件集合必须**恰好**是
   冻结时登记的那几个；多一个 ⇒ 红（"冻结期间它又长出去了"）。少一个也红（见 1）。

## ⚠️ 本门禁**不**覆盖什么

- **不判"冻结项有没有被开发"** —— 那是"不做某事"的政策，没有可执行对象
  （同 `AGENTS.md` 对 `D-219` 的注记）。本门禁只守**两个可观测的边界**：消失 / 扩张。
- **不判语义等价** —— 把 `tickChain` 内部逻辑改烂但符号还在，本门禁**看不见**。
  那属于"引用触发"（真机出现连锁相关异常时人工复核）。
- 冻结**不是**"永远不许动"：真要动（交付模组兼容 / 修改语义）⇒ **先改本文件的登记**，
  改动就"响亮"了，而不是静默的。

## 红臂（`--selftest`，每次运行都跑）

合成臂针对**纯函数** `inspect(...)` 打，覆盖：符号缺失 ⇒ 红 · 默认被打开 ⇒ 红 ·
新消费者 ⇒ 红 · 消费者减少 ⇒ 红 · 正常 ⇒ 绿。
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"

# ==================== 冻结登记表（一条冻结项 = 一个 dict） ====================
#: ⚠️ 新增冻结项只需在这里加一行；**解冻**也只需删掉对应项（那就是"响亮"的动作）。
FREEZE_REGISTRY: tuple[dict[str, object], ...] = (
    {
        "id": "chain_executor",
        "title": "连锁挖掘执行器（模组兼容交付内容）",
        "reason": "用户 2026-09-27 裁定：「它属于模组兼容交付内容，只是一个提前太多的实验性产物」",
        "revive": "模组兼容交付立项时；届时按交付项补证据（手工入口 = 场景 `alice_test:chain_mine_course` "
                  "或物品 `alice:chain_test_runner`）",
        # 每个文件里必须还在的**子串**（口径 = 存在性，不是哈希）
        "symbols": {
            "com/dddgn/alice/compat/ChainMining.java": (
                "class ChainMining", "enum StartResult",
                # ⚠️ 一律用**声明形**：只写 `foo(` 会被**调用点**满足 —— 真树红臂 R1 实测过这件事
                # （把 `private Status tickChain()` 改名，调用点 `tickChain()` 还在 ⇒ 假绿）。
                "public static boolean shouldChain(", "public static StartResult start(",
                "public static boolean available(", "public static boolean isRunning(",
                "public static int minedCount(", "public static void stop(",
                "oreexcavation.handlers.MiningScheduler",
            ),
            "com/dddgn/alice/task/MineTask.java": (
                "private Status beginChain() {", "private Status tickChain() {",
                "private boolean chainTriggered;", "private boolean chainRefusedByBudget;",
                "public boolean chainRefusedByBudget() {",
                "private static final int CHAIN_TIMEOUT_TICKS = 200;",
                "Phase.CHAIN", "chain_budget_refused", "prod_fallback",
            ),
            "com/dddgn/alice/task/ChainMineDiagnosticTask.java": (
                "class ChainMineDiagnosticTask", "chain_mod=absent",
            ),
            "com/dddgn/alice/task/mining/MineStep.java": ("ChainMining.shouldChain(",),
            "com/dddgn/alice/reach/MiningTuning.java": ("enum ChainMode",),
            "com/dddgn/alice/command/BotCommand.java": (
                "private static int chainMode(", 'literal("chain")'),
        },
        # 冻结的功能必须**默认关闭**：文件 → 必须仍然命中的子串
        "defaults_off": {
            "com/dddgn/alice/reach/MiningTuning.java": ("chainMode = ChainMode.OFF",),
        },
        # 全仓提到这个符号的文件**恰好**只能是这些（多一个 ⇒ 冻结期间又长出去了）
        "consumer_needle": "ChainMining",
        "consumers_allowlist": (
            "MineTask.java", "ChainMineDiagnosticTask.java", "MineStep.java",
            "ChainMining.java", "BotCommand.java",
        ),
    },
)

#: 人口下限（防"登记表被清空 ⇒ 判据空集真"，`Z4` 的教训）。
MIN_FROZEN_ITEMS = 1
MIN_FROZEN_SYMBOLS = 15


def inspect(files: dict[str, str], consumers: dict[str, list[str]]) -> list[str]:
    """纯函数：`files` = `{相对 SRC 的 posix 路径: 内容}`，`consumers` = `{needle: [命中的文件名]}`。

    拆成纯函数是为了让红臂能直接驱动它（不许再写第二套解析 —— `D-468` 的教训）。
    """
    problems: list[str] = []
    if len(FREEZE_REGISTRY) < MIN_FROZEN_ITEMS:
        problems.append(f"冻结登记表只剩 {len(FREEZE_REGISTRY)} 条（下限 {MIN_FROZEN_ITEMS}）⇒ "
                        f"判据退化成空集真（要「什么都没有被冻结」必须显式写进本文件）")
    total_symbols = 0
    for item in FREEZE_REGISTRY:
        fid = item["id"]
        symbols = item["symbols"]  # type: ignore[assignment]
        for rel, needed in symbols.items():  # type: ignore[union-attr]
            total_symbols += len(needed)
            text = files.get(rel)
            if text is None:
                problems.append(f"[{fid}] 找不到 {rel} ⇒ 冻结项被搬走/改名？"
                                f"（冻结口径：**不许静默消失**；要动先改本文件的登记）")
                continue
            for needle in needed:
                if needle not in text:
                    problems.append(f"[{fid}] {rel} 里找不到 `{needle}` ⇒ "
                                    f"冻结的功能被删/改语义了（解冻必须**先改本文件的登记**）")
        for rel, needed in item.get("defaults_off", {}).items():  # type: ignore[union-attr]
            text = files.get(rel, "")
            for needle in needed:
                if needle not in text:
                    problems.append(f"[{fid}] {rel} 里找不到 `{needle}` ⇒ "
                                    f"**冻结的功能被默认打开了**（那就不再是「提前太多的实验产物」，"
                                    f"而是悄悄进了生产）⇒ 必须显式解冻")
        needle = item.get("consumer_needle")
        if needle:
            allow = set(item["consumers_allowlist"])  # type: ignore[arg-type]
            hits = set(consumers.get(str(needle), []))
            extra = sorted(hits - allow)
            missing = sorted(allow - hits)
            if extra:
                problems.append(f"[{fid}] `{needle}` 出现了**新消费者**：{extra} ⇒ "
                                f"冻结期间它又长出去了（要么撤掉，要么显式解冻并改本表）")
            if missing:
                problems.append(f"[{fid}] `{needle}` 少了登记的消费者：{missing} ⇒ "
                                f"冻结项被拆掉了一块（见断言 1）")
    if total_symbols < MIN_FROZEN_SYMBOLS:
        problems.append(f"登记表里只有 {total_symbols} 个冻结符号（下限 {MIN_FROZEN_SYMBOLS}）⇒ "
                        f"判据形同虚设")
    return problems


def scan() -> tuple[dict[str, str], dict[str, list[str]]]:
    """读真树：冻结项涉及的文件 + 全仓 `consumer_needle` 命中。"""
    files: dict[str, str] = {}
    needles: set[str] = set()
    for item in FREEZE_REGISTRY:
        files.update({rel: "" for rel in item["symbols"]})  # type: ignore[union-attr]
        files.update({rel: "" for rel in item.get("defaults_off", {})})  # type: ignore[union-attr]
        if item.get("consumer_needle"):
            needles.add(str(item["consumer_needle"]))
    for rel in list(files):
        path = SRC / rel
        if path.exists():
            files[rel] = path.read_text(encoding="utf-8", errors="replace")
        else:
            del files[rel]
    consumers: dict[str, list[str]] = {n: [] for n in needles}
    for path in sorted(SRC.rglob("*.java")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for needle in needles:
            if needle in text:
                consumers[needle].append(path.name)
    return files, consumers


# ==================== 红臂（每次运行都跑） ====================

SELFTEST_CASES: list[tuple[str, dict[str, str], dict[str, list[str]], bool]] = (
    ("绿：登记项全在 + 默认关闭 + 消费者恰好", {
        "com/dddgn/alice/compat/ChainMining.java": "class ChainMining enum StartResult public static boolean shouldChain( public static StartResult start( public static boolean available( public static boolean isRunning( public static int minedCount( public static void stop( oreexcavation.handlers.MiningScheduler",
        "com/dddgn/alice/task/MineTask.java": "private Status beginChain() { private Status tickChain() { private boolean chainTriggered; private boolean chainRefusedByBudget; public boolean chainRefusedByBudget() { private static final int CHAIN_TIMEOUT_TICKS = 200; Phase.CHAIN chain_budget_refused prod_fallback",
        "com/dddgn/alice/task/ChainMineDiagnosticTask.java": "class ChainMineDiagnosticTask chain_mod=absent",
        "com/dddgn/alice/task/mining/MineStep.java": "ChainMining.shouldChain(",
        "com/dddgn/alice/reach/MiningTuning.java": "enum ChainMode chainMode = ChainMode.OFF",
        "com/dddgn/alice/command/BotCommand.java": "private static int chainMode( literal(\"chain\")",
     }, {"ChainMining": ["MineTask.java", "ChainMineDiagnosticTask.java", "MineStep.java",
                         "ChainMining.java", "BotCommand.java"]}, False),
    ("红：`tickChain(` 被删（静默消失）", {
        "com/dddgn/alice/task/MineTask.java": "private Status beginChain() { private Status tickChain() { private boolean chainTriggered; private boolean chainRefusedByBudget; public boolean chainRefusedByBudget() { private static final int CHAIN_TIMEOUT_TICKS = 200; Phase.CHAIN chain_budget_refused prod_fallback",
     }, {"ChainMining": ["MineTask.java"]}, True),
    ("红：整个文件被搬走", {}, {"ChainMining": []}, True),
    ("红：默认被打开（`ChainMode.AUTO`）", {
        "com/dddgn/alice/reach/MiningTuning.java": "enum ChainMode chainMode = ChainMode.AUTO",
     }, {"ChainMining": []}, True),
    ("红：长出**新消费者**（冻结期间又扩出去）", {
        "com/dddgn/alice/compat/ChainMining.java": "class ChainMining enum StartResult public static boolean shouldChain( public static StartResult start( public static boolean available( public static boolean isRunning( public static int minedCount( public static void stop( oreexcavation.handlers.MiningScheduler",
        "com/dddgn/alice/task/MineTask.java": "private Status beginChain() { private Status tickChain() { private boolean chainTriggered; private boolean chainRefusedByBudget; public boolean chainRefusedByBudget() { private static final int CHAIN_TIMEOUT_TICKS = 200; Phase.CHAIN chain_budget_refused prod_fallback",
        "com/dddgn/alice/task/ChainMineDiagnosticTask.java": "class ChainMineDiagnosticTask chain_mod=absent",
        "com/dddgn/alice/task/mining/MineStep.java": "ChainMining.shouldChain(",
        "com/dddgn/alice/reach/MiningTuning.java": "enum ChainMode chainMode = ChainMode.OFF",
        "com/dddgn/alice/command/BotCommand.java": "private static int chainMode( literal(\"chain\")",
     }, {"ChainMining": ["MineTask.java", "ChainMineDiagnosticTask.java", "MineStep.java",
                         "ChainMining.java", "BotCommand.java", "SomeNewJob.java"]}, True),
)


def run_selftest() -> list[str]:
    problems: list[str] = []
    for label, files, consumers, expect_red in SELFTEST_CASES:
        found = inspect(files, consumers)
        is_red = bool(found)
        if is_red != expect_red:
            problems.append(f"红臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、"
                            f"实得{'红' if is_red else '绿'}（{found}）")
    return problems


def main() -> int:
    problems = [f"[红臂] {p}" for p in run_selftest()]
    files, consumers = scan()
    problems.extend(inspect(files, consumers))

    if problems:
        print("FROZEN_CODE_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        print("  ⇒ 依据：用户 2026-09-27「冻结连锁挖掘执行器」+ 本仓冻结口径"
              "（鱼骨线：转挂账，**不许静默消失**）")
        return 1

    for item in FREEZE_REGISTRY:
        allow = ", ".join(item["consumers_allowlist"])  # type: ignore[arg-type]
        print(f"FROZEN_CODE_RESULT PASS[{item['id']}]: {item['title']} —— "
              f"符号 {sum(len(v) for v in item['symbols'].values())} 个全在 · "  # type: ignore[union-attr]
              f"默认关闭已断言 · 消费者恰好 {{{allow}}}")
    print(f"  冻结项 {len(FREEZE_REGISTRY)} 条 · 红臂 {len(SELFTEST_CASES)}/{len(SELFTEST_CASES)}"
          f" · ⚠️ 本门禁只守「消失/扩张」，不判「有没有被开发」也判不出语义被改烂")
    return 0


if __name__ == "__main__":
    sys.exit(main())
