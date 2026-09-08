# 批 4 审查报告：外围/单文件模块（protection/command/tool/log/decision/action/AliceMod）

- **任务**：批 4（用户指示一次处理完剩余批次；依据 `full-review-plan-20260825.md` §1.2 批 4、§2 模板、§4/§5）
- **审查基线**：`6c2b461`（HEAD）
- **日期**：2026-08-25
- **性质**：只读审查。未修改任何文件；未运行测试；未连接工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（protection/ 2、command/ 2、tool/ 1、log/ 1、decision/ 1、action/ 1、AliceMod/ 1）、grep 引用、HANDOVER R# 对照。

---

## 2.1 定位与文件清单

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `protection/BlockBreakSafety.java` | 策略 | Alice 唯一方块破坏安全入口：explicitTargetRefusal（明确目标）/ clearingRefusal（清障更保守：脚下承重/高代价）/ isUnbreakable（负破坏速度）/ isExpensiveToClear（黑曜石/哭泣黑曜石/强化深板岩） |
| `protection/SafeZoneData.java` | 数据 | SavedData 保护规则：维度隔离水平圆形区域 + 全世界方块/标签黑名单；protectionReason/sharesArea |
| `command/BotCommand.java` | 命令 | /alice 命令路由（`requires(hasPermission(2))`）；14 个子命令（spawn/mine/soft-probe/soft-probe-travel/soft-path-probe/follow/road build+buildbybot/observe/selftest/scan/diagnose-path/status/bot-inventory/transfer-test） |
| `command/TransferSelectionCommandParseFixture.java` | 夹具 | 传输选择命令解析 fixture（item 批已核对） |
| `tool/ScanWand.java` | 工具 | 扫描铲测试工具：手持 interface_scanner 右键→服务端扫描→**setCanceled(true)**（阻止 GUI/铲土径）；等效 /alice scan |
| `log/BotLog.java` | 日志 | [alice] 前缀统一日志（info/warn），M0 假人不可见时全靠日志验证 |
| `decision/AutoMineDecision.java` | 决策 | 决策层最小规则（无 LLM，纯规则）：感知→决策→执行；pickNearest（标签）/pickNearestBlock（方块 ID）+ SafeZoneData 跳过 |
| `action/BotMiner.java` | 状态机 | 挖掘状态机（M0 核心）：选站位→直走→视线无遮挡 raycast→原版 4.5 格距离→模拟挖掘协议包+进度→破坏；路径重试/searchLimit |
| `AliceMod.java` | 入口 | MOD 入口：物品/网络/Forge 事件总线注册（已读，批 2 gui 审查确认无 MENUS 注册） |

**注册方式**：BotCommand 经 @Mod.EventBusSubscriber(FORGE) 挂 RegisterCommandsEvent（BotCommand.java:47-55）；ScanWand 同 FORGE 总线（ScanWand.java:25-29）；BotLog 静态；AutoMineDecision/BotMiner 静态/被调用；AliceMod @Mod。

---

## 2.2 边界归属

