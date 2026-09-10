# 挖掘 Job（J5）一键场景：配额 4 / 共 6 处铁矿
function alice_test:ore_course_terrain
tp @s 56.5 63.0 132.5
give @s alice:mine_job
tellraw @s [{"text":"[Alice 挖掘 Job 场景·裸露矿脉] ","color":"gold"},{"text":"石体顶面 y=62 嵌 6 处铁矿（52/56/60 × 128/136）。配额 4 处。","color":"white"}]
tellraw @s [{"text":"挖掘 Job 启动器","color":"aqua"},{"text":"右键：扫描候选 → 就近选择 → 逐处挖（含走到站位）→ 收集入包；决策与终态走 [Job] 日志。","color":"white"}]
