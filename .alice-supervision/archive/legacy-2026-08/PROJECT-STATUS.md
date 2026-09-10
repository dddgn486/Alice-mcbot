# Alice 项目完整状态分析

> 🐋 **创建时间**: 2026-09-03
> 
> **最后更新**: 2026-09-03 23:30
> 
> **目的**: 系统记录项目所有模块的实现程度、已知问题、设计决策

---

## 📊 **项目概览**

### **基本信息**
- **总代码文件**: 130+ Java 文件
- **总代码量**: 约 2万+ 行
- **主要模块**: 24个包
- **开发阶段**: Alpha（核心功能可用，部分功能需优化）

### **架构层次**
```
Alice Mod
├─ Bot 系统（核心）- FakePlayer 实现
├─ 任务系统 - Task 接口 + 各种任务实现
├─ 寻路系统 - A* / Surface / Movement
├─ 物品系统 - 交互工具
├─ 客户端渲染 - HUD / 高亮
└─ 网络通信 - 客户端-服务端同步
```

---

## 🎯 **核心系统状态**

### **1. Bot 系统** (`alice.bot`)

#### **实现文件** (7个)
- ✅ `BotPlayer.java` - FakePlayer 核心实现
- ✅ `BotManager.java` - Bot 管理器
- ✅ `FakeConnection.java` - 假网络连接
- ✅ `BotWorldData.java` - Bot 世界数据持久化
- ✅ `BotController.java` - Bot 控制接口
- ✅ `BotControllerExample.java` - 示例控制器
- ✅ `BotSelftest.java` - 自测试套件
- ✅ `TaskExecutionRecord.java` - 任务执行记录

#### **实现程度**: **90%** ✅

**已实现功能**：
- ✅ FakePlayer 创建和生命周期管理
- ✅ 任务执行框架（Task 接口）
- ✅ Bot 位置同步到客户端
- ✅ Bot 装备同步（手持物品、装备）
- ✅ Bot 物理碰撞（可被推动）
- ✅ Bot 数据持久化（保存到世界数据）

**已知问题**：
- ⚠️ FakeConnection.send() 是 no-op，需要用 `level.broadcast()` 广播
- ⚠️ Bot 朝向同步需要手动处理（`setYRot` + `setYHeadRot`）
- ⚠️ Bot 实体包需要手动广播到真实玩家

**未实现功能**：
- 🔲 Bot AI 决策系统（AutoMineDecision 存在但未完整）
- 🔲 Bot 血量/饥饿度管理
- 🔲 Bot 伤害/死亡处理

**设计决策**：
- 使用 FakePlayer 而不是自定义 Entity（利用原版玩家逻辑）
- 手动广播包而不是依赖 FakeConnection（因为 FakeConnection.send() 无效）

---

### **2. 任务系统** (`alice.task`)

#### **实现文件** (10个核心 + 3个lumber)
- ✅ `Task.java` - 任务接口
- ✅ `TaskTarget.java` - 任务目标封装
- ✅ `MineTask.java` - 挖掘任务 **（带清障逻辑）**
- ✅ `PlaceTask.java` - 放置方块任务
- ✅ `FollowTask.java` - 跟随任务
- ✅ `DropCollectionTask.java` - 掉落物收集任务 **（复杂，有问题）**
- ✅ `TransferTask.java` - 物品传输任务
- ✅ `RoadBuildTask.java` - 道路建造任务
- ✅ `SoftPathMineTask.java` - 软路径挖掘任务
- ✅ `SoftPathProbeTask.java` - 软路径探测任务
- ✅ `SoftMoveProbeTask.java` - 软移动探测任务
- ✅ `RegionLumberTask.java` - 区域伐木任务 **（新）**
- ✅ `ContinuousLumberTask.java` - 持续伐木任务 **（新）**
- ⚠️ `TreeDetector.java` - 树木检测器 **（新）**
- ⚠️ `Tree.java` - 树木数据结构 **（新）**
- ⚠️ `TreeType.java` - 树木类型枚举 **（新）**

#### **实现程度**: **70%** ⚠️

