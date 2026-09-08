# Q7 验收场景：路线偏好（一键）：孤立长方体开阔场景
# 目的：验证成本模型按"真实耗时"选路——1 格深坑（下降+上升）vs 同层绕路
# 固定起点 (0,64,66)，目标 (0,64,64)
# 路线 A：DESCEND (0,63,65) → ASCEND (0,64,64)          现模型 1.0+2.0 = 3.0
# 路线 B：(1,64,66)→(1,64,65)→(1,64,64)→(0,64,64)      现模型 4×1.0 = 4.0
# 实测：A ≈ 16+10 = 26 tick，B ≈ 4×6 = 24 tick → B 更快
# 期望（Q7 标定后）：first=TRAVERSE movements=4；标定前为 first=DESCEND movements=2（基线）
function alice_test:dip_course_reset

# 平地板：支撑 y=63（脚位 64），x -3..3，z 60..70
fill -3 63 60 3 63 70 minecraft:stone

# 坑底：y=62 石头，保证坑恰好 1 格深（否则会变成 4 格深）
setblock 0 62 65 minecraft:stone

# 1x1 深坑：挖掉 (0,63,65)
setblock 0 63 65 minecraft:air

tp @s -1.5 64.0 72.5
give @s alice:pathing_dip_route
tellraw @s [{"text":"[Alice 路线偏好场景] ","color":"gold"},{"text":"起点 (0,64,66) → 目标 (0,64,64)；中间 (0,63,65) 是 1 格深坑。","color":"white"}]
tellraw @s [{"text":"路线偏好检查器","color":"aqua"},{"text":"右键：期望 first=TRAVERSE movements=4（走绕路，实测更快）；显示 first=DESCEND 是标定前的基线。","color":"white"}]
