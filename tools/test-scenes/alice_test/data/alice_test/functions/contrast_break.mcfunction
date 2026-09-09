# Baritone 对照：break_course（破坏墙后通过）
# 用法：/function alice_test:contrast_break → 然后 #goto 7 64 66
function alice_test:break_course
clear @s
kill @e[type=minecraft:marker]
tp @s 0.5 64.0 66.5
summon minecraft:marker 7.5 64.0 66.5
function alice_test:sw_start
tellraw @s [{"text":"[对照] 场景=break_course 目标=(7,64,66)","color":"gold"},{"text":"请输入 ","color":"white"},{"text":"#goto 7 64 66","color":"aqua"}]
