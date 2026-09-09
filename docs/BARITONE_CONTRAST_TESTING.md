# Baritone 真实对照测试流程

- 日期：2026-09-09
- 目的：在**同一场景、同一目标**下，把 Alice 的确定性 bot 与**真实 Baritone**并排跑一遍，
  用可比的 tick 数回答"我们对齐得怎么样、哪里还差"。
- 定位：对照测试是**观测手段**，不是验收门槛；Alice 自身的验收仍用 `alice_test:*` 一键场景。

## 一、实例与版本

| 项 | 值 |
|---|---|
| 对照实例 | `D:\JAVA_projects\worldedit-test\versions\Bariton_contrast`（WSL：`/mnt/d/JAVA_projects/worldedit-test/versions/Bariton_contrast`） |
| 加载器 | Forge `1.20.1-47.4.23` |
| Baritone | `mods/baritone-standalone-forge-1.10.5.jar`（v1.10.5，官方最后一个 1.20/1.20.1 构建；`loaderVersion=[46,)`） |
| 世界 | `saves/新的世界`（从固定客户端拷贝，含 `alice_test` 数据包） |
| Alice 侧 | 固定客户端 `1.20.1-Forge_47.4.10` + `alice-1.0.0-1.20.1.jar` |

**参考源码 pin（D-036 更新）**：

| 树 | 位置 | 用途 |
|---|---|---|
| **1.20.1（主）** | `/home/fb486/projects/reference/baritone-1.20.1/`，tag `v1.10.5`，commit `8c55ad0` | 与对照实例**同版本**；行号/语义引用以它为准 |
| 1.21.4（辅） | `/home/fb486/projects/reference/baritone/`，commit `64333af` | 交叉核对（新版本语义变化） |

## 二、对照场景与命令

Alice 侧与 Baritone 侧使用**同一个场景函数、同一个目标**：

| 场景 | Alice 入口（bot） | Baritone 准备函数（玩家） | Baritone 命令 | 目标 |
|---|---|---|---|---|
| 基础课程（平地/对角/上升/下降/两段链） | `alice:pathing_session` | `/function alice_test:contrast_pathing` | `#goto 0 62 44` | (0,62,44) |
| 放置台阶跨缺口 | `alice:pathing_placer` | `/function alice_test:contrast_place` | `#goto 8 62 66` | (8,62,66) |
| 破坏墙后通过 | `alice:pathing_breaker` | `/function alice_test:contrast_break` | `#goto 7 64 66` | (7,64,66) |
| 1 格深坑 vs 同层绕路 | `alice:pathing_dip_route`（仅规划） | `/function alice_test:contrast_dip` | `#goto -1 64 63` | (-1,64,63) |

`contrast_*` 函数做四件事：① 生成场景；② 把玩家传送到**bot 的起点**；③ 在目标处生成
`minecraft:marker`；④ 启动对照计时器并提示要输入的 `#goto` 命令。

## 三、计时口径

**Baritone 侧（玩家）**：数据包自带的停表。

- `sw_load`（`minecraft:load` 标签）创建计分板 `alice_sw`；
- `sw_tick`（`minecraft:tick` 标签）每 tick 给带 `alice_sw_running` 标签的玩家 +1；
- 玩家进入目标 `marker` 1.5 格内 → `sw_stop` 停表，聊天与日志输出
  `[对照计时] 到达目标，用时 N tick`。

**Alice 侧（bot）**：模组自身日志。

- `[R4 Session] result ... ticks=N`（会话总 tick）
- `task_execution_terminal ... durationTicks=N`（任务总 tick）

两者口径一致：都是"从起点出发到达目标的 tick 数"。

## 四、操作步骤（每个场景一次）

**Baritone 侧**

1. 用 PCL 启动 `Bariton_contrast` 实例，进入世界 `新的世界`。
2. `/reload`（首次或数据包更新后）。
3. `/function alice_test:contrast_<场景>`。
4. 立刻在聊天输入提示的 `#goto x y z`。
5. 到达后看聊天 `[对照计时] ... N tick`（同时写入 `latest.log`）。

**Alice 侧**

1. 固定客户端进入同一世界，`/function alice_test:<场景>`。
2. 右键对应测试物品。
3. 读 `latest.log` 的 `[R4 Session] result ... ticks=N`。

## 五、对照记录表（待填）

| 场景 | Alice ticks | Baritone ticks | 路线是否一致 | 备注 |
|---|---|---|---|---|
| pathing_course | | | | |
| place_course | | | | |
| break_course | | | | |
| dip_course | | | | |

## 六、已知不可比因素（读数时必须在心里扣掉）

1. **实体不同**：Baritone 驱动真实玩家（`LocalPlayer`，客户端侧），Alice 驱动服务端假人。
   两者 `maxUpStep` 都是 0.6（D-025 已对齐），但 **Baritone 会疾跑**（`SPRINT_MULTIPLIER`），
   Alice 的 core 执行器目前从不疾跑 → Baritone 平地更快是预期的。
2. **命令延迟**：停表从函数执行那一刻开始，玩家输入 `#goto` 有 0～N tick 的手动延迟。
   请在函数提示出现后**立刻**输入命令；必要时多跑几次取最小。
3. **到达判定不同**：Baritone 的 `#goto` 有自己的到达容差；我们用的是"进入 marker 1.5 格"。
4. **版本差异**：对照实例是 v1.10.5（1.20.1），源码主参考树同为 v1.10.5；
   旧 1.21.4 树只用于交叉核对。
5. **不可比场景**：`fluid_course` / `lava_course` / `fence_course` 测的是**拒绝行为**
   （不走进/不挖流体、不把栅栏当支撑面），Baritone 侧的等价观察是"玩家是否被卡住/是否绕开"，
   不做 tick 对比，只看行为是否一致。

## 七、失败排查

- `#goto` 无反应：确认 Baritone 已加载（`/baritone` 或 `#help`），且命令前缀是 `#`。
- 停表不停：`marker` 是否被清掉（`contrast_*` 每次会 `kill @e[type=marker]` 再生成）；
  玩家是否真的到达目标（容差 1.5 格）。
- 计时明显偏大：检查是否手动延迟输入了 `#goto`。
