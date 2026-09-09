# 连续行动数据采集：pathing_course（平地长走廊）—— 一键准备 + 开始记录
# 用法：/function alice_test:contrast_trace_pathing → 立刻输入 #goto 0 62 44
function alice_test:pathing_course_terrain
clear @s
kill @e[type=minecraft:marker]
tp @s 0.5 64.0 46.5
summon minecraft:marker 0.5 62.0 44.5
function alice_test:trace_start
tellraw @s [{"text":"[对照] 连续行动轨迹记录：目标 (0,62,44)","color":"gold"},{"text":"请输入 ","color":"white"},{"text":"#goto 0 62 44","color":"aqua"}]
