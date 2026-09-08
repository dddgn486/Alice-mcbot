# 批 3 审查报告：road 模块（道路计划/构建/连续曲线——R# 决策核对）

- **任务**：`06117a13-d33e-4ab5-842f-9ada497ad1fa`（批 3 / road 模块只读审查）
- **审查基线**：`6c2b461`（HEAD）
- **日期**：2026-08-25
- **依据**：`full-review-plan-20260825.md`（§1.2 批 3/§2/§4/§5）、`docs/HANDOVER.md`（§三：选择链/连续曲线/已移除逻辑/Builder 注意事项；§八 R17/R18/R19/R21/R22/R23）、批 1 task（RoadBuildTask R24）、批 3 item（RoadPlannerItem）、批 2 client（RoadPlanPacket→ClientRoadState 渲染盲区）。
- **性质**：只读审查。未修改任何文件；未运行测试；未连接工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（road/ 4 文件）、HANDOVER §三/§八、grep 复活检查、交叉核对。

---

## 2.1 定位与文件清单

**模块定位**（类注释原意）：road = "数学道路预览的服务端状态：路径中心线 + 两格净空 + 一格支撑。独立可视化/搭路蓝图，不调用旧 MineTask 清障逻辑"（RoadPlan.java:18-21）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `RoadPlan.java` | 计划 | 静态单例（R21）+ buildUnits 选择链（R17）+ 中心线/净空/支撑体素化 + 螺旋 |
| `ContinuousRoadCurve.java` | 曲线 | 连续曲线候选几何层（直线 + 两侧二次 Bezier，法向偏移 0-24 步 2），不读世界，体素化中心线 |
| `RoadBuilder.java` | 构建 | 玩家版逐单元施工（先清净空后铺支撑 + 5 tick 稳定检测，沙砾 3 次停止） |
| `RoadObstaclePolicy.java` | 策略 | 统一禁区策略（流体 3D 一格膨胀；安全区/负硬度/道路标签/高代价只禁本体；`#alice:road_forbidden` 标签扩展） |

**注册方式**：RoadBuilder 经 `@SubscribeEvent ServerTickEvent.END`（RoadBuilder.java:52-55）；RoadPlan 静态单例 `INSTANCE`（:33,45-47）；RoadObstaclePolicy 静态工具；ContinuousRoadCurve 静态工具。

---

## 2.2 边界归属

- **归属线**：road = **独立道路蓝图线**（RoadPlannerItem 入口 → RoadPlan 数学规划 → RoadPlanPacket 预览/ClientRoadState 渲染 → RoadBuilder 玩家施工 / RoadBuildTask bot 施工）。不参与矿链（HANDOVER §:254 "手动 RoadPlan/RoadBuilder/RoadBuildTask 保持为独立道路蓝图功能，当前不参与挖矿任务"）。
- **相邻边界**：
  - item（批 3）：RoadPlannerItem 右键选端点/Shift 清空（RoadPlannerItem.java:24-35）→ RoadPlan.select（R21 单例绑定）；
  - task（批 1）：RoadBuildTask R24（终点缓冲单元→MineTask）已核对；
  - client（批 2）：RoadPlanPacket → ClientRoadState 链路 PASS，渲染无客户端矩阵（盲区）；
  - 保护：RoadBuilder 清障走 `BlockBreakSafety.clearingRefusal`（RoadBuilder.java:103,148）；RoadObstaclePolicy 引 `SafeZoneData`（:42）。

