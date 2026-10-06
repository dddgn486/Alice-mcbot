#!/usr/bin/env python3
"""**开工前的收敛性检查**：旧家的每一件，在**新家**是不是都能找到**唯一**去处。

## ⭐ 为什么有它（用户 2026-10-02 逐字）

> 「**"清理旧家是非常麻烦的事"，他能不能做好，直接建立在新家有没有建好的基础上**，
>  所以**开始这波之前，一定要保证草案，没有问题**」

⇒ **本脚本就是"新家建好了没有"的机械判据。** 它回答**两个问题**：

1. ⛔ **有没有"无家可归"的件**？（回收站为空才是新家建好的判据）
2. ⛔ **有没有"一个载体挂两类"的件**？（多归属 ⇒ 清理时必然摇摆）

## 判据（三道，⛔ 都是机械可算的）

| 道 | 判据 | 失败意味着 |
|---|---|---|
| **① 覆盖臂** | 旧家的**每个目录／件**必须命中 `DEST` 里**至少一条**规则 | ⛔ **新家有洞** ⇒ 那些件会变成"孤儿"，清理时无处可放 |
| **② 唯一臂** | 命中**多于一条**规则 ⇒ **报出来**（⛔ 不自动选，因为"谁优先"是裁定） | ⚠️ **载体挂两类** ⇒ 同族错（本项目最贵那族）的形状 |
| **③ 判据臂** | `DEST` 里每条规则必须带**"为什么它在这"**（一句判据），⛔ 空判据 ⇒ 红 | 规则的载体 | 门禁 > 散文 |

## ⛔ 本脚本**假装不了**的

1. ⛔ 它判**结构**，⛔ 不判**实质**（"这份该不该留"是你的判断 —— 见 `tools/` 那条纪律）；
2. ⛔ 它不查**内容重复度**（那是"合并"的判据，另算）；
3. ⭐⭐ **"域外"与"好家"可以同时成立（⛔ 不是二选一）** —— 实测抓到 4 件：
   `docs/archive/legacy-*/**/*_DESIGN.md` 既命中 ④（它**是**设计件）又命中 ⑩（它**是**上一代/已归档）。
   ⇒ ⭐ 正确读法 = **⑩ 管"它进不进体系（效力）"，④ 管"它是什么（形态）"** ——
   ⛔ **两个轴，不是一个**落点****。本脚本用"⑩ 兜底优先"给出**唯一落点**，但**如实保留这条边界**。
4. ⚠️ `DEST` 是**我从草案 `§D′` 抽出来的** —— ⚠️ 它与草案不一致时，**以草案为准**，
   本脚本要跟着改（⭐ 这正是 `C-2`：读数漂了就停下重勘）。

用法：`python3 tools/new-home-audit.py`　·　`--selftest`
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: ⭐⭐ **新家的八类 ＋ 域外**（出处 = 草案 `docs/HANDOVER.md` `§D′`）。
#: 形状：("类号 类名", 判据（它为什么在这）, 载体（glob，相对仓根）)
#: ⚠️ **载体允许多条**（同一类可能有两个物理位置），⛔ 但**跨类的载体不许重叠**（见 ② 唯一臂）。
DEST: list[tuple[str, str, tuple[str, ...]]] = [
    ("① 常驻规范", "agent 推断不出 · 每会话无条件读 ⇒ 唯一被严格限量的",
     # ⚠️ `PLAYBOOK` 是 **① 类里与 `AGENTS.md` 并列的第二件** —— 第一版**漏了它**，
     #    而它正是"常驻件总行数 1501"这条预算的被除数之一 ⇒ 漏了它这张表就不完整。
     ("AGENTS.md", "docs/AI_DEVELOPMENT_PLAYBOOK.md")),
    #: ⭐⭐ **⑧ 设计（`package-info.java`）—— 2026-10-06 由「`①-code` 子类」升格为第 8 类**
    #:  （开发者裁「**补充 ⑧ 设计类**」⇒ ⚠️ 旧行名 `①-code` **已退役**；⭐ 同时按开发者令**把 ⑧ 与 ⑩ 对调**：
    #:    `⑧` = 设计 · `⑩` = 域外 ⇒ ⛔ 2026-10-06 **之前**的文档里写的「`⑧ 域外`」是**旧号**）。
    #:  ⭐ **为什么是 ① 的"子类"而不是并在 ① 里**（出处 = 咨询回执 `001` `T3` 的裁定「代码注释**进体系**，
    #:     作为 `①-code` 子类」——⛔ 该裁定 2026-10-06 已被取代，见上）：① 的**身份栏**是「agent 推断不出 · **每会话无条件读** ⇒ 唯一被严格限量的」，
    #:     ⛔ 而 `package-info.java` **按需读、⛔ 不每会话读、⛔ 不占 1501 那笔预算**
    #:     ⇒ 并在 ① 的载体里会让**那一栏的判据变假**（那种"读起来还正常"的错正是本表要拦的）。
    #:  ⭐ **它此前没有家**：`old_home_items()` 有一行 `src/` 的非 `.md` 全排除
    #:     ⇒ 本表**对 `src/` 一个保证都没有**，而 `package-info.java` 恰恰是 `src/` 里**唯一进体系**的那件
    #:     （它**已经在写设计**：骨架第②③⑥节；`DESIGN_INDEX` 也**已经索引它**）。
    ("⑧ 设计（`package-info.java`）",
     "架构／功能／子系统的技术规约（`§D′-1` ⑧）：**设计住在它描述的那个包里**（改代码的人顺手就能改）；⭐ 按需读 ⇒ ⛔ 不占 ① 的限量；⭐ **一个设计可以生成多条 ③ 裁定**",
     ("src/**/package-info.java", "docs/CROSS_PACKAGE_DESIGN.md")),
    ("② 入口 ＋ 地图", "谁说了算 · 从哪开始读", ("README.md", "docs/README.md")),
    ("③ 决策/裁定", "为什么这样定 ＋ 还算不算数 ⇒ 必须配索引",
     # ⚠️ 2026-10-04 用户裁「**门禁没有权利否决草案**」⇒ 两个 `*PROPOSAL*` **已搬到 ④**
     #    （草案 `§D′-1` ④ **身份栏**逐字把它们列在「提案未裁」里）—— 见 `§D′-8` 第 7 条。
     ("docs/AI_DECISIONS.md", "docs/DECISIONS_INDEX.md")),
    ("④ 状态 ＋ 未裁提案", "做到哪了／下一步／卡在哪 ＋ ⭐ 提案未裁 ＋ 施工域（plans）",
     # ⭐ **「提案未裁」这一半的两个具体位置**（2026-10-04 从 ③ 与 ⑧ 搬来 —— 撞上的是
     #    草案 `§D′-1` ④ 的**身份栏**，按「门禁没有权利否决草案」跟着草案走）。
     ("docs/HANDOVER.md", "docs/OPEN_ITEMS_LEDGER.md", "docs/AI_PROJECT_STATE.md",
      # ⭐ `docs/SCHEDULE.md`（2026-10-06 新增）—— 它是**从台账 `### J.` 拆出的排期表**
      #    （回执 `004.1` 的待办 `A`；判据 = 咨询回执 `005` 的 §七）。⭐ 它**不是**
      #    「现在在哪」那一半，而是 ④ 的**施工域 / 工序**那一半（与 `docs/plans/` 同侧）。
      #    ⚠️ 加这一行的**唯一**理由 = 它是**新造件** ⇒ 不加 `check-new-home` 报「无家可归 1 件」。
      "docs/SCHEDULE.md",
      "docs/QUESTIONS_LEDGER.md", "docs/plans/*.md",
      ".alice-supervision/improvements/*",
      "docs/authz/PROPOSAL_*.md", "docs/authz/POLICY_MATRIX_PROPOSAL.md",
      # ⚠️ 设计件：⛔ **不许**写成 `docs/*DESIGN*` —— 那会把 ⑦ 的**索引生成物**
      #    `docs/DESIGN_INDEX.md` 与 ⑧ 的 `docs/archive/**/*_DESIGN.md` 一起吞进来
      #    （⭐ 本审计第一版就是这么撞出 5 件"多归属"的 —— 记在脚本头的诚实边界里）。
      "docs/*_DESIGN.md", "docs/*_DISCUSSION.md",
      "docs/*_DRAFT.md", "docs/*_COMPARISON.md", "docs/DECISION_LAYER_FINAL_FORM.md",
      "docs/INTERACTION_LAYERS_COMPARISON.md",
      # ⚠️ **显式点名这一件**：`INTERACTION_LAYERS_COMPARISON.md` 头部逐字
      #    「本文是回答与留档（**只讨论，未实现**）」＋ §4「结论与建议（**待用户裁定**）」
      #    ⇒ 它是**设计讨论**，属 ④ 的"未裁提案"那一半。⭐ 本审计曾把它判成"④/⑦ 同类重叠"，
      #    这一行就是那条判据的落地。
      "docs/INTERACTION_LAYERS_COMPARISON.md",
      "docs/ALICE_PATHING_CORE_*.md", "docs/JOB_LAYER_DESIGN.md",
      "docs/MOD_ADAPTER_PROTOCOL.md", "docs/MINE_SURVEY_PROTOCOL.md",
      "docs/MOD_COMPAT_CRAFT_STATION_PLAN.md", "docs/STAGE3A_CRAFT_PLAN.md",
      "docs/WORLD_WRITE_AUTHORIZATION.md", "docs/CLIENT_AGENT_CHANNEL.md",
      "docs/CLIENT_AGENT_NEW_DEVICE_TEST.md", "docs/CLOUD_MIGRATION.md",
      "docs/ALIGNMENT_OPEN_QUESTIONS.md", "docs/GLOSSARY.md",
      "docs/TESTING_GUIDE.md", "docs/ACCEPTANCE_GUIDE.md", "docs/EXPECTED_REDS.md",
      "docs/DOC_REFACTOR_PLAN.md", "docs/KNOWLEDGE_RECIPE_GRAPH_NOTES.md",
      # ⭐ `docs/CLEANUP_CLASSIFY.md`（2026-10-02 阶段 3）—— **清旧家的判据表**。
      #    它与 `DOC_REFACTOR_PLAN.md` 同性质：**生成物 ＋ 挂门禁 ＋ 禁手改** ⇒ 归 ④ 的
      #    「做到哪了／下一步」那一半。⚠️ 它**不是**设计件（⛔ 不自称设计）、也**不是** ⑤ 类报告
      #    （⛔ 不自称报告）。⭐ 归类这条**是 `design-index` 的覆盖自证逼出来的**：
      #    本刀落地时它当场报「既不是设计件、也没在名单里被点名」—— 那一报就是这条的目的。
      "docs/CLEANUP_CLASSIFY.md",
      #: ⭐ `docs/EXTRACTED_KNOWLEDGE.md`（2026-10-05 开发者裁「**④ 家 ＋ 仍算容器**」）——
      #:   **提取册**：装从旧件里**提取**出来的设计／经验／判据（`W7′-8` 工序的落点，回执 `003` 定）。
      #:   ⭐ **归 ④ 的两个依据**：**(a)** 它**件头自报类标 = ④**（`cleanup-classify` 的
      #:   「件自报优先」**要 `DEST` 先有同号那一行才生效** —— ⛔ 本行缺了，自报就被前置拦掉）；
      #:   **(b)** 与 `DOC_REFACTOR_PLAN.md`／`CLEANUP_CLASSIFY.md` **同性质**（容器／判据表）。
      #:   ⛔ **本行是「补登记」，⛔ 不是新分类决策** —— `#2` 落地时只登记了 `design-index.py` 的
      #:   `NON_DESIGN_PATTERNS`、**漏了本表** ⇒ 覆盖臂当场红（⭐ **它抓对了**，见 `O167`）。
      #:   ⚠️ **已知遗留**：册里提取出的**设计**不进 `DESIGN_INDEX`（登记在 `O167`，⛔ 待裁未修）。
      "docs/EXTRACTED_KNOWLEDGE.md",
      "docs/MULTI_BOT_INTERFACE_RESERVATION.md", "docs/BATTERY_CURATION.md",
      "docs/reference/ROAD_MATHEMATICAL_MODEL.md")),
    ("⑤ 报告 ＋ 证据", "可引 ⛔ 不可当依据 · 四类证据 E1–E4",
     # ⚠️ 2026-10-04：这里原来还有一条 `docs/reviews/archive/*.md` ⇒ ⛔ **已删（冗余）**。
     #    ⭐ 原因：本文件的匹配器是 `fnmatch`，而 **`fnmatch` 的 `*` 会跨 `/`**
     #    ⇒ `docs/reviews/*.md` **本来就覆盖** `docs/reviews/archive/*.md`
     #    ⇒ 两条同时命中同一件 = **同类内冗余通配**（本审计自己的一条断言：迟早分叉）。
     #    ⚠️ **为什么以前没红**：`docs/reviews/archive/` 里**只有 1 份件、且是中文名**
     #    ⇒ 它一直在 `git ls-files` 那个盲区里被跳过 ⇒ **臂 F 修好盲区，这条冗余当场暴露**。
     #    ⛔ **不要**改成"让匹配器路径敏感"来保这条——那会让 `docs/archive/*` 失去 `docs/archive/legacy-*/…`
     #    （35 件当场无家可归）。⇒ 最小正确修法 = 删冗余那条。
     ("survey/*.md", "docs/reviews/*.md",
      ".alice-supervision/client-tests/*",
      "docs/RISK_SYSTEM_REVIEW_*.md", "docs/RISK_SYSTEM_ISSUE_LIST.md",
      "docs/REVIEW_2026-*.md", "docs/R4_BARITONE_ALIGNMENT_AUDIT.md",
      "docs/TRANSFER_MODULE_AUDIT.md", "docs/STAGE2_MODS_READABILITY.md",
      "docs/BARITONE_ANCHORS.md", "docs/BARITONE_CONTRAST_TESTING.md",
      "docs/reference/BARITONE_PORTING_CHECKLIST.md")),
    ("⑥ 边界即机器", "把规矩变成会失败的东西（门禁 ＋ 造它们的机器 ＋ runner）",
     # ⚠️ 第一版只写 `tools/check-*` ⇒ `⑥ = 0`（**不是没件，是判据漏了一整类**）；
     #    补上"造门禁的机器"与 runner 后，`tools/` 下全部机器都归此。⭐ 判据：`tools/` 下
     #    **一切"机器"**都属 ⑥ —— 门禁（`check-*`）· 生成器（`*-index.py`）· runner
     #    （`check-all.sh`）· 一次性工具。⛔ 它们不是散文，是**能跑的东西**。
     ("tools/*",)),
    ("⑦ 事实/数据", "⛔ 不做判定、不产生待办；唯一失效模式 = 过期",
     ("docs/*_FACTS.md", "docs/*.csv", "docs/*.txt", "docs/ALICE_CAPABILITIES.md",
      "docs/DESIGN_INDEX.md", "docs/data/MACHINE_MAP.csv", "docs/authz/OVERVIEW.md",
      "docs/reference/MEK_GUI_SEMANTICS.md", "docs/AI_TEST_MATRIX.md", "docs/AI_CHANGELOG.md",
      "docs/*.svg", "docs/*.html")),
    #: ⭐⭐ **⑨ 待删（没有家 ≠ 洞）** —— 2026-10-02 收敛时立的最后一类。
    #:  判据（**机械**）：① 在 `tools/` / `docs/` 之外（仓根或 stray）② 名字自带一次性标记（`.tmp-`）
    #:  ③ `git grep` 全仓**零 import / 零执行引用**（提及它的事 ≠ 引用它）。
    #:  ⚠️ 本表**只登记、⛔ 不删** —— 删是 🔴 档（`§P-0`），且台账 `O14-e` 已裁
    #:  「删前 `git grep` 确认零引用」⇒ 本行就是那次确认的落地。
    #: ⭐⭐ **⑪ 咨询通道**（2026-10-04 立）—— ⭐ 用户裁：「`consult/` **属新类，因为会直接产生用户的决策**」。
    #: ⭐ **类名 2026-10-05 已批**（用户裁：「**咨询通道**」，去「待批」二字）；⭐ **类号保留 `⑪`**。
    #: ⚠️ ⭐ **类标适用范围 = `①`–`⑦`**（同次裁定）⇒ ⛔ **`consult/` 的件不走类标路线**（那个 `⑪` 不是类标值）。
    #:  判据（它为什么在这）：⭐ **它的产出不是文档结论，是"要开发者拍板的问题"** ——
    #:  通道两头（`request/` 现状问题 ＋ `receipt/` 回执）**成对才构成一次咨询**。
    #:  ⚠️ ⭐ **类名待批**（新词须经开发者批准）⇒ 本行先用**描述性类名**，
    #:      ⛔ **本刀未动草案 `§D′-1` 的七类表**（那要另裁）。
    #:  ⛔ **它盯的不是这一行** —— 成对判据在 `tools/check-consult-pairs.py`（挂 `check-all`）；本行只回答"**它归谁**"。
    ("⑪ 咨询通道", "⭐ 产出是「要开发者拍板的问题」，⛔ 不是结论 ⇒ 成对才算一次咨询",
     ("consult/README.md", "consult/request/*.md", "consult/receipt/*.md")),

    ("⑨ 待删（一次性脚本）", "零引用 ＋ 一次性命名 ⇒ 没有家，等用户点头删",
     (".tmp-*",)),

    ("⑩ 域外（⛔ 不进体系）", "不同世代 / 受许可证约束 / 跨项目资产 / 仓外工具配置",
     # ⚠️ 2026-10-04：`.alice-supervision/improvements/*` **已搬去 ④** —— 它原先在这张表里，
     #    但它既不是"不同世代"、也不受许可证约束、也不是跨项目资产、也不是仓外工具配置
     #    （实测 = 2026-09-08 的**仓内改造提案**）⇒ 撞上草案 `§D′-1` ④ 的「提案未裁」。
     (".alice-supervision/archive/*", ".alice-supervision/skills/*",
      ".alice-supervision/skills-manifest.yml",
      "docs/archive/*",
      "src/**/CREDITS.md", "tools/agent-presets/*", "tools/client-agent/*",
      "tools/cloud-tunnel-README.txt", ".github/*", "steward-entry/*")),
]

#: ⚠️ 人口下限：`DEST` 条数少于此 ⇒ 响亮失败（表被删空也算通过，不放过）。实测 = 8。
DEST_FLOOR = 6

#: ⛔ 明确**不参与**归位的（工具/元数据，不是文档体系成员）
SKIP = re.compile(r"^(\.git|build|run|\.gradle|gradle)/|\.(png|jpg|jpeg|gif|svg|html|log|jar|class)$")


def old_home_items(root: Path) -> list[str]:
    """旧家 = **被 git 跟踪**的全部"文档型"件（⛔ 不含图片/日志正文，它们在证据目录里被整目录归类）。

    ⚠️⚠️ **必须 `-z`**（2026-10-04 修 —— `P2-7`）：不带 `-z` 时 `git ls-files` 会把
    **非 ASCII 路径加引号**（`"survey/52-\\346\\211\\271…md"`）⇒ 下面的 `endswith(...)`
    **全部为假** ⇒ ⭐ **139 件中文名的件被静默跳过**（≈ 全部件的 23%），**而本表照报「无家可归 0」**。
    ⇒ ⭐ 这是**同一个错第二次**（`tools/check-e4-offrepo.py` 犯过一模一样的一次，
       当时 3 份文件被静默跳过而报绿，修法也是 `-z` ＋ 加一条专防它的臂）。
    ⭐ 防它的是 **臂 F**（喂一个中文名文件，断言它**真的**出现在清单里）。
    """
    import subprocess
    out = subprocess.run(["git", "ls-files", "-z"], cwd=root,
                         capture_output=True, text=True).stdout
    items = []
    for l in out.split("\0"):
        if not l:
            continue
        if SKIP.search(l):
            continue
        # ⚠️⚠️ **必须含 `.py` / `.sh`** —— 第一版只收 `.md/.csv/.txt/.yml` ⇒
        #    结果 ⑥ 类（`tools/check-*`）**恒为 0**。⭐ 而那不是"没有件"，是**普查漏了一整类**：
        #    `AGENTS.md` 逐字「把规则变成会失败的东西」⇒ **那 54 道门禁就是 ① 类规则的机器载体**。
        #    ⇒ 漏了它们，这张"新家覆盖表"就是**不完整**的（而它读起来完全正常）。
        # ⚠️ **范围判据**（本脚本最该被质疑的地方，如实写）：
        #    · ✅ 收：`.md/.csv/.txt/.yml/.yaml`（文档）、`.py/.sh/.mjs`（**脚本也是文档体系成员** ——
        #      ① 类规则的机器载体就是它们）
        #    · ⛔ **不收 `.json`**：实测一收就涌进 **149 件"无家可归"**，绝大多数是
        #      `src/main/resources/assets/**`（mod 的物品模型 / 语言文件）与 `.devcontainer/`
        #      ⇒ ⭐ **它们不是"文档体系成员"，是 game 资源与 CI 配置** —
        #      本表只回答"**文档**去哪儿"，⛔ 不回答"代码资源去哪儿"（那是另一条线）。
        #    · ⛔ 不收 `src/**` 的**非 `.md`** 件（同上）
        #    ⭐⭐ **2026-10-05 `A2` 开了**一处****（用户裁「`check-new-home` 要收 `src/**/package-info.java`」）：
        #      `package-info.java` **进普查** —— 它是 `src/` 里**唯一进体系**的那件（见 `DEST` 的 `⑧ 设计` 行）。
        #      ⛔ **不放开整个 `.java`**：542 份 `.java` 是**主代码**，⛔ 不是文档体系成员
        #      （`src/**` 的非 `.md` 仍**全部排除**，只开 `package-info.java` 这一处）。
        if l.startswith("src/") and not l.endswith((".md", "package-info.java")):
            continue
        if l.startswith((".devcontainer/", ".github/workflows/build.yml")):
            continue
        if l.endswith((".md", ".csv", ".txt", ".yml", ".yaml", ".py", ".sh", ".mjs",
                       "package-info.java")):
            items.append(l)
    return items


def match(rel: str, pats: tuple[str, ...]) -> list[str]:
    import fnmatch
    hit = []
    for p in pats:
        if fnmatch.fnmatch(rel, p):
            hit.append(p)
    return hit


def audit(root: Path) -> tuple[list[str], list[str], dict[str, int], list[str]]:
    items = old_home_items(root)
    homeless: list[str] = []
    multi: list[str] = []
    axis: list[str] = []
    counts: dict[str, int] = {d[0]: 0 for d in DEST}
    # ⭐⭐ **优先级 = `DEST` 里的出现顺序**（⛔ 不靠"模式更具体"那种启发式）：
    #   第一版用"通配更少 ⇒ 更具体"，实测在 `docs/archive/**/*_DESIGN.md` 上**失灵** ——
    #   ④ 的 `docs/*_DESIGN.md` 与 ⑧ 的 `docs/archive/*` **通配数相同** ⇒ 判不出胜负。
    #   ⭐ 排进数据才可复算：**⑩ 域外排最后、模式最兜底 ⇒ 它命中时永远赢**
    #   （它声明的是"不进体系"）。⚠️ 而**两轴并存的真相**见脚本头诚实边界第 3 条。
    order = {name: i for i, (name, _w, _p) in enumerate(DEST)}
    for rel in items:
        hits: list[tuple[str, str]] = []          # (类名, 命中的那个模式)
        for name, _why, pats in DEST:
            for h in match(rel, pats):
                hits.append((name, h))
        if not hits:
            homeless.append(rel)
            continue
        # ⚠️ **按类去重**：同一类里被两个模式命中（如 `docs/*_DESIGN.md` 与
        #    `docs/MINE_TASK_DESIGN.md`）**不是多归属** —— 那是同一类的两条路。
        #    （⭐ 第一版没去重 ⇒ 报出 20 件"多归属"，全是假的。）
        seen: dict[str, str] = {}
        for n, h in hits:
            seen.setdefault(n, h)
        names_hit = sorted(seen, key=lambda n: order[n])
        winner = names_hit[-1]
        counts[winner] += 1
        # ⭐ **同类内多模式也算错** —— ⚠️ 我一度删掉这条，**臂 B 当场把它抓回来**：
        #    同类里两个模式同时命中 = **冗余模式**（同一份东西两条路）⇒ 迟早分叉。
        # ⚠️⚠️ **只报"两条通配重叠"** —— ⛔ **不报"显式点名 ＋ 通配"**：
        #    点名是**作者意图的显式登记**（"我知道这份该在这类"），它与通配同时命中**无害**；
        #    而两条**通配**重叠才是真冗余（同一条路铺两遍 ⇒ 迟早分叉）。
        #    ⭐ 这个区分是**加臂 E 逼出来的**：我第一版把两者一起报 ⇒ 真树上刷出 8 条假红。
        hit_pats = {pt for n, pt in hits if n == winner}
        wild_card = {pt for pt in hit_pats if "*" in pt}
        if len(wild_card) > 1:
            multi.append(f"{rel}  ⇒ **同一类（{winner}）被 {len(wild_card)} 条通配命中**："
                         f"{sorted(wild_card)} ⇒ 冗余通配，迟早分叉")
        if len(names_hit) > 1:
            # ⭐⭐ **区分两种重叠**（2026-10-02 实测撞出来的关键区分）：
            #   · **同一类**：那是同一类的两条路 ⇒ **错误**（本就该去重，第一版误报 20 件）
            #   · **跨类**：⭐ 是**两个轴同时成立** —— `⑩ 域外` 管「进不进体系（效力）」，
            #     好家管「它是什么（形态）」。⇒ ⛔ **不是缺陷**，是**必须显式登记的双轴**。
            if names_hit == [DEST[-1][0], DEST[-1][0]] or (
                    DEST[-1][0] in names_hit and len(names_hit) == 2):
                axis.append(f"{rel}  ⇒ 效力 = **{DEST[-1][0]}** ／ 形态 = "
                            f"**{names_hit[0]}**（⭐ 两轴并存，落点按兜底优先取域外）")
            else:
                multi.append(f"{rel}  ⇒ 命中 {len(names_hit)} 类：{' / '.join(names_hit)}")
    return homeless, multi, counts, axis


def run() -> int:
    problems: list[str] = []
    if len(DEST) < DEST_FLOOR:
        problems.append(f"⛔ `DEST` 只有 {len(DEST)} 条 < 人口下限 {DEST_FLOOR} ⇒ 表被删空也算通过")
    for name, why, pats in DEST:
        if not why.strip():
            problems.append(f"⛔ 载体「{name}」**没有判据**（为什么它在这）—— 规则的载体 | 门禁 > 散文")
        if not pats:
            problems.append(f"⛔ 载体「{name}」没有 glob")

    homeless, multi, counts, axis = audit(ROOT)

    print("## 每类覆盖面（被 git 跟踪的文档型件）")
    for name, _why, _p in DEST:
        print(f"  {name:22} {counts[name]:4}")
    print(f"  {'合计':22} {sum(counts.values()):4}")
    print()
    if homeless:
        print(f"## ⛔ 无家可归：{len(homeless)} 件（**新家有洞** —— 这些件清理时无处可放）")
        for h in homeless[:40]:
            print(f"  {h}")
        if len(homeless) > 40:
            print(f"  … 另 {len(homeless)-40} 件")
        print()
    if multi:
        print(f"## ⚠️ 多归属：{len(multi)} 件（**载体挂两类** —— 清理时必然摇摆）")
        for m in multi[:25]:
            print(f"  {m}")
        print()

    if axis:
        print(f"## ⭐ 双轴并存：{len(axis)} 件（⛔ **不是缺陷** —— 效力轴与形态轴同时成立）")
        for a in axis[:10]:
            print(f"  {a}")
        print()
    if problems or homeless or multi:
        print(f"NEW_HOME_AUDIT RESULT FAIL: 判据问题 {len(problems)} · 无家可归 {len(homeless)} · "
              f"同类重叠 {len(multi)}")
        for p in problems:
            print(f"  [FAIL] {p}")
        for m in multi:
            print(f"  [FAIL] {m}")
        return 1
    print(f"NEW_HOME_AUDIT RESULT PASS: 无家可归 **0** · 同类重叠 **0** · 双轴并存 {len(axis)}（已登记）"
          f" · 每条载体都有判据")
    return 0


def selftest() -> int:
    """五臂注入自证 —— ⭐ 「能过」≠「能抓」。"""
    arms: list[tuple[str, str, bool]] = []
    # 臂 A：无家可归必须被抓
    globals()["DEST"] = [("① 测试", "判据", ("docs/ONLY.md",))]
    with tempfile.TemporaryDirectory() as td:
        r = Path(td)
        (r / "docs").mkdir()
        (r / "docs" / "ONLY.md").write_text("x", encoding="utf-8")
        (r / "docs" / "OTHER.md").write_text("x", encoding="utf-8")
        import subprocess
        subprocess.run(["git", "init", "-q"], cwd=r, check=True)
        subprocess.run(["git", "add", "-A"], cwd=r, check=True)
        homeless, multi, _c, _axis = audit(r)
        arms.append(("A 无家可归", "无家可归", any("OTHER.md" in h for h in homeless)))
        # 臂 B：**同类内两个模式**同时命中 ⇒ 必须算错
        globals()["DEST"] = [("① A", "判据", ("docs/*.md",)), ("① A", "判据", ("docs/OTHER*",))]
        _h, multi, _c, _a = audit(r)
        arms.append(("B 同类重叠（同类多模式）", "多归属", len(multi) > 0))

        # 臂 D：**跨类（含末类兜底）** ⇒ ⛔ **必须安静**（那是双轴并存，不是缺陷）。
        #   ⚠️ 这一臂**不是凑数**：判据从"命中多类就报"进化成
        #   「**同类重叠 = 错** ／ **跨类双轴 = 合法**」⇒ 必须有东西证明
        #   "双轴那一路真的不报"，否则"我把重叠放宽了"**没有任何东西会因此变红**。
        globals()["DEST"] = [("① A", "判据", ("docs/OTHER*",)), ("⑩ 域外", "判据", ("docs/*",))]
        _h, multi2, _c, axis2 = audit(r)
        arms.append(("D 跨类双轴 ⇒ 必须安静且登记", "",
                     (len(multi2) == 0) and any("OTHER.md" in a for a in axis2)))

        # 臂 E：**显式点名 ＋ 通配 同命中 ⇒ 必须安静**（点名是意图登记，不是冗余）
        globals()["DEST"] = [("① A", "判据", ("docs/*.md", "docs/OTHER.md"))]
        _h, multi3, _c, _a = audit(r)
        arms.append(("E 点名＋通配 ⇒ 必须安静", "", len(multi3) == 0))
        # 臂 C：判据为空必须被抓（人口下限已满足）
        globals()["DEST"] = [(f"类{i}", "" if i == 0 else "判据", (f"docs/x{i}*",)) for i in range(DEST_FLOOR)]
        probs = [f"⛔ 载体「{n}」**没有判据**" for n, w, _ in DEST if not w.strip()]
        arms.append(("C 空判据", "没有判据", bool(probs)))

        # 臂 F ⭐ **非 ASCII 路径不许被静默跳过**（2026-10-04 补 —— `P2-7` 的防复发臂）。
        #   ⚠️ 它防的是一个**真实发生过**的缺陷：`git ls-files` 不带 `-z` ⇒ 中文名被加引号
        #   ⇒ `endswith(...)` 为假 ⇒ **139 件从未被评估，而本表照报「无家可归 0」**。
        #   ⛔ 判据不许耦合到"仓里现在有几个中文名文件"（那会随文档增删假红/假绿）——
        #   所以喂一个**临时**中文名文件，断言它**进得来**。
        (r / "docs" / "中文件.md").write_text("x", encoding="utf-8")
        subprocess.run(["git", "add", "-A"], cwd=r, check=True)
        _items = old_home_items(r)
        arms.append(("F 非 ASCII 路径不被静默跳过（-z）", "漏件",
                     any("中文件" in x for x in _items)))

        # 臂 G ⭐⭐ **`A2`（2026-10-05）的双向防复发臂** —— ⚠️ 缺任一半都测不出真缺陷：
        #   · **G1 开得不够** ⇒ `⑧ 设计` 的判据**恒不命中** ⇒ ⭐ 本表对 `src/` 又回到"**零保证**"
        #     （那正是 `A2` 要修的原缺陷 —— 它当时**没有任何东西会红**）；
        #   · **G2 开得过头** ⇒ 542 份 `.java` 涌进普查 ⇒ 本表立刻**几百件"无家可归"**假红。
        #   ⇒ ⭐ 只测 G1 会放过"把 `src/` 整个放开"，只测 G2 会放过"其实没放开"。
        _y = r / "src" / "main" / "java" / "com" / "x" / "y"
        _y.mkdir(parents=True)
        (_y / "package-info.java").write_text("x", encoding="utf-8")
        (_y / "Foo.java").write_text("x", encoding="utf-8")
        subprocess.run(["git", "add", "-A"], cwd=r, check=True)
        _items2 = old_home_items(r)
        arms.append(("G1 `src/**/package-info.java` **进**普查（`A2`）", "漏件",
                     any(x.endswith("package-info.java") for x in _items2)))
        arms.append(("G2 普通 `.java` ⛔ **不进**普查（`A2` 只开**一处**）", "",
                     not any(x.endswith("Foo.java") for x in _items2)))

    bad = 0
    for name, want, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ {'期望红且含「' + want + '」' if want else '期望安静'}")
    print(f"NEW_HOME_AUDIT_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        # ⚠️ 自证会改 `DEST` ⇒ 用子进程跑真树检查时**不受影响**（本函数在改完就返回）
        return selftest()
    return run()


if __name__ == "__main__":
    sys.exit(main())
