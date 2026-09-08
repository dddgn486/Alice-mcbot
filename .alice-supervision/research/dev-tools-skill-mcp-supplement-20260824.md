# Alice 开发辅助 Skill 包 / MCP 服务 / 开发工具补充调查

- **任务**：`.alice-supervision/research/dev-tools-skill-mcp-supplement-task-20260824.txt`
- **Alice 基线**：`ab510fd`（Forge 1.20.1-47.x，Java 17，ForgeGradle 6，Parchment）
- **日期**：2026-08-24
- **取证**：`web_search` + 官方 GitHub API/README/release + Modrinth/npm 注册表；全程只读，未安装、未下载、未连接任何工具或 MCP endpoint，未修改 Alice 代码/build.gradle/计划/HANDOVER。
- **先读的已有调查**：`dev-tools-mcp-survey-20260824.md`、`dsh-skill-integration-universal-packages-evaluation-20260824.md`、`dsh-skill-integration-forge-mod-dev-evaluation-20260824.md`、`project-refactor-survey-20260824.md`、`skills-manifest.yml`。

## 1. 执行摘要

增量发现：**没有发现对 Alice 当前 Forge 1.20.1 / FakePlayer 物理 / FakeConnection 同步问题可直接接入的新工具**；但发现了 **5 项有增量价值的候选**（官方 Git MCP server、Anthropic 官方 Agent Skills 方法论、`adhi-jp/agent-skills` 的 repair-ledger/eval 结构、`mcdev-mcp` 静态部分、Java LSP MCP server）和 **2 项可作为模式借鉴但版本/loader 不匹配**的候选（DebugBridge、VitaminMCP），以及 **2 项不推荐**（async-profiler 与已覆盖 spark 重叠、craftmcp 证据不足）。

一句话结论：**投入应放在 (a) 官方 Git MCP server 解决 Git 工作流、(b) Anthropic/adhi-jp 的 skill 方法论与 eval 结构完善 Alice 已批准 skill、(c) 把 DebugBridge/VitaminMCP 的"真实客户端能看到的证据"模式写入 Alice 测试方法论——而不是再接入另一个 Minecraft 运行时调试 mod**（现有已知候选均不支持 Forge 1.20.1）。

| 候选 | 优先级 | 一句话理由 |
|---|---|---|
| Anthropic 官方 Agent Skills 文章 | P0 方法论 | 权威、零成本，指导 SKILL.md / progressive disclosure / eval |
| 官方 Git MCP server（modelcontextprotocol/servers） | P1 试点 | 直接服务 Alice Git 工作流（status/diff），早开发阶段需试点 |
| `adhi-jp/agent-skills`（vibe-debug/repair-ledger + evals） | P1 借鉴 | 外部 skill 工程实践与 eval 套件，可与已批准 3 skill 互补 |
| `mcdev-mcp`（静态部分） | P1 对照 | 静态反编译+callgraph 有增量，但其运行时桥依赖 DebugBridge（不支持 Forge 1.20.1） |
| Java LSP MCP server（JDTLS symbols/diagnostics） | P2 试点 | 补"Alice 自身 Java 代码检索"增量，但处于 active development |
| DebugBridge（Fabric 客户端） | P1 模式借鉴 | mapping-aware JVM 内调试+截图/GUI 检查模式有价值，但只支持 Fabric 1.19/1.21.11/26.x |
| VitaminMCP（Paper 插件） | P1 模式借鉴 | 真实协议客户端+服务端内省双视角测试模式可借鉴，但只支持 Paper 1.21+ |
| MCP Inspector | P2 工具 | 官方 MCP 调试器，仅在 Alice 接入 MCP 后需要 |
| async-profiler | 不推荐 | 与已评估 spark 性能维度重叠 |
| craftmcp（anomalyco/minecraft-mcp npm） | 不推荐 | 证据弱、活跃度/稳定性未验证 |

## 2. 与已有调查的差异

### 已有覆盖（不重复）

**dev-tools-mcp-survey-20260824.md** 已覆盖：
- `adhi-jp/minecraft-modding-mcp`（静态源码/映射/Mixin/AT 校验，P0）
- `langyo/minecraft-mod-mcp`（运行中游戏 MCP bridge，含 1.20.1-forge release，P1）
- Forge GameTest、JDWP/IDE、spark、DataGen、Mixin、packet 观察；MCP 与 Minecraft Coder Pack 之辨。

**dsh-skill-integration-universal-packages-evaluation-20260824.md** 已覆盖：10 个通用 skill 设计（debugging-root-cause-analysis、failure-pattern-recognition、test-coverage-matrix、evidence-collection-standard、git-workflow-best-practices 等）。

