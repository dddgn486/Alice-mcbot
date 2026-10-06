# 文档改革 · 验收指引

> ⛔ **这不是规则、不是裁定。** 它是**给你看的一页纸**：做完之后你**怎么自己确认它对**、以及**这些东西以后上哪儿找**。
> ⭐ 每条都带**你自己能跑的命令**。⛔ 如果你发现我报的数和这里对不上，**那就是我错**。
> ⚠️ **失效条件**（`§D′-9` 9.4 第③条 · ⭐ 用户 2026-10-05 令「让**它跟着文档改革失效**」）：
>   本文**随「文档改革」而生** —— ⭐ **改革收口**（草案 `§P-8` 三档走完、下面那些「看数」不再是验收手段）之后，
>   **本文即失效** ⇒ ⭐ 届时的动作（删／转历史）**由批 2 审批台决定**。⛔ 它**不是**「永远正确」的散文。

---

## 〇、先说最要紧的三句话

1. ⭐ **你不需要读文档来判断我做得对不对** —— 下面每一条都有**一条命令**给你跑。
2. ⭐ **凡是删掉的东西，都在 git 历史里**（`git log --diff-filter=D --name-only` 可查），⛔ **没有真删**。
3. ⭐ **准入判据只有一条**：「**删掉它，agent 会不会做错？**」答不出 ⇒ 不该留。

---

## 一、验收的 **8** 个「看数」（⭐ 只花两分钟，先做这个）

```bash
cd /workspaces/Alice-mcbot
go() { printf '%-34s %s\n' "$1" "$2"; }

# ⚠️ `check-all` 一次约 1 分钟 ⇒ **只跑一次**，把输出存起来给下面各条共用
#    （第一版让它跑了 3 次 ⇒ 验收脚本 60 秒超时被 kill）
CHECK_ALL_OUT="$(bash tools/check-all.sh 2>&1)"

# ① 门禁总体（本次之后 failed 必须 = 0）
go "check-all"        "$(printf '%s' "$CHECK_ALL_OUT" | grep -o 'CHECK_ALL_RESULT.*')"

# ② 常驻规范行数 —— ✅ **2026-10-02 用户裁定 (c)：上限 1476 → 1501**
#    （我原先报"仍红、待裁"；用户裁了 (c) 并要求**先与草案原理由核对** ⇒ 理由三条已落在
#      `tools/check-all.sh` 的 `run_doc_budget` 注释里。⛔ 不是"降级过关"，是**改上限 ＋ 写理由**。）
go "AGENTS+PLAYBOOK+STATE" "$(cat AGENTS.md docs/AI_DEVELOPMENT_PLAYBOOK.md docs/AI_PROJECT_STATE.md | wc -l) / 1501（⛔ 超了就是红）"

# ③ 入口声索：必须**恰好 1 个文件**在**声明自己是入口**
#    ⚠️ 判据不能只数 `grep -l`：`AGENTS.md` 与 `docs/README.md` 里各有 1 处是在**说明"过去有两处"**，
#    它们**不是声索**。⇒ 判据 = "行的开头是 `-`/`>` + 指向自己的链接"
go "第一入口声索人" "$(grep -rln '^[-*] .*接手第一入口\|^> .*第一入口：\[' --include=*.md . | grep -v 'archive/\|survey/\|HANDOVER' | tr '\n' ' ')"

# ④ 设计覆盖率 —— ⭐ 现在有门禁了，直接读它（⛔ 别自己 find，口径会分叉）
bash tools/check-design-index.sh 2>&1 | grep DESIGN_INDEX_RESULT

# ⑤ 裁定状态词表（收敛后应当只剩 4 种取值）
go "状态取值种类"     "$(grep -oh '状态[：:][^（(]*' docs/AI_DECISIONS.md | sort -u | wc -l)"

# ⑥ ⭐ 施工计划书 —— **施工期唯一执行入口**（用户 2026-10-02：「还没有载体就先创一个」）
#    ⭐ 它自己会报"活读数漂没漂"（判据 C-2「停下重勘」）。⛔ 进度不看它，看它 §六 的状态列。
bash tools/check-plan-doc-refactor.sh 2>&1 | grep PLAN_DOC_REFACTOR_RESULT

# ⑦ ⭐ 2026-10-02 新增的三道门禁（各自对着一个**真实失败**，⛔ 不是"凑数"）
#    `check-quote-lint`  字面量里嵌裸引号（⭐ 起因：主工作流**同一天同一文件连续 4 次**犯它，
#                        而它只在 `py_compile` 那一刻暴露 ⇒ 要跑整套门禁才知道写坏了）
#    `check-facts-tiers` ⑦ 类事实表必须**自报档位**（⑦a/⑦b/⑦c —— 用户逐字「运行时产出 ≠ 不变数据」）
#    `check-glossary`    ⭐ **反臂**：词表里每个词必须在 `docs`/`src`/`tools` 里**真的搜得到**
#                        （词在仓里不存在 ⇒ AI 拿它去对对不上却**不报错**）
for g in check-quote-lint check-facts-tiers check-glossary; do
  bash "tools/$g.sh" 2>&1 | grep -E 'QUOTE_LINT_RESULT|FACTS_TIERS_RESULT|GLOSSARY_RESULT'
done

# ⑧ ⭐ 2026-10-02 第二批三道（同样各自对着一个**真实失败**）
#    `check-skills-index`    skills/README.md 变生成物 —— ⭐ 它原**手维护 131 行、无生成器、无门禁**
#                            ⇒ 按 §五 5.1 判据「索引能活 ⟺ 单一出处＋有门禁」**必烂**（列 24 行 vs 磁盘 21）；
#                            ⭐ 落地时当场抓到 `forge-blockpos-mutability.skill.md` **缺 frontmatter**
#                            ⇒ DSH 会**安静地丢掉它**（已补）
#    `check-proposal-status` ⭐ 自称「提案/草案」的件必须自报状态 —— 落地抓到 2 处真缺陷（零误报）：
#                            `POLICY_MATRIX_PROPOSAL.md` 标题与同页状态**相反**（已更正标题、原题留档）·
#                            `RISK_SYSTEM_DESIGN_DRAFT.md` 缺状态行（已补）
#    `check-archive-index`   6 个归档目录各有 README 写明「按什么分」（⭐ 用户裁：⛔ 不统一分法，
#                            改成两条判据 —— 按内容类型 / 按日期世代）
#    `check-new-home`        ⭐ **新家建好了没有** —— 旧家每一件在目标体系里都有**唯一**去处。
#                            ⚠️ 它是**清旧家的开工前提**（用户逐字「他能不能做好，直接建立在
#                            新家有没有建好的基础上」），⛔ 不是事后验收。
#                            基线：**441 → 443 件 · 无家可归 0 · 同类重叠 0 · 双轴并存 11（合法）**
for g in check-skills-index check-proposal-status check-archive-index check-new-home; do
  bash "tools/$g.sh" 2>&1 | grep -E 'SKILLS_INDEX_RESULT|PROPOSAL_STATUS_RESULT|ARCHIVE_INDEX_RESULT|NEW_HOME_AUDIT_RESULT'
done
```

