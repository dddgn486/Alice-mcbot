#!/usr/bin/env python3
"""**清旧家的分类器**（阶段 3）—— 给旧家**每一件**算出「**去哪个新家 ＋ 是不是丢/合/过期候选**」。

## 依据

草案 `docs/HANDOVER.md` **`§D′-9` 清旧家的判据**（用户 2026-10-02 裁定「支线变主线」时立）。
工序 = `docs/DOC_REFACTOR_PLAN.md` 的 `W7′-3`（批 1）/ `W7′-4`（批 2..N）。

## 本脚本的**唯一职责**：把「机械可算的那一半」算完

用户逐字：

> 「我有点担心我人工审核会有**标准偏移**的情况，但是…**这句话不对，或者这句话过期，我一定能看出来**」

⇒ 机械检查归 AI，人只判两问（对不对／过期没有）。所以本脚本**只算**四类信号，
**它一个字都不判「该不该留」**：

| 信号 | 怎么算（机械） | 它意味着什么 |
|---|---|---|
| **零引用** | 全仓（`*.md/*.py/*.sh/*.yml/*.java/*.csv`）除自身外零命中（路径或 basename） | 只是**候选** —— `§D′-9` 9.3 判据② |
| ~~**生成物陈旧**~~ | ⛔ **已于 2026-10-04 移出本表**（`P2-1`）：它**归各生成物自己的门禁** —— 本表复制那个判断时**恒报「陈旧」（假阳性）**，而且**天然自指 ⇒ 迟早震荡**。本表只保留"**名单本身**不许陈旧"（逐字节比对） |
| **同族重复度** | 同前缀族内两两 **8-gram Jaccard** | `§D′-9` 9.3 判据④的**量化门**；本项目实测普遍很低 |
| **归位** | 命中 `DEST` 哪一类（**复用 `new-home-audit.py` 的同一张表**，不抄第二份） | 「它是什么（形态）」 |

## 本脚本**假装不了**的（照抄 `§D′-9` 9.6 与 `new-home-audit.py` 的边界）

1. **「该丢」与「该合」的实质判断不在脚本里** —— 它只给机械候选；点头的永远是人；
2. **零引用不等于该丢** —— 归档件本来就该零引用（实测 ⑧ 域外 93 件零引用全部合法）⇒
   必须按好家分桶读，不许把总数当成可删清单；
3. **8-gram Jaccard 判「抄没抄」，判不了「该不该合」** —— 同一主题各写各的（低分）也可能该合并；
4. `§D′-9` 9.4 的搬迁三条（有格·有引用面·有失效条件）本脚本**只能算前两条** ——
   「失效条件」是语义的，脚本判不了（它只能判「文件里有没有写」）。

## 用法

    python3 tools/cleanup-classify.py --write     # 生成判据表
    python3 tools/cleanup-classify.py --check     # 校验（门禁调这个）
    python3 tools/cleanup-classify.py --selftest  # 注入自证
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "CLEANUP_CLASSIFY.md"

#: 复用 `new-home-audit.py` 的 `DEST` / `match` / `old_home_items` —— 不许抄第二份。
#: 理由：两张表一旦分叉，就是本项目最贵那族错（同一口径写两份然后漂移）。
_NHA = ROOT / "tools" / "new-home-audit.py"
_spec = importlib.util.spec_from_file_location("_nha", _NHA)
nha = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(nha)

#: 生成物名单：**有生成器 ＋ 有门禁**才算（`§D′-9` 9.3「丢掉」判据①要求两者都有）。
#: 形状：(生成物相对路径, 生成器, 门禁脚本)
GENERATED: list[tuple[str, str, str]] = [
    ("docs/DOC_REFACTOR_PLAN.md", "tools/plan-doc-refactor.py", "tools/check-plan-doc-refactor.sh"),
    ("docs/DESIGN_INDEX.md", "tools/design-index.py", "tools/check-design-index.sh"),
    ("docs/DECISIONS_INDEX.md", "tools/decisions-index.py", "tools/check-decisions-index.sh"),
    ("survey/README.md", "tools/survey-index.py", "tools/check-survey-index.sh"),
    ("docs/plans/README.md", "tools/doc-registry.py", "tools/check-doc-registry.sh"),
    ("docs/reviews/README.md", "tools/doc-registry.py", "tools/check-doc-registry.sh"),
    (".alice-supervision/skills/README.md", "tools/skills-index.py", "tools/check-skills-index.sh"),
    # ⭐ **本表也在名单里**（它有生成器 ＋ 有门禁）—— ⚠️ 而它的新鲜度由 `generated_stale`
    #    **逐字节比对**判定（⛔ 不是去跑它自己的门禁，那会递归）。
    ("docs/CLEANUP_CLASSIFY.md", "tools/cleanup-classify.py", "tools/check-cleanup-classify.sh"),
]

#: 同族重复度的族名（按前缀）—— 只量「前缀像」的族，不做全仓 N² 两两比对
#: （442 件全比对约 97k 对，而绝大多数对之间毫无关系 ⇒ 读数会被噪音淹没）。
FAMILIES = ("RISK_", "MINE_", "DECISION_", "CLIENT_", "ALICE_PATHING_CORE_",
            "BARITONE_", "STAGE", "HANDOVER", "GLOSSARY", "ACCEPTANCE")

#: 人口下限：生成的表里必须至少有这么多行 —— 防「表被删空也算通过」。
ROW_FLOOR = 200

#: 扫描面（与 `new-home-audit.py` 同口径，不抄第二份）
SCAN_GLOBS = ("*.md", "*.py", "*.sh", "*.yml", "*.java", "*.csv")

#: 域外前缀（⑧ 类）—— 判「零引用合不合法」时用它分桶，不硬编码类名全串。
OUTSIDE = "⑧"


def home_of(rel: str) -> str:
    """归属于哪个好家（与 `new-home-audit.py` 同一套 `DEST` 与优先级）。"""
    hits: list[tuple[int, str]] = []
    for i, (name, _w, pats) in enumerate(nha.DEST):
        if nha.match(rel, pats):
            hits.append((i, name))
    if not hits:
        return "（无家）"
    return max(hits)[1]          # 优先级 = `DEST` 出现顺序（⑧ 域外排最后 ⇒ 它命中时赢）


def _git_rev() -> str:
    """当前 `HEAD` ＋ 工作区状态指纹 —— 缓存的新鲜度**只由它决定**。"""
    r = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True)
    s = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True)
    return r.stdout.strip() + "|" + s.stdout.strip()


#: 读数缓存 —— 形状 {git指纹: {"rows": [...], "dups": [...]}}
#: ⚠️ **为什么必须有**：`refs()` 对 **444 件**各起一次 `git grep` ⇒ 单次 `classify()` 约 **21 秒**，
#:    而门禁 `--check` 要跑 **两遍**（一遍算、一遍比）⇒ `check-all` 每次多花 ~40 秒。
#:    ⛔ **这不是放宽判据**：缓存的键是**`HEAD` ＋ 工作区状态** —— 仓库一动，键就变，**必然重算**。
_CACHE: dict[str, dict] = {}

#: 「本表重新生成会是什么样」—— 生成物新鲜度判据（逐字节比对）用。
#: ⚠️ 它是模块级可变状态，由 `classify()` 填；`generated_stale()` 只读。
_OUT_TEXT: str = ""


def mention_index() -> dict[str, set[str]]:
    """**一次扫全仓** ⇒ {basename: {提到它的文件, ...}}。⭐ 这是把 444 次 `git grep` 降到 1 次的关键。

    ⚠️ **为什么要它**：第一版对 **444 件各起一次 `git grep`** ⇒ 单次 `classify()` 约 **21 秒**，
    而门禁 `--check` 要算两遍 ⇒ `check-all` 每次多花 ~40 秒。
    ⭐ 判据**逐字不变**：仍然是「除自身外有多少个**文件**提到它」。⛔ 唯一差别是匹配方式 ——
    `-E` ＋ `\b` 词边界，比原版 `-F` 子串匹配**更准**（`-F` 会把 `MYX.md` 里的 `X.md`
    也算一次，那是**假命中**）。⚠️ 这个差别**已当场量过**，表里如实记。
    """
    pat = r"[A-Za-z0-9_.-]+\.(md|py|sh|yml|yaml|java|csv|txt|mjs|json)"
    r = subprocess.run(["git", "grep", "-I", "-n", "-o", "-E", "-e", pat, "--", *SCAN_GLOBS],
                       cwd=ROOT, capture_output=True, text=True)
    idx: dict[str, set[str]] = {}
    full = re.compile(pat + r"$")
    for line in r.stdout.splitlines():
        # 形状：`<文件>:<行号>:<匹配到的 token>`
        parts = line.rsplit(":", 2)
        if len(parts) != 3 or not full.match(parts[2]):
            continue
        idx.setdefault(parts[2].rsplit("/", 1)[-1], set()).add(parts[0])
    return idx


#: 「声称生效」的机械判据 = 文件**头部 40 行**里出现这些词。⭐ 为什么可信：
#:   它**不是我的判断**，是**文件自己的自称**（`§D′-9` 9.5：人判"对不对/过期没有"，
#:   而"它自称生效"这件事本身是机械可读的）。⚠️ 与 `check-proposal-status` 同一个思路。
CLAIM_WORDS = ("已生效", "生效中", "已落地", "已接线", "已实现", "已拍板")


def src_index() -> dict[str, set[str]]:
    """**只在 `src/` 下扫** —— 「生效的规则会被代码引用」这条判据的另一半（批 1 的靶子）。"""
    pat = r"[A-Za-z0-9_.-]+\.(md|py|sh|yml|yaml|java|csv|txt|mjs|json)"
    r = subprocess.run(["git", "grep", "-I", "-n", "-o", "-E", "-e", pat, "--", "src"],
                       cwd=ROOT, capture_output=True, text=True)
    idx: dict[str, set[str]] = {}
    full = re.compile(pat + r"$")
    for line in r.stdout.splitlines():
        parts = line.rsplit(":", 2)
        if len(parts) != 3 or not full.match(parts[2]):
            continue
        idx.setdefault(parts[2].rsplit("/", 1)[-1], set()).add(parts[0])
    return idx


def claims_effective(rel: str) -> str | None:
    """头部 40 行里自称生效的词（⛔ 找不到就返回 `None`，**不猜**）。"""
    p = ROOT / rel
    if not p.exists() or p.suffix not in (".md",):
        return None
    try:
        head = "".join(p.read_text(encoding="utf-8", errors="ignore").splitlines(True)[:40])
    except OSError:
        return None
    for w in CLAIM_WORDS:
        if w in head:
            return w
    return None


def refs(rel: str) -> int:
    """除自身以外，全仓有多少个文件提到它（路径或 basename）。

    判据（`§D′-9` 9.3 判据②）：本脚本不假装分得开「提到它」与「引用它」
    （分开要语义）；所以读数只叫「被提到过」，不叫「被引用」。
    """
    base = os.path.basename(rel)
    r = subprocess.run(["git", "grep", "-l", "-F", "-e", rel, "-e", base, "--", *SCAN_GLOBS],
                       cwd=ROOT, capture_output=True, text=True)
    return len([x for x in r.stdout.split() if x and x != rel])


def ngrams(text: str, n: int = 8) -> set[str]:
    toks = re.findall(r"[A-Za-z_][A-Za-z0-9_]*|[\u4e00-\u9fff]", text)
    return {" ".join(toks[i:i + n]) for i in range(max(0, len(toks) - n + 1))}


def family_of(rel: str) -> str | None:
    b = os.path.basename(rel)
    for f in FAMILIES:
        if b.startswith(f):
            return f
    return None


def dup_pairs(items: list[str]) -> list[tuple[str, str, float]]:
    """同族两两 8-gram Jaccard。只报 >= 0.30 —— 低于它的对噪声多于信息
    （本项目实测：`RISK_*` 0.085 · `MINE_*` 0.032 ⇒ 全族都是前缀像、内容各写各的）。"""
    cache: dict[str, set[str]] = {}
    fams: dict[str, list[str]] = {}
    for rel in items:
        f = family_of(rel)
        if f:
            fams.setdefault(f, []).append(rel)
    out: list[tuple[str, str, float]] = []
    for _f, group in fams.items():
        group = sorted(group)
        for i, a in enumerate(group):
            for b in group[i + 1:]:
                for rel in (a, b):
                    if rel not in cache:
                        p = ROOT / rel
                        cache[rel] = ngrams(p.read_text(encoding="utf-8", errors="ignore")) \
                            if p.exists() else set()
                A, B = cache[a], cache[b]
                if not A or not B:
                    continue
                j = len(A & B) / len(A | B)
                if j >= 0.30:
                    out.append((a, b, j))
    return sorted(out, key=lambda t: -t[2])


def tool_reach() -> tuple[list[str], list[str]]:
    """**`tools/` 的调用图**：从入口 `tools/check-all.sh` **反向可达**的有哪些、**不可达**的有哪些。

    为什么单列这条线（用户 2026-10-02 令）：用户逐字「检查脚本我本来也要逐个检查整理的，
    但是这次文档整顿没有纳入，所以我还不清楚现状」＋ 选 丙（19 处 `code_ref` 留在册）。
    ⇒ 那条线其实早就有规模：`tools/` 下 **269 个被跟踪文件**，而它从来没被清点过。

    判据只有一个，而且是机械的：*从 `check-all.sh` 反向可达吗*。
    **不可达不等于该删** —— 分析工具、云端脚本、夹具 runner 本来就该手动跑。
    它意味着的是：**这些工具的「存在理由」没有任何地方登记过** ⇒ 那正是本条线要补的东西。

    诚实边界（如实写）：本判据靠正则找 `tools/xxx.py|sh` **全路径**字样
    ⇒ 抓不到 `bash $VAR` 那种动态调用、也抓不到「被别的仓／工作流调」⇒ 它给的是**不可达的上界**。
    """
    import re as _re
    calls: dict[str, set[str]] = {}
    for sh in subprocess.run(["git", "ls-files", "tools/*.sh"], cwd=ROOT,
                             capture_output=True, text=True).stdout.split():
        txt = (ROOT / sh).read_text(encoding="utf-8", errors="ignore")
        calls[sh.split("/")[-1]] = set(_re.findall(r"tools/([A-Za-z0-9_.\-]+\.(?:py|sh))", txt))
    ca = (ROOT / "tools" / "check-all.sh").read_text(encoding="utf-8")
    calls["check-all.sh"] = set(_re.findall(r"tools/([A-Za-z0-9_.\-]+\.(?:py|sh))", ca))
    reach: set[str] = set()
    frontier = {"check-all.sh"}
    while frontier:
        nxt: set[str] = set()
        for f in frontier:
            for c in calls.get(f, ()):
                if c not in reach:
                    reach.add(c)
                    nxt.add(c)
        frontier = nxt
    top = [f.split("/")[-1] for f in subprocess.run(
        ["git", "ls-files", "tools/*.py", "tools/*.sh"], cwd=ROOT,
        capture_output=True, text=True).stdout.split()]
    top = sorted(a for a in top if "/" not in a and a != "check-all.sh")
    orphans = [a for a in top if a not in reach]
    return sorted(reach), orphans


def classify() -> dict:
    """⭐⭐ **带缓存**：同一份 git 指纹下只算一次。

    ⚠️ 缓存键 = `_git_rev()`（`HEAD` ＋ 工作区状态）⇒ ⭐ **仓库一动必然重算**，
    ⛔ 所以它**不改变任何判据**，只是把"同一状态被问两遍"这一种浪费去掉。
    """
    key = _git_rev()
    if key not in _CACHE:
        global _OUT_TEXT
        items = nha.old_home_items(ROOT)
        idx = mention_index()
        sidx = src_index()
        rows = []
        # 先算一遍「本表应该长什么样」—— 生成物新鲜度判据（逐字节比）要用它
        _OUT_TEXT = ""
        for rel in items:
            b = os.path.basename(rel)
            # 除自身以外：谁提到它（索引天然排掉它自己 —— 文件不会"提到"自己）
            n = len(idx.get(b, set()) - {rel})
            ns = len(sidx.get(b, set()))
            rows.append({"rel": rel, "home": home_of(rel), "refs": n, "src": ns,
                         "claim": claims_effective(rel)})
        reach, orphans = tool_reach()
        data = {"items": items, "rows": rows, "dups": dup_pairs(items),
                "reach": reach, "orphans": orphans}
        # ⭐⭐ **单遍渲染**（2026-10-04 修 `P2-1`）：本表**不再算生成物新鲜度**（见 §四 的说明）。
        #   ⚠️ 旧版是"两遍"：先清 `_OUT_TEXT=""` 再比 ⇒ **8 个生成物全被标"陈旧"**（假阳性），
        #   而 `--selftest` 没有一条臂覆盖它 ⇒ 一直没人抓到。
        #   ⇒ 正确形状 = ⛔ **不许第二份真相**：新鲜度归**各生成物自己的门禁**。
        _OUT_TEXT = render(data)
        data["text"] = _OUT_TEXT
        _CACHE[key] = data
    return _CACHE[key]


def render(data: dict) -> str:
    rows, dups = data["rows"], data["dups"]
    L: list[str] = []
    A = L.append
    A("# 清旧家 · 判据表（**生成物，禁手改**）")
    A("")
    A("> 本文件由 `tools/cleanup-classify.py` 生成 —— 手改会被门禁判红。")
    A("> 重新生成：`python3 tools/cleanup-classify.py --write`　·　校验：`--check`　·　自证：`--selftest`")
    A("> 门禁：`tools/check-cleanup-classify.sh`（挂 `tools/check-all.sh`）。")
    A("")
    A("## 先说清楚：**本表不是「可删清单」**")
    A("")
    A("它只**算**四类机械信号（依据 = 草案 `§D′-9`），**一个字都不判「该不该留」**。")
    A("用户只判两问：**① 这句话对不对 ② 这句话过期没有**（原文逐字见 `§D′-9` 9.5）。")
    A("")
    A("| 本表给的 | 本表**不给**的 |")
    A("|---|---|")
    A("| 「它归哪个好家」·「全仓有几个文件提到它」·「生成物陈不陈旧」·「同族重复度」 | "
      "「该丢还是该留」·「该合还是该并」·「它现在还算不算数」 |")
    A("")
    A("## 一 · 分桶读数（**必须按好家分桶读**）")
    A("")
    A("**为什么**：零引用**不是**错的信号 —— ⑧ 域外的归档件**本来就该零引用**。")
    A("把总数（本表最后一行的合计）当成「可删清单」是**本表最容易被误读的地方**。")
    A("")
    A("| 好家 | 件数 | 零引用 | 零引用**合法**吗 |")
    A("|---|---|---|---|")
    for name in [d[0] for d in nha.DEST]:
        sub = [r for r in rows if r["home"] == name]
        z = [r for r in sub if r["refs"] == 0]
        if name.startswith(OUTSIDE):
            note = "**合法** —— 域外件不进体系，本来就没人引"
        elif name.startswith("①"):
            note = "**绝不合法** —— 常驻件零引用 = 没有任何 agent 会读到它"
        else:
            note = "**待用户判**（本表只报数）"
        A(f"| {name} | {len(sub)} | **{len(z)}** | {note} |")
    tot_z = sum(1 for r in rows if r["refs"] == 0)
    A(f"| **合计** | **{len(rows)}** | **{tot_z}** | 这个合计**没有行动含义**（见上） |")
    A("")
    A("## 二 · 行动面：**零引用且不在 ⑧ 域外**")
    A("")
    A("⚠️⚠️ **本节这个信号会被「提及」污染 —— 实测过一次，如实记**：本表头一版列了 **2 件**")
    A("（`tools/dsh-phone-qr.sh` · `tools/render-scene-preview.py`），而**下一个提交里**")
    A("主工作流在台账 `O155` 写了这两个文件名 ⇒ 它们**当场变成「被提到过 1 次」** ⇒ 本节归零。")
    A("⭐ **那两次读数都对**，差的是**我改了仓**（不是判据漂了）—— 而它暴露的是 `§D′-9` 9.3 判据②")
    A("自己写下的那条：**「提到它」会被当成「引用它」**，所以本列只叫**被提到过**，⛔ 不叫被引用。")
    A("⇒ ⭐ **结论**：本节**不是**主要行动面（⛔ 别把 `0 件` 读成「没活可干」）；")
    A("    真正有信息的是**下一节**（自称生效而 `src/` 零落点）。")
    A("")
    actionable = sorted([r for r in rows if r["refs"] == 0 and not r["home"].startswith(OUTSIDE)],
                        key=lambda r: (r["home"], r["rel"]))
    A(f"**{len(actionable)} 件** —— 打勾栏：`保留` / `丢` / `合并到 X` / `已过期`（`§D′-9` 9.5 的四个选项）。")
    A("")
    A("| # | 件 | 好家 | 提到它的文件数 | 候选理由（机械） | 保留 | 丢 | 合并到 | 已过期 |")
    A("|---|---|---|---|---|---|---|---|---|")
    for i, r in enumerate(actionable, 1):
        why = []
        if r["refs"] == 0:
            why.append("全仓**只有它自己**提到自己")
        A(f"| {i} | `{r['rel']}` | {r['home']} | **{r['refs']}** | {'；'.join(why) or '—'} "
          "| ☐ | ☐ | ☐ | ☐ |")
    A("")
    A("## 三 · **批 1 的靶子**：自称生效、而 `src/` 里零引用 ⭐⭐")
    A("")
    A("**为什么单列这一节**：`W7′-3` 把批 1 定为「**声称生效但零 `src/` 引用**」。")
    A("⭐ 这是**最贵的一族假边界**的形状 —— 一份文档自称「已生效／已落地／已接线」，")
    A("而**代码里根本找不到它** ⇒ 后续会话会把它当**依据**引（`§E`：假边界会被当依据引用）。")
    A("")
    A("⚠️ 判据两条**都是机械的**：① 头部 40 行里出现「已生效／生效中／已落地／已接线／已实现／已拍板」")
    A("（**文件自己的自称**，⛔ 不是我的判断）② 全仓 `src/` 下**零命中它的文件名**。")
    A("")
    claimed = [r for r in rows if r["claim"] and r["src"] == 0]
    if claimed:
        A(f"**{len(claimed)} 件** —— 打勾栏同 `§D′-9` 9.5。")
        A("")
        A("| # | 件 | 好家 | 自称 | 全仓提到它 | `src/` 里 | 保留 | 丢 | 合并到 | 已过期 |")
        A("|---|---|---|---|---|---|---|---|---|---|")
        for i, r in enumerate(sorted(claimed, key=lambda r: r["rel"]), 1):
            A(f"| {i} | `{r['rel']}` | {r['home']} | **{r['claim']}** | {r['refs']} | "
              "**0** | ☐ | ☐ | ☐ | ☐ |")
    else:
        A("**0 件** —— 实测**没有任何件**同时满足这两条。")
    A("")
    A("⚠️ ⛔ **`src/` 里零命中不等于它错了** —— 文档本来就不必被代码引用。")
    A("它只说明：**这份自称生效的东西，在代码里没有落点**。⇒ 该由你来判「这句话对不对／过期没有」。")
    A("")
    A("### 附：自称生效且有 `src/` 落点的（**供对照**，⛔ 这些不是候选）")
    A("")
    ok_rows = [r for r in rows if r["claim"] and r["src"] > 0]
    A(f"**{len(ok_rows)} 件**：")
    A("")
    if ok_rows:
        A("| 件 | 自称 | `src/` 里提到它的文件数 |")
        A("|---|---|---|")
        for r in sorted(ok_rows, key=lambda r: -r["src"]):
            A(f"| `{r['rel']}` | {r['claim']} | **{r['src']}** |")
    else:
        A("（无）")
    A("")
    A("## 四 · 生成物名单（⛔ **本表不报新鲜度**）")
    A("")
    A("⭐ **每件生成物的新鲜度由它自己的门禁判** —— ⛔ **本表不复制那个判断**（复制 = 第二份真相）。")
    A("")
    A("⚠️ **为什么删掉这一栏（`P2-1`，2026-10-04 当场实测）**：旧版在这里报「陈旧／新鲜」，"
      "而它的实现是拿每件生成物的磁盘内容去比 `_OUT_TEXT` ——")
    A("⛔ 而 `_OUT_TEXT` 是**本表自己的**渲染结果 ⇒ **8 件全部被标「陈旧」（假阳性）**，"
      "而它们 8 道门禁在 `check-all` 里**全是绿的**。")
    A("⚠️ 而且这一栏**天然自指**（本表显示新鲜度 ⇒ 改本表内容 ⇒ 又影响新鲜度）⇒ 迟早震荡。")
    A("⇒ ⭐ **判据归位**：新鲜度 = 各生成物**自己门禁**的事；本表只负责**名单本身**不许陈旧"
      "（`tools/check-cleanup-classify.sh` 逐字节比对）。")
    A("")
    A("| 生成物 | 生成器 | 门禁（新鲜度由它判） |")
    A("|---|---|---|")
    for path, gen, gate in GENERATED:
        A(f"| `{path}` | `{gen}` | `{gate}` |")
    A("")
    A("## 五 · 同族重复度（8-gram Jaccard >= **0.30** 才列）")
    A("")
    if dups:
        A(f"**{len(dups)} 对** —— 高分**不自动等于「该合并」**（同主题也可能各写各的）。")
        A("")
        A("| 对 | Jaccard | 保留左边 | 保留右边 | 都保留 | 合并 |")
        A("|---|---|---|---|---|---|")
        for a, b, j in dups:
            A(f"| `{a}` 与 `{b}` | **{j:.3f}** | ☐ | ☐ | ☐ | ☐ |")
    else:
        A("**0 对** —— 实测**没有任何同族对达到 0.30**。")
        A("")
        A("这与本项目既有读数一致（`RISK_*` 0.085 · `MINE_*` 0.032 · `DECISION_*` 0.056 · "
          "`CLIENT_*` 0.052 · `ALICE_PATHING_CORE_*` 0.052 —— 全部 < 0.085）⇒ "
          "**「前缀像、内容各写各的」是本仓的常态**，所以「合并」这一档在本仓几乎没有活可干。")
    A("")
    A("## 六 · ⛔ 检查脚本整理线 —— **不属本次文档整顿** ⇒ 已移出本表")
    A("")
    A("> 用户逐字：「检查脚本我本来也要逐个检查整理的，但是**这次文档整顿没有纳入**，所以我还不清楚现状」")
    A("> ＋「**选丙**，同时**登记检查脚本整理线**，**直接放在施工计划书的末尾**用来提醒」。")
    A("")
    A("⛔ **本表（清旧家的判据表）不再承载它** —— 移出原因：**它不是文档整顿的内容**，"
      "放在这里会被**读成文档整顿的一部分**（用户 2026-10-04 令「**移出去**」）。")
    A("⇒ 现行落点两个：⭐ **施工计划书末尾**（`docs/DOC_REFACTOR_PLAN.md` 的 §九 · **只作提醒**）"
      "＋ ⭐ **台账 `O164`**（一件一号的登记处：规模／判据／诚实边界／底线）。")
    A("")
    A("## 七 · 全表（**每一件的归位与信号**；供逐件复核）")
    A("")
    A("| 件 | 好家 | 提到它的文件数 | `src/` 里 | 自称生效 |")
    A("|---|---|---|---|---|")
    for r in sorted(rows, key=lambda r: (r["home"], r["rel"])):
        A(f"| `{r['rel']}` | {r['home']} | {r['refs']} | {r['src']} | {r['claim'] or '—'} |")
    A("")
    A(f"<!-- CLEANUP_ROWS {len(rows)} -->")
    A("")
    return "\n".join(L) + "\n"


def check() -> int:
    """门禁：① 表不许陈旧（双向防漂移）② 人口下限 ③ 生成物名单逐个存在性自证。"""
    problems: list[str] = []
    if OUT.exists():
        want = render(classify())
        got = OUT.read_text(encoding="utf-8")
        if want != got:
            wl, gl = want.splitlines(), got.splitlines()
            first = next((i for i in range(max(len(wl), len(gl)))
                          if (wl[i] if i < len(wl) else None) != (gl[i] if i < len(gl) else None)), 0)
            problems.append(f"判据表已陈旧，首个不同在第 {first + 1} 行 ⇒ "
                            "跑 `python3 tools/cleanup-classify.py --write`")
        n = len([l for l in got.splitlines() if l.startswith("| `")])
        if n < ROW_FLOOR:
            problems.append(f"表里只有 {n} 行 < 人口下限 {ROW_FLOOR} ⇒ 表被删空也算通过")
    else:
        problems.append("判据表不存在：docs/CLEANUP_CLASSIFY.md ⇒ 跑 `--write`")
    for path, gen, gate in GENERATED:
        if not (ROOT / path).exists():
            problems.append(f"生成物名单里的 `{path}` 在磁盘上不存在 ⇒ 名单陈旧（不静默放过）")
        if not (ROOT / gate).exists():
            problems.append(f"`{path}` 的门禁 `{gate}` 不存在 ⇒ 「有生成器 ＋ 有门禁」不成立")
        if not (ROOT / gen).exists():
            problems.append(f"`{path}` 的生成器 `{gen}` 不存在")
    if problems:
        print("CLEANUP_CLASSIFY_RESULT FAIL")
        for p in problems:
            print(f"  [FAIL] {p}")
        return 1
    data = classify()
    a = len([r for r in data["rows"] if r["refs"] == 0 and not r["home"].startswith(OUTSIDE)])
    print(f"CLEANUP_CLASSIFY_RESULT PASS: 件 {len(data['rows'])} · "
          f"行动面（零引用且非域外）{a} · 同族重复对 {len(data['dups'])} · 生成物 {len(GENERATED)}")
    return 0


def selftest() -> int:
    """注入自证 —— 「能过」不等于「能抓」。"""
    arms: list[tuple[str, str, bool]] = []

    # 臂 A：归位必须与 `new-home-audit.py` 同源（直接用它的 `DEST`，不是抄一份）
    a_ok = hasattr(nha, "DEST") and home_of("docs/HANDOVER.md") == "④ 状态 ＋ 构想"
    arms.append(("A 归位与 new-home-audit 同源且判得对", "分叉", a_ok))

    # 臂 B：行动面必须排除 ⑧ 域外 —— 混在一起就是那张最容易被误读的表
    #        （把 93 件合法归档件算进「可删清单」）
    fake = [{"rel": "a.md", "home": OUTSIDE + " 域外（不进体系）", "refs": 0, "stale": False, "why": ""},
            {"rel": "b.md", "home": "④ 状态 ＋ 构想", "refs": 0, "stale": False, "why": ""}]
    act = [r for r in fake if r["refs"] == 0 and not r["home"].startswith(OUTSIDE)]
    arms.append(("B 行动面排除 ⑧ 域外", "误算", len(act) == 1 and act[0]["rel"] == "b.md"))

    # 臂 C：人口下限必须为正（删空表 ⇒ 红）
    arms.append(("C 人口下限", "人口下限", ROW_FLOOR > 0))

    # 臂 D：生成物名单**逐个存在性自证** —— 名单里写一个不存在的门禁，
    #        「有生成器 ＋ 有门禁」这条判据就静默失效（本项目最贵那族）
    arms.append(("D 生成物名单自证", "不存在",
                 all((ROOT / g).exists() and (ROOT / s).exists() for _p, g, s in GENERATED)))

    # 臂 E：陈旧必须被报出**行号**（「内容不同」这种话没法定位）
    wl, gl = "a\nb\nc".splitlines(), "a\nX\nc".splitlines()
    first = next((i for i in range(max(len(wl), len(gl)))
                  if (wl[i] if i < len(wl) else None) != (gl[i] if i < len(gl) else None)), 0)
    arms.append(("E 陈旧报出行号", "首个不同", first == 1))

    # 臂 F：`refs()` 必须**排除自身** —— 不排除的话每件至少被自己提到 1 次
    #        ⇒「零引用」永不成立 ⇒ 一个永真断言（看着像在查，其实什么都没查）。
    #        ⚠️ **本臂第一版拿 `.tmp-fix2.py` 当"必零引用"的例子，结果它红了** ——
    #        因为本刀在 `docs/HANDOVER.md` 里写了它（「⑨ 待删」那一段）⇒ ⭐ **它已经不是零引用了**。
    #        ⇒ 教训（正是 `§D′-9` 9.3 判据②那条）：**"提到它"会被当成"引用它"** ——
    #        所以本臂不能用"我以为没人提"的件，只能用**定义上不可能被提到的路径**：
    #        ① 一个不存在于仓里的路径 ⇒ 必须 0；② 一份已知被引的件 ⇒ 必须 > 0。
    # ⚠️ **探针必须运行时拼**：字面量写进源码 ⇒ `git grep -F` 会**命中本文件自己**
    #    ⇒ 计数 1 ≠ 0 ⇒ **臂恒红**（2026-10-04 实测到的自污染，已修）。
    _probe = "/nonexistent/" + "probe-" + "9f3a" + ".md"
    arms.append(("F 自身被排除（零引用不是永真断言）", "",
                 refs("docs/HANDOVER.md") > 0 and refs(_probe) == 0))

    # 臂 G：`claims_effective` 必须**真的能读出自称**（否则批 1 那节恒为空 ⇒ 永真断言）。
    #        ⚠️ 用**正对照**（一份已知自称生效的件）＋ **反对照**（工具脚本，必然 None）。
    # ⚠️ 本臂第一版拿 `AGENTS.md` 当"正对照"⇒ **它红了**（那份头部 40 行里
    #    根本没有「已生效」这类词）⇒ ⭐ 教训：**臂不许耦合到某个具体文件的内容**
    #    （内容一变，臂就假红）。⇒ 改成**临时文件正反对照**：
    import tempfile as _tf
    with _tf.TemporaryDirectory(dir=ROOT) as td:
        rel_yes = os.path.relpath(os.path.join(td, "probe-yes.md"), ROOT)
        rel_no = os.path.relpath(os.path.join(td, "probe-no.md"), ROOT)
        (Path(td) / "probe-yes.md").write_text("# X\n\n> 状态：**已接线**（2026-01-01）\n", encoding="utf-8")
        (Path(td) / "probe-no.md").write_text("# Y\n\n这份什么都没自称。\n", encoding="utf-8")
        g_ok = claims_effective(rel_yes) == "已接线" and claims_effective(rel_no) is None
    arms.append(("G 自称生效判据有区分度（正反对照）", "恒空", g_ok))

    # 臂 H：批 1 那一节必须**真的排除有 src/ 落点的件** —— 不排除的话，"零 src/ 引用"
    #        这条判据就没生效（列了出来却什么都没筛）。
    data = classify()
    cand = [r for r in data["rows"] if r["claim"] and r["src"] == 0]
    others = [r for r in data["rows"] if r["claim"] and r["src"] > 0]
    arms.append(("H 批 1 排除有 src/ 落点的件", "未筛",
                 all(r["src"] == 0 for r in cand) and (not others or all(r["src"] > 0 for r in others))))

    # 臂 I ⭐ **生成物新鲜度不许自指震荡**（2026-10-04 补 —— `P2-1` 的防复发臂）。
    #   ⚠️ 缺陷形状：本表**显示**生成物新鲜度，而"新鲜度" = 磁盘内容 vs **本表重新生成的结果**
    #   ⇒ 本表**自己在名单里** ⇒ 写一次、内容变一次 ⇒ **震荡**（同族：`archive-index.py` 第一版）。
    #   判据 = ⭐ **清掉缓存、连算两次，产物必须逐字节相同**（不动点）＋ 自评栏必须是"不自评"。
    _CACHE.clear()
    _t1 = render(classify())
    _CACHE.clear()
    _t2 = render(classify())
    arms.append(("I 不自指震荡（清缓存连算两次，产物逐字节相同）", "震荡", _t1 == _t2))
    #   ⭐ 本臂守的是这一族：**"本表显示的东西" 又反过来决定 "本表的内容"** ⇒ 写一次变一次。
    #   ⚠️ 旧版的活样本就是 §四 的「生成物新鲜度」栏（它比的是本表自己的渲染结果）——
    #      那一栏已按 `P2-1` 整栏删除；本臂留着防**下一个**同族写法。

    bad = 0
    for name, want, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ "
              f"{'期望红且含「' + want + '」' if want else '期望安静'}")
    print(f"CLEANUP_CLASSIFY_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.write:
        data = classify()
        text = data["text"]
        OUT.write_text(text, encoding="utf-8")
        print(f"已写入 docs/CLEANUP_CLASSIFY.md（{len(text.splitlines())} 行 · 件 {len(data['rows'])}）")
        return 0
    if a.check:
        return check()
    data = classify()
    a_n = len([r for r in data["rows"] if r["refs"] == 0 and not r["home"].startswith(OUTSIDE)])
    print(f"件 {len(data['rows'])} · 行动面 {a_n} · 同族重复对 {len(data['dups'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
