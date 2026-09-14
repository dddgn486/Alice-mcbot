# 阶段 3-B / S2+S3 场景：**孤立平台 + 两台模组机器**（Mekanism 富集仓 + 粉碎机）
# S3（D-209）加第二台的意义：证明"按表认机器"是数据驱动的——加机器 = 表已有行 + 这里多一个 setblock，**不改 Java**。
# S4 起富集仓下面放**创造能量方块**（`mekanism:creative_energy_cube`，真实方块、纯数据）：S4 闭环要机器真跑，
# 机器要电 ⇒ 供电是**场景前提**，不是 Java 改动。只读的 S2/S3 探针不需要电，不受影响。
# **方块必须写 `[facing=up]`**（2026-09-14 第五轮修，根因两侧都落在上游源码上）：
#   ① 方块侧：能量方块只有**朝向面**出电 —— `TileEntityEnergyCube` 用
#      `setupIOConfig(ENERGY, energyContainer, RelativeSide.FRONT).setEjecting(true)`，而 `TileComponentConfig`
#      这个重载做的是 `fill(DataType.INPUT)` + `setDataType(DataType.OUTPUT, outputSide)` ⇒ 除 FRONT 外面面只进不出；
#   ② 机器侧：`TileEntityElectricMachine` 用 `setupInputConfig(ENERGY, …)`（= `fill(INPUT)`）⇒ **所有面都收电**，
#      所以唯一的约束就是方块朝向；③ 方块状态朝向是 **6 向且默认 `down`** ⇒ 本行原样写的时候电朝地板送，
#      机器一格都收不到（实测 `energy_at_open=0.0`；补 4000000 J 后 200 tick 只掉 10000 J = 纯消耗、流入 0，
#      于是 S4 夹具每次都退回 `energy_source=api_precharge`，场景"真供电"这条前提形同虚设）。
# 区域：x 60..72, y 60..72, z 300..312（与其它场景分开；边界外一圈空气）
# 机器：enrichment_chamber @(66,64,306)（起点东侧 2 格）、crusher @(66,64,307)；
# 两格间距 ⇒ 都在原版交互距离 4.5 内（实测眼到方块中心 2.29 / 3.20 格）；**先成空气再放**（避免方块实体状态跨场景存活）
fill 59 59 299 73 73 313 minecraft:air
fill 60 59 300 72 72 312 minecraft:air
fill 60 63 300 72 63 312 minecraft:stone
setblock 66 63 306 minecraft:air
setblock 66 63 306 mekanism:creative_energy_cube[facing=up]
setblock 66 64 306 minecraft:air
setblock 66 64 306 mekanism:enrichment_chamber
setblock 66 64 307 minecraft:air
setblock 66 64 307 mekanism:crusher
kill @e[type=minecraft:item,x=60,y=60,z=300,dx=13,dy=13,dz=13]
