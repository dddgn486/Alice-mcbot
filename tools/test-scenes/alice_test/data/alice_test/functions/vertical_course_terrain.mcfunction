# DOWNWARD（垂直下落 1 格）验证场景：孤立长方体区域
# 区域：x -6..14, y 59..70, z 38..54
fill -6 59 38 14 70 54 minecraft:air
fill -6 59 38 14 59 54 minecraft:stone

# 地板：支撑 y=63（脚位 64），x -2..2，z 42..48
fill -2 63 42 2 63 48 minecraft:stone

# 正例：落点支撑（破掉脚下 (0,63,45) 后掉到脚位 63）
setblock 0 62 45 minecraft:stone

# 反例：落点支撑 + 四周封死逃生路线（1x1 竖井）
setblock 2 62 45 minecraft:stone
setblock 1 64 45 minecraft:stone
setblock 3 64 45 minecraft:stone
setblock 2 64 44 minecraft:stone
setblock 2 64 46 minecraft:stone

# 观察台（平台外，避免与 bot 推挤）
fill -3 63 50 -2 63 50 minecraft:stone
