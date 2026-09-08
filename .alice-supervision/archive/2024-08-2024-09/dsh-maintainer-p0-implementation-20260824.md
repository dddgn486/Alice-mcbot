# 监督员授权：DSH 工作流可靠性改造 P0 实施（用户已批准）

- 日期：2026-08-24
- 对接方：DSH 工作流维护员（隔离进程）
- 授权来源：`dsh-workflow-reliability-plan-20260824.md` 已转 `APPROVED_FOR_IMPLEMENTATION`（用户 2026-08-24 批准；监督员二审通过 + 用户批准双门禁满足）
- 前置状态：基线 JSX 类型修复已完成（`dsh-baseline-jsx-fix-result-20260824.md`，二审通过），开发源 `/home/fb486/projects/dsh-agent-bus`（baseline-npm-0.1.1 @ 105b9df）已可重复构建

---

## ⚠️ 重要边界（不可逾越）

**本 P0 只授权 DSH 基础设施（`dsh-agent-bus` 源码 dsh-agent-bus 对应插件源码、项目侧配置、维护工具）的改造。不授权任何 Alice 业务代码（`src/main/java/...`）、不触碰 P1 物理修复/FakeConnection/HARD_PATH/GUI 产品逻辑。**

## 本 P0 的实施范围（全部可选字段 / 新工具 / 项目侧新增文件）

### 1. 插件源码（开发源 `/home/fb486/projects/dsh-agent-bus/src/`，注意是 TS 源码，非 lib/ 产物）

- `src/spec.js`（对应 TS `src/spec.ts`）：taskRecord 增加可选字段 `required_skills`/`skill_usage`/`evidence_state`/`client_visible`（参照 v10→v11 `ignored` 兼容先例）
- `src/ledger.js`（`ledger.ts`）：新增 skill 使用记录、evidence_state 读写、emergency 登记方法
- `src/tools.js`（`tools.ts`）：`create_task`/`edit_task` 接受新可选字段；`report_task` 增加 required_skills 审计；新工具 `check_required_skills`、`check_evidence_state`、`git_sync_check`、`record_emergency_sync`
- `src/panel.js`（`panel.ts`）/ `src/index.js`：展示 evidence_state、skills 摘要；USAGE_TEXT 更新

### 2. 项目侧新增（Alice 仓库 `/home/fb486/projects/alice/` 内，非 Harness）

- `.alice-supervision/skills-manifest.yml`（或逐 skill manifest）
- `.alice-supervision/state-machine.yml`（业务证据状态机定义）
- `.alice-supervision/emergency/`（紧急操作登记目录 + 模板）

### 3. 行为变化（全部可选，缺省与现状一致 —— 向后兼容硬性要求）

- `report_task`：任务声明 `required_skills` 时，缺失已审核 Skill 使用记录则拒绝提交；记录 Skill ID/版本/时间。例外只能由 initiator/reviewer 预先写入 `skill_exceptions`（含原因/批准人/时间），执行者不得自行新增。这是可审计声明，不是不可伪造证明。
- `create_task`/`edit_task`：向后兼容扩展可选字段 `required_skills`（含 min_version）、`skill_exceptions`、`evidence_state`、`client_visible`；旧调用不受影响。
- 新工具（只读或登记类）：
  - `check_required_skills(task_id)`：必须以项目侧 manifest 中 `maintainer-approved` 且版本满足 min_version 的 Skill 才能满足；`draft`/`deprecated`/`revoked` 不得满足。
  - `check_evidence_state(task_id, from_state, to_state)`：读项目侧 `state-machine.yml`，只校验显式转换；对 `client_visible=true` 发现 `SERVER_VERIFIED -> USER_ACCEPTED` 时报告违规；只输出提示，不写 evidence_state、不自行转状态、不替代监督员/用户裁决。
  - `git_sync_check()`：只读检查远端配置、工作树 dirty、未记录手工复制标记。
  - `record_emergency_sync(...)`：登记紧急操作（源 commit、目标路径、SHA-256、操作者、原因、恢复计划），写 `.alice-supervision/emergency/`。

### 4. 约束（硬性）

