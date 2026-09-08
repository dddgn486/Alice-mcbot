# Alice work-session review packet

- Commit: `ab510fdd4cc9c4ae4f73e5661b1b2b76fa8214d6`
- Subject: fix(soft): P1 client sync - broadcast bot position/velocity packets
- Authored: 2026-08-24T00:24:48+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260824-p1-client-sync-fix-v1`
- Active plan status at handoff: `APPROVED_FOR_IMPLEMENTATION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
.alice-supervision/active-plan.md
.alice-supervision/client-tests/65f4863-soft-path-test-tool-retest-result.md
.alice-supervision/client-tests/65f4863-soft-path-test-tool-retest.md
.alice-supervision/pending/65f4863.md
.alice-supervision/research/p1-physics-collision-knockback-investigation-20260824.md
.alice-supervision/research/p1-physics-collision-knockback-investigation-task.txt
.dsh-runtime/sessions/--home-fb486-projects-alice--/session-0e50f21c-fd6f-4b0b-ac69-19a3c9f3b1aa/session.jsonl.zstd
.dsh-runtime/sessions/--home-fb486-projects-alice--/session-302a9da6-184c-4338-9567-56e853b501ed/session.jsonl.zstd
.dsh-runtime/sessions/--home-fb486-projects-alice--/session-30b693e7-9115-4e57-914b-854bf9ca2510/session.jsonl.zstd
.dsh-runtime/sessions/--home-fb486-projects-alice--/session-f26bd205-c692-4c4e-849d-576fd8bc7ff6/session.jsonl.zstd
.dsh-runtime/storages/agent_bus.json
.dsh-runtime/storages/session_projcache.json
docs/HANDOVER.md
src/main/java/com/dddgn/alice/bot/BotManager.java
src/main/java/com/dddgn/alice/bot/FakeConnection.java
```

## Commit summary

```text
 .alice-supervision/active-plan.md                  | 129 ++++++-----
 .../65f4863-soft-path-test-tool-retest-result.md   |  72 ++++++
 .../65f4863-soft-path-test-tool-retest.md          |  98 ++++++++
 .alice-supervision/pending/65f4863.md              | 171 ++++++++++++++
 ...s-collision-knockback-investigation-20260824.md | 235 ++++++++++++++++++++
 ...sics-collision-knockback-investigation-task.txt | 110 +++++++++
 .../session.jsonl.zstd                             | Bin 2285755 -> 2285835 bytes
 .../session.jsonl.zstd                             | Bin 15113159 -> 15389194 bytes
 .../session.jsonl.zstd                             | Bin 7322144 -> 7457636 bytes
 .../session.jsonl.zstd                             | Bin 2767461 -> 3108505 bytes
 .dsh-runtime/storages/agent_bus.json               |  78 ++++++-
 .dsh-runtime/storages/session_projcache.json       | 246 ++++++++++-----------
 docs/HANDOVER.md                                   |   2 +
 src/main/java/com/dddgn/alice/bot/BotManager.java  |   3 +-
 .../java/com/dddgn/alice/bot/FakeConnection.java   |  36 ++-
 15 files changed, 998 insertions(+), 182 deletions(-)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index e9027ce..4908a05 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -41,6 +41,8 @@ GitHub：`github` 远端 `dddgn486/Alice-mcbot`（本会话未推 GitHub，push
 
 按批准计划 `20260824-soft-path-test-tool-v1`（方案 A），扩展现有 `soft_path_probe_selector` 为软路径测试工具：右键点击 support 方块 → 软寻路到 support.above()（SoftPathProbeTask，已有不变）；Shift+右键 → 软寻路到 support.above() + 到达后挖掘 support（新增 `SoftPathMineTask`，测试工具专用）。新增 `task/SoftPathMineTask.java`（约 320 行）：Phase.SOFT_NAVIGATE 完整复用 P1 逻辑（SurfacePathfinder + 逐段 NATIVE_TRAVEL + P1 观测字段 deltaMovement/supportTopY/collision/deviation/velocityDisruption + 偏离检测 hDev>2.0/vDev>1.5/noProgressTicks>20 + revalidateSegment + replanFromCurrent MAX_REPLAN=3 + 稳定失败码 soft_mine_no_path/search_limit/replan_*），Phase.MINING 调用 `BotMiner.tick()`（复用挖掘原语，不收集掉落物）。`BotManager.assignSoftPathMine(bot, target)` + `BotSession.assignSoftPathMine(targetPos)` 提供专用入口。`item/SoftPathProbeSelector.useOn` 增加 `player.isShiftKeyDown()` 判定，Chat 消息区分 `soft-path-test` / `soft-path-mine-test`，日志输出软路径点击探针/软路径挖掘点击。**冻结边界守护**：MineTask.java 保持 HARD_PATH 完全不动（不改一行）；独立任务类（SoftPathMineTask 与 MineTask 隔离）；专用入口（assignSoftPathMine，不改 assign）；无自动切换（失败不回退 MineTask）。服务端验证：`./gradlew compileJava` PASS，既有 suites 保持 PASS at 19:47:06。Windows 客户端 T1-T4 测试矩阵（右键软寻路、Shift+右键软寻路+挖掘、P1 观测日志、MineTask 不受影响）CLIENT_TEST_PENDING。
 
