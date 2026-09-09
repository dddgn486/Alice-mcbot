# 连续行动对照场景：纯直线 11 格平地（跨 10 个方块边界）
# 区域：x -6..14, y 59..70, z 38..54
fill -6 59 38 14 70 54 minecraft:air
fill -6 59 38 14 59 54 minecraft:stone
# 走廊：x=0..1，z=40..52，支撑 y=63（脚位 64）
fill 0 63 40 1 63 52 minecraft:stone
# 观察台
setblock -2 63 40 minecraft:stone
