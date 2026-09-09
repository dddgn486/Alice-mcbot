# Baritone 对照（逐 tick 轨迹）：pillar_course
# 用法：/function alice_test:contrast_trace_pillar → 立刻输入 #goto 25 67 44
function alice_test:pillar_course_terrain
clear @s
kill @e[type=minecraft:marker]
tp @s 24.5 64.0 44.5
give @s minecraft:cobblestone 8
summon minecraft:marker 25.5 67.0 44.5
function alice_test:trace_start
tellraw @s [{"text":"[对照] PILLAR 逐 tick 轨迹：目标 (25,67,44)","color":"gold"}]
tellraw @s [{"text":"请立刻输入 ","color":"white"},{"text":"#goto 25 67 44","color":"aqua"}]