+按批准计划 `20260824-p1-client-sync-fix-v1`，修复 P1 客户端同步失效问题（T4 发现）。**根因**：服务端物理完全正常（bot 碰撞/击退正常、P1 观测日志正确），但 `FakeConnection.send(Packet)` 空实现丢弃所有 S2C 包，`ServerEntity` 的位置/速度同步包（`ClientboundMoveEntityPacket` / `ClientboundSetEntityMotionPacket` / `ClientboundTeleportEntityPacket`）被阻断，客户端永不更新 bot 物理状态。**修复**：修改 `bot/FakeConnection.java`，构造时传入 `BotPlayer bot` 引用（`BotManager.spawn:75` 调用处修改）；`send(Packet)` 和 `send(Packet, PacketSendListener)` 识别位置/速度包（`instanceof ClientboundMoveEntityPacket/SetEntityMotion/TeleportEntity`）并调用新增 `broadcastToRealPlayers(packet)`；该方法遍历 `bot.getServer().getPlayerList().getPlayers()`，排除 `instanceof BotPlayer`，仅广播给同 level 真实玩家（`player.connection.send(packet)`）；其他包继续丢弃（bot 自己不需要）。服务端验证：`./gradlew compileJava` PASS，既有 suites 保持 PASS at 00:21:36。Windows 客户端 T4a/T4b/T4c CLIENT_TEST_PENDING（T4a 玩家一格宽通道堵 bot 被阻挡、T4b 攻击 bot 后击退可见位移、T4c HARD_PATH 不受影响）。
+
 按批准计划 `20260821-task-observability-v1`，`BotSession` 新增只读不可变 `TaskExecutionRecord`，区分当前任务摘要与最近终端记录。记录包含任务类型、语义目标、起止 server tick、终端状态、原有稳定结果码、终端 bot 方块位置与恢复状态；完成、失败、维生中断、follow 取消、替换取消、road-plan/未实现目标的启动前拒绝均记录稳定 `task_execution_terminal` 日志。`/alice status` 只读展示 `current` 和 `latest`，同时保留原有 `last/pos/hazard`。没有改变 Task 生命周期、成功/失败条件、移动、world edit 或任何客户端交互。
 
 已执行验证：`./gradlew compileJava` 成功（仅既有弃用警告）；headless `./gradlew runServer -Dalice.selftest.auto=true` 的 `INTERFACE_SNAPSHOT_SELFTEST PASS` 验证原版箱子 `OK`、27 个显式槽位、slot 0 `minecraft:diamond×3`、泥土 `NO_BLOCK_ENTITY`，并按 `20260821-interface-c1-maintenance-f1-f4-v1` 新增三项 F1 断言：`formatterPure`（`InterfaceScanner.format` 不改变来源箱子内容与已捕获快照字段）、`postCaptureStable`（源箱子 slot0 改为 iron_ingot×7 后原快照仍为 diamond×3）、`itemsImmutable`（`List.copyOf` items 拒绝 add 并抛 `UnsupportedOperationException`），三项连同基线断言汇总为单一 `INTERFACE_SNAPSHOT_SELFTEST PASS|FAIL` 且日志输出每项可审核布尔值。完整既有 smoke 仍在外部 180 秒时限内未完成并以 `143` 退出，不能报告整套通过。C1 快照不保留 capability handler、ItemStack、tag、block entity 或 level 引用；能量/流体路径有编译覆盖但没有专用 fixture，未写成运行时 PASS（本轮按计划未新增任何 machine/fluid/energy/mock/tick/chunk fixture）。
