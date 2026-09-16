# deep_pond_course：一键场景（地形由 deep_pond_course_terrain 提供；串联回归直接调用它）
function alice_test:deep_pond_course_terrain
tp @s -1.5 64.0 72.5
give @s alice:pathing_battery
tellraw @s [{"text":"[Alice 深水池场景] ","color":"gold"},{"text":"起点 (0,64,66) → 目标 (7,64,66)，中间是 3 格深、横跨全场的水池（两侧到顶石墙，绕不过去）。","color":"white"}]
tellraw @s [{"text":"复测方式：","color":"aqua"},{"text":"右键 alice:pathing_battery 跑串联回归（含本场景 deep_pond_course）；或直接看 bot 能否从水里游过去。","color":"white"}]
