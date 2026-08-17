# M0 架构决策与审查点

> 里程碑:M0 技术验证——服务端假人按命令走到可达站位并挖掉指定方块。
> **验收标准:不存在隔空挖**(挖掘前视线无遮挡 raycast 检查)。
> 标记 ⚠️ 的为需作者审查/实测确认的决策点。

## 1. 已定架构决策

| # | 决策 | 理由 |
|---|---|---|
| D1 | 假人 = Forge `FakePlayer` 子类（`BotFakePlayer`） | 官方轮子，`gameMode` 由 `ServerPlayer` 构造自动创建（已 javap 验证），可直接模拟原版挖掘协议包 |
| D2 | 移动 = 输入模拟（`xxa/zza` + `setJumping`，1.20.1 输入在 LivingEntity 层） | 走真实物理、像人；为 M3 寻路打底 |
| D3 | 挖掘 = 原版模拟（`handleBlockBreakAction` + `getDestroyProgress` 进度累加） | 工具速度/时运等原版机制自动生效，不瞬挖 |
| D4 | tick 驱动 = 全局 `ServerTickEvent` 驱动动作（BotSession.tick） | 动作逻辑与实体解耦、易测（mc_aiplayer ActionPack 同款） |
| D5 | 站位选择 = 目标周围 2 格内的「脚空+头空+下方实心」格，排除目标本体与其正上方 | M0 简化版；A* 留 M3 |
| D6 | 入口 = `/aibot spawn` + `/aibot mine <x y z>` 命令 | M4 换成 AI 工具调用 |

## 2. 审查点清单

| # | 审查点 | 位置 | 需要确认 |
|---|---|---|---|
| R1 | **FakePlayer tick NPE 防护**：假人无 networkHandler，ServerPlayer.tick 部分路径可能 NPE，现为 try-catch 吞掉 | `BotFakePlayer.tick()` | Windows 实测假人能否正常站立/移动/被 tick |
| R2 | **输入模拟有效性**：`zza/xxa` 是否被 ServerPlayer 的 aiStep 正常消费（假人无客户端发包） | `BotWalker.tick()` | 实测假人是否真的会走 |
| R3 | **挖掘协议包在假人上有效**：`handleBlockBreakAction`/`destroyBlock` 对 FakePlayer 是否正常掉落 | `BotMiner.tick()` | 实测挖掉方块、掉落物正常 |
| R4 | **站位选择与视线检查的边界**：目标在脚下/头顶/悬空时的行为 | `BotMiner.pickStandPos()`/`lineOfSightClear()` | 实测各种方位目标 |
| R5 | **命令触发流程**：`/aibot mine` 用最近假人或自动生成 | `BotCommand` | 交互体验是否符合预期 |

## 3. M0 实测指引（Windows 侧 runClient / 服务器）

1. 进入世界，找一个平坦处：
   ```
   /aibot spawn TestBot
   ```
2. 在假人旁边放一个泥土/石头，F3 看坐标：
   ```
   /aibot mine <x> <y> <z>
   ```
3. 观察假人：走到目标旁 → 面朝方块 → 挖掘 → 方块掉落
4. **验收测试 A（隔空挖不存在）**：让假人站到方块正上方 2 格（人为用命令/方块堆出场景），
   再 `/aibot mine` 该方块——预期：假人先走下到旁边站位，而不是原地隔空挖；
   若完全无法站位（如被围死），应报 `no_stand_pos`/`line_of_sight_blocked` 失败而非隔空挖
5. **验收测试 B（正常挖）**：平地旁 2 格挖一个方块，观察进度条与掉落
6. 日志：观察 `latest.log` 中假人是否有 NPE 刷屏（R1）

## 4. 已知限制（M0 不做的）

- 不做 A* 寻路（直线 + 一阶跳）
- 不做工具选择（空手挖，速度慢但可验证逻辑）
- 不做 AI 接入（M4）
- 不做 GameTest（后续里程碑补）

## 5. 自审修复记录（2026-08-17，等待 Windows 实测前的代码复查）

| # | 问题 | 修复 |
|---|---|---|
| F1 | `BotWalker` 朝向公式反了：`atan2(dx, dz)` 在目标位于 +X 时给出 +90°（朝西），而朝东需 -90°（MC yaw 顺时针为正） | 改为 `atan2(-dx, dz)` |
| F2 | `/aibot mine` 无假人时出生在目标方块内部（卡进方块窒息） | 改为出生在 `target.above()` |
