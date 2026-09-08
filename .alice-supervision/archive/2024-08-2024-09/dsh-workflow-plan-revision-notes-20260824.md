# DSH 可靠性改造 plan 修订说明（提交监督员二审）

- 日期：2026-08-24
- 提交方：DSH 工作流维护员
- 依据：`dsh-maintainer-next-action-20260824.md` 四项修订要求
- 提交物：
  1. 修订后的 plan：`.alice-supervision/dsh-workflow-reliability-plan-20260824.md`（状态保持 DRAFT）
  2. 本修订说明（四项修订逐条对应）
  3. 3082 lab 预期验证矩阵
  4. 构建 / 部署 / 回滚步骤

---

## 一、四项修订逐条说明

### 修订 1：从 P0 移出 contract_failures 与自动失败升级

**已执行**：
- Allowed Scope 删除：新表 `contract_failures`、`list_contract_failures` 工具、settle failure/failed 时按 contract_key 累积、1/2/3 阈值自动升级、自动 `BLOCKED_BY_CLIENT_EVIDENCE` / `INVESTIGATION_REQUIRED` / `ARCHITECTURE_REVIEW_REQUIRED`。
- Explicitly Out Of Scope 增加：「contract_failures / 自动失败升级 / list_contract_failures（P1/阶段 2，后续独立工作包）」。
- Implementation Steps、行为变化、Verification 中相关条目已全部移除。

**理由**：规格 §7 与 §10 阶段 2 明确属 P1；虽「可选字段兼容」，但不提前混入 P0，保持本轮最小。

### 修订 2：DSH checkout 开发 + 3082 先行 + 禁止编辑 profile node_modules

**已执行**：
- 文件范围改为：开发源唯一为 DSH checkout `/home/fb486/.nvm/versions/node/v22.23.2/lib/node_modules/@deepseek-ai/dsh/`。
- 明确声明：profile `node_modules` 只作为部署目标或只读比对对象，不作为开发源。
- Implementation Steps 重排为：checkout 改造 → 构建 → 部署 3082 lab → 完整验证 → 呈报用户 → 用户确认后受控部署 3083 → 重启/验证。
- 约束节增加「禁止直接编辑 profile node_modules」。
- 新增「构建 / 部署 / 回滚」章节（见下）。

**理由**：生产 profile 是已部署物，直接编辑破坏可追溯性；checkout → lab → 用户确认 → 生产 是受控发布路径。

### 修订 3：新增只读 `check_evidence_state`

**已执行**：
- 新工具 `check_evidence_state(...)` 加入 Allowed Scope 行为变化：
  - 读取项目侧 `.alice-supervision/state-machine.yml`；
  - 校验申请的 evidence_state 转换是否在项目规则中允许；
  - 对 `client_visible=true` 工作包发现 `SERVER_VERIFIED -> USER_ACCEPTED` 时报告违规；
  - 只输出提示/审计结果，不自行转状态、不替代监督员或用户裁决；
  - 可选字段，缺省不影响旧任务。
- Verification 矩阵增加 V4/V5（非法转换报告违规、合法转换不阻塞）。

**理由**：五问答复已定「项目侧定义状态机、通用层只展示」，但缺校验入口则无实际收益；只读校验补齐闭环且不越权。

### 修订 4：Skill gate 验收措辞改为 report_task 前强制审计

**已执行**：
- 删除任何「未加载 Skill 时不能开始工作 / working 前硬阻塞」表述。
- Objective、行为变化、约束统一改为：
  > 对声明 `required_skills` 的任务，执行者在 `report_task` 前必须登记已审核 Skill 的使用记录（Skill ID、版本、时间、例外原因）。缺失记录时拒绝 report；补齐后才允许提交。该记录是可审计声明，不是系统对 agent 实际阅读内容的不可伪造证明。
- 明确 `draft` 或 `revoked` Skill 不得满足 `required_skills`；正式 manifest/审核状态仍在 P0 范围。
- 修订后 P0 唯一 Allowed Scope 第 1 项即为此闭环。

