# Alice work-session review packet

- Commit: `8bb2d7b1641c0b6e6063d4c78ae0357896fd0c90`
- Subject: feat: add readonly interface snapshots
- Authored: 2026-08-21T03:18:58+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260821-interface-readonly-snapshot-v1`
- Active plan status at handoff: `APPROVED_FOR_IMPLEMENTATION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
docs/HANDOVER.md
src/main/java/com/dddgn/alice/bot/BotSelftest.java
src/main/java/com/dddgn/alice/capability/InterfaceScanner.java
src/main/java/com/dddgn/alice/capability/InterfaceSnapshot.java
src/main/java/com/dddgn/alice/capability/ObservationStatus.java
src/main/java/com/dddgn/alice/item/AliceItems.java
src/main/java/com/dddgn/alice/tool/ScanWand.java
src/main/resources/assets/alice/lang/en_us.json
src/main/resources/assets/alice/lang/zh_cn.json
src/main/resources/assets/alice/models/item/interface_scanner.json
```

## Commit summary

```text
 docs/HANDOVER.md                                   |   4 +-
 src/main/java/com/dddgn/alice/bot/BotSelftest.java |  29 +++++
 .../dddgn/alice/capability/InterfaceScanner.java   | 122 +++++++++++++++++++--
 .../dddgn/alice/capability/InterfaceSnapshot.java  |  46 ++++++++
 .../dddgn/alice/capability/ObservationStatus.java  |   9 ++
 src/main/java/com/dddgn/alice/item/AliceItems.java |   4 +
 src/main/java/com/dddgn/alice/tool/ScanWand.java   |  15 +--
 src/main/resources/assets/alice/lang/en_us.json    |   1 +
 src/main/resources/assets/alice/lang/zh_cn.json    |   1 +
 .../alice/models/item/interface_scanner.json       |   6 +
 10 files changed, 218 insertions(+), 19 deletions(-)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index 36d01e8..2799a3d 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -39,7 +39,9 @@ GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push
 
 按批准计划 `20260821-task-observability-v1`，`BotSession` 新增只读不可变 `TaskExecutionRecord`，区分当前任务摘要与最近终端记录。记录包含任务类型、语义目标、起止 server tick、终端状态、原有稳定结果码、终端 bot 方块位置与恢复状态；完成、失败、维生中断、follow 取消、替换取消、road-plan/未实现目标的启动前拒绝均记录稳定 `task_execution_terminal` 日志。`/alice status` 只读展示 `current` 和 `latest`，同时保留原有 `last/pos/hazard`。没有改变 Task 生命周期、成功/失败条件、移动、world edit 或任何客户端交互。
 
-已执行验证：本轮 `./gradlew compileJava` 成功（仅既有弃用警告）；headless `./gradlew runServer -Dalice.selftest.auto=true` 的既有完整 smoke 仍在外部 180 秒时限内未完成并以 `143` 退出，不能报告整套通过，但 `TASK_OBSERVABILITY_SELFTEST PASS` 已在 TEST1 终端路径输出，并验证 `COMPLETED/done/idle_after_cleanup` 及终端位置记录；`task_execution_terminal` 也实际输出 start/end/duration、status、code、position 和 recovery。该内部只读记录不需要 Windows 客户端验收，也不替代既有 SOFT_SURFACE 客户端物理证据。
+已执行验证：本轮 `./gradlew compileJava` 成功（仅既有弃用警告）；headless `./gradlew runServer -Dalice.selftest.auto=true` 的 `INTERFACE_SNAPSHOT_SELFTEST PASS` 验证原版箱子 `OK`、27 个显式槽位、slot 0 `minecraft:diamond×3`，以及泥土 `NO_BLOCK_ENTITY`；完整既有 smoke 仍在外部 180 秒时限内未完成并以 `143` 退出，不能报告整套通过。C1 快照不保留 capability handler、ItemStack、tag、block entity 或 level 引用；能量/流体路径有编译覆盖但没有专用 fixture，未写成运行时 PASS。
+
+`alice:interface_scanner` 的独立物品模型/名称、右键扫描行为，以及普通 `minecraft:diamond_shovel` 不再触发 Alice 扫描仍待 Windows 客户端确认；该入口不证明 C2/C3/C4 操作兼容性，也不替代既有 SOFT_SURFACE 客户端物理证据。
 
 不能回退的决策：普通挖矿、拾取仍固定 `HARD_PATH`；`MineTask` 不生成道路或隧道，深层目标失败 `target_requires_tunnel`；A* 不破坏方块；本地清障仍仅限直接可见、4.5 格内、最多 2 个方块。`SOFT_SURFACE` 不得接入 `MineTask`、`DropCollectionTask`、道路或隧道，除非逐项客户端验证和单独决策批准。
 