**已实现任务**：
- ✅ **挖掘** (MineTask) - 核心任务，支持清障
- ✅ **放置** (PlaceTask) - 基础任务
- ✅ **跟随** (FollowTask) - 基础任务
- ✅ **传输** (TransferTask) - 复杂任务，涉及容器交互
- ✅ **道路建造** (RoadBuildTask) - 复杂任务，规划+建造
- ✅ **区域伐木** (RegionLumberTask) - 刚实现，支持清树叶
- ✅ **持续伐木** (ContinuousLumberTask) - 刚实现，有黑名单机制

**已知问题**：
- ⚠️ **DropCollectionTask 是"屎山"** - 你明确说过复杂且有问题
- ⚠️ **MineTask 的清障逻辑** - 可用但不完美，只清直线上的遮挡
- ⚠️ **RegionLumberTask 清树叶** - 刚做的补丁，最多清3层
- ⚠️ **砍伐顺序问题** - 目前从下往上，理想应该"留底部、站木桩上砍"

**未实现任务**：
- 🔲 **合成任务** - 使用工作台
- 🔲 **熔炉任务** - 烧制物品
- 🔲 **战斗任务** - 攻击实体
- 🔲 **种植任务** - 农业自动化
- 🔲 **钓鱼任务** - 自动钓鱼

**设计决策**：
- 任务使用状态机模式（Phase 枚举 + handleXXX() 方法）
- 任务返回 Status (RUNNING / DONE / FAILED)
- 清障逻辑在任务层（MineTask），不在 BotMiner 层
- **重要**：RegionLumberTask 直接用 BotMiner，跳过了 MineTask 的清障

---

### **3. 寻路系统** (`alice.pathing`)

#### **实现文件** (16个核心 + 8个movement)
- ✅ `PathPlanner.java` - 路径规划接口
- ✅ `PathPlanners.java` - 路径规划器工厂
- ✅ `AStarPathfinder.java` - A* 寻路算法 **（核心）**
- ✅ `SurfacePathfinder.java` - 表面寻路 **（当前主要使用）**
- ✅ `PathExecutor.java` - 路径执行器
- ✅ `MovementPathExecutor.java` - 移动路径执行器
- ✅ `Goal.java` - 寻路目标
- ✅ `PathNode.java` - 路径节点
- ✅ `OpenSet.java` - 开放集（A*）
- ✅ `TunnelPlanner.java` - 隧道规划器
- ✅ `MovementMode.java` - 移动模式（HARD_PATH / SOFT_SURFACE）
- ✅ `MovementType.java` - 移动类型枚举
- ✅ `SoftMovementPrimitive.java` - 软移动原语
- ⚠️ `WalkMovement.java` - 行走移动
- ⚠️ `PillarMovement.java` - 垫方块移动
- ⚠️ `DescendMovement.java` - 下降移动
- ⚠️ `BreakAndWalkMovement.java` - 破坏并行走
- ... 及各自的 Provider

#### **实现程度**: **80%** ✅

**已实现功能**：
- ✅ A* 寻路算法（适用于挖矿、隧道）
- ✅ 表面寻路（适用于地表移动，不破坏方块）
- ✅ 移动原语系统（walk / pillar / descend / breakAndWalk）
- ✅ 路径执行器（沿路径移动）
- ✅ 寻路超时机制（100ms，防止卡顿）

**已知问题**：
- ⚠️ **寻路超时** - 复杂地形 100ms 内找不到路径 → `stand_search_limit`
- ⚠️ **A* 节点爆炸** - 复杂地形扩展 2万+ 节点 → 超时
- ⚠️ **伐木寻路问题** - 高处原木（Y=75+）从低处（Y=69）找不到路径

**未实现功能**：
- 🔲 动态避障（寻路后地形变化的重规划）
- 🔲 爬梯子移动
- 🔲 游泳移动（水下寻路）
- 🔲 飞行移动（创造模式）

**设计决策**：
- **HARD_PATH**: 挖掘通道到达目标（MineTask）
- **SOFT_SURFACE**: 只在地表移动，不破坏方块（FollowTask）
- 寻路超时而不是无限等待（防止卡顿）
- **重要冲突**：超时机制 vs 复杂地形，导致高处原木砍不到

---

### **4. 挖掘系统** (`alice.action`)

