# 挖掘前流体风险自检场景（S-4 / P0-C）：一键生成，孤立长方体开阔场景
# 目的：验证「目标格本身不是岩浆，但邻格有岩浆源」时，挖掘目标确认阶段必须**硬拒**
#       （挖穿之后岩浆会涌进来；这与 D-037"身体别走进岩浆"不重叠）
# 布局：5×5 石头平台（支撑 y=63 ⇒ 脚位 64）
#       目标 (66,64,104)：**正下方 (66,63,104) 是岩浆源**（做一个"盖着岩浆的石头"）
#       正对照 (64,64,106)：同平台上的普通石头（必须能正常挖完）
# 站位：(64,64,104) —— 距目标 2 格，在触及范围内
function alice_test:fluid_mine_course_reset

# 平台：x 62..70，z 102..108，支撑 y=63
fill 62 63 102 70 63 108 minecraft:stone

# 目标下方的岩浆源：把 (66,63,104) 换成岩浆 ⇒ 目标(66,64,104) 的 6 邻格里有岩浆
# （1 格深的坑 + 四周 y=63 都是石头 ⇒ 岩浆不流动，场景可重复）
setblock 66 63 104 minecraft:lava

# 目标本身：盖在岩浆坑上的石头（挖穿它 ⇒ 掉落物直接掉进岩浆里 —— 正是要拒绝的场景）
setblock 66 64 104 minecraft:stone

# 正对照目标：普通石头（平台本身 y=63 是石头，这里在脚位层再放一格石头目标）
setblock 64 64 106 minecraft:stone

# ⭐ `1.4z-a`（2026-09-26）：**水**两档 —— 目标上方有水源 / 水平邻格有水源
# 水囊都**封闭**（四壁+顶），免得水漫出去污染其它臂（尤其正对照）
# C 目标 (68,64,104)：上方 (68,65,104) 是水源
setblock 68 64 104 minecraft:stone
setblock 68 65 104 minecraft:water
setblock 67 65 104 minecraft:stone
setblock 69 65 104 minecraft:stone
setblock 68 65 103 minecraft:stone
setblock 68 65 105 minecraft:stone
setblock 68 66 104 minecraft:stone

# D 目标 (68,64,106)：水平邻格 (69,64,106) 是水源
setblock 68 64 106 minecraft:stone
setblock 69 64 106 minecraft:water
setblock 69 65 106 minecraft:stone
setblock 69 64 105 minecraft:stone
setblock 69 64 107 minecraft:stone
setblock 70 64 106 minecraft:stone