---

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| R1 | 连续曲线候选：直线 + 两侧 Bezier（法向偏移 0-24 步 2） | `offsets.add(0.0)` + `2.0..24.0 步长 2` 正负（ContinuousRoadCurve.java:36-41） | 静态核对 | **一致**（HANDOVER §3.1"法向偏移 0~24 步 2"） |
| R2 | 候选需具备 ≥ 垂直差的水平弧长 | `totalLength + 1e-6 < verticalDistance → 空`（:71-74） | 静态核对 | **一致**（每步最多一格高差保证） |
| R3 | 逐格补齐水平折线（4/8 邻格 supercover） | `appendGridSegment`（:120-131） | 静态核对 | **一致**（HANDOVER §3.2） |
| R4 | 单候选内禁止重复 (x,z) 投影 | `projections.add(x+":"+z)` 重复即返回空（:112-114） | 静态核对 | **一致**（内部防重复，见 R22 复活检查） |
| R5 | 每步 dx/dz≤1、|dy|≤1、起终点必须等于 start/goal | :90-92 + :104-111 | 静态核对 | **一致** |
| R6 | RoadBuilder 先清净空后铺支撑 | buildUnit 顺序（RoadBuilder.java:100-112） | 静态核对 | **一致**（HANDOVER §3.4） |
| R7 | 沙砾 3 次不稳定即停止 | `MAX_STABILITY_RETRIES = 3`（:30,68-70） | 静态核对 | **一致**（§3.4"沙砾 3 次不稳定即停止"） |
| R8 | 检测/清理扫描范围一致 | `hasFallingMaterial`/`removeFallingMaterial` 同 `support ±1 × headroom+6` 柱体（:115-158） | 静态核对 | **一致**（§3.4 明示） |
| R9 | 障碍策略：流体含模组 3D 一格膨胀 | `forbidsCorridor`（RoadObstaclePolicy.java:24-38） | 静态核对 | **一致**（注释 :15-16） |
| R10 | 螺旋 2×2 投影 + 三格净空（R18/R19） | `spiralCompensationRoute` 枚举 4 相出口 + `forbidsCorridor(level, current, 3)`（RoadPlan.java:312,339）+ 末端水平缓冲（:344-346）+ headroom 3（:145-148） | 静态核对 | **一致**（R18/R19） |

---

## 2.4 已知坑与 R# 对照（本模块核心）

| R# | 内容 | 当前代码是否遵守 | 核查点证据 |
|---|---|---|---|
| R17 | 普通弯曲优先；`verticalDistance > 0` 就试螺旋（曲线候选失败后）；**不要改回旧阈值 vertical > horizontal + 4** | ✅ **遵守（代码是演进后的最新语义）** | buildUnits 选择链（RoadPlan.java:108-136）：曲线 → A* 混合比较（:121-125）→ 仅曲线（:126-128）→ 仅 A*（:129-132）→ **`verticalDistance > 0`（:133）→ 螺旋**；SPIRAL_EXTRA=4 仅作日志字段（:137），**非触发阈值**——旧阈值未复活。**注意：与 HANDOVER §3.1 文档顺序（曲线→螺旋→A*）不同**——代码是"曲线 → A* → 螺旋"（混合寻路比较是演进后新增），见 D1 |
| R18 | 螺旋入口外一格同样扩到三格净空 | ✅ **遵守** | `headroom = (isSpiral \|\| nextIsSpiral) ? 3 : 2`（RoadPlan.java:143-146）+ 螺旋步 `forbidsCorridor(current, 3)`（:339） |
| R19 | 螺旋出口→目标必须经过一格水平缓冲，且不侵入螺旋 2×2 投影 | ✅ **遵守** | 末端 buffer 一格（:344-346）+ `terminalSegmentClear`/`spiralFootprint`（:350-352） |
| R21 | RoadPlan 静态单例绑定 ServerLevel；切维度/重进世界会重置 | ✅ **遵守** | `INSTANCE`（:33）；`select` 中 `!selected \|\| this.level != level → 重置 first/second/cells/units 重新选择`（:65-73）——**切维度时 level 不同 → 自动重置**；`reset()`（:49-57） |
| R22 | 不要复活"拒绝重复水平投影"的临时逻辑（`isAcceptableNormalRoute`） | ✅ **遵守（未复活）** | grep `isAcceptableNormalRoute` 无命中；**ContinuousRoadCurve.java:112 的 `projections.add` 是单候选内部防重复（候选内曲线不自交），非跨路线拒绝——与 R22 所指的"拒绝 A* 路线重复投影"是不同机制，不构成复活**（D2 待监督员确认语义边界） |
| R23 | 不要复活"支撑格与其他单元净空重叠即失败"（`hasUnambiguousCellOwnership`） | ✅ **遵守（未复活）** | grep `hasUnambiguousCellOwnership` 无命中；代码注释明确"不把集合归属当成搜索失败条件"（RoadPlan.java:290-291:293-294,297）——**复活检查通过** |

