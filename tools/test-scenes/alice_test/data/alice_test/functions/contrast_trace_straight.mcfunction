# Baritone 对照：直线跑场景（与 Alice 侧同一场景、同一目标）
# 两步：① 本函数（已自动开始记录）② 立刻输入 #goto 0 64 51
function alice_test:trace_course_terrain
kill @e[type=minecraft:marker]
tp @s 0.5 64.0 40.5
summon minecraft:marker 0.5 64.0 51.5
function alice_test:trace_start
tellraw @s [{"text":"[对照] 直线跑：目标 (0,64,51)","color":"gold"}]
tellraw @s [{"text":"请立刻输入 ","color":"white"},{"text":"#goto 0 64 51","color":"aqua"}]