#### **实现文件** (1个核心)
- ✅ `BotMiner.java` - 挖掘执行器 **（核心，1400+ 行）**

#### **实现程度**: **95%** ✅

**已实现功能**：
- ✅ 选择可达站位（目标周围 4x6x4 范围）
- ✅ A* 寻路到站位
- ✅ 视线检查（raycast，防止隔空挖）
- ✅ 距离检查（4.5 格内）
- ✅ 原版挖掘协议（破坏进度、挖掘动画）
- ✅ 朝向目标（挖掘时持续朝向）
- ✅ 挖掘超时机制（200 ticks）
- ✅ 安全检查（BlockBreakSafety / FluidRiskPolicy）
- ✅ **手动广播方块更新**（刚加的补丁）

**已知问题**：
- ⚠️ **视线检查使用 COLLIDER 模式** - 树叶没有碰撞箱，被认为"视线清晰"
- ⚠️ **BotMiner 本身不清障** - 清障逻辑在 MineTask 里
- ⚠️ **方块破坏后需手动广播** - `destroyBlock()` 不自动广播给客户端

**未实现功能**：
- 🔲 支持附魔（时运、精准采集）- 原版机制应该自动生效
- 🔲 工具耐久度管理
- 🔲 自动换工具（合适的工具类型）

**设计决策**：
- BotMiner 是"纯挖掘执行器"，不处理清障
- 站位优先级：目标下方 > 同平面 > 目标上方（挖矿场景）
- 视线无遮挡优先，其次距离优先
- **重要限制**：最多尝试 8 个候选站位，防止 A* 风暴

---

### **5. 物品系统** (`alice.item`)

#### **实现文件** (12个工具)
- ✅ `AliceItems.java` - 物品注册中心
- ✅ `BotRemoteControl.java` - Bot 遥控器（右键选 Bot + 命令）
- ✅ `TargetSelector.java` - 目标选择器（挖掘）
- ✅ `TransferEndpointSelector.java` - 传输端点选择器
- ✅ `RoadPlannerItem.java` - 道路规划器
- ✅ `SoftMoveSelector.java` - 软移动选择器
- ✅ `SoftPathProbeSelector.java` - 软路径探测器
- ✅ `LumberPlanner.java` - 伐木规划器（铁斧，三步流程）**（新）**
- ✅ `AutoLumberer.java` - 自动伐木器（钻石斧，持续砍树）**（新）**

#### **实现程度**: **85%** ✅

**已实现工具**：
- ✅ **Bot 遥控器** - 召唤、命令、查看状态
- ✅ **目标选择器** - 选择挖掘目标
- ✅ **伐木规划器** - 三步流程（起点→终点→确认）
- ✅ **自动伐木器** - Shift+右键启动持续砍树
- ✅ **道路规划器** - 规划道路
- ✅ **传输端点选择器** - 选择箱子传输

**已知问题**：
- ⚠️ **物品模型可能缺失** - 部分工具没有纹理（显示为紫黑方块）
- ⚠️ **工具 HUD 提示不清晰** - 用户不知道怎么操作

**未实现工具**：
- 🔲 Bot 召唤卡（一键召唤指定 Bot）
- 🔲 任务卡（预设任务，右键激活）
- 🔲 Bot 配置器（GUI 配置 Bot 行为）

**设计决策**：
- 工具通过右键交互触发
- 伐木规划器用"三步流程"而不是 GUI（简单但不直观）
- 自动伐木器用 Shift+右键启动（防止误触）

---

### **6. GUI 系统** (`alice.gui` + `alice.client.gui`)

#### **实现文件** (7个)
- ✅ `BotInventoryMenu.java` - Bot 背包菜单（服务端）
- ✅ `BotInventoryService.java` - Bot 背包服务
- ✅ `BotInventorySnapshot.java` - Bot 背包快照
- ✅ `ModMenuTypes.java` - 菜单类型注册
- ✅ `BotInventoryScreen.java` - Bot 背包屏幕（客户端）**（可能废弃）**
- ✅ `BotInventoryMenuScreen.java` - Bot 背包菜单屏幕（客户端）
- ✅ `ClientMenuScreens.java` - 客户端菜单注册

