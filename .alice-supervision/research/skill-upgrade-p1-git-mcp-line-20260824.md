# P0 skill 方法论升级 + P1 Git MCP 试点 —— 最小实施线路规划

- 规划角色：Alice 实现规划员
- 日期：2026-08-24
- 基线：`ab510fd`（与补充调查一致；本任务未改任何文件）
- 依据事实源：
  1. `.alice-supervision/research/dev-tools-skill-mcp-supplement-20260824.md`（180 行；P0/P1/P2 划分、双端证据模式）
  2. `.alice-supervision/research/dev-tools-mcp-survey-20260824.md`（已有 MCP/工具调查）
  3. `.alice-supervision/skills/debugging-root-cause-analysis.skill.md`（161 行，纯 Markdown，无 frontmatter）
  4. `.alice-supervision/skills/test-coverage-matrix.skill.md`（221 行，纯 Markdown，无 frontmatter）
  5. `.alice-supervision/skills/evidence-collection-standard.skill.md`（286 行，纯 Markdown，无 frontmatter）
  6. `.alice-supervision/skills-manifest.yml`（31 行；3 个 skill 均为 `maintainer-approved` v1.0.0）
  7. `.alice-supervision/state-machine.yml`、`docs/SUPERVISION_PROTOCOL.md`（证据状态机与验收边界）

- 只读确认（本任务内直接观察）：`/home/fb486/.nvm/.../@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-mcp-client` 存在，package.json 为 `@deepseek-ai/dsh-mcp-client@0.1.1-rc.2`，README 声明“One plugin instance per MCP server in `cordis.yml`，`ctx.tools` 注册为 `mcp__<serverName>__<rawName>`，transport 支持 stdio / streamable-http”。**这只证明依赖存在与插件设计，不证明当前 3083 工作流已可/可安全挂载外部 stdio MCP server —— P1 可行性全部标注「待验证」**。

> **本规划不构成实施授权。** 未安装/连接任何工具、未改任何文件；P1 需监督员批准独立试点计划后才可动工，且 DSH 挂载能力必须先实测。

---

## 1. 阶段划分

### P0 —— skill 方法论升级（零成本、纯文档、不改业务代码/工具）

| 步骤 | 内容 | 依赖 | 产出 |
|---|---|---|---|
| P0-S1 | 依据 Anthropic 官方 Agent Skills 文章（supplement §3.1），为 3 个已批准 skill 补 `SKILL.md` 格式的 YAML frontmatter（`name`/`description`） | 无 | 3 个升级后的 skill 文件 |
| P0-S2 | 按 progressive disclosure 重构：顶部只保留“适用时机/核心原则/入口”，深度过程（5-Whys、矩阵模板、证据采集清单）下沉到可按需引用的段落或附录 | P0-S1 | 分层结构 |
| P0-S3 | 新增每 skill 的 eval 验证小节（最小可执行自检清单，见 §2.4） | P0-S2 | eval 清单 |
| P0-S4 | 把 supplement §3.6/§3.7 的“真实客户端所见 + 服务端内省”双端证据模式写入 `evidence-collection-standard` 与 `test-coverage-matrix`（§2.2/§2.3 落点） | P0-S2 | 双端证据方法段落 |
| P0-S5 | 语义一致性核对：升级前后 3 个 skill 的 `skills-manifest.yml` 注册条目（名称/scope/summary）不变，methodology 语义不增不减 | P0-S1..S4 | 核对结论（评估清单或报告附录） |
| P0-S6 | 监督员 review + 用户确认（无客户端可见行为） | P0-S5 | PLAN 转 APPROVED_FOR_IMPLEMENTATION（如适用）；HANDOVER 记录 |

依赖关系：S1→S2→S3/S4（可并行）→S5→S6。S3 与 S4 无相互依赖，可同时进行。

### P1 —— Git MCP server 试点（可行性未知，先行验证）

| 步骤 | 内容 | 依赖 | 产出 |
|---|---|---|---|
| P1-S1 | **DSH MCP 挂载能力验证**（最短路径第一步） | 无（先行） | 挂载可行性结论：可行 / 不可行 / 需隔离 profile |
| P1-S2 | 只读工具试点（`git_status`/`git_diff_unstaged`/`git_diff_staged`/`git_diff`；**禁止 `git_commit` 与任何写工具**） | P1-S1 判定可行 | 试点结果 + 证据 |
| P1-S3 | `repo_path` 白名单收敛为唯一 Alice 仓库路径；license/风险标注（§4） | P1-S2 | 白名单配置 + 风险记录 |
| P1-S4 | 影响评估：是否影响现有 3083 工作流（§5） | P1-S2/S3 | 影响结论 |
| P1-S5 | 监督员 review + 用户批准试点范围后才实施 | P1-S4 | 独立 approved plan |

