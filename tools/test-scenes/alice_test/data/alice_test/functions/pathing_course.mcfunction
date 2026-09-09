# pathing_course：一键场景（地形由 pathing_course_terrain 提供；串联回归直接调用它）
function alice_test:pathing_course_terrain
tp @s 13.5 64.0 46.5

give @s alice:pathing_battery
give @s alice:pathing_session
give @s alice:pathing_regression
tellraw @s [{"text":"[Alice 路径课程] ","color":"gold"},{"text":"孤立长方体开阔场景已生成，固定起点 (0,64,46)。你已站在观察台：","color":"white"},{"text":"寻路自检电池","color":"yellow"},{"text":"右键跑全部单步检查；","color":"white"},{"text":"路径会话测试器","color":"yellow"},{"text":"右键跑 规划→逐段执行。","color":"white"}]
