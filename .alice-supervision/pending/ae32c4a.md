# Alice work-session review packet

- Commit: `ae32c4a205856af30d93981b27ccaed7686ba5a0`
- Subject: feat: record task execution outcomes
- Authored: 2026-08-21T01:56:57+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260821-task-observability-v1`
- Active plan status at handoff: `APPROVED_FOR_IMPLEMENTATION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
docs/HANDOVER.md
src/main/java/com/dddgn/alice/bot/BotManager.java
src/main/java/com/dddgn/alice/bot/BotSelftest.java
src/main/java/com/dddgn/alice/bot/TaskExecutionRecord.java
src/main/java/com/dddgn/alice/command/BotCommand.java
```

## Commit summary

```text
 docs/HANDOVER.md                                   |   4 +-
 src/main/java/com/dddgn/alice/bot/BotManager.java  | 129 ++++++++++++++++-----
 src/main/java/com/dddgn/alice/bot/BotSelftest.java |  14 ++-
 .../com/dddgn/alice/bot/TaskExecutionRecord.java   |  36 ++++++
 .../java/com/dddgn/alice/command/BotCommand.java   |   9 ++
 5 files changed, 157 insertions(+), 35 deletions(-)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index 9654864..f42815b 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -37,7 +37,9 @@ GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push
 
 按批准计划 `20260820-soft-path-descent-landing-v1`，本轮未修改 A*、对角逻辑或任何稳定任务链。下降段使用统一 `supportTopY` 读取实际 `VoxelShape` 顶面；到达水平半径后显式进入下降 settle 状态，记录 `actualY/supportTopY/onGround/settleTicks`，仅在目标脚位、顶面和 `onGround` 同时成立时完成，失败保持 `soft_path_unsettled` 并带完整诊断。
 
-已执行验证：本轮 `./gradlew compileJava` 成功（仅既有弃用警告）；headless `./gradlew runServer -Dalice.selftest.auto=true` 日志记录既有路径回归和专项回归全部 `PASS`：`full_block_edge`、`lower_half_slab`（支撑顶面 `59.5`）、`unsupported_landing`，以及此前的对角侧阻塞、下降扫掠阻塞、直线择优。完整既有 selftest 仍在外部 180 秒时限内未完成并以 `143` 退出，不能报告整套通过；专项回归已在初始化阶段完成。客户端下降与落地行为仍未验证，不能用 headless 结果替代 Windows 证据。
+按批准计划 `20260821-task-observability-v1`，`BotSession` 新增只读不可变 `TaskExecutionRecord`，区分当前任务摘要与最近终端记录。记录包含任务类型、语义目标、起止 server tick、终端状态、原有稳定结果码、终端 bot 方块位置与恢复状态；完成、失败、维生中断、follow 取消、替换取消、road-plan/未实现目标的启动前拒绝均记录稳定 `task_execution_terminal` 日志。`/alice status` 只读展示 `current` 和 `latest`，同时保留原有 `last/pos/hazard`。没有改变 Task 生命周期、成功/失败条件、移动、world edit 或任何客户端交互。
+
+已执行验证：本轮 `./gradlew compileJava` 成功（仅既有弃用警告）；headless `./gradlew runServer -Dalice.selftest.auto=true` 的既有完整 smoke 仍在外部 180 秒时限内未完成并以 `143` 退出，不能报告整套通过，但 `TASK_OBSERVABILITY_SELFTEST PASS` 已在 TEST1 终端路径输出，并验证 `COMPLETED/done/idle_after_cleanup` 及终端位置记录；`task_execution_terminal` 也实际输出 start/end/duration、status、code、position 和 recovery。该内部只读记录不需要 Windows 客户端验收，也不替代既有 SOFT_SURFACE 客户端物理证据。
 
 不能回退的决策：普通挖矿、拾取仍固定 `HARD_PATH`；`MineTask` 不生成道路或隧道，深层目标失败 `target_requires_tunnel`；A* 不破坏方块；本地清障仍仅限直接可见、4.5 格内、最多 2 个方块。`SOFT_SURFACE` 不得接入 `MineTask`、`DropCollectionTask`、道路或隧道，除非逐项客户端验证和单独决策批准。
 