```

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260821-interface-readonly-snapshot-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Created from: accepted `PRODUCT_ARCHITECTURE_ROADMAP.md`, R38 C1 compatibility level, `.alice-supervision/research/interface-readonly-snapshot-shallow-20260821.md`, and the user's request to replace the vanilla diamond-shovel hook with an Alice-owned test item
- Approved by: user selected `批准实施（推荐）`, then explicitly added the independent scanner-item scope, 2026-08-21
- Scope owner: `Alice project supervisor`

## Objective

Separate server-side capability observation from presentation by introducing an immutable, versioned C1 readonly interface snapshot for unsided Forge item, energy, and fluid capabilities. Preserve `/alice scan`, and replace the vanilla diamond-shovel hook with an Alice-owned `alice:interface_scanner` test item that only reuses the diamond-shovel model texture.

## Why This Slice

`InterfaceScanner` currently reads mutable capability handlers while directly building a display string. That output is useful to a human but cannot safely support later Policy Gate, WorldProfile, local guidance or adapter work. The smallest useful increment is a synchronous fact capture and formatter split. Item transfer, machine semantics and serialization are separate higher-risk packages.

## Delivery Lane

- Lane: `independent`.
- Relationship to active blockers: isolated from movement, mining, collection, roads, combat and frozen SOFT_SURFACE descent.
- Why this package preserves overall project throughput: it advances product stage C and compatibility level C1 while movement issues remain frozen, and creates the fact contract required before inventory-transfer design.

## Allowed Scope

- `src/main/java/com/dddgn/alice/capability/InterfaceScanner.java` may be refactored to delegate to a structured capture and formatter.
- New narrowly scoped immutable records/classes under `com.dddgn.alice.capability` for snapshot, descriptor and item/energy/fluid values.
- `BotCommand` and `ScanWand` only as necessary to consume the same formatter/snapshot and move the right-click hook from vanilla `minecraft:diamond_shovel` to the Alice-owned scanner item.
- `AliceItems` may register one independent non-tool test item with registry id `alice:interface_scanner`; the item may reuse the vanilla diamond-shovel model texture but must not subclass or replace vanilla shovel behavior.
- Add `assets/alice/models/item/interface_scanner.json` and `item.alice.interface_scanner` entries in `zh_cn.json` and `en_us.json`; keep resource changes limited to this item.
- A focused deterministic selftest or test helper under the existing headless framework, limited to readonly snapshot assertions.
- `docs/HANDOVER.md`, `README.md` or interface documentation only to record the actual C1 contract and evidence.
- Snapshot facts must include: schema version, dimension id, immutable position, block id, optional block-entity type id, observed server tick, observation status, and copied item/energy/fluid values.
- Item values: slot index, item id, count, damage, and an optional copied tag representation; empty slots explicit.
- Energy values: stored, capacity, canExtract and canReceive.
- Fluid values: tank index, fluid id, amount, capacity, optional copied tag representation; empty tanks explicit.
- Distinguish at minimum `OK`, `NO_BLOCK_ENTITY`, `CHUNK_NOT_LOADED`, and `CAPTURE_ERROR`. An absent capability is a valid C1 observation, not a capture error.
- Capture synchronously on the server thread and copy all mutable values. Do not retain `LazyOptional`, handlers, stacks, tags, block entities, levels or other live capability references.
- Preserve the existing human-readable scan behavior sufficiently for operators; generic slot-role hints must be removed or visibly labeled heuristic and must not be stored as facts.
- Existing Mek-specific scan output may remain as a clearly labeled legacy/adapter projection, but is not part of the generic v1 schema.
- The server-side scan event must trigger only when the main-hand stack is `AliceItems.INTERFACE_SCANNER`; ordinary diamond shovels must retain vanilla GUI/terrain interaction and never invoke Alice scanning.

## Explicitly Out Of Scope

- No item/fluid/energy insertion, extraction, transfer, mutation or acceptance simulation.
- No side-specific capability enumeration or endpoint-role inference.
- No machine input/output slot semantics, mode interpretation, catalyst/byproduct rules or process completion semantics.
- No cache, persistence, SavedData, JSON/network protocol, packet, GUI screen, LLM tool, Policy Gate implementation or permission change.
- No new Mekanism/AE2 write adapter and no normalization of all existing Mek-specific states.
- No modifications to tasks, bot lifecycle, movement, HARD_PATH/SOFT_SURFACE, mining, collection, roads, FollowTask or survival behavior.

## Preconditions

- Baseline revision: `d1def08676a3e74ef9927011edb7cf2a75e9a0ba`, already pushed to `origin/master`.
- Product architecture is user accepted; R38 requires this package to claim C1 only.
- Existing dirty governance files (`.gitignore`, `AGENTS.md`, `docs/SUPERVISION_PROTOCOL.md`, `docs/supervision/`, `tools/`) must not be reverted or included in the functional commit unless separately assigned.

## Research Decision

- Supervisor shallow research: completed in `.alice-supervision/research/interface-readonly-snapshot-shallow-20260821.md`. Current code has only two read-only callers and already uses the required Forge 1.20.1 capabilities. A capture/formatter split is local and version-bounded.
- Deep research: Not required
- Required report path(s): none.
- Report conclusions adopted by this plan: copy synchronously; never retain capability/live mutable references; keep unsided discovery as the existing v1 contract; separate absent capability from capture failure; exclude slotHint from facts; preserve Mek-specific output only as non-generic legacy/adapter projection.
- Rejected/expired research conclusions: prior “90% automatic machine compatibility” and “tutorial implies operation” assumptions are explicitly superseded by R38-R40.
- Evidence confidence and version boundary: local Forge 1.20.1-47.4.10 code and compiled APIs; no promise across Forge/mod versions and no write semantics.

## Implementation Steps

1. Define the immutable snapshot/fact records and implement synchronous capture for unloaded/no-BE/unsided item-energy-fluid states, with copied values and stable schema/status enums.
2. Refactor `InterfaceScanner` into capture plus human formatter and mark Mek-specific output as outside the generic schema.
3. Register `alice:interface_scanner`, add its model/localizations, and change `ScanWand` so only this item activates scanning while a vanilla diamond shovel keeps normal behavior.
4. Add focused deterministic evidence for a known vanilla container and a no-block-entity block. Compile and stop at handoff; do not add a transfer operation to make testing easier.

Stop and return to supervision if implementation requires side-specific semantics, capability caching, cross-thread access, persistence/network serialization, machine writes, or a new mod API.

## Client Test Entry

Required for the independent scanner item only; snapshot correctness remains headless/server verified.

- Obtain `alice:interface_scanner` through `/give @s alice:interface_scanner` or the creative inventory.
- Confirm its localized name and diamond-shovel appearance, without vanilla shovel attributes or terrain-flattening behavior.
- Right-click a known machine/container: default GUI interaction is cancelled, Alice reports a completed scan, and the log contains the structured human projection.
- Right-click a non-block-entity block: Alice reports no block entity without exception or world mutation.
- Hold a normal `minecraft:diamond_shovel` and right-click dirt/path-capable terrain and a machine: vanilla behavior remains available and no Alice scan log/message appears.
- Isolation: neither entry may create/assign a bot task, move a bot, mutate machine inventory, invoke mining, roads, FollowTask or SOFT_SURFACE.
- Failure recovery: item use is one synchronous read; failure returns a message and leaves player/world recoverable with no retry loop.

## Verification Evidence

- Required: `./gradlew compileJava`.
- Required focused headless evidence:
  - a known vanilla container snapshot records the expected slot/item/count and stable metadata;
  - a non-block-entity target returns `NO_BLOCK_ENTITY` rather than exception/error;
  - formatting a captured snapshot does not alter the underlying world/container;
  - snapshot collections and captured mutable values cannot change after later world/container mutation.
- Energy/fluid paths: run a deterministic fixture if one already exists or can be added without mod-specific setup. Otherwise record compile coverage plus a manual known-machine scan as unverified runtime coverage; do not claim PASS.
- Existing Mek-specific output is regression-inspected only unless a deterministic Mek fixture is already available.
- Required resource/build check: the item model and both locale entries are packaged, and no missing-model/missing-translation warning is introduced.
- Windows client acceptance is required only for the new item identity, appearance, right-click isolation and restored vanilla diamond-shovel behavior. This package proves no C2/C3/C4 operation compatibility.

## User Acceptance Gate

Before implementation: satisfied by the user's C1 approval and explicit independent-item addition. After implementation: supervisor reviews headless evidence, then the package remains `CLIENT_TEST_PENDING` until the user confirms `alice:interface_scanner` appearance/scan behavior and that the vanilla diamond shovel no longer triggers Alice scanning.

## Handoff Rules

- Main work session must run `./tools/work-session-start.sh`, implement only Allowed Scope, run required checks, update `docs/HANDOVER.md`, commit, push `origin/master`, and run `./tools/session-complete.sh`.
- Any discovery that changes this scope stops implementation and returns to the supervisor for replanning.
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
