# break_course：一键场景（地形由 break_course_terrain 提供；串联回归直接调用它）
function alice_test:break_course_terrain
tp @s -1.5 64.0 72.5
give @s alice:pathing_breaker
tellraw @s [{"text":"[Alice 破坏通行场景] ","color":"gold"},{"text":"固定起点 (0,64,66) → 目标 (7,64,66)，中间 x=3 有 2 格高石墙。","color":"white"}]
tellraw @s [{"text":"手持 ","color":"yellow"},{"text":"寻路破坏器","color":"aqua"},{"text":"右键任意方块：bot 会规划穿墙路径（破坏 2 个方块）并执行。","color":"white"}]
