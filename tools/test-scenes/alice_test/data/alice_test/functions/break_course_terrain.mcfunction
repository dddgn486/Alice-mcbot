# R5-2 破坏通行场景（一键）：孤立长方体开阔场景
# 目的：验证 BreakAndTraverse —— 目标格被阻挡时先破坏（PATH_ACCESS）再通过
# 固定起点 (0,64,66)，目标 (7,64,66)；x=3 处横墙（y=64..65）挡住去路
function alice_test:break_course_reset

# 测试平台：支撑 y=63（脚位 64），x -3..8, z 62..70
fill -3 63 62 8 63 70 minecraft:stone

# 横向石墙：x=3，两格高（脚位 + 头位），阻挡整个平台宽度
fill 3 64 62 3 65 70 minecraft:stone

# 玩家观察点（墙前侧、平台边缘外）
