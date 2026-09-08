# DSH 工作流维护员调整清单

- 适用对象：DeepSeek Harness（DSH）工作流维护员
- 提出方：Alice 项目架构监督员
- 日期：2026-08-24
- 性质：工作流可靠性改造规格，不修改 Alice 业务代码
- 优先级：高

## 1. 背景与结论

Alice 当前的四角色分工、用户批准门禁、工作包、监督二审、客户端验收框架是正确的，不建议推倒重来。

但近期 Forge/Minecraft 开发暴露出若干流程缺口：

1. 服务端 fixture PASS 曾被误读为产品行为完成，Windows 客户端才发现物理与同步问题。
2. 深度调查曾将源码推断写成过强结论，缺少运行时和客户端证据等级约束。
3. Skill 工具存在，但没有“声明所需 Skill -> 开工前加载 -> 交付中记录使用结果”的强制闭环。
4. WSL 工作区、Windows 客户端工作副本、GitHub 远端与本地 Git 远端混用，导致同步、回退和手工复制源码等不可追溯路径。
5. 同一模块连续失败时缺少自动升级规则，导致 Bot inventory GUI 出现多轮局部修复后才冻结重审。

目标不是让流程更重，而是把已经存在的好原则做成系统可验证的约束：**能力先加载、证据分级、状态不可跳跃、失败及时升级、代码同步可追溯。**

---

## 2. 改造目标

### 必须实现

1. Skill 注册、版本、审核、任务路由与使用记录闭环。
2. 以证据为前提的功能状态机，禁止从服务端验证直接宣称客户端完成。
3. 调查结论证据等级，区分源码、运行时、客户端和假设。
4. Git/工作树/Windows 客户端副本的单一真源与安全回退路径。
5. 连续失败自动进入调查或架构重审，防止无限局部补丁。
6. 角色职责边界与紧急操作审计，避免监督员/调查员越权改业务代码或手工同步掩盖提交状态。

### 明确不做

- 不替换现有 dsh-agent-bus、task、flow、reviewer、supervisor_wait_watch 机制。
- 不把 DSH 变成项目业务实现框架。
- 不把 GameTest、MCP、Skill 或 agent 报告当作用户客户端验收替代品。
- 不自动批准架构扩展或实验能力接入稳定任务链。

---

## 3. P0：Skill 生命周期与强制路由

### 3.1 Skill Manifest 标准

每个可加载 Skill 应有机器可读 manifest，建议格式为 `SKILL.md` frontmatter 或相邻 `skill.json`。

```yaml
id: debugging-root-cause-analysis
version: 1.0.0
status: draft # draft | maintainer-approved | deprecated | revoked
owner: alice-supervision
scope: methodology # methodology | technical-domain | project-policy
summary: Structure root-cause analysis with falsifiable hypotheses.
applies_to:
  - investigation
  - incident-response
requires: []
conflicts: []
evidence_sources:
  - project-local
reviewed_by: null
reviewed_at: null
```

### 3.2 Skill 分类

必须支持至少三类：

| 类别 | 用途 | 示例 |
|---|---|---|
| `methodology` | 跨项目方法论 | 根因分析、测试矩阵、证据采集 |
| `technical-domain` | 技术域知识 | Forge FakePlayer、实体同步、Container Menu、GameTest |
| `project-policy` | 项目不可逾越边界 | Alice HARD_PATH/SOFT_SURFACE、服务端权威、客户端验收 |

### 3.3 Task / Active Plan 所需字段

对实现、调查、规划任务增加可选但可强制的字段：

```json
{
  "required_skills": [
    {"id": "debugging-root-cause-analysis", "min_version": "1.0.0"},
    {"id": "forge-entity-physics-collision", "min_version": "1.0.0"}
  ],
  "skill_policy": "required-before-work"
}
```

规则：

1. `required-before-work` 的任务在执行者成功调用/加载所有 `maintainer-approved` Skill 前，不允许进入 `working`。
2. 任务报告必须自动附带：Skill ID、版本、加载时间、是否有例外。
3. `draft` Skill 只能作为参考材料，不能满足 `required_skills`。
4. `deprecated` Skill 可显示警告但不得被新任务声明；`revoked` Skill 直接拒绝加载。
5. 维护员应能查看“某次失败是否未加载要求 Skill”的审计记录。

### 3.4 初始待审 Skill

维护员应审核并决定是否迁移/安装以下 Alice 初稿：

