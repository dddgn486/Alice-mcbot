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
4. **`生产`** —— 其余（被生产层引用）⇒ 逐类在**波 4** 定到具体层，⛔ 今天不假装知道。

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
HEADER = "task_class,task_path,dest,reason"
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


def render(rows: list[tuple[str, str, str, str]]) -> str:
    return "\n".join([HEADER] + [",".join(r) for r in rows]) + "\n"


def main(argv: list[str]) -> int:
    rows = build_rows()
    if "--write" in argv:
        MAP_OUT.write_text(render(rows), encoding="utf-8")
        dist = Counter(r[2] for r in rows)
        print("TASK_RETIREMENT_MAP_RESULT WROTE: %d 行 · %s → %s" % (
            len(rows), " / ".join(f"{d}={dist[d]}" for d in DESTS), MAP_OUT.relative_to(ROOT)))
        return 0

    problems: list[str] = []
    old: dict[str, tuple[str, str, str]] = {}
    head = ""
    if MAP_OUT.exists():
        lines = [ln for ln in MAP_OUT.read_text(encoding="utf-8").split("\n") if ln.strip()]
        head = lines[0] if lines else ""
        for ln in lines[1:]:
            parts = ln.split(",")
            if len(parts) >= 3:
                old[parts[0]] = (parts[1], parts[2], ",".join(parts[3:]))
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

    if problems:
        print("TASK_RETIREMENT_MAP_RESULT FAIL: 台账与实物不一致（**双向核** ＋ 交叉核）")
        for p in problems[:20]:
            print(f"  - {p}")
        if len(problems) > 20:
            print(f"  … 共 {len(problems)} 条")
        return 1

    dist = Counter(r[2] for r in rows)
    risks = r3_risks(rows, REFSET)
    print("TASK_RETIREMENT_MAP_RESULT PASS: %d 行 · %s · 0 条不一致 · 未建成的目的地包：%s" % (
        len(rows), " / ".join(f"{d}={dist[d]}" for d in DESTS),
        "、".join(missing) if missing else "无"))
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