#### **实现程度**: **90%** ✅

**已实现功能**：
- ✅ Bot 背包 GUI（玩家可以打开 Bot 的背包）
- ✅ 容器协议（Container Menu Protocol）
- ✅ 物品拖放、快捷移动（quickMoveStack）
- ✅ 客户端-服务端同步

**已知问题**：
- ⚠️ **容器协议复杂** - `.alice-supervision/container-quick-move-explained.md` 572 行详解
- ⚠️ **可能有两套 GUI** - `BotInventoryScreen` vs `BotInventoryMenuScreen`，不确定哪个在用

**未实现功能**：
- 🔲 Bot 状态配置 GUI
- 🔲 任务管理 GUI（查看任务列表、取消任务）
- 🔲 Bot 装备 GUI（查看/更换装备）

**设计决策**：
- 使用 Minecraft 原版容器协议而不是自定义网络包
- GUI 通过 BotRemoteControl 右键 Bot 打开

---

### **7. 客户端渲染** (`alice.client.render`)

#### **实现文件** (2个)
- ✅ `BotTaskHudRenderer.java` - Bot 任务状态 HUD **（新）**
- ✅ `TargetOutlineRenderer.java` - 目标方块高亮边框

#### **实现程度**: **60%** ⚠️

**已实现功能**：
- ✅ Bot 任务状态 HUD（右上角显示 Bot 名字 + 任务类型）
- ✅ 目标方块高亮边框（红色线框）

**已知问题**：
- ⚠️ **HUD 显示简陋** - 只有文字，没有图标
- ⚠️ **区域高亮边框未实现** - 伐木区域没有边框显示

**未实现功能**：
- 🔲 伐木区域边框渲染
- 🔲 Bot 路径可视化（显示 Bot 寻路路径）
- 🔲 Bot 工作范围指示（半径圈）
- 🔲 Bot 血量/状态条（头顶显示）

**设计决策**：
- HUD 使用 Forge 的 `RenderGuiOverlayEvent.Post` 事件
- 边框使用自定义 RenderType（关闭深度测试，透视效果）

---

### **8. 网络通信** (`alice.network`)

#### **实现文件** (5个)
- ✅ `AliceNetwork.java` - 网络通道注册
- ✅ `BotInputPacket.java` - Bot 输入包（客户端→服务端）
- ✅ `BotInventoryPacket.java` - Bot 背包同步包
- ✅ `BotInventoryActionPacket.java` - Bot 背包操作包
- ✅ `RoadPlanPacket.java` - 道路规划包
- ✅ `TargetPacket.java` - 目标同步包

#### **实现程度**: **80%** ✅

**已实现功能**：
- ✅ 客户端输入传到服务端（移动、跳跃等）
- ✅ Bot 背包同步到客户端
- ✅ 道路规划数据同步
- ✅ 目标方块同步（高亮）

**已知问题**：
- ⚠️ **方块更新不自动广播** - 需要手动调用 `level.sendBlockUpdated()`
- ⚠️ **Bot 实体包不自动广播** - 需要手动调用 `level.broadcast()`

**未实现功能**：
- 🔲 Bot 状态同步包（血量、饥饿度）
- 🔲 任务进度同步包（详细进度）

**设计决策**：
- 使用 Forge 的 SimpleChannel
- 手动广播包而不是依赖 FakeConnection
- **重要**：FakeConnection.send() 是 no-op，必须用 broadcast

---

### **9. 传输系统** (`alice.transfer`)

#### **实现文件** (12个)
- ✅ `TransferTask.java` - 传输任务
- ✅ `TransferRequest.java` - 传输请求
- ✅ `ChestBotTransferPrimitive.java` - 箱子-Bot 传输原语
- ✅ `ChestEndpointRef.java` - 箱子端点引用
- ✅ `InventoryObservation.java` - 背包观察
- ✅ `TransferLedgerData.java` - 传输账本数据
- ✅ `TransferSelectionData.java` - 传输选择数据
- ✅ `TransferSelectionLifecycle.java` - 传输选择生命周期
- ✅ `TransferSelectionSubmission.java` - 传输选择提交
- ✅ `TransferCodes.java` - 传输状态码
- ✅ `CapacityPreflight.java` - 容量预检查
- ... 及 Fixture 测试文件

