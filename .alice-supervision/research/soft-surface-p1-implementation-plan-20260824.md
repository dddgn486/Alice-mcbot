# SOFT_SURFACE P1（物理观测与扰动恢复）最小实施路线

- 规划角色：Alice 实现规划员+
- 日期：2026-08-24
- Git 基线：`19af345281f61223c465e4cb632382fee1e11941`
- 规划门禁：`./tools/work-session-start.sh` 报 `PLANNING` 拒绝启动，符合只读浅调研；未修改代码/plan/HANDOVER/客户端证据，未运行测试。
- Active plan：`20260824-soft-surface-physics-observation-p1-v1`（PLANNING，用户已批准 P1）
- 依据：深度调研 P1 定义（`.alice-supervision/research/bot-pathfinding-soft-hard-boundary-research-20260824.md:135-149`）、现状地图（`alice-movement-system-inventory-20260824.md`）、HANDOVER R30/R36 移动边界、协议冻结边界。

> **本规划不构成实施授权。** 监督员审核后派主开发，主开发仍需工作会话门禁批准后才可动工。

## 1. 现状快照

当前软移动任务已有基础：
- `SoftMovementPrimitive.java:48-86`：NATIVE_TRAVEL 调 `bot.travel(Vec3)`，ASCEND 用 `setJumping(true) + travel`，settle 用 `travel(Vec3.ZERO)` 不伪造 `setOnGround`。
- `SoftPathProbeTask.java:50-136`：逐段复核 HORIZONTAL/ASCEND/DESCEND，只在 `MovementHelper.isStandingAtFootPos + bot.onGround()` 同时成立时推进段；MAX_SETTLE_TICKS=30。
- `FollowTask.java:45-120`：同维 24 格，每 10 tick 重算，settle 同样判 `isStandingAtFootPos + onGround`。

当前观测仅有 `bot.onGround()`、`bot.fallDistance`；缺 `deltaMovement`（速度）、支撑顶面变化、碰撞方向、水平/垂直偏差、实体接触、击退/damage 速度突变。无 revalidate/replan 链路；settle 失败只有 timeout 单一失败码。

## 2. P1 改动清单

### 2.1 观测扩展（新字段 + 日志）

在 `SoftPathProbeTask` 和 `FollowTask` 增加逐 tick 观测字段（不持久化到 Task 外部；仅供当前任务 revalidate/replan 决策和日志诊断）：

1. **速度**：`Vec3 lastDeltaMovement` / `Vec3 currentDeltaMovement = bot.getDeltaMovement()`。
2. **支撑顶面**：`double lastSupportTopY` / `double currentSupportTopY = MovementHelper.supportTopY(level, bot.blockPosition().below())`；检测格子动态变化（方块被破坏/放置、活塞推动、重力方块下落）。
3. **碰撞方向**：`boolean horizontalCollision = bot.horizontalCollision`、`boolean verticalCollisionBelow = bot.verticalCollisionBelow`；可区分撞墙/撞顶/无地板。
4. **水平/垂直偏差**：当前段目标 `(goalX, goalZ)` 与 `bot.getX/Z` 的欧氏距离；垂直偏差 = `bot.getY() - segment.getY()`（预期脚位 Y）。
5. **实体接触**：`bot.isPassenger()`（被骑乘）、周围实体 AABB 推挤（后续可选；P1 先记录 `level.getEntities(bot, bot.getBoundingBox().inflate(0.5)).size()`）。
6. **击退/速度突变**：每 tick 比较 `currentDeltaMovement` 与 `lastDeltaMovement` 的水平/垂直分量变化；若单次变化超阈值（水平 >0.3、垂直 >0.5）记录 `velocityDisruption` 标志。

日志关键字（在 settle/revalidate/fail 阶段输出）：
- `soft_phys_obs: segment=X/Y action=A deltaMove=(dx,dy,dz) hDev=H vDev=V support=S onGround=G hColl=HC vColl=VC entities=E`
- `soft_phys_disruption: velocityChange=(Δx,Δy,Δz) cause=knockback|unknown`
- `soft_phys_revalidate: segment=X/Y revalidResult=blocked|passable reason=...`
- `soft_phys_replan: trigger=deviation|collision|velocityDisrupt oldPath=N newCost=C`