- `.alice-supervision/skills/debugging-root-cause-analysis.skill.md`
- `.alice-supervision/skills/test-coverage-matrix.skill.md`
- `.alice-supervision/skills/evidence-collection-standard.skill.md`

审核要求：

- 转换到正式 manifest 格式。
- 记录版本、owner、适用任务类型与维护者审核时间。
- 不允许“普通 Markdown 已存在”被误认为“Skill 已安装”。

---

## 4. P0：证据驱动状态机

### 4.1 功能/工作包状态

对有用户可见行为的功能，必须显式区分以下状态：

```text
PLANNING
APPROVED_FOR_IMPLEMENTATION
IMPLEMENTED
SERVER_VERIFIED
CLIENT_TEST_PENDING
USER_ACCEPTED
BLOCKED_BY_CLIENT_EVIDENCE
INVESTIGATION_REQUIRED
FROZEN
REJECTED
```

### 4.2 允许的状态转换

```text
PLANNING -> APPROVED_FOR_IMPLEMENTATION        (必须用户明确批准)
APPROVED_FOR_IMPLEMENTATION -> IMPLEMENTED     (代码提交/交付)
IMPLEMENTED -> SERVER_VERIFIED                  (计划规定的服务端验证通过)
SERVER_VERIFIED -> CLIENT_TEST_PENDING          (客户端可见/交互/同步/物理/GUI 场景)
SERVER_VERIFIED -> USER_ACCEPTED                (仅当任务无客户端可见行为且计划明确允许)
CLIENT_TEST_PENDING -> USER_ACCEPTED            (用户证据满足矩阵)
CLIENT_TEST_PENDING -> BLOCKED_BY_CLIENT_EVIDENCE (客户端失败)
BLOCKED_BY_CLIENT_EVIDENCE -> INVESTIGATION_REQUIRED
INVESTIGATION_REQUIRED -> PLANNING              (调查后重新规划)
任何状态 -> FROZEN / REJECTED                   (用户明确决定或停止条件)
```

禁止：

1. `SERVER_VERIFIED -> USER_ACCEPTED`，若功能涉及渲染、GUI、客户端输入、网络同步、实体物理、动画、多人可见状态。
2. 仅凭“编译通过”“日志正常”“headless PASS”写出“客户端验证通过”。
3. 未记录 Windows 证据目录、测试矩阵和失败回收条件时标记 `CLIENT_TEST_PENDING`。

### 4.3 客户端测试的强制字段

当任务被判定需要客户端测试时，计划必须包含：

```yaml
client_validation:
  required: true
  matrix_path: .alice-supervision/client-tests/<feature>.md
  evidence_root: .alice-supervision/client-tests/<feature>/evidence/
  required_artifacts:
    - latest.log
    - evidence-report.md
  optional_artifacts:
    - screenshot
    - video
  failure_recovery: stop-and-investigate
```

没有 `evidence_root` 时，不得把证据采集责任模糊转交给用户。测试说明应明确创建目录、文件名、日志关键字与截图/视频触发条件。

---

## 5. P0：调查报告的结论强度

### 5.1 每条关键结论必须声明等级

```yaml
claim:
  statement: FakeConnection drops movement packets sent to the bot connection.
  confidence: source-confirmed
  evidence:
    - path: src/main/java/.../FakeConnection.java
      lines: 35-37
  limitations:
    - Does not prove observer clients received or rendered updates.
```

允许等级：

| 等级 | 含义 | 可支持的说法 |
|---|---|---|
| `source-confirmed` | 源码、映射、反编译文本确认 | “代码包含/调用此逻辑” |
| `runtime-confirmed` | 断点、结构化日志、GameTest 或运行时状态确认 | “此运行时路径实际发生” |
| `client-confirmed` | Windows 客户端证据确认 | “用户可见行为如此” |
| `hypothesis` | 合理推断，尚待验证 | “可能/待证实” |

规则：

1. `source-confirmed` 不得写成“服务端行为已正常”。
2. `runtime-confirmed` 不得写成“客户端可见行为已正常”。
3. 架构决策依赖客户端物理、GUI、同步时，必须包含 `client-confirmed` 或明确保持 `CLIENT_TEST_PENDING`。
4. 审核器应拒绝缺少等级、证据路径或限制项的关键结论。

---

## 6. P0：Git、工作树与环境隔离

### 6.1 单一代码真源

建议固定：

