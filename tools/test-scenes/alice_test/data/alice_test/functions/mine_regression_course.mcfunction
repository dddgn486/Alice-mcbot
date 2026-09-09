# 挖掘专项串联回归（批次 5）：一键入口
# 地形由任务自己逐个重放（mine_course_terrain / chain_mine_course_terrain），
# 玩家只需站到中立观察点，右键物品即可；不要走到 bot 工作区，避免抢先捡走掉落物。
tp @s 20.5 64.0 148.5
give @s alice:mine_regression
tellraw @s [{"text":"[Alice 挖掘回归] ","color":"gold"},{"text":"一次右键跑完：规划 5 项（free/wall/headroom/blocked/buried）+ 执行 2 项（exec_direct/exec_blocked）+ 模组连锁 1 项（exec_chain）。","color":"white"}]
tellraw @s [{"text":"判读","color":"aqua"},{"text":"日志 [MineRegression] SUMMARY 全 PASS；每项细节行含 mode/stand/collected/inventoryDelta/dropsLeft/ticks。约 1~2 分钟。","color":"white"}]
