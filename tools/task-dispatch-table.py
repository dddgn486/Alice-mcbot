#!/usr/bin/env python3
"""`丙` 方案：把**派发枢纽**（`bot/BotManager` ＋ `fixture/FixtureDispatch`）的**派发表**生成成显式清单（`D-551` 裁定）。

## 为什么需要它（`O76` §4c）

批次 2 ① 的分类判据 `R1` 说「**被玩家入口引用** ⇒ `debug/`」，但实测：
`item/`／`command/` 只是**注册位置**，真正的**派发枢纽**是 `bot/BotManager`
（它自己 `new` 夹具，如 `BotManager.java:1581` `new CraftCheckTask(...)`）⇒
190 个类里有 **45 个**只被枢纽引用，**玩家可达性判不了**。

本工具把"可达性推理"**降级成"清单核对"**：从 `BotManager` 的构造点生成
`(任务类 → 派发方法 → 是否入口可达)` 三列，作为 `task-retirement-map.py` 的**输入**。

## ⛔ 为什么不记行号（这是本工具最重要的一条设计决定）

本项目刚把「`code_ref` 列去行号」列为**批次 2 ③ 的债**（`write/WritePolicyMatrix` 的字符串
→ `POLICY_MATRIX.csv` 今天没有任何门禁核对行号，`:182`/`:241` 已漂）⇒ 生成物里再塞行号
＝把同一笔债**再造一遍**。故键 = `(任务类, 派发方法)`，**不带行号**，与无关编辑零耦合。

## 口径

- **构造点** = **任一派发枢纽**（`DISPATCH_HUBS`：`bot/BotManager` ＋ `fixture/FixtureDispatch`）
  里 `new <类>(`，且 `<类>` 的**简单名**在 `task/` · `debug/` · `fixture/` 三棵树里存在
  （⇒ 迁移后 FQN 变了也照样认）。
  ⚠️ **刀 2 起枢纽有两个**（`D-512`：47 个开发期入口搬去 `fixture/FixtureDispatch`）——
  ⛔ 加宽扫描源必须与搬家**同刀**，否则本表静默缩水。
- **派发方法** = 该构造点往上最近的方法签名（**在本枢纽内**）。
- **入口可达** = 该派发方法在 **`item/`／`command/`／`debug/` 调用闭包**内
  （`<枢纽类名>.<方法>(` 为一跳种子，再**在该枢纽内部**做**传递闭包**；⭐ 种子与闭包**都按枢纽分开**）。
- ⚠️ **本表是「过近似」，方向是安全的**（实测两条近似，如实登记）：
  **(a) 同名合并** —— `BotManager.assignXxx`（静态派发）与 `BotSession.assignXxx`（实例，
  真正 `new`）**同名** ⇒ 图按**方法名**合并节点（这恰好让"静态入口 ↔ 实例构造"连上）；
  **(b) 点号调用** —— `session.assign(...)` 这类也算类内调用。
  两条都只会把类**往 `debug/` 判**（玩家可达），⛔ **不会**把玩家可达的判成"可剔除"
  —— 后者才会真的砍掉玩家功能（`R6`：将来物理剔除剔 `fixture/`）。
  自证一例：`WalkToTask` 判可达，路径 = `item/SurvivalExitCheckItem.java:105`
  → `BotManager.assignSurvivalExitCheck` → `session.assignWalkTo(dummyGoal)` → `new WalkToTask`。
- ⚠️ **剥注释保留行号**：注释里的 `new XxxTask(` 不算构造点。

用法：
    python3 tools/task-dispatch-table.py --write   # 重新生成 docs/TASK_DISPATCH_TABLE.csv
    python3 tools/task-dispatch-table.py           # 门禁（与实物双向核）
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src/main/java"
BOT_MANAGER = SRC / "com/dddgn/alice/bot/BotManager.java"
#: ⭐ **刀 2（`D-512`）**：派发枢纽**不止一个** —— 47 个开发期入口搬去了 `fixture/FixtureDispatch`
#: （`bot/` 里零夹具/调试引用）。⚠️ 若不同刀加宽扫描源，本表会从 61 行**掉到 15 行**
#: （`MIN_ROWS` 会响，但读数已经"绿着变坏"过一次了）⇒ 两个枢纽一起扫，`dispatcher` 列仍是**裸方法名**。
FIXTURE_DISPATCH = SRC / "com/dddgn/alice/fixture/FixtureDispatch.java"
DISPATCH_HUBS = (BOT_MANAGER, FIXTURE_DISPATCH)
OUT = ROOT / "docs/TASK_DISPATCH_TABLE.csv"

SRC_REL = "src/main/java/"
ALICE = "com/dddgn/alice/"
# 任务类的**家**（迁移后类会从 task/ 搬到 debug/、fixture/、step/ ⇒ 三处都认）
TASK_TREES = ("com/dddgn/alice/task", "com/dddgn/alice/debug", "com/dddgn/alice/fixture")
#: ⭐ **开发期入口树**（`D-560`，2026-09-30 用户裁定「现有 `/alice` 命令全是开发期入口」）：
#: 刀 1 把 28 条开发期子命令劈去 `debug/DebugCommands.java` ⇒ `debug/` **必须**算入口树，
#: 否则"只被开发期命令可达"的任务类会**从表里消失**（覆盖面静默缩水 —— 实测曾一次掉 24 行）。
ENTRY_TREES = ("item", "command", "debug")
#: ⭐ **产品面命令树**（2026-09-30 用户裁「甲」）：发行包的"玩家调试面"载体 = **命令 ＋ GUI 按钮**；
#: ⛔ `/give` 物品退为**开发期入口** ⇒ 两个可达面**分开报**（`entry_reachable` 与 `cmd_reachable`）。
#: ⚠️ 刀 1 之后本表**才有粒度**：劈分前 `command/` 混装产品与开发期命令 ⇒ 两列几乎同值。
CMD_TREES = ("command",)

MIN_ROWS = 40          # 人口下限（非空检查）：今天 60+ 行；掉到 40 以下 ⇒ 解析器坏了
HEADER = "task_class,dispatcher,entry_reachable,cmd_reachable"


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


def strip_comments_keep_lines(text: str) -> str:
    """掏空注释，**保留换行数**（行号/方法序不能变）。字符串字面量原样保留。"""
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            if j < 0:
                out.append(" " * (n - i))
                break
            out.append(" " * (j - i))
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            end = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:end]))
            i = end
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


def known_task_classes() -> set[str]:
    names: set[str] = set()
    for tree in TASK_TREES:
        for p in listed(tree):
            names.add(p.stem)
    assert names, "三棵树里一个类都没找到"
    return names


METHOD_RE = re.compile(
    r"^\s*(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?"
    r"[\w<>\[\],.\s?]+?\s+(\w+)\s*\(")
CTOR_RE = re.compile(r"^\s*(?:public|private|protected)\s+(\w+)\s*\(")
NEW_RE = re.compile(r"\bnew\s+([A-Za-z_][\w.]*)\s*(?:<[^;()]*>)?\s*\(")
CALL_RE = re.compile(r"(?:[A-Za-z_]\w*\.)*([A-Za-z_]\w*)\s*\(")
JAVA_KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "synchronized", "assert"}


def brace_depths(lines: list[str]) -> list[int]:
    """每行**进入时**的括号深度（注释已掏空、字符串已保留 ⇒ 字符串里的 `{` 不算）。"""
    depths: list[int] = []
    depth = 0
    for line in lines:
        depths.append(depth)
        depth += line.count("{") - line.count("}")
    return depths


def parse_hub(hub: Path, known: set[str]) -> tuple[list[tuple[str, str]], dict[str, set[str]],
                                                   list[tuple[int, int, str]]]:
    """⇒ (构造点 [(类, 派发方法)], 方法→本类方法调用, 方法 span [(起, 止, 名)])

    ⚠️ **归属靠括号深度，不靠"最近的前 4 空格签名行"**（本工具第一次写就踩了）：
    枢纽里有**嵌套类 `BotSession`**，它的方法缩进是 **8 空格** ⇒ 只认 4 空格的正则
    会把嵌套类里的构造点（如 `new MineTask(`）**全挂到上一个外层方法**（实测 9 行被挂到
    `stableTaskKind`，而那个方法体里一个 `new` 都没有）。⇒ 改判据：构造点归属 = 它前面
    **签名深度 < 本行深度**的那个**最近**方法。
    """
    raw = hub.read_text(encoding="utf-8")
    src = strip_comments_keep_lines(raw)
    lines = src.split("\n")
    depths = brace_depths(lines)

    # ① 方法边界（任意缩进）＋ 签名深度
    starts: list[tuple[int, str, int]] = []
    for idx, line in enumerate(lines, start=1):
        m = METHOD_RE.match(line) or CTOR_RE.match(line)
        if m:
            starts.append((idx, m.group(1), depths[idx - 1]))
    assert len(starts) > 40, f"只认出 {len(starts)} 个方法（下限 40）⇒ 签名解析器坏了"
    method_names = {name for _, name, _ in starts}

    def enclosing(lineno: int) -> str:
        d = depths[lineno - 1]
        best, best_start = "<unknown>", -1
        for start, name, sig_depth in starts:
            if start <= lineno and sig_depth < d and start > best_start:
                best, best_start = name, start
        return best

    # ② 构造点
    sites: list[tuple[str, str]] = []
    for idx, line in enumerate(lines, start=1):
        for m in NEW_RE.finditer(line):
            simple = m.group(1).split(".")[-1]
            if simple in known:
                sites.append((simple, enclosing(idx)))
    assert sites, "一个构造点都没解析出来 ⇒ 解析器坏了"
    unknown = sorted({c for c, d in sites if d == "<unknown>"})
    assert not unknown, f"有构造点定不出派发方法（深度归属失败）：{unknown[:5]}"

    # ③ 方法 span ＋ 方法 → 本类方法调用（为传递闭包）
    ordered = sorted(starts)
    spans: list[tuple[int, int, str]] = []
    for k, (start, name, _) in enumerate(ordered):
        end = ordered[k + 1][0] - 1 if k + 1 < len(ordered) else len(lines)
        spans.append((start, end, name))
    graph: dict[str, set[str]] = {name: set() for name in method_names}
    for start, end, name in spans:
        body = "\n".join(lines[start - 1:end])
        for cm in CALL_RE.finditer(body):
            callee = cm.group(1)
            if callee in method_names and callee != name and callee not in JAVA_KEYWORDS:
                graph[name].add(callee)

    return sites, graph, spans


def seeds_from(trees: tuple[str, ...], hub_simple: str, required: bool = True) -> set[str]:
    """某个枢纽在入口树里被调用过的方法名（⭐ **按枢纽分别取种子**：两个枢纽的方法名集合
    不互相污染 —— 跨枢纽按名字合并会把可达性**无端放大或缩小**）。"""
    seeds: set[str] = set()
    pat = re.compile(re.escape(hub_simple) + r"\s*\.\s*(\w+)\s*\(")
    for tree in trees:
        for p in listed(ALICE + tree):
            for m in pat.finditer(strip_comments_keep_lines(p.read_text(encoding="utf-8"))):
                seeds.add(m.group(1))
    if required:
        assert seeds, f"入口侧（{trees}）一个 `{hub_simple}.<方法>(` 都没找到 ⇒ 解析器坏了"
    return seeds


def reachable_methods(graph: dict[str, set[str]], seeds: set[str]) -> set[str]:
    seen: set[str] = set()
    stack = [s for s in seeds if s in graph]
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        stack.extend(graph.get(cur, ()))
    return seen


def build_rows() -> list[tuple[str, str, str, str]]:
    known = known_task_classes()
    reach: set[tuple[str, str]] = set()
    cmd_reach: set[tuple[str, str]] = set()
    rows: set[tuple[str, str, str, str]] = set()
    for hub in DISPATCH_HUBS:
        if not hub.exists():
            raise SystemExit(f"空集！派发枢纽不存在 {hub.relative_to(ROOT)} ⇒ "
                             f"搬了家就要同刀改本文件的 `DISPATCH_HUBS`（⛔ 不许静默少扫一个枢纽）")
        stem = hub.stem
        sites, graph, _ = parse_hub(hub, known)
        entry_seeds = seeds_from(ENTRY_TREES, stem, required=False)
        # ⭐ 每个枢纽**都必须**被入口树调到过（否则它要么死了、要么种子正则坏了）。
        assert entry_seeds, (f"枢纽 {stem} 在入口树 {ENTRY_TREES} 里一个调用点都没有 ⇒ "
                             f"要么它已死、要么种子正则坏了（⛔ 不许静默当成空集）")
        for m in reachable_methods(graph, entry_seeds):
            reach.add((stem, m))
        # ⚠️ 产品面命令树（`CMD_TREES`）**可以**调不到某个枢纽（`fixture/FixtureDispatch`
        #    正是如此：开发期派发器按 `D-560` 就不该被产品面命令可达）⇒ 这里 **required=False**。
        for m in reachable_methods(graph, seeds_from(CMD_TREES, stem, required=False)):
            cmd_reach.add((stem, m))
        for cls, disp in sites:
            rows.add((cls, disp, "yes" if (stem, disp) in reach else "no",
                      "yes" if (stem, disp) in cmd_reach else "no"))
    return sorted(rows)


def render(rows: list[tuple[str, str, str, str]]) -> str:
    return "\n".join([HEADER] + [",".join(r) for r in rows]) + "\n"


def read_csv(path: Path) -> tuple[str, list[tuple[str, str, str, str]]]:
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    lines = [ln for ln in text.split("\n") if ln.strip()]
    if not lines:
        return "", []
    head, body = lines[0], lines[1:]
    rows = []
    for ln in body:
        parts = ln.split(",")
        if len(parts) != 4:
            rows.append(tuple(parts + ["<列数不对>"] * (4 - len(parts)))[:4])  # type: ignore[arg-type]
            continue
        rows.append((parts[0], parts[1], parts[2], parts[3]))
    return head, rows


def main(argv: list[str]) -> int:
    rows = build_rows()
    if len(rows) < MIN_ROWS:
        print(f"TASK_DISPATCH_TABLE_RESULT FAIL: 只得到 {len(rows)} 行（下限 {MIN_ROWS}）⇒ "
              f"派发枢纽（{[h.stem for h in DISPATCH_HUBS]}）的构造点解析**静默失效**"
              f"（非空检查不通过；搬了家就同刀改 `DISPATCH_HUBS`）")
        return 1

    if "--write" in argv:
        OUT.write_text(render(rows), encoding="utf-8")
        yes = sum(1 for r in rows if r[2] == "yes")
        cyes = sorted({r[0] for r in rows if r[3] == "yes"})
        print(f"TASK_DISPATCH_TABLE_RESULT WROTE: {len(rows)} 行（入口可达 {yes} / 不可达 {len(rows) - yes}"
              f" · ⭐ **命令可达类 {len(cyes)}**）→ {OUT.relative_to(ROOT)}")
        return 0

    head, old = read_csv(OUT)
    problems: list[str] = []
    if head != HEADER:
        problems.append(f"表头不符：`{head}`（应为 `{HEADER}`）")
    if not old:
        problems.append("CSV 是空的或不存在（⛔ 空表不许读成「没有要检查的」）")
    new_set, old_set = set(rows), set(old)
    for r in sorted(new_set - old_set):
        problems.append(f"**实物有、CSV 缺**：`{r[0]}` ← `{r[1]}`（entry_reachable={r[2]} · "
                        f"cmd_reachable={r[3]}）⇒ 忘了 `--write`？")
    for r in sorted(old_set - new_set):
        problems.append(f"**CSV 有、实物无**（陈旧行）：`{r[0]}` ← `{r[1]}`")
    if problems:
        print("TASK_DISPATCH_TABLE_RESULT FAIL: 派发表与实物不一致（**双向核**）")
        for p in problems[:20]:
            print(f"  - {p}")
        if len(problems) > 20:
            print(f"  … 共 {len(problems)} 条")
        return 1

    yes = sorted({r[0] for r in rows if r[2] == "yes"})
    cyes = sorted({r[0] for r in rows if r[3] == "yes"})
    print(f"TASK_DISPATCH_TABLE_RESULT PASS: 构造点 {len(rows)} 行 · 派发方法 "
          f"{len({r[1] for r in rows})} 个 · 入口可达类 {len(yes)} · "
          f"⭐ **命令可达类 {len(cyes)}**（{cyes}）· 不可达类 "
          f"{len({r[0] for r in rows if r[2] == 'no'})}（0 条不一致）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
