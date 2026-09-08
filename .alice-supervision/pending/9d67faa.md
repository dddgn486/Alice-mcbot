# Alice work-session review packet

- Commit: `9d67faabcb2d8b139fb7827d913fb7505f6d7828`
- Subject: feat(phys): F1-F6 server-physics assertion fixture (evidence only, no root-cause)
- Authored: 2026-08-25T21:28:41+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260824-p1-client-sync-fix-v1`
- Active plan status at handoff: `APPROVED_FOR_IMPLEMENTATION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
.alice-supervision/research/f1-f6-assertion-result-20260825.md
docs/HANDOVER.md
src/main/java/com/dddgn/alice/bot/BotSelftest.java
src/main/java/com/dddgn/alice/task/BotPhysicsAssertionFixture.java
```

## Commit summary

```text
 .../research/f1-f6-assertion-result-20260825.md    |  65 +++++
 docs/HANDOVER.md                                   |   9 +
 src/main/java/com/dddgn/alice/bot/BotSelftest.java |   3 +
 .../alice/task/BotPhysicsAssertionFixture.java     | 278 +++++++++++++++++++++
 4 files changed, 355 insertions(+)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index 5a0f90e..0a8db91 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -391,3 +391,12 @@ headless 验收：`./gradlew runServer -Dalice.selftest.auto=true`（测完自
 - **P1 重启条件**（供未来评估）：官方 Git server 支持工具过滤 / 用户授权自建最小只读 Git server（4 工具白名单）/ 转向其他成熟 MCP server（filesystem/fetch 等）
 - 边界遵守：未改 DSH checkout、未改 3083 生产 profile（任何生产 MCP 接入均需独立新授权）、零 env 注入、无写工具暴露
 - 遗留物：alice-mcp-lab(3090 运行中)/alice-git-lab(3091 已停) 两个隔离 profile 保留为模板；`~/.local/bin/mcp-server-git` 已安装（可 pip uninstall 清理）；`/tmp/mcp-test-server`、`/tmp/mcp-servers` 为临时文件
+
+## F1-F6 服务端物理断言（2026-08-25，主开发实施，只收集证据不定案）
+
+- 依据：active plan `20260825-f1f6-bot-physics-assertions-v1`（APPROVED_FOR_IMPLEMENTATION，用户批准 2026-08-25）；任务书 `.alice-supervision/research/f1f6-implementation-task-20260825.txt`
+- 实施提交：`6c2b461`（HEAD；新增 `src/main/java/com/dddgn/alice/task/BotPhysicsAssertionFixture.java` + `BotSelftest.setup()` 一行调用 + 证据报告 `.alice-supervision/research/f1-f6-assertion-result-20260825.md`）
+- 结果：`BOT_PHYSICS_ASSERTION_SUITE FAIL corr=147947fa tick=101 f1=false f3=false f4=true f5=true f2=true tickChain=false`（latest.log:117-141）；既有 suites 全部保持 PASS
+- 证据数值：F1 generic+mob 双源 hurt 均返回 false、health 20->20、delta 恒 0；F3 push 写入 OK(0.5,0,0) 但实体推挤位移 0；F4 travel 摩擦衰减执行 ratioH=0.728（含重力 -0.078）；tickChain 墙体撞击后碰撞 flags 恒 false；F5 同 tick 0.980 vs 延迟 1.890；F2 击退后位移 0.890 vs 基线 0.100
+- 边界：未改任何业务实现（BotPlayer/FakeConnection/SoftMovementPrimitive/5 task 语义零改动）；未改 P0 门禁（SoftPhysicsObservationTest 文件零改动）；未判定根因（区分表归监督员定案）；未实现修复；不写档（未触碰 BotWorldData/saveToWorld）
+- 未验证限制：本包只服务端证据；客户端矩阵 M2-M4（推挤/击退可见性、收包对照）需 Windows 用户实测（阶段 B，仅当监督员按区分表指向时执行）
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
