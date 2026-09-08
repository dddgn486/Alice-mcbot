# Movement 实验 6：结果契约

> 本文只冻结事实型结果语义，不实现执行器分类，也不设计自动恢复策略。

## 适用范围

独立 Movement 实验及 `MovementPathExecutor` 事实报告。不得据此接入 MineTask、旧 `PathExecutor` 或自动重规划。

## 结果枚举

### `BLOCKED_DYNAMIC`

只有同时满足以下条件才允许使用：

1. Movement 已进入执行阶段；
2. 任务启动时，对应路径/扫掠区域满足当前 Movement 的通行前置条件；
3. 执行期间，服务端确认行走方向上的方块碰撞体新出现或发生变化；
4. Bot 尚未到达目标；
5. 在连续观察窗口内没有有效位移或只发生碰撞前的有限位移。

必须记录：

```text
movementIndex
from
to
obstaclePos / obstacleShape
obstacleObservedTick
botFoot
botY
support
onGround
noProgressTicks
```

### `MOVEMENT_TIMEOUT`

Movement 未完成并超过无进展或执行预算，但没有足够的服务端证据证明是动态方块阻挡。

这是保守兜底结果。不能仅凭 Bot 不动、速度为零或碰撞现象直接改成 `BLOCKED_DYNAMIC`。

### `INVALID_PRECONDITION`

Movement 尚未进入执行阶段时，起点、目标脚位、支撑、头部空间、扫掠空间或其他 Movement 专用前置条件不满足。

启动前不可执行不得伪装成动态阻挡，也不得等待到超时后再报告。

## 共同终态要求

三类结果都必须：

- 记录实际终点和失败阶段；
- 产生稳定失败码；
- 清理 `BotController` 的移动、跳跃、潜行和疾跑输入；
- 保留服务端时间和必要的诊断字段；
- 不自动挖障碍；
- 不自动绕行；
- 不自动重规划；
- 不传送；
- 不切换到其他 Movement。

## 第一版排除项

以下情况第一版不强行分类为 `BLOCKED_DYNAMIC`：

- 实体推挤；
- 流体；
- 复杂或非标准碰撞体；
- 未知模组方块；
- 无法证明是在执行期间出现的障碍。

证据不足时回退 `MOVEMENT_TIMEOUT`，而不是猜测分类。

## 对照实验要求

实验 6 至少需要：

1. 动态方块阻挡组：启动时可通行，执行中出现封闭方块屏障；
2. 普通超时对照组：无可确认的动态方块障碍，仍保留 `MOVEMENT_TIMEOUT`；
3. 启动前非法组：前置条件不满足，直接 `INVALID_PRECONDITION`。

每组都需要服务端日志；实体、物理和玩家可见行为还需要 Windows 客户端验证。

## 当前状态

```text
CONTRACT_FROZEN
IMPLEMENTED
WINDOWS_CLIENT
USER_ACCEPTED
```

三组对照均已完成：6A 动态方块证据归类为 `BLOCKED_DYNAMIC`，6B 无动态方块证据保留 `MOVEMENT_TIMEOUT`，6C 启动前头部空间非法立即报告 `INVALID_PRECONDITION`。6C 夹具的可见副作用是 Bot 头部障碍触发生存窒息诊断；这不改变 Movement 任务的结构化失败码。
