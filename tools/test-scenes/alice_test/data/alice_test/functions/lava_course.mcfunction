# lava_course：一键场景（地形由 lava_course_terrain 提供；串联回归直接调用它）
function alice_test:lava_course_terrain
tp @s -1.5 64.0 72.5
give @s alice:pathing_fluid_guard
tellraw @s [{"text":"[Alice 封闭岩浆池场景] ","color":"gold"},{"text":"起点 (0,64,66) → 目标 (4,64,66)，中间 x=2..3 是静止岩浆池。","color":"white"}]
tellraw @s [{"text":"流体屏障检查器","color":"aqua"},{"text":"右键：期望 UNREACHABLE；请观察 bot 是否朝岩浆移动。","color":"white"}]
