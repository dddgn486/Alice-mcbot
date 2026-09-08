# 软路径测试工具实施路线（右键寻路 / Shift+右键寻路+挖掘）

- 规划角色：Alice 实现规划员+
- 日期：2026-08-24
- Git 基线：`2298dd3dfe393eed495461574d812dd29e2b2bff`（P1 已实施，CLIENT_TEST_PENDING）
- 规划门禁：`./tools/work-session-start.sh --allow-no-plan` 已通过（P1 active plan `APPROVED_FOR_IMPLEMENTATION`，规划阶段只读）；未修改代码/plan/HANDOVER/客户端证据，未运行测试。
- 调研任务：`.alice-supervision/research/soft-path-test-tool-investigation-task.txt`
- 冻结边界：MineTask 固定 HARD_PATH（`docs/SUPERVISION_PROTOCOL.md:13`、`docs/HANDOVER.md` R30）；P1 只允许 SoftPathProbeTask/FollowTask 使用 SOFT_SURFACE。

> **本规划不构成实施授权。** 监督员审核后派主开发，主开发仍需工作会话门禁批准后才可动工。

## 1. 现状快照

- **现有工具**：
  - `soft_path_probe_selector`（指南针外观）：右键方块（support） → `BotManager.assignSoftPathProbe(bot, support.above())` → `SoftPathProbeTask`（SOFT_SURFACE 软寻路，不挖掘）。
  - `target_selector`（钻石斧外观）：右键方块 → 派 `MineTask`（HARD_PATH 硬寻路+挖掘）。
  - `transfer_endpoint_selector`（金斧/stick 材质）：右键 dest / Shift+右键 source → 转移端点选择（已有 Shift 区分参考）。
- **用户需求**：增加 Shift+右键"软寻路+挖掘"；寻路和寻路+挖掘可做在一个工具上（Shift 区分）。
- **冻结边界约束**：**不能让 MineTask 接入 SOFT_SURFACE**（P1 只允许 SoftPathProbeTask/FollowTask）；不改 PathExecutor/BotMiner/DropCollectionTask/道路/隧道。

## 2. 方案对比与推荐

### 2.1 方案 A：扩展现有 `SoftPathProbeSelector`（推荐）

**描述**：在 `SoftPathProbeSelector.useOn` 增加 `context.getPlayer().isShiftKeyDown()` 判定；Shift+右键调用新 `BotManager.assignSoftPathMine(bot, target)`，派发新任务 `SoftPathMineTask`（先软寻路，到达后挖掘）。

**优点**：
- 单工具统一，符合用户"寻路和寻路+挖掘可做在一个工具上"要求。
- 复用现有物品注册（`soft_path_probe_selector`），无需新物品/贴图。
- 交互清晰：右键=只寻路，Shift+右键=寻路+挖掘。

**缺点**：
- `SoftPathProbeSelector` 语义从"诊断探针"扩展为"测试工具"；若未来有纯诊断需求，可能混淆。
- 需新增 `SoftPathMineTask` 任务类（见 §3）。

### 2.2 方案 B：新增独立工具 `soft_path_mine_selector`

**描述**：新物品 `alice:soft_path_mine_selector`（如钻石锄外观），只支持 Shift+右键挖掘，不支持普通右键。

**优点**：
- 语义分离：`soft_path_probe_selector` 保持纯寻路诊断，新工具专用测试挖掘。

**缺点**：
- 用户需携带两个工具（寻路用探针，挖掘用新工具），违反"可做在一个工具上"要求。
- 增加物品注册、贴图、客户端资源，实施成本高于方案 A。
- 不支持普通右键则需强制 Shift（反直觉）；若支持右键也挖掘，则无区分。

### 2.3 方案 C：统一工具 `soft_surface_test_selector`（重命名）

**描述**：重命名 `soft_path_probe_selector` 为 `soft_surface_test_selector`，实现与方案 A 相同（右键寻路 / Shift+右键挖掘）。

**优点**：
- 语义更泛化（测试工具 vs 诊断探针）。

**缺点**：
- 需物品 ID 重命名（`alice:soft_surface_test_selector`），已发放旧 ID 物品失效；或保留旧 ID 但名称不一致。
- 实质与方案 A 相同，额外重命名成本无收益。

### 2.4 推荐：方案 A

**理由**：
1. 满足用户"单工具"要求；
2. 无需新物品注册/贴图，实施最快；
3. `SoftPathProbeSelector` 已是独立软路径入口，扩展为"测试工具"（右键诊断寻路 / Shift+右键测试挖掘）语义自然；
4. 冻结边界守护清晰：新 `SoftPathMineTask` 独立实现，MineTask 不动。

