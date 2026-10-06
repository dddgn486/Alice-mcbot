#!/usr/bin/env python3
"""提取漏斗 · **落地器** —— ⭐ **先量后写**（2026-10-05 开发者裁「**超 45 KB 响亮失败**」）。

## ⭐ 为什么有它（出处 = 本项目的两次真实损失，⛔ 不是"洁癖"）

1. **撞 `maxInlineBytes` 被截断**（2026-10-05）：主工作流要了一批**件级**回吐，实际拿到
   **≈75 KB** ⇒ 撞上 harness 的 **`50000` 字节**上限 ⇒ 只回来 **head/tail ＋ 一行通知**
   （原文 `(Omitted <bytes> bytes. Full formatted result stored at: <locator>. <retrievalHint>)`）
   ⇒ ⚠️ **若不响亮失败，主工作流会把半份当成全份**。⭐ 那次是靠**外部**截断＋JSON 解析报错救的场
   —— ⛔ **不能指望它**（⭐ 「靠运气救回来的」不是纪律）。
2. **466 MB 事故**（2026-10-05）：脚本改台账时 `old` 切片算成**空串**、又**漏写唯一性断言**
   ⇒ `s.replace("", new)` 每字符间插 700 字 ⇒ `OPEN_ITEMS_LEDGER.md` **1.1 MB → 466 MB**。
   ⇒ ⭐ 本脚本**只做锚点定位 ＋ 切片拼接**，⛔ **一次 `str.replace` 都不用**。

## 它管什么（机械判据 · ⛔ 全是"能让它失败"的，不是散文）

| # | 判据 | 为什么是这一条 |
|---|---|---|
| 1 | 回吐体 **≤ `MAX_BYTES` = 45000 字节**（UTF-8 **字节**，⛔ 不是字符） | 离 `50000` 留 5 KB 余量；⭐ **自报体积** |
| 2 | 回吐体必须是**合法 JSON** | 被截断的体症状就是**断在中间** ⇒ 解析当场炸 |
| 3 | 每件的 `path` 必须在**靶子集**内（现算） | ⛔ 防把范围外的件写进册子 |
| 4 | 现算靶子数必须 **== `TARGET1`**（⭐ 从 `check-extraction-status.py` **导入**，⛔ 不另写一个数） | ⛔ **同一个量不许两个数** |
| 5 | `sections` **≤ 3**（⭐ 2026-10-05 开发者裁「`sections` 降到 3 个」） | 体积：首件实测该项占 ~31%。⚠️⭐ **只属于第 1 遍** —— 节级那一遍**没有这个上限**（见下方 §"两套形状"） |
| 6 | 摘要 **⛔ 不截断**，`> 100` 字**只追加标记**（⭐ 开发者逐字「**超长摘要改由我截断并标记**」） | ⛔ 脚本不许丢内容；⚠️ 断点八十七 §E 曾写反成「由脚本硬截断」 |
| 7 | 已在册的**本遍条目** ⛔ 不许再写（**本遍内**只许追加、⛔ 不覆盖） | 对齐门禁判据④「每一遍只许变长」。⭐⭐ **作用域 = 本遍**（⛔ 不是全册，见下方"两套形状"） |
| 8 | 第 1 遍：本批写完后**累计 ≤ `TARGET1`**；第 2 遍及以后：**件必须来自上一遍** | ⛔ 不许把这批写成让门禁变红的形状（判据③／判据②a） |
| 9 | 只许追加到**指定遍节**尾部，且遍标题**恰好一个**、插入点**由断言保证** | ⭐ 上面第 2 个事故的正解 |
| 10 | `read_ok` 必填且必须是 `true`（⭐ **读失败 ≠ 无价值**） | 2026-10-06 批 2 事故：17 件读失败被回吐成「判：无」 |

## ⭐⭐ 两套形状（2026-10-06 立 · 出处 = 开发者裁「**一节一条**」）

| 遍 | 一条 = | 条目标题 | 回吐体字段 |
|---|---|---|---|
| **第 1 遍**（件级） | **一件** | `` #### `<件>` `` | `path`／`read_ok`／`has_value`／`kind`／`sections`（**字符串** ≤ 3）／`types`／`ruling`／`summary` |
| **第 2 遍及以后**（节级） | **一节** | `` #### `<件>` › <节标题> `` | `path`／`read_ok`／`sections`（**对象**：`title`／`anchor`／`summary`／`type`／`why_keep`）／`ruling`／`summary`（整件筛掉时的**理由**）／`drop_reason`（`过期`／`无效`） |

⚠️⭐ **为什么判据⑦/⑧ 必须按遍分开**（2026-10-06 当场复算出来的硬阻塞）：第 2 遍处理的是
**同一批 188 件** ⇒ 若判据⑦ 的 `already` 还是**全册**的，第 2 遍**一条都落不进去**，
而判据⑧ 的累计 `188 + n` **必 > 188** ⇒ ⭐ 病根一处：**把「全册」当成了「本遍」**。
⚠️⭐ **为什么节级那一遍 ⛔ 不设 `sections` 上限**：那条 3 个的裁是 **2026-10-05 针对第 1 遍**下的
（口径是"**在哪几节**"，⭐ 只作**线索**）；节级那一遍的**任务本身**就是"**把有价值的节提全**"
⇒ 套 3 个上限＝**按配额丢内容**（⭐ 与判据⑥「⛔ 不许丢内容」同一条纪律）。
⚠️ 它**不是**"免检"：本遍唯一的硬约束仍是**回吐体 ≤ 45000 字节**（判据①）⇒ 装不下就**分批**。


## ⛔ 它判不了（诚实边界 · ⛔ 不许读成"已验"）

1. ⛔ **判不了「提得对不对」** —— 它只管**形状、范围、体积**；
2. ⛔ **判不了「这段是不是裁定」** —— 那只有开发者能判；
3. ⛔ **判不了「开发者看过没有」** —— ⭐ 假装能判就是**伪造完成感**；
4. ⚠️ **判不了「子代理真的读了那个件」** —— ⭐ 它只保证**回吐的 `path` 在靶子集内**；
   ⇒ ⭐ 正解是**件路径由主工作流注入**（见 `W7′-8` 约束组），⛔ 不是靠本脚本。

## 用法

    python3 tools/ingest-extraction.py --pass 1 --input /tmp/batch2.json          # 只量不写（默认）
    python3 tools/ingest-extraction.py --pass 1 --input /tmp/batch2.json --write  # 量完再写
    python3 tools/ingest-extraction.py --pass 1 --input - --write                 # 从 stdin 读
    python3 tools/ingest-extraction.py --selftest                                 # 注入自证（12 臂）

⚠️ 默认**只量不写** —— ⭐ 与 `plan-doc-refactor.py` / `cleanup-classify.py` 的 `--write` 惯例一致：
**量出来给人看过**再决定写不写。
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: 默认落点 = 提取册
LEDGER = ROOT / "docs" / "EXTRACTED_KNOWLEDGE.md"

#: ⭐⭐ **体积上限**（UTF-8 字节）—— 2026-10-05 开发者裁。
#:    ⚠️ harness 的 `maxInlineBytes` 是 `50000` ⇒ 这里留 5 KB 余量。
MAX_BYTES = 45000

#: ⭐ 每件 `sections` 上限（2026-10-05 开发者裁「`sections` 降到 3 个」）
SECTIONS_MAX = 3

#: ⭐ 摘要字数软阈值 —— **超了只标记，⛔ 不截断**（开发者逐字「超长摘要改由我截断并标记」）
SUMMARY_SOFT = 100

#: ⭐ 新一代标记（2026-10-05 起）—— ⚠️ 与首批那 15 个**旧标记**（`已截断` / `truncated: true`）
#:    措辞**不同**是**故意的**：旧标记的口径**不可复算**（见 `HANDOVER.md` 断点八十七 §E 的更正），
#:    ⛔ 不许把两者混成一条。⭐ 两代标记各自的含义写在册子 §六。
OVER_MARK = "　⚠️ **摘要超 100 字**（`over_100: true` · ⛔ **未截断**，待开发者处理）"

#: 判「有／无」时允许的档
KINDS = ("设计", "判据", "经验教训", "混合")
#: `类型` 字段允许的值
TYPES = ("设计", "判据", "经验教训")

#: ⭐⭐ **第 2 遍及以后**：整件被筛掉时允许的原因（⭐ = 本遍的**收窄判据**，册子 `§三.1`）。
#:    ⚠️ ⛔ **不含「已落地」** —— 那属**第 4 遍**（回执 `004` 的 `M5` 逐字澄清）。
DROP_REASONS = ("过期", "无效")
#: ⭐ 整件被筛掉时渲染出的**节标题** —— ⭐ 让它也是一条**节级**条目：一遍只许一种形状，
#:    ⛔ 不许混进一条件级的（那会让门禁判据②**判不动** ⇒ 红）。
DROP_TITLE = "⛔ 整件筛掉"

RESULT = "INGEST_EXTRACTION_RESULT"


# ---------------------------------------------------------------- 靶子集


def _ces():
    """⭐ 导入门禁模块 —— `TARGET1` 的**唯一真相**在那儿（⛔ 本脚本不另写一个 188）。"""
    p = ROOT / "tools" / "check-extraction-status.py"
    spec = importlib.util.spec_from_file_location("_ces_target", p)
    if spec is None or spec.loader is None:                      # pragma: no cover
        raise SystemExit(f"{RESULT} FAIL: 导不到 {p}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


#: ⭐⭐ **本次改革的「落点件」白名单**（出处 = 咨询回执 `004` 的 `M7` ＋ 开发者令）：
#:    这些件**新建／改名自本次改革**，⛔ **不是存量旧件** ⇒ 从靶子里排除
#:    （⛔ 从册子里再提取＝循环；⚠️ 开发者逐字：「这两个文档没理由当成靶子，应该直接排除」）。
DEST_WHITELIST = (
    "docs/EXTRACTED_KNOWLEDGE.md",      # 提取册自己
    "docs/LESSONS_LEARNED.md",          # 经验类落点（将来可能建）
    "docs/CRITERIA_LIBRARY.md",         # 判据类落点（将来可能建）
    "docs/SCHEDULE.md",                 # 回执 004.1 待办 A：从台账拆出的排期表
    "docs/ALICE_CAPABILITIES.md",       # 回执 004.1 待办 C：能力清单瘦身后的新名
)

#: ⭐⭐⭐ **靶子的冻结清单**（2026-10-06 开发者令：「**靶子 = 提取开始时那 188 件**，
#:    ⛔ **不随改名／搬迁变动**」）⇒ ⭐ 口径从**扫磁盘**改成**读这份 manifest**。
TARGETS_FILE = ROOT / "tools" / "extraction-targets-188.txt"


def target_set() -> list[str]:
    """⭐⭐ **靶子集 = 冻结 manifest**（⛔ **不再扫磁盘**）。

    ⚠️ **为什么改**（2026-10-06 开发者令）：回执 `004.1` 的待办 A/C/D 会**新建／改名／搬走**
    `docs/` 根的件 ⇒ 若口径仍是"扫磁盘"，**每动一件靶子数就变** ⇒ ⛔ `ingest-extraction`
    判据④ 当场响亮失败，⛔ 而且册子里已落的那 188 条会**逐条悬空**。
    ⇒ ⭐ **靶子 = 提取开始时的那 188 件**，⛔ **不随改名／搬迁变动**；
    ⭐ 改名／搬迁 ⇒ **用 `docs/` 与册子两侧的指针去追**（⛔ 不动这份清单）。

    ⭐ **单一出处**：清单由 `--write-targets` 从磁盘**一次性**生成（生成时排除 `DEST_WHITELIST`）；
    ⚠️ 之后**只有用户裁定**能改它。
    """
    if not TARGETS_FILE.exists():
        raise SystemExit(f"{RESULT} FAIL: 缺靶子清单 {TARGETS_FILE.relative_to(ROOT)} "
                         f"⇒ 跑 `python3 tools/ingest-extraction.py --write-targets`")
    out = [l.strip() for l in TARGETS_FILE.read_text(encoding="utf-8").split("\n")
           if l.strip() and not l.startswith("#")]
    return out


def live_targets() -> list[str]:
    """⚠️ **现算**（扫磁盘）—— ⛔ **只用于对账报告**，⛔ 不再作为写盘判据。

    口径 = `plan-doc-refactor.py` 的 `LIVE` 表那三条命令：
      · `docs/` 根 `.md`（⛔ 不含子目录）｜ `docs/reviews/` **顶层**（⭐ 含本目录 `README.md`，
        ⛔ 不含 `docs/reviews/archive/`）｜ `survey/` **全部** `.md`
      · ⛔ 再减去 `DEST_WHITELIST`（本次改革的落点件）
    """
    docs = sorted((ROOT / "docs").glob("*.md"))
    rev = sorted((ROOT / "docs" / "reviews").glob("*.md"))        # ⛔ 顶层，不是 **/
    sur = sorted((ROOT / "survey").glob("*.md"))
    wl = {w for w in DEST_WHITELIST}
    paths = [q for q in docs + rev + sur if q.relative_to(ROOT).as_posix() not in wl]
    return [q.relative_to(ROOT).as_posix() for q in paths]


def write_targets() -> int:
    """⭐ 一次性生成冻结清单（⛔ 只该在口径刚立时跑；跑完由用户裁定才许再跑）。"""
    live = live_targets()
    TARGETS_FILE.write_text(
        "# ⭐⭐ 提取靶子的【冻结清单】—— 出处 = 2026-10-06 开发者令\n"
        "#    「靶子 = 提取开始时那 188 件，⛔ 不随改名／搬迁变动」\n"
        "# ⛔ 本清单**不是**从磁盘现算的活读数 ⇒ 改名／搬迁**不**改它（⭐ 用指针去追）\n"
        "# ⭐ 生成 = `python3 tools/ingest-extraction.py --write-targets`（⚠️ 之后只有用户裁定能改）\n"
        + "\n".join(live) + "\n", encoding="utf-8")
    print(f"{RESULT} 已写靶子清单 {TARGETS_FILE.relative_to(ROOT)}：**{len(live)}** 件")
    return 0


# ---------------------------------------------------------------- 校验 / 渲染


def _as_list(v, what: str, errs: list[str], where: str) -> list[str]:
    if v is None:
        return []
    if not isinstance(v, list):
        errs.append(f"{where}：`{what}` 必须是数组（拿到 {type(v).__name__}）")
        return []
    out = []
    for x in v:
        if not isinstance(x, str) or not x.strip():
            errs.append(f"{where}：`{what}` 里有空项／非字符串项")
            continue
        out.append(x.strip())
    return out


def pass_slice(text: str, pass_no: int) -> str:
    """⭐ `### 第 N 遍 …` 标题到**下一节标题**之间的正文 —— **判据⑦/⑧ 的作用域 = 本遍**。

    ⚠️⭐ **为什么必须有它**（2026-10-06 当场复算出来的硬阻塞）：原判据⑦ 的 `already` 是
    **全册**的 `` #### `<件>` ``（⛔ 不分遍），而第 2 遍处理的是**同一批 188 件**
    ⇒ ⭐ 第 2 遍**一条都落不进去**（每条都被判「已在册」），而判据⑧ 的累计 `188 + n`
    **必 > 188** ⇒ 两个红一起响。
    ⇒ ⭐ 病根只有一处：**把「全册」当成了「本遍」** —— ⚠️ 而且**报错会指错方向**
    （读起来像"我重复提交了"，其实是判据的作用域错了）。
    """
    ms = list(re.finditer(rf"^###\s*第\s*{pass_no}\s*遍[^\n]*$", text, re.M))
    if len(ms) != 1:
        return ""
    tail = text[ms[0].end():]
    nxt = re.search(r"^#{1,3}\s", tail, re.M)
    return tail[:nxt.start()] if nxt else tail


def pass_items(text: str, pass_no: int) -> list[tuple[str, str | None]]:
    """→ 那一遍的 `(件路径, 节标题|None)` 列表（⭐ 顺序 = 册子里的顺序）。

    ⭐ **形状的正则来自门禁**（`check-extraction-status.ITEM_TITLE_RE`）—— ⛔ 本脚本**不另抄一份**：
    抄一份就迟早会漂，而「同一个形状两份定义」正是「同一个量两个数」的变体。
    """
    r = _ces().ITEM_TITLE_RE
    out: list[tuple[str, str | None]] = []
    for l in pass_slice(text, pass_no).splitlines():
        m = r.match(l)
        if m:
            out.append((m.group(1), m.group(2)))
    return out


def _mark(summary: str, stats: dict) -> str:
    """⭐ 判据 ⑥：摘要 **⛔ 不截断**，`> 100` 字**只追加标记**（⭐ 截断由开发者自己做）。"""
    if len(summary) > SUMMARY_SOFT:
        stats["over"] += 1
        return summary + OVER_MARK
    return summary


def validate(items, ledger_text: str, targets: set[str], *, pass_no: int) -> tuple[list[str], str, dict]:
    """纯函数：返回 (错误列表, 渲染块, 读数)。⛔ **不碰磁盘** —— 臂才跑得动。

    ⭐⭐ **两套形状**（⭐ 单一出处 = 册子 `§六`；标题正则 = 门禁的 `ITEM_TITLE_RE`）：

      · **第 1 遍（件级）**：一条 = **一件** ⇒ 标题 `` #### `<件>` ``（回吐体照旧）
      · **第 2 遍及以后（节级）**：一条 = **一节** ⇒ 标题 `` #### `<件>` › <节标题> ``
        —— ⭐ 开发者 2026-10-06 裁「**一节一条**」：一次子代理调用仍只读**一件**
        （⛔ 不许一件多次调用），但**渲染成多条**；回吐体照回执 `004` 的 `M2` 字段表
        （`sections[].title / anchor / summary / type / why_keep`）。
        ⭐ **整件被筛掉**（本遍的收窄判据 = **过期／无效**）⇒ 给 `drop_reason`，
        ⛔ 不给 `sections` ⇒ 渲染成一条「**⛔ 整件筛掉（原因）**」（⭐ ⛔ 不许静默丢掉）。
    """
    errs: list[str] = []
    ces = _ces()
    #: ⭐⭐ **判据⑦ 的作用域 = 本遍**（⛔ 不是全册）—— 见 `pass_slice` 的注释。
    already = set(pass_items(ledger_text, pass_no))
    #: ⭐ **判据⑧′**：第 2 遍及以后的件必须来自**上一遍**（漏斗不许添新件）。
    prev_files = {p for p, _ in pass_items(ledger_text, pass_no - 1)} if pass_no >= 2 else set()
    #: ⭐ **判据⑧**：只对**第 1 遍**比 `TARGET1`（⭐ 那是**件级**靶子 —— 别的遍单位不同）。
    p1_here = len(pass_items(ledger_text, 1)) if pass_no == 1 else 0
    seen: set[tuple[str, str | None]] = set()
    block: list[str] = []
    stats = {"n": 0, "over": 0, "bytes": 0, "chars": 0, "capped": 0, "dropped": 0}
    #: ⭐ 判据⑤（`sections` ≤ 3）是 **2026-10-05 针对第 1 遍**裁的 ⇒ ⛔ 不套到节级那一遍上
    #:   （节级本来就要**把有价值的节提全**，套 3 个上限＝**按配额丢内容**）。
    secs_max = SECTIONS_MAX if pass_no == 1 else None

    if not isinstance(items, list):
        return ([f"顶层 `items` 必须是数组（拿到 {type(items).__name__}）"], "", stats)

    for k, rec in enumerate(items):
        where = f"第 {k + 1} 件"
        if not isinstance(rec, dict):
            errs.append(f"{where}：不是对象（拿到 {type(rec).__name__}）")
            continue
        path = rec.get("path")
        if not isinstance(path, str) or not path.strip():
            errs.append(f"{where}：`path` 缺失／空 ⇒ ⭐ 件路径**由主工作流注入**，⛔ 不许缺")
            continue
        path = path.strip()
        where = f"第 {k + 1} 件（`{path}`）"

        # 判据 ③：必须在靶子集内
        if path not in targets:
            hint = ""
            if path == "docs/EXTRACTED_KNOWLEDGE.md":
                hint = "（⭐ 这是本册自己 —— 从册子里再提取＝循环）"
            elif path.startswith("docs/reviews/archive/"):
                hint = "（⚠️ `docs/reviews/archive/` ⛔ 不在靶子集 —— reviews 口径是**顶层**）"
            errs.append(f"{where}：⛔ **不在靶子集**{hint}")

        # ⭐⭐ 判据 ⑩：**读没读到** ≠ **有没有价值** —— 2026-10-06 批 2 当场撞出来的。
        #   ⚠️ 事故形状：子代理的工作目录是 `/home/vscode`，而注入的是**仓库相对路径**
        #   ⇒ **26/55 件根本没读到**，其中 **17 件回吐成 `has_value: false`＋摘要写「读取失败」**
        #   ⇒ ⛔ 若照单落盘，册子里会出现 **17 条「判：无」的假条目**（＝把**读失败**当**无价值**）。
        #   ⭐ 修法**不是**加一句散文，是**加一个必填字段**：子代理必须回吐 `read_ok`；
        #   `read_ok is not True` ⇒ **响亮失败**，⛔ 不许写。⭐ 两遍都必填。
        if rec.get("read_ok") is not True:
            got = rec.get("read_ok", "<缺字段>")
            errs.append(f"{where}：⛔ `read_ok` 不是 `true`（拿到 {got!r}）⇒ "
                        f"⭐ **没读到件** ⇒ ⛔ **不许**写成「判：无」（⭐ 读失败 ≠ 无价值）")

        # ⭐ 判据 ⑧′：第 2 遍及以后 —— 件必须来自**上一遍**（⭐ 漏斗不许添新件）
        if pass_no >= 2 and path not in prev_files:
            errs.append(f"{where}：⛔ **不属于第 {pass_no - 1} 遍的件** ⇒ ⭐ 漏斗只许在"
                        f"上一遍的件里收窄，⛔ 不许添新件（⭐ 靶子**冻结**，⛔ 不追加）")

        ruling = rec.get("ruling")
        if ruling is not None and (not isinstance(ruling, str) or not ruling.strip()):
            ruling = None
        summary = rec.get("summary")
        if isinstance(summary, str) and summary.strip():
            summary = summary.strip()
        else:
            summary = None

        keys: list[tuple[str, str | None]] = []

        if pass_no == 1:
            # ---------------------------------------------------- 第 1 遍 · 件级
            has = rec.get("has_value")
            if not isinstance(has, bool):
                errs.append(f"{where}：`has_value` 必须是**布尔**（拿到 {has!r}）")
                has = None
            kind = rec.get("kind")
            sections = _as_list(rec.get("sections"), "sections", errs, where)
            types = _as_list(rec.get("types"), "types", errs, where)

            if summary is None:
                errs.append(f"{where}：`summary` 缺失／空 ⇒ ⭐ 判「无」也必须给**理由**")
            # 判据 ⑤：sections ≤ 3（⭐ 2026-10-05 开发者裁「`sections` 降到 3 个」）
            if len(sections) > SECTIONS_MAX:
                errs.append(f"{where}：⛔ `sections` **{len(sections)} 个 > {SECTIONS_MAX} 个**"
                            f"（2026-10-05 开发者裁「`sections` 降到 3 个」）")
            if has is True:
                if kind not in KINDS:
                    errs.append(f"{where}：判「有」时 `kind` 必须是 {'／'.join(KINDS)} 之一（拿到 {kind!r}）")
                if not sections:
                    errs.append(f"{where}：判「有」时 `sections` ⛔ 不许空")
                if not types:
                    errs.append(f"{where}：判「有」时 `types` ⛔ 不许空")
                for t in types:
                    if t not in TYPES:
                        errs.append(f"{where}：`types` 里有不认识的值 {t!r}（允许 {'／'.join(TYPES)}）")
            elif has is False:
                for nm, v in (("kind", kind), ("sections", sections), ("types", types)):
                    if v:
                        errs.append(f"{where}：判「无」时 `{nm}` 必须为空（⭐ ⛔ 不许编内容）")

            if summary is None or path not in targets:
                continue                                   # ⛔ 有硬错就不渲染，免得写出半截
            summary = _mark(summary, stats)
            keys = [(path, None)]
            stats["n"] += 1
            stats["chars"] += len(summary)
            block.append("\n".join([
                f"#### `{path}`",
                (f"- **判**：**有**价值内容（{kind}）" if has else "- **判**：**无**"),
                f"- **在哪几节**：{' ／ '.join(sections) if sections else '（无）'}",
                f"- **类型**：{'·'.join(types) if types else '（无）'}",
                f"- **疑似裁定**：{ruling or '（无）'}",
                f"- **摘要**：{summary}",
                "",
            ]) + "\n")
        else:
            # -------------------------------------------- 第 2 遍及以后 · 节级（一节一条）
            drop = rec.get("drop_reason")
            secs_raw = rec.get("sections")
            if drop is not None:
                # ⭐ **整件被筛掉**（本遍的收窄判据 = 过期／无效）
                if drop not in DROP_REASONS:
                    errs.append(f"{where}：⛔ `drop_reason` 只能是 {'／'.join(DROP_REASONS)}"
                                f"（拿到 {drop!r}）⇒ ⭐ 本遍的收窄判据只有这两条（册子 `§三.1`）")
                if secs_raw:
                    errs.append(f"{where}：⛔ **整件被筛掉**时 ⛔ 不许再给 `sections`"
                                f"（⭐ 筛掉就是筛掉，⛔ 不许又留一半）")
                if summary is None:
                    errs.append(f"{where}：⛔ **整件筛掉必须给理由**（`summary` 空）⇒ "
                                f"⭐ 统计报告要按原因分布（册子 `§三.2`）")
                if summary is None or path not in targets:
                    continue
                s = _mark(summary, stats)
                keys = [(path, DROP_TITLE)]
                stats["n"] += 1
                stats["chars"] += len(s)
                stats["dropped"] += 1
                block.append("\n".join([
                    f"#### `{path}` › {DROP_TITLE}",
                    f"- **判**：**筛掉**（{drop}）",
                    f"- **摘要**：{s}",
                    "",
                ]) + "\n")
            else:
                # ⭐ 本遍的**正形状**：一条 = 一节
                if not isinstance(secs_raw, list) or not secs_raw:
                    errs.append(f"{where}：⛔ 第 {pass_no} 遍**一条 = 一节** ⇒ `sections` 必须是"
                                f"**非空数组**（⭐ 整件被筛掉才用 `drop_reason`）")
                    secs_raw = []
                if secs_max is not None and len(secs_raw) > secs_max:
                    errs.append(f"{where}：⛔ `sections` **{len(secs_raw)} 个 > {secs_max} 个**")
                if path not in targets:
                    continue
                for j, sec in enumerate(secs_raw):
                    sw = f"{where} 第 {j + 1} 节"
                    bad: list[str] = []
                    if not isinstance(sec, dict):
                        errs.append(f"{sw}：不是对象（拿到 {type(sec).__name__}）")
                        continue
                    title = sec.get("title")
                    anchor = sec.get("anchor")
                    ssum = sec.get("summary")
                    stype = sec.get("type")
                    why = sec.get("why_keep")
                    if not isinstance(title, str) or not title.strip():
                        errs.append(f"{sw}：`title` 缺失／空 ⇒ ⭐ 标题＝条目标题的后半截，⛔ 不许空")
                        continue
                    title = title.strip()
                    if not isinstance(anchor, str) or not anchor.strip():
                        bad.append("`anchor` 缺失／空 ⇒ ⭐ 落盘一律用**描点**，⛔ 不写行号")
                    if not isinstance(ssum, str) or not ssum.strip():
                        bad.append("`summary` 缺失／空 ⇒ ⭐ 每节都要 2–3 句")
                    if stype not in TYPES:
                        bad.append(f"`type` 必须是 {'／'.join(TYPES)} 之一（拿到 {stype!r}）")
                    if not isinstance(why, str) or not why.strip():
                        bad.append("`why_keep` 缺失／空 ⇒ ⭐ **为什么留这节**是本遍的过滤理由，⛔ 不许省")
                    ssum = ssum.strip() if isinstance(ssum, str) else ""
                    for b in bad:
                        errs.append(f"{sw}（`{title}`）：{b}")
                    if bad:
                        continue                                    # ⛔ 有硬错的节不渲染
                    s = _mark(ssum, stats)
                    keys.append((path, title))
                    stats["n"] += 1
                    stats["chars"] += len(s)
                    block.append("\n".join([
                        f"#### `{path}` › {title}",
                        f"- **节**：{anchor.strip()}",
                        f"- **类型**：{stype}",
                        f"- **摘要**：{s}",
                        f"- **为什么留**：{why.strip()}",
                        f"- **疑似裁定**：{ruling or '（无）'}",
                        "",
                    ]) + "\n")

        # ⭐ 判据 ⑦（**本遍内**）：已在册 ／ 本批内重复
        for key in keys:
            label = f"`{key[0]}`" + (f" › `{key[1]}`" if key[1] else "")
            if key in already:
                errs.append(f"{where}：⛔ **已在册**（{label} —— 本遍只许**追加**，⛔ 不许覆盖）")
            if key in seen:
                errs.append(f"{where}：⛔ **本批内重复**（{label}）")
            seen.add(key)

    rendered = "".join(block)
    stats["bytes"] = len(rendered.encode("utf-8"))
    if pass_no == 1:                                           # 判据 ⑧（⭐ 只对第 1 遍）
        total = p1_here + stats["n"]
        _t1 = ces.TARGET1
        if total > _t1:
            errs.append(f"⛔ 本批写完后**第 1 遍累计** **{total}** 条 > `TARGET1` **{_t1}** "
                        f"⇒ 会让门禁判据③（第 1 遍 ≤ {_t1}）变红 ⇒ ⛔ 拒绝")
    return errs, rendered, stats


# ---------------------------------------------------------------- 落盘（⛔ 不用 replace）


def insert_pos(text: str, pass_no: int) -> int | None:
    """⭐ 返回**指定遍节尾**的插入点；遍标题缺失 ⇒ `None`（⛔ 不猜、⛔ 不新建）。"""
    ms = list(re.finditer(rf"^###\s*第\s*{pass_no}\s*遍[^\n]*$", text, re.M))
    if len(ms) != 1:
        return None
    tail = text[ms[0].end():]
    nxt = re.search(r"^#{1,3}\s", tail, re.M)
    return ms[0].end() + (nxt.start() if nxt else len(tail))


def apply_block(text: str, pos: int, block: str) -> str:
    """⭐ 切片拼接 —— ⛔ **一次 `str.replace` 都不用**（466 MB 事故的正解）。"""
    assert block, "⛔ 空块 ⇒ 会产生「每字符间插内容」那类事故，拒绝"
    assert 0 < pos < len(text), f"⛔ 插入点越界：{pos} ∉ (0, {len(text)})"
    out = text[:pos] + block + text[pos:]
    assert len(out) == len(text) + len(block), "⛔ 拼接后长度对不上 ⇒ 立即停"
    assert out.count(block) == text.count(block) + 1, "⛔ 块没插进去 or 插了多次"
    return out


# ---------------------------------------------------------------- main


def run(raw: bytes, ledger_text: str, pass_no: int) -> tuple[list[str], str, dict]:
    """⭐ **先量后写**的第一道：体积。⛔ 它必须在 JSON 解析**之前** —— 截断体的解析报错很吵，
    会把"其实是爆体积"这件事盖过去。"""
    if len(raw) > MAX_BYTES:
        return ([f"⛔ 回吐体 **{len(raw)} 字节 > 上限 {MAX_BYTES} 字节** ⇒ "
                 f"⭐ 极可能撞了 harness 的 `maxInlineBytes`（50000）被截断 ⇒ "
                 f"⛔ **拒绝按「全份」处理**（⭐ 这正是本脚本存在的理由）"], "", {})
    try:
        data = json.loads(raw.decode("utf-8"))
    except Exception as e:
        return ([f"⛔ 回吐体**不是合法 JSON**：{type(e).__name__}: {e} ⇒ "
                 f"⚠️ 若报错位置在体**尾部**，那就是**被截断**的典型症状"], "", {})
    items = data.get("items") if isinstance(data, dict) else data
    if isinstance(data, dict) and "items" not in data:
        return ([f"⛔ 对象的 key 里没有 `items`（拿到 {sorted(data)[:6]}）"], "", {})
    errs, block, stats = validate(items, ledger_text, set(target_set()), pass_no=pass_no)
    stats["raw"] = len(raw)
    return errs, block, stats


def main() -> int:
    ap = argparse.ArgumentParser(description="提取漏斗落地器（先量后写）")
    ap.add_argument("--pass", dest="pass_no", type=int, help="落到哪一遍（⭐ 必须是册子里已有的 `### 第 N 遍`）")
    ap.add_argument("--input", help="回吐体路径，或 `-` 读 stdin")
    ap.add_argument("--ledger", default=str(LEDGER), help="落点件（默认提取册）")
    ap.add_argument("--write", action="store_true", help="真的落盘（⛔ 默认只量不写）")
    ap.add_argument("--selftest", action="store_true", help="注入自证")
    ap.add_argument("--write-targets", action="store_true",
                    help="⭐ 一次性生成**靶子冻结清单**（⛔ 之后只有用户裁定能改）")
    a = ap.parse_args()

    if a.selftest:
        return selftest()
    if a.write_targets:
        return write_targets()
    if not a.pass_no or not a.input:
        ap.error("需要 --pass N 与 --input PATH|‑")

    ledger = Path(a.ledger)
    text = ledger.read_text(encoding="utf-8")
    raw = sys.stdin.buffer.read() if a.input == "-" else Path(a.input).read_bytes()

    errs, block, stats = run(raw, text, a.pass_no)
    targets = target_set()
    _t1 = _ces().TARGET1
    #: ⭐⭐ **已在册 = 本遍的条目数**（⛔ 不是全册 —— 第 2 遍处理的是**同一批 188 件**，
    #:    按全册数会把整批判成「已在册」⇒ 一条都落不进去）。
    here = pass_items(text, a.pass_no)
    already = len(here)
    unit = "节" if any(s for _, s in here) else "件"
    prev_here = {p for p, _ in pass_items(text, a.pass_no - 1)} if a.pass_no >= 2 else set()

    print(f"体积自报：回吐体 **{stats.get('raw', len(raw))}** 字节 / 上限 {MAX_BYTES} 字节")
    print(f"靶子清单：**{len(targets)}** 件（⭐ **冻结** · ⛔ 不随改名／搬迁变动） vs `TARGET1` **{_t1}**"
          + ("　✅ 一致" if len(targets) == _t1 else "　⛔ **对不上**"))
    print(f"落点：第 **{a.pass_no}** 遍 · 该遍单位 = **{unit}** · 已在册 **{already}** 条"
          + (f" · 上一遍件集 **{len(prev_here)}** 件" if a.pass_no >= 2 else ""))
    if len(targets) != _t1:
        errs.append(f"⛔ 靶子清单 {len(targets)} ≠ `TARGET1` {_t1} ⇒ 清单被动过，⛔ 先查口径再写")
    live = set(live_targets())
    frozen = set(targets)
    only_live, only_frozen = sorted(live - frozen), sorted(frozen - live)
    if only_live or only_frozen:
        print(f"⚠️ **现算 vs 冻结的差集**（⭐ **只报告，⛔ 不红** —— 改名／搬迁是正当的）："
              f"只现算 {len(only_live)} 件 · 只冻结 {len(only_frozen)} 件")
        for x in only_live[:5]:
            print(f"    ＋ 只现算：{x}")
        for x in only_frozen[:5]:
            print(f"    − 只冻结：{x}")
    if block:
        print(f"本批读数：**{stats['n']}** 条 · 渲染体 **{stats['bytes']}** 字节 "
              f"· 均值 **{stats['bytes'] // max(stats['n'], 1)}** 字节/条 · "
              f"摘要超 {SUMMARY_SOFT} 字 **{stats['over']}** 条（⭐ 只标记，⛔ 未截断）"
              + (f" · ⭐ **整件筛掉 {stats.get('dropped', 0)} 件**（收窄判据 = 过期／无效，"
                 f"⭐ 它们也是本遍的产出）" if a.pass_no >= 2 else ""))
        print(f"本遍读数：已在册 {already} ＋ 本批 {stats['n']} = **{already + stats['n']}** 条"
              + (f" / `TARGET1` {_t1}（⭐ 判据⑧ 只对第 1 遍）" if a.pass_no == 1
                 else f"（⭐ 本遍单位 = {unit} ⇒ ⛔ 与第 1 遍的**件**不是同一个量）"))
    if errs:
        print(f"{RESULT} FAIL")
        for e in errs:
            print(f"  [FAIL] {e}")
        return 1

    pos = insert_pos(text, a.pass_no)
    if pos is None:
        print(f"{RESULT} FAIL")
        print(f"  [FAIL] ⛔ 册子里 `### 第 {a.pass_no} 遍` 标题**不是恰好一个** ⇒ "
              f"⛔ 本脚本**不新建遍节**（新建=改结构，那是另一刀的事）")
        return 1

    if not a.write:
        print(f"{RESULT} OK（**只量未写** —— 加 `--write` 才落盘）· 插入点 = 第 {pos} 字符")
        return 0
    out = apply_block(text, pos, block)
    ledger.write_text(out, encoding="utf-8")
    print(f"{RESULT} OK（**已落盘**）· {ledger.relative_to(ROOT)} "
          f"{len(text.encode())} → {len(out.encode())} 字节")
    return 0


# ---------------------------------------------------------------- 注入自证


def _mk_ledger() -> str:
    return (
        "# 提取册（合成）\n\n## 六 · 漏斗\n\n### 第 1 遍 · 粗筛（件级）\n\n> 口径\n\n"
        "#### `docs/ACCEPTANCE_GUIDE.md`\n- **判**：**有**价值内容（混合）\n"
        "- **在哪几节**：A\n- **类型**：判据\n- **疑似裁定**：（无）\n- **摘要**：旧。\n\n"
        "### 第 2 遍 · 细筛（节级）\n\n> 空\n\n## 七 · 落地（⛔ 不是一遍）\n\n> 空\n"
    )


def _rec(path="docs/AI_PROJECT_STATE.md", **kw):
    r = {"path": path, "read_ok": True, "has_value": True, "kind": "混合", "sections": ["一"],
         "types": ["判据"], "ruling": None, "summary": "摘要。" * 1}
    r.update(kw)
    return r


def _sec(title="一 · 假的一节", **kw):
    s = {"title": title, "anchor": "§ 假描点", "summary": "节摘要。" * 1, "type": "判据",
         "why_keep": "因为它是判据。"}
    s.update(kw)
    return s


def _rec2(path="docs/ACCEPTANCE_GUIDE.md", **kw):
    """⭐ **节级**回吐体（第 2 遍及以后）—— 一条 = **一件**，但**渲染成一节一条**。

    ⚠️ 默认 `path` 挑的是**合成册子第 1 遍里已有的那件**（`docs/ACCEPTANCE_GUIDE.md`）
    —— ⭐ 因为第 2 遍的件必须来自上一遍（判据⑧′）。
    """
    r = {"path": path, "read_ok": True, "sections": [_sec()], "ruling": None}
    r.update(kw)
    return r


def _body(items):
    return json.dumps({"items": items}, ensure_ascii=False).encode("utf-8")


def selftest() -> int:
    L = _mk_ledger()
    T = set(target_set())
    ok = fail = 0

    def want(name, raw, *, red: bool, pass_no=1, ledger=L):
        nonlocal ok, fail
        errs, block, _ = run(raw if isinstance(raw, bytes) else _body(raw), ledger, pass_no)
        got = bool(errs)
        if got == red:
            ok += 1
            print(f"  [ok] {name} → {'红' if red else '绿'}")
        else:
            fail += 1
            print(f"  [⛔臂失效] {name} → 期望{'红' if red else '绿'}，实得{'红' if got else '绿'}：{errs[:2]}")

    # A 体积臂：⛔ 超上限必须红 —— ⚠️ 输入用**字面量 `45001`**，⛔ 不许写 `MAX_BYTES + 1`：
    #   ⭐ 退回验过一次 —— 写成 `MAX_BYTES + 1` 时，把常量调到 `10**9` 臂**照样绿**
    #   ⇒ 那是**恒真臂**（臂的输入跟着被测常量一起变）⇒ 已改字面量 ＋ 补 `A′` 边界臂。
    errs, _, _ = run(b"x" * 45001, L, 1)
    if errs and "字节 > 上限" in errs[0]:
        ok += 1
        print("  [ok] A 体 45001 字节 ⇒ 红（⭐ 报的是**体积**错，⛔ 不是 JSON 错）")
    else:
        fail += 1
        print(f"  [⛔臂失效] A 体积臂没报体积错：{errs[:1]}")
    # A′ 边界：**恰好** 45000 字节的**合法**体 ⇒ 绿（⭐ 判据是 `>`，⛔ 不是 `>=`）
    #   ⚠️ 填充量必须**补回被替换掉的 `摘要。` 那 9 字节** —— 退回验过：漏补时体是 **44991**
    #   ⇒ 那是**离边界 9 字节的假边界臂**（`>=` 那种错根本碰不到它）。
    base = _body([_rec()])
    pad = 45000 - len(base) + len("摘要。".encode("utf-8"))
    raw_a2 = _body([_rec(summary="丙" * (pad // 3) + "x" * (pad % 3))])
    errs2, _, _ = run(raw_a2, L, 1)
    if len(raw_a2) == 45000 and not errs2:
        ok += 1
        print("  [ok] A′ 体恰好 45000 字节 ⇒ 绿（边界：`>` 不是 `>=`）")
    else:
        fail += 1
        print(f"  [⛔臂失效] A′ 边界没守住：体 {len(raw_a2)} 字节 errs={errs2[:1]}")
    # B 非法 JSON（截断症状）
    want("B 体不是合法 JSON ⇒ 红", b'{"items": [{"path": "docs/', red=True)
    # P ⭐⭐ **读失败 ⇒ 红**（判据 ⑩）—— 本臂是**批 2 事故**的回归测试：
    #   ⚠️ 那次 17 件**根本没读到**，却被回吐成 `has_value: false` ＋ 摘要写「读取失败」
    #   ⇒ ⛔ 没有这条判据时，落地器会**照单写进 17 条假的「判：无」**。
    #   ⭐ 所以本臂分三种：`read_ok=false`／**缺字段**／`read_ok=true`（⭐ 第三种必须绿）。
    want("P `read_ok: false` ⇒ 红", [_rec(read_ok=False, has_value=False, kind="", sections=[], types=[])], red=True)
    _noro = _rec()
    _noro.pop("read_ok")
    want("P′ **缺 `read_ok`** ⇒ 红", [_noro], red=True)
    want("P″ `read_ok: true` ⇒ 绿", [_rec(read_ok=True)], red=False)
    want("P‴ `read_ok: true` ＋ 判「无」⇒ 绿（⭐ 真判无 vs 读失败，形状不同）",
         [_rec(read_ok=True, has_value=False, kind="", sections=[], types=[])], red=False)
    # C sections > 3 ⇒ 红（⭐ 新裁的判据，臂必须能退回验证）
    want("C sections 4 个 ⇒ 红", [_rec(sections=["a", "b", "c", "d"])], red=True)
    # C' 恰好 3 个 ⇒ 绿（⭐ 边界不许"多一格也不红"）
    want("C′ sections 恰好 3 个 ⇒ 绿", [_rec(sections=["a", "b", "c"])], red=False)
    # D 已在册 ⇒ 红
    want("D 件已在册 ⇒ 红", [_rec(path="docs/ACCEPTANCE_GUIDE.md")], red=True)
    # E 不在靶子集 ⇒ 红（含本册自己与 reviews/archive）
    want("E 不在靶子集 ⇒ 红", [_rec(path="docs/EXTRACTED_KNOWLEDGE.md")], red=True)
    want("E′ reviews/archive ⇒ 红", [_rec(path="docs/reviews/archive/zzz.md")], red=True)
    # F 本批内重复 ⇒ 红
    want("F 本批内重复 ⇒ 红", [_rec(), _rec()], red=True)
    # G 判「无」却带 sections ⇒ 红
    want("G 判「无」却带 sections ⇒ 红", [_rec(has_value=False, kind=None, sections=["一"], types=[])], red=True)
    # H 摘要空 ⇒ 红
    want("H 摘要空 ⇒ 红", [_rec(summary="  ")], red=True)
    # I 正常体 ⇒ 绿
    want("I 正常体 ⇒ 绿", [_rec()], red=False)

    # J ⭐ **超长摘要：⛔ 不截断、只标记**（开发者 2026-10-05 裁）—— 本臂**逐字验全文保留**
    long_s = "甲" * (SUMMARY_SOFT + 20)
    errs, block, st = run(_body([_rec(summary=long_s)]), L, 1)
    if not errs and long_s in block and "over_100: true" in block and "未截断" in block \
            and st["over"] == 1 and long_s + OVER_MARK in block:
        ok += 1
        print("  [ok] J 超长摘要 → 绿 + **全文保留** + 新标记（⛔ 未截断）")
    else:
        fail += 1
        print(f"  [⛔臂失效] J 超长摘要被截断或没标记：errs={errs[:1]} over={st.get('over')}")
    # J' 恰好 100 字 ⇒ 不标记（⭐ 边界）
    errs, block, st = run(_body([_rec(summary="乙" * SUMMARY_SOFT)]), L, 1)
    if not errs and st["over"] == 0 and "over_100" not in block:
        ok += 1
        print("  [ok] J′ 恰好 100 字 → 不标记")
    else:
        fail += 1
        print(f"  [⛔臂失效] J′ 边界没守住：over={st.get('over')}")

    # K ⭐ **渲染逐字**（防格式漂移）—— 与册子里已有 23 条的**字段顺序/标点**逐字对齐
    errs, block, _ = run(_body([_rec()]), L, 1)
    want_block = ("#### `docs/AI_PROJECT_STATE.md`\n- **判**：**有**价值内容（混合）\n"
                  "- **在哪几节**：一\n- **类型**：判据\n- **疑似裁定**：（无）\n"
                  "- **摘要**：摘要。\n\n")
    if block == want_block:
        ok += 1
        print("  [ok] K 渲染逐字（字段顺序 · 标点 · 空行）")
    else:
        fail += 1
        print(f"  [⛔臂失效] K 渲染漂了：\n期望 {want_block!r}\n实得 {block!r}")

    # L ⭐ **只落在指定遍内**：插入点必须在第 1 遍节尾、⛔ 不落进第 2 遍
    #   ⚠️ 退回验过一次：把 `insert_pos` 改成 `return len(text)` 时本臂**崩在 `apply_block` 的断言上**
    #   （崩＝退出码非 0 ⇒ 汇总门禁仍判红，⭐ 但**看不见是哪条臂**）⇒ 已加显式 `pos` 断言让**臂自己报**。
    errs, block, _ = run(_body([_rec()]), L, 1)
    pos = insert_pos(L, 1)
    p1, p2 = L.index("### 第 1 遍"), L.index("### 第 2 遍")
    # ⚠️ 边界是**闭的右边**：插入点**正好等于 `p2`** 才是对的（＝紧贴下一节标题之前）
    #   —— 退回验过：写成 `pos < p2` 时**正确实现也判失效**（`pos == p2 == 155`）。
    if pos is None or not (p1 < pos <= p2):
        fail += 1
        print(f"  [⛔臂失效] L 插入点不在第 1 遍节内：pos={pos}（应落在 ({p1}, {p2})）")
    else:
        out = apply_block(L, pos, block)
        if out.index("docs/AI_PROJECT_STATE.md") < out.index("### 第 2 遍"):
            ok += 1
            print("  [ok] L 新条目落在第 1 遍节内（⛔ 没插进第 2 遍）")
        else:
            fail += 1
            print("  [⛔臂失效] L 插入位置错了")
    # M 遍标题缺失 ⇒ **插入点 None**（⛔ 不新建遍节）
    #   ⚠️⭐ 本臂的**主语是插入点**，⛔ 不是 `run()` 的 errs —— 退回验过一次：2026-10-06
    #   加了判据⑧′（件必须来自上一遍）之后，`--pass 9` 会**因为另一个原因**红
    #   （没有第 8 遍 ⇒ 件集空）⇒ 原来那句 `not errs` 会让**本臂失效**（⭐ 它与插入点无关）。
    pos9 = insert_pos(L, 9)
    if pos9 is None:
        ok += 1
        print("  [ok] M `### 第 9 遍` 不存在 ⇒ 插入点 None（⛔ 不新建）")
    else:
        fail += 1
        print(f"  [⛔臂失效] M 缺失的遍节没被拦住：pos={pos9}")
    # M′ ⭐ **遍标题重复** ⇒ 插入点 None（⛔ 不猜哪一个）—— ⚠️ 本条是**退回验出来的漏洞**：
    #   合成册子原先只有一个「第 1 遍」⇒ 判据 `len(ms) != 1` 与 `len(ms) < 1` **无法区分**
    #   ⇒ 把判据改成 `< 1` 臂**照样绿**。⭐ 补一个**重复标题**的册子才照得出这条判据。
    L2 = L.replace("### 第 2 遍 · 细筛（节级）", "### 第 1 遍 · 粗筛（重复标题）", 1)
    if insert_pos(L2, 1) is None:
        ok += 1
        print("  [ok] M′ `### 第 1 遍` 出现两次 ⇒ 插入点 None（⛔ 不猜）")
    else:
        fail += 1
        print("  [⛔臂失效] M′ 重复遍标题没被拦住")

    # N 靶子对账：现算必须 == TARGET1（⛔ 一个量两个数）
    if len(T) == _ces().TARGET1:
        ok += 1
        print(f"  [ok] N 靶子现算 {len(T)} == `TARGET1` {_ces().TARGET1}")
    else:
        fail += 1
        print(f"  [⛔臂失效] N 靶子对不上：现算 {len(T)} vs TARGET1 {_ces().TARGET1}")
    # O 累计 > TARGET1 ⇒ 红（⭐ **填充件必须落在第 1 遍节内** —— ⚠️ 本臂 2026-10-06 改过：
    #   ⛔ 原先把 188 条**追加在册子末尾**（＝落在**落地节**里），而判据⑧ 的作用域已收窄到
    #   **第 1 遍** ⇒ 那样填**根本喂不到判据**（臂会失效成"恒绿"）。
    many = "\n\n".join(f"#### `docs/f{i}.md`\n- **判**：**无**\n- **在哪几节**：（无）\n"
                       f"- **类型**：（无）\n- **疑似裁定**：（无）\n- **摘要**：x。" for i in range(_ces().TARGET1))
    L_full = ("# 提取册（合成）\n\n## 六 · 漏斗\n\n### 第 1 遍 · 粗筛（件级）\n\n> 口径\n\n" + many +
              "\n\n### 第 2 遍 · 细筛（节级）\n\n> 空\n\n## 七 · 落地（⛔ 不是一遍）\n\n> 空\n")
    errs, _, _ = run(_body([_rec()]), L_full, 1)
    if errs:
        ok += 1
        print("  [ok] O 第 1 遍累计会超 `TARGET1` ⇒ 红")
    else:
        fail += 1
        print("  [⛔臂失效] O 超靶子没被拦住")

    # P ⭐⭐ **本刀的主臂**：靶子 = **冻结清单**（⛔ 不扫磁盘）⇒ 清单必须恰好 188 件
    frozen = target_set()
    if len(frozen) == _ces().TARGET1:
        ok += 1
        print(f"  [ok] P 靶子清单（冻结）{len(frozen)} 件 == TARGET1")
    else:
        fail += 1
        print(f"  [⛔臂失效] P 靶子清单 {len(frozen)} ≠ TARGET1 {_ces().TARGET1}")
    # Q ⭐ **白名单生效**：本次改革的落点件 ⛔ 不许出现在「现算」里
    live = live_targets()
    leaked = [w for w in DEST_WHITELIST if w in live]
    if not leaked:
        ok += 1
        print(f"  [ok] Q 白名单 {len(DEST_WHITELIST)} 条 ⇒ 现算里一条都没漏（现算 {len(live)} 件）")
    else:
        fail += 1
        print(f"  [⛔臂失效] Q 白名单泄漏：{leaked}")
    # R ⭐ **冻结清单里也不许有**白名单件（⭐ 两道都要）
    in_frozen = [w for w in DEST_WHITELIST if w in frozen]
    if not in_frozen:
        ok += 1
        print("  [ok] R 白名单件 ⛔ 不在冻结清单里")
    else:
        fail += 1
        print(f"  [⛔臂失效] R 冻结清单里有白名单件：{in_frozen}")

    #: ⭐⭐⭐ **本刀的正主**（2026-10-06）：⛔ **第 2 遍同一批件 ⇒ 不许再被判「已在册」**。
    #:   ⚠️ **退回验过**：改之前，同一条回吐体在 `--pass 2` 下**两处红**
    #:   （`已在册` ＋ `累计 189 > TARGET1 188`）⇒ 第 2 遍**一条都落不进去**。
    want("S ⭐ 第 2 遍 · 同一件 ⇒ ⛔ 不再误红（判据⑦ 作用域＝本遍）",
         [_rec2()], red=False, pass_no=2)
    #: S′ 反向：第 2 遍里**同一节**再来一次 ⇒ 红（⭐ 判据⑦ 仍然管得住"本遍内不覆盖"）
    #:   ⚠️⭐ 那条**已存在**的节条目必须落在**第 2 遍节内** —— 退回验过一次：
    #:   追加在册子**末尾**时它落在**落地节**里 ⇒ `already`（作用域＝第 2 遍）是空的
    #:   ⇒ 本臂**恒绿**（⭐ 那就是"验的是空气"）。
    L_p2 = _mk_ledger().replace(
        "### 第 2 遍 · 细筛（节级）\n\n> 空\n\n",
        "### 第 2 遍 · 细筛（节级）\n\n"
        "#### `docs/ACCEPTANCE_GUIDE.md` › 一 · 假的一节\n"
        "- **节**：§ x\n- **类型**：判据\n- **摘要**：y。\n"
        "- **为什么留**：z。\n- **疑似裁定**：（无）\n\n", 1)
    want("S′ 第 2 遍 · 同一节重复 ⇒ 红（判据⑦ 本遍内）",
         [_rec2(title="一 · 假的一节")], red=True, pass_no=2, ledger=L_p2)
    #: X ⭐ 第 2 遍的件**必须来自第 1 遍**（⭐ 漏斗不许添新件 = 判据⑧′）
    want("T 第 2 遍 · 件不在第 1 遍里 ⇒ 红（判据⑧′）",
         [_rec2(path="docs/AI_DEVELOPMENT_PLAYBOOK.md")], red=True, pass_no=2)
    #: Y ⭐ **一节一条**：一次回吐 3 节 ⇒ 渲染成 **3 条**（⛔ 不是 1 条）
    errs, block, st = run(_body([_rec2(sections=[_sec("甲"), _sec("乙"), _sec("丙")])]), L, 2)
    if not errs and block.count("#### ") == 3 and st["n"] == 3:
        ok += 1
        print("  [ok] U 3 节 ⇒ **渲染成 3 条**（一节一条）")
    else:
        fail += 1
        print(f"  [⛔臂失效] Y 一节一条没做到：条数={block.count('#### ')} n={st.get('n')} errs={errs[:1]}")
    #: Z ⭐ 节级那一遍 **⛔ 没有 `sections` ≤ 3 的上限**（那条裁只属于第 1 遍）
    #:   ⚠️ 与臂 C（第 1 遍 4 个 ⇒ 红）**配对** —— ⛔ 别把这条读成"判据⑤ 被删了"。
    want("V 第 2 遍 · 5 节 ⇒ 绿（⛔ 3 个上限只属于第 1 遍）",
         [_rec2(sections=[_sec(f"节 {i}") for i in range(5)])], red=False, pass_no=2)
    #: A′ 整件筛掉（收窄判据 = 过期／无效）⇒ 绿 ＋ 渲染成**节级**的一条（⛔ 不许静默丢掉）
    errs, block, st = run(_body([_rec2(sections=[], drop_reason="过期", summary="整份已被取代。")]), L, 2)
    if not errs and f"› {DROP_TITLE}" in block and "**筛掉**（过期）" in block and st["dropped"] == 1:
        ok += 1
        print("  [ok] W 整件筛掉（过期）⇒ 绿 ＋ 留痕（⛔ 不静默）")
    else:
        fail += 1
        print(f"  [⛔臂失效] A′ 整件筛掉没落对：{block!r} errs={errs[:1]}")
    #: B′ 筛掉的原因只许两条（⭐「已落地」属第 4 遍，⛔ 不在本遍）
    want("X 第 2 遍 · `drop_reason=已落地` ⇒ 红（⛔ 属第 4 遍）",
         [_rec2(sections=[], drop_reason="已落地", summary="x")], red=True, pass_no=2)
    #: C′ 筛掉却还带 sections ⇒ 红（⛔ 不许又留一半）
    want("Y 第 2 遍 · 筛掉却带 `sections` ⇒ 红",
         [_rec2(drop_reason="无效", summary="x")], red=True, pass_no=2)
    #: D′ 节级缺 `why_keep` ⇒ 红（⭐ 回执 `M2` 的字段表：过滤理由 ⛔ 不许省）
    _nosec = _sec()
    _nosec.pop("why_keep")
    want("Z 第 2 遍 · 节缺 `why_keep` ⇒ 红", [_rec2(sections=[_nosec])], red=True, pass_no=2)
    #: E′ 节级缺 `anchor` ⇒ 红（⭐ 落盘一律用描点）
    _noanchor = _sec()
    _noanchor.pop("anchor")
    want("AA 第 2 遍 · 节缺 `anchor` ⇒ 红", [_rec2(sections=[_noanchor])], red=True, pass_no=2)
    #: F′ 既无 `sections` 也无 `drop_reason` ⇒ 红（⭐ 空手回吐 = 静默丢件）
    want("AB 第 2 遍 · 空手回吐（无 sections 无 drop_reason）⇒ 红",
         [_rec2(sections=[])], red=True, pass_no=2)
    #: G′ ⭐ **渲染逐字**（节级形状，防漂）
    errs, block, _ = run(_body([_rec2()]), L, 2)
    want2 = ("#### `docs/ACCEPTANCE_GUIDE.md` › 一 · 假的一节\n"
             "- **节**：§ 假描点\n- **类型**：判据\n- **摘要**：节摘要。\n"
             "- **为什么留**：因为它是判据。\n- **疑似裁定**：（无）\n\n")
    if block == want2:
        ok += 1
        print("  [ok] AC 节级渲染逐字（字段顺序 · 标点 · 空行）")
    else:
        fail += 1
        print(f"  [⛔臂失效] G′ 渲染漂了：\n期望 {want2!r}\n实得 {block!r}")
    #: H′ ⭐ 节级超长摘要 ⇒ **只标记、⛔ 不截断**（⭐ 判据⑥ 对两遍都成立）
    long_sec = "甲" * (SUMMARY_SOFT + 5)
    errs, block, st = run(_body([_rec2(sections=[_sec(summary=long_sec)])]), L, 2)
    if not errs and long_sec + OVER_MARK in block and st["over"] == 1:
        ok += 1
        print("  [ok] AD 节级超长摘要 → 全文保留 ＋ 新标记")
    else:
        fail += 1
        print(f"  [⛔臂失效] H′ 节级超长摘要被截断或没标记：errs={errs[:1]}")

    print(f"INGEST_EXTRACTION_SELFTEST {'PASS' if fail == 0 else 'FAIL'}: 臂 {ok}/{ok + fail}")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
