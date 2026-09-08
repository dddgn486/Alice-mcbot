# R5-2 破坏通行场景（一键）：孤立长方体开阔场景
# 目的：验证 BreakAndTraverse —— 目标格被阻挡时先破坏（PATH_ACCESS）再通过
# 固定起点 (0,64,66)，目标 (7,64,66)；x=3 处横墙（y=64..65）挡住去路
function alice_test:break_course_reset

# 测试平台：支撑 y=63（脚位 64），x -3..8, z 62..70
fill -3 63 62 8 63 70 minecraft:stone

# 横向石墙：x=3，两格高（脚位 + 头位），阻挡整个平台宽度
fill 3 64 62 3 65 70 minecraft:stone

# 玩家观察点（墙前侧、平台边缘外）
tp @s -1.5 64.0 72.5
give @s alice:pathing_breaker
tellraw @s [{"text":"[Alice 破坏通行场景] ","color":"gold"},{"text":"固定起点 (0,64,66) → 目标 (7,64,66)，中间 x=3 有 2 格高石墙。","color":"white"}]
tellraw @s [{"text":"手持 ","color":"yellow"},{"text":"寻路破坏器","color":"aqua"},{"text":"右键任意方块：bot 会规划穿墙路径（破坏 2 个方块）并执行。","color":"white"}]
