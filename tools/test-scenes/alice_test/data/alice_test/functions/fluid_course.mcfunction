# fluid_course：一键场景（地形由 fluid_course_terrain 提供；串联回归直接调用它）
function alice_test:fluid_course_terrain
tp @s -1.5 64.0 72.5
give @s alice:pathing_fluid_guard
tellraw @s [{"text":"[Alice 流体屏障场景] ","color":"gold"},{"text":"起点 (0,64,66) → 目标 (4,64,66)，x=2 是 2 格高充水墙。","color":"white"}]
tellraw @s [{"text":"流体屏障检查器","color":"aqua"},{"text":"右键：期望 UNREACHABLE（拒绝把含流体方块当可挖阻挡物）；显示 REACHED 即缺陷。","color":"white"}]