**基准（施工前）**，供你对比：

| 读数 | 施工前 |
|---|---|
| `check-all` | **`pass=39 warning=2 failed=1`** → ✅ 现在 **`pass=57 warning=2 failed=0`**（⚠️ `warning=2` = `check-machine-map` 缺上游 jar · `check-headless-battery` 云端无 `run/` ⇒ **两条都是环境档**，⛔ 非行为红；门禁计数 **39 → 57**，⭐ 与 `pass=` **逐字同口径** —— 口径见 `docs/DOC_REFACTOR_PLAN.md` §四 的「门禁项数」行）|
| `AGENTS+PLAYBOOK+STATE` | **1476 / 1476**（余额 **0**）→ ✅ 现在 **1501 / 1501**（上限已按用户裁 (c) 上调；⚠️ 遗留「清点机制」= `O143`） |
| 「第一入口」声索 | **3** → ✅ 现在 **1**（`README.md`；`docs/START_HERE.md` 已删） |
| `package-info` | **9 / 30**（⛔ **不是 7/30** —— 另有 2 份在**子包**里：`action/craft/` · `region/authz/`） |
| 状态取值种类 | **37** → ✅ 现在 **47**（旧读数是**又少又脏**：见 §六 勘误） |
| 编译 | ⭐ **`BUILD SUCCESSFUL in 3m 8s`**（11 warnings / 0 error）—— 这是 **`src/` 改动前的对照臂** |
| `AI_DECISIONS.md` 真条目 | ⭐ **565**（不是 670 —— 「670」把 `####` 子标题也数了进去） |
| ⭐ 施工计划书 | ⛔ **施工前不存在** → ✅ 现在 `docs/DOC_REFACTOR_PLAN.md`（生成物 ＋ 门禁 ＋ 四臂注入自证） |

⚠️ **`warning` 不是"通过"** —— 它意思是**那条断言本轮没有执行**。规矩是"别把这一行读成全绿"。

---

