# Baritone 对照（逐 tick 轨迹）：fall_course 3 格落差
# 用法：/function alice_test:contrast_trace_fall → 立刻输入 #goto 23 61 68
function alice_test:fall_course_terrain
clear @s
kill @e[type=minecraft:marker]
tp @s 22.5 64.0 68.5
summon minecraft:marker 23.5 61.0 68.5
function alice_test:trace_start
tellraw @s [{"text":"[对照] FALL 逐 tick 轨迹：目标 (23,61,68)","color":"gold"}]
tellraw @s [{"text":"请立刻输入 ","color":"white"},{"text":"#goto 23 61 68","color":"aqua"}]
