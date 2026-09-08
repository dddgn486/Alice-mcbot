# Alice Active Work Plan — DSH 工作流可靠性改造（P0 修订版）

- Plan ID: `20260824-dsh-workflow-reliability-v1`
- Status: APPROVED_FOR_IMPLEMENTATION（用户 2026-08-24 明确批准；监督员二审通过 + 用户批准，双门禁均满足）
- User approval: explicit approval received (2026-08-24) — 用户批准开始 P0 实施
- Created from: `.alice-supervision/dsh-maintainer-workflow-adjustment-spec-20260824.md`（架构监督员规格）+ `dsh-maintainer-next-action-20260824.md`（四项修订要求）+ 维护员五问答复
- Approved by: 待定（需用户明确批准后才允许修改 Harness；监督员二审已通过）
- Scope owner: Alice 项目监督员 + DSH 工作流维护员
- 修订记录: v1 初稿 → 依 next-action 四项修订（移出 contract_failures、改 checkout 开发路径、加 check_evidence_state、Skill gate 措辞改为 report 审计）→ 监督员二审通过（2026-08-24），追加门禁

> **本 plan 仅授权 DSH 基础设施（`dsh-agent-bus` 插件源码、profile 配置、维护工具）的改造，
> 不授权修改任何 Alice 业务代码（`src/main/java/...`）、不触碰 P1 物理修复、不改变现有
> task/flow/reviewer/watch 机制。**

---

## Objective

在 `dsh-agent-bus` 源码（DSH checkout 内）上，以**向后兼容**方式落地规格 §3/§4/§5/§6 的 P0 最小可靠性基础：

1. Skill manifest、审核状态、`required_skills`/`skill_usage` 可选字段与 `report_task` 前强制审计。
2. `evidence_state` 可选字段、展示、项目侧 `state-machine.yml` 与**只读**状态校验工具。
3. 调查结论 `confidence / evidence / limitations` 的结构化展示或模板校验。
4. `git_sync_check`、`record_emergency_sync`、紧急登记目录与派发警告。

## Why This Slice

- 这是规格 §10 阶段 1（P0，优先）的最小实现；全部以「可选字段 / 新工具 / 新项目侧文件」落地，不动 `ALLOWED_TRANSITIONS`、不动现有工具签名、不换插件。
- 复杂度可控：lab（3082）已有同路径 `ignored` 字段改造的兼容先例（spec v10→v11）。
- 验证成本低：3082 lab 行为测试 + 兼容回归即可；本 P0 无 Minecraft 客户端可见行为，无需客户端验收。

## Delivery Lane

- Lane: `maintenance`（基础设施维护，独立于主业务线）。
- Relationship to active blockers: `isolated`（不依赖 P1 物理修复，也不被其阻塞）。
- 为何不伤吞吐：改造只加可选字段/新工具/新项目文件，不触碰运行中的任务与 flow；现有 A1.1/P1 工作包不受影响。

## Allowed Scope（修订后唯一范围）

### 文件范围

**开发源（唯一）**：DSH checkout
`/home/fb486/.nvm/versions/node/v22.23.2/lib/node_modules/@deepseek-ai/dsh/`

> profile `node_modules`（`/home/fb486/.dsh/profiles/alice-bus/node_modules/dsh-agent-bus/`）**只作为部署目标或只读比对对象**，不作为开发源，不直接编辑。

- 插件源码（DSH checkout 内 dsh-agent-bus 对应源码路径）：
  `spec.js`（taskRecord 增可选字段）、`ledger.js`（skill 记录/evidence 读写/emergency 登记方法）、`tools.js`（report_task 审计、create_task/edit_task 扩展、新工具）、`panel.js`（展示）、`index.js`（USAGE_TEXT）。
- 项目侧新增（Alice 仓库内，非 Harness）：
  - `.alice-supervision/skills-manifest.yml`（或逐 skill manifest）
  - `.alice-supervision/state-machine.yml`（业务证据状态机定义）
  - `.alice-supervision/emergency/`（紧急操作登记目录 + 模板）
- 维护工具：`tools/` 下如需只读检查脚本，属项目侧。

### 行为变化（全部可选、缺省与现状一致）

