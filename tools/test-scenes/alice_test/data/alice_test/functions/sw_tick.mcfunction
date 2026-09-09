# 对照计时器：每 tick 累加；到达目标标记（marker）1.5 格内即停表并播报
execute as @a[tag=alice_sw_running] run scoreboard players add @s alice_sw 1
execute as @a[tag=alice_sw_running] at @s if entity @e[type=minecraft:marker,distance=..0.8] run function alice_test:sw_stop