- 所有新增字段必须可选，缺省时行为与现在完全一致（向后兼容）。
- `ALLOWED_TRANSITIONS`、现有工具签名、ledger/flow/DAG 结构、`dsh-dafeiyu`、现有 task/flow/reviewer/watch 语义一律不动。
- **不做**：working 前强制加载 Skill 的 agent-plane 硬阻塞；把证据状态机硬编码进 DSH 通用层（由项目侧 state-machine.yml 定义）；Git 硬拦截；`contract_failures`/自动失败升级/`list_contract_failures`（属 P1/阶段 2，后续独立工作包）；P2 GUI/DAG 展示增强与 incident report 导出；运行时 MCP、spark、Mixin。

## 构建与部署路径（plan 步骤 3-7）

1. **构建入口冻结**：在修改 P0 前，从开发源 `package.json`、workspace 配置、插件打包脚本确定唯一构建命令、产物路径、版本标识方式，记录到 3082 验证记录。若无可重复构建/可部署路径，**立即停止并交回监督员重规划**，不以手工复制开发源替代构建。
2. **开发 + 构建**：在开发源实施上述 P0 改动 → 按冻结的构建流程产出可部署产物，记录 checkout commit、包版本、构建时间、产物 SHA-256。（注意：基线 JSX 修复已改用 registry 版本 peer；P0 改动在 `src/*.ts` 用 TS 源码，构建后产出 `lib/`。）
3. **部署 3082 lab**：仅部署已识别产物，完成 V1-V11 完整行为与兼容验证（见下），不触碰 3083。
4. **呈报用户**：3082 结果、构建标识、产物哈希、验证证据、回滚包。
5. **用户确认后部署 3083**：按受控步骤（备份 → 记录备份哈希 → 部署同一产物 → 重启 → 验证 → 回滚预案）。**未获用户确认前不部署 3083。**
6. **交接**：更新 HANDOVER（或 `.dsh-runtime/` 交接文档），注明未触碰业务代码、构建/部署版本和 3083 部署记录。

## 3082 lab 预期验证矩阵（plan §Verification Evidence）

| 编号 | 场景 | 预期 |
|---|---|---|
| V1 | 声明 required_skills 的任务，report 缺失 Skill 使用记录 | report 被拒绝，提示登记满足版本的已审核 Skill |
| V2 | 补齐 Skill 使用记录后 report | 允许提交，任务行记录 Skill ID/版本/时间 |
| V3 | 执行者试图 report 时自行新增 skill exception | report 被拒绝；只有 initiator/reviewer 预先登记的例外可被接受 |
| V4 | draft/deprecated/revoked Skill 被声明为 required | 校验拒绝，提示需 maintainer-approved 的有效版本 |
| V5 | 项目状态机非法转换（client_visible 工作包 SERVER_VERIFIED -> USER_ACCEPTED） | check_evidence_state 报告违规，不写字段、不自行转状态 |
| V6 | 合法转换 | check_evidence_state 输出允许，不阻塞 |
| V7 | 旧任务/旧 flow 读取 | 完整读取，无 schema 错误（向后兼容） |
| V8 | git_sync_check | 只读输出远端配置/dirty/紧急标记状态，不改任何文件 |
| V9 | record_emergency_sync | 写入 `.alice-supervision/emergency/`，字段完整可读 |
| V10 | 3082 无 schema 错误且部署产物可追溯 | 日志无 zod/schema 报错；记录 checkout commit、构建命令、产物 SHA-256 |
| V11 | 3083 未被改动 | 部署前确认 3083 仍为原状态 |

## 实施产出（维护员提交给监督员）

1. P0 改动 diff（`src/*.ts` 源码 + 项目侧文件），标注每个改动对应 plan 的哪一项。
2. 构建证据：构建命令、checkout commit、产物 SHA-256、V1-V11 验证结果。
3. 3082 lab 部署记录与回滚包说明。
4. 一份结果报告写到 `.alice-supervision/dsh-p0-implementation-result-20260824.md`。

## 到下一审批点

1. 维护员完成 P0 实施 + 3082 lab 验证 + 产出结果报告。
2. 监督员核对：P0 改动符合 plan Allowed Scope、可选字段向后兼容、未碰业务代码、V1-V11 达预期。
3. 监督员**报请用户**：是否批准部署 3083。
4. **用户批准前不部署 3083；plan 当前已 APPROVED_FOR_IMPLEMENTATION，但业务代码与 Harness 主模块仍不可触碰。**

---

**本授权针对 DSH 基础设施 P0 实施。若实施中发现任何超出 plan Allowed Scope 的改动需求，立即停止并回到监督员重规划。**
