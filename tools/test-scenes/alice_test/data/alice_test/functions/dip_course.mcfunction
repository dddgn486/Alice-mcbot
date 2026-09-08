# Q7 验收场景：路线偏好（一键）：孤立长方体开阔场景
# 目的：验证成本模型按"真实耗时"选路
# 固定起点 (0,64,66)，目标 (-1,64,63)
# 场景：x=-1..2、z=65 是一条 1 格深的坑（支撑 y=62，脚位 63），其余为平地板（支撑 y=63，脚位 64）
# 旧模型路线：DESCEND(0,63,65) + ASCEND(0,64,64) + DIAGONAL(-1,64,63)  cost=4.41  实测≈34 tick
# 标定后路线：TRAVERSE + DIAGONAL + DIAGONAL + TRAVERSE（西侧绕路）      cost=4.66  实测≈28 tick
# 期望（Q7 标定后）：first=TRAVERSE；标定前为 first=DESCEND（基线）
function alice_test:dip_course_reset

# 平地板：支撑 y=63（脚位 64），x -3..3，z 60..70
fill -3 63 60 3 63 70 minecraft:stone

# 坑底：y=62 石头，保证坑恰好 1 格深
fill -1 62 65 2 62 65 minecraft:stone

# 1 格深坑：x=-1..2、z=65
fill -1 63 65 2 63 65 minecraft:air

tp @s -1.5 64.0 72.5
give @s alice:pathing_dip_route
tellraw @s [{"text":"[Alice 路线偏好场景] ","color":"gold"},{"text":"起点 (0,64,66) → 目标 (-1,64,63)；x=-1..2、z=65 是一条 1 格深坑。","color":"white"}]
tellraw @s [{"text":"路线偏好检查器","color":"aqua"},{"text":"右键：期望 first=TRAVERSE movements=4（西侧绕路，实测约 28 tick）；显示 first=DESCEND 是标定前基线（约 34 tick）。","color":"white"}]