依赖关系：S1 → S2 → S3/S4（并行）→ S5。**P1-S1 不通过则整个 P1 停止**（停止条件见 §4）。

P0 与 P1 相互独立：P1 试点不依赖 P0 文档升级；P0 不依赖 DSH MCP 能力。可并行推进（P0 由监督员批准文档工作包即可，P1 需独立试点批准）。

---

## 2. P0 每个 skill 文件的具体升级清单

### 2.1 共同升级（3 个文件都要）

1. **YAML frontmatter（新加，置于文件第一行，前无空白）**：
   ```yaml
   ---
   name: <skill-id>          # 与 skills-manifest.yml 的 id 严格一致
   description: <一句话>     # 说明何时该用、不承诺什么；<=~160 字符更佳
   ---
   ```
   - `debugging-root-cause-analysis`：description 示例“在多次失败、结论与结果矛盾或盲试时，用可证伪假设与对照验证找根本原因，而不是修症状。”
   - `test-coverage-matrix`：description 示例“规划验证时显式区分布局：服务端 fixture、客户端矩阵、不可由服务端证明的边界。”
   - `evidence-collection-standard`：description 示例“让每次 bug/测试/调查结论都带可复核证据并注明证据等级；没有证据就写‘未证实’。”
   - **保留**：现有 `# Skill: <Name>` 标题行（frontmatter 后接标题合法；或改为 `# <Name>` 均可，二选一，保证 manifest 引用不变）。
2. **progressive disclosure**：
   - 顶部保留：`## When to use this skill` / `## Core Principle`（若存在）。
   - 把当前全部正文里的深度模板（5-Whys、Comparative、Binary Search、Hypothesis-Driven；矩阵模板；证据采集步骤）标记为“按需展开”子节，并用“先读顶部 → 判定适用 → 再读对应方法”的入口指引。不删除任何既有内容，只重新分层。
3. **eval 验证小节（新加，每 skill 一个）**：
   - `debugging-root-cause-analysis`：自检“每个结论是否由可证伪假设 + 定向测试支持；是否解释了全部症状；能否预测何时不再出现；是否排除替代解释”。对应既有 Checklist 段（161 行文件内已有），升级为 eval 清单样式。
   - `test-coverage-matrix`：自检“决策树三项问题是否都答了；是否标注了服务端不可证项；每个客户端场景是否有截图/视频/日志证据要求”。对应既有 Decision Tree/Checklist。
   - `evidence-collection-standard`：自检“每个 claim 是否带时间戳/行号/文件名；visual 是否标注关键点；是否区分 server 证据与 client evidence；是否声明证据等级”。对应既有 Evidence Types/Common Mistakes。
   - 每个 eval 清单以“PASS/FAIL + 缺项修复”结束，可被 DSH required_skills gate 之外的 review 使用（不改 manifest 的 gate 语义）。

### 2.2 `evidence-collection-standard` 的双端证据落点

- 新增小节 `## Dual-End Evidence (server introspection + real client view)`，落点依据 supplement §3.6（DebugBridge：mapping-aware JVM 内检查+截屏/GUI structure）与 §3.7（VitaminMCP：真实协议客户端 + 服务端内省 + 断言）。
- 内容（方法论，不引用 tool 名承诺 Forge 支持）：
  1. 同一复现必须同时采集：**服务端内省**（entity state、packet recipient、tick、correlation id）与**真实客户端所见**（截图/10-15 秒视频、GUI/menu 可见状态、用户描述）。
  2. 两者矛盾 → 标 `EVIDENCE_CONFLICT`，不得以“服务端 PASS”覆盖客户端可见失败。
  3. 引用 Alice 既有教训：`ab510fd`“服务端物理正常”被客户端实测否定（progress-snapshot；HANDOVER R30/R36）；`test-coverage-matrix` 的 Server-Side Limitations 段落已列同类清单。
- 明确边界：DebugBridge/VitaminMCP **不支持 Forge 1.20.1，只取模式，不接入**（supplement §5.1 硬边界）。

### 2.3 `test-coverage-matrix` 的双端证据落点

- 新增小节 `## Dual-View Matrix` 或并入既有 `Client Test Matrix Template`：
  1. 每场景三列：`server event`（可观测状态）、`recipient/client-visible`（客户端应看到什么）、`evidence`（corr id、日志行号、截图/视频）。
  2. 决策树补一问：该行为是否依赖服务端→客户端的同步链路（packet recipient/dispatch）？是 → 客户端测试必需 + 需要双端证据（引用 FakeConnection 同步教训）。
  3. 保留既有 Test Pyramid 与 Server-Side Limitations（它们是本 skill 已批准语义，不得删除）。

