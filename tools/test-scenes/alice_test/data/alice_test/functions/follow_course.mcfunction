# Follow 迁移验证：一键场景（沿用 walk_course 地形）
function alice_test:walk_course_terrain
tp @s 21.5 64.0 83.5
give @s alice:follow_runner
tellraw @s [{"text":"[Alice Follow 场景] ","color":"gold"},{"text":"① 右键启动跟随（目标=你）② 原地跳几下 ③ 走到 x=23 墙的东侧 ④ 再走到远处","color":"white"}]
tellraw @s [{"text":"再点一次右键结束","color":"aqua"},{"text":"：日志输出 [Follow] SUMMARY stopped ticks=.. replans=.. minDist=..","color":"white"}]
tellraw @s [{"text":"原地跳跃是缺陷回归点：","color":"aqua"},{"text":"bot 不应报 follow_target_not_on_safe_surface / follow_no_path，日志只应有 [Follow] target_airborne hold_goal=..","color":"white"}]