### 2.2 settle → revalidate → replan 链路

当前 `settle` 只循环 `travel(Vec3.ZERO)` 直到 `isStandingAtFootPos + onGround` 或 MAX_SETTLE_TICKS。P1 扩展为：

```text
每 tick（执行 travel 后）:
  1. 观测 deltaMovement/supportTopY/collision/deviation
  2. 若 horizontal > ARRIVE:
       → applyToward / applyJumpToward
       → 若 noProgressTicks > 20 或 hDev > 2.0: 进入 REVALIDATE
  3. 若 horizontal <= ARRIVE:
       → settle(travel Vec3.ZERO)
       → 若 isStandingAtFootPos + onGround: 推进段
       → 若 settleTicks > MAX_SETTLE_TICKS:
            → REVALIDATE（可能段几何已变或速度不足）
  4. REVALIDATE:
       → 重验当前段 canTraverse/canAscend/canDescend（世界可能已变）
       → 若 blocked: 记 soft_phys_revalidate_blocked → REPLAN 或 FAIL
       → 若 passable 但严重偏离（vDev > 1.5 或 hDev > 1.5）: REPLAN
       → 若可恢复（轻微偏离）: 继续 settle，revalidateTicks++
  5. REPLAN:
       → 从当前 bot.blockPosition() 重新调 SurfacePathfinder.find(bot.blockPosition(), finalTarget)
       → 若 REACHED: 替换 path、index=0、replanCount++
       → 若 UNREACHABLE/SEARCH_LIMIT: FAIL with stable code
       → 若 replanCount > MAX_REPLAN（3）: FAIL with soft_phys_max_replan
```

**关键约束**：
- 永不用 `setPos` 校正；只用 `travel` 驱动物理。
- 永不自动 HARD_PATH fallback；只能 FAIL 或 REPLAN soft。
- revalidate 只读世界当前几何，不改路径；REPLAN 才重算路径。
- velocityDisruption（击退/damage）也进 REVALIDATE；若速度归零且段仍可达则继续 settle，否则 REPLAN。

### 2.3 需改类/方法

| 类/方法 | 改动 | 理由 |
|---|---|---|
| `SoftPathProbeTask` | 新增 `lastDeltaMovement`、`currentDeltaMovement`、`lastSupportTopY`、`noProgressTicks`、`revalidateTicks`、`replanCount` 字段；tick 循环增加观测、偏离判定、revalidate、replan 逻辑；新增 `revalidateSegment()`、`replanFromCurrent()` 私有方法 | P1 核心扩展点 |
| `FollowTask` | 同样增加观测字段；因 FollowTask 已有 10 tick replan 周期，P1 只需增加"碰撞/速度突变时立即 replan"；新增 `observePhysics()`、`shouldImmediateReplan()` 辅助方法 | 保持 follow 短周期重算优势 |
| `SoftMovementPrimitive` | `settle` 增加 optional 观测回调参数（`Consumer<PhysicsObservation>`），供调用方记录每 tick 观测；不改变 settle 本身逻辑（仍是 `travel(Vec3.ZERO)`） | 解耦观测与原语 |
| `MovementHelper` | 已有 `supportTopY`、`isStandingAtFootPos`、`canTraverse/Ascend/Descend` 足够；无需改 | P1 不扩展几何判定 |
| `SoftMoveProbeTask` | 不改；它只用于同高平地 8 格独立实验，不涉及段间导航 | P1 不扩展到 SoftMoveProbeTask |

### 2.4 不改的类（冻结边界）

- `PathExecutor`、`BotMiner`、`PlaceTask`、`MineTask`、`DropCollectionTask`：HARD_PATH 矿链全不动。
- `ContinuousRoadCurve`、`TunnelObstaclePolicy`、`RoadPlan`、`RoadBuildTask`：道路/隧道独立链不动。
- `AStarPathfinder`、`SurfacePathfinder`：硬搜索底座不改；软任务只复用现有 `find` 结果。
- `BotManager.BotSession`：任务生命周期/SurvivalSystem 中断不动；软失败仍走 `Status.FAILED` 并记稳定 `failureReason`。

## 3. settle/revalidate/replan 详细设计

### 3.1 偏离检测条件

