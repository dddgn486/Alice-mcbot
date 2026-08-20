# 寻路与采集重构方案

> 状态：已定稿，第一版先恢复单目标纯曲面寻路；通道规划、目标簇与完整时间成本模型后续按本文件逐层接入。

## 一、问题与边界

此前将连续道路几何、体素道路蓝图、曲面行走、通道施工和目标挖掘合并进 `RoadMineTask`。实测表明这会把普通目标误判为通道目标，并使施工单元反向影响路线选择。

重构后保留两个互不混淆的寻路模块：

1. `SurfacePathfinder`：只在真实可通行曲面上行走，绝不破坏或放置方块；
2. `TunnelPlanner`：只在曲面寻路确认不可达时，连接两个已确定的曲面点。

手动道路蓝图 `RoadPlan`/`RoadBuilder` 保持独立，不再作为挖矿任务的输入或全局单例通道计划。

## 维生系统与挖矿边界

所有任务先经过统一 `SurvivalSystem` 监测。第一版硬中断危险为 `LAVA_CONTACT` 与 `SUFFOCATING`，水、低空气和着火先记录，不在任务内部擅自逃生；`MineTask`/`DropCollectionTask` 也重复执行同一硬中断检查，保证被单独驱动时不继续工作。后续由独立 `EmergencyEscapeTask` 或软移动模式处理。强制施工也不能绕过维生监测。当前框架为 `MovementMode.HARD_PATH/SOFT_SURFACE/SOFT_FLUID/FORCED_BUILD` 预留边界：普通挖矿仍固定使用现有 `HARD_PATH`。`SOFT_SURFACE` 的默认实验后端是 `NATIVE_TRAVEL`，可通过 `/alice soft-probe`、金斧 `alice:soft_move_selector`、`/alice soft-path-probe` 和独立 `FollowTask` 验证；`SELF_MOVE` 仅作回归对照。FollowTask 已实现但尚待客户端逐项验证；软移动未接入普通任务寻路、液体、高差稳定链、道路或挖矿。

挖掘前 `FluidRiskPolicy` 会保守拒绝目标或相邻六格存在可见岩浆的操作，返回 `fluid_risk_lava`，不自动堵水、挖源头或把普通挖矿扩大成流体工程。复杂流体传播仍必须客户端验证。

## 成熟方案复用原则

涉及路径搜索、玩家移动、流体/碰撞、假人控制或通道几何时，优先调研并借鉴维护中的成熟项目；已有可移植算法、行为原语或原版 API 能覆盖的问题，不从零重写核心算法。当前优先参考 Baritone 的“路径 -> movement primitive -> 输入覆盖”执行模型，以及 Carpet fake player 的 action pack/玩家行为驱动模型；由于 Alice 是 Forge 1.20.1，不能直接引入 Fabric 实现，必须以最小适配层复用其架构思想和原版语义。

`setPos` 的 `HARD_PATH` 只保留为当前稳定兼容模式。`SOFT_SURFACE` 不应继续扩展为逐 tick 直接 `move` 到格心的自制运动系统；探针仅用于验证 Forge 假人上的原版碰撞 API。后续软移动应优先实现受限输入驱动执行器，并先从平地走路、朝向、前进和跳跃原语开始，客户端实测后再接入寻路。

