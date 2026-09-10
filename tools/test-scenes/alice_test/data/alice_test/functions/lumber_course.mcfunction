# 伐木 Job（L3 切片 J1）一键场景：真树夹具
function alice_test:lumber_course_terrain
function alice_test:lumber_course_trees
tp @s 24.5 64.0 205.5
give @s alice:lumber_job
tellraw @s [{"text":"[Alice 伐木 Job 场景·真树] ","color":"gold"},{"text":"橡树(20,64,208) + 复制橡树(29,64,215) + 云杉(28,64,208) + 2x2 高大云杉(22,64,218)。配额 2 棵。","color":"white"}]
tellraw @s [{"text":"伐木 Job 启动器","color":"aqua"},{"text":"右键：扫描 → 决策（应选橡树，高大云杉应被拒）→ 逐根砍（自下而上、掏空后仰头挖）→ 收集入包。","color":"white"}]
tellraw @s [{"text":"注意","color":"yellow"},{"text":"站在观察点别走近树（掉落物会被你先捡走）。","color":"white"}]
