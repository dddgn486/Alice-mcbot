# 模组兼容（Ore Excavation 连锁挖掘）验证场景：孤立长方体区域
# 区域：x 18..30, y 59..75, z 148..162（外扩一圈空气保证隔离）
fill 17 58 147 31 76 163 minecraft:air
fill 18 59 148 30 75 162 minecraft:air
fill 18 59 148 30 59 162 minecraft:stone

# 平台：支撑 y=63（脚位 64），x 20..26，z 150..160
fill 20 63 150 26 63 160 minecraft:stone

# 矿脉：3x3 铁矿石（脚位层 y=64，整块落在平台上 → 连锁后掉落物可达）
# 种子 = 北面中心格 (23,64,154)；bot 起点 (23,64,152)，距最远矿石约 3.8 格
fill 22 64 154 24 64 156 minecraft:iron_ore

# 观察台（场景内、路径外）
fill 20 63 148 21 63 149 minecraft:stone
