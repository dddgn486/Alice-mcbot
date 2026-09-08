# legacy 上升兼容测试场景重置：清空整块长方体区域（含外圈空气边界）
# 长方体区域：x -8..20, y 59..74, z 58..78
fill -8 59 58 20 74 78 minecraft:air
# 区域底部隔离地板（y=59）
fill -8 59 58 20 59 78 minecraft:stone
