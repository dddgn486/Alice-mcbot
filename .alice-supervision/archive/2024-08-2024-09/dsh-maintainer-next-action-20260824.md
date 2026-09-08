# 给 DSH 工作流维护员：下一步执行说明

你正在处理的是 **DSH 工作流可靠性改造**，不是 Alice 业务功能开发。请不要修改 Alice 的 `src/main/java/...`、P1 物理、FakeConnection、HARD_PATH、GUI 产品逻辑，也不要把运行时 MCP、spark 或 Mixin 加入本轮。

## 先按顺序阅读

1. `.alice-supervision/dsh-maintainer-workflow-adjustment-spec-20260824.md`
   - 这是监督员提出的完整可靠性改造规格。重点看第 3-10 节和第 13 节维护员决策请求。
2. `.alice-supervision/dsh-workflow-five-questions-answer-20260824.md`
   - 这是你已完成的五问可实现性答复，已获监督员认可其总体方向。
3. `.alice-supervision/dsh-workflow-reliability-plan-20260824.md`
   - 这是当前 DRAFT 改造计划。**尚未授权改 Harness**，需要先按下方“必须修订”修改。
4. `.alice-supervision/progress-snapshot-20260824.md`
   - Alice 当前暂停点：P1 物理/同步问题；它是本次可靠性改造要避免再次发生的案例，但不是本轮修复目标。
5. `.alice-supervision/research/dev-tools-mcp-survey-20260824.md`
   - P0 工具调查结论：静态 MCP、GameTest、双端证据；仅供理解工作流与工具边界。
6. `.alice-supervision/research/project-refactor-survey-20260824.md`
   - 全项目重构调查结论：不直接替换 Alice 为 Baritone/Carpet/Mineflayer/TLM；保持领域语义，先建立可证伪证据闭环。

## 已获用户批准的方向

用户批准的是 **DSH 工作流可靠性 P0**，目标是提升后续工作包的 Skill 审计、证据状态、调查结论等级、Git/worktree 约束与紧急操作可追溯性。

用户没有批准：
- Alice 产品代码重构或 P1 物理修复；
- 运行时 `minecraft-mod-mcp` bridge、spark、Mixin；
- “working 前” agent-plane Skill 硬阻塞；
- 对现有 task/flow/DAG/`ALLOWED_TRANSITIONS` 的破坏性重写。

## 当前 plan 必须先修订的四点

请只修改 DRAFT plan，保持 DRAFT；修订后交监督员复核，不要直接修改 Harness。

### 1. 从本次 P0 移出 contract_failures 和自动失败升级

`contract_failures`、跨任务失败计数、1/2/3 自动升级属于规格的 **P1 / 阶段 2**，不属于本次最小 P0。

因此从当前 plan 的 Allowed Scope、行为变化、实现步骤、验收中移除：
- 新表 `contract_failures`
- `list_contract_failures`
- settle failure/failed 时累积并升级
- 自动 `BLOCKED_BY_CLIENT_EVIDENCE` / `INVESTIGATION_REQUIRED` / `ARCHITECTURE_REVIEW_REQUIRED`

这些应留作后续独立工作包。不要因为它们“可选字段兼容”就提前混入 P0。

### 2. 修正 Harness 开发与部署路径

不得直接编辑生产/已安装 profile 的：

```text
/home/fb486/.dsh/profiles/alice-bus/node_modules/dsh-agent-bus/lib/
```

请把 plan 改为：

1. 在 DSH checkout 的对应源码/插件源完成改造：
   `/home/fb486/.nvm/versions/node/v22.23.2/lib/node_modules/@deepseek-ai/dsh/`
2. 构建并部署到 **3082 lab**，完成完整行为与兼容验证。
3. 向用户呈报 3082 结果。
4. 仅在用户确认后，按受控部署步骤应用到 **3083**，并重启/验证现有 GUI。

profile `node_modules` 只能作为部署目标或只读比对对象，不能作为开发源。请记录构建、部署、回滚和版本识别步骤。

### 3. 为项目侧 evidence_state 补一个只读校验工具

维护员结论已正确规定：业务证据状态机由项目侧 `state-machine.yml` 定义，DSH 通用层只存储和展示 `evidence_state`，不裁决业务转换。

但是当前 plan 还缺少实际收益所需的校验入口。请加入一个 **只读** 工具或 review-time validator，例如 `check_evidence_state`：

- 读取项目侧 `.alice-supervision/state-machine.yml`；
- 校验申请的 evidence_state 转换是否在项目规则中允许；
- 对 `client_visible=true` 的工作包，发现 `SERVER_VERIFIED -> USER_ACCEPTED` 时报告违规；
- 输出提示/审计结果，不自行转状态、不替代监督员或用户裁决；
- 所有字段可选且缺省时不影响旧任务。

### 4. 修订 Skill 验收矩阵措辞

Q1 已确认：当前异步 followup 模型没有 claim-before-working hook。

因此不得保留“未加载 Skill 时不能开始工作”这类不可实现的验收描述。应改为：

> 对声明 `required_skills` 的任务，执行者在 `report_task` 前必须登记已审核 Skill 的使用记录（Skill ID、版本、时间、例外原因）。缺失记录时拒绝 report；补齐后才允许提交。该记录是可审计声明，不是系统对 agent 实际阅读内容的不可伪造证明。

`draft` 或 `revoked` Skill 不得满足 `required_skills`。正式 manifest/审核状态仍是本 P0 范围。

## 修订后 P0 的唯一 Allowed Scope

修订后的计划只应包含：

1. Skill manifest、审核状态、`required_skills`/`skill_usage` 可选字段与 `report_task` 审计。
2. `evidence_state` 可选字段、展示、项目侧 `state-machine.yml`、只读状态校验。
3. 调查结论 `confidence / evidence / limitations` 的结构化展示或模板校验。
4. `git_sync_check`、`record_emergency_sync`、紧急登记目录与派发警告。
5. DSH checkout -> 3082 lab -> 用户确认 -> 3083 的受控验证/部署路径。

所有改动必须是可选字段、新工具或新项目侧文件；不得改现有 task/flow/reviewer/watch 语义、`ALLOWED_TRANSITIONS`、既有工具签名或 Alice 业务代码。

## 修订后请向监督员提交的材料

1. 修订后的 `dsh-workflow-reliability-plan-20260824.md`，状态保持 `DRAFT`。
2. 简短变更说明，逐条对应上方四项修订。
3. 3082 lab 的预期验证矩阵，至少包括：
   - required_skills 缺失记录时 report 被拒绝、补齐后可提交；
   - draft Skill 不能满足 required Skill；
   - 项目状态机非法转换被只读检查报告；
   - 旧任务/旧 flow 可完整读取；
   - git_sync_check 和 emergency record 只读/登记行为；
   - 3082 无 schema 错误、3083 还未被改动。
4. 清晰的构建、部署和回滚步骤。

在监督员明确结论“通过，可转 APPROVED_FOR_IMPLEMENTATION”且用户批准前，**不得修改 Harness**。
