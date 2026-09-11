# ASCEND 到"非满高支撑"回归（D-105）：箱子碰撞顶面 0.875，不足整格
# 区域：x 0..12, y 58..70, z 118..132（孤立长方体，四周含上下留空气边界）
fill 0 58 118 12 70 132 minecraft:air
fill 0 58 118 12 58 132 minecraft:stone

# 地板：支撑 y=63（脚位 y=64）
fill 0 63 118 12 63 132 minecraft:stone

# 唯一目标：箱子（顶面 64.875）。规划层脚位格 = 箱子上一格 (2,65,126)
# 旧缺陷：完成契约用原版 blockPosition() → 站在箱顶时脚位格算成 (2,64,126)，
# 与目标格差一格 → ASCEND 段永不完成 → 原地弹跳 12 次后 SEGMENT_TIMEOUT。
setblock 2 64 126 minecraft:chest
