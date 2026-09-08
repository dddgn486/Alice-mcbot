# 内核对齐验证：流体屏障（一键）：孤立长方体开阔场景
# 目的：验证规划器不会把"含流体的方块"当可挖阻挡物
#       （对照 Baritone MovementHelper.getMiningDurationTicks:588-590 → 任何非空流体状态 → COST_INF）
# 固定起点 (0,64,66)，目标 (4,64,66)
# 说明：脚位高度的岩浆必然向相邻可走格流动、会毁掉场景，因此判别性测试用"充水方块"
#       （同一代码路径：state.getFluidState() 非空），另见 lava_course 验证"不走进岩浆"。
function alice_test:fluid_course_reset

# 平台 A：支撑 y=63（脚位 64），x -3..1
fill -3 63 62 1 63 70 minecraft:stone

# 流体屏障：x=2，脚位与身体高度各 1 格（2 格高、不可攀爬），充水方块（不流动）
fill 2 64 62 2 65 70 minecraft:stone_brick_wall[waterlogged=true]

# 平台 B：支撑 y=63（脚位 64），x 3..6
fill 3 63 62 6 63 70 minecraft:stone

tp @s -1.5 64.0 72.5
give @s alice:pathing_fluid_guard
tellraw @s [{"text":"[Alice 流体屏障场景] ","color":"gold"},{"text":"起点 (0,64,66) → 目标 (4,64,66)，x=2 是 2 格高充水墙。","color":"white"}]
tellraw @s [{"text":"流体屏障检查器","color":"aqua"},{"text":"右键：期望 UNREACHABLE（拒绝把含流体方块当可挖阻挡物）；显示 REACHED 即缺陷。","color":"white"}]
