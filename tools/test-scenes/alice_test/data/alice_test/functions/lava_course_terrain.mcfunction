# 内核对齐验证：封闭岩浆池（一键）：孤立长方体开阔场景
# 目的：验证 bot 不会规划走进/穿过岩浆（危险方块 + 不可放置）
# 固定起点 (0,64,66)，目标 (4,64,66)
# 岩浆池四周封闭（下方/两端/两侧），因此不流动，场景可重复使用。
function alice_test:lava_course_reset

# 平台 A：支撑 y=63（脚位 64），x -3..1
fill -3 63 62 1 63 70 minecraft:stone

# 岩浆池底部封底 + 两端封口（防下落/横向流动）
# 注意：封口必须 2 格高（y=63..64）——只做 1 格时，封口顶面（脚位 64）会成为可站面，
# 对角移动可以踩着它绕过岩浆池（2026-09-09 串联回归实证：movements=11 REACHED）。
fill 2 62 62 3 62 70 minecraft:stone
fill 2 63 61 3 64 61 minecraft:stone
fill 2 63 71 3 64 71 minecraft:stone

# 岩浆池：x=2..3，位于支撑层 y=63（脚位下方一格），两侧由平台封住 → 静止
fill 2 63 62 3 63 70 minecraft:lava

# 平台 B：支撑 y=63（脚位 64），x 4..6
fill 4 63 62 6 63 70 minecraft:stone