| 触发器 | 阈值 | 行为 |
|---|---|---|
| 水平偏差 | `hDev > 2.0`（bot 距段目标 >2 格） | 进 REVALIDATE；若段几何仍可达则继续 settle，否则 REPLAN |
| 垂直偏差 | `vDev > 1.5`（bot 脚位高于/低于预期段 Y >1.5 格） | 进 REVALIDATE；检查支撑顶面、onGround、段几何 |
| 无进展 | `noProgressTicks > 20`（连续 20 tick 水平移动 <0.001） | 进 REVALIDATE；可能撞墙/夹角/实体堆 |
| 速度突变 | 单 tick `|ΔdeltaMovement.horizontal| > 0.3` 或 `|ΔdeltaMovement.y| > 0.5` | 记 `velocityDisruption`，进 REVALIDATE；可能击退/damage/piston/流体推 |
| settle 超时 | `settleTicks > MAX_SETTLE_TICKS`（当前 30） | 进 REVALIDATE；可能段几何已变或不可达 |

### 3.2 REVALIDATE 做什么

重验当前**正在执行的段**（不是整条路径）：

1. 从 `bot.blockPosition()` 到 `path.get(index)` 的几何关系（假设当前段是 `from=bot.blockPosition(), to=path.get(index)`）：
   - 若 `dy=0`：检查 `MovementHelper.canTraverse(level, from, to)`；
   - 若 `dy=1`：检查 `canAscend(level, from, to)`；
   - 若 `dy=-1`：检查 `canDescend(level, from, to)`；
   - 若非相邻或几何不满足：`soft_phys_revalidate_blocked` → REPLAN。
2. 支撑顶面变化：若 `currentSupportTopY` 与规划时相差 >0.5（方块被破坏/放置/下落），记 `soft_phys_support_changed`；若仍可达则继续 settle，否则 REPLAN。
3. 碰撞/实体堆：若 `horizontalCollision=true` 且连续 10 tick 无进展，且当前段几何仍为 passable，则认为是**临时动态阻塞**（实体推挤、门未开），继续 revalidate 等待（最多 revalidateTicks=20）；若超时则 REPLAN。
4. 速度归零：若 `velocityDisruption=true` 且当前速度已基本归零（`|deltaMovement| < 0.05`），且段几何仍可达，则清 disruption 标志并继续 settle；否则 REPLAN。

### 3.3 REPLAN 触发条件

- revalidate 后段几何明确 blocked（`canTraverse/Ascend/Descend` 返回 false）；
- 严重偏离（hDev >2.0 或 vDev >1.5）且 revalidate 后仍不在预期几何内；
- 速度突变后无法在 20 tick 内恢复到可 settle 状态；
- revalidate 超时（revalidateTicks > 20）且段仍不可达。

### 3.4 REPLAN 做什么

```java
private Status replanFromCurrent(ServerLevel level) {
    if (replanCount >= MAX_REPLAN) {
        failure = "soft_phys_max_replan";
        return Status.FAILED;
    }
    BlockPos currentFoot = bot.blockPosition();
    SurfacePathfinder.Result result = SurfacePathfinder.find(level, currentFoot, finalTarget);
    if (!result.reachable()) {
        failure = result.inconclusive() ? "soft_phys_replan_search_limit" : "soft_phys_replan_no_path";
        return Status.FAILED;
    }
    BotLog.info("soft_phys_replan: trigger=deviation|collision|velocityDisrupt oldPath={} newPath={} newCost={} replanCount={}",
            path.size(), result.path().size(), result.totalCost(), replanCount + 1);
    path = result.path();
    index = 0;
    replanCount++;
    settleTicks = 0;
    noProgressTicks = 0;
    revalidateTicks = 0;
    // 清除 velocityDisruption 等临时标志
    return Status.RUNNING;
}
```

### 3.5 FAIL 条件

- SurvivalSystem hazard（已有）；
- elapsed > MAX_TICKS（已有）；
- replanCount > MAX_REPLAN（新增，3 次）；
- replan 后 UNREACHABLE/SEARCH_LIMIT（新增稳定码）；
- 新增失败码：`soft_phys_revalidate_blocked`、`soft_phys_max_replan`、`soft_phys_replan_no_path`、`soft_phys_replan_search_limit`、`soft_phys_severe_deviation`（若不触发 replan 的极端情况）。

## 4. 服务端 fixture

