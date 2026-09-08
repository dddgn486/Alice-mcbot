# Alice 工作组全量迁移交接进度快照（2026-08-25，最优先级）

- 记录：Alice 架构监督员（旧会话 session-302a9da6）
- 日期：2026-08-25
- 目的：**整个工作组迁移到新会话**——本文件是新会话（含新监督员/新主开发员/规划员/调查员）的权威起点；旧会话上下文不再依赖。
- 性质：进度事实快照（含完整证据链路径）。**不构成实施授权**——实施授权以 active plan + 用户批准为准。

---

## 0. 一句话现状

**bot 物理失效（方案 C）服务端线已闭环并已提交（C-1 已 commit），待 push + Windows 同步 + 客户端实测（M2-M5）；全量功能审查（批 1-4，19 模块）已完成并验收。**

---

## 1. Git 状态（迁移时核查）

| 项 | 值 |
|---|---|
| **HEAD** | `8dacd94`（feat(phys): **C-1 task-layer residual-delta fallback consumption (scheme C, user-approved)**）|
| 前序 | `21f653f`（机制定位证据）/ `e9af1b3`（NPE 佐证）|
| 远端 | **master...github/master [ahead 7, behind 2]**——**C-1 已 commit 但未 push；远端有 2 个新提交，push 前需 fetch + rebase/合并（禁强推）**|
| 工作区 | **src 零 M 状态**（C-1 已入 git ✅）；仅 .alice-supervision 文档未跟踪（见 §6）|
| Windows 同步 | ❌ **未完成**——C-1 未 push 即未达 Windows；同步路径：push origin → GitHub → Windows `receive.denyCurrentBranch=updateInstead` 自动更新 |

## 2. 方案 C 服务端线（最新主线，用户批准）

| 环节 | 状态 | 证据 |
|---|---|---|
| F1-F6 断言实施 | ✅ | `research/f1-f6-assertion-result-20260825.md`（HEAD 709bc5a 曾，后并入）|
| NPE 佐证（B3 排除）| ✅ | `research/npe-evidence-result-20260825.md`（stderr 0 字节）|
| 机制定位（注射点前置）| ✅ | `research/physics-chain-mechanism-evidence-20260825.md`（aiStep 分支/驱动链/flags 设置点）|
| **定案裁决** | ✅ | **`research/f1f6-bot-physics-verdict-20260825.md`**（主根因=实体 tick 链推挤/碰撞响应未被 bot 消费；B2/B3 排除）|
| 修复线规划 | ✅ | `research/physics-fix-plan-20260825.md`（三方案对比，推荐 C）|
| active plan | ✅ | `active-plan.md`：`2026-08-25-bot-physics-fix-c-v1`（方案 C，用户批准→已实施）|
| **C-1 实施** | ✅ | `research/physics-fix-c-result-20260825.md`（122 行：C-1 兜底段+C1_CONSUME 7 次证据+边界零违反）|
| **C-1 commit** | ✅ | **`8dacd94`**（已入 git）|
| **push + Windows 同步** | ⚠️ **待办** | ahead 7/behind 2，需 fetch+rebase+push（禁强推）|

### 方案 C 服务端断言结果（如实，不伪造）
- **F1 hurt false**（第二根因候选——**单独立包不混修**，见 §5 待办）
- **F3b/tickChain FAIL**（如实：fixture 同步断言与 C-1 兜底 tick 尾段**时序隔离**；转 PASS 判据移交客户端 M2/M3）
- **F4/F5/F2 PASS**（任务层 travel 链零回归）
- **R2/R3 suites PASS**（P0 门禁/既有套件未回归）
- **C1_CONSUME 7 次触发**（兜底生效证据）→ 停止条件不成立（不转 B）

## 3. 全量功能审查（已完成验收，背景参照）

| 批次 | 模块 | 结论 | 报告 |
|---|---|---|---|
| 批 1 | bot / pathing / task | 两条未修复问题线定位（跳跃异常/bot 物理）+ 矿链隔离确认 | review-batch1-{bot,pathing,task}-20260825.md |
| 批 2 | transfer / gui / client / network | 全 D 维持、A1.1 保持、FROZEN 正确冻结、白名单干净 | review-batch2-*-20260825.md |
| 批 3 | item / road / capability / survival / perception | 全 D 维持、R# 全遵守 | review-batch3-{item,road,capability-survival-perception}-20260825.md |
| 批 4 | 外围 7 模块 | 全 D 维持、安全唯一入口/权限门/R39 | review-batch4-periphery-20260825.md |

## 4. 已闭环的其他线（勿重开）

- **P0 skill 方法论升级**：✅（commit 4312880 + HANDOVER d872367）
- **P1 Git MCP 试点**：⏸ 暂停（用户决策选项 A，官方 Git MCP server 无工具过滤不可行；HANDOVER 6c2b461）
- **bus 投递缺陷修复**：✅（b242da8 已部署 3083，投递失败回退 + retryIdleMs 300s→60s，探针闭环）
- **最小唤醒机制**：✅（99a638c 已部署：request_input 通知 dispatcher）

## 5. 待办（新会话接手顺序）

