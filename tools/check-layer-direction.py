#!/usr/bin/env python3
"""`J-★` 第 6 段 **step 3 / step 4**（2026-09-27）结构门禁：**层方向不许倒过来**。

## 为什么这条规则该存在

`D-455` 定了三层（`action/` 微操作 < `task/` 动作原语 < `job/` 高级任务），
`survey/42 §1.2` 实测**全仓唯一的循环依赖**就在这里：

    action/MineBlockRunner → task/mining/{StandingPointSelector, LineOfSightChecker, ReachPlan}   ← 历史（`step 3a`/`D-460` 已搬到 `reach/`）
    task/**                → action/{Attribution, Quota, BlockInteraction, …}

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
与 **6 个写入治理**（`TaskTargetProtection` · `ModifyAudit` · `Quota` · `Attribution` ·
`WritePolicyMatrix` · `WriteReason`）。拆包后方向变成**单向**：

    action/mining/MineBlockRunner → write/Attribution ✅ 允许（执行件调授权）
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
   `{TaskTargetProtection, ModifyAudit, Quota, Attribution, WritePolicyMatrix, WriteReason}.java`
   ⇒ 红；且这 6 个文件必须**都**在 `write/` 里（否则"拆包"可以是"删掉"）。
5. **人口下限**（防"把包搬空 ⇒ 门禁假绿"）：扫描 `.java` ≥ `MIN_SCANNED_FILES` ·
   `reach/` ≥ `MIN_REACH_FILES` · `action/` ≥ `MIN_ACTION_FILES` · `write/` ≥ `MIN_WRITE_FILES`。
6. ⭐ **`task/` 里 `dest ∈ {生产, step}` 的类，不得在代码里依赖 `debug/`／`fixture/`**（`D-557`）。   `R3_EXCLUDED` 整包排除 `task/` 的理由是「**别管那 100+ 个还没搬的夹具**」，
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
7. ⭐ **刀 3（`D-566`）的搬包判据：`protection/` → `region/` 是"移动"，⛔ 不是"复制"**：
   `region/` 里必须有 `{JobRegionRegistry, ClaimService, MapGeometry}.java`，且 `protection/` 里
   **零残留**；`region/` 人口下限 `MIN_REGION_FILES`（反空转 ③：搬空也能"看起来通过"）。
   ⚠️ **本条只判"搬没搬完"**，⛔ 不判 `region/` 的 import 方向 —— 那个（`§7` #6）**尚未裁定**（台账 `O130`）。

## 红臂（`--selftest`，每次运行都跑）

合成片段（`reach/` 里 import `task`（红）· `reach/` 里 import `job`（红）·
`action/` 里 import 一个**没登记**的 `task` 类（红）· `action/` 里 import 表内那个（绿）·
`reach/` 里 import `pathing`/`log`（绿，内核层本来就该能用）· `write/` 里 import `action`（红）·
`write/` 里 import `job`（红）· `write/` 里 import `pathing`（绿）·
`job/` import `debug/`（红）· ⭐ `bot/`（**刀 2 起不再是注册位置**）import `debug/`（**红** —— 断言**收窄**）· `debug/`（`D-560` 起是**开发期桶**）import `fixture/`（**绿**）·
⭐ `command/`（`D-560` 起是**真产品面**）import `fixture/`（**红** —— 断言由 `debug/` **搬家**到此，净效果**收窄**）·
⭐ `task/` 的**生产类**在**代码里**用 `fixture/`（红）· ⭐ `task/FixtureScript` 的**纯 javadoc/import**（绿））。
另 3 条**定义**臂：`action/Attribution.java`（红）· `action/mining/MineBlockRunner.java`（绿）·
`write/Attribution.java`（绿）。

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
#: ⚠️ 2026-10-01 刀 4（`D-566`）：`WriteAudit` **已搬去 `ledger/ModifyAudit`** ⇒ 从此不在本表
#: （本表是"**只许定义在 `write/` 下**"的名单 —— 它搬走了就该走，⛔ 不是"忘了"）。
WRITE_GOVERNANCE = ("TaskTargetProtection", "Attribution",
                    "WritePolicyMatrix", "WriteReason")

#: ⭐ 刀 4（`D-566`）：`write/WriteAudit` → **`ledger/ModifyAudit`**（用户 2026-10-01 裁定
#: 「`WriteAudit` → `ledger/`（带上前缀 ⇒ 类名 `ModifyAudit`）」）。判据 = **搬包不是复制**：
#: `ledger/` 里必须有、`write/` 里必须没有。
LEDGER_MOVED = ("ModifyAudit",)

#: ⭐ **刀 4**（`D-566`，2026-10-01 用户裁定）：`write/WriteBudget` → **`region/authz/Quota`**
#: （`§1` 原方案：authz = "允不允许动 ＋ 还能动几次"）。判据同"搬包不是复制"。

#: ⭐ **刀 3**（`D-566`，2026-10-01）：`§8` 步 2 从 `protection/` **原样搬进** `region/` 的 3 个类
#: （`JobRegionRegistry` · `ProtectionClaimService`→`ClaimService` · `ProtectionMapGeometry`→`MapGeometry`）。
#: ⚠️ 用途 = **"搬包不是删除"**：搬完之后 ① `region/` 里必须**有**、② `protection/` 里必须**没有**
#: —— 否则"把包搬空"或"复制一份留着"都能看起来通过（`O85` §② 同族：⛔ 不许靠"空过"留着）。
REGION_MOVED = ("JobRegionRegistry", "ClaimService", "MapGeometry")

#: ⭐ **刀 4 第 4 件**（2026-10-01，用户裁定「拆三个谓词后定名」；结构提案 `§5`）：
#: `protection/ZoneAuthority` **整体消失** —— 裁决面进 `region/authz/`，
#: 判据 = ① 两件**必须**在 `region/authz/`、② `protection/` 里**必须没有**同名文件、
#: ③ ⭐ **入口唯一**：E 维那件必须**包内可见** ⇒ 编译器保证没有第二个授权入口。
AUTHZ_MOVED = ("AreaPermission", "AreaPermissionLevel")

#: ⛔ 已被拆分**删除**的类（只能不存在；若回来 ⇒ 红）：拆分 = 移动，⛔ 不是复制。
AUTHZ_DISSOLVED = ("ZoneAuthority",)

#: `action/` 对 `task/` 的**已登记欠账**（`文件: import 的类 → 到期条件`）。
#: ⚠️ 只许**减少**；新增一条 = 必须显式改本文件（这就是"响亮"）。
ALLOWED_REVERSE: dict[str, dict[str, str]] = {
    # ⭐ `step 3b`（2026-09-27）之后**本表为空** —— `PathRetryRunner` 已搬去 `pathing/`
    # ⇒ `action/ → task/` 是**无条件** 0 命中。留这张表是为了"将来若真出现欠账，必须带到期条件显式登记"。
}

#: ⭐ `R3`（`D-492`）的**注册位置** —— 这些位置**允许**依赖 `debug/`／`fixture/`。
#: `<root>` = 模组入口（`AliceMod`）—— 它做的是 `EVENT_BUS.register(X.class)`，同属注册。
#: ⭐⭐ **`bot/` 已于刀 2（`D-512`，2026-09-30）移出本表** —— 用户裁「乙」时把它加进来，是因为
#: `bot/BotManager` 的 47 个 `assignXxx` 自己 `new` 夹具、**看起来**与 `item/` 是同一角色。
#: ⚠️ 但那不是"注册"，是**内核里的派发枢纽**：刀 2 把那 48 个入口搬去
#: `fixture/FixtureDispatch`（走 `bot/BotManager.beginIdleTask` 桥），`bot/` 现在**零开发期引用**
#: ⇒ 豁免理由消失 ⇒ 移出本表 = 断言**收窄**（`bot/` 与任何生产包同等对待）。
#: ⚠️ 同一条理由也让 `check-provision-containment.sh` 的 `BOT_EXEMPT` 白名单**删掉**了
#: （旧白名单自己写着"豁免得手也要 FAIL"）。
#: ⭐⭐ **`command/` 已于 `D-560`（2026-09-30 用户裁定）移出本表** —— 开发期/调试子命令已劈去 `debug/`，
#: `command/` 现在是**真产品面**（`D-554`：发行包玩家调试面 = 命令 ＋ GUI）⇒ 它**不许**依赖开发期物。
#: ⚠️ 这**不是放宽**而是**收窄**：原先 `debug/` 被当作产品面（故禁其依赖 `fixture/`），现在该断言
#: 改管 `command/`（真产品面），`debug/` 按 `D-560` 归**开发期桶**（见下方 `debug/` 分支）。
REGISTRATION_POSITIONS = {"item", "<root>"}
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


# ==================== ⭐ 2026-09-30「乙」：`pathing/calc/` 零写侧调用点 ====================
# 用户 2026-09-30 逐字：「**`core` 不加调用点是肯定的**」。
# 按 Baritone 拆成 `calc/`（内核 36）· `movement/`（执行器族 27）· `path/`（驱动器 4）之后，
# 这句话的**可执行形式** = `pathing/calc/` 里不得出现**写侧调用点**
# （带调用点的族在 `movement/`：实测 14 处 / 5 文件）。
# ⚠️ 判据只看"**调用**"，⛔ 不禁止 `calc/` 依赖 `movement/` 的接口 —— Baritone 的 `calc/` 同样要问
# `Moves`「你能做什么」（`AStarPathFinder` → `Moves`）⇒ 两包是**平级域**，方向由调用点划，不由 import 划。
CALC_DIR = f"{PKG}/pathing/calc/"   # ⚠️ rel 是相对 SRC 的全路径（含 `com/dddgn/alice`），⛔ 不是包名
MIN_CALC_FILES = 30
WRITE_CALL_RE = re.compile(
    r"(\.setBlockAndUpdate\(|\.setBlock\(|\.destroyBlock\(|\.removeBlock\(|\.setBlockState\("
    r"|BlockInteraction\.(beginBreak|placeAt)\(|BlockBreakSession\."
    r"|\.teleportTo\(|performPrefixedCommand|clickSlot|\.getController\("
    r"|WorldModLedger\.(record|openScope|closeScope))")


def calc_callpoints(rel: str, text: str) -> list[str]:
    """`pathing/calc/` 里只许有计算，⛔ 不许有写侧调用点（注释/import 不算）。"""
    if not rel.startswith(CALC_DIR):
        return []
    out = []
    for ln, line in enumerate(text.split("\n"), 1):
        s = line.strip()
        if s.startswith(("*", "//", "import ")):
            continue
        if WRITE_CALL_RE.search(line):
            out.append(f"{rel}:{ln} 出现写侧调用点 ⇒ `pathing/calc/` 是内核（无调用器），"
                       f"带调用点的执行器族必须住 `pathing/movement/` —— {s[:70]}")
    return out


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
#: ⚠️ 2026-10-01 刀 4（`D-566`）：`WriteAudit` 搬去 `ledger/ModifyAudit` ⇒ **6 → 5**
#: （`WRITE_GOVERNANCE` 同步收窄；⛔ 这个数**跟着名单走**，别两处各改一半）。
MIN_WRITE_FILES = 4
#: 刀 3（`D-566`）：`region/` 的**原样类**人口（实测 3 个 `.java` ＋ 1 个 `package-info.java`）。
MIN_REGION_FILES = 3

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
        # ⭐ 刀 2：判据从「只扫 import」升到 **`debug_fixture_code_use`（代码级：行内 FQN ＋ import＋简单名）**
        # —— 只挡 import 会漏 `new com.dddgn.alice.fixture.X()` 这一形态（`O90`/`D-557` 的教训）。
        for why in debug_fixture_code_use(rel, text):
            problems.append(f"{why} ⇒ 违反 `R3`／`D-560`：`command/`（**真产品面**）✗→ `debug/`／`fixture/`"
                            f"（开发期命令请放 `debug/`，⛔ 不许长回产品面）")
    elif tp not in REGISTRATION_POSITIONS and tp not in R3_EXCLUDED:
        for why in debug_fixture_code_use(rel, text):
            problems.append(f"{why} ⇒ 违反 `R3`：`{tp}/`（**生产包**）✗→ `debug/`／`fixture/`。"
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

_MB = f"{PKG}/action/mining/MineBlockRunner.java"
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
     "import com.dddgn.alice.pathing.path.PathRetryRunner;", False),
    ("`reach/` import `pathing` / `log` ⇒ 绿（同层/更下层）",
     f"{PKG}/reach/LineOfSightChecker.java",
     "import com.dddgn.alice.pathing.MovementHelper;\nimport com.dddgn.alice.log.BotLog;", False),
    ("`action/` import `action` 自己 ⇒ 绿",
     _MB,
     "import com.dddgn.alice.action.Attribution;", False),
    # ---- `step 4`（`D-462`）新增 ----
    ("`write/` import `action`（调用它的微操作）⇒ 红（方向只许单向）",
     f"{PKG}/write/Attribution.java",   # ⚠️ 臂的路径**必须留在 `write/` 下**（它测的就是 write/ 的方向）
     "import com.dddgn.alice.action.BlockInteraction;", True),
    # ---- `D-551`（用户 2026-09-30 裁「乙」）新增：`R3` 生产 ✗→ debug/fixture ----
    ("⭐ `job/`（生产包）import `debug/` **并在代码里用简单名** ⇒ 红",
     f"{PKG}/job/mine/MineJob.java",
     "import com.dddgn.alice.debug.SomeDebugTool;\nclass A { SomeDebugTool t; }", True),
    ("⭐ `job/`（生产包）**只 import、代码里没用**（纯 javadoc/import 形态）⇒ **绿**"
     "（`D-556` (c) 口径：文档引用/未用 import **不是**依赖）",
     f"{PKG}/job/mine/MineJob.java",
     "import com.dddgn.alice.debug.SomeDebugTool;", False),
    # ⭐ 刀 2（`D-512`）：`bot/` 移出 `REGISTRATION_POSITIONS` ⇒ 本臂由**绿改红**（断言收窄，
    # 与 `check-provision-containment.sh` 删 `BOT_EXEMPT` 是同一件事的两半）。
    ("⭐ `bot/`（**刀 2 起不再是注册位置**）import `debug/` **并在代码里用简单名** ⇒ **红**"
     "（刀 2 已把 48 个开发期入口搬去 `fixture/FixtureDispatch` ⇒ 豁免理由消失）",
     f"{PKG}/bot/BotManager.java",
     "import com.dddgn.alice.debug.SomeDebugTool;\nclass A { SomeDebugTool t; }", True),
    ("⭐ `bot/` 用**行内 FQN** 引用 `fixture/` ⇒ **红**（只挡 import 会漏这一形态）",
     f"{PKG}/bot/BotManager.java",
     "Object t = new com.dddgn.alice.fixture.ClearGuardCheckTask();", True),
    ("⭐ `debug/`（`D-560` 起是**开发期桶**）import `fixture/` ⇒ **绿**"
     "（原先它被当作产品面故为红 —— `D-560` 之后那条断言**改管 `command/`**，见下一臂）",
     f"{PKG}/debug/SomeDebugTool.java",
     "import com.dddgn.alice.fixture.transfer.TransferFixture;", False),
    ("⭐ `command/`（`D-560` 起是**真产品面**）import `fixture/` **并在代码里用简单名** ⇒ **红**"
     "（断言**搬家**不是删除 ⇒ 净效果是**收窄**：原先管一个包，现在还管产品面那个包）",
     f"{PKG}/command/BotCommand.java",
     "import com.dddgn.alice.fixture.transfer.TransferFixture;\nclass A { TransferFixture f; }", True),
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
     f"{PKG}/write/Attribution.java",
     "import com.dddgn.alice.job.lumber.LumberJob;", True),
    ("`write/` import `pathing` / `log`（更下层）⇒ 绿",
     f"{PKG}/write/WritePolicyMatrix.java",
     "import com.dddgn.alice.pathing.calc.PathRequest;\nimport com.dddgn.alice.log.BotLog;", False),
    ("`action/` import `write/`（微操作调授权）⇒ 绿（`step 4` 后的**正确**方向）",
     _MB,
     "import com.dddgn.alice.write.Attribution;", False),
]

#: 「定义」类红臂：`(label, rel, 期望红)` —— 走 `scan_definition`，与 import 臂分开。
SELFTEST_DEF_CASES: list[tuple[str, str, bool]] = [
    ("`action/` 里又出现一份 `Attribution` 的定义 ⇒ 红", f"{PKG}/action/Attribution.java", True),
    ("`action/mining/` 里的域执行件 `MineBlockRunner` ⇒ 绿（刀 1 之后它家在域子包）",
     f"{PKG}/action/mining/MineBlockRunner.java", False),
    ("`write/` 里的 `Attribution` ⇒ 绿（`step 4` 之后它的家）",
     f"{PKG}/write/Attribution.java", False),
]


SELFTEST_CALC_CASES: list[tuple[str, str, str, bool]] = [
    ("⭐ `pathing/calc/` 里出现 `BlockInteraction.placeAt(...)` ⇒ **红**",
     f"{PKG}/pathing/calc/CostModel.java",
     "class A { void f(){ BlockInteraction.placeAt(bot, level, pos, false, grant); } }", True),
    ("⭐ `pathing/calc/` 里只有**查询**（`BlockInteraction.breakable`）⇒ **绿**"
     "（查询不是调用器；`calc/` 问 `movement/`「能不能」是合法的）",
     f"{PKG}/pathing/calc/CostModel.java",
     "class A { boolean f(){ return BlockInteraction.breakable(bot, level, pos, grant); } }", False),
    ("⭐ `pathing/movement/` 里出现写侧调用点 ⇒ **绿**（它就是干这个的）",
     f"{PKG}/pathing/movement/PillarExecution.java",
     "class A { void f(){ BlockInteraction.placeAt(bot, level, pos, false, grant); } }", False),
]


# ==================== ⭐ 2026-10-01 刀 1：`action/`「原语住根 · 域执行件住子包」 ====================
# 用户 2026-10-01 裁定：① 层链出路 = **(甲)**（`pathing/` 跨两层，层链**按子包**声明）·
# ② 顶包名**保持 `action/`** · ⭐ ③「**`act/` 只装执行件，以及这些执行件的调用器**」——
# 「**维持单向依赖链**」是它的**机械可检形式**，⛔ 不是理由；「有没有专属包」只是**结果**，不能当判据。
#
# 落地 = `action/mining/`（域执行件：`MineBlockRunner` · `ChainMining`）＋ `action/` 根（跨域共享原语 5 个）。
# 三条断言（每条都能用一次注入变红）：
#   ① ⭐ **根 ✗→ 域子包**：`action/*.java`（**非递归**）不得引用 `com.dddgn.alice.action.<域>.`
#      —— 根是**跨域共享原语**（`D-455` 的「微操作」），它⛔不认识任何一个域执行件；
#      反过来 **域子包 → 根 是允许的**（执行件当然要用原语）。
#   ② ⭐ **低层 ✗→ `action/<域>/`**：`pathing/`·`reach/`·`write/`·`log/`·`ledger/`（层链里在
#      `action/` **之下**的那些）不得引用域子包。⚠️ 今天成立**只是运气** —— 实测生产侧**没有任何一处**
#      从下面调 `MineBlockRunner`；没有门禁，`survey/42 §1.2` 那个环（`action ↔ pathing`）
#      随时会**换个名字长回来**（`O110` ⑧ 逐字：「今天成立只是运气」）。
#   ③ ⭐ **人口下限 / 反空转**：域子包必须真的存在且非空 —— ⛔ 不许靠"把域搬空"让 ①② 空过。
ACTION_DIR = f"{PKG}/action/"
#: `action/` 下的**域子包**（域执行件的家）。⛔ 加新域时同刀加人口下限。
ACTION_DOMAINS = ("mining",)
ACTION_DOMAIN_DIRS = tuple(f"{PKG}/action/{d}/" for d in ACTION_DOMAINS)
#: `action/` **根**原语的实测人口（刀 1 之后 = 5；`step 4` 之后原 6 个里的 `MineBlockRunner` 进了 `mining/`）。
MIN_ACTION_ROOT_FILES = 5
#: 域子包人口下限（刀 1 实测 = 2：`MineBlockRunner` · `ChainMining`）。
MIN_ACTION_DOMAIN_FILES = 2
#: ⭐ 低层包（层链里在 `action/` **之下**）—— 它们 ✗→ `action/<域>/`。
LOW_LAYERS = ("pathing", "reach", "write", "log", "ledger")
#: `action/<域>` 的全限定名（**剥注释与字符串字面量后**再扫 ⇒ 路径指针字符串不算依赖，`D-556` (c)）。
ACTION_DOMAIN_FQN_RE = re.compile(
    r"com\.dddgn\.alice\.action\.(?:" + "|".join(ACTION_DOMAINS) + r")\.")


def _domain_ref(text: str) -> tuple[list[str], bool]:
    """⇒ (`action/<域>` 的 import 列表, 代码里有没有**行内** FQN)。注释/字符串都不算。"""
    imports = [i for i in imports_of(text)
               if any(i.startswith(f"com.dddgn.alice.action.{d}.") for d in ACTION_DOMAINS)]
    inline = bool(ACTION_DOMAIN_FQN_RE.search(strip_to_code(text)))
    return imports, inline


def action_layer_edges(rel: str, text: str) -> list[str]:
    """①②两条层序断言（`action/` 原语住根 · 域执行件住子包）。"""
    imports, inline = _domain_ref(text)
    if not imports and not inline:
        return []
    where = "、".join(f"`{i}`" for i in imports) if imports else \
        "**行内**全限定名 `com.dddgn.alice.action.<域>.…`"
    # ① 根（原语）✗→ 域子包
    if rel.startswith(ACTION_DIR) and not rel.startswith(ACTION_DOMAIN_DIRS):
        return [f"{rel}（`action/` **根**的原语）引用了域子包 {where} ⇒ 根是**跨域共享原语**，"
                f"⛔ 不认识任何一个域执行件（2026-10-01 刀 1）；域内私有件请留在 `action/<域>/` 里"]
    # ② 低层 ✗→ 域子包
    tp = top_pkg(rel)
    if tp in LOW_LAYERS:
        return [f"{rel}（`{tp}/`）引用了 `action/<域>/` 的 {where} ⇒ 层方向倒了：域执行件只许"
                f"**向上**被编排层（`task/`·`job/`·`bot/`）调用。⚠️ 若这是**新欠账**，"
                f"必须带到期条件显式登记进本文件的 `ALLOWED_REVERSE` —— ⛔ 不许静默"]
    return []


SELFTEST_ACTION_CASES: list[tuple[str, str, str, bool]] = [
    ("⭐ `action/` **根**的原语引用域子包 `action/mining/` ⇒ **红**",
     f"{PKG}/action/BlockInteraction.java",
     "import com.dddgn.alice.action.mining.MineBlockRunner;", True),
    ("⭐ `action/mining/` 的域执行件引用**根**原语 ⇒ **绿**（执行件本来就该能用原语）",
     f"{PKG}/action/mining/MineBlockRunner.java",
     "import com.dddgn.alice.action.BlockInteraction;", False),
    ("⭐ `pathing/`（**低层**）引用 `action/mining/` ⇒ **红**（层方向倒了）",
     f"{PKG}/pathing/calc/CostModel.java",
     "class A { void f(){ com.dddgn.alice.action.mining.MineBlockRunner g = null; } }", True),
    ("⭐ `write/` 里**字符串字面量**写着 `action/mining/MineBlockRunner.java:158` ⇒ **绿**"
     "（`D-556` (c)：路径指针字符串不是依赖）",
     f"{PKG}/write/WritePolicyMatrix.java",
     'class A { String s = "com.dddgn.alice.action.mining.MineBlockRunner"; }', False),
    ("⭐ `reach/` 的**注释**里提到 `action/mining/MineBlockRunner` ⇒ **绿**"
     "（`D-556` (c)：文档引用不是依赖）",
     f"{PKG}/reach/ReachPlan.java",
     "class A {\n    // 执行期 com.dddgn.alice.action.mining.MineBlockRunner 自己复核视线\n}", False),
]


def selftest() -> list[str]:
    problems: list[str] = []
    # ⚠️ 合成红臂会往 `_JAVADOC_ONLY` 里塞**假条目**（如 `MineJob.java→SomeDebugTool`）⇒
    #    它会污染 PASS 行的"纯 javadoc/import 不计：N 处"读数（读起来像真代码）。
    #    ⇒ 红臂期间**快照并还原**这个诊断清单（红臂只判红绿，不产出读数）。
    _javadoc_snapshot = list(_JAVADOC_ONLY)
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
    for label, rel, body, expect_red in SELFTEST_CALC_CASES:
        found = calc_callpoints(rel, body)
        is_red = bool(found)
        if is_red != expect_red:
            problems.append(f"calc 调用点臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、实得"
                            f"{'红' if is_red else '绿'}（{found}）")
    for label, rel, body, expect_red in SELFTEST_ACTION_CASES:
        found = action_layer_edges(rel, body)
        is_red = bool(found)
        if is_red != expect_red:
            problems.append(f"action 层序臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、实得"
                            f"{'红' if is_red else '绿'}（{found}）")
    _JAVADOC_ONLY[:] = _javadoc_snapshot
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
    calc_files = under("pathing/calc")
    debt_hits: dict[str, list[str]] = {}

    for path in files:
        rel = rels[path]
        text = path.read_text(encoding="utf-8")
        problems.extend(scan_imports(rel, text))
        problems.extend(scan_definition(rel))
        problems.extend(calc_callpoints(rel, text))
        problems.extend(action_layer_edges(rel, text))
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
    if len(calc_files) < MIN_CALC_FILES:
        problems.append(f"`pathing/calc/` 只有 {len(calc_files)} 个文件（下限 {MIN_CALC_FILES}）⇒ "
                        f"2026-09-30「乙」拆出来的内核被搬回去/被删了")
    # ⭐ 刀 1（2026-10-01）：`action/` 根原语人口 ＋ 每个域子包的人口（反空转 ③）
    action_root_files = [p for p in action_files if not rels[p].startswith(ACTION_DOMAIN_DIRS)]
    domain_counts = {d: len(under(f"action/{d}")) for d in ACTION_DOMAINS}
    if len(action_root_files) < MIN_ACTION_ROOT_FILES:
        problems.append(f"`action/` **根**只有 {len(action_root_files)} 个 `.java`（下限 "
                        f"{MIN_ACTION_ROOT_FILES}）⇒ 跨域共享原语被搬走/被删（刀 1 之后根 = 5 个）")
    for d, n in domain_counts.items():
        if n < MIN_ACTION_DOMAIN_FILES:
            problems.append(f"`action/{d}/` 只有 {n} 个 `.java`（下限 {MIN_ACTION_DOMAIN_FILES}）"
                            f"⇒ 域子包被搬空 ⇒ 断言①②会**空过**（反空转 ③）")
    missing = [n for n in WRITE_GOVERNANCE if not (SRC / PKG / "write" / f"{n}.java").exists()]
    if missing:
        problems.append(f"`write/` 里缺 {missing} ⇒ 「拆包」不成立（拆包不是删除；`step 4`/`D-462`）")

    # ⭐ 刀 4（`D-566`）：`write/` → `ledger/` 与 `write/` → `region/authz/` 的**搬包**判据。
    authz_quota = SRC / PKG / "region" / "authz" / "Quota.java"
    if not authz_quota.exists():
        problems.append("`region/authz/` 里缺 `Quota.java` ⇒ 刀 4 第 3 件没落地（`D-566`）")
    if (SRC / PKG / "write" / "Quota.java").exists():
        problems.append(f"`{PKG}/write/Quota.java` 还在 ⇒ 刀 4 第 3 件的搬包没做完（`D-566`）")
    for name in LEDGER_MOVED:
        if not (SRC / PKG / "ledger" / f"{name}.java").exists():
            problems.append(f"`ledger/` 里缺 `{name}.java` ⇒ 「搬包」不成立（搬包不是删除；`D-566` 刀 4）")
        if (SRC / PKG / "write" / f"{name}.java").exists():
            problems.append(f"`{PKG}/write/{name}.java` 还在 ⇒ 刀 4 的搬包没做完"
                            f"（同一层不许有两份定义；`D-566`）")

    # ⭐ **刀 4 第 4 件**（2026-10-01，用户裁定「拆三个谓词后定名」）：`protection/ZoneAuthority`
    # **整体消失** ⇒ 三处落点各查一次（搬包不是复制 + 拆包不是删除 + **入口唯一**）。
    for name in AUTHZ_MOVED:
        if not (SRC / PKG / "region" / "authz" / f"{name}.java").exists():
            problems.append(f"`region/authz/` 里缺 `{name}.java` ⇒ 刀 4 第 4 件（拆三个谓词）没落地")
        if (SRC / PKG / "protection" / f"{name}.java").exists():
            problems.append(f"`{PKG}/protection/{name}.java` 还在 ⇒ 拆分没做完"
                            f"（同一层不许有两份定义；`D-566`）")
    for name in AUTHZ_DISSOLVED:
        if (SRC / PKG / "protection" / f"{name}.java").exists():
            problems.append(f"`{PKG}/protection/{name}.java` 回来了 ⇒ 拆出去的判据又被合回一个类"
                            f"（结构提案 `§5`：「三者不许再合成一个函数」）")
    # ⭐ **入口唯一由编译器保证**：E 维那件**必须包内可见**（`final class`，⛔ 不是 `public final class`）
    # —— 它一旦 public，外部就能绕开 `AreaPermission` 直接问 E 维 = 出现**第二个授权入口**。
    level_path = SRC / PKG / "region" / "authz" / "AreaPermissionLevel.java"
    if level_path.exists():
        level_decl = [ln for ln in level_path.read_text(encoding="utf-8").splitlines()
                      if "class AreaPermissionLevel" in ln]
        if not level_decl:
            problems.append("`AreaPermissionLevel` 找不到类声明（结构变了 ⇒ 本判据要跟着改）")
        elif any(ln.lstrip().startswith("public") for ln in level_decl):
            problems.append("`AreaPermissionLevel` 成了 `public` ⇒ 出现**第二个授权入口**"
                            "（E 维只许由 `AreaPermission.authorize` 调用；结构提案 `§5`）")

    # ⭐ 刀 3（`D-566`，2026-10-01）：`protection/` → `region/` 的**搬包**判据。
    # 反空转 ③：`region/` 被搬空/被删 ⇒ 红；且**两个包不许各留一份**（搬包 = 移动，⛔ 不是复制）。
    region_files = under("region")
    if len(region_files) < MIN_REGION_FILES:
        problems.append(f"`region/` 只有 {len(region_files)} 个 `.java`（下限 {MIN_REGION_FILES}）"
                        f"⇒ 刀 3 搬出来的 3 个原样类被搬回去/被删了")
    for name in REGION_MOVED:
        if not (SRC / PKG / "region" / f"{name}.java").exists():
            problems.append(f"`region/` 里缺 `{name}.java` ⇒ 「搬包」不成立（搬包不是删除；`D-566`）")
        if (SRC / PKG / "protection" / f"{name}.java").exists():
            problems.append(f"`{PKG}/protection/{name}.java` 还在 ⇒ 刀 3 的搬包没做完"
                            f"（同一层不许有两份定义；`D-566`）")

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
    arms = (len(SELFTEST_CASES) + len(SELFTEST_DEF_CASES) + len(SELFTEST_CALC_CASES)
            + len(SELFTEST_ACTION_CASES))
    javadoc_only = sorted(set(_JAVADOC_ONLY))
    print(f"LAYER_DIRECTION_RESULT PASS: `reach/` 反向依赖 0 · `write/` 反向依赖 0 · `action/` → `task/` 欠账 "
          f"{sum(len(v) for v in debt_hits.values())} 条「{debt or '无（step 3b 后已是无条件 0 命中）'}」 · "
          f"reach {len(reach_files)} 文件 / action {len(action_files)} 文件 / write {len(write_files)} 文件 "
          f"（写入治理类 5 个全在）· `R3` 注册位置 {sorted(REGISTRATION_POSITIONS)} 零违规 · "
          f"⭐ `task/` 生产类（{len(_PROD_DESTS)} 个 `dest ∈ {{生产, step}}`）✗→ `debug/`／`fixture/` "
          f"**代码级** 0 命中"
          f"（纯 javadoc/import 不计：{len(javadoc_only)} 处 {javadoc_only or '无'} —— `D-556` (c)） · "
          f"⭐ `pathing/calc/`（内核 {len(calc_files)} 文件）**零写侧调用点**"
          f"（用户 2026-09-30：「`core` 不加调用点是肯定的」） · "
          f"⭐ `action/` 原语住根 · 域执行件住子包（2026-10-01 刀 1）：根 {len(action_root_files)} 文件 "
          f"✗→ 域子包 **0** · 低层 {sorted(LOW_LAYERS)} ✗→ `action/<域>/` **0** · "
          f"域子包 " + " · ".join(f"`action/{d}/` {n} 文件" for d, n in sorted(domain_counts.items())) + " · "
          f"⭐ `region/` {len(region_files)} 文件（刀 3/`D-566`：{len(REGION_MOVED)} 个原样类全在、"
          f"`protection/` 里零残留） · ⭐ `ledger/ModifyAudit` 在、`write/` 里零残留（刀 4/`D-566`） · "
          f"⭐ `region/authz/` 裁决面 {len(AUTHZ_MOVED)} 谓词 ＋ `Quota` 全在、"
          f"**入口唯一**（E 维包内可见）、`protection/{AUTHZ_DISSOLVED[0]}` 零残留（刀 4 第 4 件） · "
          f"扫描 {len(files)} · 红臂 {arms}/{arms}"
          f"（import {len(SELFTEST_CASES)} + 定义 {len(SELFTEST_DEF_CASES)} + calc {len(SELFTEST_CALC_CASES)}"
          f" + action 层序 {len(SELFTEST_ACTION_CASES)}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
