# DSH 工作流可靠性改造 P0 — 实施结果报告

- 日期：2026-08-24
- 实施方：DSH 工作流维护员（隔离进程）
- 授权来源：`dsh-maintainer-p0-implementation-20260824.md`（用户批准 + 监督员二审通过双门禁满足）
- 开发源：`/home/fb486/projects/dsh-agent-bus`（`baseline-npm-0.1.1` @ `105b9df661e4a85d197c6fa5bf1f4a3b065bc687`）
- 关联 plan：`dsh-workflow-reliability-plan-20260824.md`（APPROVED_FOR_IMPLEMENTATION）
- 前置：基线 JSX 类型修复完成（`dsh-baseline-jsx-fix-result-20260824.md`）

---

## 1. 概述

在隔离开发源上实施 DSH 工作流可靠性改造 **P0**（仅 DSH 基础设施），全部为可选字段/新工具/项目侧新增文件，向后兼容。**未触碰 Alice 业务代码、P1/FakeConnection/HARD_PATH/GUI、未部署 3083。**

## 2. 实施范围与改动对照

### 2.1 插件源码（`dsh-agent-bus` 开发源 `src/`）

| 文件 | 改动 | plan 项 |
|---|---|---|
| `src/spec.ts` | `taskRecord` 增可选字段：`requiredSkills`/`skillExceptions`/`skillUsage`/`evidenceState`/`clientVisible` | §3/§4 |
| `src/types.ts` | `TaskRecord` 增对应可选字段（与 spec 对齐） | §3 |
| `src/ledger.ts` | 新增 `putSkillUsage`/`setEvidenceState` 方法；`NewTask`/`editTask` patch 接受 P0 字段 | §3/§4 |
| `src/tools.ts` | `create_task`/`edit_task` 接受新可选字段；`report_task` 增 required_skills 审计（含 exception 豁免）；新增 4 工具 `check_required_skills`/`check_evidence_state`/`git_sync_check`/`record_emergency_sync` | §3/§4/§5/§6 |
| `src/panel.ts` | `TaskView` 增 `evidenceState`/`requiredSkills`/`skillUsage` 展示 | §4 |
| `src/index.ts` | USAGE_TEXT 更新（新工具说明 + 工具清单） | §3 |

改动统计：10 个源码文件（含新增 `src/p0-gates.ts` 与前序 3 个 `.tsx` JSX 修复），本 P0 及测试配套改动已更新。源码 diff 已存：`research/dsh-p0-source-diff-20260824.patch`。

### 2.2 项目侧新增（Alice 仓库）

- `.alice-supervision/skills-manifest.yml`：3 个 skill（maintainer-approved）登记。
- `.alice-supervision/state-machine.yml`：证据状态机定义（PLANNING→...→USER_ACCEPTED 等）。
- `.alice-supervision/emergency/TEMPLATE.md`：紧急登记模板。

## 3. 向后兼容设计（硬性要求）

- 所有新增字段**可选**，缺省时行为与现状完全一致。旧任务/旧 flow 读取时不带这些字段（panel 显示 `null`/空数组），无破坏。
- `ALLOWED_TRANSITIONS`、现有工具签名、ledger/flow/DAG 结构、`dsh-dafeiyu` 语义**未动**。
- target 验证：3082 加载后旧 5 任务完整读取，`evidenceState`/`requiredSkills` 缺省正常。

## 4. 构建证据

```bash
cd /home/fb486/projects/dsh-agent-bus
pnpm install
pnpm run build   # tsc -p tsconfig.json && tsc -p tsconfig.client.json && tsdown
```

- **构建结果：`build exit: 0`** ✅（服务端 + 客户端 tsc 通过，tsdown 产出 `lib/client.js 126.93 kB / client.js.map 189.93 kB`）
- 服务端独立校验：`npx tsc -p tsconfig.json --noEmit` exit 0 ✅
- checkout commit：`105b9df661e4a85d197c6fa5bf1f4a3b065bc687`
- 包版本：0.1.0（源码字段；npm 发布时 bump）
- **以 lib/ 逐文件 SHA-256 清单为唯一产物校验依据**：当前清单为 50 项，已归档至 `research/dsh-p0-lib-sha256-20260824.txt`。
- 不使用聚合哈希：此前的聚合值计算方式无法被标准命令复现，已从报告移除，避免误导追溯。
- 说明：基线 `105b9df` 无 fingerprint 机制（已确认），故不记录 id/buildTime，仅记录 lib/ 逐文件 SHA-256。