## 二、逐项验收（按类别；⛔ 每项都给你一条命令）

### 类 A｜环境件 —— 基线变绿

| # | 你要确认 | 命令 | 通过长什么样 |
|---|---|---|---|
| A1 | 参照树在**持久**的位置 | `ls -ld /workspaces/reference/baritone-1.20.1` | 目录存在 |
| A2 | 参照树**内容对** | `wc -l /workspaces/reference/baritone-1.20.1/src/main/java/baritone/pathing/movement/MovementHelper.java` | **863** |
| A3 | `AGENTS.md` 不再钉旧机绝对路径 | `grep -n 'fb486' AGENTS.md` | ⛔ **0 命中** |
| A4 | 红线门禁转绿 | `python3 tools/redline-gates.py \| tail -1` | **`problems=0`** |

### 类 B｜入口 —— 只剩一处

| # | 你要确认 | 命令 | 通过长什么样 |
|---|---|---|---|
| B1 | 接手口令进了常驻规范 | `grep -n '接手' AGENTS.md` | 有，且带 `[用户确认: 2026-10-02]` |
| B2 | 旧入口文件已删 | `ls docs/START_HERE.md` | **不存在** |
| B3 | ⛔ 没有断链 | `python3 tools/check-doc-links.py \| tail -1` | **PASS** |
| B4 | 引用清干净 | `grep -rn 'START_HERE' --include=*.md . \| grep -v archive \| grep -v survey` | 只剩**记录性**的提及（⛔ 不该再有"入口"意义的） |
| B5 | `docs/README.md` 不再自称入口 | `head -4 docs/README.md` | ⛔ 没有"第一入口"字样 |

### 类 C｜裁定

| # | 你要确认 | 命令 | 通过长什么样 |
|---|---|---|---|
| C1 | 每条裁定**都有简述** | `python3 -c "import re;L=open('docs/AI_DECISIONS.md',encoding='utf-8').read().splitlines();print(sum(1 for l in L if re.match(r'^#{2,3}\s+D-\d+\s*$',l)))"` | **0**（无空简述） |
| C2 | **没有跳号** | `python3 tools/decisions-index.py --check` | **PASS** |
| C3 | 状态词表已收敛 | 见 §一 的 ⑤ | **4** 或更少 |
| C4 | ⭐ **AI 结论没冒充裁定** | `grep -c '状态：生效' docs/AI_DECISIONS.md` 对照 `grep -c '用户确认' docs/AI_DECISIONS.md` | 前者 ≤ 后者（**生效的必须有人批过**） |

### 类 D｜设计 —— 索引从下往上

| # | 你要确认 | 命令 | 通过长什么样 |
|---|---|---|---|
| D1 | 索引是**生成物** | `head -3 docs/DESIGN_INDEX.md` | 写明"由脚本生成、禁手改" |
| D2 | 索引与包**一致** | `bash tools/check-all.sh 2>&1 \| grep design-index` | **PASS** |
| D3 | ⭐ **"扫不到就报绿"被挡住** | 看 D2 那门禁的**人口下限**读数 | 有下限数，且不为 0 |
| D4 | 10 份设计件**都有状态声明** | `for f in docs/*DESIGN*.md docs/*ARCHITECTURE*.md; do head -3 "$f" \| grep -l '未裁提案\|边界' >/dev/null \|\| echo "缺: $f"; done` | 输出为空 |

### 类 E｜报告

| # | 你要确认 | 命令 | 通过长什么样 |
|---|---|---|---|
| E1 | 报告有落档判定 | `grep -l '落档判定' survey/*.md \| wc -l` | ≈ 报告总数 |
| E2 | 登记表是生成物 | `head -3 survey/README.md` | 写明"生成" |
| E3 | ⭐ **报告没被当规则引用** | 由 E4 门禁判定 | **PASS** |

---

## 三、⭐ 做完之后，这些文档上哪儿找（你要记住的只有 5 个位置）

| 你想知道 | 去哪儿 | 一句话 |
|---|---|---|
| **我该遵守什么** | `AGENTS.md` | ⭐ **只有这一个**。别的都不是规范 |
| **从哪开始读** | `AGENTS.md` 开头 | 接手口令 ＋ 阅读顺序，⛔ 别处不重复 |
| **某件事定了什么、还算不算数** | `docs/DECISIONS_INDEX.md` | 按**关键词/状态**找 `D-###` → 只读那一条正文 |
| **某块东西现在是什么样** | **那个包的 `package-info.java`** | ⭐ **跟着代码走，不会过期** |
| **全部设计一眼看全** | `docs/DESIGN_INDEX.md` | **从下往上生成**的索引 |
| **做到哪了 / 卡在哪** | `docs/HANDOVER.md` ＋ `docs/OPEN_ITEMS_LEDGER.md` | ⚠️ 带**保鲜期**，过期就不算数 |
| **某份报告说了什么** | `survey/README.md` | 每份带**落档判定**（🔴需裁/🟡挂账/🟢参考/⚪数据） |

