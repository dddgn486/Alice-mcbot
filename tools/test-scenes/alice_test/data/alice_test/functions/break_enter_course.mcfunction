# BREAK_AND_ENTER 验证：一键场景
function alice_test:break_enter_course_terrain
tp @s 20.5 64.0 92.5
give @s alice:pathing_break_enter
tellraw @s [{"text":"[Alice 破坏进入场景] ","color":"gold"},{"text":"平台 x20..22（脚位64）；(23,*,100) 是 2 格高石柱、(23,*,98) 是 1 格石柱，目标就是柱内脚位。","color":"white"}]
tellraw @s [{"text":"破坏进入检查器","color":"aqua"},{"text":"右键：期望 plan_a/plan_b=PASS（首步 BREAK_AND_ENTER）+ execute_a=PASS（破坏后站进柱内）。","color":"white"}]