```

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260821-task-observability-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Created from: user-approved parallel roadmap and process confirmation, 2026-08-21
- Approved by: user selected `task-observability-v1` as the first package, 2026-08-21
- Scope owner: `Alice project supervisor`

## Objective

Add a unified, read-only task execution record so operators and future decision tools can distinguish the current task from the latest terminal result, its duration, failure code, final bot position, and recovery state without changing task behavior.

## Why This Slice

`BotSession` currently stores only `lastTaskResult` and `lastMineStartPos`; success, task failure, survival interruption, invalid road-plan rejection, and user follow stop use separate paths. This blocks reliable audit and later target-level decision integration. The smallest useful increment is an internal record plus a read-only status projection, not a task scheduler or behavior rewrite.

## Delivery Lane

- Lane: `mainline`.
- Relationship to active blockers: isolated from frozen SOFT_SURFACE descent; it records existing results without enabling or changing movement.
- Why this package preserves overall project throughput: it establishes a shared task-result contract while the next independent capability-readonly package is prepared.

## Allowed Scope

- `src/main/java/com/dddgn/alice/bot/BotManager.java` and a narrowly scoped task-record type under `bot/` if needed.
- `src/main/java/com/dddgn/alice/command/BotCommand.java` only for read-only `/alice status` presentation.
- `src/main/java/com/dddgn/alice/bot/BotSelftest.java` only if a deterministic observability assertion is practical without changing test scenario behavior.
- `docs/HANDOVER.md` and required supervision handoff records.
- Record: task kind, semantic target description, start/end server tick, terminal status, stable failure/result code, terminal bot block position, and recovery state (`idle_after_cleanup` or `not_started`).
- Preserve legacy `lastTaskResult()` behavior for existing callers unless a compatibility-safe equivalent is proven.
- Log one stable terminal record key for completed, failed, survival-interrupted, cancelled-follow, and rejected-before-start outcomes.

## Explicitly Out Of Scope

- No change to `Task` lifecycle semantics, MineTask, DropCollectionTask, BotMiner, PathExecutor, SurfacePathfinder, roads, placement, FollowTask, or SOFT_SURFACE physics.
- No new task queue, LLM interface, persistence format, network packet, GUI, file telemetry, or automatic recovery/retry.
- No change to success criteria, failure codes, timeout values, highlighter behavior, scope behavior, or client-visible movement.
- No changes to `HARD_PATH` / `SOFT_SURFACE` boundaries.

## Preconditions

- Baseline revision is `d11e851dd8ac18a02cf03d860ebede7612946b97`; `origin/master` matched during supervisor shallow research.
- Existing governance changes in the dirty worktree are user/supervision-owned and must not be reverted or mixed with functional behavior changes.
- The active plan was recreated because `.alice-supervision/` state files were absent at planning time.

## Research Decision

- Supervisor shallow research: reviewed current Git status, `docs/HANDOVER.md`, `docs/AI_PLAYER_DESIGN.md`, `docs/EXECUTION_FRAMEWORK.md`, `BotManager`, `Task`, MineTask, DropCollectionTask, and command status paths. `BotSession` has only a string result and start position; terminal paths are inconsistent. The change is local, does not depend on external version semantics, and can preserve all task behavior.
- Deep research: Not required
- Required report path(s): none.
- Report conclusions adopted by this plan: none.
- Rejected/expired research conclusions: the movement research report is not relevant to this package and grants no scope beyond its frozen SOFT_SURFACE conclusions.
- Evidence confidence and version boundary: local code and current commit facts; Forge `1.20.1-47.4.10`, Java 17. No external API dependency.

## Implementation Steps

1. Introduce an immutable or effectively immutable terminal execution record and update it from all existing BotSession terminal paths without altering state transitions.
2. Project the current/last record through read-only status output and a stable log key; retain legacy status fields for compatibility.
3. Run compile and focused deterministic checks. Stop and return to supervision if any change requires modifying task state machines, terminal semantics, or client test entry behavior.

## Client Test Entry

Not required for this internal-only slice. `/alice status` is a read-only operator command; no GUI, entity movement, world edit, or task behavior may change.

## Verification Evidence

- `./gradlew compileJava`.
- A focused deterministic assertion or selftest log, if implemented without broadening existing scenarios.
- Manual command/readback evidence for idle, running, success/failure, survival interruption or explicit follow-stop paths where available.
- No Windows client acceptance is claimed or needed for the record structure itself; any observed task behavior remains governed by its existing client-test status.

## User Acceptance Gate

Not required for this internal-only slice. The supervisor must review the implementation packet and confirm that no task behavior or client-visible interaction was changed before issuing the next work package.

## Handoff Rules

- Main work session must update `docs/HANDOVER.md`, commit, push `origin/master`, and run `./tools/session-complete.sh`.
- Any discovery that changes this scope stops implementation and returns to the supervisor for replanning.
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
