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
6. ⭐ **`task/` 里 `dest ∈ {生产, step}` 的类，不得在代码里依赖 `debug/`／`fixture/`**（`D-557`）。
   `R3_EXCLUDED` 整包排除 `task/` 的理由是「**别管那 100+ 个还没搬的夹具**」，
   ⛔ **不是**「生产类可以依赖可剔除物」—— 那正是 `R6` 要防的。实测代价（`O90`）：
   `task/MineTask.java` 一度是 `private com.dddgn.alice.fixture.mining.GainStepRunner gainRunner;`
   （1 处 import ＋ 6 处**行内 FQN**）而 **`check-all` 全绿** —— 当时这类错误**只有 `P0` 台账一条防线**。
   ⭐ **判据 = 代码里的使用**（同 `D-556` (c)）：剥块注释／行注释／**字符串字面量** ＋ 去掉 `import` 行后，
   ① 出现 `com.dddgn.alice.(debug|fixture).`（**行内 FQN**），或 ② 有 `debug/`／`fixture/` 的 import
   **且该简单名在代码里被用**（import 型依赖，FQN 扫描**看不见**它）⇒ 红。
   ⛔ 纯 `{@link}`／`import` 不做引用 ⇒ 今天**零豁免表**。
   ⚠️ **本条随 `task/` 一起退役**（`P4` 关门后 `task/` 为空 ⇒ 本规则退化成空真）——
   ⛔ 不许靠"空过"留着（同 `O85` §② 两条）。**反空转判据**：`task/` 里**有** `.java` 却读不出任何
   `生产/step` 行 ⇒ 红（读不到台账 **响亮失败**；⛔ 不许静默当成"没有生产类"）。

## 红臂（`--selftest`，每次运行都跑）

合成片段（`reach/` 里 import `task`（红）· `reach/` 里 import `job`（红）·
`action/` 里 import 一个**没登记**的 `task` 类（红）· `action/` 里 import 表内那个（绿）·
`reach/` 里 import `pathing`/`log`（绿，内核层本来就该能用）· `write/` 里 import `action`（红）·
`write/` 里 import `job`（红）· `write/` 里 import `pathing`（绿）·
`job/` import `debug/`（红）· `bot/`（注册位置）import `debug/`（绿）· `debug/`（`D-560` 起是**开发期桶**）import `fixture/`（**绿**）·
⭐ `command/`（`D-560` 起是**真产品面**）import `fixture/`（**红** —— 断言由 `debug/` **搬家**到此，净效果**收窄**）·
⭐ `task/` 的**生产类**在**代码里**用 `fixture/`（红）· ⭐ `task/FixtureScript` 的**纯 javadoc/import**（绿））。
另 3 条**定义**臂：`action/WriteGrant.java`（红）· `action/MineBlockRunner.java`（绿）·
`write/WriteGrant.java`（绿）。