**dsh-skill-integration-forge-mod-dev-evaluation-20260824.md** 已覆盖：5 个 Forge 专用 skill 设计（FakePlayer 生命周期、Entity 同步、Container Menu、物理碰撞、Forge Event、Headless 测试、客户端矩阵）。

**skills-manifest.yml** 已批准：debugging-root-cause-analysis / test-coverage-matrix / evidence-collection-standard（3 个）。

### 本调查新增（真实增量）

1. **官方 Git MCP server**（`modelcontextprotocol/servers` 的 `src/git`）。已有报告只提了"git-workflow-best-practices **skill 设计**"（方法论），本调查新增的是**官方 Git MCP 工具服务器**（git_status/git_diff_*/git_commit 等实际工具）——两者互补但不同，属于新增。
2. **Anthropic 官方 Agent Skills 工程文章**（2025-10-16）。已有 skill 评估是 DSH 内部设计，未引用 Anthropic 官方 SKILL.md 结构/progressive disclosure 标准；属于新增方法论依据。
3. **`adhi-jp/agent-skills`** 仓库（vibe-debug/vibe-commit/vibe-plan-execution + eval 套件 + repair-ledger fixture）。已有调查评估的是同一作者的 **MCP 服务器**（minecraft-modding-mcp），没有评估其 **skill 仓库**；属于新增。
4. **`mcdev-mcp`（use-ai-for-mc）**。已有报告未覆盖此项目；它同时提供静态反编译（Vineflower+SQLite callgraph）与运行时交互（DebugBridge）。属于新增，但与已覆盖的 `adhi-jp/minecraft-modding-mcp` 静态功能高度重叠，需对照。
5. **Java LSP MCP server（sunix/java-lsp-mcp-server）**。已有报告未覆盖"Alice 自身 Java 代码库"的 MCP 检索/诊断；属于新增。
6. **DebugBridge（use-ai-for-mc/debugbridge）**。已有报告提到了 `langyo/minecraft-mod-mcp`（运行时 bridge），未评估 DebugBridge；属于新增，但注意它只支持 Fabric 客户端版本。
7. **VitaminMCP（Backas03/VitaminMCP-minecraft）**。已有报告未覆盖；属于新增（Paper 1.21+，loader 不匹配）。
8. **MCP Inspector**（modelcontextprotocol 官方调试器）。已有报告未覆盖；属于新增工具，但依赖 MCP 接入后才能发挥价值。
9. **async-profiler**：属"确认不新增"（与 spark 重叠，避免重复包装）。
10. **craftmcp**：属"证据不足不推荐"。

## 3. 候选清单（含证据）

### 3.1 Anthropic 官方 Agent Skills 工程文章（P0 方法论，零成本）

- 官方来源：[Anthropic Engineering - Equipping agents for the real world with Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)（2025-10-16）
- 证据：官方文章定义 skill 为含 `SKILL.md`（YAML frontmatter：`name`/`description`）的目录；progressive disclosure（系统提示只预载 name/description、按需读取正文、可再引用额外文件）；强调 eval。
- 适用性：指导 Alice 已批准 3 个 skill 与未来 skill 的结构化、分层与评估；补 universal/forge 评估中未引用的官方标准。
- 接入门槛：零（只读方法论，不装工具）；学习曲线低（文章 1 小时）。
- 风险：无。
- 优先级：P0（先作为 skill 维护标准写入项目方法论；不构成实施授权）。

### 3.2 官方 Git MCP server（P1 试点）