## 5. 3082 lab 部署与 V1-V11 验证

### 部署
- 备份 3082 lab 原 bus lib：`dsh-agent-bus-lab/backup-p0-lab/lib-pre-p0`
- 部署构建产物 lib 到 `/home/fb486/.dsh/profiles/alice-bus-lab/node_modules/dsh-agent-bus/lib`
- 3082 启动：bus state 200，无 schema/加载错误

### V1-V11 结果

| 编号 | 场景 | 结果 | 验证方式 |
|---|---|---|---|
| V1 | required_skills 缺失记录 report 被拒 | ✅ | skill-audit 单测（缺失→reject） |
| V2 | 补齐记录后允许 | ✅ | skill-audit 单测（approved→allow；min_version 校验均通过） |
| V3 | 执行者自加 exception 拒绝 | ✅ | report_task 的 skill_usage 参数仅 id/version/loaded_at，无 exception 字段；exception 仅来自 task.skillExceptions（initiator/reviewer 预登记）；exception 豁免逻辑 3/3 单测通过 |
| V4 | draft/deprecated/revoked 不满足 required | ✅ | skill-audit 单测 3 类均 reject |
| V5 | client_visible SERVER_VERIFIED→USER_ACCEPTED 违规 | ✅ | evidence-state 单测（违规上报，不写状态不转） |
| V6 | 合法转换输出允许 | ✅ | evidence-state 单测（legal→allowed，无违规） |
| V7 | 旧任务/旧 flow 读取（向后兼容） | ✅ | 3082 加载后旧 5 任务完整读取，新字段缺省正常 |
| V8 | git_sync_check 只读 | ✅ | 读 remotes/dirty，不改文件 |
| V9 | record_emergency_sync 写入 | ✅ | 写 `.alice-supervision/emergency/` 字段完整可读 |
| V10 | 3082 无 schema 错误 + 产物可追溯 | ✅ | 日志无 zod/schema 报错；checkout commit/构建命令/产物 SHA-256 已记录 |
| V11 | 3083 未被改动 | ✅ | 3083 profile node_modules 与开发源产物不同（未同步）；3083 进程仍在运行但用旧 bus（未被部署 P0） |

> **说明**：V1-V6/V8/V9 已落盘为可复现的 Vitest 测试 `tests/p0-verification.test.ts`，配置文件为 `vitest.config.ts`；`pnpm test` 结果为 **1 个测试文件、16 个测试全部通过（16/16）**。V7/V10/V11 是 3082 部署/环境验证，单独记录。真实 agent 会话端到端点验已完成，详见 `.alice-supervision/research/dsh-p0-3082-e2e-record-20260824.md`。

## 6. 边界确认

- ✅ 仅 DSH 基础设施改造（dsh-agent-bus 插件源码 + 项目侧文件）
- ✅ 未触碰 Alice 业务代码 / P1 / FakeConnection / HARD_PATH / GUI / MCP / spark / Mixin
- ✅ 未做 contract_failures / 自动失败升级 / list_contract_failures（P1/阶段 2）
- ✅ 未部署 3083（3083 profile node_modules 未同步，进程未重启为 P0）
- ✅ 未动 `ALLOWED_TRANSITIONS` / 现有工具签名 / ledger/flow/DAG 语义

## 7. 产物归属

- 源码 diff：`.alice-supervision/research/dsh-p0-source-diff-20260824.patch`
- 持久化验证测试：`/home/fb486/projects/dsh-agent-bus/tests/p0-verification.test.ts`
- 测试配置：`/home/fb486/projects/dsh-agent-bus/vitest.config.ts`
- lib 逐文件 SHA-256 清单：`.alice-supervision/research/dsh-p0-lib-sha256-20260824.txt`（当前 50 项）
- 结果报告：本文件
- 3082 lab 备份：`dsh-agent-bus-lab/backup-p0-lab/lib-pre-p0`

## 8. 待监督员/用户下一步

1. 监督员复核本报告的闭合修订、持久化测试、逐文件 SHA-256 与 E2E 记录。
2. 监督员**报请用户**：是否批准部署 3083（按受控步骤：备份 → 部署同一产物 → 重启 → 验证 → 回滚预案）。
3. **用户批准前不部署 3083**；plan 当前 APPROVED_FOR_IMPLEMENTATION，业务代码与 Harness 主模块仍不可触碰。