## 3. 任务链设计：`SoftPathMineTask`

### 3.1 核心思路

**不让 MineTask 接入 SOFT_SURFACE**，而是**新增独立任务 `SoftPathMineTask`**，内部复用 SOFT_SURFACE 移动（SoftPathProbeTask 逻辑）和 BotMiner 挖掘原语。

### 3.2 `SoftPathMineTask` 设计

```java
package com.dddgn.alice.task;

/**
 * 软路径测试工具专用：先软寻路到目标附近（SOFT_SURFACE），到达后挖掘目标（复用 BotMiner）。
 * 不接入普通矿链；MineTask 保持 HARD_PATH 不动。
 */
public final class SoftPathMineTask implements Task {
    private enum Phase { SOFT_NAVIGATE, MINING }
    
    private final ServerPlayer bot;
    private final BlockPos target;
    private final ScopeBuffer scope;
    private Phase phase = Phase.SOFT_NAVIGATE;
    private List<BlockPos> path; // SurfacePathfinder 规划路径
    private int index;
    private BotMiner miner;
    private String failure = "";
    
    // 阈值
    private static final double ARRIVE = 0.25D;
    private static final int MAX_TICKS = 400;
    private static final int MAX_SETTLE_TICKS = 30;
    private static final int MAX_REPLAN = 3;
    // P1 观测字段（与 SoftPathProbeTask 一致）
    private Vec3 lastDeltaMovement;
    private double lastSupportTopY;
    private int noProgressTicks;
    private int settleTicks;
    private int replanCount;
    
    public SoftPathMineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope) {
        this.bot = bot;
        this.target = target.immutable();
        this.scope = scope;
        bot.getInventory().setItem(bot.getInventory().selected, new ItemStack(Items.DIAMOND_PICKAXE));
        BotManager.syncMainHand(bot);
    }
    
    @Override
    public Status tick() {
        HazardState hazard = SurvivalSystem.tick(bot);
        if (SurvivalSystem.shouldInterrupt(hazard)) {
            failure = SurvivalSystem.interruptionReason(hazard);
            return Status.FAILED;
        }
        
        return switch (phase) {
            case SOFT_NAVIGATE -> tickSoftNavigate();
            case MINING -> tickMining();
        };
    }
    
    private Status tickSoftNavigate() {
        // 复用 SoftPathProbeTask 的 P1 逻辑：
        // 1. SurfacePathfinder 规划路径（首次）
        // 2. 逐段 NATIVE_TRAVEL 执行
        // 3. P1 观测（deltaMovement/supportTopY/collision/deviation）
        // 4. 偏离/碰撞/速度突变 → revalidate → replan
        // 5. 到达路径终点 → phase = MINING
        // ... （完整实现复制 SoftPathProbeTask.tick 逻辑）
        
        // 简化伪代码：
        if (path == null) {
            SurfacePathfinder.Result result = SurfacePathfinder.find(level, bot.blockPosition(), target);
            if (!result.reachable()) {
                failure = result.inconclusive() ? "soft_mine_search_limit" : "soft_mine_no_path";
                return Status.FAILED;
            }
            path = result.path();
            index = 0;
        }
        if (index >= path.size()) {
            // 软寻路到达，切换挖掘阶段
            phase = Phase.MINING;
            miner = new BotMiner(bot, target);
            BotLog.info("soft_mine: 软寻路完成，开始挖掘 target={}", target.toShortString());
            return Status.RUNNING;
        }
        
        // 逐段执行（P1 观测、settle、revalidate、replan）
        // ...（与 SoftPathProbeTask 一致）
        
        return Status.RUNNING;
    }
    
    private Status tickMining() {
        // 复用 BotMiner.tick()
        BotMiner.Status status = miner.tick();
        if (status == BotMiner.Status.DONE) {
            return Status.DONE; // 挖掘完成，不收集掉落物（测试工具简化）
        }
        if (status == BotMiner.Status.FAILED) {
            failure = "soft_mine_dig_failed:" + miner.failureReason();
            return Status.FAILED;
        }
        return Status.RUNNING;
    }
}
```

