# 阶段 3-B / S2+S3 场景：**孤立平台 + 两台模组机器**（Mekanism 富集仓 + 粉碎机）
# S3（D-209）加第二台的意义：证明"按表认机器"是数据驱动的——加机器 = 表已有行 + 这里多一个 setblock，**不改 Java**。
# S4 起富集仓下面放**创造能量方块**（`mekanism:creative_energy_cube`，真实方块、纯数据）：S4 闭环要机器真跑，
# 机器要电 ⇒ 供电是**场景前提**，不是 Java 改动。只读的 S2/S3 探针不需要电，不受影响。
# **供电前提要成立，必须做两件事（2026-09-14 第六轮定案；第五轮的 `[facing=up]` 只说对了一半）**：
#   ① **朝向**（第五轮已修，保留）：方块只有**朝向面**出电 —— `TileEntityEnergyCube:63` =
#      `setupIOConfig(ENERGY, energyContainer, RelativeSide.FRONT).setEjecting(true)`，而 `TileComponentConfig:213-214`
#      这个重载做的是 `fill(DataType.INPUT)` + `setDataType(DataType.OUTPUT, FRONT)` ⇒ 除 FRONT 外面面只进不出；
#      方块状态朝向是 **6 向且默认 `down`** ⇒ 不写 `[facing=up]` 就是朝地板出电。机器侧不是约束：
#      `TileEntityElectricMachine:65` = `setupInputConfig(ENERGY, …)`（`fill(INPUT)`）⇒ **面面都收电**。
#   ② **方块自带电量 = 0**（第六轮新修，这才是"一滴都没过来"的根因）：`BasicEnergyContainer:52` 的字段初值
#      就是 `FloatingLong.ZERO`，而创造档 `EnergyCubeEnergyContainer:39` 把 `insert` 强制成模拟
#      （`action.combine(!isCreative)`）⇒ **放下就是空的、而且永远充不进电**；`TileComponentEjector:166` 又是
#      `if (!container.isEmpty())` ⇒ 空方块**根本不发**。只写朝向不够。
#      证据（存档 `r.0.0.mca` 同区块、同一次保存 ⇒ 天然对照组，`serializeNBT` 只在非空时写 `stored`）：
#        enrichment_chamber @(66,64,306) → `EnergyContainers=[{"Container":0,"stored":"3990000"}]`
#        creative_energy_cube @(66,63,306) → `EnergyContainers=[]`（= 0 J）
#      ⇒ 机器那 3990000 J 是夹具 `setEnergy` 灌的、机器自己在耗；方块是 0 ⇒ 第五轮 `energy_at_open=0.0`
#      与朝向无关，"场景真供电"这条前提从来没成立过（第五轮把根因判给朝向，是错的，见 D-213）。
#   ③ 修法（**纯数据、零 Java**）：放下方块后用 `/data merge block` 直接把电量写进方块实体
#      （键名与形状照抄上面机器那条实测 NBT）。这样场景就真的自带电源，夹具的 `api_precharge` 只是兜底。
# 区域：x 60..72, y 60..72, z 300..312（与其它场景分开；边界外一圈空气）
# 机器：enrichment_chamber @(66,64,306)、crusher @(66,64,307)；
# 两格间距 ⇒ 都在原版交互距离 4.5 内（实测眼到方块中心 2.29 / 3.20 格）；**先成空气再放**（避免方块实体状态跨场景存活）
# **本场景有两个起点**（都是 Java 常量，场景不摆标记方块）：
#   · 只读探针 `MachineStationProbeTask.START` = (66,64,304)：机器西侧 2 格，够得着就行（只读，不需要走）；
#   · S4 闭环 `MachineCycleCheckTask.CYCLE_START` = **(72,64,312) 平台远角**：到机器 dx=dz=6（≈8.49 格，**远超交互距离**）
#     ⇒ v2 起闭环必须**先走过去**再开菜单（内核寻路纯通行；平台全平，路线无障碍）。
fill 59 59 299 73 73 313 minecraft:air
fill 60 59 300 72 72 312 minecraft:air
fill 60 63 300 72 63 312 minecraft:stone
setblock 66 63 306 minecraft:air
setblock 66 63 306 mekanism:creative_energy_cube[facing=up]
data merge block 66 63 306 {EnergyContainers:[{Container:0,stored:"4000000000"}]}
setblock 66 64 306 minecraft:air
setblock 66 64 306 mekanism:enrichment_chamber
setblock 66 64 307 minecraft:air
setblock 66 64 307 mekanism:crusher
kill @e[type=minecraft:item,x=60,y=60,z=300,dx=13,dy=13,dz=13]
