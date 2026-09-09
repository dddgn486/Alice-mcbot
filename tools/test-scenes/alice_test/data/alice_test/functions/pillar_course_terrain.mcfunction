# PILLAR（垂直上升 1 格）验证场景：孤立长方体区域
# 区域：x 18..30, y 59..70, z 38..54（与其它场景 x<=14 无重叠，四周含上下各留空气边界）
fill 18 59 38 30 70 54 minecraft:air
fill 18 59 38 30 59 54 minecraft:stone

# 平台：支撑 y=63（脚位 y=64）
fill 20 63 40 28 63 48 minecraft:stone

# 竖井：内部 (24,44) 空气 3 格高（y=64..66），基岩壁（唯一出路 = 跳跃中在脚下放方块）
fill 23 64 43 25 66 45 minecraft:bedrock
fill 24 64 44 24 66 44 minecraft:air

# 观察台（场景内、路径外）
fill 20 63 51 21 63 52 minecraft:stone