**边界漂移待监督员判定**：
- **D1（文档顺序 vs 代码顺序）**：HANDOVER §3.1 文档写"曲线 → 螺旋 → A*"，代码（RoadPlan.java:121-135）是"曲线 ⥢ A* 混合比较 → 螺旋（前两者都空时才触发）"。**代码比文档更新**（混合寻路比较 + A* 后备先于螺旋）；R17 的核心语义（普通路径优先 + verticalDistance>0 试螺旋 + 不回旧阈值）**代码遵守**；**需监督员确认是否更新 HANDOVER §3.1 文档顺序**（文档维护事项，非功能问题）。
- **D2（R22 语义边界）**：ContinuousRoadCurve:112 的"单候选禁止重复 (x,z) 投影" vs R22 的"拒绝跨路线重复投影"——`isAcceptableNormalRoute` 已删，当前 projections 检查作用于**单个 Bezier 候选内部的体素化**（防止曲线自交导致断步），与 R22 禁止的"把 A* 路线打成 no_walkable_route"是不同机制；**当前实现不会把合法高差路线打成 no_walkable_route**（R22 目标达成）。待监督员确认文档表述（§3.2 "禁止重复 (x,z) 投影"与 §3.3 R22 的区分已存在，代码一致）。

---

## 2.5 Dual-View：服务端可证 vs 需客户端实测

**服务端可证（fixture/日志可断言）**：
- 选择链输出/路线/单元（`道路路线选择: mode=normal\|spiral ...` 日志，RoadPlan.java:136-141）；
- 曲线候选 valid 序列（`连续道路候选 i/n` 日志——HANDOVER §3.1 诊断定位法）；
- RoadBuilder 单元推进/稳定检测（TickEvent.END 驱动 + waitTicks）；
- R17/R18/R19/R21/R22/R23 全部静态可证（上述行号）。

**需客户端实测（Windows）**：
- 道路渲染视觉效果（ClientRoadState → 渲染——**client 审查已标盲区：无客户端矩阵**）；
- RoadBuildTask 施工动画（HANDOVER 用户强制"强行构建动画"——批 1 task 已记录需客户端验证）；
- RoadPlannerItem 右键选点/Shift 清空的交互手感。

> **必须写明**：服务端 PASS ≠ 客户端正确。road 的数学规划/选择链/施工逻辑服务端可证（日志/状态机），但**道路视觉效果与施工动画只能客户端验收**；当前"道路专用回归测试（平面/高差/螺旋正反/液体/端点/沙砾）仍未纳入 selftest"（HANDOVER §:267）——**服务端回归缺口已记录**。

---

## 2.6 证据等级核查

| 项 | 现有证据 | 等级 | 缺口 |
|---|---|---|---|
| 选择链/曲线/螺旋代码语义 | 本审查静态核对（行号如上） | 静态核对 | 无运行断言 |
| 道路专用回归（平面/高差/螺旋/液体/端点/沙砾） | HANDOVER §:267 记录"仍未纳入 selftest" | **无** | 服务端回归缺口（明确记录） |
| 道路渲染客户端矩阵 | client 审查：无客户端记录 | **无（盲区）** | 渲染视觉效果 |
| RoadBuildTask 施工动画 | 批 1 task：无专项客户端记录 | 无 | 施工动画 |
| RoadPlannerItem 入口 | 批 3 item：无专项记录 | 静态核对 | 交互手感 |

---

## 2.7 风险等级判定

| 模块/问题 | 等级 | 触发理由 |
|---|---|---|
| RoadPlan 选择链/ContinuousRoadCurve/RoadObstaclePolicy | **D 维持** | R17/R18/R19/R21/R22/R23 全部遵守；无复活临时逻辑 |
| RoadBuilder（玩家版） | **D 维持** | §3.4 注意事项全部遵守（先清后铺/同范围扫描/沙砾 3 次） |
| 道路专用回归未入 selftest | **A 补测试** | HANDOVER §:267 明确记录缺口——补回归（平面/高差/螺旋正反/液体/端点/沙砾）可获得服务端证据 |
| 道路渲染/施工动画客户端矩阵 | **A 补测试（客户端，窗口期）** | 盲区（client 审查）；需 Windows 验收 |
| D1（文档顺序 vs 代码顺序） | **D 维持（文档维护）** | 功能正确；HANDOVER §3.1 顺序需同步（待监督员） |
| 停止条件 | 未触发 | 未发现崩溃/死锁；R# 已定决策未建议改回；无替换结论 |

