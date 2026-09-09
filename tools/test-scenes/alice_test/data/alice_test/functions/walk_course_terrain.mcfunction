# WalkTo 任务迁移验证场景（新内核 R3/R4）：孤立长方体区域
# 区域：x 18..30, y 59..75, z 76..90（与 fall_course z 76 起不重叠，四周含上下空气边界）
fill 18 59 76 30 75 90 minecraft:air
fill 18 59 76 30 59 90 minecraft:stone

# 平台：支撑 y=63（脚位 64），x 20..26，z 80..86
fill 20 63 80 26 63 86 minecraft:stone

# 1 格高的墙（x=23，横跨平台）：跨越需要 ASCEND + DESCEND
fill 23 64 80 23 64 86 minecraft:stone

# 不可达目标：孤立 3 格高柱，目标脚位 (25,66,88)（纯通行不可达 → walk_no_path）
setblock 25 63 88 minecraft:stone
setblock 25 64 88 minecraft:stone
setblock 25 65 88 minecraft:stone

# 观察台（场景内、路径外）
fill 20 63 88 21 63 89 minecraft:stone
