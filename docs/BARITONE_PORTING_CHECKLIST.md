# Baritone 1.20.1 规划器移植清单

## 决策

Alice 当前的 `AStarPathfinder -> BotMiner 清障 -> AStarPathfinder` 链式逻辑不是成熟的破坏感知规划器。实测出现近距离目标节点超限、清障副产物抢目标、反复换站位和 `collect_no_path`，后续不再向旧阶梯分支追加特判。

采用 Baritone 1.20.1 的计算模型，参考 Automatone 的服务端执行分层：计算器负责带动作与成本的路径，Alice 负责 ServerPlayer 执行、安全区与任务生命周期。

来源：

- https://github.com/cabaletta/baritone/tree/1.20.1
- https://github.com/cabaletta/baritone/blob/1.20.1/LICENSE
- https://github.com/Ladysnake/Automatone
- https://github.com/PrismarineJS/mineflayer-pathfinder

Baritone 为 LGPL-3.0。若复制源文件，保留许可证头、改动说明和许可证；若仅按算法净室重写，不复制实现文本/结构，则只记录参考来源。

## 保留约束映射

- `allowDownward = false`：不破坏当前脚位正下方方块。
- `maxFallHeightNoWater = 1`，禁用水桶/多格自由落体：任何下行边最多一格高差。
- 安全区、负硬度、流体风险和清障高代价方块：break cost = INF 或直接拒绝。
- 明确用户目标与自动清障使用不同 break policy：明确黑曜石目标允许，清障黑曜石回避。
- 物品目标使用整数脚位格 + 执行层中心校验，未到格心不得 `playerTouch`。
- 搜索必须有毫秒预算、节点/半径边界和可诊断失败原因；不能用无限扩展换“最终失败”。

## 建议移植边界

### 第一阶段：净室原型

1. `ActionCosts`：移动、下落、破坏和放置的统一 tick 成本。
2. `Goal`、`GoalBlock`：整数脚位终点。
3. `Movement`、`MutableMoveResult`：动作携带目标点、待破坏方块和成本。
4. `CalculationContext` 的 Alice 版：世界查询、工具破坏时间、安全区/清障策略、搜索预算。
5. `BinaryHeapOpenSet`、`PathNode`、限时 A*：不复制客户端行为层。

### 第二阶段：动作原语

按优先级移植 `TRAVERSE`、`ASCEND`、`DESCEND`、`DIAGONAL`。明确关闭 `DOWNWARD`、多格 `FALL`、PARKOUR、PILLAR 和水桶动作，直到返回路径证明加入。

每个动作返回：

- 到达脚位；
- 需要破坏的方块列表；
- 每个方块的 `BreakIntent`；
- 总 tick 成本；
- 失败原因与替代动作。

### 第三阶段：服务端执行层

- 计算在独立快照上运行，服务器 tick 只执行已批准动作；
- 破坏仍只能通过 `BotMiner` 唯一出口；
- 动作前后验证世界状态，变化则取消剩余路径并局部重算；
- 路径执行必须将实体移动到每个动作的中心，而不是只进入碰撞/吸取半径；
- 目标产物按来源方块格锁定，清障产物不得抢占原始目标。

## 验收门槛

- 简单挖掘：10 秒内；
- 普通路径：20 秒内；
- 清障/阶梯：30 秒内；
- 超时即失败，即使最终方块被挖掉或掉落物入包也不能 PASS；
- 记录搜索耗时、展开节点、路径动作数、破坏方块数、实际到达格心误差；
- 封闭场景必须验证：无脚下挖、无超过一格自由落体、受保护/昂贵方块未被清障破坏、掉落物实际格心拾取。

## 道路蓝图的独立数学方向

道路构建不直接等待 Baritone 移植，而使用独立的几何/体素模型验证路线概念：

- 连续模型把禁挖区域视为封闭三维区域，理想路径是贴禁区边缘的“绷紧绳子”；
- 体素模型用受约束最短路近似该曲线，液体及一格 clearance 是 forbidden volume；
- 水平 8 邻只在两个正交侧格都安全时允许，拐角净空拓宽；
- 垂直差绑定水平移动，不允许终点列上的纯竖井；不足的水平距离由路线向外弯曲补足；
- 该模型只负责产生通道蓝图，`RoadBuilder` 负责逐单元稳定执行，旧 MineTask 不参与。

## 当前诊断结论

严格预算回归已证明旧实现仍有失败：TEST5 超时；TEST6/7 因清障副产物和主产物混流出现 `collect_no_path`；TEST9 场景残留污染决策扫描。此结果保留为重构前基线，不能以“自测最终有 PASS 项”代替整体通过。
