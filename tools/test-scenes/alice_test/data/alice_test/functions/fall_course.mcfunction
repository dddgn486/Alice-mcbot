# FALL 验证：一键场景
function alice_test:fall_course_terrain
tp @s 20.5 64.0 72.5
give @s alice:pathing_fall
tellraw @s [{"text":"[Alice 落差场景] ","color":"gold"},{"text":"高台脚位 y=64：z=66 是 2 格落差、z=68 是 3 格落差、z=64 是 4 格（应拒绝）、z=70 落点上方封顶（应拒绝）。","color":"white"}]
tellraw @s [{"text":"落差检查器","color":"aqua"},{"text":"右键：期望 fall_plan_2/3=PASS + drop4_guard/recover_guard=PASS + fall_execute=PASS。","color":"white"}]