在 `src/test/java/com/dddgn/alice/pathing` 或 `task` 新增 `SoftPhysicsObservationTest.java`（headless focused fixture）：

| 断言项 | 目的 | 验证方式 |
|---|---|---|
| 观测字段非空 | P1 新增字段在 tick 循环中被赋值 | 构造短距离 SoftPathProbeTask，执行数 tick 后反射读取 `currentDeltaMovement`/`lastSupportTopY` 等字段；断言非 null/非 NaN |
| settle 不用 setPos | 确保真实物理 | mock/spy `ServerPlayer.setPos`，执行 settle 阶段断言未调用；只调用 `travel` |
| 偏离后 revalidate 被调用 | 扰动恢复链路存在 | 人工构造"段中途封路"场景（bot 到段中点时 `level.setBlock` 封住目标），断言日志含 `soft_phys_revalidate_blocked` |
| replan 后路径变化 | 动态重算生效 | 人工构造"段被封、侧向可绕"场景，断言 `replanCount++` 且新路径长度/成本与预期一致 |
| 速度突变触发 revalidate | 击退/damage 感知 | 人工调用 `bot.setDeltaMovement(...)` 模拟击退，断言日志含 `soft_phys_disruption` 和 revalidate 进入 |
| MAX_REPLAN 保护 | 无限重规划保护 | 构造"每次 replan 后目标仍不可达"循环场景，断言第 4 次 replan 时 FAILED with `soft_phys_max_replan` |

既有 `PathingRegression.java`（成本/段数断言）、`SoftMovementFixture.java`（若存在）不受影响；新 fixture 仅增量覆盖 P1 观测/revalidate/replan 逻辑，不改变 A* 搜索结果。

## 5. Windows 客户端验收矩阵

P1 客户端证据目的：验证真实物理观测、扰动恢复不破坏稳定移动、偏离/重规划在真实环境可观察且不崩溃/传送。

### 5.1 场景表（独立 CLIENT_TEST_PENDING 工作包）

| 场景 ID | 场景描述 | 操作 | 预期观察 | PASS 标准 |
|---|---|---|---|---|
| P1-G1 基础物理 | 平地 10 格 `/alice soft-path-probe <target>` | bot 平地前进 | latest.log 含 `soft_phys_obs` 每 10 tick 输出；`deltaMove.horizontal` 约 0.2；无 setPos 校正；bot 真实走到目标 | onGround=true、actualFoot 对齐、无传送 |
| P1-G2 slab/stair | 从整格到下半砖/台阶 | `/alice soft-path-probe <slab脚位>` | supportTopY 变化（1.0 → 0.5）；settle 后 onGround=true；bot 真实站在 slab | 支撑顶面对齐、无伪造 onGround |
| P1-G3 一格上升 | 一格台阶 ASCEND | `/alice soft-path-probe <上方脚位>` | action=ASCEND、setJumping=true、跳跃轨迹可见、落地后 onGround=true | 真实跳跃动画、无传送到顶 |
| P1-G4 一格下降 | 一格下台阶 DESCEND | `/alice soft-path-probe <下方脚位>` | action=DESCEND、settle 进入、fallDistance >0、重力生效、onGround=true | 真实下落、无瞬移 |
| P1-G5 墙碰撞 | 平地前进途中玩家放方块封路 | 执行中手动封段 | `horizontalCollision=true`、noProgressTicks++、revalidate 触发、日志含 `soft_phys_revalidate_blocked`、最终 FAILED 或 REPLAN（若侧向可绕） | 不穿墙、不传送、稳定失败/重规划 |
| P1-G6 低天花板 | ASCEND 上方有方块 | 跳跃途中头撞顶 | `verticalCollisionBelow=false`（可能）、跳跃失败、revalidate blocked、FAILED with `soft_phys_revalidate_blocked` | 不穿顶、稳定失败 |
| P1-G7 实体推挤 | bot 前进时玩家站在路径上 | 玩家堵路 | `horizontalCollision=true` 或 entities >0、noProgressTicks++、revalidate 等待（最多 20 tick）或 REPLAN | 不穿玩家、有限等待后重规划 |
| P1-G8 击退 | bot 前进时玩家攻击 | 用剑击退 bot | `soft_phys_disruption` 日志、`velocityChange` 非零、revalidate 进入、速度归零后继续 settle 或 REPLAN | 真实击退动画、无传送回路径 |
| P1-G9 support 动态变化 | settle 阶段玩家破坏脚下支撑 | 执行中挖掉段目标下方方块 | supportTopY 变化/NaN、revalidate blocked、FAILED | 不悬空伪造 onGround |
| P1-G10 follow 碰撞重规划 | `/alice follow on`，玩家绕障碍走 | 玩家拐弯 | 每 10 tick replan（已有）+ 碰撞立即 replan（新增）、路径可见改变 | follow 不卡死、不穿墙 |
| P1-G11 危险区域 | 路径途经岩浆/火/深坑 | SurfacePathfinder 或手动指定 | 已有 SurvivalSystem hazard FAILED（不改）；若 P1 未扩展危险判定，只需确认不因 replan 绕过危险检查 | 不跳岩浆、稳定失败 |
| P1-G12 MAX_REPLAN 保护 | 人工构造循环重规划场景（每次 replan 后再封新路） | 连续封路 4 次 | 第 3 次 replan 后 FAILED with `soft_phys_max_replan` | 不无限重规划、稳定失败码 |
| P1-G13 隔离验证 | 执行 `/alice mine <矿>`、`/alice road-build`、transfer | 普通矿链/道路/transfer | 确认无 soft 偷接入；MineTask/DropCollectionTask/RoadBuildTask 不含 P1 观测/replan 日志 | HARD_PATH 矿链不受影响 |

