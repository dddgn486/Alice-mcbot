# D-095 断言场景：目标在墙后，唯一通道被**箱子**堵住 —— bot 不得为取目标而拆箱子
# 孤立区域：x 40..62, y 58..78, z 150..170（边界外一圈为空气）
fill 40 58 150 62 78 170 minecraft:air
fill 40 63 150 62 63 170 minecraft:stone
# 一道横墙（x=50，高 4 格，防跳越），只在 z=158 留一个缺口
fill 50 64 150 50 67 157 minecraft:stone
fill 50 64 159 50 67 170 minecraft:stone
# 唯一通道用箱子堵住（箱子含方块实体 → D-095 不得作为清障/通行破坏对象）
setblock 50 64 158 minecraft:chest
# 墙另一侧的目标（石头）
setblock 56 64 158 minecraft:stone
setblock 56 64 157 minecraft:stone
setblock 56 64 159 minecraft:stone
