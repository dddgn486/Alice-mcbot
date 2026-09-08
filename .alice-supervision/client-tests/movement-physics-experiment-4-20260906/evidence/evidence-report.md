# Movement 物理实验 4 证据报告

日期：2026-09-06

## 客户端观察

用户确认上台阶和下降一级均通过，未观察到异常。

## 服务端事实

### 上一级

```text
plan direction=ASCEND heightDiff=1 y=-60.000
completed goalFoot=-5,-59,79 actualFoot=-5,-59,79 support=去皮丛林木 onGround=true y=-59.000
terminal=COMPLETED code=done
```

### 下降一级

第二次入口日志标签仍为 `ASCEND`，但物理事实明确为下降：

```text
plan heightDiff=-1 y=-59.000
completed goalFoot=-5,-60,82 actualFoot=-5,-60,82 support=草方块 onGround=true y=-60.000
terminal=COMPLETED code=done
```

两次均有 `Movement 完成: 1/1 type=WalkMovement`，并在终态清理 Controller 输入。

## 结论

实验 4 达到 `USER_ACCEPTED`：纯 `WalkMovement` 能通过 BotController/aiStep 物理链完成一级上升和一级下降，且真实 Y、脚位、支撑和 `onGround` 与目标一致。

已知诊断问题：下降测试的入口方向字符串仍打印为 `ASCEND`；`heightDiff=-1` 已提供真实下降事实。该标签问题不改变执行结果，后续可在不影响物理链的维护修改中修正。

本结论不覆盖跳跃、两格以上落差、动态阻挡、世界修改型 Movement 或 MineTask 接入。