- 官方来源：[modelcontextprotocol/servers（src/git）](https://github.com/modelcontextprotocol/servers/tree/main/src/git)；官方示例页 [Example Servers](https://modelcontextprotocol.io/examples)
- 证据：仓库 2026-08-24 仍活跃（latest commit `599dafc` 2026-08-18）；git server README 列出 `git_status`/`git_diff_unstaged`/`git_diff_staged`/`git_diff`/`git_commit` 等工具，并声明"currently in early development"。
- 适用性：直接服务 Alice Git 工作流缺口（HANDOVER/progress-snapshot 反复记录：治理提交混入功能提交、Windows 回退测试 `unable to read tree`）。agent 可通过它读取仓库状态/差异，辅助提交拆分与回退对比。
- 与已有差异：dev-tools/mcp survey 只设计过 git-workflow **skill**（方法论），未评估过 **Git MCP 工具服务器**。
- 接入门槛：需确认 DSH 的 MCP client seam（`@deepseek-ai/dsh-mcp-client` 存在于 DSH 依赖中）能否挂载外部 stdio MCP server——**待验证**；配置简单（`uvx mcp-server-git` 或 npx，经官方 examples 提供）；学习低。
- 风险：早开发阶段（工具列表/行为可能变化）；需白名单 `repo_path`（只给 Alice 仓库路径）；**禁止用其自动 commit**（提交归属仍按监督协议）。
- 优先级：P1 试点；验证 DSH MCP 挂载后再考虑。

### 3.3 `adhi-jp/agent-skills`（P1 借鉴）

- 官方来源：[adhi-jp/agent-skills](https://github.com/adhi-jp/agent-skills)
- 证据：MIT；活跃（2026-08-15 last commit `0397e6c`）；包含 `vibe-debug`（repair-ledger / retest contract）、`vibe-commit`、`vibe-plan-execution`、`vibe-writing`、`minecraft-modding-workbench` 等 skill，以及 `evals/skill-eval`、`evals/vibe-debug/fixtures`（closure-commit、history-consent、repair-ledger.md）等 eval 套件。
- 适用性：其 **repair-ledger / 证据背书 + 复测契约** 与 Alice 已批准 `debugging-root-cause-analysis` 互补；eval 驱动结构可借鉴到 Alice 已批准 3 skill 的验证方法。`minecraft-modding-workbench` 覆盖 Fabric/NeoForge/Architectury（README 表述），**未列 Forge**，对 Alice Forge 1.20.1 适用性有限——只借鉴其 skill 结构，不直接采用其 Minecraft 内容。
- 接入门槛：低（读仓库、摘方法论）；不安装。
- 风险：外部 skill 的指令边界/维护状态需按 Alice 规范另行审核；不构成自动授权。
- 优先级：P1 借鉴（作为 skill 维护与 eval 设计的参考样例）。

### 3.4 `mcdev-mcp`（P1 对照，仅静态部分）

- 官方来源：[use-ai-for-mc/mcdev-mcp](https://github.com/use-ai-for-mc/mcdev-mcp)
- 证据：仓库活跃（2026-08-18 pushed；last commit `7b98bdb` 2026-06-30）；README 提供静态反编译源码访问（Vineflower）+ SQLite callgraph + 运行时工具（mc_snapshot/mc_screenshot/mc_screen_inspect/mc_nearby_entities/mc_execute/会话控制）经 DebugBridge 桥。
- **版本边界（关键）**：其运行时桥依赖 [DebugBridge](https://github.com/use-ai-for-mc/debugbridge)，而 DebugBridge README 明确是 **Fabric 客户端 mod**，支持 **1.19.x / 1.21.11 / 26.1 / 26.2**——**不支持 Forge，不支持 1.20.1**。因此其对 Alice 的运行时部分**不适用/待未来版本验证**；静态反编译+callgraph 部分与已覆盖 `adhi-jp/minecraft-modding-mcp` 功能高度重叠。
- 许可证：GitHub API 显示 `NOASSERTION`（未明确声明）——**接入前必须先确认 license**，这是否决级风险。
- 适用性（严格限定）：只在"确认 license + 确认能反编译 Forge 1.20.1/Parchment 依赖"后，作为静态源码检索的**对照**候选；不接入运行时。
- 优先级：P1 对照（与已有 minecraft-modding-mcp 对比后二选一），当前不改动 Alice 任何设置。

### 3.5 Java LSP MCP server（P2 试点）

- 官方来源：[sunix/java-lsp-mcp-server](https://github.com/sunix/java-lsp-mcp-server)
- 证据：仓库 README 声明基于 Quarkus MCP HTTP transport + LSP4J + Eclipse JDT-LS；提供 `initializeWorkspace`、`getSymbols`（documentSymbol）、`getDiagnostics`（publishDiagnostics）、`formatCode`；"currently under active development"；自动下载 JDTLS（`jdtls.auto.download`）。
- 适用性：补"Alice 自身 Java 源码检索/诊断"增量（任务要求第 21 行"Java 项目源码检索 MCP"）；可提供 Alice 类/方法符号与编译诊断的 MCP 检索面。
- 与已有差异：已有 MCP 候选都面向 Minecraft 源码；本候选面向 **Alice 项目本身**。
- 接入门槛：中等（需要 JDTLS 下载、workspace 初始化、DSH MCP 挂载待验证）；学习低。
- 风险：active development（稳定性未定）；自动下载外部 JDTLS 有网络/供应链面（**禁止任务期间下载，接入须监督员批准**）；license 未在输出中确认（README 未见明确 license 声明）——同样需先确认。
- 优先级：P2 试点（等待稳定与 license 确认后）；当前不接入。

### 3.6 DebugBridge（P1 模式借鉴，不接入）

- 官方来源：[use-ai-for-mc/debugbridge](https://github.com/use-ai-for-mc/debugbridge)
- 证据：MIT；活跃（2026-08-24 last commit `9eb5052`）；README 明确 Fabric 客户端 mod、支持 1.19.x / 1.21.11 / 26.1 / 26.2；localhost WebSocket（默认 9876-9886）；mapping-aware Groovy 注入（Mojang 名→intermediary 名）；game snapshot、截图/录屏、GUI structure、chat history、会话控制（默认 gate off）。
- 适用性（Alice）：**不可直接接入**（Fabric + 版本不匹配 Forge 1.20.1）；但其"在 JVM 内以 mapping-aware 方式检查实体/bot 状态 + 截屏/GUI 结构 + 双端证据"正是 Alice FakePlayer 物理/GUI 失败/双端证据缺口的**模式参考**。
- 优先级：P1 模式借鉴（把该方法论记录到 Alice 测试/证据标准）；它本身不属于可接入候选，除非未来发布 Forge 1.20.1 支持（届时重新评估）。

### 3.7 VitaminMCP（P1 模式借鉴，不接入）

- 官方来源：[Backas03/VitaminMCP-minecraft](https://github.com/Backas03/VitaminMCP-minecraft)
- 证据：MIT；活跃（2026-08-23 last commit `d340197`）；README 明确是 **Paper/Purpur 服务端插件**，支持 **1.21-1.21.11**；功能：真实协议客户端 bot（非 mock Player）+ 服务端事件/日志/异常/权限读取 + 玩家屏幕读取（menus/chat/actionbar/titles/bossbar/scoreboard）+ 断言（blocks/players/events/inventory/messages）+ MCP endpoint。
- 适用性（Alice）：**不可直接接入**（Paper + 1.21+，Alice 是 Forge 1.20.1）；但其"真实协议客户端能看到什么 + 服务端内省 + MCP"的双视角正是 FakeConnection 同步验证（T4a/T4b）需要的**测试模式参考**（真实客户端所见 = 客户端验收层；服务端内省 = GameTest/dev observer 层）。
- 优先级：P1 模式借鉴（纳入 test-coverage-matrix skill 的"双端证据"矩阵设计）；不接入。

### 3.8 MCP Inspector（P2）

- 官方来源：[MCP Inspector - Model Context Protocol](https://modelcontextprotocol.io/docs/2026-07-28/tools/inspector)
- 证据：官方文档（2026-07-28 版）。
- 适用性：调试/验证 MCP 服务器连接本身；**仅在 Alice 接入任一 MCP 服务器后**有增量价值（验证 3.2/3.4/3.5 的挂载）。当前无 MCP 接入 → P2。
- 优先级：P2（跟随 MCP 接入一起评估）。

### 3.9 async-profiler（不新增）

- 官方来源：[async-profiler/async-profiler](https://github.com/async-profiler/async-profiler)。JVM 采样 profiler。
- 结论：与已评估 **spark**（Minecraft 性能分析维度）重叠；Alice 当前问题链（物理/同步/GUI 正确性）不是性能维度。**不新增**，避免与 spark 重复包装。

### 3.10 craftmcp（不推荐）

- 来源：npm [`craftmcp`](https://www.npmjs.com/package/craftmcp)（version 1.0.2，MPL-2.0，描述"MCP server for Minecraft mod debugging - launch, download, and run Minecraft through LLM tools via stdio"，repository 指向 anomalyco/minecraft-mcp）。
- 证据不足：GitHub 仓库元数据未能确认（活跃度/版本支持不明）；"下载并运行 Minecraft"暗示重度客户端工作流，与 Alice 只读调查/开发机工具定位不符。
- 结论：**不推荐**；证据充分前不进入候选。

## 4. 针对 Alice 当前问题链的映射

| Alice 问题链 | 直接可接入候选 | 模式借鉴候选 | 说明 |
|---|---|---|---|
| FakePlayer 物理（tick/travel/push/hurt 证据） | 无（新候选均不支持 Forge 1.20.1 运行时） | DebugBridge（mapping-aware JVM 内检查）、mcdev-mcp 静态反编译（对 Forge 源码）+ 已有 GameTest/JDWP | 运行时调试仍走已有 GameTest + 断点 + Windows 客户端矩阵；新候选只在未来版本对齐时重新评估 |
| FakeConnection 同步 / packet 追踪 | 无 | VitaminMCP（真实协议客户端所见 + 服务端内省双视角） | 建议把"真实客户端可见证据"写入 test-coverage-matrix skill；仍以 Windows 用户实测为验收层 |
| GUI 8 轮失败 | 无 | DebugBridge（GUI structure/截图）、Java LSP MCP（符号检索辅助协议分析） | GUI 主路径仍是无新依赖的 versioned view model + contract（见 project-refactor-survey）；新候选只作观察模式参考 |
| 双端证据 | 官方 Git MCP（仓库状态可审计） | VitaminMCP（双视角断言）、DebugBridge（截图/录屏） | 证据采集标准 skill 可扩展"运行中状态快照"方法 |
| Git 工作流 | 官方 Git MCP server（status/diff） | adhi-jp/agent-skills 的 vibe-commit（提交规范） | 治理提交混入功能提交、回退 `unable to read tree` 的直接工具候选；先试点只读 status/diff |
| Skill/方法论 | Anthropic 官方 Agent Skills 文章、adhi-jp/agent-skills 的 repair-ledger + eval | — | 完善已批准的 3 个 skill 的结构与验证方式 |

## 5. 限制与风险

1. **版本/loader 不匹配是硬边界**：DebugBridge（Fabric 1.19/1.21.11/26.x）、VitaminMCP（Paper 1.21+）、mcdev-mcp 运行时（依赖 DebugBridge）、craftmcp（未知）——**均不支持 Forge 1.20.1 运行时接入**。任何"这些工具能帮助 Alice 物理问题"的表述都限于模式借鉴，不能作为实施依据。
2. **license 未明确**：mcdev-mcp（NOASSERTION）、java-lsp-mcp-server（README 未见明确 license）——接入前必须先确认；不做授权推定。
3. **MCP 读取本地仓库/工作区风险**：Git MCP 需要 `repo_path` 白名单；任何 MCP 都不得读取 API 密钥、Windows 用户路径、Alice 源码以外的数据；只绑定 localhost。
4. **运行时工具改变时序**：运行中调试 mod/桥会改变启动、事件、网络或 GUI 时序（已有 `langyo/minecraft-mod-mcp` P1 试点已说明此风险）；新候选若未来可用，同样必须独立 runClient profile + 加载/未加载对照。
5. **早开发阶段**：官方 Git MCP server 明确 early development；java-lsp-mcp-server active development——均标注"待验证"。
6. **DSH MCP 挂载未知**：`@deepseek-ai/dsh-mcp-client` 依赖存在，但会话级外部 MCP 挂载能力未实测——Git MCP 等候选的接入可行性待试点验证。
7. **客户端验收不可替代**：任何服务端/工具/日志证据都不替代 Windows 客户端实测（AGENTS.md 与环境约束已固定）。
8. **报告不构成实施授权**：所有接入均需监督员审核 + 用户批准新 active plan。

## 6. 结论

**P0（方法论，零成本，可立即纳入项目文档/ skill 维护）**：
- Anthropic 官方 Agent Skills 工程文章（SKILL.md 结构、progressive disclosure、eval）作为 Alice skill 设计与维护的权威参考。
- 把 DebugBridge/VitaminMCP 的"运行中状态快照 + 真实客户端所见 + 服务端内省"双视角写入 `evidence-collection-standard` / `test-coverage-matrix` 的方法论建议。

**P1（试点/借鉴，需 DSH 挂载验证与监督员批准）**：
- 官方 Git MCP server：试点只读 `git_status`/`git_diff_*` 解决提交混入与回退对比；**禁止自动 commit**。
- `adhi-jp/agent-skills`：借鉴 repair-ledger/eval 结构改进已批准 3 skill 的验证。
- `mcdev-mcp` 静态部分：与已有 `adhi-jp/minecraft-modding-mcp` 对照（先确认 license），二选一作为静态源码/映射检索工具。

**P2（条件性试点）**：
- Java LSP MCP server（稳定 + license 确认后）：Alice 自身 Java 源码符号/诊断检索。
- MCP Inspector：仅在 Alice 正式接入任一 MCP 后使用。

**不推荐 / 无额外候选**：
- async-profiler（与 spark 重叠，不新增）；craftmcp（证据不足）。
- 对 Alice 当前 FakePlayer 物理 / FakeConnection 同步 / GUI 失败问题链：**无新工具可直接接入**；增量主要在 Git 工作流、skill 方法论与测试模式借鉴。已有覆盖（GameTest/JDWP/minecraft-modding-mcp/GameTest 层）依然是这些问题的首选工具集。

---

**本报告不构成实施授权**；未修改 Alice 代码、build.gradle、计划或 HANDOVER；未安装/下载/连接任何工具或 MCP endpoint。