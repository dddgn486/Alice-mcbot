#!/usr/bin/env python3
"""生成 / 校验 `docs/DOC_REFACTOR_PLAN.md` —— 文档整顿的**施工计划书**（施工期唯一执行入口）。

照本项目现成模子：**单一出处 + 双向防漂移 + 解析不到就响亮失败 + 全挂 `check-all`**。

## 为什么它是「生成物」而不是「手写文档」

计划书最怕的失效模式是**它和现实不一致而没人知道**。本项目已实测：草案 §C 那份 6 步顺序
在 9 处被施工推翻，**而没有任何东西会因此报错** —— 因为没有执行载体。
⇒ 本脚本把两件事钉死：

1. **活读数**（`LIVE`）：每条都带**「怎么重算」** ⇒ 门禁逐条复算，**漂了就红**。
2. **工序清单**（`WAVES`）：每条工序有 id / 名 / 档 / 前置 / 判据 / 落点 ⇒ **可核对**。

## 三个不会静默的兜底（每条都真出现过）

| 兜底 | 防的是 |
|---|---|
| 每条 `LIVE` 读数**必须**带 `src`（重算命令或冻结值标记） | 读数**没来源**却长得像实测 |
| `src` 重算不出来 ⇒ **响亮失败**，不报绿 | 「扫不到就报绿」 |
| **人口下限** `LIVE_FLOOR` / `WAVE_FLOOR` / `ITEM_FLOOR` | 清单**被删空**也算通过 |

## 用法

    python3 tools/plan-doc-refactor.py --write     # 生成
    python3 tools/plan-doc-refactor.py --check     # 校验（门禁调这个）
    python3 tools/plan-doc-refactor.py --drift     # 只打印活读数的复算对照
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLAN = ROOT / "docs" / "DOC_REFACTOR_PLAN.md"

# ═══════════════════════════════════════════════════════════════════════════
# 一 · 冻结值 —— 只有用户裁定能改。门禁判「与这里不一致 ⇒ 红」。
# ═══════════════════════════════════════════════════════════════════════════
FROZEN: list[tuple[str, str, str]] = [
    ("常驻件总行数上限", "1501", "2026-10-02 用户裁定 (c)：1476 → 1501（＝当前实际值，余量 0）"),
    ("原上限（已作废）", "1476", "2026-09-14 定；2026-10-02 上调后作废，只作历史留痕"),
    ("归错档基线", "22", "`tools/decisions-index.py` `BASELINE_MISFILED`；只许变短"),
    ("归错档人口下限", "22", "`tools/decisions-index.py` `MISFILED_FLOOR`"),
    ("生效无痕基线", "33", "`tools/check-effective-trace.py` `BASELINE_NO_TRACE`；只许变短"),
    ("生效无痕人口下限", "30", "`tools/check-effective-trace.py` `EFFECTIVE_FLOOR`"),
    ("顶层包人口下限", "25", "`tools/design-index.py` `ASSERTIONS`"),
    ("勘测报告份数下限", "40", "`tools/survey-index.py` `ASSERTIONS`"),
    ("审批档 🟢", "新增脚本 · 新增门禁 · 生成物 · 新目录下的新文件", "§P-0；2026-10-02 复核仍然有效"),
    ("审批档 🔴", "任何 `AGENTS.md` 改动 · 删任何文件 · 改任何已有门禁的判据", "§P-0"),
    ("审批档 🟡", "`AI_DECISIONS.md` 存量改写 · 设计件归位 · `src/` 注释改动",
     "§P-0 ＋ 2026-10-02 追加 `src/` 注释"),
]

# ═══════════════════════════════════════════════════════════════════════════
# 二 · 活读数 —— 每条必须带 src（怎么重算），门禁逐条复算。
#    src 形状：("cmd", shell 命令)         ⇒ 取 stdout.strip()
#              ("regex", 相对路径, 正则)   ⇒ 取 group(1)
#              ("frozen", 冻结值表的量名)   ⇒ 值必须与 FROZEN 一致
# ═══════════════════════════════════════════════════════════════════════════
LIVE: list[tuple[str, str, tuple]] = [
    ("决策条目标题数", "724",
     ("cmd", "grep -cE '^#{2,4} D-[0-9]+' docs/AI_DECISIONS.md || true")),
    ("决策条目标题数（门禁口径）", "723",
     ("cmd", "python3 -c \"import re,sys;print(sum(1 for l in open('docs/AI_DECISIONS.md',encoding='utf-8') "
             "if re.match(r'^(#{2,4})\\\\s+(D-\\\\d+)(?![\\\\d\\\\w])',l)))\"")),
    ("`AI_DECISIONS.md` 行数", "27952",
     ("cmd", "wc -l < docs/AI_DECISIONS.md | tr -d ' '")),
    ("`package-info.java` 份数", "9",
     ("cmd", "find src -name package-info.java | wc -l | tr -d ' '")),
    ("`src/` 顶层包数", "30",
     ("regex", "docs/DESIGN_INDEX.md", r"^\| 顶层包数 \| \*\*(\d+)\*\* \|")),
    ("顶层包有设计说明", "7",
     ("regex", "docs/DESIGN_INDEX.md", r"^\| ⭐ \*\*顶层包有设计说明\*\*.*\| \*\*(\d+) /")),
    ("骨架有图例的 `package-info` 份数", "9",
     ("cmd", r"grep -l '@alice-skeleton' $(find src -name package-info.java) | wc -l | tr -d ' '")),
    ("`docs/plans/` 份数（含本目录 README）", "14",
     ("cmd", r"ls docs/plans/*.md | wc -l | tr -d ' '")),
    ("`docs/reviews/` 份数（含本目录 README）", "75",
     ("cmd", r"ls docs/reviews/*.md | wc -l | tr -d ' '")),
    ("`docs/` 根设计件份数", "14",
     ("regex", "docs/DESIGN_INDEX.md", r"^\| 顶层设计件（`docs/` 根） \| \*\*(\d+)\*\* \|")),
    ("`survey/` 报告份数", "49",
     ("regex", "survey/README.md", r"^\| 报告份数 \| \*\*(\d+)\*\* \|")),
    ("常驻件当前总行数", "1501",
     ("cmd", "cat AGENTS.md docs/AI_DEVELOPMENT_PLAYBOOK.md docs/AI_PROJECT_STATE.md | wc -l | tr -d ' '")),
]

# ═══════════════════════════════════════════════════════════════════════════
# 三 · 工序清单 —— id / 名 / 档 / 前置 / 判据 / 落点 / 状态
#      状态只有四种：未开始 · 进行中 · 已完成 · 阻塞（原因）
# ═══════════════════════════════════════════════════════════════════════════
W = "未开始"
DONE = "已完成"

WAVES: list[tuple[str, str, list[tuple]]] = [
    ("W0", "元工序（把载体与规矩立起来）", [
        ("W0-1", "修订草案 —— 被施工推翻处就地标注、指向新结论", "🟢", "—",
         "§P-1..§P-5 被点名的判据只有活读数仍在漂；§P-6／§C 的旧 6 步顺序已声明作废",
         "`HANDOVER.md` §J-1 / §J-3′ / §J-5", DONE),
        ("W0-2", "施工计划书载体 —— 本文件 ＋ 生成器 ＋ 门禁", "🟢", "—",
         "`check-plan-doc-refactor.sh` 绿；活读数逐条复算一致", "本文件", DONE),
        ("W0-3", "预算上限 1476 → 1501（用户裁 (c) ＋ 要求先与草案原理由核对）", "🔴", "—",
         "`check-doc-budget` 绿（1501 ≤ 1501）· 三条理由已写进函数注释",
         "`tools/check-all.sh` `run_doc_budget`", DONE),
        ("W0-4", "`tools/check-all.sh` 注释里的过期读数（写 1508／超 32）改掉", "🟢", "—",
         "注释与实际一致（实测 1501／超 25 ⇒ 改后 1501／余额 0）", "`tools/check-all.sh`", DONE),
        ("W0-6", "skill §四 加第 ④′ 步（出施工计划书）＋ 三条门槛 ＋ 升级路径", "🟢", "W0-2",
         "`large-refactor-survey-and-verify.skill.md` 含 ④′ 与升级路径；skills INDEX 有该 skill 行",
         "`.alice-supervision/skills/`", DONE),
        ("W0-5", "预算清点机制 —— 常驻件多久看一次 · 只出不进的落点", "🔴", "W0-3",
         "用户裁定后才动手；本轮不夹带（§J-6-5 第 2 条）", "`O143`", W),
        ("W0-7", "`AGENTS.md:40` 的 **1476** 要不要改（改就要同刀删等量行，需用户点名删哪些）",
         "🔴", "—",
         "⛔ 故意没改并已登记（§J-8）：加任何一行都会把预算门禁再顶红",
         "`AGENTS.md` · `O140` ⑧", "阻塞（待用户裁：改 ／ 不改）"),
    ]),
    ("W1", "③ 类：裁定（门禁 ＋ 状态词表）—— 主体已完成", [
        ("W1-1", "索引改认 `#{2,4}`（真判据 = 让 `D-203` 可见）", "🟢", "—",
         "`D-203` 在索引里可见", "`tools/decisions-index.py`", DONE),
        ("W1-2", "三断言落地：① 重复标题=警告 ② 归错档=红 ③ 缺号=红", "🔴", "W1-1",
         "三臂注入自证全红、基线安静", "`tools/decisions-index.py`", DONE),
        ("W1-3", "`HEADING` 补 `\\b`（防畸形编号被静默吞）", "🟢", "W1-1",
         "`### D-250X：` 不再被读成 `D-250`（h4 计数 54 → 53）", "`tools/decisions-index.py`", DONE),
        ("W1-4", "状态词表收敛为 **6 值**（用户裁定，含保留限定语）", "🟡", "—",
         "一行换一行（净增 0）· 128 条状态一条没丢 · 47 种 → 21 种", "`docs/AI_DECISIONS.md`", DONE),
        ("W1-5", "`生效` 无痕门禁**拆成独立门禁** `check-effective-trace`", "🟢", "W1-4",
         "`--selftest` 8/8；基线 33、人口下限 30；`check-decisions-index` 回到三断言",
         "`tools/check-effective-trace.py`", DONE),
    ]),
    ("W2", "④ 类：设计（索引从下往上）—— 主体已完成", [
        ("W2-1", "生成器 `tools/design-index.py` ⇒ `docs/DESIGN_INDEX.md`", "🟢", "—",
         "三臂注入自证（陈旧／人口下限／完整性自证）", "`tools/design-index.py`", DONE),
        ("W2-2", "门禁 `check-design-index.sh` 挂 `check-all`", "🟢", "W2-1",
         "完整性自证：磁盘上有 `package-info.java` 的包一个都不许不在表里",
         "`tools/check-design-index.sh`", DONE),
        ("W2-4", "⭐ **`DESIGN_INDEX` 扩成两层**：① `docs/` 根的**设计件**（14）② `package-info`（30 包）"
                 "＋ **双向覆盖自证**", "🟢", "W2-1",
         "✅ 用户 2026-10-02 纠正「不建 `docs/designs/` —— 它已经有了，就是 `docs/` 根」；"
         "⭐ 覆盖自证**当场抓出两个真错**（`ALICE_PATHING_CORE_R2` 漏收 · 名单里 `AGENTS.md`/`TASK_*.md` "
         "两条**点错名**的模式）", "`tools/design-index.py`", DONE),
        ("W2-3", "设计层缺口摆上台面（23/30 个顶层包没有设计说明）", "🟢", "W2-1",
         "索引只暴露、不代替人去补；补一份 = 在那个包里加一个 `package-info.java`",
         "`docs/DESIGN_INDEX.md`", DONE),
    ]),
    ("W3", "⑤ 类：报告（可引不可依据）—— 规矩立了一半", [
        ("W3-1", "`survey/README.md` 变生成式登记表（`采纳读数` 列只从磁盘复算）", "🟢", "—",
         "两臂注入自证（陈旧／人口下限）· 实测 48 份、被提到过 47、一次都没提到 1",
         "`tools/survey-index.py`", DONE),
        ("W3-2", "`Q-021`：`survey/` 与 `docs/reviews/` 是不是同一类", "🔴", "—",
         "✅ **用户 2026-10-02 已裁**（分层答案）：**效力上是同一类**（都 ⑤ 类 · 可引不可依据）；"
         "**内容上不是**（`docs/reviews/` 混着**施工设计单/讨论记录** ⇒ 已把 6 份搬到 `docs/plans/`）",
         "`HANDOVER.md` §J-12 · `docs/reviews/README.md`", DONE),
        ("W3-3", "E4「报告被当规则引用 ⇒ 红」", "🟢", "W3-2",
         "⚠️ 原判「判据不可分辨」（§K-2）；⭐ **用户 2026-10-02 裁完 `Q-021` 后判据变得可分辨了** —— "
         "**「哪份是报告」由它自己的自称决定**（自称写在文件里、可复算），"
         "而原先分不开是因为**「施工设计单」这类非报告件也住在同一目录、也被引**（已搬走）"
         "⇒ ⛔ 本刀仍未做，登记 `O147` 待排",
         "`HANDOVER.md` §K-2 ＋ §J-12", "阻塞（判据已可分辨，但未做 ⇒ 登记 O147）"),
        ("W3-4", "`survey/` 49 份 ＋ `docs/reviews/` 74 份逐份补 `落档判定` 行", "🟡", "W3-2",
         "🔴需裁／🟡挂账／🟢参考／⚪数据 —— 不改报告正文", "待做", W),
        ("W3-5", "**6 份草案/讨论件从 `docs/reviews/` 搬到 `docs/plans/`**", "🟢", "W3-2",
         "✅ 已搬；**0 处残留错路径**（逐份 grep 复算）；每份加了一行「移入说明」；"
         "`docs/reviews` 80 → **74** · `docs/plans` 7 → **13**", "`docs/plans/`", DONE),
        ("W3-6", "两个参考目录各补**生成式登记表** ＋ 门禁 `check-doc-registry`", "🟢", "W3-5",
         "✅ 每行**从文件自己头部读出来**（标题/自称类型/声明的性质/行数）；"
         "人口下限 plans ≥ 10 · reviews ≥ 60；逐字节比对到附录前", "`tools/doc-registry.py`", DONE),
    ]),
    ("W4", "设计层归位（D1/D5/D6/D7 的重编）—— 勘测前置", [
        ("W4-1", "勘测 `S-W4`（用户要求：施工难度大，勘测要做好，不一定是现在）", "🟢", "—",
         "产出：逐节勘测表（37 节 / 9 份）＋ **8 角色骨架** ＋ **37→8 的逐节映射**"
         "＋ 两条必须先裁的冲突（`C-A` 禁令 4 种写法 · `C-B` 修订占 h2）＋ 诚实边界",
         "`survey/50-S-W4-package-info骨架勘测-2026-10-02.md`", DONE),
        ("W4-1′", "裁 `S-W4` 的两条冲突 `C-A` / `C-B`", "🔴", "W4-1",
         "`C-A`：禁令/不属于这里/不负责的事/不装什么 ⇒ **合成一个节名**；"
         "`C-B`：修订**降为节内附注**，不占 h2 ✅ 用户 2026-10-02 已裁「三条我都采纳」",
         "本计划 · `O142`", DONE),
        ("W4-2", "统一 9 份 `package-info.java` 的节名与次序（用户裁 (b) ＋ 追加「内容结构必须统一」）",
         "🔴", "W4-1′",
         "统一 = 节名与次序统一，不是让每份凑齐所有节（空节比缺节更坏）"
         "· 不改内容事实 · `compileJava` 仍成功（实测 **BUILD SUCCESSFUL / 7 warnings**，与改动前同族）"
         "· `check-design-index` 重生成后逐字节相同",
         "`src/main/java/**/package-info.java`", "已完成（⚠️ 待另一个会话对抗性复核 —— 改的是 9 份 `src/` 注释）"),
        ("W4-3", "设计件归位（10 份：能下沉的下沉，剩下的原样保留、由索引登记）", "🟡", "W4-1",
         "不许合并（实测 8-gram Jaccard 中位 0.000）", "待做", W),
        ("W4-4", "补 4 份设计件的状态首行（`构想`／`边界`）", "🟢", "—",
         "其余 6 份已有声明 ⇒ 只统一位置", "待做", W),
        ("W4-5", "顶层策略文档 ≤400 行 ＋ 只引不抄（8-gram 可门禁）", "🟡", "W4-3",
         "废掉 §O-2 的「1/10」判据", "待做", W),
    ]),
    ("W5", "13 条危险件（A 组 6 ＋ B 组 7，`B7` 已裁忽略）", [
        ("W5-1", "A 组 6 条：`D-540` `D-550` `D-213` `D-345` `D-166` `D-568`", "🟡", "—",
         "用户 2026-10-02 **已逐条看完并采纳建议**（见 §J-3′ 第 5 项 ＋ §J-9 第 4 项）；"
         "`D-166` 只标结构、不动结构", "`docs/AI_DECISIONS.md`", W),
        ("W5-2", "B 组 6 条：`D-529` `D-347` `D-429` `D-200` `D-123` `D-278`", "🟡", "—",
         "同上**已统一批**；`D-033`（原 `B7`）用户裁「直接忽略，太久了，早就过期了」"
         "⇒ 通用裁定：过期件不修", "`docs/AI_DECISIONS.md`", W),
        ("W5-3", "8 条次级候选读完（实为 10 条 id）", "🟢", "—",
         "⛔ **勘测结果推翻了复核件的定性：8/10 条不是危险件**（它们是「AI 量了数」的留痕，"
         "不是「AI 判定冒充裁定」）⇒ 只剩 `D-312`/`D-405` 两条真缺陷"
         "（已被取代却无状态行）", "`HANDOVER.md` §J-9′", DONE),
        ("W5-4", "给 `D-312`/`D-405` 各补一行状态（`已失效` ＋ 取代者编号）", "🟡", "W5-3",
         "改动面 = **2 行** · `check-effective-trace` 不动基线（`已失效` 不进 `生效` 集合）"
         "· `check-decisions-index` 计数不变", "`docs/AI_DECISIONS.md`", W),
    ]),
    ("W6", "历史文档按内容重编（独立立项 · 支线，不取代主线）", [
        ("W6-1", "铁律确认：保号（不重编号）· 只标不删", "🔴", "—",
         "用户 2026-10-02 原话「我已确认」（同时确认了 §P-7 第 5 条）", "本计划 §二 `C-7`", DONE),
        ("W6-2", "H1：给 723 条补状态（复用 6 值 ＋ 一个 `已被取代`）", "🟡", "W6-1",
         "机械可算 · 净增 0（一行换一行）", "待做", W),
        ("W6-3", "H2：设计总表（生成物 ⇒ 见 §五 的裁定）", "🟡", "W6-2",
         "只有现成的事实能生成；完整版要等 `H1`／`H2-a` 的标记 ⇒ 见 §五", "待做", W),
        ("W6-4", "H3：从 151 条分离施工报告（＝ R3「分离」工序在历史账上的同一件事）", "🟡", "W6-2",
         "不改裁定语句本身", "待做", W),
        ("W6-5", "H4：过期／被覆盖件标记（117 条候选）⇒ 总表不列", "🟡", "W6-2",
         "原文保留在原位", "待做", W),
        ("W6-6", "H5：设计／裁定总表逐条经用户审批", "🔴", "W6-3",
         "这一步是人工瓶颈，不能自动化", "待做", W),
    ]),
]

# ═══════════════════════════════════════════════════════════════════════════
# 四 · 骨架图例（⭐ 单一出处）—— 9 份 `package-info.java` 的节名/次序由它产生
#      ⛔ 改这里 = 改 9 份 `src/` 注释 ⇒ 🔴 档（需用户裁）
#      用户 2026-10-02 裁 (b)「承认它兼作包级准入 ＋ 设计」＋ 追加「内容结构必须统一」
#      ⇒ ⭐「统一」= **节名与次序统一**，⛔ **不是让每份凑齐所有节**（空节比缺节更坏）
#      ⇒ 节号**按角色固定**，所以某份文件里的号**会跳**（那不表示缺内容，只表示没这个角色）
# ═══════════════════════════════════════════════════════════════════════════
ROLES: list[tuple[str, str]] = [
    ("①", "谁进得来（准入）"),
    ("②", "这个包是什么"),
    ("③", "判据"),
    ("④", "本包 ⛔ 不做什么"),
    ("⑤", "回收条件"),
    ("⑥", "门禁"),
    ("⑦", "沿革 / 未裁"),
    ("⑧", "今天的状态"),
]
LEGEND = ("骨架（9 份 `package-info.java` 统一节名与次序，2026-10-02 用户裁定「内容结构必须统一」）："
          + " · ".join(f"{n} {t}" for n, t in ROLES)
          + "。⭐ 节号**按角色固定**，所以本文件的号**会跳** —— 那不表示缺内容，只表示本包没有这个角色；"
            "⛔ 空节比缺节更坏，所以不许补空标题。")

# ═══════════════════════════════════════════════════════════════════════════
# 五 · 门禁兜底参数
# ═══════════════════════════════════════════════════════════════════════════
LIVE_FLOOR = 5
WAVE_FLOOR = 5
ITEM_FLOOR = 25
PLAN_MIN, PLAN_MAX = 120, 420
STATUSES = ("未开始", "进行中", "已完成")
STATUS_PREFIX = ("已完成（", "阻塞（")   # ⭐ 带说明的状态（⛔ 不许静默把说明吞掉）


def sh(cmd: str) -> str:
    p = subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True, text=True)
    return p.stdout.strip()


def measure(src: tuple) -> str:
    kind = src[0]
    if kind == "cmd":
        return sh(src[1])
    if kind == "regex":
        f = ROOT / src[1]
        if not f.exists():
            raise FileNotFoundError(f"活读数的来源文件不存在：{src[1]}")
        text = f.read_text(encoding="utf-8")
        flat = re.sub(r"[ \t]+", " ", text)
        for hay in (text, flat):
            m = re.search(src[2], hay, re.M)
            if m:
                return m.group(1)
        raise ValueError(f"在 {src[1]} 里正则取不到读数：{src[2]}")
    if kind == "frozen":
        for name, val, _ in FROZEN:
            if name == src[1]:
                return val
        raise KeyError(f"冻结值表里没有这一项：{src[1]}")
    raise ValueError(f"未知的 src 类型：{kind}")


def drift() -> list[tuple[str, str, str]]:
    out = []
    for name, claimed, src in LIVE:
        try:
            got = measure(src)
        except Exception as e:
            got = f"<复算失败：{type(e).__name__}: {e}>"
        out.append((name, claimed, got))
    return out


def render() -> str:
    L: list[str] = []
    A = L.append

    A("# 文档整顿 · 施工计划书（**生成物，禁手改**）")
    A("")
    A("> 本文件由脚本生成 —— 手改会被门禁判红。")
    A("> 重新生成：`python3 tools/plan-doc-refactor.py --write`　·　校验："
      "`python3 tools/plan-doc-refactor.py --check`")
    A("> 门禁：`tools/check-plan-doc-refactor.sh`（挂 `tools/check-all.sh`）。")
    A("")
    A("## 一 · 这是什么")
    A("")
    A("⭐ **本文件是「文档整顿」这个大施工的施工期唯一执行入口。**")
    A("")
    A("| 件 | 角色 | 地址 |")
    A("|---|---|---|")
    A("| **草案（依据）** | 被施工反推的第二次定稿；不是拿来照着干，是拿来查为什么 | "
      "`docs/HANDOVER.md` §J |")
    A("| ⭐ **施工计划书（本文件）** | **执行入口** —— 工序／档位／前置／判据／落点 | "
      "`docs/DOC_REFACTOR_PLAN.md` |")
    A("| 台账（一件一号） | 每件待处置事的生命周期 | `docs/OPEN_ITEMS_LEDGER.md` |")
    A("| 断点（历史） | 施工发生过什么（按时间追加） | `docs/HANDOVER.md` `## 断点NN` |")
    A("| 验收 | 本轮的验收判据与结论 | `docs/ACCEPTANCE_GUIDE.md` |")
    A("")
    A("### 用户 2026-10-02 的方法裁定（原话）")
    A("")
    A("> 「**先修订草案，再以此修订完整施工计划书**，现在还没有施工计划书载体**就先创一个**」")
    A("> 「**当实际施工情况比设计草案时变化太多，应该是停下来修订草案，而不是强行推进**」")
    A("")
    A("⇒ ⭐ **从本轮起，草案与施工计划书是两个物件**：草案 = 依据 · 计划书 = 执行入口。")
    A("")
    A("### ⚠️ 两个 `723` / `724` 的差别（2026-10-02 当场复算时抓出来的）")
    A("")
    A("同一天、同一份文件，两个数都出现过：**`grep -cE '^#{2,4} D-[0-9]+'` = 724**，")
    A("而 `decisions-index.py` 的 `HEADING`（末尾有 `\\b` 等价物）**= 723**。")
    A("")
    A("| 数 | 口径 | 差在哪 |")
    A("|---|---|---|")
    A("| **724** | 任何 `##`/`###`/`####` 后跟 `D-数字` | 含 `#### D-268⑤ 补：…` |")
    A("| **723** | 同上**但编号后必须是词边界** | ⛔ **排除 `D-268⑤`** —— `⑤` 被 `\\w` 认作词字符 ⇒ **不是词边界** |")
    A("")
    A("⭐ **这不是矛盾，是两个口径**；但它值得写下来，因为**它正是本项目最贵那一族错的形状**")
    A("（同一个量、两条命令、两个数，谁也不报错）。")
    A("⇒ 计划书里两个都列，**哪个数对应哪个口径写在旁边**。")
    A("")
    A("## 二 · 优先级铁律（冲突时按这个判）")
    A("")
    A("| 冲突 | 听谁的 |")
    A("|---|---|")
    A("| 计划书 vs 草案 | ⭐ **计划书**（草案是依据，会被修订） |")
    A("| 计划书 vs 台账 | **台账**（每件的生命周期归它） |")
    A("| 计划书 vs 任一条读数 | ⭐ **当场的复算**（两者都不算） |")
    A("| 计划书 vs 门禁 | ⭐ **门禁**（门禁是边界，计划书是安排） |")
    A("")
    A("**三条禁令**：")
    A("")
    A("1. ⛔ **本文件不抄正文** —— 只放路径／编号／判据。任何一段正文都归它的单一出处。")
    A("2. ⛔ **本文件不排期** —— 排期表在 `docs/OPEN_ITEMS_LEDGER.md` 的 `### J. 工作排期表`。")
    A("3. ⛔ **本文件不是门禁** —— 它不新增判据；判据一律挂 `tools/check-all.sh` 下的门禁。")
    A("")
    A("**读数纪律（`C-1`）**：本文件里的数一律标出处；⛔「我记得」不算出处。")
    A("")
    A("**重勘条件（`C-2`）**：⭐ **任何一条读数在开工时复算与本文件不符 ⇒ 停下重勘**，")
    A("⛔ 不许照着计划书硬推。这正是 §C 那份 6 步顺序被推翻 9 处而无人知晓的成因。")
    A("")
    A("**`C-7`：`src/` 改动边界** —— 2026-10-02 用户明示：`package-info.java` 的**注释**"
      "（含骨架统一）**允许改**，仍 ⛔ 禁止改任何**代码**（行为面）。")
    A("")
    A("## 三 · 审批档（🟢 自决 / 🔴 必裁 / 🟡 先看）")
    A("")
    A("| 档 | 什么算这一档 |")
    A("|---|---|")
    for name, val, _src in FROZEN:
        if name.startswith("审批档"):
            A(f"| **{name.replace('审批档 ', '')}** | {val} |")
    A("")
    A("⚠️ §P-0 的三档 **2026-10-02 复核后仍然有效，一字未改**。")
    A("")
    A("## 四 · 活读数（**门禁逐条复算；漂了就红**）")
    A("")
    A("| 量 | 计划书写值 | 实测 | 怎么重算 |")
    A("|---|---|---|---|")
    for name, claimed, src in LIVE:
        try:
            got = measure(src)
        except Exception as e:
            got = f"⛔ 复算失败：{type(e).__name__}"
        mark = "✅" if got == claimed else "⛔ **漂了**"
        how = "冻结值表" if src[0] == "frozen" else f"`{src[1]}`"
        A(f"| {name} | **{claimed}** | {got} {mark} | {how} |")
    A("")
    A("### 冻结值（只有用户裁定能改）")
    A("")
    A("| 量 | 值 | 依据 |")
    A("|---|---|---|")
    for name, val, why in FROZEN:
        A(f"| {name} | **{val}** | {why} |")
    A("")
    A("## 五 · 两条新裁定（2026-10-02，见 §J-3′）")
    A("")
    A("### 5.1 「设计总表」能不能长期存在，还是一次性")
    A("")
    A("**用户的问法**：「设计总表这个东西**真能存在并维护吗**，还是说你想先产出一个**一次性总表**，"
      "再归纳到各个子包文档里」")
    A("")
    A("**本项目已有的三条实证**：")
    A("")
    A("| 索引 | 单一出处 | 有门禁吗 | 结局 |")
    A("|---|---|---|---|")
    A("| `docs/DECISIONS_INDEX.md` | `AI_DECISIONS.md` | 有 | ⭐ **能活** |")
    A("| `docs/DESIGN_INDEX.md` | `package-info.java` ＋ 目录 | 有 | ⭐ **能活** |")
    A("| `survey/README.md` | 48 份报告的头部 | 手维护时期没有 | ⛔ **烂过一次** |")
    A("")
    A("⇒ ⭐ **判据（本项目实测规律）**：索引能活，**当且仅当** ① 有单一出处 ② 有门禁逐字节比对。")
    A("")
    A("⛔ 所以再抽一份总表是错的 —— 那会变成**第二份真相**"
      "（本项目已被「同一口径写两份然后漂移」坑过）。")
    A("")
    A("✅ **正确的形状（两步，顺序不能反）**：")
    A("")
    A("| 步 | 做什么 | 现在能做吗 |")
    A("|---|---|---|")
    A("| **H2-a** | 给设计条目加**判定标记**（`设计`／`裁定`／`读数`…）—— 标记**落地在 "
      "`AI_DECISIONS.md` 的条目上**，不抽成新副本 | 能做（机械可算 ＋ 人工批） |")
    A("| **H2-b** | **设计总表 = `DECISIONS_INDEX.md` 的一个按标记筛选的视图**（生成物 ＋ 门禁）"
      " | 要等 `H1`／`H2-a` 的标记有了才有意义 |")
    A("")
    A("⭐ **一句话**：**能存在、能维护 —— 但只能是视图，不能是第二份原件**；"
      "而它今天的信息量取决于标记做到什么程度（现在 84% 的条目连状态都没有）。")
    A("")
    A("### 5.2 「草案 → 勘测 → 修订 → 计划书」该进 skill 还是立规矩")
    A("")
    A("⭐ **裁定建议：进 skill，不立规矩**（完整论证见 `docs/HANDOVER.md` §J-6）。三条理由：")
    A("")
    A("1. 它过不了本项目自己的**准入三问**（问不了「能让构建失败吗」）")
    A("2. **样本只有 3 次** ⇒ 从 3 次里立永久规矩**正是本项目反复踩的坑**")
    A("3. ⭐ **计划书本身就是带牙齿的载体** —— 约束力来自**产物的判据**，不来自散文条文")
    A("")
    A("✅ **要做的两件低成本事**：① skill §四 加一行指针（`④ 改 → ④′ 出施工计划书 → ⑤ 动手`）")
    A("② **升级路径写死**：当「跳过该流程」造成一次真实损失时，才升进 `AGENTS.md`，"
      "且**必须同时给出可机械判的条件**。")
    A("")
    A("## 六 · 工序清单")
    A("")
    A("**状态只有四种**：`未开始` · `进行中` · `已完成` · `阻塞（原因）`。")
    A("⭐ **「阻塞（待用户裁）」与「不做」是两回事** —— 后者要写明**为什么判据不可分辨**。")
    A("")
    for wid, wname, items in WAVES:
        A(f"### {wid} · {wname}")
        A("")
        A("| # | 工序 | 档 | 前置 | ⭐ 判据（做完怎么知道对了） | 落点 | 状态 |")
        A("|---|---|---|---|---|---|---|")
        for iid, nm, tier, pre, crit, dest, st in items:
            A(f"| `{iid}` | {nm} | {tier} | {pre} | {crit} | {dest} | {st} |")
        A("")
    A("## 七 · ⛔ 本计划明确**不做**的")
    A("")
    A("1. ⛔ **搬动 `D-167`..`D-498` 的标题级别**（332 条）—— 用户 2026-10-02 已确认")
    A("2. ⛔ **给历史条目重编号**（`W6` 的铁律：保号）")
    A("3. ⛔ **修过期件**（`D-033` 用户原话「直接忽略，太久了，早就过期了」⇒ 通用裁定）")
    A("4. ⛔ **删任何 `survey/` ／ `docs/reviews/` 正文**")
    A("5. ⛔ **改任何已有门禁的判据** —— 除非用户裁（`W1-5` 就是先裁后拆的样板）")
    A("")
    A("## 八 · 读数漂了怎么办")
    A("")
    A("```")
    A("开工 → 跑 python3 tools/plan-doc-refactor.py --drift")
    A("   ├─ 全一致  ⇒ 照计划书干")
    A("   └─ 有漂移  ⇒ 停下（C-2）")
    A("        ├─ 若是「世界变了」（别人改了文件）  ⇒ 改 LIVE 表 → --write → 提交")
    A("        └─ 若是「我读错了」（当初口径就错） ⇒ ⭐ 回去修订草案（§J），再改计划书")
    A("```")
    A("")
    A("⚠️ **第二条是本项目最贵的那一族**（§G：假前提写进定稿草案，变成后续所有施工的地基）。")
    A("")
    return "\n".join(L) + "\n"


def check() -> int:
    problems: list[str] = []
    warns: list[str] = []

    if len(LIVE) < LIVE_FLOOR:
        problems.append(f"活读数只有 {len(LIVE)} 条 < 人口下限 {LIVE_FLOOR} ⇒ 计划书的读数被删空了")
    if len(WAVES) < WAVE_FLOOR:
        problems.append(f"工序波只有 {len(WAVES)} 波 < 人口下限 {WAVE_FLOOR}")
    n_items = sum(len(it) for _, _, it in WAVES)
    if n_items < ITEM_FLOOR:
        problems.append(f"工序条目只有 {n_items} 条 < 人口下限 {ITEM_FLOOR}")

    for name, claimed, src in LIVE:
        if not src or (src[0] != "frozen" and len(src) < 2):
            problems.append(f"活读数「{name}」没有「怎么重算」（src 空）")
            continue
        try:
            got = measure(src)
        except Exception as e:
            problems.append(f"活读数「{name}」**复算不出来**（{type(e).__name__}: {e}）"
                            f" ⇒ 这是「扫不到就报绿」的入口，不放过")
            continue
        if got != claimed:
            problems.append(f"⛔ **活读数漂了**：「{name}」计划书写 **{claimed}**，实测 **{got}** "
                            f"⇒ 按 `C-2` 停下重勘（改 `LIVE` 表 → `--write`，或回去修订草案）")

    for name, claimed, src in LIVE:
        if src and src[0] == "frozen" and not any(f[0] == src[1] for f in FROZEN):
            problems.append(f"活读数「{name}」引了冻结值表里没有的项：{src[1]}")

    ids = [it[0] for _, _, items in WAVES for it in items]
    dup = {i for i in ids if ids.count(i) > 1}
    if dup:
        problems.append(f"工序 id 重复：{sorted(dup)}")

    idset = set(ids)
    for _, _, items in WAVES:
        for iid, _nm, _t, pre, _c, _d, _s in items:
            if pre in ("—", "", None):
                continue
            for p in re.split(r"[ /＋+,]", pre):
                p = p.strip()
                if p and p not in idset:
                    problems.append(f"`{iid}` 的前置 `{p}` 不是一个存在的工序 id")

    for _, _, items in WAVES:
        for iid, _nm, tier, _p, _c, _d, _s in items:
            if tier not in ("🟢", "🔴", "🟡"):
                problems.append(f"`{iid}` 的档位不合法：{tier}")

    for _, _, items in WAVES:
        for iid, _nm, _t, _p, _c, _d, st in items:
            if not (st in STATUSES or st.startswith(STATUS_PREFIX)):
                problems.append(f"`{iid}` 的状态不合法：{st}")

    want = render()
    if not PLAN.exists():
        problems.append(f"计划书不存在：{PLAN.relative_to(ROOT)} ⇒ 跑 `--write`")
    else:
        have = PLAN.read_text(encoding="utf-8")
        if have != want:
            ha, wa = have.split("\n"), want.split("\n")
            hl = next((i + 1 for i, (a, b) in enumerate(zip(ha, wa)) if a != b),
                      min(len(ha), len(wa)) + 1)
            problems.append(f"⛔ 计划书已陈旧，首个不同在第 {hl} 行 "
                            f"⇒ 跑 `python3 tools/plan-doc-refactor.py --write`")

    n = len(want.split("\n"))
    if not (PLAN_MIN <= n <= PLAN_MAX):
        warns.append(f"计划书 {n} 行，落在 [{PLAN_MIN}, {PLAN_MAX}] 之外 "
                     f"⇒ 它是不是开始变成第二本厚账了？")

    print(f"PLAN_DOC_REFACTOR_RESULT 活读数 {len(LIVE)} 条 · 工序 {len(WAVES)} 波 / "
          f"{n_items} 条 · 计划书 {n} 行")
    for w in warns:
        print(f"  [WARN] {w}")
    for p in problems:
        print(f"  [FAIL] {p}")
    if problems:
        print("PLAN_DOC_REFACTOR_RESULT FAIL")
        return 1
    print("PLAN_DOC_REFACTOR_RESULT PASS")
    return 0


def selftest() -> int:
    """四臂注入自证 —— ⭐ 「能过」≠「能抓」。"""
    import io
    from contextlib import redirect_stdout
    import copy

    global LIVE, WAVES

    def run() -> tuple[int, str]:
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = check()
        return rc, buf.getvalue()

    arms: list[tuple[str, str, str]] = []  # (臂, 期望在输出里出现的字样, 实际输出)

    base_live, base_waves = copy.deepcopy(LIVE), copy.deepcopy(WAVES)

    # 臂 A：把一条活读数的"写值"改错 ⇒ 必须报漂移
    LIVE = copy.deepcopy(base_live)
    LIVE[0] = (LIVE[0][0], "999999", LIVE[0][2])
    rc, out = run()
    arms.append(("A 活读数写错", "活读数漂了", out))
    LIVE = base_live

    # 臂 B：把 LIVE 清空 ⇒ 人口下限必须红
    LIVE = []
    rc, out = run()
    arms.append(("B 活读数清空", "人口下限", out))
    LIVE = base_live

    # 臂 C：把 WAVES 清空 ⇒ 两道人口下限都必须红
    WAVES = []
    rc, out = run()
    arms.append(("C 工序清空", "人口下限", out))
    WAVES = base_waves

    # 臂 D：把前置指向一个不存在的 id ⇒ 必须红
    WAVES = copy.deepcopy(base_waves)
    it = list(WAVES[0][2][0])
    it[3] = "W9-99"
    WAVES[0][2][0] = tuple(it)
    rc, out = run()
    arms.append(("D 前置悬空", "不是一个存在的工序 id", out))
    WAVES = base_waves

    bad = 0
    for name, want, out in arms:
        ok = ("FAIL" in out) and (want in out)
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ 期望红且含「{want}」"
              f"{'' if ok else ' —— 实际：' + out.replace(chr(10), ' | ')[:220]}")
    print(f"PLAN_DOC_REFACTOR_SELFTEST {'PASS' if not bad else 'FAIL'}: "
          f"arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help="生成计划书")
    ap.add_argument("--check", action="store_true", help="校验（门禁调这个）")
    ap.add_argument("--drift", action="store_true", help="只打印活读数的复算对照")
    ap.add_argument("--selftest", action="store_true", help="四臂注入自证")
    a = ap.parse_args()

    if a.selftest:
        return selftest()

    if a.drift:
        bad = 0
        for name, claimed, got in drift():
            ok = "✅" if got == claimed else "⛔ 漂了"
            bad += got != claimed
            print(f"{ok}  {name}: 写 {claimed} / 实测 {got}")
        return 1 if bad else 0

    if a.write:
        text = render()
        PLAN.write_text(text, encoding="utf-8")
        print(f"已写入 {PLAN.relative_to(ROOT)}（{len(text.splitlines())} 行）")
        return 0

    return check()


if __name__ == "__main__":
    sys.exit(main())
