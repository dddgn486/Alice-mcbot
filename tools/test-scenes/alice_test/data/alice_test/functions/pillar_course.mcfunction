# PILLAR 验证：一键场景
function alice_test:pillar_course_terrain
tp @s 20.5 64.0 51.5
give @s alice:pathing_pillar
tellraw @s [{"text":"[Alice 垂直上升场景] ","color":"gold"},{"text":"3 格深 1x1 竖井（基岩壁），唯一出路是在跳跃中往脚下放方块。","color":"white"}]
tellraw @s [{"text":"垂直上升检查器","color":"aqua"},{"text":"右键：期望 pillar_plan=PASS（首步 PILLAR x3）+ resource_guard=PASS（无方块不可规划）+ pillar_execute=PASS。","color":"white"}]
