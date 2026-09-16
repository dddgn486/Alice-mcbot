# 内核对齐验证：水位（**深水浮着**）场景（一键）：孤立长方体开阔场景
# 目的：3 格深水池能不能**游过去**（对齐 Baritone `MovementHelper.canWalkOnPosition:432-448`
#      "下面是水且上面也是水 ⇒ 算支撑" + `MovementTraverse` 的水位完成口径）。
# 固定起点 (0,64,66)，目标 (7,64,66)。
# **绕不过去**：水池横跨 x=2..5，两侧 z（61/71）是到顶的石墙（顶面 72 不可站），
# 平台外（z<62 / z>70）没有支撑 ⇒ 不进水就走不到对岸。
function alice_test:deep_pond_course_reset

# 平台 A：实体 y 59..63（顶面 63 ⇒ 脚位 64；x=1 那一列同时是水池的挡墙）
fill -3 59 62 1 63 70 minecraft:stone

# 水池：x 2..5，池底 y=60，水在 y=61..63（3 格深，水面格 y=63）
fill 2 60 62 5 60 70 minecraft:stone
fill 2 61 62 5 63 70 minecraft:water

# 池子的 z 向挡墙（到顶 ⇒ 站不上去，也圈住水不流动）
fill 2 59 61 5 72 61 minecraft:stone
fill 2 59 71 5 72 71 minecraft:stone

# 平台 B：实体 y 59..63（顶面 63 ⇒ 脚位 64）
fill 6 59 62 8 63 70 minecraft:stone
