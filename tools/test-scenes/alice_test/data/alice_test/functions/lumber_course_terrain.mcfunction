# 伐木 Job（L3 切片 J1）验证场景：孤立长方体区域
# 区域：x 18..30, y 59..80, z 204..222（外扩一圈空气保证隔离）
fill 17 58 203 31 81 223 minecraft:air
fill 18 59 204 30 80 222 minecraft:air
fill 18 59 204 30 59 222 minecraft:stone

# 平台：支撑 y=63（脚位 64），x 20..26，z 206..222
fill 20 63 206 26 63 222 minecraft:stone

# ── A：最近但被石头完全封死（预期 rejected=A:no_stand）──
fill 22 64 208 24 66 210 minecraft:stone
setblock 23 64 209 minecraft:oak_log
setblock 23 65 209 minecraft:oak_log
setblock 23 66 209 minecraft:oak_log
setblock 23 67 209 minecraft:stone

# ── B：露天 4 原木 + 树冠在侧（预期 picked=B）──
fill 23 64 213 23 67 213 minecraft:oak_log
setblock 21 67 213 minecraft:oak_leaves
setblock 25 67 213 minecraft:oak_leaves
setblock 23 67 211 minecraft:oak_leaves
setblock 23 68 212 minecraft:oak_leaves
setblock 23 68 214 minecraft:oak_leaves
setblock 22 68 213 minecraft:oak_leaves
setblock 24 68 213 minecraft:oak_leaves

# ── C：6 原木、更远（预期 rejected=C:not_nearest）──
fill 23 64 217 23 69 217 minecraft:oak_log
setblock 21 69 217 minecraft:oak_leaves
setblock 25 69 217 minecraft:oak_leaves
setblock 23 69 219 minecraft:oak_leaves
setblock 22 70 217 minecraft:oak_leaves
setblock 24 70 217 minecraft:oak_leaves

# ── E：8 原木高树，超出触及（预期 rejected=E:trunk_too_tall）──
fill 23 64 220 23 71 220 minecraft:oak_log
