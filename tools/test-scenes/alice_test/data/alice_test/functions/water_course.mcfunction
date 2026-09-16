# water_course：一键场景（地形由 water_course_terrain 提供；串联回归直接调用它）
function alice_test:water_course_terrain
tp @s -1.5 64.0 72.5
give @s alice:pathing_battery
tellraw @s [{"text":"[Alice 蹚水场景] ","color":"gold"},{"text":"起点 (0,64,66) → 目标 (5,64,66)，x=2..3 是 1 格深水沟（横跨全场，绕不过去）。","color":"white"}]
tellraw @s [{"text":"复测方式：","color":"aqua"},{"text":"右键 alice:pathing_battery 跑串联回归（含本场景 water_course）；或直接看 bot 是否从水里走过去。","color":"white"}]
