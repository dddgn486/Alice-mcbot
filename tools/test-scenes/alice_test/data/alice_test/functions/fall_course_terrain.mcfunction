# FALL（落差 2~3 格）验证场景：孤立长方体区域
# 区域：x 18..30, y 59..75, z 60..74（与 pillar_course 同 x 带、不同 z 带，四周含上下空气边界）
fill 18 59 60 30 75 74 minecraft:air
fill 18 59 60 30 59 74 minecraft:stone

# 高台：支撑 y=63（脚位 64），x 20..22，z 62..70
fill 20 63 62 22 63 70 minecraft:stone

# 2 格落差落点平台：支撑 y=61（脚位 62），z=66，x 23..26
fill 23 61 66 26 61 66 minecraft:stone

# 3 格落差落点平台：支撑 y=60（脚位 61），z=68，x 23..26
fill 23 60 68 26 60 68 minecraft:stone

# 守卫反例：落点 (23,62,70) 上方 y=64 封顶 → PILLAR 返回列被挡（应拒绝 FALL）
fill 23 61 70 26 61 70 minecraft:stone
setblock 23 64 70 minecraft:stone

# 观察台（场景内、路径外）
fill 20 63 72 21 63 73 minecraft:stone
