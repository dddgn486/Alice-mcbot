# Alice work-session review packet

- Commit: `d1def08676a3e74ef9927011edb7cf2a75e9a0ba`
- Subject: docs: define product architecture roadmap
- Authored: 2026-08-21T02:57:58+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260821-product-architecture-review-v1`
- Active plan status at handoff: `NEEDS_USER_DECISION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
README.md
docs/AI_PLAYER_DESIGN.md
docs/AI_PLAYER_NOTES.md
docs/EXECUTION_FRAMEWORK.md
docs/HANDOVER.md
docs/PRODUCT_ARCHITECTURE_ROADMAP.md
```

## Commit summary

```text
 README.md                            |   2 +-
 docs/AI_PLAYER_DESIGN.md             | 165 ++++++++++---------
 docs/AI_PLAYER_NOTES.md              |  25 +--
 docs/EXECUTION_FRAMEWORK.md          |  15 +-
 docs/HANDOVER.md                     |  29 ++--
 docs/PRODUCT_ARCHITECTURE_ROADMAP.md | 304 +++++++++++++++++++++++++++++++++++
 6 files changed, 432 insertions(+), 108 deletions(-)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index f42815b..36d01e8 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -1,22 +1,22 @@
 # Alice 项目交接说明（给新会话的快速上手）
 
 > 用途：另一个对话/会话接手本项目时，先读本文件 + README + 设计文档，即可快速进入工作。
-> 更新：2026-08-20（本会话记录收口；以当前 HEAD `26819bd` 与工作区 diff 为准）
+> 更新：2026-08-21（当前功能 HEAD `ae32c4a`；总体产品路线已补充，文档改动尚未提交）
 > 重要：本会话功能提交已同步到 Windows 端仓库（`origin` = `/mnt/d/JAVA_projects/alice`）。**每次代码修改完成后必须 `git push origin master`**，用户在 Windows 客户端实测。
-> 本次文档提交前工作区另有 `.gitignore`、`docs/SUPERVISION_PROTOCOL.md`、`tools/` 的未提交变更；它们不属于本轮软移动/跟随实现，接手时不得覆盖或混入功能提交。
+> 当前工作区的未提交改动均为监督流程：`.gitignore`、`AGENTS.md`、`docs/SUPERVISION_PROTOCOL.md`、`docs/supervision/`、`tools/` 与本文件；它们不属于 Mod 功能实现，应作为独立治理提交核对，勿覆盖或混入功能提交。
 
 ## 〇、当前 Git 状态（重要）
 
 ```
-26819bd fix: decouple follow from safe zones               ← HEAD（跟随与保护区模块解耦）
-f07533a feat: add safe zone follow task                    （首次 FollowTask；随后已移除保护区前提）
-becd643 fix: support partial block soft landings           （半格/台阶 VoxelShape 支撑顶面落地验收）
-c635bc5 feat: add native travel path probe                （SurfacePathfinder 连续脚位段 NATIVE_TRAVEL 探针）
-c293c10 fix: settle soft movement with native gravity     （移除伪 onGround 成功，零输入 travel 结算）
-9184ae7 fix: prefer direct mining stands                   （直通且可达的挖掘站位优先）
+ae32c4a feat: record task execution outcomes               ← HEAD（任务终态审计与只读状态）
+d11e851 fix: settle soft path descents                    （下降诊断已实现；客户端失败后保守禁用）
+511a1e2 fix: align surface paths with collision sweeps     （直线路径与对角碰撞一致性）
+b423af3 fix: prove surface path costs                     （零 heuristic / Dijkstra 成本正确性）
+1f95687 feat: probe native travel height segments         （独立软路径高差探针）
+26819bd fix: decouple follow from safe zones              （跟随与保护区模块解耦）
 ```
 
