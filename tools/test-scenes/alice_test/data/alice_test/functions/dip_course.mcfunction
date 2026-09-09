# dip_course：一键场景（地形由 dip_course_terrain 提供；串联回归直接调用它）
function alice_test:dip_course_terrain
tp @s -1.5 64.0 72.5
give @s alice:pathing_dip_route
tellraw @s [{"text":"[Alice 路线偏好场景] ","color":"gold"},{"text":"起点 (0,64,66) → 目标 (-1,64,63)；x=-1..2、z=65 是一条 1 格深坑。","color":"white"}]
tellraw @s [{"text":"路线偏好检查器","color":"aqua"},{"text":"右键：期望 first=TRAVERSE movements=4（西侧绕路，实测约 28 tick）；显示 first=DESCEND 是标定前基线（约 34 tick）。","color":"white"}]
