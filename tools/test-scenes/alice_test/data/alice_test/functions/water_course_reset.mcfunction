# 蹚水场景重置：清空整块长方体区域（含外圈空气边界）
# 长方体区域：x -6..12, y 59..72, z 60..74（与 fluid_course / dip_course 同一块场地，各自重置）
fill -6 59 60 12 72 74 minecraft:air
fill -6 59 60 12 59 74 minecraft:stone
