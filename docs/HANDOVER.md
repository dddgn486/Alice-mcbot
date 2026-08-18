# Alice 项目交接说明（给新会话的快速上手）

> 用途：另一个对话/会话接手本项目时，先读本文件 + README + 设计文档，即可快速进入工作。
> 更新：2026-08-18

## 一、项目是什么

**Alice** —— Minecraft Forge 1.20.1 模组（mod_id: `alice`，包 `com.dddgn.alice`）。
目标是做一个「游戏内 AI 助手」：服务端假人玩家能像真实玩家一样感知世界、决策、执行任务，
长期愿景是理解模组内容、操作机器（如 Mekanism）、完成采集/建造任务。
**设计核心**：LLM 只做目标级决策，确定性执行器负责可靠完成；服务端权威数据，
GUI 背后操作序列化为语义接口（而非视觉点 GUI）。

设计文档：`docs/AI_PLAYER_DESIGN.md`（架构总纲）· `docs/EXECUTION_FRAMEWORK.md`（执行层框架）·
`docs/MEK_GUI_SEMANTICS.md`（Mek GUI→接口语义表）· `docs/AI_PLAYER_NOTES.md`（思考笔记）

## 二、当前进度（2026-08-18，旧路径基线已暴露问题）

| 模块 | 状态 |
|---|---|
| 假人玩家化 | ✅ `BotPlayer` 继承 ServerPlayer + 伪造连接经 `PlayerList.placeNewPlayer` 注册（客户端可见、tick 无 NPE） |
| 移动 | ✅ 位置步进（无重力，服务端不跑玩家物理）；`PathExecutor` 手动 setPos + setOnGround |
| 寻路 | ⚠️ 当前 `pathing/` 是临时简化 A*，已加局部边界/节点上限但不具备 break-aware 成本；近距离不可达可能仍 `collect_no_path`。下一阶段按 `docs/BARITONE_PORTING_CHECKLIST.md` 移植 Baritone 1.20.1 movement/cost 核心 |
| 挖掘 | ✅ `BotMiner`：站位候选逐个尝试（目标上方→下方优先）、视线无遮挡硬检查（防隔空挖）、挖掘时朝向目标（含 yHeadRot） |
| 清障挖通道 | 🗄️ 旧方案已归档：`MineTask` 的“失败后猜 blocker 再重跑 A*”仅保留兼容/基线，不再用于道路蓝图；道路改由 `RoadPlan` 三维受约束最短路拟合 + `RoadBuilder` 逐单元执行。液体及一格 clearance 作为禁区；水平允许带侧格检查的 8 邻，对角拐角额外拓宽净空 |
| 拾取 | ⚠️ 已禁止未到物品格心时提前 `playerTouch`，并锁定原始目标方块来源产物；旧 `MineTask`/阶梯逻辑归档为旧方案，不再参与道路蓝图执行 |
| 感知层 | ✅ `PerceptionSnapshot` 分类聚合摘要 + `ScopeBuffer` 任务作用域；`PerceptionProfile`（MINING/GENERAL） |
| 决策层 | ✅ `AutoMineDecision` 最小规则：扫描匹配目标（标签或方块 ID）→ 取最近 → 执行 |
| 接口扫描 | ✅ `InterfaceScanner`：Forge/Mek capability + Mek GUI 页签（红石/升级/安全/传输配置）统一扫描 |
| 客户端高亮 | ✅ 任务目标透视高亮（自定义 RenderType 关深度测试） |
| 世界存档 | ✅ bot 位置/主手物品存 SavedData，重启恢复；死亡反馈+清除 |
| 测试工具 | ✅ `alice:target_selector`（贴图钻石斧）、钻石铲右键扫描、`/alice` 命令族 |
| 安全区机制 | ✅ `SafeZoneData` 持久化：区域（维度+中心+水平半径）/方块 ID/方块标签三类保护；所有 bot 破坏和清障均硬拦截，自动挖跳过受保护目标 |
| 破坏安全策略 | ✅ `BlockBreakSafety` 是所有 bot 破坏/清障的统一门卫：明确脚下目标先换侧面站位；清障避开脚下承重、基岩/负硬度、黑曜石类高代价方块及 `SafeZoneData` 区域/方块/标签保护 |
| 自动验收 | ⚠️ 已扩至 13 场景并加入 10/20/30 秒严格预算；最近实跑整体 FAIL（TEST5 超时、TEST6/7 `collect_no_path`、TEST9 场景污染），这些失败作为重构前基线保留 |

## 三、验收标准（设计文档 §4.2，所有执行器必须满足）

1. **无隔空挖**：任何目标先有可达站位，挖掘前做视线无遮挡检查（raycast）
2. **目标搜索排序**：距离/暴露/可达性排序，暴露方块优先
3. **任务完成 = 产物入包**：掉落物追踪到账（拾取），非「动作发生过」
4. **环境前置检查**：多 tick 动作前检查流体/悬空/位移

## 四、命令与测试

