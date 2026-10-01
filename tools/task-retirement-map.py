#!/usr/bin/env python3
"""`P0` **定去处**：把 `task/` 整包退役的"每个类去哪"生成成台账（`D-550` §五 / `D-551`）。

## 判据（`R1′` 四桶，优先级 **`step` > `debug` > `fixture` > `生产`**）

1. **`step`** —— 结构性事实优先：`task/Step.java`（注册口本身）或声明里 `implements … Step`
   ⇒ 原语，家 = 包 `com.dddgn.alice.step`。
   ✅ **2026-09-30 `P1` 立家已建包** —— 同刀把 `P0` **判据②**（目的地 ∈ **已建成的包**）
   从「只印」变成**能红**：`step/` · `debug/` · `fixture/` 任一不存在 ⇒ 红。
2. **`debug`** —— 玩家可达：**(a)** 被 `item/` 或 `command/` **直接**引用（剥注释），或
   **(b)** 在**派发表**（`docs/TASK_DISPATCH_TABLE.csv`）里 **`cmd_reachable=yes`**（⭐ 2026-09-30 改口径：
   产品面 = **命令可达**；`entry_reachable` 含 `item/` 种子 ⇒ ⛔ 不再当产品面判据）。
   ⛔ (b) 不能自己猜 —— 它是 `丙` 方案（`D-551`）的产物，`BotManager` 才是真派发枢纽。
3. **`fixture`** —— **只**被验证侧引用：引用者全落在 `task/**` · `fixture/**` · `headless/**` · `tools/**`。
4. **`生产`** —— 其余（被生产层引用）⇒ 逐类在**波 4** 定到具体层。⭐ 目的地由下节的
   **`PROD_HOME`（生产层包名）**一维表达。

## ⭐ 「生产层包名」一维（`O92` ② 逐字要求；2026-10-01 扩）

`P0` 判据① 逐字：「`task/` 下**每个类**登记一个目的地：`debug/` · `fixture/` · `step/` · **生产层包名**」
⇒ `dest=生产` 的行**必须**有一维说出它**搬到哪个包**，否则 `P4`（`task/` 全树 0）没有判据可核。

- **载体 = `PROD_HOME`**（本文件里的**手写**表：`类名 → 目标包全名`）。⚠️ 它**不能**由分类器推出来
  —— 分类器只知道"这是生产类"，不知道"它该住哪"（那是**裁定**）。
- **哨兵 `?`** = **未定家**（`O92` ⑥ 的 35 个类今天全在这）。⛔ 不许用"猜一个包名"代替 `?`。
- **判据（⛔ 全部今天绿、只对**新违规**红 —— 同 `D-551` ① 的 `P2` 提前上线口径，不撞 `R4`）**：

  | # | 判据 | 抓什么 |
  |---|---|---|
  | ① | 每个 `dest=生产` 的台账行**必须**在 `PROD_HOME` 里有条目 | 漏登记（⛔ 不许静默留空） |
  | ② | `PROD_HOME` 的键若**已不在** `task/`（搬走了）⇒ 值**必须** ≠ `?` | **未定家不许搬** |
  | ③ | ② 的值 ⇒ 目标包必须**存在** ＋ 该包下必须**有** `<类>.java` | 搬到别处 / 指空（`P0` 判据②「⛔ `task` 不是合法目的地」） |
  | ④ | 值 ≠ `?` 但台账行**还在** `task/` ⇒ 目标包里**不许**已经有同名文件 | 「搬包不是**复制**」（同 `check-layer-direction` 的 `LEDGER_MOVED` 口径） |

  ⇒ ⛔ 今天**没有**一条要求"`?` 必须变成值"：那会**凭空发明 35 个裁定**。⭐ 但 `?` 的**计数**
  （`待裁 N`）**必须**打进 PASS 读数 —— 它是波 4 的**进度表**，不是可以眼不见的待办。

## ⛔ 两条纪律

- **不记行号**：本台账是生成物，行号会随无关编辑腐烂（`code_ref` 的债，批次 2 ③）⇒
  键 = `类名`，另存 `task_path`（**全仓相对**）。
- **双向防漂移**：漏行、陈旧行、路径不符、`dest` 非法 ⇒ **红**；并做**交叉核** ——
  派发表里 **`cmd_reachable=yes`** 的类在台账里**必须**是 `debug`，否则红。

用法：
    python3 tools/task-retirement-map.py --write   # 重新生成 docs/TASK_RETIREMENT_MAP.csv
    python3 tools/task-retirement-map.py           # 门禁
"""
from __future__ import annotations

import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_REL = "src/main/java/"
TASK_DIR = SRC_REL + "com/dddgn/alice/task"
MAP_OUT = ROOT / "docs/TASK_RETIREMENT_MAP.csv"
DISPATCH = ROOT / "docs/TASK_DISPATCH_TABLE.csv"

VERIFY_SIDE = {"task", "fixture", "headless", "tools"}   # 验证侧的**一级包**


