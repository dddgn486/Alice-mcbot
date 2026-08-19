# 寻路与采集重构方案

> 状态：已定稿，第一版先恢复单目标纯曲面寻路；通道规划、目标簇与完整时间成本模型后续按本文件逐层接入。

## 一、问题与边界

此前将连续道路几何、体素道路蓝图、曲面行走、通道施工和目标挖掘合并进 `RoadMineTask`。实测表明这会把普通目标误判为通道目标，并使施工单元反向影响路线选择。

重构后保留两个互不混淆的寻路模块：

1. `SurfacePathfinder`：只在真实可通行曲面上行走，绝不破坏或放置方块；
2. `TunnelPlanner`：只在曲面寻路确认不可达时，连接两个已确定的曲面点。

手动道路蓝图 `RoadPlan`/`RoadBuilder` 保持独立，不再作为挖矿任务的输入或全局单例通道计划。

## 二、单目标挖掘

默认链路必须是：

```text
TargetSelector / /alice mine / auto-mine
  -> 枚举目标合法挖掘站位
  -> SurfacePathfinder
  -> MineTask
  -> 仅收集该目标 origin 的掉落物
```

`SurfacePathfinder` 复用现有 `AStarPathfinder` 与 `PathExecutor`，输入为 bot 当前脚位与一个合法挖掘站位。它的可走条件是：脚下已有实体支撑、脚位与头位无碰撞、无流体，并采用已有的上坡/下坡安全规则。

只要任一合法站位存在纯曲面路径，任务就直接由 `MineTask` 执行，**绝不启动通道规划**。

## 三、通道规划

仅在所有目标挖掘站位的 `SurfacePathfinder` 均不可达时，才允许调用 `TunnelPlanner`。

`TunnelPlanner` 的输出不是道路单元，而是不可变的 `TunnelPlan`：

```text
surfacePath(bot -> entrance)
entrance surface point
mathematical tunnel curve(entrance -> exit)
exit surface point
surfacePath(exit -> mining stand)
```

规划阶段的总代价为：

```text
Cost = surfaceMoveCost(start, entrance)
     + tunnelGeometryLength(entrance, exit) * TUNNEL_FACTOR
     + surfaceMoveCost(exit, miningStand)
```

- `tunnelGeometryLength` 只取数学模型中直线或贴禁区曲线的长度，经体素映射近似；
- 实际施工生成的净空格、支撑格和重试次数不参与该成本；
- 禁区仍由 `RoadObstaclePolicy` 提供；
- 施工阶段只消费已选定的 `TunnelPlan`，不得重新选择入口、出口或路线；
- 无合格计划时返回 `no_tunnel_plan`，不得为普通目标强行开隧道。

通道执行顺序：到入口曲面点 -> 清理下一单元净空 -> 放置下一单元支撑 -> 移动到已完成单元 -> 到出口后使用曲面寻路到挖掘站位 -> `MineTask`。

## 四、掉落物收集

掉落物仍只收集主动目标方块产生的 `origin` 物品。路径策略独立且保守：

```text
等待落地稳定 -> SurfacePathfinder 到掉落物脚位 -> 拾取
```

允许保留现有的有限侧向单格下行阶梯恢复：不得挖脚下支撑、不得跨越安全区/禁区、不得造成超过一格自由落下。

掉落物**不得**启动完整 `TunnelPlanner`。若曲面寻路和有限恢复均失败，任务以明确收集失败结束；未来如需要“产物优先模式”，必须由显式任务选项授权。

## 五、目标簇连续挖掘

连续挖掘使用 `TargetCluster` 与 `ClusterMineTask`，而不是为每个目标重复全局寻路。

### 5.1 簇定义

- 同一个方块 ID，或同一个 `auto-mine` 标签匹配的方块；
- 使用 6 邻接扩张，不把纯对角接触并入；
- 初版限制半径 8 格、最多 64 个方块；
- 每挖一块后局部复扫，允许新暴露的同类方块加入。

### 5.2 簇级规划与局部开采

```text
发现 TargetCluster
  -> 一次 SurfacePathfinder 到簇外围工作点
  -> 若不可达，至多一次 TunnelPlanner 到簇外围出口点
  -> 循环选择簇内下一个局部目标并调用 MineTask
  -> 簇耗尽或无安全可达目标时结束
```

长通道最多为“进入该簇”规划一次。通道禁止进入目标簇膨胀一格后的 AABB；进入簇后只能使用局部曲面路径和受限清障，不能因簇内单个矿石再次启动长通道。

簇内目标优先级：当前站位可挖 -> 纯曲面可达 -> 单层直接可见且安全的局部清障 -> 重新扫描暴露面 -> 结束簇。

## 六、实施顺序

1. 恢复单目标 `MineTask` 纯曲面 A* 链，并移除混合道路任务接管；
2. 将现有 A* 封装为 `SurfacePathfinder` 返回结构化结果；
3. 定义独立 `TunnelPlan`/`TunnelPlanner`，不复用 `RoadPlan` 单例；
4. 仅在曲面不可达后接入通道执行；
5. 抽出 `DropCollectionTask`，保持无长通道规则；
6. 实现 `TargetCluster` 与 `ClusterMineTask`；
7. 为上述决策补充专用回归场景。