---

## 2.8 替换候选评估判据（只列方向不选型）

**方向：道路结构生成（自研曲线+螺旋 vs 成熟结构生成）**

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | 自研 Bezier 曲线 + 2×2 螺旋原语（ContinuousRoadCurve/RoadPlan） | 成熟"道路/结构生成"方案对比评估 |
| M2 Forge 1.20.1 兼容 | 纯数学体素化（不读世界，ContinuousRoadCurve.java:11-13）+ 世界离散验证层（RoadPlan）——架构合理 | — |
| M3 集成成本 | 被 RoadPlannerItem/RoadBuildTask/ClientRoadState 三面消费；替换需重写规划链 | 引用面清单 |
| M4 客户端风险 | 路面渲染无客户端矩阵（盲区） | 新方案客户端矩阵 |
| M5 过渡成本 | 单实现切换 | 双实现并存方案未设计 |

**结论**：道路结构生成属**产品语义**（用户定义的"强行构建动画"道路 + 数学成本模型——批 1 重构调查已定"保持自制"）；无明确成熟候选；**只列方向不选型**。

---

## 特别核查项结论

### ① R17 遵守性（buildUnits 选择链，代码行号）

**结论：遵守（代码是演进后的最新语义）**：

```java
// RoadPlan.java:108-136 buildUnits 选择链
List<BlockPos> curved = selectContinuousRoute(level, start, goal);   // :117 曲线优先
List<BlockPos> weighted = shortestVoxelRoute(level, start, goal, 2); // :118 A*
if (!curved.isEmpty() && !weighted.isEmpty())                        // :121 混合比较
    route = curvedCost <= weightedCost ? curved : weighted;          // :124 几何成本择优
else if (!curved.isEmpty()) route = curved;                          // :126 仅曲线
else if (!weighted.isEmpty()) route = weighted;                      // :129 仅 A* 后备
else if (verticalDistance > 0)                                       // :133 螺旋触发
    route = spiralCompensationRoute(level, start, goal);             // :134 2×2 螺旋
```

- **普通弯曲优先**：`curved` 先生成且成本比较优先（:117,:121-125）——**R17"普通弯曲优先"遵守**；
- **螺旋触发条件**：`verticalDistance > 0`（:133）**是"曲线和 A* 都无解"后的触发**（注释"普通混合搜索无解时"）——**与 R17"曲线候选失败后就试螺旋"语义相容并更严格**（A* 也会先试）——**不回旧阈值 `vertical > horizontal + 4`**（SPIRAL_EXTRA=4 只在日志显示 :137,作为诊断字段，非触发判据）——**R17 核心遵守**；
- **未回旧阈值确认**：grep 无 `vertical > horizontal` 或 `horizontal + 4` 触发逻辑（SPIRAL_EXTRA 常量仅注释"螺旋触发的高差裕量"且未在 if 条件使用——:31-32 注释与 :133 实际条件之间**注释保守、代码更激进**：触发是"无解+有高差"，比注释更早尝试螺旋——符合 R17"verticalDistance>0 就试螺旋"的语义演进）。

### ② R21 遵守性（静态单例绑定 ServerLevel）

**结论：遵守**：
- `static final RoadPlan INSTANCE = new RoadPlan()`（:33）——**JVM 级静态单例**；
- `select(ServerLevel level, BlockPos target)`：`if (!selected \|\| this.level != level) { this.level = level; first=target; second=null; cells/units 清空; ... }`（:65-73）——**切维度（不同 ServerLevel 实例）→ 自动重置起点重新选择**；
- `reset()`（:49-57）：level=null + 全清——RoadPlannerItem Shift+右键调用（RoadPlannerItem.java:25）；
- **跨维行为**：维度切换后 `this.level != level` → select 视为新起点（不会把跨维坐标误当第二点）——**R21 遵守**。

