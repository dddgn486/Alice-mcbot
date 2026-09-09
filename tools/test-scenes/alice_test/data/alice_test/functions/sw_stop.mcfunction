# 对照计时器：停表并播报（进入日志，便于 AI 读取）
tag @s remove alice_sw_running
tellraw @s [{"text":"[对照计时] 到达目标，用时 ","color":"gold"},{"text":"","extra":[{"score":{"name":"@s","objective":"alice_sw"}}]},{"text":" tick","color":"gold"}]
execute as @s run say [对照计时] 到达目标