def is_verify_side(pkg: str, prod_task: frozenset[str] = frozenset()) -> bool:
    """⚠️ 必须按**前缀**判，⛔ 不能拿整串去 `set` 里比 —— `rel_pkg()` 对子包返回**路径式**
    （`fixture/check/modules`），早期写成 `refset <= {"task", …}` ⇒ 全部落空，
    实测把 110 个夹具误判成"生产"。

    ⭐ 2026-09-30（夹具波实测踩到，本次修）：`task/` **不再整体**算验证侧 ——
    `task/` 里同时住着**生产/原语**类，而"只被 `task/` 引用 ⇒ `fixture/`"会把
    **生产执行路径上的类**判成"可剔除"。实测受害者 **4 个**：`GainStepRunner`（被 `MineTask`+`CollectStep`）·
    `FixtureClaim`（被 `ScaffoldLifecycleTask`）· `MachineRecipeFacts`（被 `RecipeQuery`）·
    `TableCraft`（被 `CraftStation`+`MachineCycle`）。
    ⇒ 引用者若是 `task/` 下的 `.java`，引用**按类**记成 `task:<Stem>` 标签，
    本函数再按那个类的**判定的 dest** 回判（`prod_task` = 已判为 `生产`/`step` 的类集合）⇒ **迭代到不动点**。
    """
    if pkg.startswith("task:"):
        return pkg[len("task:"):] not in prod_task
    head = pkg.split("/")[0]
    return head in VERIFY_SIDE and head != "task"
def _is_prod_ref_impl(tag: str, prod_task: frozenset[str]) -> bool:
    if tag.startswith("task:"):
        return tag[len("task:"):] in prod_task
    head = tag.split("/")[0]
    return head not in VERIFY_SIDE and head not in REG_POS


#: ⭐ **产品面入口**（2026-09-30 用户裁「甲」）= **只有 `command/`**；⚠️ 刀 1（`D-560`）之后
#: `command/` 里只剩 **14 条产品面命令**（28 条开发期子命令已劈去 `debug/`）⇒ 本表**这才名副其实**。
ENTRY_SIDE = {"command"}
#: 开发期入口（保留识别，只为 `reason` 可读）：被它引用的类**不因此进 `debug/`**。
#: ⭐ `D-560`（刀 1）：**`debug/` 也是开发期入口**（28 条开发期/调试子命令住那里）——
#: 它引用某类**不足以**证明那个类在生产执行路径上，⚠️ 但也**必须被认出来**，否则只经开发期命令
#: 可达的类会静默掉出"玩家可达"口径。
DEV_ENTRY_SIDE = {"item", "debug"}
#: **注册位置**（`D-552`）：它们引用某类**不足以**证明那个类是"生产执行路径"上的 ——
#: 它们只做注册／派发（`/give` 物品、`/alice` 命令、`BotManager.assignXxx`、模组入口）。
#: ⭐ `D-560`（刀 1）：`debug/` 同属"注册／派发"位置。
REG_POS = {"item", "command", "debug", "bot", "<root>"}


def is_prod_ref(tag: str, prod_task: frozenset[str]) -> bool:
    """这条引用是否来自**生产执行路径**（⇒ 被引用的类**不可能**是"可剔除"的，`R6`）。

    ⚠️ 2026-09-30（夹具波实测）：判据原来只看"是不是夹具命名"，于是 **`FixtureClaim`**（名字带 `Fixture`）
    即使被**生产**类 `ScaffoldLifecycleTask` 引用也仍判 `fixture/` —— 而它在生产执行路径上。
    ⭐ `R6` 逐字：「要物理剔除，剔的是 `fixture/`」⇒ **被生产引用的东西不可能同时是"可剔除"的**。
    """
DESTS = ("step", "debug", "fixture", "生产")
#: ⭐ 2026-10-01（`O92` ② 同刀）：多出 `prod_pkg` 一列（**生产层包名**）。
#: ⚠️ 插在 `dest` **后面**、`reason` **前面** —— `reason` 里**含逗号**（`生产:refs=job/lumber,task`）
#: ⇒ 它是"**吃掉剩下全部字段**"的尾巴，新列只能插在它前面（插在后面解析不回来）。
HEADER = "task_class,task_path,dest,prod_pkg,reason"
#: ⛔「不适用」（`dest ∈ {step,debug,fixture}` —— 那些行的目的地**就是** `dest` 本身）。
NA = "-"
#: ⭐ **未定家**的哨兵。含义逐字 = 「这个类的生产层包名**还没被裁定**」。
#: ⛔ 不许拿一个猜的包名顶替它（那会把"待裁"洗成"已裁"）。
UNDECIDED = "?"

