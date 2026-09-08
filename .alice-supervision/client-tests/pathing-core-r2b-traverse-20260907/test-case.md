# R2-B Traverse 测试用例

## 测试标识

- **测试 ID**: pathing-core-r2b-traverse-20260907
- **测试阶段**: R2-B (Alice Pathing Core - Traverse Movement)
- **测试类型**: 客户端验收测试
- **验收状态**: `USER_ACCEPTED`

## 功能描述

R2-B Traverse 是 Alice Pathing Core 的第一个具体 Movement 执行器实现，提供同高度四向相邻移动能力。

**核心设计**:
- 基于 R2-A 纯数据契约 (`MovementExecution`/`MovementExecutionFactory`)
- 严格几何约束：只处理同高度四向相邻目标
- 命令驱动：`/alice pathing traverse <north|south|east|west>`
- 独立验证链：不接入生产 MineTask

## 测试入口

### 命令格式
```
/alice pathing traverse <direction>
```

**参数**:
- `direction`: `north` | `south` | `east` | `west`

**行为**:
1. 从 Bot 当前脚位自动派生同高度相邻目标
2. 创建 `TraverseDiagnosticTask` 和 `TraverseExecution`
3. 执行移动并报告终态
4. 超时保护：100 tick 执行预算

## 测试环境

- **Minecraft**: 1.20.1
- **Forge**: 47.4.10
- **Java**: 17
- **客户端**: Windows 固定客户端
- **Mod 版本**: alice-1.0.0-1.20.1.jar
- **工件 SHA-256**: `fc0ad051208422882b5d8c1060bb4afe822552a1ef753ac5e0964b8e09e0fbbe`

## 测试步骤

### 前置条件
1. 确保 Bot 已生成（如 `/alice spawn tango`）
2. Bot 处于平坦地形，周围无障碍
3. Bot 脚位支撑稳定，onGround=true

### 执行步骤
1. 选择测试方向（north/south/east/west）
2. 执行命令：`/alice pathing traverse <direction>`
3. 观察 Bot 移动到相邻方块
4. 检查服务端日志中的 `[R2-B Traverse]` 前缀日志

### 预期结果
- Bot 平滑移动到目标脚位
- 日志显示 `started` → `completed`
- `actualFoot` 与目标一致
- `support=true`, `onGround=true`
- `controllerActive=false`（清理完成）
- 完成时间 < 100 ticks

## 测试覆盖

### 功能覆盖
- ✅ 四向移动（north/south/east/west）
- ✅ 目标自动派生
- ✅ 前置条件检查
- ✅ 执行器生命周期
- ✅ 终态报告
- ✅ Controller 清理

### 边界覆盖
- ✅ 严格几何约束（只接受四向参数）
- ✅ 同高度验证
- ✅ 相邻性验证
- ✅ 超时保护机制（框架存在，未触发）

### 未覆盖场景
- ⏸️ 障碍阻挡（动态/静态）
- ⏸️ 无支撑情况
- ⏸️ 超时触发
- ⏸️ 取消机制
- ⏸️ 前置条件不满足

## 实现文件

### 新增文件
- `src/main/java/com/dddgn/alice/pathing/core/TraverseExecution.java` (140 行)
- `src/main/java/com/dddgn/alice/pathing/core/TraverseExecutionFactory.java` (79 行)
- `src/main/java/com/dddgn/alice/task/TraverseDiagnosticTask.java` (120 行)

### 修改文件
- `src/main/java/com/dddgn/alice/bot/BotManager.java`: 新增 `assignTraverseDiagnostic()`
- `src/main/java/com/dddgn/alice/command/BotCommand.java`: 新增 `/alice pathing traverse` 命令
- `src/main/java/com/dddgn/alice/item/AliceItems.java`: 移除旧测试物品

### 依赖
- R2-A 纯数据契约 (`pathing.core` 包)
  - `MovementExecution` 接口
  - `MovementExecutionFactory` 接口
  - `LiveExecutionContext`
  - `MovementSpec`

## 关键设计决策

### D-022: R2-B Traverse 独立验证
- **状态**: `USER_ACCEPTED`
- **决策**: 使用命令 + 方向参数，移除测试物品
- **原因**: 避免工件混乱和目标派生不确定性

### 架构边界
- ✅ 不接入生产 MineTask
- ✅ 不修改 Legacy PathExecutor
- ✅ 保持独立验证链
- ✅ 遵守 R2-A 契约

## 已知限制

1. **仅支持同高度平面移动**: 不处理上升/下降
2. **无障碍避让**: 遇到障碍直接报告失败（当前未测试）
3. **无世界修改**: 不破坏方块或放置台阶
4. **诊断性质**: 设计为验证工具，非生产级 Movement

## 后续计划

### R2-C 候选（待用户确认）
- Diagonal: 同高度对角移动
- Ascend: 一级上升（允许水平位移）
- Descend: 一级下降（允许水平位移）

### R3+ 路线图
- PathSession 多段链接
- Movement-aware 搜索器
- 任务层适配（MineTask 等）

---

**文档版本**: 1.0  
**创建日期**: 2026-09-07  
**维护者**: Alice Project Team