- **protection**：全模块共享的安全拦截面（BlockBreakSafety 被 BotMiner/MineTask/DropCollectionTask/RoadBuilder/TunnelObstaclePolicy 消费；SafeZoneData 被决策/道路/隧道消费）。
- **command**：测试/管理入口（14 子命令，`hasPermission(2)` 权限门）。
- **tool**：interface_scanner 测试工具（capability 扫描入口，唯一**主动 cancel** 的交互——与其他选择器"不 cancel"设计对照，属设计内差异）。
- **log**：统一日志面（[alice] 前缀）。
- **decision**：LLM 决策的纯规则占位（R39 计划门：LLM 后续接入需 Policy Gate，当前无 LLM）。
- **action**：BotMiner 单动作状态机（矿链核心，批 1 task 已核对 HARD_PATH 使用）。
- **AliceMod**：注册汇聚点。

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| PT1 | 破坏安全唯一入口 | 注释"Alice 唯一的方块破坏安全入口"（BlockBreakSafety.java:10）；grep 全工程破坏调用均过 clearingRefusal/ExplicitTargetRefusal（BotMiner/MineTask/RoadBuilder/Tunnel 交叉——批 1/批 3 已核对） | 静态核对 | **一致**（唯一入口） |
| PT2 | 明确目标 vs 清障不同策略 | explicitTargetRefusal（安全区+不可破坏；黑曜石允许）/ clearingRefusal（+脚下承重+高代价）（:23-52） | 静态核对 | **一致**（注释 :11-15 明示） |
| PT3 | 清障兜底（换站位优先） | requiresReposition（脚下时不能原地挖，可侧面后作明确目标）（:55-61） | 静态核对 | **一致**（R34 相关） |
| PT4 | SafeZone 维度隔离 + 水平圆半径 | Area(dimension, center, radius) + contains d²≤r²（SafeZoneData.java:171-177）+ dimension 匹配（:139） | 静态核对 | **一致**（全高度覆盖注释 :23） |
| PT5 | 命令权限门 | `requires(source.hasPermission(2))`（BotCommand.java:58） | 静态核对 | **一致**（A1.1 权限记录） |
| PT6 | ScanWand 主动 cancel | `event.setCanceled(true)`（ScanWand.java:41）——注释明示"阻止默认行为:开 GUI / 铲土径" | 静态核对 | **一致**（设计内；与选择器"不 cancel"对照——扫描工具消费交互是刻意行为） |
| PT7 | BotLog 统一前缀 | "[alice] " 拼接（BotLog.java:19-25） | 静态核对 | **一致**（latest.log grep 便利） |
| PT8 | 决策纯规则 + 保护跳过 | pickNearest 的 matcher 含 `!isProtected`（AutoMineDecision.java:37,44）+ SafeZoneData（:48-50） | 静态核对 | **一致**（R39：无 LLM，后续 LLM 接入需 Policy Gate） |
| PT9 | BotMiner 原版进度协议模拟 | "模拟原版挖掘协议包 + 进度累加（handleBlockBreakAction + getDestroyProgress，工具速度/时运自动生效；不用瞬挖）"（BotMiner.java:20-26 注释） | 静态核对 + 服务端可证 | **一致**（批 1 task 已核对；MineTask 服务端 PASS） |
| PT10 | BotMiner 视线硬检查 | "视线无遮挡检查（raycast，根治隔空挖）"（:21）+ 4.5 格距离（MAX_REACH:36） | 静态核对 | **一致**（65f4863 T2/批 1 已核对） |

## 2.4 R#/决策对照

| R# | 内容 | 遵守性 | 证据 |
|---|---|---|---|
| R34 | 单目标挖掘站位：视线直通+曲面可达优先；清障最多 2 格/4.5 格 | ✅ 遵守 | BotMiner 站位候选/raycast（批 1 task 已核对 MAX_CLEAR_DEPTH=2/REACH=4.5） |
| R39 | LLM ToolCall/PlanDraft 必须先过 Policy Gate；移动/攻击/库存/机器原语默认只供编排器 | ✅ 遵守（当前无 LLM；决策层为纯规则占位） | AutoMineDecision.java:13-25 注释明示"无 LLM，纯规则" |
| R8 | selftest 手动触发 | ✅ 遵守 | BotSelftest（批 1 bot 已核对） |
| R38 | 兼容分级（C1 只读） | ✅ 遵守 | ScanWand→InterfaceScanner C1（capability 批已核对） |

**边界漂移**：无。

## 2.5 Dual-View

**服务端可证**：权限门（command requires）、破坏安全策略（fixture 可断言 explicit/clearing 差异）、SafeZone 持久化（SavedData）、决策纯规则（pickNearest 保护跳过）、BotMiner 状态机（MINING/DONE/FAILED + raycast 拦截——65f4863/批 1 已服务端验证）。

**需客户端实测**：BotMiner 挖掘动画（手臂摆动——客户端可见性，65f4863 T2 已部分验证）；ScanWand 扫描结果日志/消息可见性；命令交互手感。