#: ⭐⭐⭐ **`生产层包名`（`PROD_HOME`）—— 手写表，`类名 → 目标包全名 | "?"`**（2026-10-01 建）。
#:
#: **规矩**：这是**裁定**的落点，⛔ 不是分类器的输出 ⇒ **我只在用户裁定之后填**，⛔ 不自行猜。
#: ⚠️ **2026-10-01 二次更新**：`D-569`（用户三次追加裁定）定下**三条规矩**（`action/` 准入 · **临时包** · **先搬优先**）
#: ⇒ 已按 **R3「有专属包/非动作 ⇒ 马上搬」** 填了 **5** 格：维生 4 ⇒ `survival/`（用户批它**唯一**可单立 ——
#: 理由逐字「`survival` 是**拥有 `step` 打断权的特殊行为**，所以它必须单独开包」）＋ `RoadBuildTask` ⇒ `road/`。
#: ⛔ 余下 30 格仍 `?`：它们**要拆解/改造**（用户：「当前迁移过来的对象，要被拆解或者改造成 `step`、`job`」）
#: ⇒ 最终家要靠**工程**定，⛔ 不是一次 `git mv`。⚠️ 其中 `transfer/` 那 2 格**两说并存**（`D-569` §三 第 2 条）。
#: (旧注) 35 条曾全是 `?` —— 因为 `O92` ⑥ 逐字写着"待用户裁定"，
#: 至今未裁（⚠️ `§38.3` 那 8 格**不含**它；那 8 格已由 `D-568` 裁完 ≠ 这一格已裁）。
#: ⭐ 填一条的动作 = 把 `"?"` 换成包全名，门禁**立刻**开始管它（`R4`：门禁与迁移同刀）。
PROD_HOME: dict[str, str] = {
    # ——— `task/` 顶层 20 个（`docs/TASK_TOP_LEVEL_FREEZE.txt` 的 22 行里除去 `Step`／`FixtureScript`）———
    "CollectDropsTask": UNDECIDED,
    "FarWalkTask": UNDECIDED,
    "FixtureClaim": UNDECIDED,
    "FollowTask": UNDECIDED,
    "MineTask": UNDECIDED,
    "PermissionDemoTask": UNDECIDED,
    "PlaceTask": UNDECIDED,
    "RestoreScopeTask": UNDECIDED,
    "RoadBuildTask": "com.dddgn.alice.road",
    "SafeReturnTask": "com.dddgn.alice.survival",
    "ScaffoldLifecycleTask": UNDECIDED,
    "SurvivalExit": "com.dddgn.alice.survival",
    "SurvivalExitTask": "com.dddgn.alice.survival",
    "SurvivalFloatTask": "com.dddgn.alice.survival",
    "Task": UNDECIDED,
    "TaskNode": UNDECIDED,
    "TaskTarget": UNDECIDED,
    "ToolMaintenanceTask": UNDECIDED,
    "TransferTask": UNDECIDED,
    "WalkToTask": UNDECIDED,
    # ——— `task/craft/` 10 个 ———
    "CraftStation": UNDECIDED,
    "FurnaceStation": UNDECIDED,
    "GridDiscovery": UNDECIDED,
    "InventoryCraft": UNDECIDED,
    "MachineCycle": UNDECIDED,
    "MachineRecipeFacts": UNDECIDED,
    "RecipeQuery": UNDECIDED,
    "StationPlacement": UNDECIDED,
    "StationProvision": UNDECIDED,
    "TableCraft": UNDECIDED,
    # ——— `task/mining/` 5 个 ———
    "BlockerClearPlanner": UNDECIDED,
    "GainStepRunner": UNDECIDED,
    "MiningBudget": UNDECIDED,
    "MiningPlanner": UNDECIDED,
    "MiningProfile": UNDECIDED,
}
#: ⭐ 2026-09-30（夹具波同刀）：人口下限从**写死的常数**改成**跨源对账**（同 `check-task-top-freeze.py`）：
#: `task/` 顶层是**设计成走向 0** 的数 ⇒ 静态下限（原 `MIN_ROWS = 150`）在第二波当场变假红发生器。
#: ⇒ 判据 = 「本台账的**顶层**行数 **==** `docs/TASK_TOP_LEVEL_FREEZE.txt` 行数」。
#: ⚠️ `P4` 关门后随门禁退役，⛔ 不许靠 `0 == 0` 空过。
FREEZE_TXT = ROOT / "docs/TASK_TOP_LEVEL_FREEZE.txt"
DEST_PKG = {"debug": SRC_REL + "com/dddgn/alice/debug",
            "fixture": SRC_REL + "com/dddgn/alice/fixture",
            "step": SRC_REL + "com/dddgn/alice/step"}


def listed(directory: str, suffix: str = ".java") -> list[Path]:
    """⚠️ **走文件系统，⛔ 不用 `git ls-files`**（注入臂实测）：后者**只列已跟踪文件** ⇒
    刚新建、还没 `git add` 的文件**不进读数** ⇒ 门禁静默放过。
    ⚠️ 路径解析：**先按 `ROOT/` 原样找**（`src/main/java`、`tools`），找不到再按**包路径**拼
    `src/main/java/`（`com/dddgn/alice/task`）—— 早期无条件拼前缀，把 `tools` 拼成
    `src/main/java/tools` ⇒ **静默空集**（那时没有断言）⇒ `tools/**` 从未进过引用者池。
    """
    cand = ROOT / directory
    if not cand.is_dir():
        cand = ROOT / SRC_REL / directory     # 包路径（如 `com/dddgn/alice/task`）
    if not cand.is_dir():
        raise SystemExit(f"空集！目录={directory} ⇒ 路径解析失败（⛔ 不许静默返回空集）")
    out = sorted(p for p in cand.rglob("*" + suffix) if p.is_file())
    if not out:
        raise SystemExit(f"空集！目录={directory} 后缀={suffix} ⇒ 收集器坏了")
    return out


