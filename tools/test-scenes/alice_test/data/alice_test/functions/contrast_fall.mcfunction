# Baritone 对照：fall_course（3 格落差）
# 用法：/function alice_test:contrast_fall → 然后输入 #goto 23 61 68
function alice_test:fall_course_terrain
clear @s
kill @e[type=minecraft:marker]
tp @s 22.5 64.0 68.5
summon minecraft:marker 23.5 61.0 68.5
function alice_test:sw_start
tellraw @s [{"text":"[对照] 场景=fall_course 起点=(22,64,68) 目标=(23,61,68) 落差=3","color":"gold"},{"text":"请输入 ","color":"white"},{"text":"#goto 23 61 68","color":"aqua"}]