## 9. 3082 lab 当前状态

- 3082 正在运行 P0 bus（bus state 200），5 个历史任务正常。
- 3082 lab 的 `ui-trajectory` 与 Dafeiyu 已为浏览器诊断暂时禁用；3083/3081 未受影响。
- 3083 仍为旧实例（未部署 P0），3081 未受影响。
- 回滚：恢复 `dsh-agent-bus-lab/backup-p0-lab/lib-pre-p0` 至 3082 lab 即可。

---

## 10. P0 二审闭合修订记录

- **持久化测试闭合**：新增 `tests/p0-verification.test.ts` 与 `vitest.config.ts`；`pnpm test` 通过，1 个文件、16/16 tests passed，覆盖 V1-V6/V8/V9。新增 `src/p0-gates.ts` 抽离无副作用 gate 逻辑，工具行为不变。
- **哈希口径闭合**：移除不可复现的聚合哈希；以 `.alice-supervision/research/dsh-p0-lib-sha256-20260824.txt` 中的逐文件 SHA-256 清单为唯一产物校验依据，当前 50 项（因新增 p0-gates 编译产物）。清单已在最终 get_task/git_sync_check 修复后刷新，当前 `lib/tools.js` SHA-256 为 `d19777f244e6d1784e078be9fcff3472bd7602b833ebf84b8a44d8667bb2fa28`。
- **GUI E2E 闭合**：真实 3082 agent 会话完成四工具点验；记录在 `.alice-supervision/research/dsh-p0-3082-e2e-record-20260824.md`，状态 `MAINTAINER_VERIFIED`。测试前后任务总数均 5，目标任务保持 `failed/settled/timeout`，测试登记已清理。
- **E2E 复测修复**：`get_task` 补齐 `title` 及 P0 字段 output schema，消除 `value.title is not a declared property`；`git_sync_check` 区分非 Git 仓库与 dirty，修复 `dirty:true` 误报。修复后重新构建、部署 3082，真实 agent 复测通过；任务总数独立由 bus state 核验仍为 5，目标任务保持 `failed/settled/timeout`。
- **边界**：未部署 3083，未改 Alice 业务代码、P1/FakeConnection/HARD_PATH/GUI，未改 `ALLOWED_TRANSITIONS`/task/flow/DAG 语义。此报告仍不构成 3083 部署批准。

---

## 11. 3083 受控部署尝试与回滚报告

- 授权：`dsh-maintainer-p0-deploy-3083-20260824.md`（用户批准）
- 部署日期：2026-08-24
- **结果：部署后因功能回归已回滚，3083 保持部署前状态，未启用 P0。**

### 执行的受控步骤
1. 记录部署前：3083 PID 2230、dsh `0.1.1-rc.2`、profile lib 39 文件。
2. 备份：`/home/fb486/projects/alice/.dsh-runtime/backup-p0-3083-deploy-20260824-221846/lib-pre-deploy/`（39 文件，逐文件 SHA-256 已存）。
3. 一致性校验：部署参照（3082 lab 当前 lib，51 文件）与开发源 P0 产物 50 文件哈希一致（0 不一致）；`supervisor-watch.js` 哈希 `7fc523e3…` 与 3083 一致。
4. 部署 → 3083 lib 变 51 文件，与参照逐路径哈希 0 不一致。
5. 重启 → 3083 READY，bus state 67 任务，日志无错误。

### 触发回滚的异常（功能回归）
- 部署后 `index.js` 中 `SupervisorWaitWatch` 集成从 **2 处 → 0 处**；`tools.js` 的 `supervisor_wait_watch` 工具从 **2 → 0**。
- 根因：待部署 P0 产物来自开发源 baseline `105b9df`（**原本无 supervisor-watch 机制**）。而 3083 部署前的 39 文件 lib 是通过**额外补丁**给 index.js/tools.js 集成了 supervisor-watch（等待守护）的。P0 产物覆盖后，`supervisor-watch.js` 文件虽在（51 文件之一）但 `index.js` 不再集成它，成为孤儿文件，**监督员等待守护功能丢失**。
- 3082 lab 同样无 supervisor-watch 集成（干净 baseline）。

### 回滚执行
- 停止 3083 → 恢复备份 lib → 重启 → 校验恢复后与备份逐文件一致（39 文件），`wait_watch` 工具恢复（2 处），bus state 67 任务，日志干净。