- **GitHub（或受控中央远端）**：唯一代码真源。
- **WSL 开发工作树**：实现与提交位置。
- **Windows 工作树**：客户端测试副本，只从中央远端 `pull`，不得作为 WSL `push` 的接收端。
- **DSH runtime / agent bus**：运行时状态，默认不进入功能提交。

禁止把 Windows 客户端工作树配置为 WSL 的 `origin` push 目标。现有 Alice 多远端状态应迁移为明确名称，例如：

```text
upstream = git@github.com:<org>/<repo>.git      # 唯一代码发布远端
windows-test = <optional read-only local path>  # 不允许 push
```

### 6.2 回退与对比测试

禁止在主开发工作树或 Windows 常用客户端工作树使用 `git reset --hard` 进行回退测试。

必须使用以下之一：

1. `git worktree add ../alice-test-<sha> <sha>`；或
2. 专用临时分支 `test/bisect-<incident-id>`；或
3. CI/临时 clone。

回退测试记录必须含：

```yaml
incident_id: p1-client-physics
known_good: 65f4863
known_bad: ab510fd
test_environment: windows-client-worktree-<sha>
result: reproducible
restoration_commit: <sha>
```

### 6.3 手工复制文件

默认禁止手工复制源码跨 WSL/Windows 作为“同步完成”。

若因 Git 基础设施故障必须使用：

1. 标记为 `EMERGENCY_MANUAL_SYNC`。
2. 记录源 commit、目标路径、文件 SHA-256、操作者、原因、恢复计划。
3. 用户客户端测试结果不得在 Git 正常同步恢复前成为正式 acceptance 的唯一依据。

---

## 7. P1：失败阈值与自动升级

### 7.1 失败计数键

按 `module + objective + client-visible contract` 计数，而非按 commit 计数。

示例：

```text
module: bot-inventory-gui
objective: client-side pickup/place correctness
contract: viewIndex-to-logical-slot and server-authoritative action result
```

### 7.2 升级规则

| 条件 | 系统动作 |
|---|---|
| 同一契约客户端失败 1 次 | `BLOCKED_BY_CLIENT_EVIDENCE`，要求证据补全与窄修复计划 |
| 同一契约连续失败 2 次 | `INVESTIGATION_REQUIRED`；禁止继续直接派发实现补丁 |
| 同一契约连续失败 3 次 | `ARCHITECTURE_REVIEW_REQUIRED`；必须由监督员+用户决定替代方案、冻结或降级 |
| 失败推翻核心架构假设 | 立即 `INVESTIGATION_REQUIRED`，不等待计数阈值 |
| 用户明确暂停/冻结 | `FROZEN`，禁止自动续派 |

重做同一个 task id 可以保留生命周期，但失败计数必须累积；不得通过新建任务规避阈值。

---

## 8. P1：角色职责与紧急操作审计

### 8.1 正常职责

| 角色 | 允许 | 禁止 |
|---|---|---|
| 监督员 | 读证据、规划、派发、审核、状态转移、用户汇报 | 直接实施业务代码、隐式批准扩展 |
| 规划员 | 只读分析、最小实施路线、风险/停止条件/矩阵 | 改代码、把报告视为实施授权 |
| 调查员 | 只读源码/外部研究、证据等级、替代方案 | 改代码、把推断写成已验证事实 |
| 主开发 | 仅实施 active plan Allowed Scope、运行计划验证、交付 | 自行扩展范围、替代用户验收 |
| 用户 | 批准路线、执行 Windows 验收、最终产品决策 | 无限制 |

### 8.2 紧急模式

仅系统无法正常同步、用户明确要求救援、或环境故障阻塞调查时可进入紧急模式。

```yaml
emergency_operation:
  id: emergency-manual-sync-<timestamp>
  reason: Windows git worktree cannot read tree
  authority: user | maintainer
  affected_paths: []
  source_commit: <sha>
  verification: sha256 + compile/test
  expiration: <timestamp>
  followup: restore-normal-git-sync
```

紧急操作必须出现在 HANDOVER 和审核包，且不能成为常态流程。

---

## 9. P1：工具接入治理

### 9.1 分层

| 层 | 示例 | 默认规则 |
|---|---|---|
| 静态开发工具 | `minecraft-modding-mcp`、映射/源码浏览 | 可作为开发机/Agent 工具，审批缓存和权限 |
| 服务端测试工具 | Forge GameTest | 需计划批准，不能替代客户端验收 |
| 运行时调试 mod | `minecraft-mod-mcp`、spark、overlay | 独立 profile、加载/未加载对照、不得进入发布 runtime |
| 高风险字节码工具 | Mixin/AT | 只有公开 API/Event 无法完成窄目标时单独审批 |