**关键点**：
1. **Phase.SOFT_NAVIGATE**：完整复用 SoftPathProbeTask 的 P1 逻辑（SurfacePathfinder + NATIVE_TRAVEL + P1 观测/revalidate/replan），到达后切换 Phase.MINING。
2. **Phase.MINING**：直接调用 `BotMiner.tick()`，复用硬路径的挖掘原语（站位候选、视线、reach、原版 break protocol），但**不走 PathExecutor 硬寻路**（因为已由 SOFT 到达）。
3. **不改 MineTask**：`MineTask` 保持原封不动（HARD_PATH + BotMiner + DropCollectionTask）；`SoftPathMineTask` 是独立测试任务，不接入普通 `/alice mine` 命令。
4. **不收集掉落物**：测试工具简化，只验证"软寻路+挖掘"可行性；若需收集可后续扩展 Phase.COLLECTING（调 DropCollectionTask）。

### 3.3 `BotManager` 新增入口

```java
// BotManager.java
public static void assignSoftPathMine(BotPlayer bot, BlockPos target) {
    BotSession session = BOTS.get(bot.getUUID());
    if (session == null) return;
    session.assignSoftPathMine(target);
}

// BotSession 内部
public void assignSoftPathMine(BlockPos targetPos) {
    if (!replaceTaskIfRunning()) return;
    TaskTarget assignedTarget = TaskTarget.block(targetPos);
    scope.begin(targetPos, 8);
    beginTask(new SoftPathMineTask(bot, targetPos, scope), assignedTarget);
    broadcastTarget(this.target);
}
```

## 4. 交互设计

### 4.1 点击目标

沿用现有 `SoftPathProbeSelector` 语义：
- 玩家右键点击**支撑方块**（如地面石头）；
- 软路径寻路到 `clicked.above()`（脚位）；
- Shift+右键则寻路到 `clicked.above()` 后**挖掘 clicked**（支撑方块本身）。

**理由**：
- 与现有 `SoftPathProbeSelector` 一致（点 support，寻路到 foot）；
- 避免"点击目标方块悬空"语义混乱（若点空中矿石，foot 是哪里？）。
- 测试场景简单：平地放方块，右键地面=寻路到方块旁，Shift+右键=寻路后挖方块。

### 4.2 Shift 行为

```java
// SoftPathProbeSelector.useOn
@Override
public InteractionResult useOn(UseOnContext context) {
    Level level = context.getLevel();
    if (level.isClientSide) {
        return InteractionResult.SUCCESS;
    }
    Player player = context.getPlayer();
    ServerLevel serverLevel = (ServerLevel) level;
    BotPlayer bot = BotManager.firstInLevel(serverLevel);
    if (bot == null) {
        send(player, "[alice] 请先生成 bot");
        return InteractionResult.FAIL;
    }
    if (BotManager.isBusy(bot)) {
        send(player, "[alice] bot 当前有任务");
        return InteractionResult.FAIL;
    }
    BlockPos clicked = context.getClickedPos();
    BlockPos foot = clicked.above();
    
    // 验证脚位可站
    if (!MovementHelper.canWalkOn(serverLevel, foot)
            || !MovementHelper.canWalkThrough(serverLevel, foot)
            || !MovementHelper.canWalkThrough(serverLevel, foot.above())) {
        send(player, "[alice] 软路径目标无效: support=" + clicked.toShortString());
        return InteractionResult.FAIL;
    }
    
    boolean shift = player.isShiftKeyDown();
    if (shift) {
        // Shift+右键：软寻路+挖掘
        BotManager.assignSoftPathMine(bot, clicked); // 挖掘 clicked（support）
        send(player, "[alice] soft-path-mine-test: support=" + clicked.toShortString()
                + " foot=" + foot.toShortString() + " -> dig support");
    } else {
        // 右键：只寻路
        BotManager.assignSoftPathProbe(bot, foot);
        send(player, "[alice] soft-path-test: support=" + clicked.toShortString()
                + " foot=" + foot.toShortString());
    }
    BotLog.info("软路径工具: player={} shift={} support={} foot={} bot={}",
            player.getName().getString(), shift, clicked.toShortString(),
            foot.toShortString(), bot.getName().getString());
    return InteractionResult.SUCCESS;
}
```

### 4.3 失败反馈

- chat 消息：`send(player, "[alice] ...")` 简短状态（bot 不存在/busy/目标无效/任务启动）。
- 日志：`BotLog.info("软路径工具: ...")` 记录 player/shift/support/foot/bot；任务失败时 `soft_mine_*` 失败码。

## 5. 改动清单

