# 连续行动轨迹记录：开始（可与 contrast_* 配合；每 tick 输出一行 [TRACE]）
scoreboard players set @s alice_tr_t 0
scoreboard players set @s alice_tr_budget 600
tag @s add alice_tr_running
tellraw @s [{"text":"[TRACE] 开始记录，请立刻输入 #goto 命令","color":"gold"}]