```
/alice spawn <name>          生成假人
/alice mine <x y z>          指派挖掘
/alice auto-mine <tag|block> 决策层自动挖最近目标(标签如 coal_ores, 或方块ID如 stone)
/alice observe               感知摘要
/alice scan <x y z>          接口扫描
/alice selftest              手动验收
目标指定器(物品)右键方块     派挖掘任务
钻石铲右键                   快捷接口扫描
```

headless 验收：`./gradlew runServer -Dalice.selftest.auto=true`（测完自动关服，看 run/logs/latest.log）

## 五、待办 / 下一步（按用户兴趣排序）

1. **AttackTask**（实体目标）：目标指定器右键生物已在提示「未实现」；近战攻击状态机
2. **LLM 决策接入**：`AutoMineDecision` 的「选目标策略」就是替换点，接口已预留
3. **背包管理**：挖的矿堆在背包，后续「存箱子/回家卸货」
4. **搭路/垫方块**：3 格高平台爬不上去（跳跃物理上限），需垫方块能力
5. **测试工具规范化**：钻石铲扫描等可迁移到 alice 注册物品

### 安全区操作

```
/alice protect add-area <x y z> <radius>   # 当前维度、覆盖全部高度的圆形区域
/alice protect remove-area <x y z>         # 移除该中心的区域
/alice protect add-block <block_id|#tag>   # 保护方块 ID 或方块标签
/alice protect remove-block <block_id|#tag>
/alice protect list
```

规则存在主世界 `SavedData`（`alice_safe_zones`），重启后保留。保护一律适用于 Alice 的显式目标和清障目标；仅移动穿过区域不受影响。

## 六、关键决策与已知坑（R# 编号，避免重踩）

- **R8**：selftest 手动触发（自动触发曾挂服务器）；auto 模式用系统属性开关
- **R14**：JECh 是纯客户端模组，已移入 `clientOnly` 配置只注入 runClient（根治 dedicated server 崩溃）
- **R15**：1×1 坑爬不出（A* 落脚格需下方实心）——已部分缓解（跳跃过渡），完全解决需跳跃模拟
- **R16**：原版 Player.tick 触碰拾取对玩家化假人不触发 → `forcePickup` 主动 playerTouch 兜底
- **坑**：掉落物有 pickupDelay；挖高处方块后掉落物在空中需等 onGround 再拾取，未落地前不要反复跑昂贵 A*
- **拾取规则**：任务掉落物按 UUID 持续追踪并每 tick 刷新实体位置/三维距离；仅超过 64 格可放弃，近距离不可达必须失败；必须走到实体实际脚位格格心附近后才能 `playerTouch`，不得在坑边等待吸取。旧版最多挖 8 格侧向阶梯的逻辑仅作过渡基线，已知会 `collect_no_path`，不再继续堆特判
- **移动边界**：不允许为拾取增加超过 1 格的自由下落；能下去不代表能返回，向下接近目标应由未来“阶梯挖掘 vs 搭路”规划处理
- **破坏策略**：所有 bot 破坏先经 `BlockBreakSafety`；明确目标在脚下时禁止原地向下挖、必须换侧面站位；清障额外回避脚下承重块、基岩/负硬度方块、黑曜石等高代价方块与安全区
- **坑**：`RenderType.lines()` 自带深度测试 → 透视高亮需自定义 RenderType（NO_DEPTH_TEST）
- **坑**：`stone` 是方块 ID 不是标签（无同名标签）→ auto-mine 需自动判断标签/方块两种模式
- **坑**：清障目标必须「bot 直接可见」——递归向 bot 靠近，否则挖到一半 line_of_sight_blocked 中断
- **坑**：`BotMiner` 的 `protected_*` 失败不能交给 `MineTask` 的通用清障分支，否则会绕开受保护目标先挖墙；现在直接终止任务
- **测试运行器**：`server.halt(true)` 后 Gradle/Forge 子进程可能不退出并遗留 `run/world/session.lock`；日志看到 SELFTEST 结果后清理该自测进程再复跑
- **坑**：Java 17 + netty 的 setAccessible 警告无害；`FMLJavaModLoadingContext.get()` 过时警告无害

## 七、开发工作流（三副本同步）

- WSL：`~/projects/alice`（开发/编译/headless 测试）
- Windows：`D:\JAVA_projects\alice`（runClient 实测；`receive.denyCurrentBranch=updateInstead`）
- GitHub：`dddgn486/Alice-mcbot`（公开存档 + Actions CI；SSH 推送）
- 流程：WSL 改代码 → `git push origin master`（Windows）→ 用户实测 → `git push github master`
- **注意**：GitHub 上用户可能用网页编辑 README → push 前先 `git fetch github` 检查冲突，
  有则 merge 再 push（遇到过 3 次 non-fast-forward）
- Windows 端若 libs/ 文件被工作区更新删除，从 WSL 复制回（libs/jecharacters jar）

## 八、环境

- Forge 1.20.1-47.4.10 + Parchment 2023.09.03 + JDK 17 + Gradle 8.8
- Mekanism 1.20.1-10.4.16.80（modmaven，接口扫描测试对象）；JEI（modmaven）；
  JECh（本地 libs/，仅 runClient）
- 依赖：`implementation fg.deobf("mekanism:Mekanism:...")` + generators(runtimeOnly)