### 2.4 `debugging-root-cause-analysis` 的保留内容核对

- 保留全部 4 种方法（5-Whys、Comparative、Binary Search、Hypothesis-Driven）、Anti-Patterns、Checklist、Integration with Alice Workflow、Example。
- 仅增加 frontmatter、入口分层与 eval 小结；**不改变任何已批准的方法语义**（这是 P0 的硬约束：不改变 skill 语义，只升级载体结构与证据方法）。

### 2.5 P0 不改的东西

- 不改 `skills-manifest.yml`（3 个条目保持 `maintainer-approved` v1.0.0、owner、scope、summary 不变）。
- 不改 `state-machine.yml`、`docs/SUPERVISION_PROTOCOL.md`、HANDOVER（除 P0-S6 的正常记录）。
- 不新增 skill 文件、不新增工具、不改业务代码。

---

## 3. P1 试点最短验证路径

### 3.1 第一步：DSH MCP 挂载能力验证（P1-S1）

**目标**：只判断“当前 DSH 部署（含 3083 工作流）能否挂载一个外部 stdio MCP server，且不破坏现有会话”，不接入真实 Git server。

**方法（依次尝试，任一通过即记录“待验证→可行”的等级）**：
- V-A（文档核对，零接触）：只读核查 DSH checkout 中 dsh-mcp-client 的接线点（cordis.yml 配置示例、插件注册、`ctx.tools` 注入）；核查是否有 dev-time / test-time 挂载路径或独立 profile 实例。
- V-B（隔离 dry-run）：在**不影响 3083 运行配置**的前提下（单独测试 profile / 临时 config 且可回滚），挂载一个极小 stdio MCP server（如官方 `@modelcontextprotocol/server-everything` 的只读部分或 echo 型 stdio server），观察工具是否以 `mcp__<serverName>__<rawName>` 出现在 `list_peers`/会话工具清单中。
- V-C（若 V-B 不可行）：在 3083 复现线程的最小变更下做一次“加载→观察→卸载”闭环，全程记录变更文件与回滚证据；若任何一步需要改动已安装 DSH checkout 或 profile node_modules → **立即停止，判定不可行**。

**判定标准**：
- 可行：外部 stdio server 工具可注册、可调用、名称合格（`mcp__<serverName>__<rawName>`），配置隔离可回滚，3083 原工作流无回归。
- 部分可行：仅隔离 profile 可行，3083 内不可行 → 试点限定隔离 profile。
- 不可行 / 需停止：挂载必须改已安装 DSH/部署环境，或启动后 3083 工具清单/会话异常 → 停止，报告“DSH 当前不支持安全挂载，P1 Git MCP 试点终止”。

**停止条件（P1-S1）**：
- 触碰已安装 DSH checkout（`/home/fb486/.nvm/.../@deepseek-ai/dsh/`）或 profile node_modules（`/home/fb486/.dsh/profiles/...`）；
- 需要重启/变更 3083 环境、或部署端口（3082/3083）受影响的任何步骤；
- 证据显示挂载会读取项目外的敏感路径（API key、Windows profile、token）。

### 3.2 第二步：只读工具试点（P1-S2/S3）

仅当 P1-S1 判定可行时：

- 挂载官方 Git MCP server（`modelcontextprotocol/servers` `src/git`；supplement §3.2，early development 明确标注），transport stdio（`uvx mcp-server-git` 或 npx，按官方 examples）。
- **工具白名单**：只允许 `git_status`、`git_diff_unstaged`、`git_diff_staged`、`git_diff`；`git_commit` 及其他写工具显式禁止（配置/接线层面不暴露或文档禁止）。
- **repo_path 白名单**：仅 `/home/fb486/projects/alice`（以及 Windows 侧 `D:\JAVA_projects\alice` 对应若试点在 Windows 侧）；不开放其他目录。
- **试点内容**：对 Alice 仓库跑 `git_status`、`git_diff_unstaged`、`git_diff_staged`，对照 `git -C /home/fb486/projects/alice status/diff` 命令输出，验证正确性与只读性（试点前后 `git status --short` 不变）。
- **验证输出**：工具返回的 JSON 与命令输出一致；未产生任何写操作；`git diff --check` 不受影响。

**判定标准**：只读工具输出与本地 `git` 命令一致；试点前后仓库无任何变化（`git status` 干净对照）；未出现 `git_commit` 可用面。