1. `report_task`：当任务声明 `required_skills` 时，缺失已审核 Skill 使用记录则拒绝提交。记录字段：Skill ID、版本、加载/使用时间。例外只能由任务 initiator/reviewer 预先写入任务的 `skill_exceptions`，并包含原因、批准人和时间；执行者不能在 report 时自行新增例外。该记录是**可审计声明**，不是系统对 agent 实际阅读内容的不可伪造证明。
2. `create_task`/`edit_task`：向现有参数 schema **向后兼容地扩展可选字段**，可声明 `required_skills`（含 `min_version`）、`skill_exceptions`、`evidence_state`、`client_visible`；旧调用不受影响。
3. 新工具（只读或登记类，不改变现有任务生命周期）：
   - `check_required_skills(task_id)`（或 report 内联审计）；必须以项目侧 manifest 中 `maintainer-approved` 且版本满足 `min_version` 的 Skill 才能满足要求，`draft`/`deprecated`/`revoked` 均不得满足。
   - `check_evidence_state(task_id, from_state, to_state)`：读取项目侧 `.alice-supervision/state-machine.yml`，只校验显式提供的转换是否允许；对 `client_visible=true` 的工作包发现 `SERVER_VERIFIED -> USER_ACCEPTED` 时**报告违规**；只输出提示/审计结果，不写 evidence_state、不自行转状态、不替代监督员或用户裁决。
   - `git_sync_check()`：只读检查远端配置（Windows 工作树非 push 目标）、工作树 dirty、未记录手工复制标记。
   - `record_emergency_sync(...)`：登记紧急操作（源 commit、目标路径、SHA-256、操作者、原因、恢复计划），写 `.alice-supervision/emergency/`。

### 约束

- 所有新增字段必须可选，缺省时行为与现在完全一致（向后兼容硬性要求）。
- `ALLOWED_TRANSITIONS`、现有工具签名、ledger/flow/DAG 结构、`dsh-dafeiyu`、现有 task/flow/reviewer/watch 语义一律不动。
- 开发在 DSH checkout 完成 → 构建部署到 3082 lab 验证 → 向用户呈报 → 用户确认后按受控步骤部署 3083；禁止直接编辑 profile node_modules。
- 本 P0 **不包含** contract_failures 表、跨任务失败计数、1/2/3 自动升级（属规格 P1/阶段 2，留作后续独立工作包）。

## Explicitly Out Of Scope

- **不改 Alice 业务代码**（`src/main/java/...`、Forge 行为、P1 物理修复、FakeConnection、HARD_PATH、GUI 产品逻辑）。
- **不做**「working 前强制加载 Skill」的 agent-plane 硬阻塞（当前异步 followup 模型无 claim-before-working hook；采用 report_task 前审计替代）。
- 不把证据状态机硬编码进 DSH 通用层（由项目侧 `state-machine.yml` 定义，DSH 只存储/展示/只读校验）。
- 不做 Git 硬拦截（采用提示 + 只读策略检查 + 紧急登记）。
- **contract_failures / 自动失败升级 / list_contract_failures**（P1/阶段 2，后续独立工作包）。
- 不做 P2 的 GUI/DAG 展示增强与 incident report 导出。
- 不引入运行时 MCP、spark、Mixin；不把 MCP 当客户端验收替代品。
- 不替换/不重构 dsh-agent-bus 主体。

## Preconditions

- 监督员二审结论「通过，可转 APPROVED_FOR_IMPLEMENTATION」且用户批准 —— **未批准前不修改 Harness**。目前：监督员二审通过 + 用户已批准（2026-08-24），门禁满足。**注意：本 plan 是基础设施改造授权，不授权 Alice 业务代码/P1 物理修复。**
- 规格文档 `dsh-maintainer-workflow-adjustment-spec-20260824.md` 与 `dsh-maintainer-next-action-20260824.md` 为需求源。
- 3082 lab 可运行（已验证 0.1.1-rc.2 + bus 补丁兼容）。

## Research Decision

- Supervisor shallow research: 规格含验收矩阵（M1–M7）与现状依据；维护员已核对 bus 源码与 DSH checkout 结构确认可行性。足够本 plan 使用。
- Deep research: Not required。
- 采纳的结论：五问答复（report 审计替代 working 阻塞；项目侧定义状态机；checkout 开发 + 3082 先行；P1 项不混入 P0）。
- 证据等级边界：基于本地已安装 `dsh-agent-bus@0.1.1` 与 DSH checkout 源码（固定本地版本），不依赖外部变更。

## Implementation Steps

