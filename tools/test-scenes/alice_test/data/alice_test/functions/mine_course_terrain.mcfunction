# 挖掘站位选优（两模式）验证场景：孤立长方体区域
# 区域：x 18..30, y 59..75, z 124..142
fill 18 59 124 30 75 142 minecraft:air
fill 18 59 124 30 59 142 minecraft:stone

# 平台：支撑 y=63（脚位 64），x 20..26，z 130..142
fill 20 63 130 26 63 142 minecraft:stone

# 1) free：露天目标（多角度可达）
setblock 23 64 140 minecraft:stone

# 2) wall：贴墙目标，仅西面可达
setblock 23 64 137 minecraft:stone
setblock 23 64 136 minecraft:stone
setblock 23 64 138 minecraft:stone
setblock 24 64 137 minecraft:stone

# 3) blocked：四面 + 上方全包围（模式 A 无解 → 模式 B 破坏进入）
setblock 23 64 134 minecraft:stone
setblock 22 64 134 minecraft:stone
setblock 24 64 134 minecraft:stone
setblock 23 64 133 minecraft:stone
setblock 23 64 135 minecraft:stone
setblock 23 65 134 minecraft:stone

# 4) headroom：目标在头位（支撑 y=64，目标 y=65，上方封顶）
setblock 23 64 131 minecraft:stone
setblock 23 65 131 minecraft:stone
setblock 23 66 131 minecraft:stone

# 5) buried：孤立被包围簇（面格下方无支撑 → 预期 found_but_unminable）
setblock 23 63 128 minecraft:stone
setblock 23 64 128 minecraft:stone
setblock 22 64 128 minecraft:stone
setblock 24 64 128 minecraft:stone
setblock 23 64 127 minecraft:stone
setblock 23 64 129 minecraft:stone

# 观察台（场景内、路径外）
fill 20 63 124 21 63 125 minecraft:stone