### ③ R22/R23 复活检查

**结论：未复活（grep 证据）**：
- `isAcceptableNormalRoute`：**无命中**（已删除，HANDOVER §3.3 记录）；
- `hasUnambiguousCellOwnership`：**无命中**（已删除）；
- 相关代码注释确认不复活：RoadPlan.java:290-291（"不再按前/后单元集合归属拒绝合法的短路径"）、:293-294（"不把集合归属当成搜索失败条件"）、:297（return true）——**R23 语义在代码中显式反向声明**；
- **D2 边界澄清**：ContinuousRoadCurve.java:112 `projections.add(x:z)` 失败 = **单个 Bezier 候选内部的防自交**（候选内水平投影唯一），与 R22 禁止的"把 A* 路线打成 no_walkable_route 的跨路线拒绝"**不同机制**——当前实现不会引发 R22 曾导致的高差 `no_walkable_route` 误判（R22 目标达成）；待监督员确认文档表述边界。

### ④ 选择链输出（完整逻辑）

**结论（已确认）**：

```
输入：first.below() → second.below()（脚位层）
1. selectContinuousRoute（曲线：直线 + 两侧 Bezier 法向偏移 0-24 步 2）
   → 逐候选体素化硬验证（净空/禁区/对角侧格/|dy|≤1/单候选防重复投影）
   → 首条通过者作为普通路线
2. shortestVoxelRoute（几何 A*，width=2）并行生成
   → curved 与 weighted 均可用：按 geometricRouteCost 择优（混合寻路比较）
   → 仅曲线可用：用曲线；仅 A* 可用：用 A*（"连续道路候选未通过，使用几何体素 A* 后备"）
3. 两者均空 且 verticalDistance > 0 → spiralCompensationRoute（2×2 螺旋，4 相枚举，三格净空）
   → 螺旋失败返回空
4. 全部失败 → 空 route → "no_walkable_route"（RoadPlan.java:84-89）
输出：centerline + spiralSupports/SpiralFootprint → buildUnits 生成 Unit 序列（headroom 2/3）→ cells
```

**与文档对照**：HANDOVER §3.1 顺序"曲线→螺旋→A*"是**旧表述**；代码已演进为"曲线 ⥢ A* → 螺旋"（混合比较新增）——**R17 语义（垂直差>0 试螺旋 + 不回旧阈值）不变且遵守**；文档顺序待监督员同步（D1）。

---

## 与 active plan 冲突声明

- road 模块与 p1-client-sync-fix-v1 **无直接交叉**（道路规划/施工不涉及位置/速度同步）；RoadPlanPacket 是 S2C 预览（client 审查已确认链路）。
- R17/R21/R22/R23 是**已定决策**：本审查只核对遵守性，未建议改回旧阈值/复活临时逻辑。

---

## 证据分级汇总

- **已确认事实**：4 文件职责与行号；R17 选择链完整逻辑（曲线→A*→螺旋，verticalDistance>0 触发，不回旧阈值）；R18/R19 螺旋净空/缓冲；R21 单例绑定/切维重置；R22/R23 未复活（grep + 代码反向声明）；RoadBuilder §3.4 全部遵守；RoadObstaclePolicy 禁区语义。
- **架构推论**：D1（HANDOVER §3.1 文档顺序 vs 代码顺序——代码更新、文档待同步）；D2（单候选防投影 vs R22 跨路线拒绝的语义边界）。无功能缺陷推论。
- **待调查**：道路专用回归未入 selftest（HANDOVER §:267 记录）；道路渲染/施工动画客户端矩阵（盲区）；D1/D2 文档同步。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。替换提案、道路改动均需监督员审核 + 用户批准。
---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**验收结论：✅ 通过**（本报告由调查员在 bus 投递故障期间以 send_note 交付；监督员独立核查后验收）

独立核查要点：关键代码引用（源码 grep 核实）、R# 遵守性、只读性（src 零改动、HEAD 仍 6c2b461）、无预定替换结论、已确认/推论/待调查分级。验收通过。
