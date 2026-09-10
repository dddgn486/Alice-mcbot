# 伐木 Job（L3 切片 J1）一键场景
function alice_test:lumber_course_terrain
tp @s 21.5 64.0 205.5
give @s alice:lumber_job
tellraw @s [{"text":"[Alice 伐木 Job 场景] ","color":"gold"},{"text":"4 棵树：A 被封死、B 露天 4 原木（应选）、C 更远 6 原木、E 高树 8 原木。","color":"white"}]
tellraw @s [{"text":"伐木 Job 启动器","color":"aqua"},{"text":"右键：bot 扫描 → 决策（选哪棵/为什么）→ 逐根砍 → 收集入包。判读 [Job] select / step / terminal。","color":"white"}]
tellraw @s [{"text":"注意","color":"yellow"},{"text":"站在观察点别走近树（掉落物会被你先捡走）。","color":"white"}]
