# 伐木真树种植场：种真树 → 用 tools/capture-scene.py 抓取真实树形作为夹具
# 区域：x 18..30, y 59..115, z 204..230（孤立平台；高大云杉需要很高净空）
function alice_test:lumber_plant_reset
fill 18 59 204 30 62 230 minecraft:stone
fill 18 63 204 30 63 230 minecraft:dirt

# ── S1 普通橡树 (20,63,208)：1 株 ──
fill 19 63 207 21 63 209 minecraft:cobblestone
setblock 20 63 208 minecraft:dirt
setblock 20 64 208 minecraft:oak_sapling

# ── S2 普通云杉 (28,63,208)：1 株 ──
fill 27 63 207 29 63 209 minecraft:cobblestone
setblock 28 63 208 minecraft:dirt
setblock 28 64 208 minecraft:spruce_sapling

# ── S3 高大云杉（2x2 = 4 株一起长）(22..23, 63, 218..219) ──
fill 21 63 217 24 63 220 minecraft:cobblestone
fill 22 63 218 23 63 219 minecraft:dirt
setblock 22 64 218 minecraft:spruce_sapling
setblock 23 64 218 minecraft:spruce_sapling
setblock 22 64 219 minecraft:spruce_sapling
setblock 23 64 219 minecraft:spruce_sapling

# 观察点（在平台北端，远离树冠）
tp @s 24.5 64.0 205.5
give @s minecraft:bone_meal 128
tellraw @s [{"text":"[Alice 真树种植场] ","color":"gold"},{"text":"三个种植点已标好（圆石环），树苗已放好。","color":"white"}]
tellraw @s [{"text":"请催熟成真树","color":"aqua"},{"text":"S1 橡树(20,63,208)｜S2 云杉(28,63,208)｜S3 高大云杉 2x2 四株(22-23,63,218-219)。2x2 那组可能要连点几次骨粉。","color":"white"}]
tellraw @s [{"text":"催熟后告诉我（说\"种好了\"即可），我读存档抓取真实树形并生成夹具。","color":"gray"}]
tellraw @s [{"text":"想换位置/树种也可以，只要在 x18..30, z204..230 内、彼此别挨太近。","color":"gray"}]
