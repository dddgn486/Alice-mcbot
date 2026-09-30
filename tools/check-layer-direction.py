#!/usr/bin/env python3
"""`J-★` 第 6 段 **step 3 / step 4**（2026-09-27）结构门禁：**层方向不许倒过来**。

## 为什么这条规则该存在

`D-455` 定了三层（`action/` 微操作 < `task/` 动作原语 < `job/` 高级任务），
`survey/42 §1.2` 实测**全仓唯一的循环依赖**就在这里：

    action/MineBlockRunner → task/mining/{StandingPointSelector, LineOfSightChecker, ReachPlan}
    task/**                → action/{WriteGrant, WriteBudget, BlockInteraction, …}

⇒ 微操作**反过来**依赖比它高一层的原语 ⇒ `action/` 自己的边界「不许编排」**没有可执行判据**
（谁都能往 `task/` 里伸手，而分层只在文档里）。

`step 3` 的修法 = 把那批「**触及站位**」件搬出 `task/`，进**新顶层包** `reach/`
（`com.dddgn.alice.reach`）—— 形状照 `pathing/`（内核层，谁都能依赖、它谁都不依赖）。
⭐ **落地口径（用户 2026-09-27 拍 ⑤-⑦ = B）：本窗口只搬包、不改名**
（改名 `ReachStanding` 是窗口结束后的**独立一刀**，带锚点清单 + `--inject`）。

⚠️ **本条的判据只能是静态门禁**：搬包**不改任何行为**（`git mv` + `package` 行 + import）
⇒ 行为夹具对本刀**结构性无感**（同 `D-425`「死码删除的判据只能是静态门禁」）。

## `step 4`（`D-462`）：写入治理从 `action/` 拆进 `write/`

`action/` 的 12 个文件本来就**干净地分成两半**：**6 个微操作**（`BlockBreakSession` ·
`BlockInteraction` · `ContainerSemantics` · `MenuCodes` · `MenuSession` · `MineBlockRunner`）
与 **6 个写入治理**（`TaskTargetProtection` · `WriteAudit` · `WriteBudget` · `WriteGrant` ·
`WritePolicyMatrix` · `WriteReason`）。拆包后方向变成**单向**：

    action/MineBlockRunner → write/WriteGrant        ✅ 允许（微操作调授权）
    write/**               → action/BlockInteraction ⛔ 禁止（写入治理不许认识调用它的人）

⚠️ **本刀不解循环**：`action ↔ pathing` 那个包级环来自**微操作** `MineBlockRunner`，
与写入治理无关 —— 别把 `step 4` 读成 `step 3` 的替代（台账 `4` 行原话）。

## 断言（任一不成立 ⇒ 非零退出）

1. **`reach/` 不许依赖上层**：`src/main/java/com/dddgn/alice/reach/**` 里不得出现对
   `com.dddgn.alice.{task, action, job}` 的 import ⇒ 红。
   ⭐ 这是「**只搬一个会造出新循环**」的防线：`StandingPointSelector` 拖着 `MiningTuning`、
   `ReachPlan` 拖着 `LineOfSightChecker` ⇒ 漏搬一个，循环就**换个方向长回来**。
2. **`action/` 不许依赖 `task/`**（`step 3b` 后**无条件**）：`src/main/java/com/dddgn/alice/action/**`
   里对 `com.dddgn.alice.task.*` 的 import 必须**逐条**在 `ALLOWED_REVERSE` 里 ——
   ⭐ 该表**今天为空**（`step 3a` 剩的最后 1 条 = `PathRetryRunner`，已由 `step 3b` 搬进 `pathing/`）。
   留这张表是为了：将来若真出现欠账，**必须带到期条件显式登记**，不许静默。
3. **`write/` 不许依赖上层**（`step 4`）：`.../write/**` 里不得出现对
   `com.dddgn.alice.{task, action, job}` 的 import ⇒ 红。
   ⭐ 方向性断言：**`action/` → `write/` 是允许的**（微操作调授权），**反过来禁止**。
4. **6 个写入治理类只许定义在 `write/`**（`step 4` 的**实现**判据）：`action/` 里再出现
   `{TaskTargetProtection, WriteAudit, WriteBudget, WriteGrant, WritePolicyMatrix, WriteReason}.java`
   ⇒ 红；且这 6 个文件必须**都**在 `write/` 里（否则"拆包"可以是"删掉"）。
5. **人口下限**（防"把包搬空 ⇒ 门禁假绿"）：扫描 `.java` ≥ `MIN_SCANNED_FILES` ·
   `reach/` ≥ `MIN_REACH_FILES` · `action/` ≥ `MIN_ACTION_FILES` · `write/` ≥ `MIN_WRITE_FILES`。

## 红臂（`--selftest`，每次运行都跑）

合成片段 8 条：`reach/` 里 import `task`（红）· `reach/` 里 import `job`（红）·
`action/` 里 import 一个**没登记**的 `task` 类（红）· `action/` 里 import 表内那个（绿）·
`reach/` 里 import `pathing`/`log`（绿，内核层本来就该能用）· `write/` 里 import `action`（红）·
`write/` 里 import `job`（红）· `write/` 里 import `pathing`（绿）。
另 3 条**定义**臂：`action/WriteGrant.java`（红）· `action/MineBlockRunner.java`（绿）·
`write/WriteGrant.java`（绿）。

跑法：`python3 tools/check-layer-direction.py`（已挂在 `tools/check-all.sh`）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"
PKG = "com/dddgn/alice"

#: `reach/` 是**内核侧**的几何层（触及站位 / 视线 / 计划）⇒ 不许依赖任何一个上层。
REACH_FORBIDDEN = ("com.dddgn.alice.task.", "com.dddgn.alice.action.", "com.dddgn.alice.job.")

#: `write/` 是**写入治理**（授权 / 额度 / 审计 / 策略表 / 保护）⇒ 同样不许依赖任何一个上层。
#: ⭐ 方向性：`action/` → `write/`（微操作调授权）**允许**，反过来**禁止** —— 见 `D-462`。
WRITE_FORBIDDEN = ("com.dddgn.alice.task.", "com.dddgn.alice.action.", "com.dddgn.alice.job.")

#: `step 4`（`D-462`）搬出 `action/` 的 6 个写入治理类 —— 它们**只许**定义在 `write/` 下。
WRITE_GOVERNANCE = ("TaskTargetProtection", "WriteAudit", "WriteBudget",
                    "WriteGrant", "WritePolicyMatrix", "WriteReason")

#: `action/` 对 `task/` 的**已登记欠账**（`文件: import 的类 → 到期条件`）。
#: ⚠️ 只许**减少**；新增一条 = 必须显式改本文件（这就是"响亮"）。
ALLOWED_REVERSE: dict[str, dict[str, str]] = {
    # ⭐ `step 3b`（2026-09-27）之后**本表为空** —— `PathRetryRunner` 已搬去 `pathing/`
    # ⇒ `action/ → task/` 是**无条件** 0 命中。留这张表是为了"将来若真出现欠账，必须带到期条件显式登记"。
}

#: ⭐ `R3`（`D-492`）的**注册位置** —— 这些位置**允许**依赖 `debug/`（产品面）。
#: 用户 2026-09-30 裁「**乙**」：把 `bot/` 加进来（`bot/BotManager` 的 `assignXxx` 是真正的派发枢纽，
#: 它自己 `new` 夹具；与 `item/`／`command/` 在"注册"这件事上是**同一角色**）。
#: `<root>` = 模组入口（`AliceMod`）—— 它做的是 `EVENT_BUS.register(X.class)`，同属注册。
REGISTRATION_POSITIONS = {"item", "command", "bot", "<root>"}
#: ⛔ **暂排除**：`task/` 是**被退役的那个包**，其内容的去留由 `docs/TASK_RETIREMENT_MAP.csv` 管；
#: `debug/`／`fixture/` 是目的地自身；`headless/`／`tools/` 是验证侧。
R3_EXCLUDED = {"task", "debug", "fixture", "headless", "tools"}
DEBUG_FIXTURE_PREFIXES = ("com.dddgn.alice.debug.", "com.dddgn.alice.fixture.")


def top_pkg(rel: str) -> str:
    """`rel`（相对 `src/main/java`）⇒ 一级包名；根包文件（`…/alice/AliceMod.java`）⇒ `<root>`。"""
    parts = rel.split("/")
    return parts[3] if len(parts) > 4 else "<root>"


#: 实测 510+（2026-09-27）；留足余量，只用来抓"扫描根被搬空 / 解析崩塌"。
MIN_SCANNED_FILES = 480
#: `step 3a` 实测 4 个（`StandingPointSelector` / `LineOfSightChecker` / `ReachPlan` / `MiningTuning`）。
MIN_REACH_FILES = 4
#: `step 4`（`D-462`）之后 `action/` = 实测 **6** 个（纯微操作；原 12 = 6 微操作 + 6 写入治理）。
MIN_ACTION_FILES = 6
#: `step 4` 实测 6 个（`WRITE_GOVERNANCE` 全体）。
MIN_WRITE_FILES = 6

IMPORT_RE = re.compile(r"^\s*import\s+(static\s+)?(com\.dddgn\.alice\.[\w.]+)\s*;", re.M)


def imports_of(text: str) -> list[str]:
    """取一个源文件的 `com.dddgn.alice.*` import（**静态 import 也算** —— 一样是依赖）。"""
    return [m.group(2) for m in IMPORT_RE.finditer(text)]


def scan_imports(rel: str, text: str) -> list[str]:
    """按 `rel`（相对 `src/main/java`）判断这串 import 违不违规。"""
    problems: list[str] = []
    if rel.startswith(PKG + "/reach/"):
        for imported in imports_of(text):
            for bad in REACH_FORBIDDEN:
                if imported.startswith(bad):
                    problems.append(f"{rel} 的 `reach/` 反过来 import 了上层 `{imported}` ⇒ "
                                    f"层方向倒了（`D-455` 三层：action < task < job；`reach/` 与 `pathing/` 同级）")
    elif rel.startswith(PKG + "/write/"):
        for imported in imports_of(text):
            for bad in WRITE_FORBIDDEN:
                if imported.startswith(bad):
                    problems.append(f"{rel} 的 `write/` 反过来 import 了上层 `{imported}` ⇒ "
                                    f"写入治理依赖了调用它的层（`step 4`/`D-462`：`action/` → `write/` "
                                    f"只许**单向**）")
    elif rel.startswith(PKG + "/action/"):
        allowed = ALLOWED_REVERSE.get(rel, {})
        for imported in imports_of(text):
            if imported.startswith("com.dddgn.alice.task.") and imported not in allowed:
                problems.append(f"{rel} 的 `action/` import 了 `{imported}` ⇒ 微操作依赖上层原语。"
                                f"若这是**新欠账**，必须带到期条件显式登记进本文件的 `ALLOWED_REVERSE`")

    # ⭐ `R3`（`D-492`＋`D-551` 乙）：生产包 ✗→ `debug/`／`fixture/`；`debug/`（产品面）✗→ `fixture/`。
    tp = top_pkg(rel)
    if tp == "debug":
        for imported in imports_of(text):
            if imported.startswith("com.dddgn.alice.fixture."):
                problems.append(f"{rel} 的 `debug/`（**产品面**）import 了 `{imported}` ⇒ "
                                f"违反 `R3`：`debug/` ✗→ `fixture/`（产品面不许依赖开发期物）")
    elif tp not in REGISTRATION_POSITIONS and tp not in R3_EXCLUDED:
        for imported in imports_of(text):
            if imported.startswith(DEBUG_FIXTURE_PREFIXES):
                problems.append(f"{rel} 的 `{tp}/`（**生产包**）import 了 `{imported}` ⇒ "
                                f"违反 `R3`（生产 ✗→ `debug/`／`fixture/`）。"
                                f"若 `{tp}/` 确属**注册位置**，必须显式加进本文件的 `REGISTRATION_POSITIONS`"
                                f"（⛔ 不许靠「它其实不算生产」含糊过去）")
    return problems


def scan_definition(rel: str) -> list[str]:
    """按 `rel` 判断"是不是又定义了一份写入治理类"（`step 4` 的实现判据）。"""
    if not rel.startswith(PKG + "/action/"):
        return []
    stem = rel.rsplit("/", 1)[-1][: -len(".java")]
    if stem in WRITE_GOVERNANCE:
        return [f"{rel} 又出现了一份 `{stem}` 的定义 ⇒ `step 4`（`D-462`）已把这 6 个写入治理类"
                f"搬进 `write/`，`action/` 里只许留微操作（同一层不许有两份定义）"]
    return []


# ==================== 红臂（每次运行都跑） ====================

_MB = f"{PKG}/action/MineBlockRunner.java"
SELFTEST_CASES: list[tuple[str, str, str, bool]] = [
    ("`reach/` import `task` ⇒ 红",
     f"{PKG}/reach/StandingPointSelector.java",
     "import com.dddgn.alice.task.mining.MiningTuning;", True),
    ("`reach/` import `job` ⇒ 红",
     f"{PKG}/reach/ReachPlan.java",
     "import com.dddgn.alice.job.mine.MineJob;", True),
    ("`action/` import 没登记的 `task` 类 ⇒ 红",
     _MB,
     "import com.dddgn.alice.task.SomeNewHelper;", True),
    ("`action/` import 已搬走的 `task.PathRetryRunner` ⇒ 红（`step 3b` 之后它家在 `pathing/`）",
     _MB,
     "import com.dddgn.alice.task.PathRetryRunner;", True),
    ("`action/` import `pathing`（同层/更下层）⇒ 绿",
     _MB,
     "import com.dddgn.alice.pathing.PathRetryRunner;", False),
    ("`reach/` import `pathing` / `log` ⇒ 绿（同层/更下层）",
     f"{PKG}/reach/LineOfSightChecker.java",
     "import com.dddgn.alice.pathing.MovementHelper;\nimport com.dddgn.alice.log.BotLog;", False),
    ("`action/` import `action` 自己 ⇒ 绿",
     _MB,
     "import com.dddgn.alice.action.WriteGrant;", False),
    # ---- `step 4`（`D-462`）新增 ----
    ("`write/` import `action`（调用它的微操作）⇒ 红（方向只许单向）",
     f"{PKG}/write/WriteBudget.java",
     "import com.dddgn.alice.action.BlockInteraction;", True),
    # ---- `D-551`（用户 2026-09-30 裁「乙」）新增：`R3` 生产 ✗→ debug/fixture ----
    ("⭐ `job/`（生产包）import `debug/` ⇒ 红",
     f"{PKG}/job/mine/MineJob.java",
     "import com.dddgn.alice.debug.SomeDebugTool;", True),
    ("⭐ `bot/`（**注册位置**，用户裁「乙」）import `debug/` ⇒ **绿**",
     f"{PKG}/bot/BotManager.java",
     "import com.dddgn.alice.debug.SomeDebugTool;", False),
    ("⭐ `debug/`（产品面）import `fixture/` ⇒ 红",
     f"{PKG}/debug/SomeDebugTool.java",
     "import com.dddgn.alice.fixture.transfer.TransferFixture;", True),
    ("`task/`（被退役的包，**暂排除**）import `fixture/` ⇒ 绿（去留由退役台账管）",
     f"{PKG}/fixture/K2AdjacentGoalCheckTask.java",
     "import com.dddgn.alice.fixture.transfer.TransferFixture;", False),
    ("`write/` import `job` ⇒ 红",
     f"{PKG}/write/WriteGrant.java",
     "import com.dddgn.alice.job.lumber.LumberJob;", True),
    ("`write/` import `pathing` / `log`（更下层）⇒ 绿",
     f"{PKG}/write/WritePolicyMatrix.java",
     "import com.dddgn.alice.pathing.core.search.PathRequest;\nimport com.dddgn.alice.log.BotLog;", False),
    ("`action/` import `write/`（微操作调授权）⇒ 绿（`step 4` 后的**正确**方向）",
     _MB,
     "import com.dddgn.alice.write.WriteGrant;", False),
]

#: 「定义」类红臂：`(label, rel, 期望红)` —— 走 `scan_definition`，与 import 臂分开。
SELFTEST_DEF_CASES: list[tuple[str, str, bool]] = [
    ("`action/` 里又出现一份 `WriteGrant` 的定义 ⇒ 红", f"{PKG}/action/WriteGrant.java", True),
    ("`action/` 里的微操作 `MineBlockRunner` ⇒ 绿（它本来就该在这层）",
     f"{PKG}/action/MineBlockRunner.java", False),
    ("`write/` 里的 `WriteGrant` ⇒ 绿（`step 4` 之后它的家）",
     f"{PKG}/write/WriteGrant.java", False),
]


def selftest() -> list[str]:
    problems: list[str] = []
    for label, rel, body, expect_red in SELFTEST_CASES:
        found = scan_imports(rel, body)
        is_red = bool(found)
        if is_red != expect_red:
            problems.append(f"红臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、实得"
                            f"{'红' if is_red else '绿'}（{found}）")
    for label, rel, expect_red in SELFTEST_DEF_CASES:
        found = scan_definition(rel)
        is_red = bool(found)
        if is_red != expect_red:
            problems.append(f"定义臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、实得"
                            f"{'红' if is_red else '绿'}（{found}）")
    return problems


def main() -> int:
    problems = [f"[红臂] {p}" for p in selftest()]

    if not SRC.is_dir():
        print(f"LAYER_DIRECTION_RESULT FAIL: 找不到 {SRC}")
        return 1

    files = sorted(SRC.rglob("*.java"))
    rels = {p: p.relative_to(SRC).as_posix() for p in files}

    def under(pkg: str) -> list[Path]:
        return [p for p in files if rels[p].startswith(f"{PKG}/{pkg}/")]

    reach_files, action_files, write_files = under("reach"), under("action"), under("write")
    debt_hits: dict[str, list[str]] = {}

    for path in files:
        rel = rels[path]
        text = path.read_text(encoding="utf-8")
        problems.extend(scan_imports(rel, text))
        problems.extend(scan_definition(rel))
        if rel in ALLOWED_REVERSE:
            hits = [i for i in imports_of(text) if i in ALLOWED_REVERSE[rel]]
            debt_hits[rel] = hits

    if len(files) < MIN_SCANNED_FILES:
        problems.append(f"只扫到 {len(files)} 个 `.java`（下限 {MIN_SCANNED_FILES}）⇒ 扫描根被搬空")
    if len(reach_files) < MIN_REACH_FILES:
        problems.append(f"`reach/` 只有 {len(reach_files)} 个文件（下限 {MIN_REACH_FILES}）⇒ "
                        f"`step 3a` 搬出来的东西被搬回去了")
    if len(action_files) < MIN_ACTION_FILES:
        problems.append(f"`action/` 只有 {len(action_files)} 个文件（下限 {MIN_ACTION_FILES}）⇒ "
                        f"微操作层被抽空")
    if len(write_files) < MIN_WRITE_FILES:
        problems.append(f"`write/` 只有 {len(write_files)} 个文件（下限 {MIN_WRITE_FILES}）⇒ "
                        f"`step 4` 拆出来的写入治理被搬回去/被删了")
    missing = [n for n in WRITE_GOVERNANCE if not (SRC / PKG / "write" / f"{n}.java").exists()]
    if missing:
        problems.append(f"`write/` 里缺 {missing} ⇒ 「拆包」不成立（拆包不是删除；`step 4`/`D-462`）")

    if problems:
        print("LAYER_DIRECTION_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        print("  ⇒ 依据：`J-★` 第 6 段 step 3（`D-460`/`D-461`）+ step 4（`D-462`）+ `D-455` 三层"
              "+ `survey/42 §1.2`（全仓唯一循环依赖）")
        return 1

    debt = " · ".join(f"{rel.split('/')[-1]}→{','.join(i.split('.')[-1] for i in hits)}"
                      for rel, hits in sorted(debt_hits.items()) if hits)
    arms = len(SELFTEST_CASES) + len(SELFTEST_DEF_CASES)
    print(f"LAYER_DIRECTION_RESULT PASS: `reach/` 反向依赖 0 · `write/` 反向依赖 0 · `action/` → `task/` 欠账 "
          f"{sum(len(v) for v in debt_hits.values())} 条「{debt or '无（step 3b 后已是无条件 0 命中）'}」 · "
          f"reach {len(reach_files)} 文件 / action {len(action_files)} 文件 / write {len(write_files)} 文件 "
          f"（写入治理 6 个全在）· `R3` 注册位置 {sorted(REGISTRATION_POSITIONS)} 零违规 · "
          f"扫描 {len(files)} · 红臂 {arms}/{arms}"
          f"（import {len(SELFTEST_CASES)} + 定义 {len(SELFTEST_DEF_CASES)}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
