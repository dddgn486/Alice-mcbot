# 挖掘站位选优（两模式）验证：一键场景
function alice_test:mine_course_terrain
tp @s 20.5 64.0 124.5
give @s alice:mine_course_runner
tellraw @s [{"text":"[Alice 挖掘站位场景] ","color":"gold"},{"text":"平台 x20..26/z130..142：z140 露天目标、z137 贴墙、z134 全包围、z131 头位、z128 孤立簇。","color":"white"}]
tellraw @s [{"text":"挖掘站位检查器","color":"aqua"},{"text":"右键：期望 free/wall/headroom=PASS（模式A）+ blocked=PASS（模式TUNNEL）+ buried=PASS（found_but_unminable 或 TUNNEL）。","color":"white"}]
