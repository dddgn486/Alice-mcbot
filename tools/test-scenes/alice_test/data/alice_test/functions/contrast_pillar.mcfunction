# Baritone 对照：pillar_course（3 格深 1x1 竖井，唯一出路=跳跃中在脚下放方块）
# 用法：/function alice_test:contrast_pillar → 然后输入 #goto 25 67 44
function alice_test:pillar_course_terrain
clear @s
kill @e[type=minecraft:marker]
tp @s 24.5 64.0 44.5
give @s minecraft:cobblestone 8
summon minecraft:marker 25.5 67.0 44.5
function alice_test:sw_start
tellraw @s [{"text":"[对照] 场景=pillar_course 起点=(24,64,44) 目标=(25,67,44)","color":"gold"},{"text":"请输入 ","color":"white"},{"text":"#goto 25 67 44","color":"aqua"}]