跑法：`python3 tools/check-layer-direction.py`（已挂在 `tools/check-all.sh`）。
"""

from __future__ import annotations

import csv
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

#: ⭐ `R3`（`D-492`）的**注册位置** —— 这些位置**允许**依赖 `debug/`／`fixture/`。
#: 用户 2026-09-30 裁「**乙**」：把 `bot/` 加进来（`bot/BotManager` 的 `assignXxx` 是真正的派发枢纽，
#: 它自己 `new` 夹具；与 `item/` 在"注册"这件事上是**同一角色**）。
#: `<root>` = 模组入口（`AliceMod`）—— 它做的是 `EVENT_BUS.register(X.class)`，同属注册。
#: ⭐⭐ **`command/` 已于 `D-560`（2026-09-30 用户裁定）移出本表** —— 开发期/调试子命令已劈去 `debug/`，
#: `command/` 现在是**真产品面**（`D-554`：发行包玩家调试面 = 命令 ＋ GUI）⇒ 它**不许**依赖开发期物。
#: ⚠️ 这**不是放宽**而是**收窄**：原先 `debug/` 被当作产品面（故禁其依赖 `fixture/`），现在该断言
#: 改管 `command/`（真产品面），`debug/` 按 `D-560` 归**开发期桶**（见下方 `debug/` 分支）。
REGISTRATION_POSITIONS = {"item", "bot", "<root>"}
#: ⛔ **暂排除**：`task/` 是**被退役的那个包**，其内容的去留由 `docs/TASK_RETIREMENT_MAP.csv` 管；
#: `debug/`／`fixture/` 是**开发期桶**（`D-560`：`debug/` 住开发期/调试子命令 ⇒ 允许依赖夹具）；
#: `headless/`／`tools/` 是验证侧。
R3_EXCLUDED = {"task", "debug", "fixture", "headless", "tools"}
DEBUG_FIXTURE_PREFIXES = ("com.dddgn.alice.debug.", "com.dddgn.alice.fixture.")

#: ⭐ `D-557`：`task/` 里**这些 `dest`** 的类，按 `R3` 同等对待（生产执行路径 ✗→ `debug/`／`fixture/`）。
#: ⛔ 其余 `dest`（`fixture`／`debug`）是**目的地自身**，本来就该互相引用 ⇒ 不在规则内。
TASK_PROD_DESTS = {"生产", "step"}
#: `P0` 台账（生成物）—— 本规则**只读它**，⛔ 不自己判"谁是生产类"（那是 `task-retirement-map.py` 的活）。
LEDGER = ROOT / "docs" / "TASK_RETIREMENT_MAP.csv"
#: `debug/`／`fixture/` 的 import（**静态 import 也算**）。
DF_IMPORT_RE = re.compile(
    r"^\s*import\s+(?:static\s+)?com\.dddgn\.alice\.(?:debug|fixture)\.([\w.]+)\s*;", re.M)
#: 行内 FQN（**行内**写法没有 import 行 ⇒ 只扫 import 会整类漏掉，`O90` 就是这么漏的）。
DF_FQN_RE = re.compile(r"com\.dddgn\.alice\.(?:debug|fixture)\.")

_STR_RE = re.compile(r'"(?:\\.|[^"\\])*"')
_IMPORT_LINE_RE = re.compile(r"\s*import\s")


def strip_to_code(text: str) -> str:
    """只留**代码里的使用**（`D-556` (c) 的判据）：剥块注释／行注释／**字符串字面量** ＋ 去掉 `import` 行。

    ⚠️ 字符串字面量必须剥 —— 本项目多处用**类名字符串**做日志／登记（`"com.dddgn.alice.fixture.X"`），
    那不是依赖；不剥会造出**假红**（同族 = `O76` §4a「引用必须剥注释／字符串」）。
    """
    s = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    s = re.sub(r"//[^\n]*", " ", s)
    s = _STR_RE.sub('""', s)
    return "\n".join(l for l in s.splitlines() if not _IMPORT_LINE_RE.match(l))


_LEDGER_LOADED = False
#: `类名 → dest`（**全树**，含子包 ⇒ 键是**裸类名**，与 `P0` 台账的键一致）。
_PROD_DESTS: dict[str, str] = {}
#: 诊断用：只有 `import`、代码里没用到的（**不是违规** —— 纯 javadoc 链接，`D-556` (c)）。
_JAVADOC_ONLY: list[str] = []


def load_ledger() -> None:
    """读 `P0` 台账。⚠️ ⛔ **不许静默空集**（那会让本规则空过）⇒ 读不到就**响亮失败**。"""
    global _LEDGER_LOADED
    if not LEDGER.is_file():
        print(f"LAYER_DIRECTION_RESULT FAIL: 找不到 `P0` 台账 `{LEDGER.relative_to(ROOT)}` ⇒ "
              f"`task/` 生产类的 `R3` 子规则**读不到判据**。⛔ 不许静默当成"
              f"「没有生产类」（那会让本规则空过）")
        sys.exit(1)
    with LEDGER.open(encoding="utf-8", newline="") as fh:
        for row in csv.reader(fh):
            if len(row) < 3 or row[0] == "task_class":
                continue
            if row[2] in TASK_PROD_DESTS:
                _PROD_DESTS[row[0]] = row[2]
    _LEDGER_LOADED = True


def _is_prod_task(rel: str) -> bool:
    """`rel` 是 `task/` 下的类，且在台账里 `dest ∈ {生产, step}`？"""
    if not rel.startswith(PKG + "/task/"):
        return False
    if not _LEDGER_LOADED:
        raise RuntimeError("load_ledger() 未调用 —— ⛔ 本规则不许在「没有判据」的情况下静默跑（`D-557`）")
    stem = rel.rsplit("/", 1)[-1][: -len(".java")]
    return _PROD_DESTS.get(stem) in TASK_PROD_DESTS


def debug_fixture_code_use(rel: str, text: str) -> list[str]:
    """`text` 是否**在代码里**依赖 `debug/`／`fixture/`？⇒ 违规理由（空 = 绿）。

    ①② 两种写法都要抓，缺一即漏：
      ① **行内 FQN** —— `private com.dddgn.alice.fixture.mining.GainStepRunner g;`（`O90` 的形状，**没有** import 行）
      ② **import ＋ 简单名** —— `import …fixture.mining.GainStepRunner;` 然后在代码里写 `GainStepRunner`
         ⚠️ 只扫 FQN 会**整类漏掉** `②`（这才是最常见的写法）。
    """
    code = strip_to_code(text)
    reasons: list[str] = []
    if DF_FQN_RE.search(code):
        reasons.append(f"{rel} 的代码里出现 `com.dddgn.alice.debug.`／`fixture.` 的**行内全限定名**")
    for m in DF_IMPORT_RE.finditer(text):
        simple = m.group(1).split(".")[-1]
        if re.search(r"\b" + re.escape(simple) + r"\b", code):
            reasons.append(f"{rel} 的代码里用了 `{m.group(0).strip()}` 的简单名 `{simple}`")
        else:
            _JAVADOC_ONLY.append(f"{rel.rsplit('/', 1)[-1]}→{simple}")
    return reasons


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

    # ⭐ `R3`（`D-492`＋`D-551` 乙）：生产包 ✗→ `debug/`／`fixture/`；
    # ⭐ `D-560`（2026-09-30 用户裁定「现有 `/alice` 命令全是开发期入口」）：**真产品面 = `command/`**
    #    ✗→ `debug/`／`fixture/`。⚠️ 原先这条断言管 `debug/`（当时把它当产品面）⇒ 现在是**搬家＋收窄**：
    #    `debug/` 归**开发期桶**（住 28 条开发期命令）⇒ 允许依赖夹具；`command/` 接任"产品面"角色。
    # ⭐ `D-557`：`task/` 里 `dest ∈ {生产, step}` 的类**同等对待** —— `R3_EXCLUDED` 整包排除 `task/`
    # 是为了"别管那 100+ 个还没搬的夹具"，⛔ 不是"生产类可以依赖可剔除物"（`O90` 的 `MineTask` 就这么漏过去）。
    tp = top_pkg(rel)
    if tp == "task":
        if _is_prod_task(rel):
            for why in debug_fixture_code_use(rel, text):
                problems.append(f"{why} ⇒ 违反 `R3`／`R6`：**生产执行路径** ✗→ `debug/`／`fixture/`"
                                f"（`D-557`）。`task/` 的 `dest` 由 `docs/TASK_RETIREMENT_MAP.csv` 定；"
                                f"若该类其实**不是**生产类，改台账（⛔ 不许靠含糊过去）")
    elif tp == "debug":
        # ⭐ `D-560`（2026-09-30 用户裁定）：`debug/` 是**开发期/调试入口桶**（住那 28 条开发期命令）
        # ⇒ **允许**依赖 `fixture/`（原来的"`debug/` ✗→ `fixture/`"断言**已改管 `command/`**，
        # 见下方 `command/` 分支 —— 断言是**搬家**不是删除，净效果是**收窄**）。
        pass
    elif tp == "command":
        # ⭐ `D-560`：真产品面 = `command/`。开发期/调试子命令已劈去 `debug/` ⇒ 产品面**不许**依赖开发期物。
        for imported in imports_of(text):
            if imported.startswith(DEBUG_FIXTURE_PREFIXES):
                problems.append(f"{rel} 的 `command/`（**产品面**）import 了 `{imported}` ⇒ "
                                f"违反 `R3`／`D-560`：`command/` ✗→ `debug/`／`fixture/`"
                                f"（开发期命令请放 `debug/`，⛔ 不许长回产品面）")
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
    ("⭐ `debug/`（`D-560` 起是**开发期桶**）import `fixture/` ⇒ **绿**"
     "（原先它被当作产品面故为红 —— `D-560` 之后那条断言**改管 `command/`**，见下一臂）",
     f"{PKG}/debug/SomeDebugTool.java",
     "import com.dddgn.alice.fixture.transfer.TransferFixture;", False),
    ("⭐ `command/`（`D-560` 起是**真产品面**）import `fixture/` ⇒ **红**"
     "（断言**搬家**不是删除 ⇒ 净效果是**收窄**：原先管一个包，现在还管产品面那个包）",
     f"{PKG}/command/BotCommand.java",
     "import com.dddgn.alice.fixture.transfer.TransferFixture;", True),
    # ⚠️ 本条原先的 rel 写的是 `task/…` 而实际测的是 `fixture/…`（**标签在说谎**）⇒ `D-557` 一并改正：
    # `fixture/` 是**目的地自身**（`R3_EXCLUDED`），它与 `fixture/` 之间的引用本来就合法。
    ("`fixture/`（**目的地自身**，`R3_EXCLUDED`）import `fixture/` ⇒ 绿",
     f"{PKG}/fixture/K2AdjacentGoalCheckTask.java",
     "import com.dddgn.alice.fixture.transfer.TransferFixture;", False),
    # ---- `D-557` 新增：`task/` 里 `dest ∈ {生产, step}` 的类 ✗→ debug/fixture ----
    ("⭐ `task/MineTask`（`dest=生产`）**代码里**出现 `fixture/` 的**行内 FQN** ⇒ 红"
     "（`O90` 的原始形状：**没有** import 行，只扫 import 抓不到）",
     f"{PKG}/task/MineTask.java",
     "private com.dddgn.alice.fixture.mining.GainStepRunner gainRunner;", True),
    ("⭐ `task/MineTask`（`dest=生产`）**import ＋ 简单名使用** ⇒ 红"
     "（最常见写法；**只扫 FQN 会整类漏掉**）",
     f"{PKG}/task/MineTask.java",
     "import com.dddgn.alice.fixture.mining.GainStepRunner;\n"
     "class A { GainStepRunner g; }", True),
    ("⭐ `task/FixtureScript`（`D-560` 后 `dest=fixture`；此前为 `生产`）**只有 import ＋ `{@link}`**、"
     "代码里零使用 ⇒ 绿"
     "（`D-556` (c)：`import` 行／javadoc 不算引用）",
     f"{PKG}/task/FixtureScript.java",
     "import com.dddgn.alice.debug.PathSessionDiagnosticTask;\n"
     "/** 见 {@link PathSessionDiagnosticTask}。 */\nclass A {\n}",
     False),
    ("⭐ `task/FixtureScript` 把同一个类名放进**字符串字面量** ⇒ 绿（不是引用）",
     f"{PKG}/task/FixtureScript.java",
     "class A { String s = \"com.dddgn.alice.fixture.mining.GainStepRunner\"; }", False),
    ("⭐ `task/FixtureScript` 在**行注释**里提到 `com.dddgn.alice.fixture.X` ⇒ 绿（剥注释）",
     f"{PKG}/task/FixtureScript.java",
     "class A {\n    // 曾经用过 com.dddgn.alice.fixture.mining.GainStepRunner\n}", False),
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
    # ⚠️ 先读 `P0` 台账 —— 红臂与真扫都要用它（`D-557`）；读不到就**响亮失败**。
    load_ledger()
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

    # ⭐ `D-557` **反空转**（跨源对账，⛔ 不写死人口数）：`task/` 里还有 `.java`、
    # 却一条 `生产/step` 都读不出来 ⇒ 判据读空了 ⇒ 红。
    # ⚠️ 本条**随 `task/` 一起退役**：`P4` 关门后 `task_files` 为空 ⇒ 条件自然不成立，
    # 届时本子规则（连这条反空转）必须**同刀删除**，⛔ 不许靠"空过"留着（`O85` §②）。
    task_files = under("task")
    if task_files and not _PROD_DESTS:
        problems.append(f"`task/` 里还有 {len(task_files)} 个 `.java`，但 `P0` 台账"
                        f"（`{LEDGER.relative_to(ROOT)}`）读不出任何 `dest ∈ {sorted(TASK_PROD_DESTS)}` 的行 "
                        f"⇒ 判据读空了（`D-557` 的反空转）。⚠️ 先进 `task-retirement-map.py` 看台账为何为空")

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
    javadoc_only = sorted(set(_JAVADOC_ONLY))
    print(f"LAYER_DIRECTION_RESULT PASS: `reach/` 反向依赖 0 · `write/` 反向依赖 0 · `action/` → `task/` 欠账 "
          f"{sum(len(v) for v in debt_hits.values())} 条「{debt or '无（step 3b 后已是无条件 0 命中）'}」 · "
          f"reach {len(reach_files)} 文件 / action {len(action_files)} 文件 / write {len(write_files)} 文件 "
          f"（写入治理 6 个全在）· `R3` 注册位置 {sorted(REGISTRATION_POSITIONS)} 零违规 · "
          f"⭐ `task/` 生产类（{len(_PROD_DESTS)} 个 `dest ∈ {{生产, step}}`）✗→ `debug/`／`fixture/` "
          f"**代码级** 0 命中"
          f"（纯 javadoc/import 不计：{len(javadoc_only)} 处 {javadoc_only or '无'} —— `D-556` (c)） · "
          f"扫描 {len(files)} · 红臂 {arms}/{arms}"
          f"（import {len(SELFTEST_CASES)} + 定义 {len(SELFTEST_DEF_CASES)}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