| 优先级 | 待办 | 说明 |
|---|---|---|
| **P0** | **C-1 push + Windows 同步确认** | fetch→rebase（禁强推，ahead 7/behind 2）→push origin→用户确认 Windows pull 到 8dacd94（或 rebase 后新 HEAD）|
| **P0** | **客户端矩阵 M2/M3 实测**（用户 Windows）| 推挤可见性 + 击退可见性；预期 `BOT_PHYSICS_C1_CONSUME` + 位移日志；服务端 PASS ≠ 客户端验收；用户实测形成 evidence-report（client-tests/）|
| P1 | **F1 hurt false 第二根因定案** | 方案 C 客户端实测后单独立包（不混修）；候选：BotPlayer 构造 isPushable/hurt 检查路径 |
| P1 | HANDOVER 更新 | 补方案 C 段（新 HEAD/完成项/待客户端实测限制/下一安全步）|
| P2 | 未跟踪文档整理 | .alice-supervision 下约 20 个未跟踪文件（draft active-plan×2、dsh 记录、progress-snapshot 等）——决定跟踪/归档（多为历史交接物）|
| P2 | 负载调整 | 旧主开发员已进入工具参数恶性循环（嵌套 JSON）→ 用户决策：归档旧会话 + 新建主开发员（干净上下文）——**本快照即为此准备的交接物**|

## 6. 已知教训（传给新会话，避免重踩）

- **bash 工具 arguments 必须扁平**：`command` 字段是单条 shell 命令的**纯字符串**，**绝不嵌套 JSON 对象**（旧主开发员反复嵌套导致 10 分钟恶性循环——根因是参数构造错误，非文件/工具问题）。
- **工具缺失处理**：成员报告"无法使用 read/bash"时先让其列可用工具清单 + 复测（区分真缺失/瞬态），不直接代读（记忆留痕 memory-handoff）。
- **bus 投递故障**：create_task 卡 submitted 时——先查 bus state API（不能只看创建返回），wait 观察；已修复（b242da8）但仍会偶发（无人值守/会话休眠），7 天观察期。
- **服务端 PASS ≠ 客户端验收**：所有客户端可见行为必须用户 Windows 实测（evidence-report），监督员不替用户验收。
- **实验线边界**：SOFT_SURFACE 不接入矿链/拾取/道路/隧道（AGENTS.md 不可协商 + R#）；SEARCH_LIMIT ≠ UNREACHABLE ≠ 通道授权。

## 7. 权威文件索引（新会话必读）

| 文件 | 内容 |
|---|---|
| `.alice-supervision/active-plan.md` | 当前计划（方案 C，APPROVED）|
| `.alice-supervision/research/f1f6-bot-physics-verdict-20260825.md` | 定案裁决（主根因）|
| `.alice-supervision/research/physics-fix-c-result-20260825.md` | C-1 实施证据（含边界确认）|
| `.alice-supervision/research/physics-fix-plan-20260825.md` | 修复线规划（三方案对比）|
| `docs/HANDOVER.md` | 历史全线交接（§九 三副本同步、§八 R# 已知坑）|
| `docs/SUPERVISION_PROTOCOL.md` | 监督协议（角色/边界/验收矩阵）|

---

**本快照由旧监督员在迁移前落盘。新会话以本文件为起点继续；实施授权以 active plan + 用户批准为准。**
---

## 8. 成员级交接（迁移后各新成员接手要点）

### 8.1 → 新主开发员（接替旧 `session-30b693e7`）

- **当前在途**：任务 `aa5e6de5-af68-4757-9235-d1b438e09a90`「提交：方案 C C-1 + Windows 同步闭环」= working——**新主开发员接手后第一个任务**。
- **接手即做**（任务书：`.alice-supervision/research/physics-fix-c-commit-task-20260825.md`）：确认 HEAD=`8dacd94`（C-1 已 commit ✅）→ `git fetch` → rebase `origin/master`（**ahead 7/behind 2，禁强推**）→ rebase 后 `./gradlew compileJava` 复验 → `git push origin master` → 验证 `git status -sb` ahead 0 → 更新 HANDOVER → 证据报告尾部追加提交闭环记录 → report_task。
- **教训（必读，防复发）**：bash 工具 `command` 字段**必须扁平纯字符串**，绝不嵌套 JSON 对象——旧主开发员因此恶性循环 10 分钟（根因=参数构造错误，非文件/工具问题；工具本身经基线验证正常）。
- **边界**：不触碰 fixture/FakeConnection/矿链/5 travel 调用点/P0 门禁；服务端 PASS ≠ 客户端验收（M2/M3 由用户 Windows 实测）。

### 8.2 → 新深度调查员（接替旧 `session-f26bd205`）

- **已完成**：批 1-4 全量功能审查（11 份报告全部落盘 + 监督员验收，见 §3）→ 机制定位证据（physics-chain-mechanism）→ NPE 佐证（B3 排除）。
- **待接（P1）**：**F1 hurt false 第二根因调查**（单独立包，不混修方案 C 线）——候选：`BotPlayer` 构造后 `isPushable` 条件 / hurt damageSource 检查 / isPushable-hurt 链在假人上的差异。**工具教训**：如遇 read/bash 不可用——先列可用工具清单+复测（区分真缺失/瞬态），确认真缺失再 request_input，不直接放弃（记忆留痕）。
- **当前无在途任务**（idle）。

### 8.3 → 新规划员（接替旧 `session-0e50f21c`）

- **已完成**：全量审查线路规划（full-review-plan-20260825）→ F1-F6 实施线路规划（f1-f6-implementation-plan）→ 物理修复线规划（physics-fix-plan，三方案对比推荐 C）——三层规划全部验收。
- **待接**：在方案 C 客户端实测（M2/M3）后，如需修复线规划更新/回归矩阵调整——按新监督员指令。
- **当前无在途任务**（idle）。

### 8.4 → 新监督员（接替旧 `session-302a9da6`）

- 见 §0-§7（权威快照全文）+ §5 待办顺序 + §6 教训。接手第一件事：读 `progress-snapshot-20260825-transfer.md` → 盯 `aa5e6de5` 提交任务验收 → 安排用户 Windows 同步确认 + M2/M3 客户端实测矩阵 → 依据实测定 F1 第二根因立包时机。

---