### 5.2 观察点与证据采集

每个场景需：
- **latest.log / debug.log**：含 `soft_phys_obs`、`soft_phys_disruption`、`soft_phys_revalidate`、`soft_phys_replan` 关键字及其详细参数。
- **截图/视频**：bot 真实移动轨迹、碰撞/击退动画、最终脚位/支撑。
- **失败场景**：稳定失败码（`soft_phys_*`）、无崩溃/栈溢出、bot 停在安全位置（不悬空/不岩浆中）。
- **evidence-report.md**：每个场景记录 是/否 + 截图链接 + 关键日志片段；P1-G13 隔离必须明确列出矿链日志无 soft 观测。

### 5.3 失败回收

任何场景失败不破坏 bot/世界：
- bot 停在当前脚位，inventory 完整；
- 不传送、不穿墙、不悬空伪造 onGround；
- task 稳定 FAILED，可重新 `/alice soft-path-probe` 或 follow；
- 若 P1 观测/replan 引入崩溃/栈溢出/无限循环，必须停止实施并回规划。

## 6. 停止条件（何时停止实施并回规划）

| 停止条件 | 原因 | 后果 |
|---|---|---|
| 观测字段需跨 tick 持久化到 Task 外部 | 若 revalidate/replan 需要历史 N tick 观测，而不只是当前/上一 tick，则需引入 `List<PhysicsObservation>` buffer；当前设计假设只需 last/current 对比 | 回规划，评估持久化复杂度与 P1 必要性 |
| settle 链路与 BotSession 维生中断冲突 | P1 的 revalidate/replan 可能在 SurvivalSystem hazard 后仍尝试执行；若 hazard 应立即中断不走 revalidate，需明确顺序 | 回规划，明确 SurvivalSystem 优先级 |
| replan 触发 A* 搜索在主 tick 阻塞 >50ms | 若 replan 频繁（碰撞/击退密集场景）且搜索昂贵，可能阻塞服务端 tick；当前 MAX_NODES=12000 应可接受，但需实测 | 回规划，评估异步搜索或 replan 冷却 |
| MAX_REPLAN=3 不足或过宽 | 若真实场景常需 >3 次 replan（复杂迷宫/动态世界），或 3 次已允许过多无效重试 | 回规划，用户/监督员调整阈值 |
| velocityDisruption 阈值误判 | 若 0.3/0.5 阈值在真实场景常因正常跳跃/下落触发，或反之不敏感 | 回规划，调整阈值或改用 `hasImpulse` 标志 |
| P1 观测扩展需改 PathExecutor/HARD_PATH | 若发现硬路径也需相同观测（不应该；HARD_PATH 不走真实物理），则违反冻结边界 | 停止，重新规划分离硬软观测 |
| 客户端 P1-G1~G13 任一场景无法通过 | 若物理观测/revalidate/replan 在真实客户端导致传送/穿墙/崩溃/卡死 | 停止实施，回规划修复或降级 P1 范围 |

