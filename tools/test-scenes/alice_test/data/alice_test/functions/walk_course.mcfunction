# WalkTo 迁移验证：一键场景
function alice_test:walk_course_terrain
tp @s 20.5 64.0 88.5
give @s alice:walk_to_runner
tellraw @s [{"text":"[Alice WalkTo 场景] ","color":"gold"},{"text":"平台 (21,64,83) 起；x=23 有一格高墙；远处有孤立柱顶目标。","color":"white"}]
tellraw @s [{"text":"WalkTo 自检器","color":"aqua"},{"text":"右键：期望 walk_flat/walk_over_wall=PASS（DONE 且脚位命中）+ walk_unreachable=PASS（walk_no_path）+ walk_unsafe=PASS（walk_target_not_safe）。","color":"white"}]