### 9.2 工具登记字段

```yaml
name: minecraft-modding-mcp
version_or_commit: 2c673ad
license: MIT
layer: static-development-tool
runtime_inclusion: forbidden
network_binding: none
filesystem_access:
  - forge-gradle-cache-read
  - project-source-read
approval: pending-maintainer-review
rollback: remove-client-config-and-cache
```

所有工具接入必须记录版本/commit、许可证、权限、网络绑定、运行时影响、卸载步骤与验证矩阵。

---

## 10. 建议实施顺序

### 阶段 1：最小可靠性基础（P0，优先）

1. 引入正式 Skill manifest、审核状态与 task `required_skills` 审计字段。
2. 引入证据状态机及客户端测试必填字段。
3. 规定调查结论等级与审核拒绝规则。
4. 修正 Git 远端/Windows 工作副本策略，建立 worktree 回退模板。

**完成标准**：新建的 Alice 工作包能声明 Skill、无法跳过客户端状态、调查报告能区分证据等级、回退测试不再破坏主工作树。

### 阶段 2：失败升级和工具治理（P1）

1. 实现模块契约失败计数与自动升级提醒。
2. 实现紧急操作记录模板和审计展示。
3. 实现开发工具登记、权限与 runtime 隔离标识。

**完成标准**：连续失败不再能通过新建任务绕过重审；运行时调试 mod 不会被误加到产品环境。

### 阶段 3：体验与报告优化（P2）

1. 在 GUI/DAG 中展示 required Skills、证据等级和客户端验证状态。
2. 任务完成页显示缺失证据、未加载 Skill、失败阈值。
3. 生成可导出的 incident report（commit、环境、证据、决定、恢复路径）。

---

## 11. 验收矩阵（维护员）

| 编号 | 场景 | 预期 |
|---|---|---|
| M1 | 实现任务声明 required Skill | 未加载已审核 Skill 时不能开始工作；加载后任务记录版本 |
| M2 | Draft Skill 被声明为 required | 系统拒绝，提示需维护员审核 |
| M3 | GUI/物理任务服务端 fixture PASS | 只能进入 CLIENT_TEST_PENDING，不能直接 USER_ACCEPTED |
| M4 | 调查报告仅有源码证据 | UI/审核显示 source-confirmed，不能声明 client-confirmed |
| M5 | 同一契约连续两次客户端失败 | 自动标记 INVESTIGATION_REQUIRED，不再派发普通实现任务 |
| M6 | Windows 工作树 Git 同步失败 | 系统提示使用 worktree/紧急模式，不推荐手工复制 |
| M7 | 运行时调试工具登记 | 必须标记 profile/权限/卸载；不得默认进入发布 runtime |

---

## 12. 与 Alice 当前工作的衔接

当前 P1 物理问题处于暂停状态：`ab510fd` 产生稳定复现的玩家跳跃异常；`65f4863` 已确认 bot 无法被推挤/击退。P0 调试证据闭环已获用户批准，但仍待规划和独立实施授权。

维护员改造应优先保证下一轮 P1 诊断能满足：

1. 任务显式加载根因分析、测试矩阵和证据采集 Skill。
2. Forge GameTest/运行时日志记录服务端真实状态。
3. recipient packet 被明确记录，而非假定 `FakeConnection.send` 的效果。
4. Windows 测试有固定 evidence root、日志片段与物理视频要求。
5. 任何“物理正常”结论必须带 source/runtime/client 证据等级。

---

## 13. 维护员决策请求

请维护员先审核并回复：

1. Skill manifest 与 `required_skills` gate 是否能在 DSH 当前 task 模型中实现；如不能，最小替代设计是什么？
2. 证据状态机应由 DSH 通用层实现，还是允许项目配置定义状态与转换？
3. Git/worktree 约束能否以提示、策略检查或工具封装的方式实现？
4. 失败阈值的“模块/契约”身份模型应如何存储，避免仅按 task id 计数？
5. 哪些 P0 项可在不破坏现有 task/flow 兼容性的情况下先落地？

维护员审核通过后，应形成独立的 DSH 改造 active plan；该文档本身不授权直接修改 Harness。
