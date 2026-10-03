#!/usr/bin/env python3
"""**决策索引**（台账 `O12`，2026-09-28）：把 23,616 行的 `docs/AI_DECISIONS.md` 压成一张能读的表。

## 为什么有它（`O12` 实测，不是感觉）
* `AGENTS.md` 原文让每个会话「先读 `AI_DECISIONS.md`」，而它是 **23,616 行 / 1,225,967 字符**
  ⇒ 粗估 **0.6M–1.2M tokens** ⇒ **读不进 512K 窗口** ⇒ **那条启动指令按字面不可满足**。
* 而它是**唯一**的规则出处：找不到前置裁定就会**重复立法**
  （活例 = `D-455` 漏引 `D-080`，`survey/43 §10.2` —— 不是理论风险，是**已经发生**过的）。
* 它还没有索引，且**标题级别不统一**：161 个编号用 `##`、378 个用 `###`
  ⇒ ⚠️ `grep "^### D-"` **漏掉 114 个编号**（这就是"靠名字找东西静默少给一半"的同一族）。
  ⚠️ **2026-10-02 实测扩展**：**54 条用 `####`**，其中 **`D-203` 三次出现全是 `####`** ⇒
  只认 `#{2,3}` 会**让 `D-203` 在索引里彻底不可见**（`grep -c 'D-203' docs/DECISIONS_INDEX.md` = **0**）。
  ⇒ 本文件认 **`#{2,4}`**（⚠️ 与 `D-166` 无关 —— 曾有一版说"`D-166` 未闭合罩 17,437 行"，那是错命令造出来的，真值 **124 行**）。

## 两个反向纪律（照 `tools/capability-list.py` 的模子）
* **不引入第二个真相源**：本索引**不新增任何事实** —— 标题 / 状态 / 追加数全从 `AI_DECISIONS.md` 读，
  引用数从全仓扫出来。**与 `AI_DECISIONS.md` 不一致时以它为准**（生成器改的是索引，不是决策）。
* **解析崩塌必须响亮失败**：`ASSERTIONS` 带**人口下限**（决策编号数 / 标题数）——
  少读一截 ⇒ 红，不许"少一行悄悄过"。

## 它**不是**什么（防误用）
* ⚠️ 它是**索引**，不是规则的替代：要读某条决策的**正文**，去 `docs/AI_DECISIONS.md`（见文末定位命令）。
* ⚠️ 它**不是**"哪些决策已废弃"的权威 —— `状态` 字段**只覆盖约 15%**；
  显示 `—` 只表示"**条目里没写状态**"，**不代表废弃**。
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DECISIONS = ROOT / "docs" / "AI_DECISIONS.md"
OUTPUT = ROOT / "docs" / "DECISIONS_INDEX.md"

#: 引用扫描范围（**本索引自身排除**，否则自引用会让每个编号恒 +1）
#: ⚠️ 「引前」（某条决策引了几条别的决策）不走这里 —— 它由每条自己的**文本段**算，见 `parse_decisions`。
SCAN_SOURCES: list[tuple[str, Path, set[str], set[str]]] = [
    ("src", ROOT / "src" / "main" / "java", {".java"}, set()),
    ("tools", ROOT / "tools", {".sh", ".py", ".mjs"}, set()),
    ("其它", ROOT / "docs", {".md"}, {"docs/AI_DECISIONS.md", "docs/DECISIONS_INDEX.md"}),
    ("survey", ROOT / "survey", {".md"}, set()),
]

#: ⚠️ 人口下限：解析崩塌 ⇒ 响亮失败（2026-10-02 实测 = **565 / 724**，留足余量但拦得住崩塌）
#: ⚠️ 键必须与 `build()` 里 `stats` 的键**逐字相同**（不一致 ⇒ `KeyError` 崩溃，这比静默通过好）
ASSERTIONS = {"决策编号数": 300, "标题数": 400}

#: ⚠️ **末尾那个 `\b` 是必须的，⛔ 不是装饰**：写成 `(D-\d+)\s*(.*)` 时，
#: `### D-250X：…` 会被解析成编号 **`D-250`** ⇒ **畸形编号被静默吞成合法编号**
#:（于是"缺号"判据永远看不到它，而且它还会**冒充**一个真编号）。
#: 2026-10-02 由**注入臂自证**抓出：我故意写了个 `### D-250X：` 去测缺号断言，
#: 结果**条目数一条没变、缺口一个没多** —— 解析器把它读成了 `D-250`。
#: ⇒ 加 `\b` 后 `D-250X` **不匹配** ⇒ 该编号真的变成缺口（⇒ 断言③ 响亮报出来）。
HEADING = re.compile(r"^(#{2,4})\s+(D-\d+)\b\s*(.*)$")
#: ⚠️ **状态只认"整行的状态声明"**，⛔ 不在正文里到处找 `状态：`
#: 2026-10-02 实测教训：旧写法 `状态[：:]\s*\*{0,2}\s*(…)` 带**两个静默错** ——
#:   ① ⛔ **假阳性 3 条**：`**挖掘状态**:` / `重启状态：` / `**阶段状态：**` 都含"状态" ⇒ 把
#:      `target= …` / `未恢复上次的请示/任务` / `sweepStarted` 当"状态"写进了索引；
#:   ② ⛔ **漏收 20 条**：`**状态**：…`（**加粗**写法）旧正则**完全不认** ⇒ 这些条目的状态显示成 `—`（= "没写"），
#:      而它**明明写了** —— 这正是"少给一半还看起来正常"的同一族。
#: ⇒ 现在要求：`状态` 前只能是行首 ＋ 可选列表符 `-`/`*` ＋ 可选 `**`，紧跟 `：`/`:`。
STATUS = re.compile(r"^\s*[-*]?\s*(?:\*\*)?状态(?:\*\*)?\s*[：:]\s*(?:(?<!\*)\*{1,2}\s*)?([^\n（(]+?)\s*(?=\*\*|（|\(|$)", re.MULTILINE)
DNUM = re.compile(r"\bD-\d+\b")
#: （保留：仅用于文档说明；判定追加条目现在按"同编号的第 2+ 个标题"数，不再做文字匹配）
FOLLOWUP_KEYS = ("附注", "修正", "修订", "验收", "补遗", "追加", "更正", "补充")

#: ⚠️ **门禁比对到此行为止**。本行以下（「全仓引用热度」）是**易变列**：
#: 只要**任何** doc / src / tools 里多提一次某个 `D-###`，计数就变 —— 而台账与断点几乎每把刀都在引编号。
#: 2026-09-28 实测教训：第一版把计数列放进被比对的内容 ⇒ **门禁在落地后几分钟就红了**
#:（我刚改完台账它就"陈旧"）⇒ 照那样**几乎每把刀都得跑一次 `--write`**，门禁会退化成"例行敲一下"。
#: ⇒ **结构列（编号 / 标题 / 状态 / 追加 / 引前）进比对；热度列只生成、不比对**（`--write` 顺手刷新）。
APPENDIX_MARKER = "## 附录：全仓引用热度（**不计入门禁比对**）"

# ==================== C1 三条断言（2026-10-02，用户批准；`docs/HANDOVER.md` 断点六十四 §H-4） ====================
#
# ⭐ 为什么这三条值得加：它们**机械可判、⛔ 不需要定义新词、⛔ 不需要语义**，
#    而且挡住的都是**已经真实发生过**的错（不是假想风险）。
#
# ⛔ **必须带基线白名单**（借 `tools/redline-gates.py` 的 `BASELINE_PROSE` 模子）：
#    这三条判据**今天就红**（旧账）⇒ 直接加会让门禁当场红 ⇒ 必须把旧账列出来并：
#      · ⭐ **只许变短**：条目被修掉后不必删它（指纹仍在也能过）；但**新出现的**会被抓；
#      · ⚠️ 若某个指纹**不在**任何地方 ⇒ 说明判据漏了东西（或正则写坏）⇒ **响亮失败**，
#        ⛔ 不许"基线还在、判据已经扫不到了"却照样绿（这正是"扫不到就报绿"那一族）。

#: 【断言①】重复标题 ⇒ **警告**（⛔ 不是红：2026-10-02 实测 79 个编号 / 159 条额外标题，全是合法的"附注"写法）
#: ⚠️ 断点六十三记的"会红 57 处"是**旧的 h2/h3 口径**，按 `#{2,4}` 是 **79 个编号**。
DUPLICATE_IS_WARNING = True

#: 【断言②】⛔ **归错档** ⇒ **红**。判据 = 出现不相邻（中间夹着别的编号）。
#: ⚠️ 这不是"编号被重复" —— 重复是合法的（附注），**归错档才是病**。
#: 基线 = 2026-10-02 实测 23 条（`(编号, 行号)` 指纹，行号参与比对 ⇒ 正文一挪动就会重新报 ⇒ 那是对的）。
BASELINE_MISFILED: frozenset[tuple[str, int]] = frozenset({
    ("D-043", 567), ("D-073", 1225), ("D-077", 1377), ("D-114", 3072),
    ("D-133", 4448), ("D-153", 5564), ("D-152", 5585), ("D-166", 6274),
    ("D-165", 6311), ("D-170", 6555), ("D-180", 6940), ("D-176", 6954),
    ("D-202", 8216), ("D-205", 8396), ("D-204", 8413), ("D-205", 8427),
    ("D-204", 8440), ("D-205", 8451), ("D-269", 10930), ("D-330", 12937),
    ("D-334", 13116), ("D-351", 14825),
})
#: ⚠️ 人口下限：基线 23 条 ⇒ 判据至少得扫出这么多，否则说明它**坏了**（⛔ 不许"扫不到就报绿"）
MISFILED_FLOOR = 22

#: 【断言③】编号缺口 ⇒ **红**（先用基线白名单）。
#: ⚠️ 「跳号」与「被删掉的条目」机械上分不开 ⇒ ⛔ 白名单是**唯一**能落地的口径；
#: ⛔ 白名单只许变短（真补上一条 ⇒ 它的指纹消失，门禁**仍然绿** —— 见上面的"只许变短"）。
BASELINE_MISSING: frozenset[int] = frozenset({178, 316, 367, 411})
MISSING_FLOOR = len(BASELINE_MISSING)


def assert_duplicates(heads: list[tuple[int, str, str]]) -> list[str]:
    """断言①：重复标题 ⇒ **警告文案**（⛔ 不进 `problems`，⛔ 不让门禁红）。"""
    per: Counter = Counter(h[1] for h in heads)
    dups = sorted((n for n, c in per.items() if c > 1), key=lambda s: int(s[2:]))
    if not dups:
        return []
    extra = sum(per[n] - 1 for n in dups)
    return [f"⚠️ 重复标题 **{len(dups)}** 个编号 / 额外标题 **{extra}** 条"
            f"（合法写法：同编号的 `附注/修正`）—— 例：{', '.join(dups[:8])}"
            f"{' …' if len(dups) > 8 else ''}"]


def assert_misfiled(found: list[dict]) -> tuple[list[str], list[str]]:
    """断言②：**归错档** ⇒ 红。基线之外的每一条都是**新病**。返回 (problems, warnings)。

    ⚠️⚠️ **指纹按「编号」判旧账，⛔ 不用「(编号, 行号)」—— 这是注入臂自证逼出来的修法。**
    2026-10-02 实测：第一版用 `(编号, 行号)` ⇒ 在文件里**插入任意一行**，
    基线全体行号错位 ⇒ 门禁同时报"22 条是新病"和"22 条已消失" ⇒ **两个方向全误报**。
    ⇒ 现在：**编号在基线里 = 旧账**（行号变了也放行）；**编号不在基线里 = 新病（红）**。
    ⇒ 少报的风险（同一编号**换个位置**再犯）由「基线只许变短」这条纪律兜底，⛔ 不靠行号自动判。
    """
    problems: list[str] = []
    if len(found) < MISFILED_FLOOR:
        problems.append(f"⛔ 归错档判据**扫不到东西**了：扫出 {len(found)} < 人口下限 {MISFILED_FLOOR}"
                        " ⇒ 不是『病好了』，是**判据坏了**（标题格式变了？`HEADING` 写坏了？）"
                        "—— 照这样它会对新病**静默报绿**")
    known = {num for num, _line in BASELINE_MISFILED}
    for m in found:
        if m["num"] not in known:
            problems.append(f"⛔ **归错档**：`{m['num']}` 的标题 @行 {m['line']} 落在 `{m['parent']}`"
                            " 的条目体里 ⇒ 它的正文/`状态：` 会被算到别人头上"
                            "（读者拿到的是**拼接出来的假条目**）"
                            " ⇒ 修法：把它移到自己编号的段里（与首条相邻）")
    warnings: list[str] = []
    gone = sorted(known - {m["num"] for m in found}, key=lambda s: int(s[2:]))
    if gone:
        warnings.append(f"⚠️ 基线里 {len(gone)} 个编号的归错档已消失（修好了 ⇒ 好事）："
                        f"{', '.join(gone)}；⚠️ 若**不是**修的、而是正文挪动导致的 ⇒ 同刀更新基线")
    shifted = sum(1 for m in found if (m["num"], m["line"]) not in BASELINE_MISFILED)
    if shifted:
        warnings.append(f"⚠️ {shifted} 条归错档的**行号**与基线不同（正文挪过）——"
                        " 仍按旧账放行（指纹按编号）；要精确到行请同刀刷新基线")
    return problems, warnings


def assert_missing(entries: list[dict]) -> list[str]:
    """断言③：编号缺口 ⇒ 红（基线之外）。

    ⚠️ 口径：只在 `1..max(已见编号)` 里找缺口 ⇒ **新增编号不会误报**。
    ⛔ 「跳号」与「被删掉的条目」机械上分不开 ⇒ 基线白名单是唯一能落地的口径。
    """
    ids = {int(e["num"][2:]) for e in entries}
    if not ids:
        return ["⛔ 一条编号都没读到 ⇒ 判据失效"]
    gaps = sorted(n for n in range(1, max(ids) + 1) if n not in ids)
    problems: list[str] = []
    if len(gaps) < MISSING_FLOOR:
        problems.append(f"⛔ 缺号判据**扫不到东西**了：只有 {len(gaps)} 个 < 下限 {MISSING_FLOOR}"
                        " ⇒ 判据坏了，照这样会对新缺口静默报绿")
    for n in sorted(n for n in gaps if n not in BASELINE_MISSING):
        problems.append(f"⛔ **编号缺口**：`D-{n:03d}` 在 `1..{max(ids)}` 里不存在任何标题"
                        " ⇒ 要么是被删的条目（须登记基线），要么是漏号")
    return problems


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def parse_decisions(text: str) -> tuple[list[dict], int, dict[str, int]]:
    """→ ([{num,title,status,followups,cites,line}], 标题总数, 各级别标题数)。

    ⚠️ **编号总数必须把 `##` / `###` / `####` 三种标题都算上**（2026-09-28 实测：
    `##` 独有 114 · `###` 独有 331 · 两者都有 47 ⇒ **并集 492**）。只数 `###` 会**漏 114 个编号** ——
    这正是 `O12` 里那个"靠名字找东西静默少给一半"的错，**我自己第一版就犯了一次**。

    ⚠️ **2026-10-02 再修一次**：还有 **54 条用 `####`**，其中 **`D-203` 三次全是 `####`** ⇒
    认 `#{2,3}` 时它**在索引里完全不存在**。⇒ 现在认 `#{2,4}`（2026-10-02 实测 **565 编号 / 724 标题**：
    h2 **205** · h3 **465** · h4 **54**）。
    ⛔ **但仍拦不住"真缺号"**：`D-178` `D-316` `D-367` `D-411` 在任何级别都扫不到 ⇒ 正则改不掉。
    """
    lines = text.splitlines()
    heads: list[tuple[int, str, str]] = []  # (行号, 编号, 标题原文)
    level_census: Counter = Counter()
    for i, line in enumerate(lines):
        m = HEADING.match(line)
        if m:
            heads.append((i, m.group(2), m.group(3).strip()))
            level_census[len(m.group(1))] += 1
    if not heads:
        return [], 0, {}

    per_num: Counter = Counter(h[1] for h in heads)
    entries: list[dict] = []
    seen: set[str] = set()
    for pos, (line_no, num, rest) in enumerate(heads):
        if num in seen:
            continue
        seen.add(num)
        # 本编号的**整段** = 从它第一个标题，到**下一个不同编号**的标题（⇒ 追加条目算在内）
        end = len(lines)
        for j in range(pos + 1, len(heads)):
            if heads[j][1] != num:
                end = heads[j][0]
                break
        block = "\n".join(lines[line_no:end])
        title = re.sub(r"^[：:\s]+", "", rest)
        #: ⚠️ **状态行被当成标题**：标题位置写的是 `状态：…` ⇒ 它**不是标题**，要往下找真标题。
        #: 2026-10-02 实测活例：`## D-533` 下一行是 `状态：生效（…）` ⇒ 索引里 D-533 的"标题"成了一整条状态
        #:（而它**明明有**状态，「状态」那列也是对的 ⇒ 同一份数据在两张表里语义不同，读者没法判断哪个是标题）。
        if re.match(r"^\**状态\**\s*[：:]", title):
            title = ""
        if not title:
            # 裸标题（如 `### D-374` 换行后才写标题）⇒ 取第一段**正文**
            for probe in lines[line_no + 1:end]:
                s = probe.strip().lstrip("#").strip()
                #: ⛔ 跳过纯**结构行**（空 / 围栏 / 表格 / 引用 / 分隔线 / 状态行 / 粗体小标题）——
                #:    它们都不是"标题"，早期版本会把 `**（2026-09-21，P0）**` 这类小标题抓成标题
                if not s or s.startswith(("```", "|", ">", "---")):
                    continue
                cand = s.lstrip("*").strip()
                cand = re.sub(r"^[-*+]\s*", "", cand).lstrip("*").strip()
                if not cand or cand.startswith("**") or re.match(r"^状态\**\s*[：:]", cand):
                    continue
                title = cand
                break
        title = re.sub(r"[*`]", "", title).strip()
        #: ⚠️ **剥掉打在标题位置上的结构前缀**（它们不是标题内容）：2026-10-02 实测 3 条
        #:（`标题：…` ×1 · `一、决定（日期）：…` ×2）—— 全是"裸标题 + 正文写成了章节"的残留。
        #: ⛔ 只剥**机械可判**的前缀（写死的几个），⛔ 不做通用启发式。
        for _pre in (r"^标题\s*[：:]\s*", r"^一、决定\s*(?:[（(][^）)]*[）)])?\s*[：:]\s*"):
            title = re.sub(_pre, "", title).strip()
        #: ⛔ 剥完若为空 ⇒ 说明原句就是那个前缀 ⇒ 退回原文（别把标题弄丢）
        if not title:
            title = re.sub(r"[*`]", "", rest).strip()
        statuses = [s.strip() for s in STATUS.findall(block) if s.strip()]
        entries.append({
            "num": num,
            "title": title[:88] or "(无标题)",
            "status": statuses[-1] if statuses else "—",   # 追加条目会更新状态 ⇒ 取最后一条
            "followups": per_num[num] - 1,
            "cites": len({n for n in DNUM.findall(block) if n != num}),
            "line": line_no + 1,
            "lines": [ln + 1 for ln, nm, _ in heads if nm == num],
            "block": block,
        })
    misfiled = detect_misfiled(heads)
    return entries, len(heads), dict(level_census), misfiled


def detect_misfiled(heads: list[tuple[int, str, str]]) -> list[dict]:
    """⛔ **归错档**：一条追加条目的标题写的是 `D-XXX`，但它物理上落在**别的编号**的条目体里。

    **机械判据（无需语义、无需新词）**：取本编号**连续两次**出现（第 k 次与第 k+1 次），
    若这两次之间夹着**任何一个别的编号的标题** ⇒ 后一次那条就落在别人体里了。

    ⚠️ **⛔ 不许写错成"本编号的所有出现两两之间"**（我第一版就犯了这个错）：
    `D-166` 的四次出现是 `6274 / 6325 / 6355 / 6377`，**两次之间**当然夹着别的编号
    （后面还有 `### D-167`），但**连续两次之间没有** ⇒ 它是**正常的相邻附注**，⛔ 不是归错档。
    ⇒ 判据必须看**连续两次**，⛔ 不是任意两次。
    """
    seen: dict[str, list[int]] = {}
    for pos, (_line_no, num, _rest) in enumerate(heads):
        seen.setdefault(num, []).append(pos)
    out: list[dict] = []
    for num, poss in seen.items():
        for a, b in zip(poss, poss[1:]):
            #: ⚠️ 归错档的是 `heads[b-1]`**那一条**（它写着自己的编号，却落在 `num` 的体里），
            #: ⛔ 不是 `num` 自己（`num` 只是**受害的父条目**）。我第一版把这两个搞反了。
            if any(heads[j][1] != num for j in range(a + 1, b)):
                out.append({"num": heads[b - 1][1], "line": heads[b - 1][0] + 1,
                            "parent": num})
    return sorted(out, key=lambda x: x["line"])


def scan_refs() -> dict[str, Counter]:
    out: dict[str, Counter] = {}
    for label, base, exts, skip in SCAN_SOURCES:
        counter: Counter = Counter()
        if base.is_file():
            files = [base]
        elif base.is_dir():
            files = [p for p in base.rglob("*") if p.is_file() and p.suffix in exts]
        else:
            files = []
        for path in files:
            rel = path.relative_to(ROOT).as_posix()
            if rel in skip:
                continue
            try:
                body = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            for num in set(DNUM.findall(body)):
                counter[num] += 1
        out[label] = counter
    return out


def esc(cell: str) -> str:
    return cell.replace("|", "\\|").replace("\n", " ").strip()


def render(entries: list[dict], heading_total: int, refs: dict[str, Counter],
           levels: dict[str, int], stats: dict) -> str:
    declared = {e["num"] for e in entries}
    src = refs["src"]
    in_force = sum(1 for n in declared if src.get(n))
    never = sorted(n for n in declared if not any(refs[k].get(n) for k in ("src", "tools", "其它", "survey")))
    followups = sum(e["followups"] for e in entries)
    with_status = sum(1 for e in entries if e["status"] != "—")
    no_cite = sorted(e["num"] for e in entries if e["cites"] == 0)
    #: ⚠️ 级别读数**每次从文件算**、⛔ 不写死 —— 写死必然腐烂（曾写死成"`###` 与 `##` 两种"，而实际有三种）
    level_note = " ＋ ".join(
        f"`{'#' * lv} D-###` {levels.get(lv, 0)}"
        for lv in (2, 3, 4) if levels.get(lv)
    ) + " 三种标题" if len(levels) > 1 else "一种标题"

    lines: list[str] = []
    lines.append("# 决策索引（**生成物，禁手改**）")
    lines.append("")
    lines.append("> ⚠️ **本文件由脚本生成** —— 手改会被门禁判红。")
    lines.append("> 重新生成：`python3 tools/decisions-index.py --write`　·　校验：`python3 tools/decisions-index.py --check`")
    lines.append("> 门禁：`tools/check-decisions-index.sh`（挂 `tools/check-all.sh`）。")
    lines.append("")
    lines.append("## 为什么有这张表")
    lines.append("")
    lines.append("`AGENTS.md` 原文让每个会话「**先读 `AI_DECISIONS.md`**」，而它是")
    lines.append(f"**{len(read_text(DECISIONS).splitlines()):,} 行**、**{len(read_text(DECISIONS)):,} 字符** ⇒ 粗估 **0.6M–1.2M tokens**")
    lines.append("⇒ ⚠️ **读不进 512K 窗口** ⇒ **那条启动指令按字面不可满足**（台账 `O12` 实测）。")
    lines.append("而它又是**唯一**的规则出处：找不到前置裁定就会**重复立法**（活例 = `D-455` 漏引 `D-080`）。")
    lines.append("⇒ 所以：**先读这张索引找编号，再只读那一条正文**。")
    lines.append("")
    lines.append("## 怎么用（三步）")
    lines.append("")
    lines.append("1. 在这张表里按**关键词**找标题 ⇒ 拿到 `D-###`；")
    lines.append("2. 看 `状态` / `追加` / 引用数粗判它**还在不在生效**（`src` 有数 = 代码里指得到它）；")
    lines.append("3. 读正文：`grep -n -A 30 '^##\\{0,1\\}# D-0NN' docs/AI_DECISIONS.md`")
    lines.append("   （⚠️ 用 `grep` **不要用行号** —— 追加条目插在中间会挪行号，而 `check-ref-integrity` 只查超界、查不出错位）")
    lines.append("")
    lines.append("## 读数（**只从 `AI_DECISIONS.md` 算** ⇒ 不含易变列）")
    lines.append("")
    lines.append("| 量 | 值 |")
    lines.append("|---|---|")
    lines.append(f"| 决策**编号**数 | **{len(declared)}**（{level_note}，标题共 {heading_total} 个） |")
    lines.append(f"| 追加条目（`附注`/`修正`/`验收`…） | **{followups}** 个（占标题 {followups / heading_total * 100:.0f}%） |")
    lines.append(f"| 写了 `状态：` 的条目 | **{with_status} / {len(declared)}**（{with_status / len(declared) * 100:.0f}%）⚠️ 其余显示 `—` = **没写**，**不等于废弃** |")
    lines.append(f"| ⚠️ **一条别的决策都没引**（`引前 = 0`） | **{len(no_cite)}** 条（= 决策点 3「新落 `D` 应引前置裁定」**今天完全没有检查**的人口） |")
    lines.append("")
    lines.append("## 表（**门禁逐字节比对的就是这一段**：编号 / 标题 / 状态 / 追加 / 引前）")
    lines.append("")
    lines.append("| 编号 | 标题 | 状态 | 追加 | 引前 |")
    lines.append("|---|---|---|---:|---:|")
    for e in sorted(entries, key=lambda x: int(x["num"][2:])):
        lines.append(
            f"| **{e['num']}** | {esc(e['title'])} | {esc(e['status'])[:28]} | "
            f"{e['followups'] or ''} | {e['cites'] or ''} |"
        )
    lines.append("")
    lines.append("> **列义**：`追加` = 这条决策下面还挂了几条 `附注/修正/验收`（多 ⇒ 条目臃肿）·")
    lines.append("> `引前` = 这条决策**正文里引了几条别的决策**（⚠️ `0` = 它在孤立法）。")
    lines.append(">")
    lines.append(f"> ⚠️ **`引前` 只是一道筛子，不是判据**：它能拦「**一条别的决策都没引**」（今天 {len(no_cite)} 条），")
    lines.append("> 但**拦不住** `D-455` 那种情形 —— 它 `引前 = 4`，**照样漏引了 `D-080`**（`survey/43 §10.2`）。")
    lines.append("> ⇒ 想机械判定「有没有引到**该引的那条**」需要语义，做不到；这一列只把可疑面缩到人能看完的规模。")
    lines.append("")
    lines.append(APPENDIX_MARKER)
    lines.append("")
    lines.append("> ⚠️ **下面这张表不在门禁比对范围内**（`tools/check-decisions-index.sh` 只比到上面那张表为止）。")
    lines.append("> 原因：引用计数**易变** —— 任何 doc / src / tools 多提一次某个编号它就变，而台账与断点几乎每把刀都在引编号；")
    lines.append("> 若把它算进比对，**几乎每把刀都要跑一次 `--write`**，门禁会退化成「例行敲一下」。")
    lines.append("> ⇒ 它只是**顺手刷新**的热度参考；**要最新值就跑一次 `--write`**（或直接看 `src` 那一列的旧值当量级）。")
    lines.append("")
    lines.append("| 编号 | src | tools | 其它 |")
    lines.append("|---|---:|---:|---:|")
    for e in sorted(entries, key=lambda x: int(x["num"][2:])):
        n = e["num"]
        s, t = refs["src"].get(n, 0), refs["tools"].get(n, 0)
        o = refs["其它"].get(n, 0) + refs["survey"].get(n, 0)
        if s or t or o:
            lines.append(f"| **{n}** | {s or ''} | {t or ''} | {o or ''} |")
    lines.append("")
    lines.append(f"> ⚠️ **从未被任何地方引用**：**{len(never)}** 条"
                 f"（= 上表里连一行都没有的编号）—— {', '.join(never) if never else '无'}")
    lines.append("> ⚠️ **被 `src/` 引用**（≈**还在生效**）：**{}/{}**（{:.0f}%）".format(
        in_force, len(declared), in_force / len(declared) * 100))
    lines.append("")
    return "\n".join(lines) + "\n"


def build() -> tuple[str, dict, dict, list[str], list[str]]:
    text = read_text(DECISIONS)
    entries, heading_total, levels, misfiled = parse_decisions(text)
    stats = {"决策编号数": len(entries), "标题数": heading_total}
    for key, floor in ASSERTIONS.items():
        if stats[key] < floor:
            print(f"DECISIONS_INDEX_RESULT FAIL: 解析崩塌 —— {key}={stats[key]} < 下限 {floor}"
                  f"（少读一截不许悄悄过）", file=sys.stderr)
            raise SystemExit(1)
    refs = scan_refs()
    stats["状态条数"] = sum(1 for e in entries if e["status"] != "—")
    stats["状态取值"] = len({e["status"] for e in entries if e["status"] != "—"})
    stats["归错档"] = len(misfiled)
    #: C1 三条断言（⛔ 基线之外的**新病**才红）—— 判据在文件头部常量区，逐条带注释
    p_mis, w_mis = assert_misfiled(misfiled)
    #: ⚠️ C4（`生效` 必须带批准痕迹）**不在这里** —— 它是**独立门禁** `tools/check-effective-trace.py`
    #:（用户 2026-10-02 裁定：`C4` 属"**新增**门禁"🟢 档，而改**已有门禁**的判据属 🔴 ⇒ 必须拆出去）
    problems = p_mis + assert_missing(entries)
    warnings = assert_duplicates(parse_heads(text)) + w_mis
    return (render(entries, heading_total, refs, levels, stats), stats, levels,
            problems, warnings)


def parse_heads(text: str) -> list[tuple[int, str, str]]:
    """只取标题表（断言①用；`parse_decisions` 里那份是局部的，不想为它改签名）。"""
    return [(i, m.group(2), m.group(3).strip())
            for i, line in enumerate(text.splitlines()) if (m := HEADING.match(line))]


def main() -> int:
    ap = argparse.ArgumentParser(description="生成/校验 docs/DECISIONS_INDEX.md")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--write", action="store_true", help="写入 docs/DECISIONS_INDEX.md")
    g.add_argument("--check", action="store_true", help="校验是否陈旧（陈旧 ⇒ 非零退出）")
    args = ap.parse_args()

    rendered, stats, levels, problems, warnings = build()
    lvz = " · ".join(f"h{lv} {levels.get(lv, 0)}" for lv in (2, 3, 4) if levels.get(lv))
    stz = f"状态 {stats.get('状态条数', 0)} 条 / {stats.get('状态取值', 0)} 种"
    mfz = f"归错档 {stats.get('归错档', 0)} 条（基线 {len(BASELINE_MISFILED)}）"

    if args.write:
        OUTPUT.write_text(rendered, encoding="utf-8")
        print(f"DECISIONS_INDEX_RESULT WROTE: {stats['决策编号数']} 决策 / {stats['标题数']} 标题"
              f"（{lvz} · {stz} · {mfz}）→ {OUTPUT.relative_to(ROOT)}")
        for w in warnings:
            print(f"  {w}")
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        return 1 if problems else 0

    if not OUTPUT.exists():
        print(f"DECISIONS_INDEX_RESULT FAIL: {OUTPUT.relative_to(ROOT)} 不存在"
              f"（跑 `python3 tools/decisions-index.py --write`）", file=sys.stderr)
        return 1

    def gated(text: str) -> list[str] | None:
        """取 `APPENDIX_MARKER` **之前**的部分（= 门禁比对范围）。缺标记 ⇒ None（响亮失败）。"""
        if APPENDIX_MARKER not in text:
            return None
        return text.split(APPENDIX_MARKER, 1)[0].splitlines()

    on_disk = OUTPUT.read_text(encoding="utf-8")
    disk_gated, gen_gated = gated(on_disk), gated(rendered)
    if disk_gated is None:
        print(f"DECISIONS_INDEX_RESULT FAIL: {OUTPUT.relative_to(ROOT)} 缺 `{APPENDIX_MARKER}` 标记"
              f"（文件被手改过？）⇒ 跑 `--write` 重生", file=sys.stderr)
        return 1
    assert gen_gated is not None  # 生成器自己一定带标记
    if disk_gated != gen_gated:
        detail = "行数不同" if len(disk_gated) != len(gen_gated) else "行数相同、内容不同"
        first = next((i for i, (a, b) in enumerate(zip(disk_gated, gen_gated), 1) if a != b), None)
        print(f"DECISIONS_INDEX_RESULT FAIL: 索引**结构列**已陈旧（{detail}"
              + (f"，首个不同在第 {first} 行" if first else "") + "）"
              f" ⇒ 跑 `python3 tools/decisions-index.py --write` 并提交", file=sys.stderr)
        return 1
    for w in warnings:
        print(f"  {w}")
    if problems:
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        print(f"DECISIONS_INDEX_RESULT FAIL: {len(problems)} 处 C1 断言未过"
              f"（判据见 `tools/decisions-index.py` 头部常量区；⛔ 基线白名单只许变短）", file=sys.stderr)
        return 1
    print(f"DECISIONS_INDEX_RESULT PASS: {stats['决策编号数']} 决策 / {stats['标题数']} 标题（{lvz} · {stz} · {mfz}）"
          f" / 结构列与 {OUTPUT.relative_to(ROOT)} 逐字节相同"
          f" / C1 三断言过（①重复标题=警告 · ②归错档=红 · ③缺号=红）"
          f"（⚠️ C4 `生效` 痕迹 = **独立门禁** `check-effective-trace`）"
          f"（⚠️ 附录「引用热度」**不在**比对范围）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
