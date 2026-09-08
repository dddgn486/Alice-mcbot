# Mekanism 机器 GUI → 接口语义表（v0.2）

> 用途：`/alice scan <x y z>` 扫描结果与 Mek 机器 GUI 的对照表。
> 数据来源：Mekanism 1.20.1-10.4.16.80 反编译接口实测（javap）+ MC 百科。
> 更新时间：2026-08

## 〇、核心结论：GUI 页签 → 接口映射（回答「有没有统一层级」）

**Mek 没有单一 Java 接口能覆盖所有 GUI 页签**，每个页签对应一个独立扩展点，
分布在三种机制上：

| GUI 页签 | 读取方式 | 机制类型 |
|---|---|---|
| 物品槽 / 能量 / 流体 | `IItemHandler` / `IEnergyStorage` / `IFluidHandler` | Forge capability |
| 气体 / 灌注 / 颜料 / 浆液 / 热 / 精确能量 | Mek `Capabilities.*` | Mek capability |
| 传输配置（颜色槽↔面→IO） | `ISideConfiguration.getConfig()` → `TileComponentConfig` | instanceof 接口 |
| 升级（添加/移除配件） | `ITileUpgradable.getComponent()` → `TileComponentUpgrade` | instanceof 接口 |
| 安全（公开/私人/信任） | `ISecurityTile`（extends `ISecurityObject`） | instanceof 接口 |
| 红石控制 | `ITileRedstone`（extends `IRedstoneControl`） | instanceof 接口 |

但注意两件事：

1. **所有 Mek 机器都继承 `TileEntityMekanism`，这些接口会同时实现**。
   所以对 Alice 来说不存在「逐台机器猜接口」的问题：一次 `instanceof` 基类
   （或直接对上面 4 个接口各查一次）就能全量读取。这就是适配器收敛的价值——
   GUI 是分散的，但数据是聚合的。

2. **Mek 自带的「计算机接口」（ComputerCraft/CC:Tweaked 集成）本身就是一套统一语义层**：
   `IComputerTile` + `@ComputerMethod` 注解动态注册，暴露统一方法如
   `getEnergy() / getRedstoneMode() / getConfigurableTypes() / getMode() / setMode() /
   getSupportedUpgrades() / getSecurityMode() / setStrictInput() / setInputColor() /
   setOutputColor()` 等，通过 `getComputerMethods(BoundMethodHolder)` 枚举。
   这与我们设计文档 §5「服务端接口序列化」思路一致——**Mek 已经做了**。
   → 审查点 R13：Alice 的 Mek 适配器应考虑「镜像/复用这套 Computer API」而非自造轮子
   （符合「成熟方案优先」原则）；但它是 `mekanism.common` 内部包，跨版本稳定性不如
   `mekanism.api`，需要权衡。

## 一、通用语义约定

| 接口类型 | capability / 接口 | 语义 |
|---|---|---|
| 物品槽 | `IItemHandler` | 槽位序号 → GUI 位置（见下表各机器） |
| 能量 | `IEnergyStorage`（FE） | 机器储能 / 容量 |
| 流体 | `IFluidHandler` | 流体输入/输出 tank |
| 气体 | `IGasHandler`（Mek） | 气体输入/输出 |
| 灌注 | `IInfusionHandler`（Mek） | 冶金灌注机的灌注介质 |
| 颜料 | `IPigmentHandler`（Mek） | 染料相关 |
| 浆液 | `ISlurryHandler`（Mek） | 矿石浆液（5x 产线） |
| 热 | `IHeatHandler`（Mek） | 热力机器 |
| 传输配置 | `ISideConfiguration` | 每个 TransmissionType：相对面 → DataType（即 GUI 颜色槽） |
| 升级 | `ITileUpgradable` | 已装升级 + 支持列表（各含上限） |
| 安全 | `ISecurityTile` | 安全模式 + 所有者 |
| 红石 | `ITileRedstone` | 红石模式（禁用/高/低/脉冲）+ 当前供电 |

### DataType（传输配置的 IO 类型，GUI 里每个颜色槽对应一个）

`NONE / INPUT / INPUT_1 / INPUT_2 / OUTPUT / OUTPUT_1 / OUTPUT_2 / INPUT_OUTPUT / ENERGY / EXTRA`

