# 文档改革 · 验收指引

> ⛔ **这不是规则、不是裁定。** 它是**给你看的一页纸**：做完之后你**怎么自己确认它对**、以及**这些东西以后上哪儿找**。
> ⭐ 每条都带**你自己能跑的命令**。⛔ 如果你发现我报的数和这里对不上，**那就是我错**。

---

## 〇、先说最要紧的三句话

1. ⭐ **你不需要读文档来判断我做得对不对** —— 下面每一条都有**一条命令**给你跑。
2. ⭐ **凡是删掉的东西，都在 git 历史里**（`git log --diff-filter=D --name-only` 可查），⛔ **没有真删**。
3. ⭐ **准入判据只有一条**：「**删掉它，agent 会不会做错？**」答不出 ⇒ 不该留。

---

## 一、验收的 5 个"看数"（⭐ 只花两分钟，先做这个）

```bash
cd /workspaces/Alice-mcbot
go() { printf '%-34s %s\n' "$1" "$2"; }

# ① 门禁总体（本次之后 failed 必须 = 0）
go "check-all"        "$(bash tools/check-all.sh 2>&1 | grep -o 'CHECK_ALL_RESULT.*')"

# ② 常驻规范的行数（余额必须 ≥ 0；⛔ 不许靠"上调预算"过关）
go "AGENTS+PLAYBOOK+STATE" "$(cat AGENTS.md docs/AI_DEVELOPMENT_PLAYBOOK.md docs/AI_PROJECT_STATE.md | wc -l) / 1476"

# ③ 入口是不是只剩一个声索人（必须只剩 1 处）
go "第一入口声索数"   "$(grep -rn '第一入口\|接手第一' --include=*.md . | grep -v '^./docs/archive/\|^./survey/\|^./docs/HANDOVER.md' | wc -l)"

# ④ 设计覆盖率（package-info 份数 / 顶层包数）
go "package-info"     "$(find src -name package-info.java | wc -l) / $(ls -d src/main/java/com/dddgn/alice/*/ | wc -l)"

# ⑤ 裁定状态词表（收敛后应当只剩 4 种取值）
go "状态取值种类"     "$(grep -oh '状态[：:][^（(]*' docs/AI_DECISIONS.md | sort -u | wc -l)"
```

**基准（施工前）**，供你对比：

| 读数 | 施工前 |
|---|---|
| `check-all` | **`pass=39 warning=2 failed=1`** |
| `AGENTS+PLAYBOOK+STATE` | **1476 / 1476**（余额 **0**） |
| 「第一入口」声索 | **3**（`README.md` · `docs/README.md` · `docs/START_HERE.md`） |
| `package-info` | **9 / 30** |
| 状态取值种类 | **37** |
| 编译 | ⭐ **`BUILD SUCCESSFUL in 3m 8s`**（11 warnings / 0 error）—— 这是 **`src/` 改动前的对照臂** |
| `AI_DECISIONS.md` 真条目 | ⭐ **565**（不是 670 —— 「670」把 `####` 子标题也数了进去） |

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
| D4 | 10 份设计件**都有状态声明** | `for f in docs/*DESIGN*.md docs/*ARCHITECTURE*.md; do head -3 "$f" \| grep -l '构想\|边界' >/dev/null \|\| echo "缺: $f"; done` | 输出为空 |

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
   机械分堆给出 **R 裁定 292 / C AI 结论 234 / D 设计 22 / ? 16**，
   但抽样复核精确率**约 75%**（12 条里 3 条假阳性）。
   ⇒ ⛔ **在用它去改历史文本之前，必须先由第二个会话复核**（我已在断点 §Q 登记）。
   ⇒ **验收时请直接问："分堆复核过了吗？精确率多少？"**

2. **`D-166` 的闭合**
   它写了个 `##` 却**从不闭合** ⇒ 后面 **17,437 行 = 全文 62%** 全挂在它名下。
   ⇒ ⛔ **它不定，任何按标题边界操作的工具（含索引生成器）都会算错 62%**。
   ⇒ **验收时请直接问："`D-166` 闭合了吗？否则后面那些数凭什么算出来的？"**

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

