# Baritone 对照：dip_course（1 格深坑 vs 同层绕路）
# 用法：/function alice_test:contrast_dip → 然后 #goto -1 64 63
function alice_test:dip_course
clear @s
kill @e[type=minecraft:marker]
tp @s 0.5 64.0 66.5
summon minecraft:marker -0.5 64.0 63.5
function alice_test:sw_start
tellraw @s [{"text":"[对照] 场景=dip_course 目标=(-1,64,63)","color":"gold"},{"text":"请输入 ","color":"white"},{"text":"#goto -1 64 63","color":"aqua"}]
