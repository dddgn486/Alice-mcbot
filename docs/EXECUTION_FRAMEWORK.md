# 执行层框架 v1（任务框架）

> 对应设计文档 §4「执行层设计」的首次代码落地。
> 目标：感知层 → 决策层 → 执行层的接线骨架，先跑通「最简单的挖掘物品任务」，
> 不用大模型，先看本地行为包的实际运行效果。

## 一、分层与职责

```
┌─ 决策层(未来: LLM / 规则) ─────────────────────────┐
│  只做一件事: 产出 TaskTarget(决定"干什么")            │
│  现阶段 = 测试工具直接指派(目标指定器 / /alice mine)  │
└──────────────────────┬─────────────────────────────┘
                       ▼ TaskTarget
┌─ 任务层 task/ ──────────────────────────────────────┐
│  Task 接口: target() / tick() / failureReason()     │
│  MineTask: MINING(BotMiner) → COLLECTING(拾取)      │
│  (编排"先做什么、后做什么")                           │
└──────────────────────┬─────────────────────────────┘
                       ▼ 动作调用 + 感知查询
┌─ 动作层 action/ ──┐   ┌─ 感知层 perception/ ────────┐
│  BotMiner 挖掘状态机│   │  ScopeBuffer: 掉落物/方块事件 │
│  PathExecutor 走位 │   │  PerceptionSnapshot: 分类聚合│
└───────────────────┘   └────────────────────────────┘
```

- **任务层**只编排，不实现单动作；**动作层**是单动作状态机（已被 M0/M1 验证）；
- **感知联动**：Task 构造时注入 `ScopeBuffer`（任务作用域），执行中查询
  掉落物位置（拾取定位）、目标状态（方块是否还在）；
- **决策接入点**：未来 LLM 决策 = 调用 `BotManager.assignTarget(bot, target)`，
  与测试工具同一条路径——接口不变，只换"谁产出 target"。

## 二、Task 生命周期

```
assignTarget(bot, target)
  → BotSession.assign(target)
      → 收尾上一个任务(clearTask: 清作用域 + 广播清除高亮)
      → 开启新作用域 scope.begin(target, 8)
      → 按目标类型实例化 Task:
          BLOCK  → MineTask(bot, pos, scope)
          ENTITY → (未实现, 下一步: AttackTask)
      → 广播高亮包 TargetPacket(S2C) → 客户端画透视线框
  → BotSession.tick() 逐 tick 驱动 task.tick()
      → DONE   : lastTaskResult="done"   + reportItems + clearTask
      → FAILED : lastTaskResult="failed:原因" + reportItems + clearTask
```

失败原因一览（MineTask 转自 BotMiner + 拾取阶段新增）：
`no_stand_pos / no_path / path_failed / line_of_sight_blocked / out_of_reach /
mine_timeout / underfoot_block / protected_area / protected_block / protected_tag /
collect_no_path / collect_path_failed / collect_timeout`

## 三、客户端测试效果（本阶段）

| 效果 | 实现 |
|---|---|
| 任务目标透视高亮 | `TargetPacket`(S2C) → `ClientTargetState` → `TargetOutlineRenderer` 在 `AFTER_LEVEL` 阶段画线框(关深度测试=透视) |
| 方块目标 | 亮绿色 AABB（固定） |
| 实体/掉落物目标 | 亮红色 AABB（实时跟实体，+0.1 膨胀） |

## 四、测试工具（本阶段）

| 工具 | 交互 | 行为 |
|---|---|---|
| `alice:target_selector`（贴图=钻石斧） | 右键方块 | 指派挖掘任务给 bot（无 bot 自动生成） |
| 钻石铲（原版） | 右键方块 | 接口扫描 `/alice scan` 的快捷版 |

物品注册走 `AliceItems`（DeferredRegister），**不套原版工具**——只引用贴图。

## 五、临时设定与已知限制

- **主手工具**：spawn 不再发钻石镐；MineTask 开始时直接替换主手为钻石镐，
  结束后不换回——等装备系统接管（临时设定，用户指定）。
- **背包**：ServerPlayer 自带完整 Inventory（36 格+盔甲+副手），
  拾取走原版 `ItemEntity.playerTouch` 入包逻辑，无需额外实现。
- **实体攻击**：`interactLivingEntity` 只提示未实现，AttackTask 下一步做。
- **JECh 依赖**：纯客户端模组，dedicated server 启动会崩——本地 headless
  验证需临时注释（已记 R14，后续处理）。
- **拾取判定**：按 UUID 追踪任务掉落物，实体位置与三维距离每 tick 刷新；终点为掉落物实际脚位格，
  进入 1.5 格后主动 `playerTouch`。只有超过 64 格才允许放弃，64 格内无路径/路径失败/超时均判任务失败；
  多件物品使用粘性目标避免反复切换。若下方近距离掉落物无路可走，可限量开凿“侧向斜下方一格”形成台阶后重规划；
  禁止挖脚下承重块、超过 1 格自由下落、安全区/基岩/黑曜石类清障。旧 `MineTask` 阶梯/猜 blocker 清障仅作历史基线；道路蓝图由独立数学拟合器处理液体 clearance 与支撑/净空。

## 六、道路数学模型与构建蓝图

道路预览与旧 MineTask 执行链完全分离。使用 `alice:road_planner` 右键选择两个端点，RoadPlan 将端点方块下方一格作为首尾支撑格，在三维体素空间拟合通路：

- 目标端点不被清理，目标方块位于最后一格支撑格上方，用于保护后续采集掉落物；
- 水/岩浆及其一格 clearance 膨胀区作为三维禁区；
- 路径主代价为体素长度，转弯加入很小的次级惩罚，近似“绳子贴着障碍表面绷紧”；
- 水平允许 4 邻和受侧格检查的 8 邻，对角连接必须保证两个正交侧格都可通过；拐角额外拓宽两格净空；
- 垂直变化不能使用纯竖直中心线移动，必须绑定水平移动，每一步高度差最多一格；水平投影不足时，搜索自动向外绕行增加坡道长度；
- 每个中心线支撑格包含一格支撑和两格玩家通行净空；高度变化的低侧额外向上扩一格；
- 虚空支撑格标记为 `SUPPORT_PLACE`，当前演示使用圆石；实体通道格标记为 `CLEAR`；
- `RoadBuilder` 按水平单元逐格处理，清理/搭建后等待 5 tick 检查沙砾、沙子等坠落风险，稳定后才进入下一单元。

旧的“失败后猜 blocker、再重跑 A*”和旧阶梯清障逻辑已归档，不参与道路蓝图计算。

## 七、下一步（建议顺序）

1. 实测：目标指定器右键矿石 → bot 挖 → 捡起 → 背包有矿 + 高亮全程可见
2. `AttackTask`（实体目标）：近战攻击状态机（走位 → 原版攻击包）
3. ~~决策层最小规则~~ 已落地：`/alice auto-mine <tag>`（AutoMineDecision），
   下一步: LLM 决策接入 / 多目标队列 / 路径代价排序
4. 高亮多目标（任务队列/多 bot 时颜色区分）
