# Baritone 对照：place_course（放置台阶跨缺口）
# 用法：/function alice_test:contrast_place → 然后 #goto 8 62 66
function alice_test:place_course
clear @s
kill @e[type=minecraft:marker]
tp @s 0.5 64.0 66.5
summon minecraft:marker 8.5 62.0 66.5
function alice_test:sw_start
tellraw @s [{"text":"[对照] 场景=place_course 目标=(8,62,66)","color":"gold"},{"text":"请输入 ","color":"white"},{"text":"#goto 8 62 66","color":"aqua"}]