#### **实现程度**: **85%** ✅

**已实现功能**：
- ✅ 箱子→Bot 传输
- ✅ Bot→箱子 传输
- ✅ 容量预检查（防止塞不下）
- ✅ 传输账本（记录传输历史）
- ✅ 传输选择流程（选起点→选终点→提交）

**已知问题**：
- ⚠️ **传输系统复杂** - 多个类协作，理解成本高

**未实现功能**：
- 🔲 Bot→Bot 传输
- 🔲 熔炉传输（自动补充燃料）
- 🔲 条件传输（只传输特定物品）

**设计决策**：
- 使用"端点引用"而不是直接操作容器
- 传输任务包含"走到容器→打开→移动物品→关闭"流程

---

### **10. 保护系统** (`alice.protection`)

#### **实现文件** (2个)
- ✅ `BlockBreakSafety.java` - 方块破坏安全检查
- ✅ `SafeZoneData.java` - 安全区域数据

#### **实现程度**: **70%** ⚠️

**已实现功能**：
- ✅ 禁止破坏基岩
- ✅ 禁止破坏出生点附近方块
- ✅ 禁止破坏脚下支撑方块
- ✅ 安全区域定义（玩家可设置禁止 Bot 操作的区域）

**已知问题**：
- ⚠️ **安全区域功能可能未完全接入** - 代码存在但不确定是否生效

**未实现功能**：
- 🔲 领地保护集成（与其他 Mod 的领地系统集成）
- 🔲 权限系统（不同玩家对 Bot 的权限）

**设计决策**：
- 安全检查在任务层（Task）和挖掘层（BotMiner）都有
- 拒绝操作返回原因字符串而不是抛异常

---

### **11. 生存系统** (`alice.survival`)

#### **实现文件** (4个)
- ✅ `SurvivalSystem.java` - 生存系统核心
- ✅ `FluidRiskPolicy.java` - 流体风险策略
- ✅ `HazardState.java` - 危险状态
- ✅ `HazardType.java` - 危险类型枚举

#### **实现程度**: **60%** ⚠️

**已实现功能**：
- ✅ 岩浆风险检测（禁止挖岩浆附近方块）
- ✅ 危险状态检测（Bot 是否处于危险中）

**已知问题**：
- ⚠️ **生存系统接入不完整** - 部分任务可能没有调用

**未实现功能**：
- 🔲 血量管理（自动回血）
- 🔲 饥饿度管理（自动吃饭）
- 🔲 窒息检测（墙里、水下）
- 🔲 掉落伤害预防

**设计决策**：
- 生存检查在每个 Task.tick() 开头调用
- 危险状态会中断任务（返回 FAILED）

---

### **12. 道路系统** (`alice.road`)

#### **实现文件** (4个)
- ✅ `RoadBuildTask.java` - 道路建造任务
- ✅ `RoadPlan.java` - 道路规划
- ✅ `RoadBuilder.java` - 道路建造器
- ✅ `ContinuousRoadCurve.java` - 连续道路曲线
- ✅ `RoadObstaclePolicy.java` - 道路障碍策略

#### **实现程度**: **75%** ✅

**已实现功能**：
- ✅ 道路规划（起点→终点，自动计算路径）
- ✅ 道路建造（放置方块）
- ✅ 障碍处理（清理障碍物）

**已知问题**：
- ⚠️ **道路功能可能很久没测试** - 不确定当前状态

**未实现功能**：
- 🔲 桥梁建造（跨水、跨峡谷）
- 🔲 隧道建造（穿山）
- 🔲 楼梯建造（上下坡）

**设计决策**：
- 道路使用 RoadPlan 数据结构（独立于寻路系统）

---

### **13. 感知系统** (`alice.perception`)

#### **实现文件** (5个)
- ✅ `PerceptionSnapshot.java` - 感知快照
- ✅ `PerceptionProfile.java` - 感知配置
- ✅ `PerceptionCategory.java` - 感知类别
- ✅ `PerceptionClassifier.java` - 感知分类器
- ✅ `ScopeBuffer.java` - 作用域缓冲区

#### **实现程度**: **50%** ⚠️

