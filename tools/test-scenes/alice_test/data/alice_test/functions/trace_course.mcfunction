# 连续行动对照：Alice 侧入口（一键场景 + 直线跑物品）
function alice_test:trace_course_terrain
tp @s -1.5 64.0 40.5
give @s alice:pathing_straight
tellraw @s [{"text":"[Alice 直线跑场景] ","color":"gold"},{"text":"起点 (0,64,40) → 目标 (0,64,51)，纯平地 11 格。","color":"white"}]
tellraw @s [{"text":"直线跑器","color":"aqua"},{"text":"右键：跑完 11 格（配合 /alice trace 采轨迹）。","color":"white"}]