-当前工作区不是干净状态：`docs/HANDOVER.md` 为本轮记录更新；`.gitignore`、`docs/SUPERVISION_PROTOCOL.md`、`tools/` 是未归属的既有变更。只提交已核对归属的文件，勿覆盖用户或监督流程变更。
+当前工作区不是干净状态：治理对接文件待独立提交。下一次功能工作开始前，监督员应先创建 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`；主工作会话必须运行 `./tools/work-session-start.sh`，没有批准工作包不得扩展功能。只提交已核对归属的文件，勿覆盖用户或监督流程变更。
 
 WSL 工作区路径：`~/projects/alice`；Windows：`D:\JAVA_projects\alice`（`origin`）。
 GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push 前先 `git fetch github` 查冲突）。
@@ -55,7 +55,8 @@ GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push
 **设计核心**：LLM 只做目标级决策，确定性执行器负责可靠完成；服务端权威数据，
 GUI 背后操作序列化为语义接口（而非视觉点 GUI）。
 
-设计文档：`docs/AI_PLAYER_DESIGN.md`（架构总纲）· `docs/EXECUTION_FRAMEWORK.md`（执行层框架）·
+设计文档：`docs/PRODUCT_ARCHITECTURE_ROADMAP.md`（三条产品主线、兼容分级与长期路线）·
+`docs/AI_PLAYER_DESIGN.md`（架构总纲）· `docs/EXECUTION_FRAMEWORK.md`（执行层框架）·
 `docs/MEK_GUI_SEMANTICS.md`（Mek GUI→接口语义表）· `docs/ROAD_MATHEMATICAL_MODEL.md`（道路数学模型）·
 `docs/BARITONE_PORTING_CHECKLIST.md`（Baritone 移植清单）
 
@@ -265,6 +266,10 @@ headless 验收：`./gradlew runServer -Dalice.selftest.auto=true`（测完自
 - **R34（新）**：单目标挖掘站位选择必须先比较所有有限候选中的“视线直通 + 曲面可达”站位，并取最短曲面路径；候选分组（下/同/上）只能辅助生成，不能压过直通性。无直通候选时才回退到视线受阻站位，并严格使用 MineTask 最多两格、4.5 格局部清障；A* 不得为了站位自行挖方块或扩大成通道施工。
 - **R35（更新）**：软移动抵达不能按水平距离直接 `setOnGround(true)` 成功。必须验证目标脚位下方碰撞形状的实际支撑顶面、实体脚底和 `onGround`；不能以 `blockPosition()` 的整数 Y 拒绝下半砖/台阶上的正常落地。跨障后未落稳时只用零输入 `travel(Vec3.ZERO)` 结算原版重力/摩擦，30 tick 未稳定返回 `soft_probe_unsettled`。半格台阶的实现已编译，但尚无本会话客户端验证；上/下台阶必须由 `SurfacePathfinder` 提供连续脚位段后逐段验证，未验证前不得视为稳定能力。
 - **R36（新）**：`FollowTask` 是独立的可开关 SOFT_SURFACE 状态，`/alice follow on|off` 只由被跟随玩家本人开关；运行约束仅为同维度、bot 空闲、24 格上限、安全曲面、连续脚位段和维生中断，保持约 2 格距离并每 10 tick 重算。它与保护区完全解耦；保护区停留/巡逻/返航必须另建任务。跟随尚未获得客户端持续运动、重算和失败边界验证，禁止据此接入攻击或普通矿链。
+- **R37（产品主线）**：Alice 按三条产品线组织里程碑：通用玩家助手、无 AE 阶段的玩家定义流水线、AE 阶段的网络/样板助理。共享移动、库存事务、权限和结果契约先稳定；不得用提前接入 LLM 掩盖执行层缺口。
+- **R38（兼容分级）**：模组兼容必须声明 `C0` 识别、`C1` 只读、`C2` 受限通用操作、`C3` 配置驱动语义、`C4` 模组原生适配、`C5` 端到端技能等级。capability 发现或教程知识不等于机器写操作兼容；版本不匹配时降级到已证明的只读等级。
+- **R39（计划门）**：LLM 的 ToolCall/PlanDraft 必须先经过 `Policy Gate / Plan Validator`，由代码校验 schema、权限、预算、资源、世界/适配器版本和后置条件。移动、攻击、库存和机器原语默认只供编排器使用，不作为常规 LLM 逐步工具。
+- **R40（知识边界）**：官方说明和目标网站教程应精炼为带来源/版本的本地指导手册，联网 RAG 仅作缺失或冲突时的后备。知识负责指导模型选择已注册工具，不能生成执行权限或替代 `ModAdapterPack` 的确定性 handler。
 - **坑**：掉落物有 pickupDelay；挖高处方块后掉落物需等 onGround 再拾取，未落地前不要反复跑昂贵 A*。
 - **坑**：`RenderType.lines()` 自带深度测试 → 透视高亮需自定义 RenderType（NO_DEPTH_TEST）。
 - **坑**：`stone` 是方块 ID 不是标签 → auto-mine 需自动判断标签/方块两种模式。
@@ -280,9 +285,9 @@ headless 验收：`./gradlew runServer -Dalice.selftest.auto=true`（测完自
 - WSL：`~/projects/alice`（开发/编译/headless 测试）
 - Windows：`D:\JAVA_projects\alice`（runClient 实测；`receive.denyCurrentBranch=updateInstead`）
 - GitHub：`dddgn486/Alice-mcbot`（公开存档 + Actions CI；SSH 推送）
-- 流程：WSL 改代码 → **编译验证（`./gradlew compileJava`）→ 更新本 HANDOVER（实际 HEAD、完成项、验证证据、未验证限制、下一安全步）→ 提交 → `git push origin master`（Windows）→ 用户实测 → `./tools/session-complete.sh` 生成监督审核包**。
+- 流程：监督员先对新模块做浅调研；仅在外部依赖/版本语义、高风险跨层、证据冲突、连续受阻或用户要求时派发 `.alice-supervision/research/` 深调研 → 监督员审核报告并在计划 `Research Decision` 写明采纳结论、证据等级与版本边界 → 监督员发布 `.alice-supervision/active-plan.md`（仅 `APPROVED_FOR_IMPLEMENTATION` 且所需报告齐全可开工）→ 主会话 `./tools/work-session-start.sh` 读取计划与调研结论 → WSL 改代码 → **编译验证（`./gradlew compileJava`）→ 更新本 HANDOVER（实际 HEAD、完成项、验证证据、未验证限制、下一安全步）→ 提交 → `git push origin master`（Windows）→ 用户实测 → `./tools/session-complete.sh` 生成含计划快照的监督审核包**。
 - **用户要求：每次修改同步到 Windows 端**，方便实测。本会话所有提交均已 push origin。
-- **二次审核规则**：每个完成会话都必须将交接记录写入本文件，并生成 `.alice-supervision/pending/<commit>.md` 审核包；审核员先读审核包、本文件、当前 Git HEAD 与相关设计文档，审查架构方向、R# 决策、任务边界和验证充分性。审核员不接管业务实现；发现架构漂移、实验能力提前接入稳定链、或记录与事实不一致时，必须明确阻止后续扩展或要求用户决策。完整协议见 `docs/SUPERVISION_PROTOCOL.md`；每个开发副本首次运行 `./tools/install-supervision-hook.sh` 安装本地 post-commit 自动入队。
+- **二次审核规则**：每个完成会话都必须将交接记录写入本文件，并生成 `.alice-supervision/pending/<commit>.md` 审核包；审核员先读审核包、本文件、当前 Git HEAD 与相关设计文档，审查架构方向、R# 决策、任务边界和验证充分性。审核员不接管业务实现；发现架构漂移、实验能力提前接入稳定链、或记录与事实不一致时，必须明确阻止后续扩展或要求用户决策。连续两次小修复仍不能改善同一客户端/跨层问题时，停止叠加特判，改为深调研或请用户选择保守降级/重新规划；调研不得阻塞无共享风险的稳定主线。完整协议见 `docs/SUPERVISION_PROTOCOL.md`；每个开发副本首次运行 `./tools/install-supervision-hook.sh` 安装本地 post-commit 自动入队。监督员在 DSH 中通过用户级 preset「Alice项目监督员」（`~/.dsh/.agent-presets/alice-supervisor`）以独立会话运行，打开时自动读取审核队列。
 - 推 GitHub 前先 `git fetch github` 检查冲突（遇到过 3 次 non-fast-forward）。
 
 ## 十、环境
```

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260821-product-architecture-review-v1`
- Status: NEEDS_USER_DECISION
- Created from: user restatement of Alice product goals and supervisor architecture review, 2026-08-21
- Approved by: pending user review
- Scope owner: `Alice project supervisor`

## Objective

Confirm Alice's three product lanes, modpack compatibility levels, LLM/policy/execution boundaries, parameter model, local knowledge strategy, and product-driven development sequence before the next implementation package.

## Delivery Lane

- Lane: `mainline architecture governance`.
- Relationship to active blockers: preserves conservative SOFT_SURFACE isolation and prevents a movement blocker from delaying independent C1 interface work.
- Throughput effect: gives general-assistant, pre-AE, and AE work explicit shared prerequisites so modules can proceed in parallel without relying on unverified capabilities.

## Documents Under Review

- `docs/PRODUCT_ARCHITECTURE_ROADMAP.md`
- `docs/AI_PLAYER_DESIGN.md`
- `docs/AI_PLAYER_NOTES.md`
- `docs/EXECUTION_FRAMEWORK.md`
- `docs/HANDOVER.md` R37-R40
- `README.md` design index

## Research Decision

- Supervisor shallow research: completed; recorded at `.alice-supervision/research/product-architecture-review-20260821.md`.
- Deep research: Not required
- Required report path(s): none.
- Adopted conclusions: capability discovery is not machine-operation compatibility; tutorials guide tool selection but do not authorize writes; Policy Gate/Plan Validator is mandatory; pre-AE production needs transaction and process contracts; AE2 requires a fixed-version adapter and topology/pattern evidence; compatibility must be reported as C0-C5.
- Rejected conclusions: no promise of automatic support for arbitrary mod machines, arbitrary pipelines, JEI-only pattern truth, or LLM step-level execution.
- Evidence confidence: current Alice code/design/client boundaries plus internal adversarial architecture review. Fixed-version API details remain future research topics.

## User Decisions Requested

1. Accept the three product lanes and product-driven phase order as the long-term planning baseline.
2. Accept C0-C5 as the required mod compatibility reporting scheme.
3. Accept `Policy Gate / Plan Validator` between LLM tools and all executable plans.
4. Accept local versioned guidance manuals as decision knowledge, separate from executable adapters.
5. Leave detailed product choices in roadmap section 10 for staged decisions rather than freezing them now.

## Explicitly Out Of Scope

- No business-code change or implementation authorization.
- No reopening of SOFT_SURFACE descent.
- No approval of inventory writes, machine configuration, PipelineProfile execution, AE2 pattern operations, AttackTask, task queue, or LLM network integration.

## Next Package After Acceptance

Prepare `interface-readonly-snapshot-v1` as compatibility level C1 only. Its fresh shallow research must inspect current scan call sites, Forge capability lifetime/absence states, snapshot schema, and human-readable projection. It must not add item transfer or infer machine slot semantics.
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
