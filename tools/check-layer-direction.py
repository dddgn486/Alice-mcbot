#!/usr/bin/env python3
"""`J-★` 第 6 段 **step 3**（2026-09-27）结构门禁：**层方向不许倒过来**。

## 为什么这条规则该存在

`D-455` 定了三层（`action/` 微操作 < `task/` 动作原语 < `job/` 高级任务），
`survey/42 §1.2` 实测**全仓唯一的循环依赖**就在这里：

    action/MineBlockRunner → task/mining/{StandingPointSelector, LineOfSightChecker, MiningPlan}
    task/**                → action/{WriteGrant, WriteBudget, BlockInteraction, …}

⇒ 微操作**反过来**依赖比它高一层的原语 ⇒ `action/` 自己的边界「不许编排」**没有可执行判据**
（谁都能往 `task/` 里伸手，而分层只在文档里）。

`step 3` 的修法 = 把那批「**触及站位**」件搬出 `task/`，进**新顶层包** `reach/`
（`com.dddgn.alice.reach`）—— 形状照 `pathing/`（内核层，谁都能依赖、它谁都不依赖）。
⭐ **落地口径（用户 2026-09-27 拍 ⑤-⑦ = B）：本窗口只搬包、不改名**
（改名 `ReachStanding` 是窗口结束后的**独立一刀**，带锚点清单 + `--inject`）。

⚠️ **本条的判据只能是静态门禁**：搬包**不改任何行为**（`git mv` + `package` 行 + import）
⇒ 行为夹具对本刀**结构性无感**（同 `D-425`「死码删除的判据只能是静态门禁」）。

## 断言（任一不成立 ⇒ 非零退出）

1. **`reach/` 不许依赖上层**：`src/main/java/com/dddgn/alice/reach/**` 里不得出现对
   `com.dddgn.alice.{task, action, job}` 的 import ⇒ 红。
   ⭐ 这是「**只搬一个会造出新循环**」的防线：`StandingPointSelector` 拖着 `MiningTuning`、
   `MiningPlan` 拖着 `LineOfSightChecker` ⇒ 漏搬一个，循环就**换个方向长回来**。
2. **`action/` 不许依赖 `task/`**，**除了下表列明的欠账**：`src/main/java/com/dddgn/alice/action/**`
   里对 `com.dddgn.alice.task.*` 的 import 必须**逐条**在 `ALLOWED_REVERSE` 里（带到期条件）。
   今天表里只有一条（`step 3b`）⇒ 欠账**写在门禁里**，不藏在谁也不知道的地方。
3. **人口下限**（防"把包搬空 ⇒ 门禁假绿"）：扫描 `.java` ≥ `MIN_SCANNED_FILES` ·
   `reach/` 文件数 ≥ `MIN_REACH_FILES` · `action/` 文件数 ≥ `MIN_ACTION_FILES`。

## 红臂（`--selftest`，每次运行都跑）

合成片段 5 条：`reach/` 里 import `task`（红）· `reach/` 里 import `job`（红）·
`action/` 里 import 一个**没登记**的 `task` 类（红）· `action/` 里 import 表内那个（绿）·
`reach/` 里 import `pathing`/`log`（绿，内核层本来就该能用）。

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

#: `action/` 对 `task/` 的**已登记欠账**（`文件: import 的类 → 到期条件`）。
#: ⚠️ 只许**减少**；新增一条 = 必须显式改本文件（这就是"响亮"）。
ALLOWED_REVERSE: dict[str, dict[str, str]] = {
    f"{PKG}/action/MineBlockRunner.java": {
        "com.dddgn.alice.task.PathRetryRunner":
            "step 3b（`PathRetryRunner` 是寻路重试器，家在 `pathing/`；实测 import 面 5 文件 + 7 处 FQN ⇒ 单独一刀）",
    },
}

#: 实测 510+（2026-09-27）；留足余量，只用来抓"扫描根被搬空 / 解析崩塌"。
MIN_SCANNED_FILES = 480
#: `step 3a` 实测 4 个（`StandingPointSelector` / `LineOfSightChecker` / `MiningPlan` / `MiningTuning`）。
MIN_REACH_FILES = 4
#: `step 3a` 实测 12 个（6 微操作 + 6 写入治理）。
MIN_ACTION_FILES = 10

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
    elif rel.startswith(PKG + "/action/"):
        allowed = ALLOWED_REVERSE.get(rel, {})
        for imported in imports_of(text):
            if imported.startswith("com.dddgn.alice.task.") and imported not in allowed:
                problems.append(f"{rel} 的 `action/` import 了 `{imported}` ⇒ 微操作依赖上层原语。"
                                f"若这是**新欠账**，必须带到期条件显式登记进本文件的 `ALLOWED_REVERSE`")
    return problems


# ==================== 红臂（每次运行都跑） ====================

_MB = f"{PKG}/action/MineBlockRunner.java"
SELFTEST_CASES: list[tuple[str, str, str, bool]] = [
    ("`reach/` import `task` ⇒ 红",
     f"{PKG}/reach/StandingPointSelector.java",
     "import com.dddgn.alice.task.mining.MiningTuning;", True),
    ("`reach/` import `job` ⇒ 红",
     f"{PKG}/reach/MiningPlan.java",
     "import com.dddgn.alice.job.mine.MineJob;", True),
    ("`action/` import 没登记的 `task` 类 ⇒ 红",
     _MB,
     "import com.dddgn.alice.task.SomeNewHelper;", True),
    ("`action/` import 表内那个 ⇒ 绿（欠账已登记）",
     _MB,
     "import com.dddgn.alice.task.PathRetryRunner;", False),
    ("`reach/` import `pathing` / `log` ⇒ 绿（同层/更下层）",
     f"{PKG}/reach/LineOfSightChecker.java",
     "import com.dddgn.alice.pathing.MovementHelper;\nimport com.dddgn.alice.log.BotLog;", False),
    ("`action/` import `action` 自己 ⇒ 绿",
     _MB,
     "import com.dddgn.alice.action.WriteGrant;", False),
]


def selftest() -> list[str]:
    problems: list[str] = []
    for label, rel, body, expect_red in SELFTEST_CASES:
        found = scan_imports(rel, body)
        is_red = bool(found)
        if is_red != expect_red:
            problems.append(f"红臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、实得"
                            f"{'红' if is_red else '绿'}（{found}）")
    return problems


def main() -> int:
    problems = [f"[红臂] {p}" for p in selftest()]

    if not SRC.is_dir():
        print(f"LAYER_DIRECTION_RESULT FAIL: 找不到 {SRC}")
        return 1

    files = sorted(SRC.rglob("*.java"))
    reach_files = [p for p in files if p.as_posix().startswith(str(SRC / PKG / "reach"))]
    action_files = [p for p in files if p.as_posix().startswith(str(SRC / PKG / "action"))]
    debt_hits: dict[str, list[str]] = {}

    for path in files:
        rel = path.relative_to(SRC).as_posix()
        text = path.read_text(encoding="utf-8")
        problems.extend(scan_imports(rel, text))
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

    if problems:
        print("LAYER_DIRECTION_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        print("  ⇒ 依据：`J-★` 第 6 段 step 3（`D-460`）+ `D-455` 三层 + `survey/42 §1.2`（全仓唯一循环依赖）")
        return 1

    debt = " · ".join(f"{rel.split('/')[-1]}→{','.join(i.split('.')[-1] for i in hits)}"
                      for rel, hits in sorted(debt_hits.items()) if hits)
    print(f"LAYER_DIRECTION_RESULT PASS: `reach/` 反向依赖 0 · `action/` → `task/` 欠账 "
          f"{sum(len(v) for v in debt_hits.values())} 条「{debt or '无'}」 · "
          f"reach {len(reach_files)} 文件 / action {len(action_files)} 文件 / 扫描 {len(files)} · "
          f"红臂 {len(SELFTEST_CASES)}/{len(SELFTEST_CASES)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