def strip_comments(text: str) -> str:
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            out.append(text[i:min(j + 1, n)])
            i = min(j + 1, n)
        else:
            out.append(c)
            i += 1
    return "".join(out)


def rel_pkg(path: Path) -> str:
    """⇒ `task` / `fixture/check/modules` / `item` / `tools` …（相对 `com/dddgn/alice/`）"""
    try:
        rel = path.relative_to(ROOT / SRC_REL).parts
    except ValueError:
        return "tools"
    if rel[:3] != ("com", "dddgn", "alice"):
        return "<other>"
    if len(rel) == 4:          # 根包文件（`com/dddgn/alice/AliceMod.java`）⇒ 不是包
        return "<root>"
    return "/".join(rel[3:-1])


REFSET: dict[str, set[str]] = {}
STEP_DECL = re.compile(r"\bimplements\s+[^{;]*\bStep\b")


# `R2` 的枚举：`debug/` = `*DiagnosticTask` · `*ProbeTask` · **双向可达的** `*CheckTask`
DEBUG_MARK = re.compile(r"(Check|Probe|Diagnostic)[A-Za-z0-9_]*$|(Check|Probe|Diagnostic)$")
# `R2` 的 `fixture/` 枚举：`*Regression*` / `*Battery*` / `Fixture*` / 课程锚点 / harness
FIXTURE_MARK = re.compile(r"(Regression|Battery|Fixture|CourseAnchor|Bench)")
CHECK_MARK = re.compile(r"(Check|Probe|Diagnostic)")


def is_debug_mark(cls: str) -> bool:
    return bool(CHECK_MARK.search(cls))


def is_fixture_mark(cls: str) -> bool:
    return bool(FIXTURE_MARK.search(cls))


def is_step_primitive(path: Path) -> bool:
    if path.stem == "Step":
        return True
    return bool(STEP_DECL.search(strip_comments(path.read_text(encoding="utf-8"))))


def load_dispatch() -> dict[str, tuple[str, str]]:
    """⇒ {类名: (entry_reachable, cmd_reachable)}；⛔ 表缺失/空 ⇒ 响亮失败（不许静默当成"全不可达"）。

    ⭐ 2026-09-30（用户裁「甲」）：**判"产品面"只看 `cmd_reachable`** —— 发行包的玩家调试面载体
    = **命令 ＋ GUI 按钮（按钮调命令）**，而 `/give` 物品退为**开发期入口**。
    ⚠️ `entry_reachable` 的种子**包含 `item/`** ⇒ 它**不能**再当"产品面可达性"用（那正是旧口径的词）。
    """
    if not DISPATCH.exists():
        raise SystemExit(f"TASK_RETIREMENT_MAP_RESULT FAIL: 缺 `{DISPATCH.relative_to(ROOT)}` "
                         f"⇒ 先跑 `tools/task-dispatch-table.py --write`")
    lines = [ln for ln in DISPATCH.read_text(encoding="utf-8").split("\n") if ln.strip()]
    if len(lines) < 20:
        raise SystemExit(f"TASK_RETIREMENT_MAP_RESULT FAIL: 派发表只有 {len(lines)} 行（下限 20）"
                         f" ⇒ 读不动或已被清空")
    out: dict[str, tuple[str, str]] = {}
    for ln in lines[1:]:
        parts = ln.split(",")
        if len(parts) != 4:
            continue
        cls, _disp, entry, cmd = parts
        cur = out.get(cls, ("no", "no"))
        # 同一类可能挂在多个派发方法下 ⇒ 取"或"（任一方法可达即算可达）
        out[cls] = ("yes" if (entry == "yes" or cur[0] == "yes") else "no",
                    "yes" if (cmd == "yes" or cur[1] == "yes") else "no")
    return out


# `R3` 的**注册位置**（用户 2026-09-30 裁「乙」把 `bot/` 加进来；`<root>` = 模组入口 `AliceMod`）
# ＋ 验证侧 ⇒ 这两类引用**不算** `R3` 风险。⚠️ 与 `tools/check-layer-direction.py` 的
# `REGISTRATION_POSITIONS` **必须保持同一套**（改一处就得改另一处，判据在那边执行）。
# ⚠️⚠️ `D-560`（刀 1）之后两边**故意不同**：那边已把 `command/` 移出（它现在是**真产品面**，
# ⛔ 不许依赖开发期物）；本表是"这条引用算不算生产风险"的口径，`command/` 仍然只做注册／派发
# ⇒ 留在这里。⭐ `debug/` 两边都新增。
NON_PROD = VERIFY_SIDE | {"item", "command", "debug", "bot", "<root>"}


