# 对照计时器：**待发**（2026-09-17 修：不再把"手动输入 #goto 的打字时间"算进去）
# 用法：contrast_* 调它 ⇒ 玩家一**离开起点**才真正开始计时（见 sw_begin / sw_tick）
scoreboard players set @s alice_sw 0
tag @s remove alice_sw_running
tag @s add alice_sw_pending
kill @e[type=minecraft:marker,tag=sw_origin]
execute at @s run summon minecraft:marker ~ ~ ~ {Tags:["sw_origin"]}