## 7. 依赖与风险

### 7.1 本地可决定

- `ServerPlayer.getDeltaMovement()`、`horizontalCollision`、`verticalCollisionBelow`、`onGround`、`fallDistance` 均为原版字段，Forge 1.20.1-47.4.10 已有。
- `MovementHelper` 的 `canTraverse/Ascend/Descend`、`supportTopY`、`isStandingAtFootPos` 已验证，P1 复用。
- `SurfacePathfinder.find` 已有，replan 只是再次调用。
- P1 不改任务生命周期/SurvivalSystem/BotSession，只在软任务内部增加观测/revalidate/replan。

### 7.2 需确认（实施前浅 source check）

- `ServerPlayer.horizontalCollision` / `verticalCollisionBelow` 在 Forge 服务端的更新时机（是否在 `travel` 后立即可用？）；可能需改用 `bot.isCollided` 或自行检测 AABB 与世界交叠。
- `bot.getDeltaMovement()` 在击退/damage 后的更新顺序（Forge `onLivingKnockBack` 事件与 `setDeltaMovement` 调用链）；可能需在 revalidate 中额外判 `hasImpulse` 标志。
- MAX_SETTLE_TICKS=30、MAX_REPLAN=3、velocityDisruption 阈值 0.3/0.5 是初始估计；需客户端实测调整。

### 7.3 风险

1. **replan 频繁触发性能**：若动态世界/击退密集场景每 tick 都 replan，A* 搜索可能累积延迟；当前 MAX_NODES=12000 约 <10ms，MAX_REPLAN=3 保护，应可接受。
2. **观测字段膨胀**：若未来 P2/P3 增加更多观测（流体深度、实体速度、属性效果），需重构为独立 `PhysicsObservation` record；P1 暂内联字段。
3. **revalidate/replan 与 follow 10-tick replan 冲突**：FollowTask 已有周期重算，P1 增加"碰撞立即 replan"；需确保不重复搜索（10-tick 周期优先，碰撞/速度突变只在非周期 tick 触发）。
4. **客户端物理不稳定**：P1 假设 `travel` 在服务端可复现客户端物理；若延迟/mod 改写导致服务端 bot 与真实客户端玩家行为不一致，P1 观测也无法完全预测；需 Windows 矩阵实测。

## 8. 实施顺序（供主开发参考，非授权）

1. 在 `SoftPathProbeTask` 增加观测字段（lastDeltaMovement 等），tick 循环增加赋值；编译通过。
2. 增加 `revalidateSegment()` 私有方法（只读世界，检查段几何），调用点在 settle 超时/偏离/无进展后；日志输出 `soft_phys_revalidate`。
3. 增加 `replanFromCurrent()` 私有方法，调用 `SurfacePathfinder.find`，处理 UNREACHABLE/SEARCH_LIMIT；日志输出 `soft_phys_replan`。
4. 在 tick 循环增加偏离判定（hDev/vDev/noProgressTicks/velocityDisruption），触发 revalidate 或 replan。
5. 同步修改 `FollowTask`（观测 + 碰撞立即 replan），保持 10-tick 周期优先。
6. 编写 `SoftPhysicsObservationTest.java` focused fixture，断言观测字段/revalidate/replan 被调用。
7. `./gradlew compileJava`、`./gradlew test --tests SoftPhysicsObservationTest`（若 headless 可运行）。
8. 更新 HANDOVER（P1 观测/revalidate/replan 已实施、fixture 通过、Windows 矩阵待测）。
9. commit/push；监督员 review → `CLIENT_TEST_PENDING` → 用户 Windows P1-G1~G13。

## 9. 交付状态

- 报告路径：`.alice-supervision/research/soft-surface-p1-implementation-plan-20260824.md`
- 仅只读浅调研（15-25 分钟）：未修改业务代码、active plan、HANDOVER、客户端记录；未运行测试。
- Active plan 当前 `PLANNING`；本报告供监督员审核，审核通过后监督员更新 plan 为 `APPROVED_FOR_IMPLEMENTATION` 并派主开发。
- **本规划不构成实施授权。**