---

## 4. 风险与停止条件

| 阶段 | 风险 | 停止/处理条件 |
|---|---|---|
| P0 | 升级改变了已批准 skill 的方法语义（如删除既有模板、改写结论） | 停止；只允许结构/载体与双端证据方法新增，任何既有方法语义变更须回退并报告 |
| P0 | frontmatter `name` 与 manifest 不一致 | 停止；`name` 必须与 `skills-manifest.yml` id 完全一致 |
| P0 | 把 DebugBridge/VitaminMCP 写成“可接入工具” | 停止；supplement §5.1 硬边界：Fabric/Paper 版本不匹配 Forge 1.20.1，只作模式借鉴 |
| P0 | eval 清单被误认为 manifest gate（required_skills）已自动校验 | 澄清：eval 是方法论自检，不改 `skills-manifest.yml` 的 gate 语义 |
| P1 | DSH 无法安全挂载外部 stdio MCP（P1-S1 否决） | 停止 P1；报告“挂载未验证/不可行”，不强行接入 |
| P1 | 触碰已安装 DSH checkout、profile node_modules、3082/3083 部署 | 立即停止；P1 试点绝不能改部署环境 |
| P1 | 工具暴露 `git_commit` / 写能力、或 repo_path 白名单外目录可读 | 停止试点；只在白名单 + 只读子集内运行 |
| P1 | 试点改变 3083 现有工作流（工具清单、会话、HMR） | 停止；卸载试点，恢复原配置并复测 |
| P1 | `modelcontextprotocol/servers` git server 为 early development（列表/行为可变化） | 记录固定版本/commit（supplement 已给 `599dafc`），试点前再核对；失败即降级为“不使用” |
| 全局 | P0/P1 被当作实施授权或 Windows 客户端验收替代 | 本报告不构成实施授权；客户端验收仍按 SUPERVISION_PROTOCOL/evidence-collection-standard 执行 |

---

## 5. 客户端/用户验收矩阵

### P0（无客户端可见行为）

| 项 | 验收方式 | PASS 标准 |
|---|---|---|
| P0-A | 用户/监督员检查 3 个 skill 文件渲染与目录 | frontmatter 合法（YAML 可解析）、manifest 3 条 id 仍可匹配加载 |
| P0-B | 语义一致性核对 | 既有方法模板全部保留；新增内容只有 frontmatter/分层/eval/双端证据 |
| P0-C | 双端证据落点抽查 | evidence/test-coverage 中至少各一处显式“服务端内省 + 真实客户端所见”方法段，且标注模式借鉴边界 |
| P0-D | 无回归 | 不改业务代码；`git status` 只含本次文档变更；skill manifest/state-machine 未动 |

### P1（试点影响评估）

| 项 | 验收方式 | PASS 标准 |
|---|---|---|
| P1-A | 挂载验证（P1-S1） | 只读核查 + 隔离 dry-run 记录；未改动已安装 DSH/配置/端口 |
| P1-B | 只读工具试点（P1-S2） | `git_status`/`git_diff_*` 输出与 `git` 命令一致；无 `git_commit` 可用；仓库前后无变化 |
| P1-C | **对现有 3083 工作流的回归** | 试点前后 3083 会话工具清单、`list_peers`、任务派发均无差异；若存在差异 → 判影响，停止试点并回滚 |
| P1-D | 白名单收敛 | `repo_path` 仅 Alice 仓库；无项目外路径读取证据 |
| P1-E | 可卸载性 | 关闭/移除试用配置后，工具清单恢复原样；未留后台进程/localhost 监听（如适用） |

> 注意：P1 的“影响 3083”与“工具只读性”必须由**进行试点时在同一 3083 会话/工作流下观察**，证据以会话前后工具清单快照 + 仓库 `git status` 对照为准；不能只以“README 说 stdio”推定无影响。

---

## 6. 交付状态

- 报告路径：`.alice-supervision/research/skill-upgrade-p1-git-mcp-line-20260824.md`
- 本任务只读：未安装/连接任何工具、未修改任何文件（含 skill、manifest、state-machine、HANDOVER、业务代码）。
- 待验证项显式列出：**DSH 对 3083 工作流的 MCP 挂载能力未实测**（P1-S1 是试点第一闸门）；**Git MCP server early development**；**DebugBridge/VitaminMCP 与 Forge 1.20.1 不匹配**（仅模式借鉴）。
- P0 与 P1 均需监督员审核；P0 文档升级可在批准后立即执行（零成本），P1 需用户批准独立试点计划且先过 P1-S1。
- **本规划不构成实施授权。**