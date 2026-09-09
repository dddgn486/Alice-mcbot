# DOWNWARD 验证：一键场景
function alice_test:vertical_course_terrain
tp @s -2.5 64.0 50.5
give @s alice:pathing_downward
tellraw @s [{"text":"[Alice 垂直下落场景] ","color":"gold"},{"text":"正例 (0,64,45) 下方有洞可掉；反例 (2,64,45) 是 1x1 竖井（无逃生路线）。","color":"white"}]
tellraw @s [{"text":"垂直下落检查器","color":"aqua"},{"text":"右键：期望 downward_execute=PASS（破坏脚下→掉 1 格）+ shaft_plan=REACHED（Baritone 原样语义）。","color":"white"}]
