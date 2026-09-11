# TRAVERSE 到"底半砖"回归（D-105）：半砖碰撞顶面 0.5，不足整格
# 区域：x 0..12, y 58..70, z 138..152（孤立长方体，四周含上下留空气边界）
fill 0 58 138 12 70 152 minecraft:air
fill 0 58 138 12 58 152 minecraft:stone

# 地板：支撑 y=63（脚位 y=64）
fill 0 63 138 12 63 152 minecraft:stone

# 封闭 1 格宽走廊（内部 x 4..8 / y 64..65 / z 145），两端与两侧封死、顶部加盖
# → 只能沿走廊直线通过，中间那块底半砖必须踩上去
fill 3 64 144 9 65 144 minecraft:stone
fill 3 64 146 9 65 146 minecraft:stone
fill 3 64 145 3 65 145 minecraft:stone
fill 9 64 145 9 65 145 minecraft:stone
fill 3 66 144 9 66 146 minecraft:stone

# 走廊正中一块底半砖（顶面 63.5）。规划层脚位格 = 半砖上一格 (6,64,145)，
# 旧缺陷：站在半砖上脚位格算成半砖自己那格 (6,63,145) → TRAVERSE 完成契约永不成立。
setblock 6 63 145 minecraft:smooth_stone_slab