**已实现功能**：
- ✅ 感知快照（Bot 周围方块/实体的快照）
- ✅ 作用域缓冲区（定义 Bot 工作范围）

**已知问题**：
- ⚠️ **感知系统可能是废案** - 代码存在但不确定是否在用

**未实现功能**：
- 🔲 实体感知（检测附近怪物、动物、玩家）
- 🔲 声音感知（检测爆炸、脚步声）
- 🔲 视觉感知（光线、可见性）

**设计决策**：
- 感知使用快照模式而不是实时查询

---

### **14. 能力系统** (`alice.capability`)

#### **实现文件** (3个)
- ✅ `InterfaceScanner.java` - 接口扫描器
- ✅ `InterfaceSnapshot.java` - 接口快照
- ✅ `ObservationStatus.java` - 观察状态

#### **实现程度**: **40%** ⚠️

**已实现功能**：
- ✅ 接口扫描（扫描方块的容器接口）

**已知问题**：
- ⚠️ **能力系统可能是废案** - 不确定用途

**未实现功能**：
- 🔲 动态能力注册
- 🔲 能力冷却管理

---

### **15. 伐木系统** (`alice.task.lumber`) **🆕 刚实现**

#### **实现文件** (5个)
- ✅ `RegionLumberTask.java` - 区域伐木任务 **（刚实现 + 清树叶补丁）**
- ✅ `ContinuousLumberTask.java` - 持续伐木任务 **（刚实现 + 黑名单）**
- ✅ `TreeDetector.java` - 树木检测器
- ✅ `Tree.java` - 树木数据结构
- ✅ `TreeType.java` - 树木类型枚举

#### **实现程度**: **75%** ⚠️

**已实现功能**：
- ✅ 树木检测（BFS 追踪原木，查找树叶）
- ✅ 树木类型识别（橡树、白桦、云杉等）
- ✅ 区域伐木（三步流程：起点→终点→确认）
- ✅ 持续伐木（Shift+右键启动，自动找树砍）
- ✅ 黑名单机制（跳过无效树，避免死循环）
- ✅ 清树叶补丁（最多清3层树叶遮挡）
- ✅ 方块同步补丁（手动广播方块更新）

**已知问题**：
- ⚠️ **砍伐顺序不合理** - 目前从下往上，理想应该"留底部、站木桩上砍"
- ⚠️ **高处原木够不到** - 寻路超时导致跳过高处原木
- ⚠️ **Bot 绕树转圈** - 树叶遮挡时找不到站位
- ⚠️ **清树叶是临时补丁** - 不是架构层解决方案

**未实现功能**：
- 🔲 **留底部原木，站木桩上砍** - 你明确要求的砍伐策略
- 🔲 **爬树逻辑** - Bot 自动跳到树干上
- 🔲 **砍伐顺序优化** - 从上往下砍，或者智能选择顺序
- 🔲 **树叶自然掉落检测** - 砍完原木后等待树叶消失

**设计决策**：
- 树木检测用 BFS 而不是递归（防止栈溢出）
- 区域伐木扫描整个区域，按树为单位砍
- 持续伐木黑名单在收集阶段清空（避免永久跳过）
- **重要权衡**：清树叶补丁 vs 重新设计砍伐策略

---

## ⚠️ **已知"屎山"和废案**

### **明确的屎山**：
1. ❌ `DropCollectionTask.java` - 你说过是屎山，复杂且有问题
2. ❌ 老工作流的监督协议 - `.alice-supervision/` 里很多文档是废弃的

### **可能的废案**（代码存在但不确定是否在用）：
1. ❓ `PerceptionSnapshot` / `PerceptionCategory` - 感知系统可能没接入
2. ❓ `InterfaceScanner` - 能力系统可能没接入
3. ❓ `BotInventoryScreen.java` - 可能被 `BotInventoryMenuScreen` 替代了
4. ❓ `AutoMineDecision.java` - AI 决策系统可能没完成
5. ❓ 部分 Fixture 和 Test 文件 - 测试代码可能过时