**理由**：Q1 已确认当前异步 followup 模型无 claim-before-working hook，保留不可实现的验收描述会造成假承诺。

---

## 二、3082 lab 预期验证矩阵（修订后）

| 编号 | 场景 | 预期 |
|---|---|---|
| V1 | 声明 `required_skills` 的任务，report 缺失 Skill 使用记录 | report 被拒绝，提示补齐/声明例外 |
| V2 | 补齐 Skill 使用记录后 report | 允许提交，任务行记录 Skill ID/版本/时间/例外 |
| V3 | `draft` 或 `revoked` Skill 被声明为 required | 校验拒绝，提示需维护员审核 |
| V4 | 项目状态机非法转换（client_visible 工作包 `SERVER_VERIFIED -> USER_ACCEPTED`） | `check_evidence_state` 报告违规，不自行转状态 |
| V5 | 合法转换 | `check_evidence_state` 输出允许，不阻塞 |
| V6 | 旧任务/旧 flow 读取 | 完整读取，无 schema 错误（向后兼容） |
| V7 | `git_sync_check` | 只读输出远端配置/dirty/紧急标记状态，不改任何文件 |
| V8 | `record_emergency_sync` | 写入 `.alice-supervision/emergency/`，字段完整可读 |
| V9 | 3082 无 schema 错误 | 日志无 zod/schema 报错 |
| V10 | 3083 未被改动 | 部署前确认 3083 仍为原状态 |

---

## 三、构建 / 部署 / 回滚步骤

### 构建（DSH checkout）

```bash
# 开发源
cd /home/fb486/.nvm/versions/node/v22.23.2/lib/node_modules/@deepseek-ai/dsh/
# 按 checkout 的构建流程（如 pnpm/npm build）产出插件/包产物
# 记录版本标识：git commit / 包版本号 / 构建时间戳
```

### 部署到 3082 lab（实验）

```bash
# 1) 将构建产物安装/同步到 lab profile（3082）
#    /home/fb486/.dsh/profiles/alice-bus-lab/ 为部署目标
# 2) 启动 lab
node /home/fb486/.nvm/versions/node/v22.23.2/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --profile alice-bus-lab --port 3082
# 3) 按验证矩阵 V1–V9 逐项验证
# 4) 验证通过后向用户呈报
```

### 部署到 3083 生产（用户确认后）

```bash
# 1) 备份
mkdir -p /home/fb486/projects/alice/.dsh-runtime/backup-<date>-dsh-reliability
cp -a /home/fb486/.dsh/profiles/alice-bus/node_modules/dsh-agent-bus/lib /home/fb486/projects/alice/.dsh-runtime/backup-<date>-dsh-reliability/
cp /home/fb486/.dsh/profiles/alice-bus/cordis.patch.yml /home/fb486/projects/alice/.dsh-runtime/backup-<date>-dsh-reliability/
# 2) 部署构建产物到 profile（仅作为部署目标，非开发源）
# 3) 重启 3083（仅该进程）
# 4) 验证：state 200、任务数据不变、日志无 schema 错误、V10 确认生产未被提前改动
```

### 回滚

```bash
# 恢复备份的 lib 与配置
cp -a /home/fb486/projects/alice/.dsh-runtime/backup-<date>-dsh-reliability/lib /home/fb486/.dsh/profiles/alice-bus/node_modules/dsh-agent-bus/lib
cp /home/fb486/projects/alice/.dsh-runtime/backup-<date>-dsh-reliability/cordis.patch.yml /home/fb486/.dsh/profiles/alice-bus/cordis.patch.yml
# 重启 3083
```

### 版本识别

- 每个部署记录：DSH checkout commit、构建产物版本/时间戳、profile 部署目标路径、3082/3083 验证结果文件路径。

---

## 四、等待监督员二审

- [ ] 二审结论「通过，可转 APPROVED_FOR_IMPLEMENTATION」+ 用户批准
- [ ] 批准前：不修改 Harness、不改 3083、不改 profile node_modules
- [ ] 批准后：按本说明第三节执行 checkout 改造 → 3082 验证 → 呈报 → 3083 受控部署
