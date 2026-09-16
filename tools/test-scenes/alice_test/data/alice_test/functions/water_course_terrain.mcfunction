# 内核对齐验证：水位（**蹚水**）场景（一键）：孤立长方体开阔场景
# 目的：① 1 格深水沟（脚位格是水、支撑是石头）**走不走得过去**；
#       ② 水里那几段的耗时是不是显著高于陆地（对照 Baritone MovementTraverse 的 waterWalkSpeed ≈ 1.96×）。
# 固定起点 (0,64,66)，目标 (5,64,66)；水沟 x=2..3 **横跨整个场景宽度**（z 62..70）⇒ 绕不过去。
function alice_test:water_course_reset

# 平台 A：支撑 y=63（脚位 64），x -3..1
fill -3 63 62 1 63 70 minecraft:stone

# 水沟：x=2..3 —— 沟底 y=63 是石头，水在 y=64（正好 1 格深，脚位就在水里）
fill 2 63 62 3 63 70 minecraft:stone
fill 2 64 62 3 64 70 minecraft:water

# 平台 B：支撑 y=63（脚位 64），x 4..6
fill 4 63 62 6 63 70 minecraft:stone