### ⭐ 遇到这三种情况，按这个顺序找

```
「这个设计为什么是这样？」   → 那个包的 package-info → 它引的 D-###
「这件事以前定过吗？」       → DECISIONS_INDEX 搜关键词 → 看「状态」
「这东西由哪些件组成？」     → DESIGN_INDEX → 那个包的 package-info（第 2 节）
```

⛔ **不要做的事**：⛔ 不要在整个 `AI_DECISIONS.md` 里 `grep` 关键词找答案（27,946 行）—— **先走索引**。

---

## 四、⛔ 本次**没做**的（你不该期待它变了）

1. ⛔ **`AI_DECISIONS.md` 的 670 条不做全量改写**（只收敛状态词表 ＋ 补空简述）
2. ⛔ **不重编已有编号**（新规矩只对**新**条目生效）
3. ⛔ **不合并那 10 份设计件**（实测它们互不重复）
4. ⛔ **不动排期表**（它归 `OPEN_ITEMS_LEDGER.md` 的 `### J. 工作排期表`）
5. ⛔ **不做术语全仓普查**
6. ⛔ **不删任何报告正文**

---

## 五、⭐ 你随时可以推翻我的一条

如果你觉得**上面任何一条验收判据不对**，直接说 —— 判据错了，**比结果错了更严重**。
⚠️ 尤其请盯这一条：**「凡是能靠'上调预算/放宽阈值'过关的验收，都是假验收」**。

---

## 六、⚠️ 两条"验收时你必须再问我一次"的项

1. **③ 类存量分堆的精确率**
   机械分堆给出 **R 裁定 292 / C AI 结论 234 / D 设计 22 / ? 16**（⚠️ 第二会话按同一套规则**重建**得 `290/235/21/18`，Δ=5 ⇒ 它是**描述性规则**，不是可复现的规则）。
   精确率有**两个**数，都给你：第二会话的 **20 条**样本 = **11/20 = 55%**（Wilson 95% CI **34%–74%**）·
   结构上界 = **82 / 290 = 71.7%**（这 82 条**既无引文、又无 `[用户确认:]`、又无裁定状态**，不可能靠更多抽样证伪）。
   ⇒ ⛔ **在用它去改历史文本之前，必须先由第二个会话复核**（已在断点 §Q 登记）。
   ⇒ **验收时请直接问："分堆复核过了吗？精确率多少？"**

2. **`D-166` 的闭合** —— ✅ **已结案（2026-10-02 当场复算）：它没坏。**
   ⛔ 我曾写「它不闭合 ⇒ 后面 **17,437 行 = 全文 62%** 全挂在它名下」。**该读数已撤销**：
   它来自 `awk '/^## D-/'` —— 只认 `##`，而 **`D-167..D-498` 这 332 条用的是 `###`** ⇒ 命令**静默跳过 332 条**，
   把 `D-499`(@23711) 当成它的结束。**实测真值：`D-166` 段 = `6274..6397` = 124 行**（含 3 条 `###` 附注），**正常闭合**。
   ⇒ 换成了**真的**那条待验收项：**`D-203` 在索引里不可见**（它 3 次出现全是 `####`，而索引正则只认 `#{2,3}`）。
   ⇒ **验收时请直接问："`D-203` 现在进索引了吗？`grep -c 'D-203' docs/DECISIONS_INDEX.md` 是多少？"**（改前 **0**，改后应为 **≥1**）

## 七、⭐ 一句话验收法（如果你只想花 30 秒）

```bash
cd /workspaces/Alice-mcbot
# 全绿了吗？
bash tools/check-all.sh 2>&1 | grep CHECK_ALL_RESULT
# 入口还有几个声索人？（目标 1）
grep -rln '第一入口\|接手第一' --include=*.md . | grep -v 'archive/\|survey/\|HANDOVER'
# 设计有没有跟着代码走？（目标：每个顶层包一份）
echo "$(find src -name package-info.java | wc -l) / $(ls -d src/main/java/com/dddgn/alice/*/ | wc -l)"
```

**三个数**：`failed=0` · **1** · 份数**接近 30**。这三个对上了，剩下的都可以慢慢看。