- `RelativeSide`：FRONT / LEFT / RIGHT / BACK / TOP / BOTTOM（相对机器朝向，非世界坐标）
- 每个 DataType 带一个 `EnumColor`——**这就是 GUI 里颜色槽的颜色**
- `ConfigInfo.isEjecting()` = GUI 的「自动弹出」开关

### 升级 Upgrade（`getMax()` = 上限）

`SPEED / ENERGY / FILTER / GAS / MUFFLING / ANCHOR / STONE_GENERATOR`

### 红石模式 RedstoneControl

`DISABLED（禁用）/ HIGH（高电平激活）/ LOW（低电平激活）/ PULSE（脉冲激活）`

### 安全模式 SecurityMode

`PUBLIC（公开）/ PRIVATE（私人）/ TRUSTED（信任）`

## 二、核心机器 GUI 语义

### 单输入单输出机器（充能冶炼炉 / 富集仓 / 粉碎机 / 精密锯木机 / 锇压缩机 / 化学结晶机）

| 槽位 | 语义 | GUI 位置 |
|---|---|---|
| 槽 0 | 输入（原料） | 左上 |
| 槽 1 | 输出（产物） | 右上 |
| 槽 2 | 能量槽（放能量立方/电池充放电） | 底部 |
| 能量条 | 机器储能 FE | GUI 左下 |

### 冶金灌注机 Metallurgic Infuser

| 槽位 | 语义 |
|---|---|
| 槽 0 | 输入（待灌注物品） |
| 槽 1 | 输出（成品） |
| 槽 2 | 额外输入（红石/钻石/碳，决定灌注类型） |
| 灌注槽（infusion） | 灌注介质储量 |
| 能量 | FE |

### 化学灌注机 Chemical Infuser

| 接口 | 语义 |
|---|---|
| 气体输入 ×2 | 两种反应气体 |
| 气体输出 ×1 | 生成气体 |
| 能量 | FE |

### 电解分离器 Electrolytic Separator

| 接口 | 语义 |
|---|---|
| 流体输入 | 水（电解原料） |
| 气体输出 ×2 | 左=氢、右=氧（可配置导出/排出） |
| 能量 | FE |

### 旋转冷凝机 Rotary Condensentrator

| 接口 | 语义 |
|---|---|
| 流体 ↔ 气体 | 双向转换（模式切换） |
| 能量 | FE |

### 泵 Electric Pump

| 接口 | 语义 |
|---|---|
| 流体输出 | 抽取的流体 |
| 能量 | FE |

### 能量立方 Energy Cube / 感应矩阵 Induction Matrix

| 接口 | 语义 |
|---|---|
| 能量 | 储能（FE） |

### 数字采矿机 Digital Miner

| 接口 | 语义 |
|---|---|
| 物品槽 | 采到的矿（多槽） |
| 能量 | FE（消耗巨大） |
| 面配置 | 配置采矿范围/替换方块 |

## 三、实测对照方法

1. 创造模式放一台机器（如充能冶炼炉），`/alice scan <x y z>`
2. 对照日志输出：
   - 【物品槽】槽0=输入 / 槽1=输出 / 槽2=能量
   - 【能量】0 / 8000 FE（空机器）
   - 【红石控制】模式 + 供电状态
   - 【升级】已装 / 支持列表（各含上限）
   - 【安全】模式 + 所有者
   - 【传输配置】每个传输类型的 面→IO 类型（含颜色） + 自动弹出
3. 放一块铁矿石进槽 0、给机器通电，再 scan，应看到能量变化 + 槽位物品变化
4. 给机器装个速度升级、把红石改成高电平激活、把安全设为私人，再 scan 验证三项变化

## 四、已知限制（待适配器解决）

- slotHint 是**通用惯例**（槽0输入/槽1输出/末位能量），对多槽/特殊槽机器（数字采矿机、化学注液机等）不精确
- 气体/灌注/颜料/浆液/热的具体**内容读取**（如"氢气 ×1000mB"）未实现，目前只识别"有该接口"
- 升级、安全、红石、传输配置已可读**状态**；**写操作**（setMode / addUpgrades / setSecurityMode）留给适配器层
- 传输配置目前读相对面 + IO 类型，尚未把 RelativeSide 换算成世界方向（需 `getDirection()` 旋转，留给写操作）