1. **DSH checkout 源码改造**：
   - `spec.js`：`taskRecord` 增加 `required_skills`/`skill_usage`/`evidence_state`/`client_visible` 可选字段（v10→v11 兼容，参照 `ignored` 先例）。
   - `ledger.js`：新增 skill 使用记录、evidence_state 读写、emergency 登记方法。
   - `tools.js`：`create_task`/`edit_task` 接受新可选字段；`report_task` 增加 required_skills 审计；新工具 `check_required_skills`、`check_evidence_state`、`git_sync_check`、`record_emergency_sync`。
   - `panel.js` / `index.js`：展示 evidence_state、skills 摘要；USAGE_TEXT 更新。
2. **项目侧文件**：起草 `skills-manifest.yml`、`state-machine.yml`、`emergency/` 目录与模板。
3. **构建入口发现与冻结**：在修改前，从 DSH checkout 的 `package.json`、workspace 配置、插件打包脚本和现有 3082 lab 部署记录中确定唯一构建命令、产物路径、版本标识方式；把实际命令与产物 SHA-256 写入 3082 验证记录。若 checkout 没有可重复构建/可部署路径，立即停止并交回维护员重规划，不以手工复制开发源替代构建。
4. **构建**：按已冻结的 DSH checkout 构建流程产出可部署产物，记录 checkout commit、包版本、构建时间与产物 SHA-256。
5. **部署 3082 lab**：仅部署上述已识别产物，完整行为与兼容验证（见验证矩阵），不触碰 3083。
6. **呈报用户**：3082 结果、构建标识、产物哈希、验证证据与回滚包。
7. **用户确认后部署 3083**：按受控步骤（备份 → 记录备份哈希 → 部署同一产物 → 重启 → 验证 → 回滚预案），并重启/验证现有 GUI。
8. **交接**：更新 HANDOVER，注明未触碰业务代码、构建/部署版本和 3083 部署记录。

## Client Test Entry

- Not required：本 P0 为 DSH 内部工具/数据层改造，无 Minecraft 客户端可见行为。
- 验证替代：3082 lab 行为测试（工具调用、状态机回归、数据兼容）+ 3083 部署后 state 检查。

## Verification Evidence（3082 lab 预期矩阵）

| 编号 | 场景 | 预期 |
|---|---|---|
| V1 | 声明 `required_skills` 的任务，report 缺失 Skill 使用记录 | report 被拒绝，提示登记满足版本的已审核 Skill |
| V2 | 补齐 Skill 使用记录后 report | 允许提交，任务行记录 Skill ID/版本/时间 |
| V3 | 执行者试图在 report 时自行新增 skill exception | report 被拒绝；只有 initiator/reviewer 预先登记的例外可被审计接受 |
| V4 | `draft`、`deprecated` 或 `revoked` Skill 被声明为 required | 校验拒绝，提示需 maintainer-approved 的有效版本 |
| V5 | 项目状态机非法转换（如 client_visible 工作包 `SERVER_VERIFIED -> USER_ACCEPTED`） | `check_evidence_state` 报告违规，不写字段、不自行转状态 |
| V6 | 合法转换 | `check_evidence_state` 输出允许，不阻塞 |
| V7 | 旧任务/旧 flow 读取 | 完整读取，无 schema 错误（向后兼容） |
| V8 | `git_sync_check` | 只读输出远端配置/dirty/紧急标记状态，不改任何文件 |
| V9 | `record_emergency_sync` | 写入 `.alice-supervision/emergency/`，字段完整，可被读取 |
| V10 | 3082 无 schema 错误且部署产物可追溯 | 日志无 zod/schema 报错；记录 checkout commit、构建命令、产物 SHA-256 |
| V11 | 3083 未被改动 | 部署前确认 3083 仍为原状态 |

## User Acceptance Gate

- 用户对 3082 lab 实验结果的确认（或提出调整）。
- 用户确认后才按受控步骤部署 3083。
- 本次为内部改造，无 Minecraft 客户端验收项。

## Handoff Rules

- 维护员更新 `docs/HANDOVER.md`（或 `.dsh-runtime/` 交接文档），记录变更、构建产物版本、部署/回滚步骤，注明未触碰业务代码。
- 任何发现超出本 plan Allowed Scope 的改动需求，停止实施，回到监督员重规划。
- 规格与 next-action 文档本身不授权修改 Harness；**本 plan 的 DRAFT 状态同样不授权**——必须监督员二审通过且用户批准后才动手。
