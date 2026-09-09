# 悬空目标（支撑放置）验证场景：孤立长方体区域
# 区域：x 18..30, y 59..75, z 184..200（外扩一圈空气保证隔离）
fill 17 58 183 31 76 201 minecraft:air
fill 18 59 184 30 75 200 minecraft:air
fill 18 59 184 30 59 200 minecraft:stone

# 地面平台：支撑 y=63（脚位 64），x 20..26，z 186..196
fill 20 63 186 26 63 196 minecraft:stone

# 目标正下方挖成 1×1 竖井 → 无法站在目标正下方（迫使"侧面站位 + 放支撑块"分支）
setblock 23 63 190 minecraft:air

# 放置点 (23,64,190) 的水平支撑面：东侧实心块（placeAt 只扫水平+下的支撑面）
setblock 24 64 190 minecraft:stone

# 悬空目标 (23,65,190)：正下方为空 → 需先放支撑块再挖
setblock 23 65 190 minecraft:stone

# 观察台（场景内、路径外）
fill 20 63 184 21 63 185 minecraft:stone
