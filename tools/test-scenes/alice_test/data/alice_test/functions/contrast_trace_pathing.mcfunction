# 连续行动数据采集：pathing_course —— 只做准备（场景 + 起点 + 目标 marker）
# 三步流程（避免"等输入命令"的空白样本）：
#   1) /function alice_test:contrast_trace_pathing
#   2) 立刻输入 #goto 0 62 44
#   3) 再执行 /function alice_test:trace_start 开始记录（此时 bot 已在移动）
function alice_test:pathing_course_terrain
clear @s
kill @e[type=minecraft:marker]
tp @s 0.5 64.0 46.5
summon minecraft:marker 0.5 62.0 44.5
tellraw @s [{"text":"[对照] 已就位：目标 (0,62,44)","color":"gold"}]
tellraw @s [{"text":"① 输入 ","color":"white"},{"text":"#goto 0 62 44","color":"aqua"},{"text":" ② 立刻执行 ","color":"white"},{"text":"/function alice_test:trace_start","color":"aqua"},{"text":" 开始记录","color":"white"}]
