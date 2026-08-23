# 软路径测试工具客户端复测结果

- 提交：`65f4863`
- 计划 ID：`20260824-soft-path-test-tool-v1`
- 测试人：user
- 测试日期：2026-08-24 20:01-20:04
- 状态：**PARTIAL_PASS（T1-T3 通过，T4 发现严重问题）**

## 测试结果

### T1 右键软寻路：✅ PASS
- 操作：右键石头
- 结果：bot 软寻路到石头旁（foot = support.above()），石头完整
- 日志：`soft-path-test: support=... foot=...`（20:01:31 等）
- 观测：无 P1 观测日志输出（平地直线，无偏离）

### T2 Shift+右键挖掘：✅ PASS
- 操作：Shift+右键石头
- 结果：bot 软寻路 → 到达后挖掘 → 石头消失
- 日志：
  - `soft-path-mine-test: support=... foot=...`（20:03:06 / 20:04:22 / 20:04:43）
  - `软路径挖掘: 导航完成，开始挖掘 target=...`
  - `软路径挖掘完成: target=...`
- Phase 切换正常：SOFT_NAVIGATE → MINING → DONE

### T3 隔离验证：✅ PASS（最重要）
- 操作：`/alice mine <矿>`（多次，20:02:34-20:02:50）
- 结果：日志只含 `MineTask` / `task_execution_terminal kind=MineTask`
- **确认**：MineTask 日志附近**无** `soft_phys` / `NATIVE_TRAVEL` / `SoftPathMineTask` 关键字
- 冻结边界守护成功：MineTask 保持 HARD_PATH 不受影响

### T4 P1 观测回归：❌ FAIL（发现严重问题）
- 操作：Shift+右键，软寻路途中
  - 玩家堵路（一格宽通道）
  - 玩家攻击 bot（击退）
- **问题 1**：bot **穿过玩家**（无碰撞箱反应）
  - 一格宽的路也堵不住 bot，直接穿过玩家
- **问题 2**：击退**无位移**
  - 攻击 bot 后，bot 无击退位移，继续前进
- **影响**：P1 物理观测的核心假设失效（NATIVE_TRAVEL 应保留碰撞/击退）

## 结论

### 测试工具本身：✅ USER_ACCEPTED（T1-T3）
- 右键寻路、Shift+右键挖掘功能正常
- 冻结边界守护完美（MineTask 不受影响）
- 可用于游戏内测试

### P1 物理观测：❌ CRITICAL_ISSUE（T4）
- **bot 没有与玩家碰撞箱发生反应**
- **击退无位移**
- P1 的核心假设"NATIVE_TRAVEL 保留原版碰撞/重力/击退"**可能不成立**
- 需要深度调查：
  1. Fake ServerPlayer 是否正确调用 `travel`？
  2. `travel` 是否正确处理实体碰撞（Entity.push / collideWithOtherEntities）？
  3. 击退速度（deltaMovement）是否被正确应用？
  4. 是否有 Forge hook 跳过了 fake player 的物理计算？

## 日志关键证据

- T1-T2：`run/logs/latest.log` 行 222-415（20:01-20:04）
- T3 隔离：行 230-306（MineTask 无 soft 关键字）
- T4 碰撞问题：用户观察（未在日志中体现，因为 P1 观测只记录 bot 自身状态，未记录与玩家碰撞）

## 下一步

1. **测试工具**：T1-T3 通过，可批准为正式工具
2. **P1 物理观测**：需暂停批准，派发深度调查任务：
   - 调查 Fake ServerPlayer + NATIVE_TRAVEL 物理正确性
   - 对比原版玩家 vs fake player 的 `travel` / 碰撞 / 击退行为
   - 确认 P1 实施是否遗漏关键物理 hook
   - 可能需要回到 P0（冻结 SOFT_SURFACE 接入）或重新设计 P1
