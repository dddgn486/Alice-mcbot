# BREAK_AND_ENTER（破坏目的地格并进入）验证场景：孤立长方体区域
# 区域：x 18..30, y 59..75, z 92..106（与 fall_course z≤90 不重叠）
fill 18 59 92 30 75 106 minecraft:air
fill 18 59 92 30 59 106 minecraft:stone

# 平台：支撑 y=63（脚位 64），x 20..22，z 96..104
fill 20 63 96 22 63 104 minecraft:stone

# 目标列 A（z=100）：y=63..65 三格石头 → 需破坏躯干(64)+头位(65) 后进入 (23,64,100)
setblock 23 63 100 minecraft:stone
setblock 23 64 100 minecraft:stone
setblock 23 65 100 minecraft:stone

# 目标列 B（z=98）：y=63..64 → 只需破坏躯干(64)
setblock 23 63 98 minecraft:stone
setblock 23 64 98 minecraft:stone

# 观察台（场景内、路径外）
fill 20 63 92 21 63 93 minecraft:stone
