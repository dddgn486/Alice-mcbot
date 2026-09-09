# fence_course：一键场景（地形由 fence_course_terrain 提供；串联回归直接调用它）
function alice_test:fence_course_terrain
tp @s 4.5 64.0 48.5
give @s alice:pathing_fence_guard
tellraw @s [{"text":"[Alice 栅栏站立场景] ","color":"gold"},{"text":"起点 (0,64,48) → 目标 (0,64,44)；z=46 有横跨栅栏。","color":"white"}]
tellraw @s [{"text":"站立判定检查器","color":"aqua"},{"text":"右键：期望 UNREACHABLE（栅栏不可站、不可穿）；显示 REACHED 说明把栅栏当成了支撑面。","color":"white"}]