def r3_risks(rows: list[tuple[str, str, str, str]], refs_by_cls: dict[str, set[str]]) -> list[str]:
    """**迁移前必须解决**的表：某类的目的地是 `debug/`／`fixture/`／`step/`，
    却有**生产包**引用它 ⇒ 搬过去就违反 `R3`（生产 ✗→ 两者）。⛔ 今天不是红（没搬就没错），
    是 `P1` 立家的**待办**。"""
    out = []
    for cls, _path, dest, _reason in rows:
        if dest == "生产":
            continue
        prod = sorted({x for x in refs_by_cls.get(cls, ()) if x.split("/")[0] not in NON_PROD})
        if prod:
            out.append(f"{cls} → {dest}／ 但被生产包引用：{','.join(prod)}")
    return out


def build_rows() -> list[tuple[str, str, str, str]]:
    dispatch = load_dispatch()
    pool: list[tuple[Path, str]] = []
    for directory in ("src/main/java", "tools"):
        for p in listed(directory, ".java" if directory.startswith("src") else ".py") + \
                (listed(directory, ".sh") if directory == "tools" else []):
            try:
                body = strip_comments(p.read_text(encoding="utf-8", errors="replace"))
                # ⚠️ 2026-09-30（夹具波实测）：**`import` 行不算"引用"**。
                # 实测受害者：`task/FixtureScript.java` 对 `fixture/PathingRegressionTask` 只有
                # javadoc `{@link}` ＋ 为它补的一行 import（⛔ 零运行期依赖），却因此被判成"生产引用"
                # ⇒ 把一个纯夹具判成"不可剔除"。⇒ 判据改成「**代码里的使用**」。
                pool.append((p, "\n".join(l for l in body.splitlines()
                                          if not l.strip().startswith("import "))))
            except OSError as e:                                   # 读不动 ⇒ 响亮失败
                raise SystemExit(f"TASK_RETIREMENT_MAP_RESULT FAIL: 读不动 {p}: {e}") from e
    assert len(pool) > 400, f"引用者池只有 {len(pool)} 个文件 ⇒ 收集器坏了"

    def fqn_of(path: Path) -> str:
        rel = path.relative_to(ROOT / SRC_REL)
        return ".".join(rel.with_suffix("").parts)

    refs_by_cls: dict[str, set[str]] = {}
    tagged_by_cls: dict[str, set[str]] = {}
    repo_paths: dict[str, str] = {}
    path_by_cls: dict[str, Path] = {}
    entries: dict[str, str | None] = {}
    for p in listed(TASK_DIR):
        cls = p.stem
        pat = re.compile(rf"\b{re.escape(cls)}\b")
        refs: list[str] = []
        tagged: list[str] = []
        direct_entry: str | None = None
        for q, body in pool:
            if q == p:
                continue
            # ⚠️ **同名遮蔽**：若 q 自己声明了同名的嵌套类型（`record X(` / `class X` / `interface X`），
            # 且**没有** import 我们这个类的全限定名 ⇒ q 里的这个名字全是它自己的 ⇒ **全部不算命中**。
            # 实测假阳性一例：`compat/ftbteams/FtbPartyBinder.java:37` 自己声明 `record Step(...)`
            # ⇒ 台账曾把 `task/Step` 判成"被 `compat/ftbteams` 引用"。
            # ⚠️ 既有的同名门禁（`tools/check-duplicate-class-names.py`）**自己写明了不覆盖嵌套类名**。
            if re.search(rf"\b(?:record|class|interface|enum)\s+{re.escape(cls)}\b", body) \
                    and f"import {fqn_of(p)};" not in body:
                continue
            if pat.search(body):
                pk = rel_pkg(q)
                refs.append(pk)
                # ⭐ 分类用标签：`task/` 下按**类**记，供"生产 task 类"回判（见 `is_verify_side`）
                tagged.append("task:" + q.stem if pk == "task" or pk.startswith("task/") else pk)
                if direct_entry is None and pk in ENTRY_SIDE:
                    direct_entry = f"{SRC_REL}{q.relative_to(ROOT / SRC_REL)}"
        refs_by_cls[cls] = set(refs)
        tagged_by_cls[cls] = set(tagged)
        repo_paths[cls] = str(p.relative_to(ROOT))
        path_by_cls[cls] = p
        entries[cls] = direct_entry

    def classify(prod_task: frozenset[str]) -> list[tuple[str, str, str, str]]:
        """按 `prod_task`（已判为 `生产`/`step` 的类集）算一遍 ⇒ 迭代到**不动点**。"""
        out: list[tuple[str, str, str, str]] = []
        for cls in sorted(tagged_by_cls):
            repo_path = repo_paths[cls]
            refset = refs_by_cls[cls]
            tagset = tagged_by_cls[cls]
            direct_entry = entries[cls]
            def is_ver(x: str) -> bool:
                return is_verify_side(x, prod_task)
            entry_reach, cmd_reach = dispatch.get(cls, ("no", "no"))
            # ⭐ 产品面可达性 = **命令可达**（`command/` 直接引用，或该类的派发方法被 `command/` 调用）。
            player_reachable = direct_entry is not None or cmd_reach == "yes"
            if is_step_primitive(path_by_cls[cls]):
                reason = "step:Step.java(注册口)" if cls == "Step" else "step:implements Step"
                dest = "step"
            elif player_reachable and is_debug_mark(cls):
                # ⭐ `debug/` 只收 `R2` 枚举的那三类 —— ⛔ "被玩家触发"不足以进调试面：
                # `MineTask`/`WalkToTask`/`TaskTarget` 这些**生产任务**玩家也能触发（走 job/命令），
                # 但它们不是调试面（早期把"被玩家入口引用"当充要条件 ⇒ 实测 20 个生产类误入 debug）。
                dest = "debug"
                reason = ("debug:A:" + direct_entry) if direct_entry is not None \
                    else "debug:B:命令可达（cmd_reachable=yes）"
            elif is_debug_mark(cls) or is_fixture_mark(cls) or all(is_ver(x) for x in tagset):
                prodrefs = sorted(x for x in tagset if _is_prod_ref_impl(x, prod_task))
                if prodrefs:
                    # ⭐ `R6`：被**生产执行路径**引用 ⇒ 不可能"可剔除" ⇒ 判生产（即使名字带 `Fixture`）
                    pretty = sorted({x[5:] if x.startswith("task:") else x for x in prodrefs})
                    dest = "生产"
                    reason = "生产:有标记但被生产引用(" + ",".join(pretty)[:100] + ")"
                else:
                    dest = "fixture"
                    if all(is_ver(x) for x in tagset):
                        reason = "fixture:只被验证侧引用"
                    else:
                        other = sorted({x for x in refset if not is_ver(x)})
                        reason = "fixture:命名标记+外部引用(" + ",".join(other)[:80] + ")"
            else:
                outside = sorted({x for x in refset if not is_ver(x)})
                dest, reason = "生产", "生产:refs=" + ",".join(outside)[:120]
            out.append((cls, repo_path, dest, reason))
        return sorted(out)

    # ⭐ 迭代到不动点（`task/` 里的生产类会随判定变化而进入 `prod_task`）
    prod_task: frozenset[str] = frozenset()
    rows: list[tuple[str, str, str, str]] = []
    for _ in range(6):
        rows = classify(prod_task)
        nxt = frozenset(r[0] for r in rows if r[2] in ("生产", "step"))
        if nxt == prod_task:
            break
        prod_task = nxt
    else:
        raise SystemExit("TASK_RETIREMENT_MAP_RESULT FAIL: 分类迭代 6 轮仍未收敛 ⇒ 判据自相矛盾")
    # ⭐ 跨源对账（取代写死的下限）：顶层行数必须 == `P2` 单向阀的名单行数。
    if not FREEZE_TXT.exists():
        raise SystemExit(f"TASK_RETIREMENT_MAP_RESULT FAIL: 缺 `{FREEZE_TXT.relative_to(ROOT)}`"
                         f" ⇒ 顶层人口**没有对账源**")
    _frozen = [x for x in FREEZE_TXT.read_text(encoding="utf-8").split("\n") if x.strip()]
    _top = [r for r in rows if r[1].startswith(TASK_DIR + "/") and r[1].count("/") == TASK_DIR.count("/") + 1]
    if not _top:
        raise SystemExit("TASK_RETIREMENT_MAP_RESULT FAIL: 顶层行 0 条 ⇒ 解析器静默失效（⛔ 空集不是通过）")
    if len(_top) != len(_frozen):
        raise SystemExit(f"TASK_RETIREMENT_MAP_RESULT FAIL: 两源不一致 —— 台账顶层 {len(_top)} 行 "
                         f"vs 冻结名单 {len(_frozen)} 行（忘了 `--write`？）")
    REFSET.clear()
    REFSET.update(refs_by_cls)
    return rows