```

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260824-p1-client-sync-fix-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Baseline: `65f4863`（测试工具已实施，T1-T3 通过，T4 发现同步问题）
- User approval: explicit approval received (2026-08-24); 批准修复 P1 客户端同步问题（选项 1）
- Investigation report: `.alice-supervision/research/p1-physics-collision-knockback-investigation-20260824.md` (deep research, supervisor accepted)

## Objective

修复 P1 客户端同步失效问题：FakeConnection 丢弃所有 S2C 包，导致客户端看到陈旧 bot 位置。实施方案 A（广播 bot 位置/速度包给真实玩家），使客户端正确观察 bot 物理状态（碰撞/击退）。

## Root Cause

- **服务端物理完全正常** ✅：bot 与玩家碰撞检测正常、击退正常、P1 观测日志正确
- **客户端同步失败** ❌：`FakeConnection.send(Packet)` 空实现（alice/bot/FakeConnection.java:35-37）丢弃所有 S2C 包，`ServerEntity` 的位置/速度同步包（`ClientboundMoveEntityPacket` / `ClientboundSetEntityMotionPacket`）被阻断，客户端永不更新 bot 物理状态

## Allowed Scope - Fix Approach A

### Core Implementation

修改 `FakeConnection.java`，识别并广播 bot 位置/速度包给真实玩家：

```java
@Override
public void send(Packet<?> packet) {
    // 识别位置/速度包
    if (packet instanceof ClientboundMoveEntityPacket
        || packet instanceof ClientboundSetEntityMotionPacket
        || packet instanceof ClientboundTeleportEntityPacket) {
        broadcastToRealPlayers(packet);
    }
    // 其他包丢弃（bot 自己不需要）
}

private void broadcastToRealPlayers(Packet<?> packet) {
    // 获取 bot 实体（需添加字段或反射）
    // 广播给所有真实玩家（排除 bot 自己）
    for (ServerPlayer realPlayer : server.getPlayerList().getPlayers()) {
        if (!(realPlayer instanceof BotPlayer)) {
            realPlayer.connection.send(packet);
        }
    }
}
```

### Alternative: BotManager Integration

如果 FakeConnection 难以获取 server/bot 引用，可在 `BotManager.spawn` 后订阅 `ServerEntity.sendChanges`：

```java
// BotManager.java spawn 后
ServerEntity tracker = level.getChunkSource().chunkMap.entityMap.get(bot.getId());
// 每 tick 手动广播 bot 位置/速度给真实玩家
```

### Files to Modify

- `src/main/java/com/dddgn/alice/bot/FakeConnection.java`：增加广播逻辑
- 可能需修改 `src/main/java/com/dddgn/alice/bot/BotManager.java`：添加 server 引用或 tracker 订阅

## Verification Matrix

### 服务端
- `./gradlew compileJava` PASS
- `git diff --check` PASS
- 既有 suites 保持 PASS（特别是 SOFT_PHYSICS_OBSERVATION_SUITE）

### 客户端（Windows，重新测试 T4）

| 场景 | 操作 | 预期（修复后） |
|---|---|---|
| T4a 玩家堵路 | 一格宽通道，玩家堵路，Shift+右键寻路 | bot 被玩家碰撞箱阻挡，客户端可见 bot 停止或绕路，日志含 soft_phys_obs horizontalCollision=true / revalidate |
| T4b 击退 | 软寻路途中玩家攻击 bot（剑击退） | bot 被击退（客户端可见位移动画），日志含 soft_phys_disruption velocityChange 非零 / revalidate |
| T4c HARD_PATH 不受影响 | `/alice mine <矿>` | bot 移动正常（既有行为不变） |

## Explicitly Forbidden

- 不改 P1 观测逻辑（SoftPathProbeTask/FollowTask/SoftPathMineTask）
- 不改 HARD_PATH（PathExecutor/MineTask）
- 不改服务端物理（BotPlayer 保持不覆盖 travel/aiStep/hurt/push）
- 广播不能导致重复包或客户端抖动

## Stop Conditions

Stop and return for replanning if:
- FakeConnection 无法获取 server/bot 引用（需重新设计架构）
- 广播导致客户端抖动/重复包/性能问题
- ServerEntity tracker 订阅影响既有 HARD_PATH 同步
- 修复后 T4a/T4b 仍失败（需更深层调查）

## Implementation Time Estimate

- 实施：2-4 小时（FakeConnection 广播逻辑 + server 引用传递 + 既有代码影响评估）
- 编译+既有测试：0.5 小时
- HANDOVER：0.5 小时
- commit/push：0.5 小时
- **客户端复测**：0.5-1 小时（T4a/T4b/T4c）
- **总计：4-6.5 小时**

## Background Research (completed)

- P1 physics investigation: `.alice-supervision/research/p1-physics-collision-knockback-investigation-20260824.md`
- Root cause: FakeConnection blocks S2C packets, not physics failure
- Server-side physics fully functional (verified by Forge 1.20.1 source code analysis)

## Previous Work (completed)

- P1 implementation (`2298dd3`): physics observation & disturbance recovery
- Test tool (`65f4863`): soft path probe selector with Shift+click mining
- Test tool client test: T1-T3 PASS, T4 discovered sync issue

## Notes

- 修复后，P1 + 测试工具全部完成（T1-T4 全 PASS）
- 证明软路径"更真实玩家定位"方向技术可行
- 为未来 P2-P5 打下基础
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
