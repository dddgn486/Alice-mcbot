---
name: alice-path-planning-execution-contract
description: 防止 Alice 寻路结果与真实移动执行混淆，固定脚位、支撑、路径状态和完成条件。
---
# Alice Path Planning and Execution Contract

## 何时使用
修改 SurfacePathfinder、A*、Movement、PathExecutor、站位选择、高差、碰撞或挖掘接入时使用。

## 核心原则
规划“可走”不等于实体“已到达”。规划器输出候选路径，执行器必须逐段重验并用实体实际位置和支撑事实判断完成。

## 坐标先说清楚

每个接口注明目标是：

```text
脚位 foot / 支撑方块 support / 目标方块 target / 实体位置 entity
```

禁止用一个含糊的 `BlockPos target` 在层之间传递不同语义。

## 结果语义

```text
REACHED       已证明可达
UNREACHABLE   在搜索规则和预算内证明不可达
SEARCH_LIMIT  预算耗尽，未知
BLOCKED       执行时环境变化导致当前段受阻
```

`SEARCH_LIMIT` 不能自动变成 `UNREACHABLE`，也不能单独授权挖隧道。

## 轻量验证

先用独立测试物品或短命令验证平地、上阶、下降和阻挡四种最小场景；日志输出 `from/to/action/foot/support/result`。通过后再接入正式任务。复杂碰撞只要求用户在 Windows 客户端观察并反馈关键现象。