def prod_pkg_of(cls: str, dest: str) -> str:
    """该行的**生产层包名**列取值：`dest=生产` ⇒ `PROD_HOME` 的值（缺 ⇒ `?`）；否则 ⇒ `-`（不适用）。"""
    if dest != "生产":
        return NA
    return PROD_HOME.get(cls, UNDECIDED)


def render(rows: list[tuple[str, str, str, str]]) -> str:
    return "\n".join([HEADER] + [",".join((r[0], r[1], r[2], prod_pkg_of(r[0], r[2]), r[3]))
                                 for r in rows]) + "\n"


def main(argv: list[str]) -> int:
    rows = build_rows()
    if "--write" in argv:
        MAP_OUT.write_text(render(rows), encoding="utf-8")
        dist = Counter(r[2] for r in rows)
        print("TASK_RETIREMENT_MAP_RESULT WROTE: %d 行 · %s → %s" % (
            len(rows), " / ".join(f"{d}={dist[d]}" for d in DESTS), MAP_OUT.relative_to(ROOT)))
        return 0

    problems: list[str] = []
    old: dict[str, tuple[str, str, str, str]] = {}
    head = ""
    if MAP_OUT.exists():
        lines = [ln for ln in MAP_OUT.read_text(encoding="utf-8").split("\n") if ln.strip()]
        head = lines[0] if lines else ""
        for ln in lines[1:]:
            parts = ln.split(",")
            if len(parts) >= 4:
                old[parts[0]] = (parts[1], parts[2], parts[3], ",".join(parts[4:]))
    if head != HEADER:
        problems.append(f"表头不符：`{head}`（应为 `{HEADER}`）")
    if not old:
        problems.append("台账是空的或不存在（⛔ 空表不许读成「没有要检查的」）")
    cur = {r[0]: (r[1], r[2], r[3]) for r in rows}
    for cls in sorted(set(cur) - set(old)):
        problems.append(f"**实物有、台账缺**：`{cls}` ⇒ 忘了 `--write`？")
    for cls in sorted(set(old) - set(cur)):
        problems.append(f"**台账有、实物无**（陈旧行）：`{cls}`")
    for cls in sorted(set(cur) & set(old)):
        if cur[cls][0] != old[cls][0]:
            problems.append(f"`{cls}` 路径不符：台账 `{old[cls][0]}` vs 实物 `{cur[cls][0]}`")
        elif cur[cls][1] != old[cls][1]:
            problems.append(f"`{cls}` 目的地不符：台账 `{old[cls][1]}` vs 实物 `{cur[cls][1]}`"
                            f" ⇒ 分类判据变了，须重新 `--write` 并说明为何变")
        elif prod_pkg_of(cls, cur[cls][1]) != old[cls][2]:
            problems.append(f"`{cls}` **生产层包名**不符：台账 `{old[cls][2]}` vs 判据 "
                            f"`{prod_pkg_of(cls, cur[cls][1])}` ⇒ 改了 `PROD_HOME` 却忘了 `--write`")
    # 非法目的地（含"目的地 = 原地"）
    for r in rows:
        if r[2] not in DESTS:
            problems.append(f"`{r[0]}` 目的地非法：`{r[2]}`（合法 = {DESTS}；⛔ `task` 不是候选）")
    # 交叉核（**有向蕴含**，⛔ 不是等价）：`dest=debug` ⇒ 必须**玩家可达**。
    # ⛔ 反向不成立 —— "玩家可达"**不足以**进调试面（`MineTask`/`WalkToTask` 玩家可触发，
    # 但它们是**生产任务**；`R2` 枚举了 `debug/` 里到底住哪三类）。早期写成等价 ⇒ 一次性报出
    # 14 条**假红**，那正是"把 `R1` 的一个桶读成充要条件"的错。
    dispatch = load_dispatch()
    for cls, path, dest, reason in rows:
        if dest == "debug" and not (reason.startswith("debug:A:")
                                    or dispatch.get(cls, ("no", "no"))[1] == "yes"):
            problems.append(f"交叉核失败：`{cls}` 判进 `debug/` 却**证明不了玩家可达**"
                            f"（既不在派发表的可达集里，也没有 item/command 直接引用）")

    # ⭐ `P1` 立家（`D-550` §2b 的 `P0` **判据②**）：目的地必须是**已建成的包** ⇒ 「指空」即红。
    # ⚠️ 加这一条之前它**只印不判**（`missing` 只进 PASS 那行的文案）⇒ `P1` 一直是「待办」而不是
    #    「能红」。今天三个包都在场 ⇒ **绿**；只有在有人删掉目的地包时才红
    #    （同 `D-551` ① 的 `P2` 提前上线口径：今天绿且只对**新违规**红 ⇒ 不撞 `R4`）。
    missing = [d for d in DEST_PKG if not (ROOT / DEST_PKG[d]).is_dir()]
    if missing:
        problems.append("目的地包**不存在**：" + "、".join(f"`com.dddgn.alice.{d}/`" for d in missing)
                        + " ⇒ `P1` 立家未完成（`P0` 判据② = 目的地 ∈ **已建成的包**）")

    # ==================== ⭐ 「生产层包名」（`PROD_HOME`）四判据（2026-10-01，`O92` ②） ====================
    prod_rows = [r for r in rows if r[2] == "生产"]
    undecided = sorted(r[0] for r in prod_rows if PROD_HOME.get(r[0], UNDECIDED) == UNDECIDED)
    # ① 完整：每个 `生产` 行必须在 `PROD_HOME` 里有条目（⛔ 漏登记不许静默留空）。
    for cls in sorted(r[0] for r in prod_rows if r[0] not in PROD_HOME):
        problems.append(f"`{cls}` 是 `dest=生产` 却没有 `PROD_HOME` 条目 ⇒ 「生产层包名」一维漏登记"
                        f"（`O92` ②；⛔ 不许留空）")
    # ② 未定家不许搬 ＋ ③ 指空 / 搬到别处 ＋ ④ 搬包不是复制。
    #    ⚠️ 判据的**方向**：台账只收"还在 `task/` 里"的类 ⇒ **已搬**的类必然不在 `rows` 里
    #    ⇒ 这一类只能从 `PROD_HOME` 反查（这正是 `?` 哨兵能被门禁抓住的唯一入口）。
    task_stems = {r[0] for r in rows}
    # ⚠️ 2026-10-01 **红臂 A4/A5 抓出来的真 bug**：这里原来写 `SRC_REL.replace("/", ".")[:-1]`
    #    ⇒ 得到 `src.main.java`（**源码根**，⛔ 不是包根）⇒ `startswith` 恒假 ⇒ **每一格都判"非法"**。
    #    ⇒ 症状 = "三条不同的臂给同一个理由"（A4 该报"指空"、A5 该报"没落在登记的家"，都报了"非法"）。
    #    ⭐ 今天**看不出来**（35 条全 `?`，这段根本不跑）⇒ **只有注入臂能抓**（`O91` ⑥ 的血债形状）。
    alice_pkg = "com.dddgn.alice"
    for cls in sorted(PROD_HOME):
        pkg = PROD_HOME[cls]
        if cls in task_stems:
            # 未搬：条目存在性已由 ① 查过；这里只查"复制"（值非 `?` 时目标包里不该已有同名件）。
            if pkg != UNDECIDED and (ROOT / SRC_REL / pkg.replace(".", "/") / f"{cls}.java").is_file():
                problems.append(f"`{cls}` **搬包不是复制**：它**还在** `task/` 里，而 `{pkg}` 下**已经有**"
                                f"同名文件 ⇒ 要么删掉 `task/` 里那份，要么改 `PROD_HOME`")
            continue
        # 已搬（`task/` 里没有这个类了）：
        if pkg == UNDECIDED:
            problems.append(f"`{cls}` **未定家就搬走了**：它已不在 `task/`，而 `PROD_HOME` 仍是 `{UNDECIDED}`"
                            f" ⇒ ⛔ `?` 只表示「还没裁定」，不是「随便搬」（`O92` ⑥）")
            continue
        if not pkg.startswith(alice_pkg + ".") or pkg == alice_pkg + ".task" \
                or pkg.startswith(alice_pkg + ".task."):
            problems.append(f"`{cls}` 的生产层包名非法：`{pkg}`（⛔ `task` 不是合法目的地 —— `P0` 判据②）")
            continue
        target = ROOT / SRC_REL / pkg.replace(".", "/")
        if not target.is_dir():
            problems.append(f"`{cls}` 的生产层包名**指空**：`{pkg}` 不是已建成的包"
                            f"（`P0` 判据②）⇒ 先立家再搬")
        elif not (target / f"{cls}.java").is_file():
            problems.append(f"`{cls}` 搬走了但**没落在登记的家**：`{pkg}` 下没有 `{cls}.java`"
                            f" ⇒ 实物与 `PROD_HOME` 不符")

    if problems:
        print("TASK_RETIREMENT_MAP_RESULT FAIL: 台账与实物不一致（**双向核** ＋ 交叉核）")
        for p in problems[:20]:
            print(f"  - {p}")
        if len(problems) > 20:
            print(f"  … 共 {len(problems)} 条")
        return 1

    dist = Counter(r[2] for r in rows)
    risks = r3_risks(rows, REFSET)
    decided = len(prod_rows) - len(undecided)
    print("TASK_RETIREMENT_MAP_RESULT PASS: %d 行 · %s · 0 条不一致 · 未建成的目的地包：%s" % (
        len(rows), " / ".join(f"{d}={dist[d]}" for d in DESTS),
        "、".join(missing) if missing else "无"))
    # ⭐ 「生产层包名」进度表（`O92` ②；⛔ 不是可以眼不见的待办 —— 波 4 的第一个数就是它）。
    print(f"  ⭐ **生产层包名**：已定 {decided} / **待裁 {len(undecided)}**（哨兵 `?`）"
          f" —— `PROD_HOME` 在 `tools/{Path(__file__).name}` 里手写")
    if undecided:
        print(f"     ⚠️ 待裁 {len(undecided)} 个 = 波 4 的**真卡点**"
              f"（`O92` ⑥「甲／乙／丙」逐字「待用户裁定」）：")
        print("     " + "、".join(f"`{c}`" for c in undecided[:14])
              + (f" … 共 {len(undecided)} 个" if len(undecided) > 14 else ""))
    if not risks:
        print(f"  ✅ `R3` 风险 **0 条**（**注册位置** {sorted(NON_PROD - VERIFY_SIDE)} 与验证侧都不算生产包；"
              f"`task/` 是**被退役的包**、暂排除 ⇒ 其去留看本台账）")
    if risks:
        print(f"  ⚠️ `P1` 立家待办 —— {len(risks)} 个类的目的地是 debug/fixture/step，却被**生产包**引用"
              f"（搬过去即违反 `R3`，⛔ 今天不是红）：")
        for r in risks[:12]:
            print(f"     - {r}")
        if len(risks) > 12:
            print(f"     … 共 {len(risks)} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
