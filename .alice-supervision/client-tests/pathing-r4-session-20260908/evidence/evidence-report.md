# R4 PathSession + 头部朝向修复 客户端验收证据

- 日期：2026-09-08
- 工件 SHA-256（修复版，含头部同步传送）：`e2d00e3c41a9620650beb52d4d8ffea44834a3e9cc335cc6a56492bbc67c87bf`
- 清理探针后的最终工件：`98f942b61475da151549f86a54b5156c5e2c14cd7d4efddb35ff51ab42cfd711`
- 场景：`/function alice_test:pathing_course`（孤立长方体开阔场景，固定起点 `(0,64,46)`）
- 入口：`alice:pathing_session` 右键（规划 → 逐段执行）
- 日志：`evidence/r4-session-key-lines.log`

## 1. R4 PathSession 结果

```
[R4 Session] planned ... status=REACHED movements=2 cost=2.00 nodes=3 considered=10
[R4 Session] segment_start index=0/2 type=DESCEND tolerance=COLUMN
[R4 Session] segment_done  index=0 type=DESCEND actualFoot=0,63,45
[R4 Session] segment_start index=1/2 type=DESCEND tolerance=EXACT
[R4 Session] segment_done  index=1 type=DESCEND actualFoot=0,62,44
[R4 Session] completed segments=2 ticks=21 finalFoot=0,62,44
[R4 Session] result ... status=COMPLETED segments=2/2 ticks=21 finalFoot=0,62,44
```

判定：`COMPLETED segments=2/2`，分段容差 COLUMN→EXACT 生效，无 `STALE`/`BLOCKED`。

## 2. 头部朝向修复（用户确认"现在同步了"）

服务端旋转探针（临时，已验证后删除）：

```
tick=1  yRot=4.19   yBodyRot=4.19   yHeadRot=4.19   ← 传送后三者一致
tick=2  yRot=-180   yBodyRot=-180   yHeadRot=4.19   ← 头晚 1 tick（aiStep 用上一 tick 的 yRot）
tick=3+ yRot=-180   yBodyRot=-180   yHeadRot=-180   ← 完全同步
```

### 根因（字节码实证）

- `ServerPlayer.teleportTo(double,double,double)` **不同步头部**；
- 只有 `teleportTo(ServerLevel, x, y, z, Set<RelativeMovement>, yRot, xRot)` 会额外 `setYHeadRot(yRot)`；
- 夹具原先用 3 参重载把 bot 挪到起点 → 头部停留在旧朝向 → 客户端渲染出头身不一致。

### 修复

`PathingBatteryTask.anchorToStart()` 与 `PathingSessionItem` 统一改用带头部同步的传送重载。

### 附带确认

`LivingEntity.aiStep()` 在 `isEffectiveAi()` 为真时调用 `serverAiStep()`（字节码 7235-7243），
`Player.serverAiStep()` 会 `yHeadRot = yRot` → 假人头部每 tick 自动同步（晚 1 tick）。

## 验证等级

- R4 PathSession：`WINDOWS_CLIENT`（日志实证）+ 头部修复 `USER_ACCEPTED`（用户确认"现在同步了"）
- 临时探针 `[RotProbe]` 已删除，最终工件已重新构建并同步
