# 内核对齐验证：栅栏不可站立（一键）：孤立长方体开阔场景
# 目的：验证 canWalkOn 不会把"有碰撞但站不住"的方块（栅栏/墙/铁栏杆…）当作支撑面
# 固定起点 (0,64,48)，目标 (0,64,44)；z=46 是一道横跨平台的栅栏墙
# 修复前：规划器认为能站在栅栏顶 → ASCEND+DESCEND 跨过去 → REACHED
# 修复后：栅栏不可站且不可穿 → 无路可走 → UNREACHABLE
function alice_test:fence_course_reset

# 平台：x -2..2，z 44..50，支撑 y=63（脚位 64）
fill -2 63 44 2 63 50 minecraft:stone

# 栅栏墙：z=46 横跨（脚位 64 一层）
fill -2 64 46 2 64 46 minecraft:oak_fence

# 观察台（平台外）
setblock 4 63 48 minecraft:stone
