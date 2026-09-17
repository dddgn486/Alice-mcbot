# 对照计时器：**正式开始**（由 sw_tick 在"玩家已离开起点"时调用）
tag @s remove alice_sw_pending
tag @s add alice_sw_running
scoreboard players set @s alice_sw 0