### 边界确认
- 未修改 Alice 业务代码、P1/FakeConnection/HARD_PATH/GUI，未做 P1/P2 功能。
- 未混入未验证改动；因「部署原样 P0 会破坏 3083 既有 supervisor-watch」而触发的功能回归，已回滚。
- 3082 lab 仍运行 P0 产物（51 文件，含无悬空的 supervisor-watch.js）；3083 保持部署前（带 supervisor-watch 集成）。

### 待监督员/用户决策（阻塞）
P0 产物（baseline 无 supervisor-watch）与 3083 现役依赖（需 supervisor-watch）存在组合冲突，直接部署会丢失 wait_watch。需决定正确路径，例如：
- A) 在 P0 产物基础之上**重新应用 supervisor-watch 补丁并验证**（属额外已验证改动，需另行授权）。
- B) 确认 supervisor-watch 不再是现行需求，接受 P0 产物覆盖为最终形态。
- C) 本轮不部署 3083，仅保留 3082 已验证，直到组合事宜明确。

---

## 12. 3083 最终部署（方案 B：干净 P0，废弃 supervisor_wait_watch）

- 执行时间：2026-08-24 22:19-22:27
- 监督员决策：方案 B（废弃 supervisor_wait_watch，部署与 3082 完全一致的干净 P0）
- **结果：部署成功，3083 已启用 P0，运行正常。**

### 监督员决策依据
1. **实际不依赖**：近 7 天调用 0 次，当前监督流程无 `supervisor_wait_watch` 也正常。
2. **监督协议优先**：supervisor_wait_watch 是无监督记录的影子补丁（无设计/验收/批准），不应强行纳入 P0。
3. **P0 完整性**：保持与 3082 验证完全一致，不引入未验证变量。
4. **技术债清理**：移除影子补丁，避免累积更多无记录依赖。

### 部署执行
- 停止 3083（PID 122563）
- 部署参照：3082 lab 当前 lib（51 文件，含 P0 产物 50 文件 + 遗留 supervisor-watch.js）
- 部署后 3083 lib：51 文件，与参照逐文件哈希 **0 不一致** ✅
- supervisor-watch 状态：`supervisor-watch.js` 文件存在但 `index.js` 未集成（0 引用），`tools.js` 未注册 `wait_watch`（0 处）—— 符合预期
- 重启 3083（PID 122732）

### 部署后验证（全部通过）
| 项 | 结果 |
|---|---|
| 进程/端口 | PID 122732，3083 监听 |
| bus state | 67 tasks，stats `completed=49, failed=15, canceled=3` |
| 日志 schema/错误 | 0（无） |
| 旧任务读取（A1.1） | `17507b65…` 可读 |
| 新工具注册 | `check_required_skills`, `check_evidence_state`, `git_sync_check`, `record_emergency_sync` 各 1 处 |
| GUI 前端 | DSH_BOOT 正常加载 |

### 交付产物
- 部署前备份：`/home/fb486/projects/alice/.dsh-runtime/backup-p0-3083-deploy-20260824-221846/lib-pre-deploy/`（39 文件）
- 备份 SHA-256：`lib-pre-deploy-sha256.txt`（39 行）
- 部署参照 SHA-256：`deploy-reference-sha256.txt`（51 行）
- 部署后 SHA-256：`deployed-lib-sha256.txt`（51 行）
- 回滚命令：`rm -rf /home/fb486/.dsh/profiles/alice-bus/node_modules/dsh-agent-bus/lib && cp -a <backup>/lib-pre-deploy /home/fb486/.dsh/profiles/alice-bus/node_modules/dsh-agent-bus/lib && pkill -TERM -f 'alice-bus --port 3083' && dsh --profile alice-bus --port 3083`

### 边界确认
- 未修改 Alice 业务代码、P1/FakeConnection/HARD_PATH/GUI
- 未做 P1/P2（contract_failures/自动失败升级）
- 部署产物与 3082 验证完全一致
- `supervisor_wait_watch` 因无监督记录且实际未使用而移除；若未来需要，按监督流程单独审核后再引入

### 部署状态
- 3082 lab（隔离验证）：P0 运行正常，16/16 tests passed，E2E MAINTAINER_VERIFIED
- **3083（生产）：P0 已部署，运行正常，67 个任务正常，4 个新工具可用**
- 3081（未改动）：保持原状