### **需要重构的部分**：
1. ⚠️ **伐木砍伐顺序** - 从下往上 → 留底部、站木桩上砍
2. ⚠️ **DropCollectionTask** - 需要简化或重写
3. ⚠️ **BotMiner 视线检查** - COLLIDER 模式 → 应该有选项用 OUTLINE
4. ⚠️ **清障逻辑** - 应该在 BotMiner 层还是 Task 层？需要统一

---

## 📝 **设计决策记录**

### **为什么用 FakePlayer 而不是自定义 Entity？**
- ✅ 利用原版玩家逻辑（碰撞、物理、交互）
- ✅ 简化实现（不需要重新实现玩家行为）
- ❌ 缺点：FakeConnection 是 no-op，需要手动广播

### **为什么清障逻辑在 MineTask 而不是 BotMiner？**
- ✅ BotMiner 是纯挖掘执行器（单一职责）
- ✅ 清障是任务策略，不同任务有不同清障需求
- ❌ 缺点：RegionLumberTask 直接用 BotMiner，跳过了清障

### **为什么寻路有超时机制？**
- ✅ 防止复杂地形卡住游戏（A* 节点爆炸）
- ❌ 缺点：高处原木够不到，任务失败

### **为什么伐木从下往上砍？**
- ✅ 注释说"避免浮空原木"
- ❌ 实际问题：高处被树叶遮挡，Bot 找不到站位
- ❌ 更好的方案：从上往下砍，树叶自然掉落

### **为什么手动广播方块更新？**
- ✅ `destroyBlock()` 不自动广播给客户端（FakePlayer 特性）
- ❌ 缺点：需要在每个破坏方块的地方都加广播代码

---

## 🚨 **当前最紧急的问题**

### **P0（阻塞功能）**：
1. ❌ **伐木砍伐策略重新设计** - 目前的从下往上不可行
2. ❌ **寻路超时 vs 复杂地形** - 高处原木够不到

### **P1（影响体验）**：
3. ⚠️ **清树叶是临时补丁** - 需要架构层解决方案
4. ⚠️ **DropCollectionTask 是屎山** - 需要简化

### **P2（优化）**：
5. 🔲 **区域高亮边框** - 视觉反馈
6. 🔲 **HUD 美化** - 图标、颜色
7. 🔲 **工具提示优化** - 用户不知道怎么用

---

## 🎯 **下一步计划**

### **短期（测试当前补丁）**：
1. 测试清树叶补丁是否生效
2. 测试 stand_search_limit 跳过是否生效
3. 测试方块同步补丁是否生效

### **中期（重新设计砍伐策略）**：
1. 设计"留底部、站木桩上砍"的砍伐流程
2. 实现爬树逻辑（Bot 跳到树干上）
3. 优化砍伐顺序（从上往下，或智能选择）

### **长期（架构优化）**：
1. 统一清障逻辑（BotMiner 还是 Task？）
2. 重构 DropCollectionTask
3. 寻路系统优化（动态超时、增量搜索）

---

## 📊 **总体评估**

### **项目成熟度**: **Alpha** ⚠️

**可用功能**：
- ✅ Bot 基础系统（创建、管理、持久化）
- ✅ 挖掘任务（带清障）
- ✅ 传输任务（箱子交互）
- ✅ 区域伐木（刚实现，有问题）
- ✅ 持续伐木（刚实现，有问题）

**主要问题**：
- ⚠️ 伐木砍伐策略不合理
- ⚠️ 寻路系统在复杂地形表现差
- ⚠️ 部分功能是临时补丁，缺乏架构设计
- ⚠️ 存在"屎山"代码（DropCollectionTask）
- ⚠️ 部分功能可能是废案，代码混乱

**项目优势**：
- ✅ 核心系统稳定（Bot / BotMiner）
- ✅ 寻路系统功能完整（A* / Surface）
- ✅ 任务系统扩展性好（Task 接口）
- ✅ 网络通信正常（Forge 网络包）

**项目风险**：
- ⚠️ 避免"到处打补丁"（老工作流的教训）
- ⚠️ 需要区分"废案"和"可用代码"
- ⚠️ 需要完整测试覆盖

---

**备忘录版本**: v3.0  
**最后更新**: 2026-09-03 23:30  
**状态**: 伐木系统补丁完成，等待测试

🐋💙