参考：Baritone [输入与移动控制](https://deepwiki.com/cabaletta/baritone/6.4-input-and-movement-control)、[路径执行器](https://git.jerryxiao.com/mc/baritone/src/branch/1.18-squashed/src/main/java/baritone/pathing/path/PathExecutor.java)、Carpet [fake player 实现](https://github.com/gnembon/fabric-carpet/blob/master/src/main/java/carpet/patches/EntityPlayerMPFake.java)。

## SOFT_SURFACE 分阶段路线

1. **坐标契约与诊断**：工具右键的是支撑方块，任务目标是其上方脚位格；命令直接传脚位格。所有入口必须调用同一个结构化校验，分别报告距离、高度、支撑、脚位碰撞和头部碰撞失败。
2. **原版碰撞探针**：客户端已验证 `NATIVE_TRAVEL` 可跨一格障碍并正确下落，因此金斧普通右键和 `/alice soft-probe` 默认使用它；`SELF_MOVE` 降为金斧 Shift+右键的平地回归对照。抵达不能只按水平距离成功，必须确认真实脚位等于目标、目标有支撑且原版 `onGround` 已成立。若跨障后仍未落稳，使用零输入 `travel(Vec3.ZERO)` 仅做原版重力/摩擦结算，超时返回 `soft_probe_unsettled`，绝不 `setOnGround(true)` 伪造成功；直线探针仍不处理跳跃、高差、液体或寻路。
3. **输入驱动适配**：参考 Baritone 的 movement primitive/input override 与 Carpet fake player action pack，在 Forge 1.20.1 中确认 `ServerPlayer` 的 `tick/travel` 边界；当前 `SoftMovementPrimitive` 实现朝向 + 前进的最小适配原语。Forge 映射已确认 `travel(Vec3)`、`xxa`、`zza` 可用，但 `ServerPlayer.tick()` 不会自然消费 fake player 输入；当前由任务逐 tick 显式调用 `travel`。客户端已验证 `NATIVE_TRAVEL` 的跨障和落地。`/alice soft-path-probe <脚位>` 复用 `SurfacePathfinder` 生成连续脚位段：平地与下降使用原版前进/重力，已规划且再次通过 `MovementHelper.canAscend` 的单格上阶才显式设置 jump 后调用 travel；每段输出 `action/from/to/foot/onGround` 日志，并在路径关系或几何失配时返回 `soft_path_invalid_segment`。上/下台阶、混合段、受阻净空和半格支撑均仍为 Windows `CLIENT_TEST_PENDING`，不接入普通任务寻路或 FollowTask。完整 input/travel 控制层仍待验证。
4. **安全地面原语**：复用现有 `MovementHelper`/A* 的支撑和碰撞语义，只增加成熟方案缺失的适配层；先做直线平地，再由 `SurfacePathfinder` 输出连续脚位段后做转向、单格上台阶和下台阶。落地验收必须比较实体实际脚底与目标脚位下方 `VoxelShape` 的支撑顶面，不能只比较 `blockPosition()` 的整数 Y；这兼容下半砖、台阶等非整格碰撞形状，同时不把障碍边缘接触误判为到达。单目标直线探针不得自行猜测或跨越障碍；每个路径段在进入和完成后均需验证脚位、支撑和落地，复杂碰撞交给客户端测试。
5. **流体与应急**：在 `SurvivalSystem` 监测和 `EmergencyEscapeTask` 设计稳定后，单独验证受限水域；不把水中移动、灭火、逃生混进普通曲面执行器。
6. **任务接入顺序**：第一个正式低风险接入是 `/alice follow on|off`：只跟随执行命令的玩家，要求当前维度、24 格内且 bot 空闲；跟随距离为 2 格，目标每 10 tick 重新规划连续脚位段。超距、目标不在安全曲面、搜索失败或落地失败均立即停止。保护区停留、巡航、返航是独立模块，后续使用保护区的区域查询实现，不作为跟随前提；近距离追踪攻击目标也留待跟随客户端测试稳定后接入。它们应使用 `SOFT_SURFACE` 并在路径失效/危险时停止或回到任务层重规划；`MineTask`、`DropCollectionTask` 默认仍是 `HARD_PATH`，任何失败必须可回收并明确报告。挤压/碰撞、重力、摩擦、踏步、坠落伤害等优先交给原版 `travel`/实体物理；饥饿、伤害来源、攻击冷却、目标选择等仍由各自任务和 `SurvivalSystem`/事件层处理，不能假定“启用软移动”就自动拥有完整生物 AI。

## 决策与兜底原则

- 决策层（规则或 LLM）只选择目标、预算和是否授权通道；执行层必须独立验证世界状态，不能因为决策层建议而越过安全区、流体、不可破坏方块或材料/工具约束。
- 无法证明安全、A* 搜索预算耗尽、世界状态在执行前后变化时，默认停止并返回可诊断失败；宁可牺牲效率，也不让 bot 隐式开长通道、困在不可恢复位置或消耗未知资源。
- `SEARCH_LIMIT` 不是 `UNREACHABLE`：前者只说明当前预算不足，必须交由决策层扩大预算、换目标或人工客户端确认，不能授权 `TunnelPlanner`。
- 通道计划、施工进度和目标簇边界将分别冻结；执行器只能消费已验证计划，不能自行重选路线。当前尚未实现自动通道施工，因此普通挖矿继续失败为 `target_requires_tunnel`。

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

结果必须保留 `REACHED`、明确 `UNREACHABLE` 和 `SEARCH_LIMIT`（含已扩展节点数）。只有前两者可用于确定性流程；`SEARCH_LIMIT` 是保守中止，不可当作“目标深埋”的证据。

只要任一合法站位存在纯曲面路径，任务就直接由 `MineTask` 执行，**绝不启动通道规划**。`BotMiner` 必须在所有有限候选中优先比较“视线直通 + 曲面可达”的站位，并选取最短曲面路径；不能因目标下方/同平面/上方的分组顺序先走向一个视线受阻的站位。只有没有直通曲面站位时，才允许回退到视线受阻候选，并由 `MineTask` 从当前站位直接清理最多两个、位于原版 4.5 格挖掘距离内的 blocker；超过该范围或需要重选站位的深埋目标返回 `target_requires_tunnel`。

## 三、通道规划

仅在所有目标挖掘站位的 `SurfacePathfinder` 均明确 `UNREACHABLE` 时，才允许调用 `TunnelPlanner`。任一站位 `REACHED` 时必须走曲面；任一站位 `SEARCH_LIMIT` 时必须保守停止或交给决策层重试，不能自动开通道。

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
- 通道候选先经独立 `TunnelObstaclePolicy` 硬验证：第一版拒绝流体、保护区、不可破坏/高代价清障、不连续或纯竖直体素几何；它不复用 `RoadPlan`，也不负责施工；
- 施工阶段只消费已选定的 `TunnelPlan`，不得重新选择入口、出口或路线；
- 无合格计划时返回 `no_tunnel_plan`，不得为普通目标强行开隧道。

通道执行顺序：到入口曲面点 -> 清理下一单元净空 -> 放置下一单元支撑 -> 移动到已完成单元 -> 到出口后使用曲面寻路到挖掘站位 -> `MineTask`。

## 四、掉落物收集

掉落物由独立 `DropCollectionTask` 处理，仍只收集主动目标方块产生的 `origin` 物品；它只消费父任务的 `ScopeBuffer`，不得自行结束作用域或清理高亮。路径策略独立且保守：

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

## 六、测试与客户端验证

headless 自检只覆盖可重复的基础不变量：普通曲面直达、隔空挖拦截、曲面失败不隐式施工、origin 掉落物过滤和有限拾取阶梯。不要为复杂洞穴、流体扩散、沙砾链式坠落或模组方块碰撞拼装脆弱场景；这些应在客户端世界中实测并记录日志。

客户端工具：

```text
/alice diagnose-path <x y z>  只读显示 REACHED/UNREACHABLE/SEARCH_LIMIT、路径段数和扩展节点数
/alice status                 只读显示 bot 是否忙、上次任务结果和当前位置
/alice mine <x y z>           分配单目标曲面挖矿，用于观察失败码与回收位置
```

客户端重点检查：复杂洞穴高差、流体边界、沙砾/沙子坠落、模组方块碰撞、保护区动态变化、长距离搜索预算耗尽、背包或工具不足。任何这些场景中 bot 无法继续时，预期行为是停止、报告原因并保持可回收，而非自行扩张破坏范围。

## 七、实施顺序

1. 恢复单目标 `MineTask` 纯曲面 A* 链，并移除混合道路任务接管；
2. 将现有 A* 封装为 `SurfacePathfinder` 返回结构化结果；
3. 定义独立 `TunnelPlan`/`TunnelPlanner`，不复用 `RoadPlan` 单例；
4. 仅在曲面不可达后接入通道执行；
5. 抽出 `DropCollectionTask`，保持无长通道规则；
6. 实现 `TargetCluster` 与 `ClusterMineTask`；
7. 为上述决策补充专用回归场景。