| 文件 | 改动 | 理由 |
|---|---|---|
| `src/main/java/com/dddgn/alice/item/SoftPathProbeSelector.java` | `useOn` 增加 `player.isShiftKeyDown()` 判定；Shift+右键调 `BotManager.assignSoftPathMine(bot, clicked)` | 推荐方案 A：单工具统一 |
| `src/main/java/com/dddgn/alice/task/SoftPathMineTask.java`（新增） | Phase.SOFT_NAVIGATE（复用 SoftPathProbeTask P1 逻辑）+ Phase.MINING（复用 BotMiner）；P1 观测字段、revalidate、replan | 独立软寻路+挖掘任务，不改 MineTask |
| `src/main/java/com/dddgn/alice/bot/BotManager.java` | 新增 `public static void assignSoftPathMine(BotPlayer, BlockPos)` 和 `BotSession.assignSoftPathMine(BlockPos)` | 派发新任务入口 |
| `src/main/java/com/dddgn/alice/item/AliceItems.java` | 不改（复用 `SOFT_PATH_PROBE_SELECTOR`） | 方案 A 无需新物品 |

**不改的类**（冻结边界）：
- `MineTask.java`：保持 HARD_PATH 不动，Phase.MINING + BotMiner + DropCollectionTask 不变。
- `PathExecutor.java`、`BotMiner.java`、`DropCollectionTask.java`：硬寻路/挖掘/拾取不动。
- `SoftPathProbeTask.java`、`FollowTask.java`：P1 软任务不动（`SoftPathMineTask` 复用其逻辑，不修改原类）。
- `ContinuousRoadCurve.java`、`TunnelObstaclePolicy.java`、`RoadPlan.java`：道路/隧道不动。

## 6. 冻结边界守护实现

### 6.1 问题：如何确保 MineTask 不偷接 SOFT_SURFACE？

**方案**：
1. **独立任务类**：`SoftPathMineTask` 是新类，与 `MineTask` 完全隔离；普通 `/alice mine <target>` 或 `target_selector` 仍调 `BotManager.assign(TaskTarget.block(target))`，派发 `MineTask`（HARD_PATH）。
2. **专用入口**：`SoftPathProbeSelector` Shift+右键调 `BotManager.assignSoftPathMine`，派发 `SoftPathMineTask`；不改 `BotManager.assign` 逻辑（它只派 `MineTask`）。
3. **无自动切换**：`SoftPathMineTask` 失败不自动回退 `MineTask`；`MineTask` 不检测"是否应改用软路径"。两者永不互转。

### 6.2 验证方式

- **T3 隔离场景**（客户端测试）：
  1. 执行 `/alice mine <矿>`；
  2. 检查 latest.log 只含 `MineTask` / `PathExecutor` / `HARD_PATH` 日志，无 `soft_phys_*` / `NATIVE_TRAVEL` 关键字；
  3. 确认 bot 用 `setPos` 小步移动（HARD_PATH），不调 `travel`。
- **编译检查**：`MineTask.java` 无 `import SoftPathProbeTask` / `import SoftMovementPrimitive`；git diff 确认 `MineTask` 未改动。

## 7. 验证矩阵

### 7.1 服务端验证

| 项 | 验证方式 | PASS 标准 |
|---|---|---|
| compileJava | `./gradlew compileJava` | 成功，无新错误 |
| 既有 suites | `./gradlew test`（若可运行）| 既有测试 PASS，无回归 |
| 新增 fixture（可选） | `SoftPathMineTaskTest.java`：构造平地+方块，`assignSoftPathMine`，断言 Phase.SOFT_NAVIGATE → Phase.MINING → DONE | 任务阶段正确，挖掘完成 |

### 7.2 客户端验证（Windows，CLIENT_TEST_PENDING）

