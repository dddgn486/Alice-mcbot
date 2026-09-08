# R2-C Movement 测试用例

## 测试标识

- **测试 ID**: pathing-core-r2c-movements-20260907
- **测试阶段**: R2-C（Alice Pathing Core — Diagonal / Ascend / Descend）
- **测试类型**: 客户端验收测试
- **验收状态**: `USER_ACCEPTED`（含已裁定暂不处理的落点过冲行为）

## 功能描述

R2-C 在 R2-B Traverse 基础上新增三种基础 Movement 执行器，均遵循 R2-A 纯数据契约：

| Movement | 几何约束 | 特性 | 成本 |
|---|---|---|---|
| Diagonal | dy=0, abs(dx)=1, abs(dz)=1 | 两侧方块通行性检查（防对角穿墙） | 1.414 |
| Ascend | dy=+1, 曼哈顿水平距离=1 | 头部空间检查、SETTLING 阶段、利用原版自动踩台阶（无跳跃） | 1.5 |
| Descend | dy=-1, 曼哈顿水平距离=1 | SETTLING 阶段、blockPosition Y 下降检测、回收等级 LOCAL_STEP | 1.2 |

## 测试入口

```
/alice pathing diagonal  <northeast|northwest|southeast|southwest>
/alice pathing ascend    <north|south|east|west>
/alice pathing descend   <north|south|east|west>
```

命令从 Bot 当前脚位派生目标，100 tick 执行预算，终态清理 BotController 输入。

## 实现文件

### 新增
- `src/main/java/com/dddgn/alice/pathing/core/{Diagonal,Ascend,Descend}ExecutionFactory.java`
- `src/main/java/com/dddgn/alice/pathing/core/{Diagonal,Ascend,Descend}Execution.java`
- `src/main/java/com/dddgn/alice/task/{Diagonal,Ascend,Descend}DiagnosticTask.java`

### 修改
- `src/main/java/com/dddgn/alice/bot/BotManager.java`: 新增 3 个 assign 方法
- `src/main/java/com/dddgn/alice/command/BotCommand.java`: 新增 3 个命令与执行方法

## 测试步骤

1. 生成 Bot（`/alice spawn tango`），置于平坦/台阶测试地形
2. 执行对应方向命令
3. 观察 Bot 移动与聊天回显
4. 检查 `[R2-C *]` 日志的 started → completed/failed 终态

## 预期结果

- `completed`：actualFoot 与目标一致、support=true、onGround=true、controllerActive=false
- 前置条件不满足时 `rejected` / `INVALID_PRECONDITION`，不进入执行

## 测试轮次摘要（详见 evidence-report.md）

1. **Round 1**（`1b00ed0d`）: Diagonal 4/4 完成；Ascend settling 全超时（跳跃干扰）；Descend 完全不动
2. **Round 2**（`871f4d51`）: Ascend 移除跳跃后大部分完成；Descend 仍不动
3. **Round 3**（`89c38637` 探针版）: 定位 Descend 根因 —— `getY() < fromY+0.5` 恒真，第 1 tick 即进入 SETTLING
4. **Round 4**（`804c5eb8`）: blockPosition 修复后 Descend 下降成立；1/4 精确落点完成，3/4 因自动踩台阶过冲报 settling 超时；**用户裁定过冲暂不处理，验收通过**

## 已知限制（用户裁定暂不处理）

1. Descend 落点过冲：下落到底后被自动踩台阶带上相邻方块边缘，actualFoot 偏离一格
2. Ascend 偶发水平位置偏差：踩台阶相位导致上层后未横移到目标列
3. 后续可参考 Baritone MovementDescend 的 sliding-block 预处理收紧落点

## 边界情况已验证

- ✅ Ascend 前置条件拒绝（用户故意构造头部空间不足/几何非法场景，返回 `ASCEND_INVALID_PRECONDITION`）
- ⏸️ 障碍阻挡、超时主动触发、取消机制：本轮未测

---

**文档版本**: 1.0
**创建日期**: 2026-09-08