> **必须写明**：服务端 PASS ≠ 客户端正确。BotMiner 进度模拟/破坏逻辑服务端可证，但客户端挖掘动画与同步时序仍 A1.1 装配渲染线已验收、软路径挖掘 T2 已 USER_ACCEPTED；ScanWand/命令消息客户端可见性属低级风险。

## 2.6 证据等级核查

| 项 | 现有证据 | 等级 | 缺口 |
|---|---|---|---|
| BotMiner 挖掘 | 65f4863 T2 + latest.log SELFTEST TEST1-3 | 服务端 PASS + 客户端部分（T2 USER_ACCEPTED） | 挖掘动画专项客户端矩阵充分性待查 |
| SafeZone/BlockBreakSafety | 批 1/批 3 交叉使用记录 | 服务端 PASS（间接） | 保护拒绝码专项 fixture 无独立记录 |
| SafeZoneData 持久化 | A1.1 关联（保护场景） | USER_ACCEPTED（相关线） | 重启持久化专项 |
| BotCommand 权限门 | A1.1 A1 权限场景 | USER_ACCEPTED | 14 子命令逐一客户端验证无记录 |
| ScanWand | capability 批 S1-S4 | USER_ACCEPTED（扫描线） | — |
| AutoMineDecision | 无独立记录 | 静态核对 | 决策扫描运行验证待查 |
| BotLog | 全模块使用（latest.log 证据） | 服务端 PASS | — |

## 2.7 风险等级判定

| 模块 | 等级 | 触发理由 |
|---|---|---|
| BlockBreakSafety / SafeZoneData | **D 维持** | 唯一安全入口、策略分层正确、设计一致 |
| BotCommand（权限门） | **D 维持** | 权限 2 门 + A1.1 A1 验收 |
| ScanWand / BotLog / AutoMineDecision / BotMiner / AliceMod | **D 维持** | 职责清晰、注册正确、无边界错位 |
| 停止条件 | 未触发 | 未发现崩溃/死锁/权限漏洞；无替换结论 |

## 2.8 替换候选评估判据（只列方向不选型）

- **protection**：SafeZoneData 基于 vanilla SavedData + 标签（原生）+ 自研区域——无成熟候选。
- **command**：Brigadier 原生命令框架——已是 Forge 标准。
- **action（BotMiner）**：批 1 重构调查已列方向（原版协议模拟 vs 成熟挖掘原语——只列方向不选型，R31 参考 Baritone 机制）。
- **tool/log/decision/AliceMod**：单文件职责清晰无替换价值（decision 的 LLM 接入是 R39 未来线，非替换）。

## 与 active plan 冲突声明

- 外围模块与 p1-client-sync-fix-v1 无直接交叉（BotMiner 是挖掘动作、command 是入口，均非同步层）。若监督员需引用安全入口/命令权限证据到 P1 线调查，写入 active plan Research Decision。

## 证据分级汇总

- **已确认事实**：9 文件职责与行号；BlockBreakSafety 唯一入口+双层策略；SafeZone 维度隔离/持久化；命令权限门 2；ScanWand 唯一主动 cancel（设计内）；BotLog 前缀；决策纯规则+保护跳过；BotMiner 原版协议模拟+raycast；AliceMod 注册面无 MENUS（批 2 已核对）。
- **架构推论**：无功能缺陷推论。
- **待调查**：保护拒绝码独立 fixture、14 子命令客户端逐一验证、AutoMineDecision 运行验证、挖掘动画客户端矩阵充分性。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。替换提案、改动均需监督员审核 + 用户批准。
---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**验收结论：✅ 通过**（本报告由调查员在 bus 投递故障期间以 send_note 交付；监督员独立核查后验收）

独立核查要点：关键代码引用（源码 grep 核实）、R# 遵守性、只读性（src 零改动、HEAD 仍 6c2b461）、无预定替换结论、已确认/推论/待调查分级。验收通过。
