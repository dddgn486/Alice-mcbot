# Baritone 对照：pathing_course（同一场景、同一目标）
# 用法：/function alice_test:contrast_pathing  → 然后在聊天输入 #goto 0 62 44
function alice_test:pathing_course_terrain
clear @s
kill @e[type=minecraft:marker]
tp @s 0.5 64.0 46.5
summon minecraft:marker 0.5 62.0 44.5
function alice_test:sw_start
tellraw @s [{"text":"[对照] 场景=pathing_course 目标=(0,62,44)","color":"gold"},{"text":"请输入 ","color":"white"},{"text":"#goto 0 62 44","color":"aqua"}]
