# 内核对齐验证：流体屏障（一键）：孤立长方体开阔场景
# 目的：验证规划器不会把流体当"可挖阻挡物"（对照 Baritone getMiningDurationTicks:588-590 → COST_INF）
# 固定起点 (0,64,66)，目标 (8,62,66)；x=2..3 在脚位/身体高度全是岩浆
function alice_test:lava_course_reset

# 平台 A：支撑 y=63（脚位 64），x -3..1
fill -3 63 62 1 63 70 minecraft:stone

# 岩浆屏障：x=2..3，脚位与身体高度（y=63..65）全为岩浆源
fill 2 63 62 3 65 70 minecraft:lava

# 平台 B：支撑 y=63（脚位 64），x 4..6
fill 4 63 62 6 63 70 minecraft:stone

# 低位平台：支撑 y=61（脚位 62），x 7..9
fill 7 61 62 9 61 70 minecraft:stone

# 玩家观察点（岩浆屏障外侧）
tp @s -1.5 64.0 72.5
give @s alice:pathing_lava_guard
tellraw @s [{"text":"[Alice 岩浆屏障场景] ","color":"gold"},{"text":"固定起点 (0,64,66) → 目标 (8,62,66)。中间 x=2..3 是岩浆屏障。","color":"white"}]
tellraw @s [{"text":"流体屏障检查器","color":"aqua"},{"text":"右键：期望 UNREACHABLE（拒绝把岩浆当可挖方块）；若显示 REACHED 则是缺陷。","color":"white"}]
