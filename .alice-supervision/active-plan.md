# Alice Active Work Plan

- Plan ID: `20260824-soft-path-test-tool-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Baseline: `2298dd3`（P1 已实施，CLIENT_TEST_PENDING 暂缓）
- User approval: explicit approval received (2026-08-24); 方案 A 批准，先做测试工具
- Implementation plan: `.alice-supervision/research/soft-path-test-tool-plan-20260824.md` (planner report, supervisor accepted)

## Objective

增加游戏内软路径测试工具（扩展现有 `soft_path_probe_selector`）：右键软寻路（已有）/ Shift+右键软寻路+挖掘（新增），避免精确坐标参数指令，便于 P1 客户端测试。

## Allowed Scope

### 方案 A：扩展现有 `SoftPathProbeSelector`

1. **SoftPathProbeSelector.java**：增加 `context.getPlayer().isShiftKeyDown()` 判定
   - 右键：`assignSoftPathProbe`（已有，不变）
   - Shift+右键：`assignSoftPathMine`（新增）

2. **SoftPathMineTask.java**（新增约 300 行）：
   - Phase.SOFT_NAVIGATE：完整复用 SoftPathProbeTask P1 逻辑（SurfacePathfinder + NATIVE_TRAVEL + P1 观测/revalidate/replan/MAX_REPLAN=3）
   - Phase.MINING：到达后调用 BotMiner.tick()（复用挖掘原语，不走 PathExecutor 硬寻路）
   - 不收集掉落物（测试工具简化）

3. **BotManager.java**：新增 `assignSoftPathMine(bot, target)` + `BotSession.assignSoftPathMine(targetPos)`

### 冻结边界守护

- **MineTask 保持 HARD_PATH 完全不动**（不改一行代码）
- 独立任务类（SoftPathMineTask 与 MineTask 隔离）
- 专用入口（assignSoftPathMine，不改 assign）
- 无自动切换（失败不回退 MineTask，两者永不互转）

### 交互设计

- 点击 support 方块（如石头）：
  - 右键 → bot 软寻路到 support.above()，不挖掘
  - Shift+右键 → bot 软寻路到 support.above()，到达后挖掘 support（点击的方块本身）
- Chat 消息 + 日志反馈（`soft-path-test` / `soft-path-mine-test`）

## Verification Matrix

### 服务端
- `./gradlew compileJava` PASS
- `git diff --check` PASS
- 既有 suites 保持 PASS
- 可选：新增 `SoftPathMineTaskTest.java` fixture（断言阶段切换、挖掘完成）

### 客户端（Windows，CLIENT_TEST_PENDING）

| 场景 | 操作 | 预期 |
|---|---|---|
| T1 右键寻路 | 右键石头 | bot 软寻路到石头旁，石头完整，P1 观测日志 soft_phys_obs |
| T2 Shift+右键挖掘 | Shift+右键石头 | bot 软寻路（P1 观测）→ 到达后挖掘石头 → 石头消失 |
| T2a Shift 寻路失败 | 封闭房间，Shift+右键外部方块 | P1 revalidate/replan → FAILED with soft_mine_no_path，石头完整 |
| T2b Shift 挖掘失败 | 保护区方块 Shift+右键 | 软寻路成功 → Phase.MINING → BotMiner 拒绝 → FAILED with soft_mine_dig_failed |
| T3 隔离验证 | `/alice mine <矿>` 或 target_selector | 日志只含 MineTask/PathExecutor/HARD_PATH，**无** soft_phys/NATIVE_TRAVEL |
| T4 P1 观测回归 | Shift+右键，途中碰撞/击退 | P1 观测 soft_phys_obs/disruption/revalidate/replan 正常 |

## Explicitly Forbidden

- 不改 MineTask.java（保持 HARD_PATH 不动）
- 不改 PathExecutor/BotMiner（核心挖掘/硬寻路不动）
- 不改 SoftPathProbeTask/FollowTask（P1 软任务不动，只复用逻辑）
- 不改 DropCollectionTask/道路/隧道
- 不做 P2-P5（primitive 扩展/独立 SoftTravelTask/任务链接入）
- SoftPathMineTask 不自动回退 MineTask

## Stop Conditions

Stop and return for replanning if:
- Shift+右键需改 MineTask 接入 SOFT_SURFACE（违反冻结边界）
- 任务链需改 BotManager 核心调度（超出测试工具范围）
- BotMiner 无法在 SOFT 到达后独立使用（需重新设计挖掘原语）
- 用户不批准方案 A（已批准）
- 客户端 T1-T4 任一场景无法通过（传送/卡死/MineTask 偷接 SOFT）

## Implementation Time Estimate

- 实施：2-4 小时（SoftPathMineTask 300 行 + Shift 判定 20 行 + BotManager 15 行）
- 编译+既有测试：0.5 小时
- 可选 fixture：1 小时
- HANDOVER：0.5 小时
- commit/push：0.5 小时
- **客户端测试**：2-3 小时（T1-T4 + evidence-report.md）
- **总计：6-9 小时**

## Notes

- 测试工具完成后，可用于 P1 客户端复测（右键/Shift+右键交互，替代手动坐标指令）
- P1 客户端复测暂缓（等测试工具完成）