| 场景 ID | 场景描述 | 操作 | 预期观察 | PASS 标准 |
|---|---|---|---|---|
| T1 右键寻路 | 平地放石头，右键石头 | 持 `soft_path_probe_selector` 右键地面石头 | latest.log 含 `soft-path-test`；bot 软寻路到石头旁（foot = 石头上方）；无挖掘；P1 观测日志 `soft_phys_obs` | bot 到达石头旁，石头完整 |
| T2 Shift+右键寻路+挖掘 | 平地放石头，Shift+右键石头 | 持 `soft_path_probe_selector` Shift+右键地面石头 | latest.log 含 `soft-path-mine-test`；bot 软寻路到石头旁（P1 观测）；到达后挖掘石头（BotMiner 日志）；石头被破坏，掉落物生成 | bot 到达后挖掘石头，石头消失 |
| T2a Shift 软寻路失败 | 封闭房间，Shift+右键外部方块 | 墙外目标，bot 无法到达 | P1 revalidate/replan 日志；最终 FAILED with `soft_mine_no_path` 或 `soft_mine_search_limit`；石头未被挖掘 | 稳定失败码，石头完整 |
| T2b Shift 挖掘失败 | 保护区方块，Shift+右键 | bot 到达后挖掘被 BlockBreakSafety 拒绝 | 软寻路成功；Phase.MINING 进入；BotMiner 日志 `protected_*` 拒绝；任务 FAILED with `soft_mine_dig_failed` | 软寻路成功，挖掘拒绝，稳定失败 |
| T3 隔离验证 | 普通挖矿命令 | `/alice mine <矿>` 或 `target_selector` 右键矿 | latest.log 只含 `MineTask` / `PathExecutor` / `setPos` / `HARD_PATH`；**无** `soft_phys_*` / `NATIVE_TRAVEL` / `SoftPathMineTask` | MineTask 保持 HARD_PATH 不动 |
| T4 P1 观测回归 | Shift+右键，途中碰撞/击退 | 软寻路阶段玩家堵路或击退 bot | P1 观测日志 `soft_phys_obs` / `soft_phys_disruption` / `soft_phys_revalidate` / `soft_phys_replan`；行为与 SoftPathProbeTask 一致 | P1 观测/revalidate/replan 正常 |

### 7.3 证据采集

每场景需：
- latest.log / debug.log 片段（含任务启动/阶段切换/完成或失败码）；
- 截图/视频：bot 移动轨迹、挖掘动画、最终结果（方块破坏/完整）；
- T3 隔离：明确列出 `/alice mine` 日志**无** soft 关键字。

## 8. 停止条件

| 停止条件 | 原因 | 后果 |
|---|---|---|
| Shift+右键需改 MineTask 接入 SOFT_SURFACE | `SoftPathMineTask` 设计复杂度超预期，监督员/用户认为应直接让 MineTask 支持软硬切换 | 停止，违反冻结边界；需用户批准 P2-P5 后才可让 MineTask 接软 |
| 任务链需改 BotManager 核心调度 | `SoftPathMineTask` Phase 切换不足，需 BotManager 支持"任务完成后自动链式派发下一任务" | 停止，超出测试工具范围；需单独工作包设计任务链机制 |
| `BotMiner` 无法在 SOFT 到达后独立使用 | BotMiner 内部硬编码依赖 PathExecutor 或 HARD_PATH 假设，不能在 SOFT 到达后调用 | 停止，回规划重新设计挖掘原语或仅保留"软寻路"测试工具（不挖掘） |
| 用户不批准方案 A | 用户要求独立工具（方案 B）或重命名（方案 C） | 按用户选择调整，实施成本增加 |
| 客户端 T1-T4 任一场景无法通过 | 软寻路+挖掘在真实客户端导致崩溃/卡死/传送/MineTask 偷接 SOFT | 停止实施，回规划修复或降级为"只寻路"工具 |

## 9. 实施时间估计

| 阶段 | 工作量 | 说明 |
|---|---|---|
| 实施 | 2-4 小时 | `SoftPathMineTask` 新类（约 300 行，复用 SoftPathProbeTask P1 逻辑 + BotMiner 调用）、`SoftPathProbeSelector` 增加 Shift 判定（约 20 行）、`BotManager` 新增 assign 方法（约 15 行） |
| 编译+既有测试 | 0.5 小时 | `./gradlew compileJava`、`./gradlew test`（若既有 suites 可运行） |
| 新增 fixture（可选） | 1 小时 | `SoftPathMineTaskTest.java` 断言阶段切换和挖掘完成 |
| HANDOVER 更新 | 0.5 小时 | 记录 Shift+右键工具、SoftPathMineTask、冻结边界守护 |
| commit/push | 0.5 小时 | 提交、推送、监督员 review |
| **客户端测试** | **2-3 小时** | Windows T1-T4（右键寻路、Shift+右键寻路+挖掘、隔离、P1 观测回归）+ evidence-report.md |
| **总计** | **6-9 小时** | 含实施、验证、客户端测试 |

## 10. 交付状态

- 报告路径：`.alice-supervision/research/soft-path-test-tool-plan-20260824.md`
- 仅只读浅调研（15-25 分钟）：未修改业务代码、active plan、HANDOVER、客户端记录；未运行测试。
- Active plan 当前 `APPROVED_FOR_IMPLEMENTATION`（P1）；本报告供监督员审核，审核通过后监督员可派主开发或创建新 active plan（若需）。
- **本规划不构成实施授权。**
