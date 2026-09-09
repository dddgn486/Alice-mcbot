# 连锁挖掘生产开关验证（D-077）：一键场景
function alice_test:chain_mine_course_terrain
tp @s 23.5 64.0 169.5
give @s alice:target_selector
tellraw @s [{"text":"[Alice 连锁生产开关场景] ","color":"gold"},{"text":"正前方 3x3 铁矿石。","color":"white"}]
tellraw @s [{"text":"对比步骤","color":"aqua"},{"text":"① /alice chain off（默认）→ 右键中间那块矿石 → 只挖 1 格；② /alice chain auto → 重新 /function alice_test:chain_mine_course → 右键同一块 → 整条脉被连锁挖掉。每次只点一块，等日志 task_execution_terminal 出现 COMPLETED 再点下一块（点新目标会替换正在跑的任务，前一批掉落物就不再收集）。","color":"white"}]
tellraw @s [{"text":"判读","color":"aqua"},{"text":"日志 [ChainMine] prod_armed/prod_trigger/prod_done + [CollectDrops] SUMMARY；off 时不应出现 prod_* 行。","color":"white"}]
